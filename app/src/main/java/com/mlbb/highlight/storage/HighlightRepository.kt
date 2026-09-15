package com.mlbb.highlight.storage

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HighlightRepository(private val context: Context) {
    fun getHighlightsDirectory(): File {
        val dir = File(context.getExternalFilesDir(null), "Highlights")
        dir.mkdirs()
        return dir
    }

    fun listHighlights(): List<HighlightEntity> {
        val dir = getHighlightsDirectory()
        return dir.listFiles { file -> file.isFile && file.extension.equals("mp4", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                HighlightEntity(
                    filePath = file.absolutePath,
                    createdAtMs = file.lastModified(),
                    title = file.nameWithoutExtension,
                    durationMs = 0L
                )
            }
            ?: emptyList()
    }

    fun saveHighlight(file: File): HighlightEntity {
        val targetDir = getHighlightsDirectory()
        val destination = File(targetDir, "highlight_${timestamp()}.mp4")
        file.copyTo(destination, overwrite = true)

        return HighlightEntity(
            filePath = destination.absolutePath,
            createdAtMs = System.currentTimeMillis(),
            title = destination.nameWithoutExtension,
            durationMs = 0L
        )
    }

    fun deleteHighlight(entity: HighlightEntity) {
        val file = File(entity.filePath)
        if (file.exists()) file.delete()
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
    }
}
