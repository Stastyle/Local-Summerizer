package com.stastyle.localsummarizer.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.localsummarizer.R
import com.stastyle.localsummarizer.data.models.ModelKind
import com.stastyle.localsummarizer.data.settings.DEFAULT_MASTER_PROMPT
import com.stastyle.localsummarizer.ui.AppViewModelProvider
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val downloadedModels by viewModel.downloadedModels.collectAsStateWithLifecycle()
    val downloadStatus by viewModel.downloadStatus.collectAsStateWithLifecycle()
    val checkingUpdates by viewModel.checkingUpdates.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var wifiOnly by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val whisperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::onWhisperModelPicked) }
    val llamaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::onLlamaModelPicked) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Models section
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_models_section),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ModelPickerRow(
                        label = stringResource(R.string.settings_whisper_model),
                        fileName = settings.whisperModelName,
                        onPick = { whisperPicker.launch(arrayOf("*/*")) },
                        onClear = viewModel::clearWhisperModel,
                    )
                    ModelPickerRow(
                        label = stringResource(R.string.settings_llama_model),
                        fileName = settings.llamaModelName,
                        onPick = { llamaPicker.launch(arrayOf("*/*")) },
                        onClear = viewModel::clearLlamaModel,
                    )
                }
            }

            // Download section
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.settings_download_section),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = viewModel::checkForUpdates,
                            enabled = !checkingUpdates,
                        ) {
                            Text(stringResource(R.string.check_updates))
                        }
                    }

                    ModelDownloadPicker(
                        kind = ModelKind.WHISPER,
                        label = stringResource(R.string.settings_whisper_model),
                        downloaded = downloadedModels,
                        statuses = downloadStatus,
                        onDownload = { viewModel.startDownload(it, allowMetered = !wifiOnly) },
                        onCancel = viewModel::cancelDownload,
                        onUse = viewModel::useDownloaded,
                        onDelete = viewModel::deleteDownload,
                    )

                    HorizontalDivider()

                    ModelDownloadPicker(
                        kind = ModelKind.LLM,
                        label = stringResource(R.string.settings_llama_model),
                        downloaded = downloadedModels,
                        statuses = downloadStatus,
                        onDownload = { viewModel.startDownload(it, allowMetered = !wifiOnly) },
                        onCancel = viewModel::cancelDownload,
                        onUse = viewModel::useDownloaded,
                        onDelete = viewModel::deleteDownload,
                    )

                    HorizontalDivider()

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.download_wifi_only),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.download_wifi_only_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }

            // Master prompt section
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_prompt_section),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    var promptDraft by remember(settings.masterPrompt) {
                        mutableStateOf(settings.masterPrompt)
                    }
                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = { promptDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp),
                        label = { Text(stringResource(R.string.settings_prompt_hint)) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { viewModel.setMasterPrompt(promptDraft) },
                            enabled = promptDraft != settings.masterPrompt,
                        ) {
                            Text(stringResource(R.string.settings_save))
                        }
                        TextButton(
                            onClick = {
                                promptDraft = DEFAULT_MASTER_PROMPT
                                viewModel.setMasterPrompt(DEFAULT_MASTER_PROMPT)
                            },
                        ) {
                            Text(stringResource(R.string.settings_reset_prompt))
                        }
                    }
                }
            }

            // Advanced section
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_advanced_section),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    Text(
                        stringResource(R.string.settings_language),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.language == "he",
                            onClick = { viewModel.setLanguage("he") },
                            label = { Text(stringResource(R.string.settings_language_hebrew)) },
                        )
                        FilterChip(
                            selected = settings.language == "auto",
                            onClick = { viewModel.setLanguage("auto") },
                            label = { Text(stringResource(R.string.settings_language_auto)) },
                        )
                        FilterChip(
                            selected = settings.language == "en",
                            onClick = { viewModel.setLanguage("en") },
                            label = { Text(stringResource(R.string.settings_language_english)) },
                        )
                    }

                    SliderSetting(
                        label = stringResource(R.string.settings_threads) + ": ${settings.threads}",
                        value = settings.threads.toFloat(),
                        valueRange = 0f..8f,
                        steps = 7,
                        onChangeFinished = { viewModel.setThreads(it.roundToInt()) },
                    )
                    SliderSetting(
                        label = stringResource(R.string.settings_context_size) +
                            ": ${settings.contextSize}",
                        value = contextSizeToIndex(settings.contextSize),
                        valueRange = 0f..3f,
                        steps = 2,
                        onChangeFinished = { viewModel.setContextSize(indexToContextSize(it)) },
                    )
                    SliderSetting(
                        label = stringResource(R.string.settings_max_tokens) +
                            ": ${settings.maxTokens}",
                        value = settings.maxTokens.toFloat(),
                        valueRange = 256f..4096f,
                        steps = 14,
                        onChangeFinished = { viewModel.setMaxTokens(it.roundToInt()) },
                    )
                    SliderSetting(
                        label = stringResource(R.string.settings_temperature) +
                            ": ${"%.2f".format(settings.temperature)}",
                        value = settings.temperature,
                        valueRange = 0f..1f,
                        steps = 19,
                        onChangeFinished = { viewModel.setTemperature(it) },
                    )
                }
            }

            // App update section
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    AppUpdateSection(
                        state = updateState,
                        savedToken = settings.githubToken,
                        onCheck = viewModel::checkForAppUpdate,
                        onDownload = viewModel::downloadUpdate,
                        onInstall = {
                            viewModel.installIntent()?.let { intent ->
                                runCatching { context.startActivity(intent) }
                            }
                        },
                        onOpenReleasePage = {
                            runCatching { context.startActivity(viewModel.releasePageIntent()) }
                        },
                        onSaveToken = viewModel::setGithubToken,
                    )
                }
            }

            // Diagnostics section
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    DiagnosticsSection(
                        onMessage = { message ->
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPickerRow(
    label: String,
    fileName: String,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = fileName.ifBlank { stringResource(R.string.settings_not_configured) },
                style = MaterialTheme.typography.bodySmall,
                color = if (fileName.isBlank()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onPick) {
                Text(stringResource(R.string.settings_choose_file))
            }
            if (fileName.isNotBlank()) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.settings_clear))
                }
            }
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChangeFinished: (Float) -> Unit,
) {
    var sliderValue by remember(value) { mutableStateOf(value) }
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onChangeFinished(sliderValue) },
            valueRange = valueRange,
            steps = steps,
        )
    }
}

private fun contextSizeToIndex(size: Int): Float = when (size) {
    2048 -> 0f
    4096 -> 1f
    8192 -> 2f
    else -> 3f
}

private fun indexToContextSize(index: Float): Int = when (index.roundToInt()) {
    0 -> 2048
    1 -> 4096
    2 -> 8192
    else -> 16384
}
