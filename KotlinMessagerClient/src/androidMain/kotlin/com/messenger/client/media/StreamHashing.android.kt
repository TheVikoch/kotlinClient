package com.messenger.client.media

import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.CRC32

actual fun sha256Base64(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(bytes)
    return Base64.encodeToString(hash, Base64.NO_WRAP)
}

actual fun sha256Base64ForFile(path: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(File(path)).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    val hash = digest.digest()
    return Base64.encodeToString(hash, Base64.NO_WRAP)
}

actual fun crc32Hex(bytes: ByteArray): String {
    val crc = CRC32()
    crc.update(bytes)
    return java.lang.Long.toHexString(crc.value)
}
