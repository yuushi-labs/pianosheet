package com.yuushilabs.pianosheet.feature.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.util.Locale

/** Where the update block is drawn: a quiet card on home, the full section in settings. */
enum class UpdateStyle { BANNER, SECTION }

/**
 * The in-app update UI: the home banner, the "new version" dialog, and the
 * settings section, plus the one install flow they share (download → "unknown
 * app sources" permission → system installer).
 */
@Composable
fun UpdateHost(
    controller: UpdateController,
    style: UpdateStyle,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }
    val pending = remember { mutableStateOf<File?>(null) }

    // The permission screen has no result of its own: coming back is the signal,
    // so the switch is read again and the install continues when it is on.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val apk = pending.value
        pending.value = null
        if (apk != null) {
            if (controller.canInstallPackages()) {
                context.startActivity(controller.installIntent(apk))
                controller.installStarted()
            } else {
                notice = "「この提供元のアプリを許可」をオンにするとインストールできます。"
            }
        }
    }

    val install: (File) -> Unit = { apk ->
        notice = null
        if (controller.canInstallPackages()) {
            context.startActivity(controller.installIntent(apk))
            controller.installStarted()
        } else {
            pending.value = apk
            permissionLauncher.launch(controller.unknownSourcesSettingsIntent())
        }
    }

    val available = state as? UpdateUiState.Available
    if (style == UpdateStyle.BANNER && available != null && !available.skipped) {
        AlertDialog(
            onDismissRequest = { controller.skip() },
            title = { Text("新しいバージョン ${available.info.versionName} があります") },
            text = {
                Column {
                    Text(
                        available.info.notes.ifBlank { "更新内容はリリースページにあります。" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "サイズ ${formatMegabytes(available.info.apkSizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { controller.downloadNow() }) { Text("今すぐ更新") }
            },
            dismissButton = {
                TextButton(onClick = { controller.skip() }) { Text("後で") }
            },
        )
    }

    Column(modifier = modifier) {
        if (style == UpdateStyle.SECTION) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("アプリバージョン", style = MaterialTheme.typography.titleSmall)
                    Text(
                        controller.currentVersionName,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedButton(
                    onClick = { controller.dismissFailure(); controller.checkNow() },
                    enabled = state !is UpdateUiState.Checking && state !is UpdateUiState.Downloading,
                ) {
                    Text(if (state is UpdateUiState.Checking) "確認中…" else "アップデートを確認")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        UpdateContent(
            state = state,
            controller = controller,
            compact = style == UpdateStyle.BANNER,
            install = install,
            notice = notice,
        )
    }
}

@Composable
private fun UpdateContent(
    state: UpdateUiState,
    controller: UpdateController,
    compact: Boolean,
    install: (File) -> Unit,
    notice: String?,
) {
    when (state) {
        UpdateUiState.Idle ->
            if (!compact) StatusText("アップデートの確認はまだしていません。")

        UpdateUiState.Checking -> {
            if (!compact) {
                StatusText("最新のバージョンを確認しています…")
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        UpdateUiState.Latest ->
            if (!compact) StatusText("最新です（${controller.currentVersionName}）。")

        is UpdateUiState.Available -> if (!(compact && state.skipped)) {
            UpdateCard {
                Text(
                    "新しいバージョン ${state.info.versionName} があります",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (state.info.notes.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (compact) 3 else 6,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { controller.downloadNow() }) { Text("更新") }
                    OutlinedButton(onClick = { controller.skip() }) { Text("後で") }
                }
            }
        }

        is UpdateUiState.Downloading -> {
            UpdateCard {
                Text(
                    "バージョン ${state.info.versionName} をダウンロード中",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(6.dp))
                val fraction = state.percent?.let { it / 100f }
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        if (state.percent != null) append("${state.percent}% ・ ")
                        append(formatMegabytes(state.bytes))
                        if (state.total > 0) append(" / ${formatMegabytes(state.total)}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { controller.cancelDownload() }) { Text("キャンセル") }
            }
        }

        is UpdateUiState.Ready -> {
            UpdateCard {
                Text(
                    "バージョン ${state.info.versionName} を準備しました",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "インストールするとアプリが再起動します。録音と楽譜はそのまま残ります。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (notice != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        notice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { install(state.apk) }) { Text("インストール") }
            }
        }

        is UpdateUiState.Failed -> if (!(compact && !state.manual)) {
            UpdateCard {
                Text(state.message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { controller.dismissFailure(); controller.checkNow() }) {
                    Text("もう一度")
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** `88.0 MB`, `1.2 MB`, `640 KB`. */
private fun formatMegabytes(bytes: Long): String = when {
    bytes <= 0 -> "0 MB"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%,d KB", bytes / 1024L)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
