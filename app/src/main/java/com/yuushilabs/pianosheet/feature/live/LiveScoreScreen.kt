package com.yuushilabs.pianosheet.feature.live

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yuushilabs.pianosheet.ui.components.PianoKeyboard
import com.yuushilabs.pianosheet.ui.components.StaffCanvas

/**
 * SCR-002: the live score screen (spec §3.3).
 *
 * Header = elapsed time + note counter + input state, centre = the growing grand
 * staff, footer = record / stop / discard, bottom = the keyboard whose keys light
 * up with the detected pitches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScoreScreen(
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: LiveScoreViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var followNewest by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startRecording() else permissionDenied = true
    }

    LaunchedEffect(state.savedProjectId) {
        state.savedProjectId?.let(onSaved)
    }

    if (permissionDenied) {
        AlertDialog(
            onDismissRequest = { permissionDenied = false },
            title = { Text("マイクの使用が許可されていません") },
            text = {
                Text(
                    "録音して採譜するにはマイクへのアクセスが必要です。" +
                        "端末の設定 → アプリ → PianoSheet → 権限 から「マイク」を許可してください。" +
                        "録音した音声は端末内にのみ保存されます。",
                )
            },
            confirmButton = {
                TextButton(onClick = { permissionDenied = false }) { Text("閉じる") }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("この録音を破棄しますか？") },
            text = { Text("元の音声と検出した音符は保存されません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        viewModel.discardRecording()
                    },
                ) { Text("破棄する") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("録音を続ける") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = formatElapsed(state.elapsedMs),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${state.noteCount} notes",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    Text(
                        text = statusLabel(state),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.phase == LivePhase.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            InputStatusRow(state)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "譜面",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = followNewest,
                    onClick = { followNewest = !followNewest },
                    label = { Text("自動追従") },
                )
                if (!followNewest) {
                    TextButton(onClick = { followNewest = true }) { Text("最新へ") }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "左へスクロールで見返せます",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StaffCanvas(
                notes = state.notes,
                followNewest = followNewest,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            ControlRow(
                state = state,
                onRecord = {
                    val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        viewModel.startRecording()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStop = viewModel::stopRecording,
                onDiscard = { confirmDiscard = true },
                onDismissError = viewModel::dismissError,
            )
            PianoKeyboard(
                activePitches = state.activePitches,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InputStatusRow(state: LiveUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    state.captureSource.isEmpty() -> "入力: 待機中"
                    state.unprocessed -> "入力: 非加工（UNPROCESSED）"
                    else -> "入力: 標準マイク（この端末は非加工録音に未対応）"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            state.latencyP95Ms?.let { latency ->
                Text(
                    text = "遅延 p95 ${latency.toInt()} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.isSlow) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(state.inputLevel.coerceIn(0f, 1f))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        state.inputWarning?.let { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (state.inputRmsDbfs > -120f) {
            Text(
                text = "入力 %.0f dBFS（ピーク %.0f dBFS）".format(state.inputRmsDbfs, state.inputPeakDbfs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        state.denoiseProfile?.let { profile ->
            Text(
                text = "ノイズ除去: $profile（解析用のみ・録音は無加工）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (state.isSlow) {
            Text(
                text = "解析が遅れています。音声は失われずに保存されています。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ControlRow(
    state: LiveUiState,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    onDismissError: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state.phase) {
            LivePhase.IDLE -> {
                Button(onClick = onRecord, modifier = Modifier.weight(1f)) { Text("録音開始") }
            }

            LivePhase.PREPARING -> {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("モデルとマイクを準備中…", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
            }

            LivePhase.RECORDING -> {
                Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("停止して保存") }
                OutlinedButton(onClick = onDiscard) { Text("破棄") }
            }

            LivePhase.SAVING -> {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Text("保存しています…", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
            }

            LivePhase.SAVED -> {
                Text("保存しました", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
            }

            LivePhase.ERROR -> {
                Text(
                    text = state.errorMessage ?: "エラーが発生しました",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissError) { Text("閉じる") }
            }
        }
    }
}

private fun statusLabel(state: LiveUiState): String = when (state.phase) {
    LivePhase.IDLE -> "待機"
    LivePhase.PREPARING -> "準備中"
    LivePhase.RECORDING -> if (state.isSlow) "遅延中" else "録音中"
    LivePhase.SAVING -> "保存中"
    LivePhase.SAVED -> "保存済み"
    LivePhase.ERROR -> "エラー"
}

private fun formatElapsed(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
