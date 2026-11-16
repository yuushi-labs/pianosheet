package com.yuushilabs.pianosheet.feature.score

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yuushilabs.pianosheet.domain.score.Score
import com.yuushilabs.pianosheet.ui.components.CompletedScoreCanvas

/**
 * SCR-004 finished score (spec §3.6): header with the estimated tempo, meter and
 * key, the engraved score, Score/Original playback with speed control and the
 * raw-WAV export buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreen(
    projectId: String,
    onBack: () -> Unit,
    onAnalyse: (String) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: ScoreViewModel = viewModel(
        key = "score-$projectId",
        factory = ScoreViewModelFactory(
            context.applicationContext as android.app.Application,
            projectId,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(projectId) { viewModel.load() }

    LaunchedEffect(state.message, state.errorMessage) {
        val text = state.message ?: state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }

    LaunchedEffect(state.positionSec, state.durationSec, dragging) {
        if (!dragging) {
            sliderPosition = if (state.durationSec > 0) {
                (state.positionSec / state.durationSec).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav"),
    ) { uri ->
        if (uri != null) viewModel.saveWav(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.title.ifEmpty { "完成楽譜" },
                            fontWeight = FontWeight.SemiBold,
                        )
                        state.score?.let { score ->
                            Text(
                                scoreSummary(score),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { onAnalyse(projectId) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "再解析")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> {
                    Spacer(Modifier.height(40.dp))
                    CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                }

                !state.hasScore -> {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            state.errorMessage ?: "まだ楽譜がありません。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { onAnalyse(projectId) }) { Text("この録音を解析する") }
                    }
                }

                else -> {
                    val score = state.score!!
                    EstimateRow(score)
                    CompletedScoreCanvas(
                        score = score,
                        currentMeasureIndex = state.measureIndex,
                        activeNoteIds = state.activeNoteIds,
                        playheadBeat = if (state.playing) state.beat else null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    PlaybackPanel(
                        state = state,
                        sliderPosition = sliderPosition,
                        onSliderChange = { value ->
                            dragging = true
                            sliderPosition = value
                        },
                        onSliderChangeFinished = {
                            dragging = false
                            viewModel.seekTo(sliderPosition.toDouble() * state.durationSec)
                        },
                        onTogglePlay = viewModel::togglePlay,
                        onMode = viewModel::setMode,
                        onSpeed = viewModel::setSpeed,
                    )
                    ExportRow(
                        onSave = { saveLauncher.launch(viewModel.suggestedFileName()) },
                        onShare = {
                            val intent = viewModel.shareWavIntent()
                            if (intent != null) context.startActivity(intent)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EstimateRow(score: Score) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            AssistChip(
                onClick = {},
                label = { Text("♩= ${score.tempoBpm.toInt()}${if (score.tempoConfidence < 0.4) "?" else ""}") },
            )
        }
        item {
            AssistChip(
                onClick = {},
                label = {
                    Text("${score.timeSignature.label}${if (score.timeSignatureConfidence < 0.3) "?" else ""}")
                },
            )
        }
        item {
            AssistChip(
                onClick = {},
                label = {
                    Text("${score.keySignature.nameJa()}${if (score.keyConfidence < 0.4) "?" else ""}")
                },
            )
        }
        item {
            AssistChip(
                onClick = {},
                label = { Text("音符 ${score.noteCount}") },
            )
        }
    }
}

@Composable
private fun PlaybackPanel(
    state: ScoreUiState,
    sliderPosition: Float,
    onSliderChange: (Float) -> Unit,
    onSliderChangeFinished: () -> Unit,
    onTogglePlay: () -> Unit,
    onMode: (PlaybackMode) -> Unit,
    onSpeed: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.mode == PlaybackMode.SCORE,
                onClick = { onMode(PlaybackMode.SCORE) },
                label = { Text("楽譜 (Score)") },
            )
            FilterChip(
                selected = state.mode == PlaybackMode.ORIGINAL,
                onClick = { onMode(PlaybackMode.ORIGINAL) },
                label = { Text("元音声 (Original)") },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onTogglePlay) {
                Text(if (state.playing) "⏸ 一時停止" else "▶ 再生")
            }
            Spacer(Modifier.width(8.dp))
            Text(
                formatTime(state.positionSec),
                style = MaterialTheme.typography.labelSmall,
            )
            Slider(
                value = sliderPosition,
                onValueChange = onSliderChange,
                onValueChangeFinished = onSliderChangeFinished,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            )
            Text(formatTime(state.durationSec), style = MaterialTheme.typography.labelSmall)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("速度", style = MaterialTheme.typography.labelSmall)
            for (speed in SPEEDS) {
                FilterChip(
                    selected = kotlin.math.abs(state.speed - speed) < 0.01f,
                    onClick = { onSpeed(speed) },
                    label = { Text("${speed}x") },
                )
            }
            Spacer(Modifier.weight(1f))
            if (state.measureIndex >= 0) {
                Text(
                    "${state.measureIndex + 1} 小節",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ExportRow(onSave: () -> Unit, onShare: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(onClick = onSave, modifier = Modifier.weight(1f)) {
            Text("生録音(WAV)を保存")
        }
        OutlinedButton(onClick = onShare) { Text("共有") }
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f)

private fun formatTime(seconds: Double): String {
    val safe = seconds.coerceAtLeast(0.0)
    val minutes = (safe / 60).toInt()
    val secs = (safe % 60).toInt()
    return "%d:%02d".format(minutes, secs)
}

private fun scoreSummary(score: Score): String =
    "♩=${score.tempoBpm.toInt()} ・ ${score.timeSignature.label} ・ ${score.keySignature.nameJa()} ・ " +
        "音符${score.noteCount} ・ ${formatTime(score.durationSec)}"
