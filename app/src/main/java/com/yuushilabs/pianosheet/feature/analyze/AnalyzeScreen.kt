package com.yuushilabs.pianosheet.feature.analyze

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yuushilabs.pianosheet.domain.final.FinalStage

/**
 * SCR-003 analysis screen (spec §3.5): the three stages, a real progress figure,
 * and a cancel that keeps the recording.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzeScreen(
    projectId: String,
    forceReanalyse: Boolean,
    onDone: (String) -> Unit,
    onCancelled: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: AnalyzeViewModel = viewModel(
        key = "analyze-$projectId-$forceReanalyse",
        factory = AnalyzeViewModelFactory(
            context.applicationContext as android.app.Application,
            projectId,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmCancel by remember { mutableStateOf(false) }

    LaunchedEffect(projectId, forceReanalyse) {
        viewModel.start(force = forceReanalyse)
    }
    LaunchedEffect(state.done) {
        if (state.done) onDone(projectId)
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("解析を中止しますか？") },
            text = { Text("録音した音声はそのまま残ります。あとからもう一度解析できます。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCancel = false
                        viewModel.cancel()
                        onCancelled()
                    },
                ) { Text("中止する") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text("続ける") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("楽譜を作成中", fontWeight = FontWeight.SemiBold)
                        Text(
                            state.title.ifEmpty { "録音を解析しています" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(Modifier.height(24.dp))
            if (state.error != null) {
                Text(
                    "解析に失敗しました",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                Text(state.error ?: "", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::retry) { Text("再試行") }
                    OutlinedButton(onClick = onCancelled) { Text("戻る") }
                }
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                )
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        stageLabel(state.stage),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${state.percent}%",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { state.fraction.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                state.detail.ifEmpty { "少しかかります。画面を閉じても録音は残ります。" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                state.engineFallback ?: "解析エンジン: PTI-ONNX（ピアノ専用・端末内）",
                style = MaterialTheme.typography.labelSmall,
                color = if (state.engineFallback != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            state.sharedPath?.let { path ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "録音はアプリの外にも保存されています: $path",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(26.dp))
            StageRow(FinalStage.PREPARING, state)
            StageRow(FinalStage.TRANSCRIBING, state)
            StageRow(FinalStage.FINISHING, state)

            Spacer(Modifier.height(24.dp))
            Text(
                "全体の音声を最初から解析し直し、テンポ・拍子・調号・リズム・左右の手を推定します。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "推定結果はあとから変更・再解析できます。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { confirmCancel = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
            ) { Text("中止する（録音は残す）") }
        }
    }
}

@Composable
private fun StageRow(stage: FinalStage, state: AnalyzeUiState) {
    val order = listOf(FinalStage.PREPARING, FinalStage.TRANSCRIBING, FinalStage.FINISHING)
    val currentIndex = order.indexOf(state.stage)
    val index = order.indexOf(stage)
    val done = state.done || index < currentIndex
    val active = !state.done && index == currentIndex
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(
                    when {
                        done -> MaterialTheme.colorScheme.primary
                        active -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = stageLabel(stage),
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                done || active -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
        Spacer(Modifier.weight(1f))
        if (done) {
            Text("完了", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        } else if (active) {
            Text("処理中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

private fun stageLabel(stage: FinalStage): String = when (stage) {
    FinalStage.PREPARING -> "準備中..."
    FinalStage.TRANSCRIBING -> "音符を書き起こしています..."
    FinalStage.FINISHING -> "仕上げ中..."
}
