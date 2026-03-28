package com.messenger.client.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

@Composable
actual fun rememberMediaCache(): MediaCache {
    val context = LocalContext.current
    val baseDir = remember(context) {
        File(context.filesDir, "media-cache").apply { mkdirs() }
    }
    return remember(baseDir) { FileMediaCache(baseDir) }
}

private class FileMediaCache(private val baseDir: File) : MediaCache {
    override fun readBytes(key: String): ByteArray? {
        val file = File(baseDir, key)
        return if (file.exists()) file.readBytes() else null
    }

    override fun writeBytes(key: String, bytes: ByteArray) {
        val file = File(baseDir, key)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override fun exists(key: String): Boolean {
        return File(baseDir, key).exists()
    }

    override fun getPath(key: String): String {
        return File(baseDir, key).absolutePath
    }

    override fun delete(key: String) {
        File(baseDir, key).delete()
    }
}
