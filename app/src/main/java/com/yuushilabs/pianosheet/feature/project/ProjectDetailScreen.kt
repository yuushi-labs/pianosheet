package com.yuushilabs.pianosheet.feature.project

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yuushilabs.pianosheet.PianoSheetApplication
import com.yuushilabs.pianosheet.domain.model.NoteEvent
import com.yuushilabs.pianosheet.domain.model.NoteOrdering
import com.yuushilabs.pianosheet.domain.model.Pitch
import com.yuushilabs.pianosheet.domain.model.ProjectDetail
import com.yuushilabs.pianosheet.domain.score.Score

/**
 * Project detail: the saved take, its finished score, the provisional note list
 * and the raw-WAV export (spec §3.2, §8, §10).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenScore: () -> Unit,
    onAnalyse: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as PianoSheetApplication
    val viewModel: ProjectDetailViewModel = viewModel(
        key = "project-$projectId",
        factory = ProjectDetailViewModelFactory(application.container, projectId),
    )
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.clearMessage()
    }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav"),
    ) { uri ->
        if (uri != null) viewModel.saveWav(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.project?.title ?: "プロジェクト") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "戻る")
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
                .padding(horizontal = 16.dp),
        ) {
            when {
                loading && detail == null -> {
                    Spacer(Modifier.height(40.dp))
                    CircularProgressIndicator()
                }

                detail == null -> {
                    Spacer(Modifier.height(24.dp))
                    Text("このプロジェクトを読み込めませんでした。")
                }

                else -> ProjectDetailContent(
                    detail = detail!!,
                    onOpenScore = onOpenScore,
                    onAnalyse = onAnalyse,
                    onSaveWav = { saveLauncher.launch(viewModel.suggestedFileName()) },
                    onShareWav = {
                        val intent = viewModel.shareWavIntent()
                        if (intent != null) context.startActivity(intent)
                    },
                    onSaveToSharedFolder = viewModel::saveToSharedFolder,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ProjectDetailContent(
    detail: ProjectDetail,
    onOpenScore: () -> Unit,
    onAnalyse: (Boolean) -> Unit,
    onSaveWav: () -> Unit,
    onShareWav: () -> Unit,
    onSaveToSharedFolder: () -> Unit,
) {
    val project = detail.project
    val recording = detail.recording
    val score = detail.score

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("録音データ", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("音符 ${project.noteCount} 件")
                    if (recording != null) {
                        append(" ・ ${"%.1f".format(recording.durationMs / 1000.0)} 秒")
                        if (recording.sampleRate > 0) append(" ・ ${recording.sampleRate} Hz")
                        append(" ・ ${recording.audioSource}")
                        if (recording.isUnprocessed) append("（無加工）")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (recording != null) {
                Spacer(Modifier.height(4.dp))
                // The copy outside the app: the one that survives an uninstall.
                val sharedPath = recording.sharedPath
                if (sharedPath != null) {
                    Text(
                        text = "共有フォルダ: $sharedPath",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text = "共有フォルダ: まだ保存していません（この端末のアプリ内にのみあります）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "アプリ内: ${recording.storagePath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "ノイズ除去: ${recording.denoiseProfile ?: "なし"}（録音は無加工で保存）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (score != null) {
                    // Which engine produced this score: PTI-ONNX, or Basic Pitch
                    // because the PTI pass could not run on this device.
                    Text(
                        text = "解析エンジン: ${engineLabel(score.detector)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSaveWav) { Text("生録音を保存") }
                OutlinedButton(onClick = onShareWav) { Text("共有") }
            }
            if (recording?.sharedPath == null) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onSaveToSharedFolder,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("共有フォルダ（Music/PianoSheet）に保存") }
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("完成楽譜", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            if (score == null) {
                Text(
                    "まだ解析していません。録音から楽譜を作れます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onAnalyse(false) }) { Text("楽譜を作成する") }
            } else {
                Text(
                    text = scoreLine(score),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenScore) { Text("楽譜を開く") }
                    OutlinedButton(onClick = { onAnalyse(true) }) { Text("再解析") }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "暫定音符（ライブ検出・開始時刻順）",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "最終解析の結果は「完成楽譜」に保存されています。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
    )

    if (detail.notes.isEmpty()) {
        Text("この録音には音符がありません。", style = MaterialTheme.typography.bodyMedium)
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("#", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.15f))
        Text("音名", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.35f))
        Text("開始 (秒)", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.3f))
        Text("信頼度", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(0.2f))
    }

    val ordered = NoteOrdering.chronological(detail.notes)
    LazyColumn(modifier = Modifier.weight(1f)) {
        itemsIndexed(ordered, key = { _, note -> note.id }) { index, note ->
            NoteRow(note, index + 1)
        }
    }
}

@Composable
private fun NoteRow(note: NoteEvent, displayIndex: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$displayIndex",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.15f),
        )
        Text(
            "${Pitch.midiToName(note.pitchMidi)} (${note.pitchMidi})",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            "%.2f".format(note.onsetMs / 1000.0),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.3f),
        )
        Text(
            "%.2f".format(note.confidence),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.2f),
        )
    }
}

private fun scoreLine(score: Score): String =
    "♩=${score.tempoBpm.toInt()} ・ ${score.timeSignature.label} ・ " +
        "${score.keySignature.nameJa()} ・ 音符${score.noteCount} ・ ${"%.1f".format(score.durationSec)} 秒"

/** The engine id stored with the score, in words. */
internal fun engineLabel(detector: String): String = when {
    detector.startsWith("pti") -> "PTI-ONNX（ピアノ専用）"
    detector.startsWith("basic_pitch") -> "Basic Pitch"
    detector == "fake_detector" -> "テスト用"
    else -> detector
}
