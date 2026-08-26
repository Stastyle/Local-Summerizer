package com.stastyle.localsummarizer.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.stastyle.localsummarizer.BuildConfig
import com.stastyle.localsummarizer.R
import com.stastyle.localsummarizer.data.update.UpdateState

/**
 * Updates the app itself from the rolling GitHub release. The repository is
 * private, so a token is offered for the in-app path and the release page is
 * always available as a fallback that uses the browser's GitHub session.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppUpdateSection(
    state: UpdateState,
    savedToken: String,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onSaveToken: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.app_update_section),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(
                R.string.app_update_installed,
                BuildConfig.GIT_SHA.take(12).ifBlank {
                    stringResource(R.string.app_update_unknown_build)
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        when (state) {
            UpdateState.Idle -> Unit
            UpdateState.Checking -> Text(
                stringResource(R.string.app_update_checking),
                style = MaterialTheme.typography.bodySmall,
            )
            UpdateState.UpToDate -> Text(
                stringResource(R.string.app_update_up_to_date),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            is UpdateState.Available -> Text(
                stringResource(
                    R.string.app_update_available,
                    state.remote.commit.take(12),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            is UpdateState.Downloading -> Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.app_update_downloading, state.percent),
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is UpdateState.ReadyToInstall -> Text(
                stringResource(R.string.app_update_install),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            is UpdateState.Failed -> Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (state.needsAuth) {
                        stringResource(R.string.app_update_needs_token)
                    } else {
                        state.message
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is UpdateState.Available -> Button(onClick = onDownload) {
                    Text(stringResource(R.string.app_update_download))
                }
                is UpdateState.ReadyToInstall -> Button(onClick = onInstall) {
                    Text(stringResource(R.string.app_update_install))
                }
                is UpdateState.Downloading -> Unit
                else -> Button(
                    onClick = onCheck,
                    enabled = state !is UpdateState.Checking,
                ) {
                    Text(stringResource(R.string.app_update_check))
                }
            }
            TextButton(onClick = onOpenReleasePage) {
                Text(stringResource(R.string.app_update_open_page))
            }
        }

        var token by remember(savedToken) { mutableStateOf(savedToken) }
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text(stringResource(R.string.app_update_token)) },
        )
        Text(
            stringResource(R.string.app_update_token_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        TextButton(
            onClick = { onSaveToken(token) },
            enabled = token != savedToken,
        ) {
            Text(stringResource(R.string.settings_save))
        }
    }
}
