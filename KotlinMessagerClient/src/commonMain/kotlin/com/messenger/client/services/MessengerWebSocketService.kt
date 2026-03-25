package com.messenger.client.services

import com.messenger.client.models.MessageReadEventDto
import com.messenger.client.models.NewMessageEventDto
import com.messenger.client.models.StreamTransferEvent
import com.messenger.client.models.StreamTransferInitRequestDto
import com.messenger.client.models.StreamTransferChunkDto
import com.messenger.client.models.TypingEventDto
import com.messenger.client.models.StreamTransferStartResponseDto
import kotlinx.coroutines.flow.SharedFlow

expect class MessengerWebSocketService(
    serverUrl: String = defaultServerUrl
) {
    val newMessages: SharedFlow<NewMessageEventDto>
    val messageRead: SharedFlow<MessageReadEventDto>
    val typing: SharedFlow<TypingEventDto>
    val streamEvents: SharedFlow<StreamTransferEvent>
    val isConnected: Boolean

    fun connect(jwtToken: String): Boolean
    fun disconnect()
    fun joinConversation(conversationId: String)
    fun leaveConversation(conversationId: String)
    fun markAsRead(conversationId: String, messageId: String)
    fun sendTypingIndicator(conversationId: String, isTyping: Boolean, userName: String = "")
    fun sendMessage(conversationId: String, content: String, replyToMessageId: String? = null)

    fun startStreamTransfer(request: StreamTransferInitRequestDto): StreamTransferStartResponseDto?
    fun acceptStreamTransfer(transferId: String)
    fun rejectStreamTransfer(transferId: String, reason: String? = null)
    fun sendStreamChunk(chunk: StreamTransferChunkDto)
    fun ackStreamChunks(transferId: String, seqs: List<Int>)
    fun nackStreamChunks(transferId: String, seqs: List<Int>)
    fun requestStreamTransferResume(transferId: String, missingSeqs: List<Int>)
    fun completeStreamTransfer(transferId: String)
    fun cancelStreamTransfer(transferId: String, reason: String? = null)
}
