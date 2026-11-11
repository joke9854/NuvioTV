package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface FloppyAuthPersistence {
    fun read(profileId: Int): String?
    fun write(profileId: Int, value: String?)
    fun clear()
    fun readInstallId(): String?
    fun writeInstallId(value: String)
}

class FloppyAuthStore(
    private val persistence: FloppyAuthPersistence,
    initialProfileId: Int = 1
) : ProfileScopedCredentialStore {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var currentScope = FloppyAuthScope(initialProfileId, 0)
    private var stored = load(initialProfileId)
    private var cachedInstallId: String? = null
    private val mutableState = MutableStateFlow(stateFor())

    val state: StateFlow<FloppyAuthState> = mutableState.asStateFlow()

    /** Random id created once per install; used to build event ids. Not a secret. */
    fun installId(): String = synchronized(lock) {
        cachedInstallId ?: (persistence.readInstallId()?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString().also(persistence::writeInstallId)).also { cachedInstallId = it }
    }

    fun scope(): FloppyAuthScope = synchronized(lock) { currentScope }

    fun isCurrent(scope: FloppyAuthScope): Boolean = synchronized(lock) { currentScope == scope }

    fun checkScope(scope: FloppyAuthScope) {
        if (!isCurrent(scope)) throw CancellationException("Floppy account changed")
    }

    fun authorization(): FloppyAuthorization? = synchronized(lock) {
        val tokens = stored.tokens ?: return@synchronized null
        val server = stored.serverUrl?.let(FloppyServerAddress::parse) ?: return@synchronized null
        FloppyAuthorization(currentScope, server, tokens)
    }

    fun serverAddress(): FloppyServerAddress? = synchronized(lock) { stored.serverUrl?.let(FloppyServerAddress::parse) }

    fun capabilities(): Set<String> = synchronized(lock) { stored.capabilities }

    fun deviceCode(scope: FloppyAuthScope): String? = synchronized(lock) {
        stored.deviceCode.takeIf { currentScope == scope }
    }

    fun selectProfile(profileId: Int) = synchronized(lock) {
        if (currentScope.profileId == profileId) return@synchronized
        val value = load(profileId)
        currentScope = FloppyAuthScope(profileId, currentScope.generation + 1)
        stored = value
        publish()
    }

    /**
     * Starts a device session for [server]. Any previous tokens or session are dropped and the generation
     * advances, so work that began against another server (or another sign-in) is cancelled.
     */
    fun saveSession(
        server: FloppyServerAddress,
        capabilities: Set<String>,
        session: FloppyDeviceSession,
        deviceCode: String,
        scope: FloppyAuthScope
    ): Boolean = mutate(scope, advanceGeneration = true) {
        FloppyStoredAuth(server.baseUrl, capabilities, session = session, deviceCode = deviceCode)
    }

    fun updateSession(session: FloppyDeviceSession, scope: FloppyAuthScope): Boolean =
        mutate(scope) { it.copy(session = session) }

    fun cancelSession(error: FloppyAuthError? = null, scope: FloppyAuthScope = scope()): Boolean =
        mutate(scope, advanceGeneration = true, error = error) { it.copy(session = null, deviceCode = null) }

    fun authorize(tokens: FloppyTokens, scope: FloppyAuthScope): Boolean =
        mutate(scope, advanceGeneration = true) {
            FloppyStoredAuth(it.serverUrl, it.capabilities, tokens = tokens)
        }

    /** Replaces rotated tokens, but only when [expected] is still the stored pair. */
    fun refreshTokens(tokens: FloppyTokens, expected: FloppyAuthorization): Boolean = synchronized(lock) {
        if (stored.tokens?.accessToken != expected.tokens.accessToken) return@synchronized false
        mutate(expected.scope) { it.copy(tokens = tokens) }
    }

    fun clearAuth(
        scope: FloppyAuthScope = scope(),
        error: FloppyAuthError? = null,
        expectedAccessToken: String? = null
    ): Boolean = synchronized(lock) {
        if (expectedAccessToken != null && stored.tokens?.accessToken != expectedAccessToken) {
            return@synchronized false
        }
        mutate(scope, advanceGeneration = true, error = error) { FloppyStoredAuth() }
    }

    override fun removeProfile(profileId: Int) = synchronized(lock) {
        persistence.write(profileId, null)
        if (currentScope.profileId == profileId) {
            currentScope = currentScope.copy(generation = currentScope.generation + 1)
            stored = FloppyStoredAuth()
            publish()
        }
    }

    override fun clearAllProfiles() = synchronized(lock) {
        persistence.clear()
        currentScope = currentScope.copy(generation = currentScope.generation + 1)
        stored = FloppyStoredAuth()
        publish()
    }

    private fun mutate(
        scope: FloppyAuthScope,
        advanceGeneration: Boolean = false,
        error: FloppyAuthError? = null,
        transform: (FloppyStoredAuth) -> FloppyStoredAuth
    ): Boolean = synchronized(lock) {
        if (currentScope != scope) return@synchronized false
        val value = transform(stored)
        persistence.write(scope.profileId, json.encodeToString(value))
        stored = value
        if (advanceGeneration) currentScope = currentScope.copy(generation = currentScope.generation + 1)
        publish(error)
        true
    }

    private fun load(profileId: Int): FloppyStoredAuth = persistence.read(profileId)?.let { value ->
        runCatching { json.decodeFromString<FloppyStoredAuth>(value) }.getOrNull()
    }?.takeIf { value ->
        value.tokens == null || (value.tokens.accessToken.isNotBlank() && value.tokens.refreshToken.isNotBlank() &&
            value.serverUrl?.let(FloppyServerAddress::parse) != null)
    } ?: FloppyStoredAuth()

    private fun publish(error: FloppyAuthError? = null) {
        mutableState.value = stateFor(error)
    }

    private fun stateFor(error: FloppyAuthError? = null) = FloppyAuthState(
        scope = currentScope,
        isAuthenticated = stored.tokens != null,
        serverUrl = stored.serverUrl,
        session = stored.session,
        error = error
    )
}
