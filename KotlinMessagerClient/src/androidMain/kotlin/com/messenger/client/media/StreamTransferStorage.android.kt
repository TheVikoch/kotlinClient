package com.messenger.client.media

import android.content.Intent
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.file.Files

@Composable
actual fun rememberStreamTransferStorage(): StreamTransferStorage {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val tempDir = remember(context) {
        File(context.cacheDir, "stream-temp").apply { mkdirs() }
    }
    val pending = remember { mutableStateOf<CompletableDeferred<StreamSaveTarget?>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val deferred = pending.value
        pending.value = null
        if (uri != null) {
            runCatching {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        deferred?.complete(uri?.let { StreamSaveTarget(it.toString(), fileName = "", isContentUri = true) })
    }

    return remember(tempDir, resolver, launcher) {
        object : StreamTransferStorage {
            private val writerLock = Any()
            private val writers = mutableMapOf<String, RandomAccessFile>()

            override fun createTempFile(transferId: String, fileName: String): String {
                val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                val file = File(tempDir, "${transferId}_$safeName.part")
                file.parentFile?.mkdirs()
                if (!file.exists()) {
                    file.createNewFile()
                }
                return file.absolutePath
            }

            override fun writeChunk(path: String, offset: Long, data: ByteArray) {
                val raf = synchronized(writerLock) {
                    writers.getOrPut(path) { RandomAccessFile(path, "rw") }
                }
                synchronized(raf) {
                    raf.seek(offset)
                    raf.write(data)
                }
            }

            override fun availableBytes(): Long {
                val stat = StatFs(tempDir.absolutePath)
                return stat.availableBytes
            }

            override suspend fun pickSaveTarget(suggestedName: String, mimeType: String?): StreamSaveTarget? {
                val deferred = CompletableDeferred<StreamSaveTarget?>()
                pending.value = deferred
                launcher.launch(null)
                val target = deferred.await()
                return target?.copy(fileName = suggestedName, mimeType = mimeType?.ifBlank { null })
            }

            override fun copyTempToTarget(tempPath: String, target: StreamSaveTarget): Result<Unit> {
                return runCatching {
                    closeWriter(tempPath)
                    if (!target.isContentUri) {
                        val outFile = resolveUniqueFile(File(target.id), target.fileName)
                        outFile.parentFile?.mkdirs()
                        runCatching {
                            Files.move(File(tempPath).toPath(), outFile.toPath())
                        }.getOrElse {
                            File(tempPath).copyTo(outFile, overwrite = true)
                        }
                        return@runCatching
                    }
                    val treeUri = android.net.Uri.parse(target.id)
                    val dir = DocumentFile.fromTreeUri(context, treeUri)
                        ?: error("Failed to open target directory")
                    val uniqueName = resolveUniqueName(dir, target.fileName)
                    val fileDoc = dir.createFile(
                        target.mimeType ?: "application/octet-stream",
                        uniqueName
                    )
                    val fileUri = fileDoc?.uri ?: error("Failed to create target file")
                    resolver.openOutputStream(fileUri)?.use { output ->
                        FileInputStream(File(tempPath)).use { input ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                        }
                    } ?: error("Failed to open output stream")
                }
            }

            override fun deleteTempFile(tempPath: String) {
                closeWriter(tempPath)
                runCatching { File(tempPath).delete() }
            }

            private fun closeWriter(path: String) {
                val writer = synchronized(writerLock) { writers.remove(path) } ?: return
                runCatching { writer.close() }
            }
        }
    }
}

private fun resolveUniqueName(dir: DocumentFile, fileName: String): String {
    if (dir.findFile(fileName) == null) return fileName
    val dotIndex = fileName.lastIndexOf('.')
    val base = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
    val ext = if (dotIndex > 0) fileName.substring(dotIndex) else ""
    var index = 1
    while (true) {
        val candidate = "$base ($index)$ext"
        if (dir.findFile(candidate) == null) return candidate
        index++
    }
}

private fun resolveUniqueFile(directory: File, fileName: String): File {
    if (!directory.exists()) {
        directory.mkdirs()
    }
    var candidate = File(directory, fileName)
    if (!candidate.exists()) return candidate
    val dotIndex = fileName.lastIndexOf('.')
    val base = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
    val ext = if (dotIndex > 0) fileName.substring(dotIndex) else ""
    var index = 1
    while (candidate.exists()) {
        candidate = File(directory, "$base ($index)$ext")
        index++
    }
    return candidate
}
