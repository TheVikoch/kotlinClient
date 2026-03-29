package com.messenger.client.services

import androidx.compose.runtime.Composable
import com.messenger.client.models.MessageDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val chatMessagesCacheJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

const val ChatMessagesWarmCacheDurationMs: Long = 30_000L

@Serializable
data class ChatMessagesCacheEntry(
    val messages: List<MessageDto> = emptyList(),
    val unreadCount: Int = 0,
    val hasMoreHistory: Boolean = false,
    val nextHistoryCursor: String? = null,
    val cachedAtEpochMillis: Long = 0L
) {
    fun isWarm(nowEpochMillis: Long, maxAgeMillis: Long = ChatMessagesWarmCacheDurationMs): Boolean {
        return cachedAtEpochMillis > 0L && nowEpochMillis - cachedAtEpochMillis <= maxAgeMillis
    }
}

interface ChatMessagesCache {
    fun read(conversationId: String, userId: String, secret: String): ChatMessagesCacheEntry?
    fun write(conversationId: String, userId: String, secret: String, entry: ChatMessagesCacheEntry)
    fun delete(conversationId: String, userId: String)
}

expect fun chatMessagesCacheNowMillis(): Long

@Composable
expect fun rememberChatMessagesCache(): ChatMessagesCache

fun buildChatMessagesCacheEntry(
    messages: List<MessageDto>,
    unreadCount: Int,
    hasMoreHistory: Boolean,
    nextHistoryCursor: String?
): ChatMessagesCacheEntry {
    return ChatMessagesCacheEntry(
        messages = messages,
        unreadCount = unreadCount,
        hasMoreHistory = hasMoreHistory,
        nextHistoryCursor = nextHistoryCursor,
        cachedAtEpochMillis = chatMessagesCacheNowMillis()
    )
}

fun buildChatMessagesCacheSecret(
    userId: String?,
    refreshToken: String?,
    jwtToken: String?,
    sessionId: String?
): String? {
    val safeUserId = userId?.ifBlank { null } ?: return null
    val safeSecret = refreshToken?.ifBlank { null }
        ?: jwtToken?.ifBlank { null }
        ?: return null
    val safeSessionId = sessionId?.ifBlank { null }.orEmpty()
    return "$safeUserId::$safeSessionId::$safeSecret"
}

internal fun buildChatMessagesCacheKey(userId: String, conversationId: String): String {
    val safeUserId = userId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val safeConversationId = conversationId.replace(Regex("[^A-Za-z0-9_-]"), "_")
    return "${safeUserId}_${safeConversationId}.bin"
}

internal fun serializeChatMessagesCacheEntry(entry: ChatMessagesCacheEntry): ByteArray {
    return chatMessagesCacheJson.encodeToString(entry).encodeToByteArray()
}

internal fun deserializeChatMessagesCacheEntry(bytes: ByteArray): ChatMessagesCacheEntry? {
    return runCatching {
        chatMessagesCacheJson.decodeFromString<ChatMessagesCacheEntry>(bytes.decodeToString())
    }.getOrNull()
}
