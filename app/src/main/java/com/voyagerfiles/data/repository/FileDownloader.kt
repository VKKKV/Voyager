package com.voyagerfiles.data.repository

import com.voyagerfiles.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FileDownloader {

    suspend fun download(
        provider: FileProvider,
        items: List<FileItem>,
        destinationDirectory: File,
    ): Result<DownloadResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
                    throw IllegalStateException("Could not create Downloads folder")
                }
                if (!destinationDirectory.isDirectory) {
                    throw IllegalStateException("Downloads path is not a folder")
                }

                val counts = DownloadCounts()
                items.forEach { item ->
                    downloadItem(provider, item, destinationDirectory, counts)
                }
                DownloadResult(
                    requestedItems = items.size,
                    downloadedFiles = counts.files,
                    downloadedDirectories = counts.directories,
                    destinationPath = destinationDirectory.absolutePath,
                )
            }
        }

    private suspend fun downloadItem(
        provider: FileProvider,
        item: FileItem,
        destinationDirectory: File,
        counts: DownloadCounts,
    ) {
        if (item.isDirectory) {
            val targetDirectory = uniqueChild(destinationDirectory, item.safeName(), isDirectory = true)
            if (!targetDirectory.mkdirs()) {
                throw IllegalStateException("Could not create folder: ${targetDirectory.name}")
            }
            counts.directories++

            val children = provider.listFiles(item.path).getOrThrow()
            children.forEach { child ->
                downloadItem(provider, child, targetDirectory, counts)
            }
            return
        }

        val targetFile = uniqueChild(destinationDirectory, item.safeName(), isDirectory = false)
        provider.getInputStream(item.path).getOrThrow().use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        counts.files++
    }

    private fun uniqueChild(parent: File, name: String, isDirectory: Boolean): File {
        var candidate = File(parent, name)
        if (!candidate.exists()) return candidate

        val extensionStart = name.lastIndexOf('.').takeIf { !isDirectory && it > 0 && it < name.lastIndex }
        val baseName = extensionStart?.let { name.substring(0, it) } ?: name
        val extension = extensionStart?.let { name.substring(it) } ?: ""

        var index = 1
        while (candidate.exists()) {
            candidate = File(parent, "$baseName ($index)$extension")
            index++
        }
        return candidate
    }

    private fun FileItem.safeName(): String =
        name.ifBlank { path.substringAfterLast("/").ifBlank { "download" } }
            .replace(Regex("""[\\/:*?"<>|]"""), "_")

    private class DownloadCounts {
        var files: Int = 0
        var directories: Int = 0
    }
}

data class DownloadResult(
    val requestedItems: Int,
    val downloadedFiles: Int,
    val downloadedDirectories: Int,
    val destinationPath: String,
)
