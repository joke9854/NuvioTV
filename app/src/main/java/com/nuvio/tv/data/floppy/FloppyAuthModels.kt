package com.nuvio.tv.data.floppy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val FLOPPY_CLIENT_ID = "nuvio-tv"

/** Capabilities every Floppy server must advertise in `/api/v1/info/` before we connect. */
val FLOPPY_REQUIRED_EXTENSIONS = setOf("oauth_v1", "scrobble_v2", "sync_history_v1", "sync_history_remove_v1")

enum class FloppyAuthError {
    INVALID_SERVER_ADDRESS,
    SERVER_UNSUPPORTED,
    INVALID_RESPONSE,
    CODE_EXPIRED,
    ACCESS_DENIED,
    AUTHORIZATION_REVOKED
}

class FloppyAuthException(val error: FloppyAuthError, val missingExtensions: Set<String> = emptySet()) :
    Exception(error.name)

data class FloppyAuthScope(val profileId: Int, val generation: Long)

@Serializable
data class FloppyDeviceSession(
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresAtEpochMs: Long,
    val intervalSeconds: Int,
    val nextPollAtEpochMs: Long
) {
    override fun toString(): String = "FloppyDeviceSession(expiresAtEpochMs=$expiresAtEpochMs)"
}

@Serializable
data class FloppyTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long
) {
    override fun toString(): String = "FloppyTokens(expiresAtEpochMs=$expiresAtEpochMs)"
}

data class FloppyAuthState(
    val scope: FloppyAuthScope = FloppyAuthScope(1, 0),
    val isAuthenticated: Boolean = false,
    val serverUrl: String? = null,
    val session: FloppyDeviceSession? = null,
    val error: FloppyAuthError? = null
)

/** Tokens plus the server they belong to. Never log or persist outside the auth store. */
class FloppyAuthorization(
    val scope: FloppyAuthScope,
    val server: FloppyServerAddress,
    val tokens: FloppyTokens
) {
    override fun toString(): String = "FloppyAuthorization(scope=$scope)"
}

sealed interface FloppyDevicePollResult {
    data object Pending : FloppyDevicePollResult
    data object Authorized : FloppyDevicePollResult
    data object Expired : FloppyDevicePollResult
    data object Denied : FloppyDevicePollResult
}

@Serializable
internal data class FloppyStoredAuth(
    val serverUrl: String? = null,
    val capabilities: Set<String> = emptySet(),
    val tokens: FloppyTokens? = null,
    val session: FloppyDeviceSession? = null,
    val deviceCode: String? = null
) {
    override fun toString(): String = "FloppyStoredAuth()"
}

@Serializable
internal data class FloppyDeviceResponse(
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Int = 5
)

@Serializable
internal data class FloppyTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null
)
