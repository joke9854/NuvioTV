package com.nuvio.tv.data.floppy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val REFRESH_LEEWAY_MS = 5 * 60_000L
private const val MAX_INTERVAL_SECONDS = 3_600

class FloppyAuthRepository(
    private val http: FloppyHttpClient,
    private val store: FloppyAuthStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val deviceMutex = Mutex()
    private val refreshMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    val state = store.state

    /**
     * Checks the server's advertised capabilities before any credential exists, then asks for a device code.
     * The capability map, never the version string, decides whether the server is supported.
     */
    suspend fun startDeviceAuthorization(
        serverInput: String,
        scope: FloppyAuthScope = store.scope()
    ): FloppyDeviceSession = deviceMutex.withLock {
        store.checkScope(scope)
        check(!state.value.isAuthenticated)
        val server = FloppyServerAddress.parse(serverInput)
            ?: throw FloppyAuthException(FloppyAuthError.INVALID_SERVER_ADDRESS)
        val capabilities = fetchCapabilities(server)
        store.checkScope(scope)
        val missing = FLOPPY_REQUIRED_EXTENSIONS - capabilities
        if (missing.isNotEmpty()) throw FloppyAuthException(FloppyAuthError.SERVER_UNSUPPORTED, missing)

        val response = oauth(server, "/o/device-authorization/", emptyMap()).requireSuccess()
        store.checkScope(scope)
        val payload = decode<FloppyDeviceResponse>(response.body)
        val deviceCode = payload.deviceCode?.takeIf(String::isNotBlank) ?: invalidResponse()
        val userCode = payload.userCode?.takeIf(String::isNotBlank) ?: invalidResponse()
        val fallback = server.url("/o/device/")
        val uri = sameHostUrl(payload.verificationUri, server) ?: fallback
        val complete = sameHostUrl(payload.verificationUriComplete, server)
            ?: fallback.toHttpUrlOrNull()!!.newBuilder().addQueryParameter("user_code", userCode).build().toString()
        val interval = payload.interval.takeIf { it in 1..MAX_INTERVAL_SECONDS } ?: invalidResponse()
        val now = nowEpochMs()
        val session = FloppyDeviceSession(
            userCode = userCode,
            verificationUri = uri,
            verificationUriComplete = complete,
            expiresAtEpochMs = expiresAt(payload.expiresIn, now),
            intervalSeconds = interval,
            nextPollAtEpochMs = now + interval * 1_000L
        )
        if (!store.saveSession(server, capabilities, session, deviceCode, scope)) scopeChanged()
        session
    }

    suspend fun pollDeviceAuthorization(scope: FloppyAuthScope = store.scope()): FloppyDevicePollResult =
        deviceMutex.withLock {
            store.checkScope(scope)
            val session = state.value.session ?: scopeChanged()
            val server = store.serverAddress() ?: scopeChanged()
            val now = nowEpochMs()
            if (now >= session.expiresAtEpochMs) {
                store.cancelSession(FloppyAuthError.CODE_EXPIRED, scope)
                return@withLock FloppyDevicePollResult.Expired
            }
            if (now < session.nextPollAtEpochMs) return@withLock FloppyDevicePollResult.Pending
            val deviceCode = store.deviceCode(scope) ?: scopeChanged()
            if (!store.updateSession(session.copy(nextPollAtEpochMs = now + session.intervalSeconds * 1_000L), scope)) {
                scopeChanged()
            }
            val response = oauth(
                server,
                "/o/token/",
                mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to deviceCode)
            )
            store.checkScope(scope)
            if (response.isSuccess) {
                val tokens = tokensFrom(response, previousRefreshToken = null)
                verifyToken(server, tokens)
                if (!store.authorize(tokens, scope)) scopeChanged()
                return@withLock FloppyDevicePollResult.Authorized
            }
            when (response.errorCode()) {
                "authorization_pending" -> FloppyDevicePollResult.Pending
                "slow_down" -> {
                    val interval = (session.intervalSeconds + 5).coerceAtMost(MAX_INTERVAL_SECONDS)
                    store.updateSession(
                        session.copy(intervalSeconds = interval, nextPollAtEpochMs = nowEpochMs() + interval * 1_000L),
                        scope
                    )
                    FloppyDevicePollResult.Pending
                }
                "access_denied" -> {
                    store.cancelSession(FloppyAuthError.ACCESS_DENIED, scope)
                    FloppyDevicePollResult.Denied
                }
                "expired_token" -> {
                    store.cancelSession(FloppyAuthError.CODE_EXPIRED, scope)
                    FloppyDevicePollResult.Expired
                }
                else -> {
                    response.requireSuccess()
                    invalidResponse()
                }
            }
        }

    fun cancelDeviceAuthorization() {
        store.cancelSession()
    }

    /**
     * Returns usable tokens, refreshing at most once at a time. A refresh happens when the access token is
     * within five minutes of expiry, or when [rejectedAccessToken] was just refused by the server with a 401
     * (and has not already been replaced by another caller).
     */
    suspend fun authorization(
        scope: FloppyAuthScope,
        rejectedAccessToken: String? = null
    ): FloppyAuthorization = refreshMutex.withLock {
        store.checkScope(scope)
        val current = store.authorization() ?: throw FloppyAuthException(FloppyAuthError.AUTHORIZATION_REVOKED)
        if (rejectedAccessToken != null && current.tokens.accessToken != rejectedAccessToken) return@withLock current
        if (rejectedAccessToken == null && current.tokens.expiresAtEpochMs - nowEpochMs() > REFRESH_LEEWAY_MS) {
            return@withLock current
        }
        val response = oauth(
            current.server,
            "/o/token/",
            mapOf("grant_type" to "refresh_token", "refresh_token" to current.tokens.refreshToken)
        )
        store.checkScope(scope)
        if (!response.isSuccess) {
            // The refresh token is single-use. Once the server says it is spent, it is never sent again.
            if (response.errorCode() == "invalid_grant") {
                store.clearAuth(scope, FloppyAuthError.AUTHORIZATION_REVOKED, current.tokens.accessToken)
                throw FloppyAuthException(FloppyAuthError.AUTHORIZATION_REVOKED)
            }
            response.requireSuccess()
        }
        val tokens = tokensFrom(response, previousRefreshToken = null)
        // Persisted before it is used; a failed write throws and the new pair is never handed out.
        if (!store.refreshTokens(tokens, current)) scopeChanged()
        FloppyAuthorization(scope, current.server, tokens)
    }

    /** Runs [block] with a valid access token, refreshing and retrying once if the server answers 401. */
    suspend fun <T> withAuthorization(
        scope: FloppyAuthScope,
        block: suspend (FloppyAuthorization) -> T
    ): T {
        val first = authorization(scope)
        return try {
            block(first)
        } catch (_: FloppyApiError.Unauthorized) {
            block(authorization(scope, rejectedAccessToken = first.tokens.accessToken))
        }
    }

    /** Clears local credentials first, then asks the server to revoke the grant. Returns whether it did. */
    suspend fun disconnect(scope: FloppyAuthScope = store.scope()): Boolean {
        store.checkScope(scope)
        val current = store.authorization()
        if (current != null && current.scope != scope) scopeChanged()
        if (!store.clearAuth(scope)) scopeChanged()
        if (current == null) return true
        return try {
            oauth(
                current.server,
                "/o/revoke_token/",
                mapOf("token" to current.tokens.refreshToken, "token_type_hint" to "refresh_token")
            ).isSuccess
        } catch (error: CancellationException) {
            throw error
        } catch (_: FloppyApiError) {
            false
        }
    }

    private suspend fun fetchCapabilities(server: FloppyServerAddress): Set<String> {
        val response = http.execute(
            FloppyHttpRequest(FloppyHttpMethod.GET, server.url("/api/v1/info/")), server
        ).requireSuccess()
        val extensions = runCatching {
            json.parseToJsonElement(response.body).jsonObject["api_extensions"]?.jsonObject
        }.getOrNull() ?: invalidResponse()
        return extensions.filterValues { value ->
            (value as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() } == true
        }.keys
    }

    /** Checks the token against a low-scope endpoint before it is stored. */
    private suspend fun verifyToken(server: FloppyServerAddress, tokens: FloppyTokens) {
        try {
            http.execute(
                FloppyHttpRequest(
                    FloppyHttpMethod.GET, server.url("/api/v1/sync/connections/"), bearer = tokens.accessToken
                ),
                server
            ).requireSuccess()
        } catch (error: CancellationException) {
            throw error
        } catch (error: FloppyApiError) {
            // The code is already spent; do not leave a live grant behind that nothing knows about.
            runCatching {
                oauth(server, "/o/revoke_token/", mapOf("token" to tokens.refreshToken, "token_type_hint" to "refresh_token"))
            }
            store.cancelSession()
            throw error
        }
    }

    private suspend fun oauth(server: FloppyServerAddress, path: String, fields: Map<String, String>) =
        http.execute(
            FloppyHttpRequest(
                method = FloppyHttpMethod.POST,
                url = server.url(path),
                form = fields + ("client_id" to FLOPPY_CLIENT_ID)
            ),
            server
        )

    private fun tokensFrom(response: FloppyHttpResponse, previousRefreshToken: String?): FloppyTokens {
        val payload = decode<FloppyTokenResponse>(response.body)
        val accessToken = payload.accessToken?.takeIf(String::isNotBlank) ?: invalidResponse()
        // Refresh tokens rotate, so a response without a new one cannot be used.
        val refreshToken = payload.refreshToken?.takeIf(String::isNotBlank) ?: previousRefreshToken ?: invalidResponse()
        if (!payload.tokenType.equals("Bearer", ignoreCase = true)) invalidResponse()
        return FloppyTokens(accessToken, refreshToken, expiresAt(payload.expiresIn, nowEpochMs()))
    }

    private fun sameHostUrl(value: String?, server: FloppyServerAddress): String? = value
        ?.toHttpUrlOrNull()
        ?.takeIf { it.host == server.host && it.username.isEmpty() && it.password.isEmpty() }
        ?.toString()

    private fun expiresAt(seconds: Long?, now: Long): Long = seconds
        ?.takeIf { it > 0L && it <= (Long.MAX_VALUE - now) / 1_000L }
        ?.let { now + it * 1_000L } ?: invalidResponse()

    private fun FloppyHttpResponse.errorCode(): String? = runCatching {
        (json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.matches(Regex("[a-z_]{1,64}")) }

    private inline fun <reified T> decode(body: String): T =
        runCatching { json.decodeFromString<T>(body) }.getOrElse { invalidResponse() }

    private fun invalidResponse(): Nothing = throw FloppyApiError.InvalidResponse()
    private fun scopeChanged(): Nothing = throw CancellationException("Floppy account changed")
}
