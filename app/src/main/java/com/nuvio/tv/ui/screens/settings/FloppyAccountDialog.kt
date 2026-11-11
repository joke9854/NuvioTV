package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun FloppyAccountDialog(
    state: FloppyTrackerUiState,
    onStartConnection: (String) -> Unit,
    onRetryPolling: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val logo = rememberVectorPainter(Icons.Default.Dns)
    if (state.isConnected) {
        NuvioDialog(
            onDismiss = onDismiss,
            title = stringResource(R.string.floppy_name),
            subtitle = stringResource(R.string.floppy_connected_to, state.serverHost.orEmpty()),
            width = 720.dp,
            suppressFirstKeyUp = false
        ) {
            Text(
                text = stringResource(R.string.floppy_tracking_description),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
            state.errorMessage?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.Error)
            }
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
                SettingsDialogActionButton(
                    text = stringResource(R.string.trakt_disconnect),
                    onClick = onDisconnect,
                    enabled = !state.isLoading,
                    primary = true
                )
            }
        }
        return
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = "",
        width = 720.dp,
        titleTextAlign = TextAlign.Center,
        suppressFirstKeyUp = false
    ) {
        if (state.session == null && !state.isLoading) {
            var serverInput by remember { mutableStateOf("") }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.floppy_name),
                    color = NuvioTheme.colors.TextPrimary,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = stringResource(R.string.floppy_server_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                InputField(
                    value = serverInput,
                    onValueChange = { serverInput = it },
                    placeholder = stringResource(R.string.floppy_server_hint),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    onImeAction = { if (serverInput.isNotBlank()) onStartConnection(serverInput) },
                    modifier = Modifier.fillMaxWidth()
                )
                state.errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.Error,
                        textAlign = TextAlign.Center
                    )
                }
            }
            SettingsDialogActionRow(horizontalAlignment = Alignment.CenterHorizontally) {
                SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
                SettingsDialogActionButton(
                    text = stringResource(R.string.floppy_connect),
                    onClick = { onStartConnection(serverInput) },
                    primary = true,
                    enabled = serverInput.isNotBlank()
                )
            }
        } else {
            TrackingDeviceAuthContent(
                providerName = stringResource(R.string.floppy_name),
                logo = logo,
                logoContentDescription = stringResource(R.string.floppy_name),
                logoLabel = stringResource(R.string.floppy_name),
                logoTint = Color.White,
                qrContentDescription = stringResource(R.string.floppy_qr_description),
                instruction = stringResource(R.string.floppy_awaiting_instruction),
                userCode = state.session?.userCode,
                displayUrl = state.session?.verificationUri,
                qrUrl = state.session?.verificationUriComplete,
                expiresAtEpochMs = state.session?.expiresAtEpochMs,
                isLoading = state.isLoading,
                isPolling = state.isPolling,
                credentialsConfigured = true,
                statusMessage = state.statusMessage,
                errorMessage = state.errorMessage,
                missingCredentialsMessage = "",
                onStartConnection = onRetryPolling,
                onRetryPolling = onRetryPolling,
                onDismiss = onDismiss
            )
        }
    }
}
