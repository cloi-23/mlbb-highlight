package com.mlbb.highlight.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Hd
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mlbb.highlight.settings.AppSettings
import com.mlbb.highlight.settings.RecordingAudioSource
import com.mlbb.highlight.settings.SettingsRepository

@Composable
fun RecordingSettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    saveLocationLabel: String,
    appVersion: String,
    onChooseSaveLocation: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onPrepareFloatingRecorder: () -> Unit,
    onResetSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = RecorderTheme.surface),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Recording quality", style = MaterialTheme.typography.titleLarge, color = RecorderTheme.textPrimary)
            Text(
                "Choose before you start. Higher quality uses more battery and storage.",
                color = RecorderTheme.textSecondary
            )

            SettingHeading("Video quality", Icons.Outlined.Hd, RecorderTheme.blue)
            ChoiceRow(
                label = "720p",
                selected = settings.resolutionShortEdge == SettingsRepository.HD_SHORT_EDGE,
                onClick = {
                    onSettingsChange(settings.copy(resolutionShortEdge = SettingsRepository.HD_SHORT_EDGE))
                }
            )
            ChoiceRow(
                label = "1080p",
                selected = settings.resolutionShortEdge == SettingsRepository.FULL_HD_SHORT_EDGE,
                onClick = {
                    onSettingsChange(settings.copy(resolutionShortEdge = SettingsRepository.FULL_HD_SHORT_EDGE))
                }
            )

            SettingHeading("Frame rate", Icons.Outlined.Speed, RecorderTheme.blue)
            ChoiceRow(
                label = "30 fps - saves battery and space",
                selected = settings.frameRate == 30,
                onClick = { onSettingsChange(settings.copy(frameRate = 30)) }
            )
            ChoiceRow(
                label = "60 fps - smoother motion",
                selected = settings.frameRate == 60,
                onClick = { onSettingsChange(settings.copy(frameRate = 60)) }
            )

            SettingHeading("Game audio", Icons.AutoMirrored.Outlined.VolumeUp, RecorderTheme.green)
            AudioChoice(
                label = "Game/device audio",
                source = RecordingAudioSource.DEVICE,
                selected = settings.audioSource,
                onSelect = { onSettingsChange(settings.copy(audioSource = it)) }
            )
            AudioChoice(
                label = "Microphone",
                source = RecordingAudioSource.MICROPHONE,
                selected = settings.audioSource,
                onSelect = { onSettingsChange(settings.copy(audioSource = it)) }
            )
            AudioChoice(
                label = "Game audio + microphone",
                source = RecordingAudioSource.DEVICE_AND_MICROPHONE,
                selected = settings.audioSource,
                onSelect = { onSettingsChange(settings.copy(audioSource = it)) }
            )
            AudioChoice(
                label = "Silent",
                source = RecordingAudioSource.SILENT,
                selected = settings.audioSource,
                onSelect = { onSettingsChange(settings.copy(audioSource = it)) }
            )
            Text(
                "Game audio may be blocked by Android or the game. Try Microphone if the recording has no game sound.",
                style = MaterialTheme.typography.bodySmall
            )

            androidx.compose.material3.HorizontalDivider(color = RecorderTheme.divider)
            SettingHeading("Editor and storage", Icons.Outlined.Tune, RecorderTheme.cyan)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconSettingRow(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.FolderOpen,
                    accent = RecorderTheme.green,
                    title = "Save location",
                    subtitle = saveLocationLabel
                )
                androidx.compose.material3.TextButton(onClick = onChooseSaveLocation) {
                    Text("Choose")
                }
            }
            Text(
                "Floating recorder controls require Android's display-over-other-apps permission. Tap the compact control to expand or collapse it; Android's secure-window flag excludes it from screen capture.",
                color = RecorderTheme.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            androidx.compose.material3.TextButton(onClick = onRequestOverlayPermission) {
                Text("Floating control permission")
            }
            Text(
                "Prepare screen-capture permission here before leaving for MLBB. The floating Start button can then begin recording without reopening this screen.",
                color = RecorderTheme.textSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            androidx.compose.material3.OutlinedButton(
                onClick = onPrepareFloatingRecorder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Prepare floating recorder")
            }
            IconSettingRow(
                icon = Icons.Outlined.Language,
                accent = RecorderTheme.green,
                title = "Language",
                subtitle = "System default · English is currently the only app language."
            )

            androidx.compose.material3.HorizontalDivider(color = RecorderTheme.divider)
            IconSettingRow(
                icon = Icons.Outlined.Info,
                accent = RecorderTheme.green,
                title = "About",
                subtitle = "MLBBHighlights · $appVersion"
            )
            androidx.compose.material3.OutlinedButton(
                onClick = onResetSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                Text("Reset settings", modifier = Modifier.padding(start = 8.dp))
            }

        }
    }
}

@Composable
private fun AudioChoice(
    label: String,
    source: RecordingAudioSource,
    selected: RecordingAudioSource,
    onSelect: (RecordingAudioSource) -> Unit
) {
    ChoiceRow(label = label, selected = selected == source, onClick = { onSelect(source) })
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = RecorderTheme.blue)
        )
        Text(label, modifier = Modifier.padding(start = 8.dp), color = RecorderTheme.textSecondary)
    }
}

@Composable
private fun SettingHeading(
    title: String,
    icon: ImageVector,
    accent: androidx.compose.ui.graphics.Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ReferenceIconTile(
            image = icon,
            tint = accent,
            tileSize = 42.dp,
            iconSize = 23.dp
        )
        Text(title, style = MaterialTheme.typography.titleMedium, color = RecorderTheme.textPrimary)
    }
}

@Composable
private fun IconSettingRow(
    icon: ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ReferenceIconTile(
            image = icon,
            tint = accent,
            tileSize = 46.dp,
            iconSize = 25.dp
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = RecorderTheme.textPrimary)
            Text(subtitle, color = RecorderTheme.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
