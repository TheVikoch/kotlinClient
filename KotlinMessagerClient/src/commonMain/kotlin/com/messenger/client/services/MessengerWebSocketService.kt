package com.messenger.client.services

import com.messenger.client.models.MessageReadEventDto
import com.messenger.client.models.NewMessageEventDto
import com.messenger.client.models.TypingEventDto
import kotlinx.coroutines.flow.SharedFlow

expect class MessengerWebSocketService(
    serverUrl: String = defaultServerUrl
) {
    val newMessages: SharedFlow<NewMessageEventDto>
    val messageRead: SharedFlow<MessageReadEventDto>
    val typing: SharedFlow<TypingEventDto>
    val isConnected: Boolean

    fun connect(jwtToken: String): Boolean
    fun disconnect()
    fun joinConversation(conversationId: String)
    fun leaveConversation(conversationId: String)
    fun markAsRead(conversationId: String, messageId: String)
    fun sendTypingIndicator(conversationId: String, isTyping: Boolean, userName: String = "")
    fun sendMessage(conversationId: String, content: String, replyToMessageId: String? = null)
}
