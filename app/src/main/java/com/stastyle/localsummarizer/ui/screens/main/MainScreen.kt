package com.stastyle.localsummarizer.ui.screens.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Intent
import com.stastyle.localsummarizer.R
import com.stastyle.localsummarizer.domain.PipelineState
import com.stastyle.localsummarizer.export.Exporter
import com.stastyle.localsummarizer.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: MainViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    val selectedAudio by viewModel.selectedAudio.collectAsStateWithLifecycle()
    val pipelineState by viewModel.pipelineState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val copiedMessage = stringResource(R.string.copied_to_clipboard)

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::onAudioPicked) }

    val exportedMessage = stringResource(R.string.export_done)
    var exportAsMarkdown by remember { mutableStateOf(true) }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let { viewModel.exportTo(it, exportAsMarkdown, exportedMessage) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.main_title)) },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.open_history),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.open_settings),
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
            if (!settings.hasWhisperModel || !settings.hasLlamaModel) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(
                                if (!settings.hasWhisperModel) {
                                    R.string.error_no_whisper_model
                                } else {
                                    R.string.error_no_llama_model
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onOpenSettings) {
                            Text(stringResource(R.string.open_settings))
                        }
                    }
                }
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = selectedAudio?.displayName
                            ?: stringResource(R.string.no_file_selected),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { audioPicker.launch(arrayOf("audio/*")) },
                            enabled = !pipelineState.isRunning,
                        ) {
                            Text(stringResource(R.string.pick_audio_file))
                        }
                        if (pipelineState.isRunning) {
                            Button(onClick = viewModel::cancel) {
                                Text(stringResource(R.string.cancel))
                            }
                        } else {
                            Button(
                                onClick = viewModel::run,
                                enabled = selectedAudio != null,
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.run_pipeline))
                            }
                        }
                    }
                }
            }

            PipelineProgressSection(pipelineState)

            ResultsSection(
                pipelineState = pipelineState,
                onCopy = { text ->
                    clipboard.setText(AnnotatedString(text))
                    viewModel.notify(copiedMessage)
                },
                onShare = { text ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                },
                onExport = { markdown ->
                    val record = viewModel.currentRecord() ?: return@ResultsSection
                    exportAsMarkdown = markdown
                    exportPicker.launch(
                        Exporter.suggestFileName(record, if (markdown) "md" else "txt"),
                    )
                },
            )
        }
    }
}

@Composable
private fun PipelineProgressSection(state: PipelineState) {
    if (state is PipelineState.Idle) return
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val label = when (state) {
                is PipelineState.Decoding -> stringResource(R.string.stage_decoding)
                is PipelineState.LoadingWhisper -> stringResource(R.string.stage_loading_whisper)
                is PipelineState.Transcribing ->
                    stringResource(R.string.stage_transcribing, state.percent)
                is PipelineState.LoadingLlama -> stringResource(R.string.stage_loading_llama)
                is PipelineState.Summarizing ->
                    if (state.chunkCount > 1) {
                        stringResource(
                            R.string.stage_summarizing_chunk,
                            state.chunkIndex + 1,
                            state.chunkCount,
                        )
                    } else {
                        stringResource(R.string.stage_summarizing)
                    }
                is PipelineState.Done -> stringResource(R.string.stage_done)
                is PipelineState.Cancelled -> stringResource(R.string.stage_cancelled)
                is PipelineState.Failed -> stringResource(R.string.error_prefix, state.message)
                PipelineState.Idle -> ""
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.isRunning) {
                    CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state is PipelineState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            when (state) {
                is PipelineState.Transcribing ->
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                is PipelineState.Decoding, is PipelineState.LoadingWhisper,
                is PipelineState.LoadingLlama, is PipelineState.Summarizing ->
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultsSection(
    pipelineState: PipelineState,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onExport: (Boolean) -> Unit,
) {
    val transcript: String
    val summary: String
    when (pipelineState) {
        is PipelineState.Done -> {
            transcript = pipelineState.transcript
            summary = pipelineState.summary
        }
        is PipelineState.Transcribing -> {
            transcript = pipelineState.partialText
            summary = ""
        }
        is PipelineState.Summarizing -> {
            transcript = ""
            summary = pipelineState.partialText
        }
        else -> {
            transcript = ""
            summary = ""
        }
    }
    if (transcript.isBlank() && summary.isBlank()) return

    var selectedTab by remember { mutableIntStateOf(if (summary.isNotBlank()) 1 else 0) }
    LaunchedEffect(pipelineState is PipelineState.Done) {
        if (pipelineState is PipelineState.Done) selectedTab = 1
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.tab_transcript)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.tab_summary)) },
                )
            }
            val text = if (selectedTab == 0) transcript else summary
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onCopy(text) }, enabled = text.isNotBlank()) {
                        Text(stringResource(R.string.copy))
                    }
                    TextButton(onClick = { onShare(text) }, enabled = text.isNotBlank()) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.share))
                    }
                    val isDone = pipelineState is PipelineState.Done
                    TextButton(onClick = { onExport(true) }, enabled = isDone) {
                        Text(stringResource(R.string.export_md))
                    }
                    TextButton(onClick = { onExport(false) }, enabled = isDone) {
                        Text(stringResource(R.string.export_txt))
                    }
                }
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
