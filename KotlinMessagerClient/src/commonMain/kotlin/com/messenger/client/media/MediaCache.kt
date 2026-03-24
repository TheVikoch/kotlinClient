package com.messenger.client.media

import androidx.compose.runtime.Composable

interface MediaCache {
    fun readBytes(key: String): ByteArray?
    fun writeBytes(key: String, bytes: ByteArray)
    fun exists(key: String): Boolean
    fun getPath(key: String): String
}

@Composable
expect fun rememberMediaCache(): MediaCache

fun buildAttachmentCacheKey(
    conversationId: String,
    messageId: String,
    attachmentId: String,
    fileName: String
): String {
    val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
    return "${conversationId}_${messageId}_${attachmentId}_$safeName"
}
