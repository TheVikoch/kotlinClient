package com.messenger.client.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import javax.swing.JFileChooser

@Composable
actual fun rememberStreamFilePicker(
    onPicked: (StreamPickedFile) -> Unit,
    onError: (String) -> Unit
): FilePicker {
    return remember {
        object : FilePicker {
            override fun pickFiles() {
                try {
                    val chooser = JFileChooser().apply {
                        isMultiSelectionEnabled = false
                    }
                    val result = chooser.showOpenDialog(null)
                    if (result != JFileChooser.APPROVE_OPTION) return
                    val file = chooser.selectedFile
                    if (file == null || !file.exists()) return
                    val contentType = Files.probeContentType(file.toPath()) ?: "application/octet-stream"
                    val source = JvmStreamFileSource(file)
                    onPicked(StreamPickedFile(file.name, file.length(), contentType, source))
                } catch (e: Exception) {
                    onError(e.message ?: "Failed to pick file")
                }
            }
        }
    }
}

private class JvmStreamFileSource(
    private val file: File
) : StreamFileSource {
    private val lock = Any()
    private var raf: RandomAccessFile? = null
    private var channel: java.nio.channels.FileChannel? = null

    override fun readChunk(offset: Long, size: Int): ByteArray {
        if (size <= 0) return ByteArray(0)
        val fileChannel = ensureChannel() ?: return ByteArray(0)
        val buffer = ByteArray(size)
        var totalRead = 0
        val byteBuffer = ByteBuffer.wrap(buffer)
        while (totalRead < size) {
            val read = fileChannel.read(byteBuffer, offset + totalRead)
            if (read <= 0) break
            totalRead += read
        }
        return if (totalRead == size) buffer else buffer.copyOf(totalRead)
    }

    override fun computeSha256Base64(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        val hash = digest.digest()
        return Base64.getEncoder().encodeToString(hash)
    }

    override fun getSize(): Long {
        return file.length()
    }

    override fun close() {
        synchronized(lock) {
            runCatching { channel?.close() }
            runCatching { raf?.close() }
            channel = null
            raf = null
        }
    }

    private fun ensureChannel(): java.nio.channels.FileChannel? {
        synchronized(lock) {
            if (channel != null && raf != null) return channel
            val opened = RandomAccessFile(file, "r")
            raf = opened
            channel = opened.channel
            return channel
        }
    }
}
