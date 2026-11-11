package com.nuvio.tv.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.floppy.FloppyApiError
import com.nuvio.tv.data.floppy.FloppyAuthError
import com.nuvio.tv.data.floppy.FloppyAuthException
import com.nuvio.tv.data.floppy.FloppyAuthRepository
import com.nuvio.tv.data.floppy.FloppyAuthScope
import com.nuvio.tv.data.floppy.FloppyAuthStore
import com.nuvio.tv.data.floppy.FloppyDevicePollResult
import com.nuvio.tv.data.floppy.FloppyDeviceSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class FloppyTrackerUiState(
    val isConnected: Boolean = false,
    val serverHost: String? = null,
    val session: FloppyDeviceSession? = null,
    val isLoading: Boolean = false,
    val isPolling: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

private data class FloppyTrackerAction(
    val scope: FloppyAuthScope? = null,
    val loading: Boolean = false,
    val polling: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class FloppyTrackerViewModel @Inject constructor(
    private val auth: FloppyAuthRepository,
    private val authStore: FloppyAuthStore,
    private val profiles: ProfileManager,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val action = MutableStateFlow(FloppyTrackerAction())
    private var connectionJob: Job? = null
    private var disconnectJob: Job? = null

    val uiState = combine(authStore.state, action, profiles.activeProfileId) { authorization, action, profileId ->
        val current = authorization.scope.profileId == profileId
        val transient = action.takeIf { it.scope == authorization.scope && current } ?: FloppyTrackerAction()
        val connected = current && authorization.isAuthenticated
        FloppyTrackerUiState(
            isConnected = connected,
            serverHost = authorization.serverUrl?.let { url ->
                url.substringAfter("://").trimEnd('/')
            }.takeIf { current },
            session = authorization.session.takeIf { current },
            isLoading = transient.loading,
            isPolling = transient.polling,
            statusMessage = if (current && authorization.session != null) {
                context.getString(R.string.floppy_status_enter_code)
            } else null,
            errorMessage = transient.error ?: authorization.error?.takeIf { current }?.userMessage()
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FloppyTrackerUiState())

    init {
        var previousProfile = profiles.activeProfileId.value
        viewModelScope.launch {
            profiles.activeProfileId.collect { profileId ->
                if (profileId != previousProfile) {
                    connectionJob?.cancel()
                    action.value = FloppyTrackerAction()
                    previousProfile = profileId
                }
            }
        }
    }

    /** Starts a device session for [serverInput] (or resumes a still-valid one) and polls until it ends. */
    fun onConnect(serverInput: String) {
        if (connectionJob?.isActive == true || disconnectJob?.isActive == true) return
        val requestedScope = authStore.scope()
        if (requestedScope.profileId != profiles.activeProfileId.value || authStore.state.value.isAuthenticated) return
        action.value = FloppyTrackerAction(requestedScope, loading = true)
        connectionJob = viewModelScope.launch(Dispatchers.IO) {
            var scope = requestedScope
            try {
                authStore.checkScope(scope)
                val session = authStore.state.value.session
                if (session == null || session.expiresAtEpochMs <= System.currentTimeMillis()) {
                    auth.startDeviceAuthorization(serverInput, scope)
                }
                scope = authStore.scope()
                if (scope.profileId != requestedScope.profileId || scope.profileId != profiles.activeProfileId.value) return@launch
                action.value = FloppyTrackerAction(scope, polling = true)
                while (isActive && authStore.isCurrent(scope) && scope.profileId == profiles.activeProfileId.value) {
                    val pending = authStore.state.value.session ?: break
                    delay((pending.nextPollAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0L))
                    if (scope.profileId != profiles.activeProfileId.value) break
                    when (auth.pollDeviceAuthorization(scope)) {
                        FloppyDevicePollResult.Pending -> Unit
                        FloppyDevicePollResult.Authorized -> {
                            action.value = FloppyTrackerAction(authStore.scope())
                            break
                        }
                        FloppyDevicePollResult.Expired, FloppyDevicePollResult.Denied -> break
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (authStore.isCurrent(scope)) action.value = FloppyTrackerAction(scope, error = error.messageForUser())
            } finally {
                val current = action.value
                if (current.scope == scope) action.value = current.copy(loading = false, polling = false)
            }
        }
    }

    fun onRetryPolling() = onConnect("")

    fun onCancel() {
        connectionJob?.cancel()
        val scope = authStore.scope()
        action.value = FloppyTrackerAction()
        viewModelScope.launch(Dispatchers.IO) { authStore.cancelSession(scope = scope) }
    }

    fun onDisconnect() {
        if (disconnectJob?.isActive == true) return
        connectionJob?.cancel()
        val profileId = profiles.activeProfileId.value
        val requestedScope = authStore.scope()
        if (requestedScope.profileId != profileId) return
        action.value = FloppyTrackerAction(requestedScope, loading = true)
        disconnectJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (profiles.activeProfileId.value != profileId) return@launch
                val revoked = auth.disconnect(requestedScope)
                if (profiles.activeProfileId.value == profileId) action.value = FloppyTrackerAction(
                    authStore.scope(), error = if (revoked) null else context.getString(R.string.floppy_revoke_failed)
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (profiles.activeProfileId.value == profileId) {
                    action.value = FloppyTrackerAction(authStore.scope(), error = error.messageForUser())
                }
            }
        }
    }

    private fun Exception.messageForUser(): String = when (this) {
        is FloppyAuthException -> error.userMessage()
        is FloppyApiError -> userMessage()
        else -> context.getString(R.string.floppy_error_invalid_response)
    }

    private fun FloppyAuthError.userMessage(): String = context.getString(when (this) {
        FloppyAuthError.INVALID_SERVER_ADDRESS -> R.string.floppy_error_invalid_address
        FloppyAuthError.SERVER_UNSUPPORTED -> R.string.floppy_error_unsupported
        FloppyAuthError.INVALID_RESPONSE -> R.string.floppy_error_invalid_response
        FloppyAuthError.CODE_EXPIRED -> R.string.floppy_error_code_expired
        FloppyAuthError.ACCESS_DENIED -> R.string.floppy_error_access_denied
        FloppyAuthError.AUTHORIZATION_REVOKED -> R.string.floppy_error_revoked
    })

    private fun FloppyApiError.userMessage(): String = context.getString(when (this) {
        is FloppyApiError.Unauthorized -> R.string.floppy_error_unauthorized
        is FloppyApiError.Forbidden -> R.string.floppy_error_forbidden
        is FloppyApiError.NotFound -> R.string.floppy_error_not_found
        is FloppyApiError.Conflict -> R.string.floppy_error_conflict
        is FloppyApiError.RateLimited -> R.string.floppy_error_rate_limited
        is FloppyApiError.Server -> R.string.floppy_error_server
        is FloppyApiError.Redirect -> R.string.floppy_error_redirect
        is FloppyApiError.Network -> R.string.floppy_error_network
        is FloppyApiError.InvalidResponse -> R.string.floppy_error_invalid_response
        is FloppyApiError.Rejected -> R.string.floppy_error_rejected
    })
}
