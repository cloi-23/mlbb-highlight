package com.mlbb.highlight.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.io.FileInputStream
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
        val directories = listOf(
            getHighlightsDirectory(),
            getRecordingsDirectory()
        )
        return directories.flatMap { directory ->
            directory.listFiles { file -> file.isFile && file.extension.equals("mp4", true) }
                ?.toList()
                .orEmpty()
        }.distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                HighlightEntity(
                    filePath = file.absolutePath,
                    createdAtMs = file.lastModified(),
                    title = file.nameWithoutExtension,
                    durationMs = 0L
                )
            }
    }

    fun getRecordingsDirectory(): File {
        val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "Recordings"
        )
        dir.mkdirs()
        return dir
    }

    fun createEditedOutputFile(): File {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        return File(getRecordingsDirectory(), "edited_$timestamp.mp4")
    }

    fun copyToFolderTree(file: File, folderTreeUri: Uri) {
        val treeDocumentId = try {
            DocumentsContract.getTreeDocumentId(folderTreeUri)
        } catch (exception: IllegalArgumentException) {
            throw IOException("The selected save folder is no longer available", exception)
        }
        val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            folderTreeUri,
            treeDocumentId
        )
        val destination = DocumentsContract.createDocument(
            context.contentResolver,
            parentDocumentUri,
            "video/mp4",
            file.name
        ) ?: throw IOException("Android could not create ${file.name} in the selected folder")

        val output = context.contentResolver.openOutputStream(destination, "w")
            ?: throw IOException("Android could not open the exported video destination")
        FileInputStream(file).use { input ->
            output.use { input.copyTo(it) }
        }
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
