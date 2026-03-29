package com.messenger.client.services

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val ChatMessagesCacheIvSize = 12
private const val ChatMessagesCacheTagSizeBits = 128
private const val ChatMessagesCacheVersion: Byte = 1
private const val ChatMessagesMemoryLimit = 20

actual fun chatMessagesCacheNowMillis(): Long = System.currentTimeMillis()

@Composable
actual fun rememberChatMessagesCache(): ChatMessagesCache {
    val context = LocalContext.current
    val baseDir = remember(context) {
        File(context.filesDir, "chat-cache").apply { mkdirs() }
    }
    return remember(baseDir) { FileChatMessagesCache(baseDir) }
}

private class FileChatMessagesCache(private val baseDir: File) : ChatMessagesCache {
    companion object {
        private val memoryCache = LinkedHashMap<String, ChatMessagesCacheEntry>(16, 0.75f, true)

        private fun readFromMemory(key: String): ChatMessagesCacheEntry? {
            return synchronized(memoryCache) { memoryCache[key] }
        }

        private fun writeToMemory(key: String, entry: ChatMessagesCacheEntry) {
            synchronized(memoryCache) {
                memoryCache[key] = entry
                while (memoryCache.size > ChatMessagesMemoryLimit) {
                    val oldestKey = memoryCache.entries.iterator().next().key
                    memoryCache.remove(oldestKey)
                }
            }
        }

        private fun removeFromMemory(key: String) {
            synchronized(memoryCache) {
                memoryCache.remove(key)
            }
        }
    }

    override fun read(conversationId: String, userId: String, secret: String): ChatMessagesCacheEntry? {
        val key = buildChatMessagesCacheKey(userId, conversationId)
        readFromMemory(key)?.let { return it }

        val file = File(baseDir, key)
        if (!file.exists()) return null

        val decrypted = decryptChatMessagesCache(file.readBytes(), secret) ?: return null
        val entry = deserializeChatMessagesCacheEntry(decrypted) ?: return null
        writeToMemory(key, entry)
        return entry
    }

    override fun write(conversationId: String, userId: String, secret: String, entry: ChatMessagesCacheEntry) {
        val key = buildChatMessagesCacheKey(userId, conversationId)
        val file = File(baseDir, key)
        file.parentFile?.mkdirs()
        file.writeBytes(encryptChatMessagesCache(serializeChatMessagesCacheEntry(entry), secret))
        writeToMemory(key, entry)
    }

    override fun delete(conversationId: String, userId: String) {
        val key = buildChatMessagesCacheKey(userId, conversationId)
        removeFromMemory(key)
        File(baseDir, key).delete()
    }
}

private fun encryptChatMessagesCache(plainBytes: ByteArray, secret: String): ByteArray {
    val iv = ByteArray(ChatMessagesCacheIvSize)
    SecureRandom().nextBytes(iv)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(deriveChatMessagesCacheKey(secret), "AES"),
        GCMParameterSpec(ChatMessagesCacheTagSizeBits, iv)
    )
    val encrypted = cipher.doFinal(plainBytes)
    return byteArrayOf(ChatMessagesCacheVersion) + iv + encrypted
}

private fun decryptChatMessagesCache(encryptedBytes: ByteArray, secret: String): ByteArray? {
    if (encryptedBytes.size <= ChatMessagesCacheIvSize + 1) return null
    if (encryptedBytes.first() != ChatMessagesCacheVersion) return null

    return runCatching {
        val iv = encryptedBytes.copyOfRange(1, 1 + ChatMessagesCacheIvSize)
        val payload = encryptedBytes.copyOfRange(1 + ChatMessagesCacheIvSize, encryptedBytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(deriveChatMessagesCacheKey(secret), "AES"),
            GCMParameterSpec(ChatMessagesCacheTagSizeBits, iv)
        )
        cipher.doFinal(payload)
    }.getOrNull()
}

private fun deriveChatMessagesCacheKey(secret: String): ByteArray {
    return MessageDigest.getInstance("SHA-256").digest(secret.encodeToByteArray())
}
