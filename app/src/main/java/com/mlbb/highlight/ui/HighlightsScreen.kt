package com.mlbb.highlight.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mlbb.highlight.storage.HighlightEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HighlightsScreen(
    highlights: List<HighlightEntity>,
    message: String? = null,
    modifier: Modifier = Modifier,
    onPlay: (HighlightEntity) -> Unit,
    onDelete: (HighlightEntity) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (message != null) {
            item(key = "message") {
                Text(message, color = RecorderTheme.cyan)
            }
        }
        if (highlights.isEmpty()) {
            item(key = "empty") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = RecorderTheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("🎬", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "No recordings yet",
                            color = RecorderTheme.textPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Your MLBB recordings will appear here.",
                            color = RecorderTheme.textSecondary
                        )
                    }
                }
            }
        } else {
            items(highlights, key = { it.filePath }) { highlight ->
                RecordingCard(
                    recording = highlight,
                    onPlay = { onPlay(highlight) },
                    onDelete = { onDelete(highlight) }
                )
            }
        }
    }
}

@Composable
private fun RecordingCard(
    recording: HighlightEntity,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val recordingInfo by produceState<RecordingInfo?>(
        initialValue = null,
        key1 = recording.filePath
    ) {
        value = withContext(Dispatchers.IO) {
            readRecordingInfo(context, recording.filePath)
        }
    }
    val details = recordingInfo
    var confirmDelete by remember(recording.filePath) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = RecorderTheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF080D18)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    details == null -> CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = RecorderTheme.cyan,
                        strokeWidth = 2.dp
                    )
                    details.thumbnail != null -> Image(
                        bitmap = details.thumbnail.asImageBitmap(),
                        contentDescription = "Preview of ${recording.title}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    else -> Icon(
                        Icons.Outlined.Movie,
                        contentDescription = "Video thumbnail unavailable",
                        tint = RecorderTheme.textSecondary,
                        modifier = Modifier.size(40.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(Color(0x99080D18), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onPlay, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Play ${recording.title}",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
                details?.durationMs?.takeIf { it > 0L }?.let { duration ->
                    Text(
                        formatDuration(duration),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC080D18))
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        recording.title,
                        color = RecorderTheme.textPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        DateFormat.getDateTimeInstance(
                            DateFormat.MEDIUM,
                            DateFormat.SHORT,
                            Locale.getDefault()
                        ).format(Date(recording.createdAtMs)),
                        color = RecorderTheme.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    details?.let { info ->
                        val facts = listOfNotNull(
                            info.resolution,
                            info.frameRate?.let { "$it fps" },
                            info.fileSizeBytes?.let(::formatFileSize)
                        )
                        if (facts.isNotEmpty()) {
                            Text(
                                facts.joinToString("  •  "),
                                color = RecorderTheme.cyan,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = "Delete ${recording.title}",
                        tint = RecorderTheme.textSecondary
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this recording?") },
            text = { Text("This video will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = Color(0xFFFF6378))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel")
                }
            },
            containerColor = RecorderTheme.surface
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1_000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private data class RecordingInfo(
    val thumbnail: Bitmap?,
    val durationMs: Long?,
    val resolution: String?,
    val frameRate: Int?,
    val fileSizeBytes: Long?
)

private fun readRecordingInfo(
    context: android.content.Context,
    filePath: String
): RecordingInfo {
    val uri = filePath.toUri().let { parsed ->
        if (parsed.scheme == null) Uri.fromFile(File(filePath)) else parsed
    }
    var retriever: MediaMetadataRetriever? = null
    return try {
        retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, uri)
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()
        val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toFloatOrNull()
            ?.toInt()
            ?.takeIf { it > 0 }
        RecordingInfo(
            thumbnail = retriever.getScaledFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                640,
                360
            ),
            durationMs = duration,
            resolution = if (width != null && height != null) "${width}×$height" else null,
            frameRate = frameRate,
            fileSizeBytes = readFileSizeSafely(context, uri, filePath)
        )
    } catch (_: Exception) {
        RecordingInfo(
            thumbnail = null,
            durationMs = null,
            resolution = null,
            frameRate = null,
            fileSizeBytes = readFileSizeSafely(context, uri, filePath)
        )
    } finally {
        retriever?.release()
    }
}

private fun readFileSizeSafely(
    context: android.content.Context,
    uri: Uri,
    filePath: String
): Long? = try {
    readFileSize(context, uri, filePath)
} catch (_: SecurityException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun readFileSize(
    context: android.content.Context,
    uri: Uri,
    filePath: String
): Long? {
    if (uri.scheme == "file") return File(filePath).takeIf(File::exists)?.length()
    return context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.SIZE),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
        } else null
    }
}

private fun formatFileSize(bytes: Long): String {
    val megabytes = bytes / (1024f * 1024f)
    return if (megabytes >= 1024f) {
        "%.1f GB".format(megabytes / 1024f)
    } else {
        "%.0f MB".format(megabytes)
    }
}
