package com.mlbb.highlight.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mlbb.highlight.storage.HighlightEntity

@Composable
fun HighlightsScreen(
    highlights: List<HighlightEntity>,
    onPlay: (HighlightEntity) -> Unit,
    onDelete: (HighlightEntity) -> Unit,
    onShare: (HighlightEntity) -> Unit
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (highlights.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = RecorderTheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("No recordings yet", color = RecorderTheme.textPrimary)
                    Text(
                        "Your finished gameplay videos will show up here.",
                        color = RecorderTheme.textSecondary
                    )
                }
            }
        }
        highlights.forEach { highlight ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = RecorderTheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(highlight.title, color = RecorderTheme.textPrimary)
                    Text(
                        java.text.SimpleDateFormat("MMM d, yyyy • h:mm a", java.util.Locale.getDefault())
                            .format(java.util.Date(highlight.createdAtMs)),
                        color = RecorderTheme.textSecondary,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onPlay(highlight) }) {
                            Text("Play")
                        }
                        Button(onClick = { onDelete(highlight) }) {
                            Text("Delete")
                        }
                        Button(onClick = { onShare(highlight) }) {
                            Text("Share")
                        }
                    }
                }
            }
        }
    }
}
