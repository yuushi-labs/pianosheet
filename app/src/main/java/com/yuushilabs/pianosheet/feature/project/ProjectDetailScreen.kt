package com.yuushilabs.pianosheet.feature.project

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yuushilabs.pianosheet.PianoSheetApplication
import com.yuushilabs.pianosheet.domain.model.NoteEvent
import com.yuushilabs.pianosheet.domain.model.Pitch
import com.yuushilabs.pianosheet.domain.model.ProjectDetail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    onBack: () -> Unit,
) {
    val application = LocalContext.current.applicationContext as PianoSheetApplication
    val viewModel: ProjectDetailViewModel = viewModel(
        key = "project-$projectId",
        factory = ProjectDetailViewModelFactory(application.container, projectId),
    )
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()

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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            when {
                loading -> {
                    Spacer(Modifier.height(40.dp))
                    CircularProgressIndicator()
                }

                detail == null -> {
                    Spacer(Modifier.height(24.dp))
                    Text("このプロジェクトを読み込めませんでした。")
                }

                else -> ProjectDetailContent(detail!!)
            }
        }
    }
}

@Composable
private fun ColumnScope.ProjectDetailContent(detail: ProjectDetail) {
    val project = detail.project
    val recording = detail.recording

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("録音データ", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("音符 ${project.noteCount} 件")
                    if (recording != null) {
                        append(" ・ ${recording.durationMs / 1000.0} 秒")
                        append(" ・ ${recording.sampleRate} Hz")
                        append(" ・ ${recording.audioSource}")
                        if (recording.isUnprocessed) append("（無加工）")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (recording != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "ファイル: ${recording.storagePath.substringAfterLast('/')}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "ノイズ除去: ${recording.denoiseProfile ?: "なし"}（録音は無加工で保存）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Text(
        "暫定音符（ライブ検出）",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "リズム・小節・調号の確定は Phase 2 の最終解析で行います。",
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

    LazyColumn(modifier = Modifier.weight(1f)) {
        items(detail.notes, key = { it.id }) { note ->
            NoteRow(note)
        }
    }
}

@Composable
private fun NoteRow(note: NoteEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${note.sequence + 1}",
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
