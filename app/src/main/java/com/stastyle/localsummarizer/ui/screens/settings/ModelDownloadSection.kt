package com.stastyle.localsummarizer.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stastyle.localsummarizer.R
import com.stastyle.localsummarizer.data.models.CatalogModel
import com.stastyle.localsummarizer.data.models.DownloadStatus
import com.stastyle.localsummarizer.data.models.DownloadedModel
import com.stastyle.localsummarizer.data.models.ModelCatalog
import com.stastyle.localsummarizer.data.models.ModelKind
import java.util.Locale

/**
 * One dropdown per model kind: pick a model from the catalog, download it, and
 * see whether the copy on the device is current.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelDownloadPicker(
    kind: ModelKind,
    label: String,
    downloaded: Map<String, DownloadedModel>,
    statuses: Map<String, DownloadStatus>,
    onDownload: (CatalogModel) -> Unit,
    onCancel: (CatalogModel) -> Unit,
    onUse: (CatalogModel) -> Unit,
    onDelete: (CatalogModel) -> Unit,
) {
    val options = remember(kind) { ModelCatalog.forKind(kind) }
    var expanded by remember { mutableStateOf(false) }
    var selected by remember(kind) { mutableStateOf(options.first()) }

    val entry = downloaded[selected.id]
    val status = statuses[selected.id] ?: DownloadStatus.Idle

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = "${selected.displayName} · ${formatBytes(selected.approxBytes)}",
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.settings_choose_from_catalog)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    val installed = downloaded.containsKey(option.id)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    buildString {
                                        append(option.displayName)
                                        append(" · ")
                                        append(formatBytes(option.approxBytes))
                                        if (installed) append("  ✓")
                                    },
                                )
                                Text(
                                    option.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        },
                        onClick = {
                            selected = option
                            expanded = false
                        },
                    )
                }
            }
        }

        Text(
            selected.note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        when (status) {
            is DownloadStatus.Checking -> Text(
                stringResource(R.string.download_checking),
                style = MaterialTheme.typography.bodySmall,
            )
            is DownloadStatus.Running -> Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (status.waitingForWifi) {
                        stringResource(R.string.download_waiting_wifi)
                    } else {
                        stringResource(
                            R.string.download_progress,
                            status.percent,
                            formatBytes(status.bytesDownloaded),
                            formatBytes(status.totalBytes),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { status.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is DownloadStatus.Failed -> Text(
                status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            DownloadStatus.Idle -> if (entry != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.download_installed, formatBytes(entry.sizeBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (entry.updateAvailable) {
                        Text(
                            stringResource(R.string.download_update_available),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status is DownloadStatus.Running || status is DownloadStatus.Checking) {
                TextButton(onClick = { onCancel(selected) }) {
                    Text(stringResource(R.string.download_cancel))
                }
            } else {
                Button(onClick = { onDownload(selected) }) {
                    Text(
                        stringResource(
                            if (entry == null) R.string.download else R.string.download_again,
                        ),
                    )
                }
                if (entry != null) {
                    TextButton(onClick = { onUse(selected) }) {
                        Text(stringResource(R.string.download_use))
                    }
                    TextButton(onClick = { onDelete(selected) }) {
                        Text(stringResource(R.string.download_delete))
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.0f MB", mb)
}
