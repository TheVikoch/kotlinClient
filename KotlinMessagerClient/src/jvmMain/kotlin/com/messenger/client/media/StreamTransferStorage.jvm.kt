package com.messenger.client.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import javax.swing.JFileChooser

@Composable
actual fun rememberStreamTransferStorage(): StreamTransferStorage {
    val tempDir = remember {
        File(System.getProperty("java.io.tmpdir"), "messenger-stream-temp").apply { mkdirs() }
    }
    return remember(tempDir) {
        object : StreamTransferStorage {
            private val writerLock = Any()
            private val writers = mutableMapOf<String, RandomAccessFile>()
            private val nextOffsets = mutableMapOf<String, Long>()

            override fun createTempFile(transferId: String, fileName: String, expectedSize: Long): String {
                val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                val file = File(tempDir, "${transferId}_$safeName.part")
                file.parentFile?.mkdirs()
                if (!file.exists()) {
                    file.createNewFile()
                }
                if (expectedSize > 0) {
                    RandomAccessFile(file, "rw").use { raf ->
                        if (raf.length() != expectedSize) {
                            raf.setLength(expectedSize)
                        }
                    }
                }
                return file.absolutePath
            }

            override fun writeChunk(path: String, offset: Long, data: ByteArray, dataOffset: Int, dataLength: Int) {
                val raf = synchronized(writerLock) {
                    writers.getOrPut(path) { RandomAccessFile(path, "rw") }
                }
                synchronized(raf) {
                    val nextOffset = synchronized(writerLock) { nextOffsets[path] ?: 0L }
                    if (nextOffset != offset) {
                        raf.seek(offset)
                    }
                    raf.write(data, dataOffset, dataLength)
                    synchronized(writerLock) {
                        nextOffsets[path] = offset + dataLength
                    }
                }
            }

            override fun availableBytes(): Long {
                return tempDir.usableSpace
            }

            override suspend fun pickSaveTarget(suggestedName: String, mimeType: String?): StreamSaveTarget? {
                return withContext(Dispatchers.IO) {
                    val chooser = JFileChooser().apply {
                        dialogTitle = "Выберите папку для сохранения"
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        isAcceptAllFileFilterUsed = false
                    }
                    val result = chooser.showSaveDialog(null)
                    if (result == JFileChooser.APPROVE_OPTION) {
                        StreamSaveTarget(
                            chooser.selectedFile.absolutePath,
                            fileName = suggestedName,
                            mimeType = mimeType?.ifBlank { null }
                        )
                    } else {
                        null
                    }
                }
            }

            override fun copyTempToTarget(tempPath: String, target: StreamSaveTarget): Result<Unit> {
                return runCatching {
                    closeWriter(tempPath)
                    val outFile = resolveUniqueFile(File(target.id), target.fileName)
                    outFile.parentFile?.mkdirs()
                    runCatching {
                        Files.move(File(tempPath).toPath(), outFile.toPath())
                    }.getOrElse {
                        File(tempPath).copyTo(outFile, overwrite = true)
                    }
                }
            }

            override fun deleteTempFile(tempPath: String) {
                closeWriter(tempPath)
                runCatching { File(tempPath).delete() }
            }

            private fun closeWriter(path: String) {
                val writer = synchronized(writerLock) {
                    nextOffsets.remove(path)
                    writers.remove(path)
                } ?: return
                runCatching { writer.close() }
            }
        }
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
