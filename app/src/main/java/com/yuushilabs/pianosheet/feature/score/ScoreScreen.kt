package com.yuushilabs.pianosheet.feature.score

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yuushilabs.pianosheet.domain.export.MidiExporter
import com.yuushilabs.pianosheet.domain.export.MusicXmlExporter
import com.yuushilabs.pianosheet.domain.score.Score
import com.yuushilabs.pianosheet.ui.components.ScorePlayhead
import com.yuushilabs.pianosheet.ui.components.ScoreWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * SCR-004 finished score (spec §3.6).
 *
 * One screen does the whole job: the engraved score, Score/Original playback
 * with speed control, the export of MusicXML / MIDI / the raw take, and a
 * re-analysis.  The score itself is drawn by OpenSheetMusicDisplay from the
 * MusicXML this app generates, so what the user sees is exactly what the
 * exported file contains.
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
    var zoom by remember { mutableFloatStateOf(1f) }

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

    val saveWav = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav"),
    ) { uri -> if (uri != null) viewModel.saveWav(uri) }

    val saveMusicXml = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MusicXmlExporter.MIME_TYPE),
    ) { uri -> if (uri != null) viewModel.saveMusicXml(uri) }

    val saveMidi = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MidiExporter.MIME_TYPE),
    ) { uri -> if (uri != null) viewModel.saveMidi(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.title.ifEmpty { "完成楽譜" },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.score?.let { score ->
                            Text(
                                scoreSummary(score),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                    ScoreArea(
                        score = score,
                        state = state,
                        zoom = zoom,
                        onZoom = { value -> zoom = value.coerceIn(0.7f, 1.8f) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    HorizontalDivider()
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
                    ExportBar(
                        onSaveMusicXml = { saveMusicXml.launch(viewModel.musicXmlSuggestedName()) },
                        onSaveMidi = { saveMidi.launch(viewModel.midiSuggestedName()) },
                        onSaveWav = { saveWav.launch(viewModel.suggestedFileName()) },
                        onShareMusicXml = { launchShare(context, viewModel.shareMusicXmlIntent()) },
                        onShareMidi = { launchShare(context, viewModel.shareMidiIntent()) },
                        onShareWav = { launchShare(context, viewModel.shareWavIntent()) },
                        onReanalyse = { onAnalyse(projectId) },
                    )
                }
            }
        }
    }
}

private fun launchShare(context: android.content.Context, intent: android.content.Intent?) {
    if (intent != null) context.startActivity(intent)
}

/**
 * The engraved score plus the play-head.  The MusicXML is built once per score
 * off the UI thread; the WebView renders it and only receives a position while
 * the take plays.
 */
@Composable
private fun ScoreArea(
    score: Score,
    state: ScoreUiState,
    zoom: Float,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val musicXml by produceState(initialValue = null as String?, key1 = score) {
        value = withContext(Dispatchers.Default) {
            runCatching { MusicXmlExporter.toMusicXml(score) }.getOrNull()
        }
    }
    val playhead = remember(state.beat, state.measureIndex, state.playing, score) {
        val beat = state.beat.coerceAtLeast(0.0)
        val measure = score.measureAtBeat(beat)
        if (measure == null) {
            null
        } else {
            ScorePlayhead(
                measureIndex = measure.index,
                beatInMeasure = (beat - measure.startBeat).coerceIn(0.0, measure.beats),
                beatsInMeasure = measure.beats,
                follow = state.playing,
            )
        }
    }

    Box(modifier = modifier) {
        val xml = musicXml
        if (xml == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            ScoreWebView(
                musicXml = xml,
                darkTheme = dark,
                zoom = zoom,
                playhead = playhead,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ZoomControl(
            zoom = zoom,
            onZoom = onZoom,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp),
        )
    }
}

@Composable
private fun ZoomControl(zoom: Float, onZoom: (Float) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            SmallStepButton(label = "－", onClick = { onZoom(zoom - 0.15f) })
            Text(
                "${(zoom * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            SmallStepButton(label = "＋", onClick = { onZoom(zoom + 0.15f) })
        }
    }
}

@Composable
private fun SmallStepButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .height(30.dp)
            .width(30.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
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

@OptIn(ExperimentalLayoutApi::class)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChipCompact(
                label = "楽譜",
                selected = state.mode == PlaybackMode.SCORE,
                onClick = { onMode(PlaybackMode.SCORE) },
            )
            Spacer(Modifier.width(6.dp))
            FilterChipCompact(
                label = "元音声",
                selected = state.mode == PlaybackMode.ORIGINAL,
                onClick = { onMode(PlaybackMode.ORIGINAL) },
            )
            Spacer(Modifier.weight(1f))
            if (state.measureIndex >= 0) {
                Text(
                    "${state.measureIndex + 1} 小節",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onTogglePlay) {
                Text(if (state.playing) "⏸" else "▶")
            }
            Spacer(Modifier.width(8.dp))
            Text(formatTime(state.positionSec), style = MaterialTheme.typography.labelSmall)
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
        // Five speed chips have to fit a 360 dp screen: compact shape, wrapping
        // to a second line instead of shrinking into a sliver at the edge.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("速度", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(6.dp))
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (speed in SpeedOptions.speeds) {
                    SpeedChip(
                        label = SpeedOptions.label(speed),
                        selected = kotlin.math.abs(state.speed - speed) < 0.01f,
                        onClick = { onSpeed(speed) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChipCompact(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = if (selected) colors.primary.copy(alpha = 0.22f) else Color.Transparent,
        contentColor = if (selected) colors.primary else colors.onSurfaceVariant,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outline),
        modifier = Modifier.height(32.dp),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun SpeedChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(9.dp),
        color = if (selected) colors.primary else Color.Transparent,
        contentColor = if (selected) colors.onPrimary else colors.onSurface,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outline),
        modifier = Modifier.height(30.dp),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/**
 * One row of two buttons, each opening the format list: 保存 (to a folder the
 * user picks) and 共有 (to another app).  The raw take, MusicXML and MIDI all
 * live behind the same two buttons so nothing is buried.
 */
@Composable
private fun ExportBar(
    onSaveMusicXml: () -> Unit,
    onSaveMidi: () -> Unit,
    onSaveWav: () -> Unit,
    onShareMusicXml: () -> Unit,
    onShareMidi: () -> Unit,
    onShareWav: () -> Unit,
    onReanalyse: () -> Unit,
) {
    var saveOpen by remember { mutableStateOf(false) }
    var shareOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Button(onClick = { saveOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("保存")
            }
            DropdownMenu(expanded = saveOpen, onDismissRequest = { saveOpen = false }) {
                DropdownMenuItem(
                    text = { Text("MusicXML（.musicxml）") },
                    onClick = {
                        saveOpen = false
                        onSaveMusicXml()
                    },
                )
                DropdownMenuItem(
                    text = { Text("MIDI（.mid）") },
                    onClick = {
                        saveOpen = false
                        onSaveMidi()
                    },
                )
                DropdownMenuItem(
                    text = { Text("生録音（.wav）") },
                    onClick = {
                        saveOpen = false
                        onSaveWav()
                    },
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(onClick = { shareOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text("共有")
            }
            DropdownMenu(expanded = shareOpen, onDismissRequest = { shareOpen = false }) {
                DropdownMenuItem(
                    text = { Text("MusicXML を共有") },
                    onClick = {
                        shareOpen = false
                        onShareMusicXml()
                    },
                )
                DropdownMenuItem(
                    text = { Text("MIDI を共有") },
                    onClick = {
                        shareOpen = false
                        onShareMidi()
                    },
                )
                DropdownMenuItem(
                    text = { Text("生録音(WAV) を共有") },
                    onClick = {
                        shareOpen = false
                        onShareWav()
                    },
                )
            }
        }
        TextButton(onClick = onReanalyse, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
            Text("再解析", maxLines = 1)
        }
    }
}

/** The playback speeds offered under the score, and their short labels. */
internal object SpeedOptions {
    val speeds: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f)

    /** 0.75f -> "0.75x", 1.0f -> "1.0x": never wider than five characters. */
    fun label(speed: Float): String {
        val hundredths = (speed * 100f).roundToInt()
        val whole = hundredths / 100
        val fraction = hundredths % 100
        val fractionText = when {
            fraction == 0 -> "0"
            fraction % 10 == 0 -> "${fraction / 10}"
            else -> fraction.toString().padStart(2, '0')
        }
        return "$whole.$fractionText" + "x"
    }
}

private fun formatTime(seconds: Double): String {
    val safe = seconds.coerceAtLeast(0.0)
    val minutes = (safe / 60).toInt()
    val secs = (safe % 60).toInt()
    return "%d:%02d".format(minutes, secs)
}

private fun scoreSummary(score: Score): String =
    "♩=${score.tempoBpm.toInt()} ・ ${score.timeSignature.label} ・ ${score.keySignature.nameJa()} ・ " +
        "音符${score.noteCount} ・ ${formatTime(score.durationSec)}"
