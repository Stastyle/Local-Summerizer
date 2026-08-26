package com.stastyle.localsummarizer.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.stastyle.localsummarizer.R
import com.stastyle.localsummarizer.diagnostics.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A sideloaded app cannot be granted READ_LOGS, so the user has no way to read
 * logcat without wiring up wireless debugging. This puts the same information
 * one tap away instead: engine state, what the last run did, and the tail of
 * ggml's own log — copyable as plain text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsSection(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.diagnostics_section),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.diagnostics_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !running,
                onClick = {
                    running = true
                    scope.launch {
                        // Loads the native library and dlopens the CPU
                        // variants; too slow for the main thread.
                        report = withContext(Dispatchers.IO) { Diagnostics.report(context) }
                        running = false
                    }
                },
            ) {
                Text(stringResource(R.string.diagnostics_run))
            }
            report?.let { text ->
                OutlinedButton(onClick = {
                    copyToClipboard(context, text)
                    onMessage(context.getString(R.string.copied_to_clipboard))
                }) {
                    Text(stringResource(R.string.copy))
                }
                OutlinedButton(onClick = { shareText(context, text) }) {
                    Text(stringResource(R.string.share))
                }
            }
        }

        report?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("diagnostics", text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}
