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

    fun listHighlights(folderTreeUri: Uri? = null): List<HighlightEntity> {
        val directories = listOf(
            getHighlightsDirectory(),
            getRecordingsDirectory()
        )
        val localFiles = directories.flatMap { directory ->
            directory.listFiles { file -> file.isFile && file.extension.equals("mp4", true) }
                ?.toList()
                .orEmpty()
        }.map { file ->
            HighlightEntity(
                filePath = file.absolutePath,
                createdAtMs = file.lastModified(),
                title = file.nameWithoutExtension,
                durationMs = 0L
            )
        }
        val folderFiles = folderTreeUri?.let(::listFolderVideos).orEmpty()
        return (localFiles + folderFiles).distinctBy { it.filePath }
            .sortedByDescending { it.createdAtMs }
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

    fun copyToFolderTree(file: File, folderTreeUri: Uri): Uri {
        val destination = createVideoDocument(folderTreeUri, file.name)
        try {
            val output = context.contentResolver.openOutputStream(destination, "w")
                ?: throw IOException("Android could not open the exported video destination")
            FileInputStream(file).use { input ->
                output.use { input.copyTo(it) }
            }
        } catch (exception: IOException) {
            deletePartialDocument(destination, exception)
            throw exception
        } catch (exception: SecurityException) {
            deletePartialDocument(destination, exception)
            throw exception
        } catch (exception: IllegalArgumentException) {
            deletePartialDocument(destination, exception)
            throw exception
        }
        return destination
    }

    fun createVideoDocument(folderTreeUri: Uri, displayName: String): Uri {
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
            displayName
        ) ?: throw IOException("Android could not create $displayName in the selected folder")
        return destination
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
        val uri = Uri.parse(entity.filePath)
        if (uri.scheme == "content") {
            if (!DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                throw IOException("Android could not delete ${entity.title}")
            }
        } else {
            val file = File(entity.filePath)
            if (file.exists() && !file.delete()) {
                throw IOException("Could not delete ${file.name}")
            }
        }
    }

    private fun listFolderVideos(folderTreeUri: Uri): List<HighlightEntity> {
        val treeDocumentId = try {
            DocumentsContract.getTreeDocumentId(folderTreeUri)
        } catch (exception: IllegalArgumentException) {
            throw IOException("The selected save folder is no longer available", exception)
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            folderTreeUri,
            treeDocumentId
        )
        val results = mutableListOf<HighlightEntity>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modifiedColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                val mime = cursor.getString(mimeColumn).orEmpty()
                if (!mime.startsWith("video/") && !name.endsWith(".mp4", ignoreCase = true)) continue
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                    folderTreeUri,
                    cursor.getString(idColumn)
                )
                results += HighlightEntity(
                    filePath = documentUri.toString(),
                    createdAtMs = cursor.getLong(modifiedColumn),
                    title = name.substringBeforeLast('.'),
                    durationMs = 0L
                )
            }
        } ?: throw IOException("Android could not read the selected video folder")
        return results
    }

    private fun deletePartialDocument(uri: Uri, originalException: Exception) {
        try {
            if (!DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                throw IOException("Android could not remove the incomplete destination")
            }
        } catch (exception: IOException) {
            originalException.addSuppressed(exception)
        } catch (exception: SecurityException) {
            originalException.addSuppressed(exception)
        } catch (exception: IllegalArgumentException) {
            originalException.addSuppressed(exception)
        }
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
    }
}
