package com.mlbb.highlight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.mlbb.highlight.settings.AppSettings

@Composable
fun RecorderHomeScreen(
    isCapturing: Boolean,
    isPaused: Boolean,
    recordingSeconds: Int,
    statusMessage: String,
    settings: AppSettings,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onOpenTrim: () -> Unit,
    onOpenEffects: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Brush.linearGradient(listOf(RecorderTheme.blue, RecorderTheme.cyan))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "M",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = RecorderTheme.textPrimary)) { append("MLBB") }
                        withStyle(SpanStyle(color = RecorderTheme.cyan)) { append("Highlights") }
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Record  •  Trim  •  Add Effects  •  Share",
                    style = MaterialTheme.typography.bodySmall,
                    color = RecorderTheme.textSecondary
                )
            }
            Icon(
                imageVector = Icons.Outlined.WorkspacePremium,
                contentDescription = "Premium",
                tint = RecorderTheme.textPrimary,
                modifier = Modifier.size(27.dp)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(174.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF0B1E42), Color(0xFF122F70), Color(0xFF075B9F))
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(width = 180.dp, height = 260.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0x5537C7FF), Color(0x0025C7E8))
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    "TURN YOUR GAMEPLAY INTO",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = RecorderTheme.textSecondary
                )
                Text(
                    "EPIC HIGHLIGHTS",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = RecorderTheme.textPrimary
                )
                Text(
                    "Capture your best moments from MLBB",
                    color = RecorderTheme.textSecondary
                )
            }
        }

        RecordingControls(
            isCapturing = isCapturing,
            isPaused = isPaused,
            recordingSeconds = recordingSeconds,
            statusMessage = statusMessage,
            onStart = onStart,
            onPauseResume = onPauseResume,
            onStop = onStop
        )

        FeatureCard(
            title = "Trim",
            subtitle = "Cut your gameplay and keep\nonly the best parts.",
            icon = Icons.Outlined.ContentCut,
            accent = RecorderTheme.blue,
            onClick = onOpenTrim
        )
        FeatureCard(
            title = "Effects",
            subtitle = "Add cool effects, slow motion,\nand make highlights stand out.",
            icon = Icons.Outlined.AutoAwesome,
            accent = RecorderTheme.purple,
            onClick = onOpenEffects
        )
        FeatureCard(
            title = "Settings",
            subtitle = "Customize your preferences\nand app behavior.",
            icon = Icons.Outlined.Settings,
            accent = RecorderTheme.green,
            onClick = onOpenSettings
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("M", color = RecorderTheme.muted, style = MaterialTheme.typography.headlineMedium)
            Text(
                "M L B B   H I G H L I G H T S",
                color = RecorderTheme.muted,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${settings.resolutionShortEdge}p  •  ${settings.frameRate} fps  •  ${settings.audioSource.label()}",
                color = RecorderTheme.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun RecordingControls(
    isCapturing: Boolean,
    isPaused: Boolean,
    recordingSeconds: Int,
    statusMessage: String,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit
) {
    if (isCapturing) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(RecorderTheme.surface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (isPaused) RecorderTheme.muted else RecorderTheme.green)
                )
                Text(
                    if (isPaused) "Recording paused" else "Recording match",
                    color = RecorderTheme.textPrimary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    statusMessage,
                    style = MaterialTheme.typography.labelMedium,
                    color = RecorderTheme.textSecondary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatRecordingTime(recordingSeconds),
                    style = MaterialTheme.typography.headlineMedium,
                    color = RecorderTheme.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onPauseResume,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RecorderTheme.textPrimary)
                    ) {
                        Text(if (isPaused) "Resume" else "Pause")
                    }
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE95062))
                    ) {
                        Text("Stop")
                    }
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (statusMessage != "Ready to record") {
                Text(
                    statusMessage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF3B2730))
                        .padding(12.dp),
                    color = Color(0xFFFFB7C0),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RecorderTheme.blue)
            ) {
                Text("●   Start recording", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FeatureCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit
) {
    androidx.compose.material3.Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            accent.copy(alpha = 0.22f),
                            accent.copy(alpha = 0.13f),
                            accent.copy(alpha = 0.08f)
                        )
                    )
                )
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.72f)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                    tint = Color.White
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = RecorderTheme.textPrimary
                )
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = RecorderTheme.textSecondary)
            }
            Text("›", style = MaterialTheme.typography.headlineLarge, color = RecorderTheme.textSecondary)
        }
    }
}

private fun com.mlbb.highlight.settings.RecordingAudioSource.label(): String =
    when (this) {
        com.mlbb.highlight.settings.RecordingAudioSource.SILENT -> "Silent"
        com.mlbb.highlight.settings.RecordingAudioSource.DEVICE -> "Game audio"
        com.mlbb.highlight.settings.RecordingAudioSource.MICROPHONE -> "Microphone"
        com.mlbb.highlight.settings.RecordingAudioSource.DEVICE_AND_MICROPHONE -> "Game + mic"
    }

private fun formatRecordingTime(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, remainingSeconds)
    else "%02d:%02d".format(minutes, remainingSeconds)
}
