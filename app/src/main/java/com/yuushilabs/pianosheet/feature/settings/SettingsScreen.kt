package com.yuushilabs.pianosheet.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yuushilabs.pianosheet.core.dsp.DenoiseStrength
import com.yuushilabs.pianosheet.domain.model.AnalysisSettings
import com.yuushilabs.pianosheet.domain.model.DenoiseMode
import com.yuushilabs.pianosheet.domain.model.HumMode
import com.yuushilabs.pianosheet.feature.update.UpdateHost
import com.yuushilabs.pianosheet.feature.update.UpdateStyle

/** SCR-007: the "録音・解析" section of the settings (spec §3.7, §20.4). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionTitle("録音・解析")
            SettingsCard {
                Text(
                    "音声入力: 端末マイク",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "録音ソース: 非加工（UNPROCESSED）を優先し、未対応の端末では標準マイクを使います。録音開始時に判定して表示します。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            SettingsCard {
                Text("ノイズ除去", style = MaterialTheme.typography.titleSmall)
                Text(
                    "部屋のサーッという音を弱めます。録音そのものは変わりません。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val mode = settings.denoiseMode
                RadioRow("自動（標準）", mode == DenoiseMode.AUTO) { viewModel.setDenoiseMode(DenoiseMode.AUTO) }
                RadioRow("オン", mode == DenoiseMode.ON) { viewModel.setDenoiseMode(DenoiseMode.ON) }
                RadioRow("オフ", mode == DenoiseMode.OFF) { viewModel.setDenoiseMode(DenoiseMode.OFF) }
            }

            Spacer(Modifier.height(8.dp))

            SettingsCard {
                Text("ノイズ除去の強さ", style = MaterialTheme.typography.titleSmall)
                Text(
                    "強くすると静かな音や余韻が消えることがあります。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StrengthRow(settings, DenoiseStrength.WEAK, "弱", viewModel)
                StrengthRow(settings, DenoiseStrength.STANDARD, "標準", viewModel)
                StrengthRow(settings, DenoiseStrength.STRONG, "強", viewModel)
            }

            Spacer(Modifier.height(8.dp))

            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ライブ表示にも適用", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "録音中の表示に使う音にも同じ処理をします（標準で適用）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.applyToLive,
                        onCheckedChange = { viewModel.setApplyToLive(it) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            SettingsCard {
                Text("電源ハム除去", style = MaterialTheme.typography.titleSmall)
                Text(
                    "ジーッという低い音（電源ハム）が気になるときに。自動にすると50/60Hzを判定して消します。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val hum = settings.humMode
                RadioRow("自動", hum == HumMode.AUTO) { viewModel.setHumMode(HumMode.AUTO) }
                RadioRow("50 Hz", hum == HumMode.HZ50) { viewModel.setHumMode(HumMode.HZ50) }
                RadioRow("60 Hz", hum == HumMode.HZ60) { viewModel.setHumMode(HumMode.HZ60) }
                RadioRow("なし", hum == HumMode.NONE) { viewModel.setHumMode(HumMode.NONE) }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("アプリ")
            SettingsCard {
                UpdateHost(
                    controller = viewModel.updateController,
                    style = UpdateStyle.SECTION,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "設定は端末に保存されます。録音済みの音声は変更しないため、設定を変えたときは同じ録音から解析し直せます（再解析はPhase 2）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StrengthRow(
    settings: AnalysisSettings,
    strength: DenoiseStrength,
    label: String,
    viewModel: SettingsViewModel,
) {
    RadioRow(label, settings.strength == strength) { viewModel.setStrength(strength) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
