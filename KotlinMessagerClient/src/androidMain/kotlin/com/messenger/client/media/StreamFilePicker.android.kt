package com.messenger.client.media

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

@Composable
actual fun rememberStreamFilePicker(
    onPicked: (StreamPickedFile) -> Unit,
    onError: (String) -> Unit
): FilePicker {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            runCatching {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val name = queryDisplayName(resolver, uri)
            val type = resolver.getType(uri) ?: "application/octet-stream"
            val size = querySize(resolver, uri).takeIf { it > 0 } ?: run {
                val stat = runCatching {
                    resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                }.getOrNull() ?: 0L
                if (stat > 0) stat else 0L
            }
            val source = AndroidStreamFileSource(context, resolver, uri)
            onPicked(StreamPickedFile(name, size, type, source))
        } catch (e: Exception) {
            onError(e.message ?: "Failed to pick file")
        }
    }

    return remember(launcher) {
        object : FilePicker {
            override fun pickFiles() {
                launcher.launch(arrayOf("*/*"))
            }
        }
    }
}

private fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String {
    val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                val value = it.getString(index)
                if (!value.isNullOrBlank()) return value
            }
        }
    }
    return "file"
}

private fun querySize(contentResolver: ContentResolver, uri: Uri): Long {
    val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && !it.isNull(index)) {
                val value = it.getLong(index)
                if (value >= 0) return value
            }
        }
    }
    return 0L
}

private class AndroidStreamFileSource(
    private val context: android.content.Context,
    private val resolver: ContentResolver,
    private val uri: Uri
) : StreamFileSource {
    private val lock = Any()
    private var pfd: ParcelFileDescriptor? = null
    private var channel: java.nio.channels.FileChannel? = null
    private var cacheFile: File? = null
    private var cacheRaf: java.io.RandomAccessFile? = null
    private var cachedSize: Long = 0

    override fun prepareForStreaming() {
        synchronized(lock) {
            ensureCacheFile() ?: throw IllegalStateException("РќРµ СѓРґР°Р»РѕСЃСЊ РїРѕРґРіРѕС‚РѕРІРёС‚СЊ С„Р°Р№Р» РґР»СЏ РѕС‚РїСЂР°РІРєРё")
        }
    }

    override fun readChunk(offset: Long, size: Int): ByteArray {
        if (size <= 0) return ByteArray(0)
        val buffer = ByteArray(size)
        var totalRead = 0
        synchronized(lock) {
            val fileChannel = ensureCacheChannel() ?: ensureChannel() ?: return ByteArray(0)
            fileChannel.position(offset)
            val byteBuffer = ByteBuffer.wrap(buffer)
            while (totalRead < size) {
                val read = fileChannel.read(byteBuffer)
                if (read <= 0) break
                totalRead += read
            }
        }
        return if (totalRead == size) buffer else buffer.copyOf(totalRead)
    }

    override fun computeSha256Base64(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (!ensureDirectAccess()) {
            return computeHashWithCache(digest)
        }
        val input = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Не удалось открыть файл")
        input.use {
            val buf = ByteArray(1024 * 1024)
            var total = 0L
            while (true) {
                val read = it.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
                total += read
            }
            if (total > 0) {
                cachedSize = total
            }
        }
        val hash = digest.digest()
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    override fun getSize(): Long {
        if (cachedSize > 0) return cachedSize
        val stat = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: 0L
        if (stat > 0) {
            cachedSize = stat
            return stat
        }
        if (!ensureDirectAccess()) {
            if (cacheFile != null) {
                cachedSize = cacheFile?.length() ?: 0L
                return cachedSize
            }
        }
        val input = resolver.openInputStream(uri) ?: return 0L
        input.use {
            val buf = ByteArray(1024 * 1024)
            var total = 0L
            while (true) {
                val read = it.read(buf)
                if (read <= 0) break
                total += read
            }
            cachedSize = total
            return total
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { channel?.close() }
            runCatching { pfd?.close() }
            runCatching { cacheRaf?.close() }
            cacheRaf = null
            channel = null
            pfd = null
            cacheFile?.let { runCatching { it.delete() } }
            cacheFile = null
        }
    }

    private fun ensureChannel(): java.nio.channels.FileChannel? {
        if (channel != null && pfd != null) return channel
        if (!ensureDirectAccess()) {
            return ensureCacheChannel()
        }
        val opened = resolver.openFileDescriptor(uri, "r") ?: return ensureCacheChannel()
        pfd = opened
        channel = FileInputStream(opened.fileDescriptor).channel
        return channel
    }

    private fun ensureDirectAccess(): Boolean {
        if (pfd != null || channel != null) return true
        val opened = resolver.openFileDescriptor(uri, "r") ?: return false
        pfd = opened
        channel = FileInputStream(opened.fileDescriptor).channel
        return true
    }

    private fun ensureCacheChannel(): java.nio.channels.FileChannel? {
        if (cacheRaf != null) return cacheRaf?.channel
        val file = ensureCacheFile() ?: return null
        val raf = java.io.RandomAccessFile(file, "r")
        cacheRaf = raf
        return raf.channel
    }

    private fun ensureCacheFile(): File? {
        if (cacheFile != null) return cacheFile
        val tempDir = File(context.cacheDir, "stream-send-cache").apply { mkdirs() }
        val outFile = File.createTempFile("stream_", ".bin", tempDir)
        val input = resolver.openInputStream(uri) ?: return null
        var total = 0L
        input.use { source ->
            FileOutputStream(outFile).use { output ->
                val buf = ByteArray(1024 * 1024)
                while (true) {
                    val read = source.read(buf)
                    if (read <= 0) break
                    output.write(buf, 0, read)
                    total += read
                }
                output.flush()
            }
        }
        cachedSize = if (total > 0) total else outFile.length()
        cacheFile = outFile
        cachedHash = null
        return outFile
    }

    private var cachedHash: String? = null

    private fun computeHashWithCache(digest: MessageDigest): String {
        cachedHash?.let { return it }
        val file = ensureCacheFile() ?: throw IllegalStateException("Не удалось открыть файл")
        if (cachedHash != null) return cachedHash!!
        FileInputStream(file).use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        val hash = Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
        cachedHash = hash
        return hash
    }
}
