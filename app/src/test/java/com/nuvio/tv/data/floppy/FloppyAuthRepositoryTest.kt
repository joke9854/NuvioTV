package com.nuvio.tv.data.floppy

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloppyAuthRepositoryTest {
    private fun FloppyTestHarness.routes(
        tokenResponses: MutableList<FloppyHttpResponse> = mutableListOf(),
        info: String = infoBody(),
        device: String = deviceBody()
    ) {
        engine.handler = { request ->
            when (request.path) {
                "/api/v1/info/" -> FloppyHttpResponse(200, info)
                "/o/device-authorization/" -> FloppyHttpResponse(200, device)
                "/o/token/" -> tokenResponses.removeAt(0)
                "/api/v1/sync/connections/" -> FloppyHttpResponse(200, "{}")
                "/o/revoke_token/" -> FloppyHttpResponse(200, "{}")
                else -> FloppyHttpResponse(404, "{}")
            }
        }
    }

    private fun oauthError(code: String, status: Int = 400) = FloppyHttpResponse(status, """{"error":"$code"}""")

    @Test
    fun `device poll goes pending then slow_down then authorized and verifies the token`() = runTest {
        val h = FloppyTestHarness()
        val responses = mutableListOf(
            oauthError("authorization_pending"),
            oauthError("slow_down"),
            FloppyHttpResponse(200, tokenBody("access-new", "refresh-new"))
        )
        h.routes(responses)

        val session = h.auth.startDeviceAuthorization(TEST_SERVER)
        assertEquals("ABCD-EFGH", session.userCode)
        val info = h.engine.requestsTo("/api/v1/info/").single()
        assertNull("capabilities are read without any credential", info.bearer)
        val device = h.engine.requestsTo("/o/device-authorization/").single()
        assertEquals(mapOf("client_id" to "nuvio-tv"), device.form)

        // Polling before the interval has elapsed does not touch the network.
        assertEquals(FloppyDevicePollResult.Pending, h.auth.pollDeviceAuthorization())
        assertEquals(0, h.engine.requestsTo("/o/token/").size)

        h.now += 5_000L
        assertEquals(FloppyDevicePollResult.Pending, h.auth.pollDeviceAuthorization())
        assertEquals(5, h.auth.state.value.session!!.intervalSeconds)
        val tokenForm = h.engine.requestsTo("/o/token/").single().form!!
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", tokenForm["grant_type"])
        assertEquals("dc-secret", tokenForm["device_code"])
        assertEquals("nuvio-tv", tokenForm["client_id"])

        h.now += 5_000L
        assertEquals(FloppyDevicePollResult.Pending, h.auth.pollDeviceAuthorization())
        assertEquals("slow_down adds five seconds", 10, h.auth.state.value.session!!.intervalSeconds)

        h.now += 9_000L
        assertEquals(FloppyDevicePollResult.Pending, h.auth.pollDeviceAuthorization())
        assertFalse(h.auth.state.value.isAuthenticated)

        h.now += 1_000L
        assertEquals(FloppyDevicePollResult.Authorized, h.auth.pollDeviceAuthorization())
        assertTrue(h.auth.state.value.isAuthenticated)
        assertEquals("access-new", h.store.authorization()!!.tokens.accessToken)
        val verify = h.engine.requestsTo("/api/v1/sync/connections/").single()
        assertEquals("access-new", verify.bearer)
        assertEquals(TEST_SERVER, h.store.authorization()!!.server.baseUrl)
        assertEquals(FLOPPY_REQUIRED_EXTENSIONS + "sync_items_v1", h.store.capabilities())
    }

    @Test
    fun `an expired device code ends the session`() = runTest {
        val h = FloppyTestHarness()
        h.routes(mutableListOf(oauthError("expired_token")))
        h.auth.startDeviceAuthorization(TEST_SERVER)
        h.now += 5_000L

        assertEquals(FloppyDevicePollResult.Expired, h.auth.pollDeviceAuthorization())
        assertEquals(FloppyAuthError.CODE_EXPIRED, h.auth.state.value.error)
        assertNull(h.auth.state.value.session)
        assertFalse(h.auth.state.value.isAuthenticated)
    }

    @Test
    fun `a locally elapsed deadline expires without another request`() = runTest {
        val h = FloppyTestHarness()
        h.routes()
        h.auth.startDeviceAuthorization(TEST_SERVER)
        h.now += 901_000L

        assertEquals(FloppyDevicePollResult.Expired, h.auth.pollDeviceAuthorization())
        assertEquals(0, h.engine.requestsTo("/o/token/").size)
    }

    @Test
    fun `a denied authorization ends the session`() = runTest {
        val h = FloppyTestHarness()
        h.routes(mutableListOf(oauthError("access_denied")))
        h.auth.startDeviceAuthorization(TEST_SERVER)
        h.now += 5_000L

        assertEquals(FloppyDevicePollResult.Denied, h.auth.pollDeviceAuthorization())
        assertEquals(FloppyAuthError.ACCESS_DENIED, h.auth.state.value.error)
        assertFalse(h.auth.state.value.isAuthenticated)
    }

    @Test
    fun `a server missing a required extension is refused before any device request`() = runTest {
        val h = FloppyTestHarness()
        h.routes(info = infoBody("sync_history_remove_v1"))

        val failure = expectFloppyFailure<FloppyAuthException> { h.auth.startDeviceAuthorization(TEST_SERVER) }

        assertEquals(FloppyAuthError.SERVER_UNSUPPORTED, failure.error)
        assertEquals(setOf("sync_history_remove_v1"), failure.missingExtensions)
        assertEquals(0, h.engine.requestsTo("/o/device-authorization/").size)
        assertNull(h.auth.state.value.session)
    }

    @Test
    fun `an extension that is present but false does not count`() = runTest {
        val h = FloppyTestHarness()
        h.routes(info = """{"version":"1","api_extensions":{"oauth_v1":true,"scrobble_v2":false,"sync_history_v1":true,"sync_history_remove_v1":true}}""")

        val failure = expectFloppyFailure<FloppyAuthException> { h.auth.startDeviceAuthorization(TEST_SERVER) }

        assertEquals(setOf("scrobble_v2"), failure.missingExtensions)
    }

    @Test
    fun `an invalid server address is refused without a request`() = runTest {
        val h = FloppyTestHarness()
        h.routes()

        val failure = expectFloppyFailure<FloppyAuthException> { h.auth.startDeviceAuthorization("http://floppy.example.com") }

        assertEquals(FloppyAuthError.INVALID_SERVER_ADDRESS, failure.error)
        assertTrue(h.engine.requests.isEmpty())
    }

    @Test
    fun `verification links on the server host are used and others fall back to the device page`() = runTest {
        val same = FloppyTestHarness()
        same.routes(device = deviceBody("$TEST_SERVER/o/device/", "$TEST_SERVER/o/device/?user_code=ABCD-EFGH"))
        val ok = same.auth.startDeviceAuthorization(TEST_SERVER)
        assertEquals("$TEST_SERVER/o/device/", ok.verificationUri)
        assertEquals("$TEST_SERVER/o/device/?user_code=ABCD-EFGH", ok.verificationUriComplete)

        val foreign = FloppyTestHarness()
        foreign.routes(device = deviceBody("https://evil.example.net/o/device/", "https://evil.example.net/o/device/?user_code=ABCD-EFGH"))
        val fallback = foreign.auth.startDeviceAuthorization(TEST_SERVER)
        assertEquals("$TEST_SERVER/o/device/", fallback.verificationUri)
        assertEquals("$TEST_SERVER/o/device/?user_code=ABCD-EFGH", fallback.verificationUriComplete)
    }

    @Test
    fun `refresh rotation persists the new pair and is single flight under concurrency`() = runTest {
        val h = FloppyTestHarness()
        h.connect("access-old", "refresh-old", expiresAtEpochMs = h.now + 60_000L)
        h.engine.handler = { request ->
            when (request.path) {
                "/o/token/" -> {
                    delay(100)
                    FloppyHttpResponse(200, tokenBody("access-new", "refresh-new"))
                }
                else -> FloppyHttpResponse(404, "{}")
            }
        }
        val scope = h.store.scope()

        val results = (1..5).map { async { h.auth.authorization(scope) } }.awaitAll()

        val refreshes = h.engine.requestsTo("/o/token/")
        assertEquals("exactly one refresh runs", 1, refreshes.size)
        assertEquals("refresh_token", refreshes.single().form!!["grant_type"])
        assertEquals("refresh-old", refreshes.single().form!!["refresh_token"])
        assertEquals("nuvio-tv", refreshes.single().form!!["client_id"])
        assertTrue(results.all { it.tokens.accessToken == "access-new" })
        val reloaded = FloppyAuthStore(h.persistence).authorization()!!
        assertEquals("access-new", reloaded.tokens.accessToken)
        assertEquals("refresh-new", reloaded.tokens.refreshToken)
    }

    @Test
    fun `a token with plenty of life left is not refreshed`() = runTest {
        val h = FloppyTestHarness()
        h.connect(expiresAtEpochMs = h.now + 10 * 60_000L)

        val authorization = h.auth.authorization(h.store.scope())

        assertEquals("access-one", authorization.tokens.accessToken)
        assertTrue(h.engine.requests.isEmpty())
    }

    @Test
    fun `a 401 refreshes once and retries with the new token`() = runTest {
        val h = FloppyTestHarness()
        h.connect()
        h.engine.handler = { request ->
            when (request.path) {
                "/o/token/" -> FloppyHttpResponse(200, tokenBody("access-new", "refresh-new"))
                "/api/v1/ping/" ->
                    if (request.bearer == "access-one") FloppyHttpResponse(401, "{}") else FloppyHttpResponse(200, "{}")
                else -> FloppyHttpResponse(404, "{}")
            }
        }

        val used = mutableListOf<String>()
        h.auth.withAuthorization(h.store.scope()) { authorization ->
            used += authorization.tokens.accessToken
            h.http.execute(
                FloppyHttpRequest(FloppyHttpMethod.GET, authorization.server.url("/api/v1/ping/"), bearer = authorization.tokens.accessToken),
                authorization.server
            ).requireSuccess()
        }

        assertEquals(listOf("access-one", "access-new"), used)
        assertEquals(1, h.engine.requestsTo("/o/token/").size)
    }

    @Test
    fun `invalid_grant signs out and the spent refresh token is never sent again`() = runTest {
        val h = FloppyTestHarness()
        h.connect("access-old", "refresh-old", expiresAtEpochMs = h.now + 1_000L)
        h.engine.handler = { FloppyHttpResponse(400, """{"error":"invalid_grant"}""") }
        val scope = h.store.scope()

        val failure = expectFloppyFailure<FloppyAuthException> { h.auth.authorization(scope) }

        assertEquals(FloppyAuthError.AUTHORIZATION_REVOKED, failure.error)
        assertFalse(h.auth.state.value.isAuthenticated)
        assertEquals(FloppyAuthError.AUTHORIZATION_REVOKED, h.auth.state.value.error)
        assertNull(h.store.authorization())
        assertNull(FloppyAuthStore(h.persistence).authorization())

        expectFloppyFailure<FloppyAuthException> { h.auth.authorization(h.store.scope()) }
        assertEquals("no second attempt with the old refresh token", 1, h.engine.requests.size)
    }

    @Test
    fun `a refresh response without a new refresh token is rejected and nothing is replaced`() = runTest {
        val h = FloppyTestHarness()
        h.connect("access-old", "refresh-old", expiresAtEpochMs = h.now + 1_000L)
        h.engine.handler = { FloppyHttpResponse(200, tokenBody("access-new", null)) }

        expectFloppyFailure<FloppyApiError.InvalidResponse> { h.auth.authorization(h.store.scope()) }

        assertEquals("access-old", h.store.authorization()!!.tokens.accessToken)
    }

    @Test
    fun `a server outage during refresh keeps the stored tokens`() = runTest {
        val h = FloppyTestHarness()
        h.connect("access-old", "refresh-old", expiresAtEpochMs = h.now + 1_000L)
        h.engine.handler = { FloppyHttpResponse(503, "") }

        expectFloppyFailure<FloppyApiError.Server> { h.auth.authorization(h.store.scope()) }

        assertEquals("refresh-old", h.store.authorization()!!.tokens.refreshToken)
        assertTrue(h.auth.state.value.isAuthenticated)
    }

    @Test
    fun `starting a session for a different server drops the old tokens and bumps the generation`() = runTest {
        val h = FloppyTestHarness()
        h.connect()
        val before = h.store.scope()
        val other = FloppyServerAddress.parse("https://other.example.com")!!
        val session = FloppyDeviceSession("X", "https://other.example.com/o/device/", "https://other.example.com/o/device/", h.now + 1, 5, h.now)

        assertTrue(h.store.saveSession(other, FLOPPY_REQUIRED_EXTENSIONS, session, "dc", before))

        assertNull(h.store.authorization())
        assertFalse(h.store.isCurrent(before))
        assertEquals("https://other.example.com", h.store.serverAddress()!!.baseUrl)
    }

    @Test
    fun `disconnect revokes best effort and clears credentials even when revocation fails`() = runTest {
        val h = FloppyTestHarness()
        h.connect("access-one", "refresh-one")
        h.engine.handler = { throw java.io.IOException("offline") }

        val revoked = h.auth.disconnect()

        assertFalse(revoked)
        assertNull(h.store.authorization())
        assertFalse(h.auth.state.value.isAuthenticated)
        val revoke = h.engine.requestsTo("/o/revoke_token/").single()
        assertEquals("refresh-one", revoke.form!!["token"])
        assertEquals("nuvio-tv", revoke.form!!["client_id"])
        assertNull(revoke.bearer)
    }

    @Test
    fun `the install id is created once and survives a restart`() {
        val h = FloppyTestHarness()
        val first = h.store.installId()

        assertNotNull(UUIDOrNull(first))
        assertEquals(first, h.store.installId())
        assertEquals(first, FloppyAuthStore(h.persistence).installId())
    }

    @Test
    fun `credential holders never print tokens`() {
        val tokens = FloppyTokens("secret-access", "secret-refresh", 1L)
        val h = FloppyTestHarness()
        h.connect("secret-access", "secret-refresh")

        assertFalse(tokens.toString().contains("secret"))
        assertFalse(h.store.authorization().toString().contains("secret"))
        assertFalse(h.http.toString().contains("secret"))
    }

    @Suppress("FunctionName")
    private fun UUIDOrNull(value: String) = runCatching { java.util.UUID.fromString(value) }.getOrNull()
}
