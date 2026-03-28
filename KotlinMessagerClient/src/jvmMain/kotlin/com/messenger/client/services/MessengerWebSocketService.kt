package com.messenger.client.services

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.messenger.client.models.ConversationCreatedEventDto
import com.messenger.client.models.ConversationDeletedEventDto
import com.messenger.client.models.MessageDeletedEventDto
import com.messenger.client.models.MessageReadEventDto
import com.messenger.client.models.MessageUpdatedEventDto
import com.messenger.client.models.NewMessageEventDto
import com.messenger.client.models.StreamTransferAcceptedDto
import com.messenger.client.models.StreamTransferAckDto
import com.messenger.client.models.StreamTransferCanceledDto
import com.messenger.client.models.StreamTransferChunkDto
import com.messenger.client.models.StreamTransferCompleteRequestDto
import com.messenger.client.models.StreamTransferCompletedDto
import com.messenger.client.models.StreamTransferEvent
import com.messenger.client.models.StreamTransferInitRequestDto
import com.messenger.client.models.StreamTransferNackDto
import com.messenger.client.models.StreamTransferOfferDto
import com.messenger.client.models.StreamTransferRejectRequestDto
import com.messenger.client.models.StreamTransferRejectedDto
import com.messenger.client.models.StreamTransferResumeRequestDto
import com.messenger.client.models.StreamTransferStartResponseDto
import com.messenger.client.models.StreamTransferAcceptRequestDto
import com.messenger.client.models.StreamTransferCancelRequestDto
import com.messenger.client.models.TypingEventDto
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import io.reactivex.rxjava3.core.Single
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

actual class MessengerWebSocketService actual constructor(
    actual val serverUrl: String
) {
    private val gson = Gson()
    private val gsonUpper = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.UPPER_CAMEL_CASE)
        .create()
    private var connection: HubConnection? = null
    private var _isConnected: Boolean = false
    private var activeToken: String? = null

    actual val currentToken: String?
        get() = activeToken

    private val _conversationCreated = MutableSharedFlow<ConversationCreatedEventDto>(extraBufferCapacity = 32)
    actual val conversationCreated: SharedFlow<ConversationCreatedEventDto> = _conversationCreated

    private val _conversationDeleted = MutableSharedFlow<ConversationDeletedEventDto>(extraBufferCapacity = 32)
    actual val conversationDeleted: SharedFlow<ConversationDeletedEventDto> = _conversationDeleted

    private val _newMessages = MutableSharedFlow<NewMessageEventDto>(extraBufferCapacity = 64)
    actual val newMessages: SharedFlow<NewMessageEventDto> = _newMessages

    private val _messageUpdated = MutableSharedFlow<MessageUpdatedEventDto>(extraBufferCapacity = 64)
    actual val messageUpdated: SharedFlow<MessageUpdatedEventDto> = _messageUpdated

    private val _messageDeleted = MutableSharedFlow<MessageDeletedEventDto>(extraBufferCapacity = 64)
    actual val messageDeleted: SharedFlow<MessageDeletedEventDto> = _messageDeleted

    private val _messageRead = MutableSharedFlow<MessageReadEventDto>(extraBufferCapacity = 64)
    actual val messageRead: SharedFlow<MessageReadEventDto> = _messageRead

    private val _typing = MutableSharedFlow<TypingEventDto>(extraBufferCapacity = 64)
    actual val typing: SharedFlow<TypingEventDto> = _typing

    private val _streamEvents = MutableSharedFlow<StreamTransferEvent>(extraBufferCapacity = 256)
    actual val streamEvents: SharedFlow<StreamTransferEvent> = _streamEvents

    actual val isConnected: Boolean
        get() = connection?.connectionState == HubConnectionState.CONNECTED

    actual fun connect(jwtToken: String): Boolean {
        return try {
            activeToken = jwtToken
            val existing = connection
            if (existing != null) {
                when (existing.connectionState) {
                    HubConnectionState.CONNECTED -> {
                        _isConnected = true
                        return true
                    }
                    HubConnectionState.CONNECTING -> {
                        return false
                    }
                    HubConnectionState.DISCONNECTED -> {
                        disconnect()
                    }
                }
            }

            val hub = HubConnectionBuilder.create("$serverUrl/messengerHub")
                .withAccessTokenProvider(Single.just(jwtToken))
                .build()

            registerHandlers(hub)
            hub.onClosed { error ->
                _isConnected = false
                if (connection === hub) {
                    connection = null
                }
                println("[WS] Hub closed: ${error?.message ?: "normal"}")
            }

            hub.start().blockingAwait()
            connection = hub
            _isConnected = true
            true
        } catch (e: Exception) {
            _isConnected = false
            false
        }
    }

    actual fun disconnect() {
        try {
            connection?.stop()?.blockingAwait()
        } catch (_: Exception) {
        } finally {
            connection = null
            _isConnected = false
            activeToken = null
        }
    }

    actual fun joinConversation(conversationId: String) {
        if (!_isConnected) return
        val uuid = toUuid(conversationId) ?: return
        connection?.send("JoinConversation", uuid)
    }

    actual fun leaveConversation(conversationId: String) {
        if (!_isConnected) return
        val uuid = toUuid(conversationId) ?: return
        connection?.send("LeaveConversation", uuid)
    }

    actual fun markAsRead(conversationId: String, messageId: String) {
        if (!_isConnected) return
        val uuid = toUuid(conversationId) ?: return
        connection?.send("MarkMessageAsRead", uuid, messageId)
    }

    actual fun sendTypingIndicator(conversationId: String, isTyping: Boolean, userName: String) {
        if (!_isConnected) return
        val uuid = toUuid(conversationId) ?: return
        connection?.send("SendTypingIndicator", uuid, isTyping, userName)
    }

    actual fun sendMessage(conversationId: String, content: String, replyToMessageId: String?) {
        if (!_isConnected) return
        val uuid = toUuid(conversationId) ?: return
        connection?.send("SendMessage", uuid, content, replyToMessageId)
    }

    actual fun startStreamTransfer(request: StreamTransferInitRequestDto): StreamTransferStartResponseDto? {
        if (!_isConnected) return null
        return try {
            connection?.invoke(StreamTransferStartResponseDto::class.java, "StartStreamTransfer", request)
                ?.blockingGet()
        } catch (_: Exception) {
            null
        }
    }

    actual fun acceptStreamTransfer(transferId: String) {
        if (!_isConnected) return
        connection?.send("AcceptStreamTransfer", StreamTransferAcceptRequestDto(transferId))
    }

    actual fun rejectStreamTransfer(transferId: String, reason: String?) {
        if (!_isConnected) return
        connection?.send("RejectStreamTransfer", StreamTransferRejectRequestDto(transferId, reason))
    }

    actual fun sendStreamChunk(chunk: StreamTransferChunkDto) {
        if (!_isConnected) return
        connection?.send("SendStreamChunk", chunk)
    }

    actual fun ackStreamChunks(transferId: String, seqs: List<Int>, ackUpToSeq: Int) {
        if (!_isConnected) return
        connection?.send("AckStreamChunks", StreamTransferAckDto(transferId, seqs, ackUpToSeq))
    }

    actual fun nackStreamChunks(transferId: String, seqs: List<Int>) {
        if (!_isConnected) return
        connection?.send("NackStreamChunks", StreamTransferNackDto(transferId, seqs))
    }

    actual fun requestStreamTransferResume(transferId: String, missingSeqs: List<Int>) {
        if (!_isConnected) return
        connection?.send("RequestStreamTransferResume", StreamTransferResumeRequestDto(transferId, missingSeqs))
    }

    actual fun completeStreamTransfer(transferId: String) {
        if (!_isConnected) return
        connection?.send("CompleteStreamTransfer", StreamTransferCompleteRequestDto(transferId))
    }

    actual fun cancelStreamTransfer(transferId: String, reason: String?) {
        if (!_isConnected) return
        connection?.send("CancelStreamTransfer", StreamTransferCancelRequestDto(transferId, reason))
    }

    private fun registerHandlers(hub: HubConnection) {
        hub.on(
            "ReceiveEvent",
            { eventType: String, eventData: JsonElement ->
                handleReceiveEvent(eventType, eventData)
            },
            String::class.java,
            JsonElement::class.java
        )
        hub.on(
            "ReceiveEvent",
            { event: Any ->
                handleReceiveEvent(event)
            },
            Any::class.java
        )
        hub.on(
            "ReceiveEvent",
            { eventData: JsonElement ->
                handleReceiveEvent(eventData)
            },
            JsonElement::class.java
        )

        hub.on("NewMessage", { data: NewMessageEventDto ->
            _newMessages.tryEmit(data)
        }, NewMessageEventDto::class.java)

        hub.on("MessageSent", { data: NewMessageEventDto ->
            _newMessages.tryEmit(data)
        }, NewMessageEventDto::class.java)

        hub.on("NewMessageEvent", { data: NewMessageEventDto ->
            _newMessages.tryEmit(data)
        }, NewMessageEventDto::class.java)

        hub.on("MessageCreated", { data: NewMessageEventDto ->
            _newMessages.tryEmit(data)
        }, NewMessageEventDto::class.java)

        hub.on("ConversationCreated", { data: ConversationCreatedEventDto ->
            _conversationCreated.tryEmit(data)
        }, ConversationCreatedEventDto::class.java)

        hub.on("MessageRead", { data: MessageReadEventDto ->
            _messageRead.tryEmit(data)
        }, MessageReadEventDto::class.java)

        hub.on("Typing", { data: TypingEventDto ->
            _typing.tryEmit(data)
        }, TypingEventDto::class.java)
    }

    private fun handleReceiveEvent(eventType: String, eventData: JsonElement) {
        val normalized = eventType.lowercase()
        when (normalized) {
            "conversation_created" -> {
                val dto = parseDto(eventData, ConversationCreatedEventDto::class.java) {
                    it.conversation.id.isNotBlank()
                }
                if (dto?.conversation?.id?.isNotBlank() == true) {
                    _conversationCreated.tryEmit(dto)
                }
            }
            "conversation_deleted" -> {
                val dto = gson.fromJson(eventData, ConversationDeletedEventDto::class.java)
                if (dto?.conversationId?.isNotBlank() == true) {
                    _conversationDeleted.tryEmit(dto)
                }
            }
            "new_message" -> {
                val dto = gson.fromJson(eventData, NewMessageEventDto::class.java)
                if (dto != null) _newMessages.tryEmit(dto)
            }
            "message_updated" -> {
                val dto = gson.fromJson(eventData, MessageUpdatedEventDto::class.java)
                if (dto?.conversationId?.isNotBlank() == true && dto.message.id.isNotBlank()) {
                    _messageUpdated.tryEmit(dto)
                }
            }
            "message_deleted" -> {
                val dto = gson.fromJson(eventData, MessageDeletedEventDto::class.java)
                if (dto?.conversationId?.isNotBlank() == true && dto.messageId.isNotBlank()) {
                    _messageDeleted.tryEmit(dto)
                }
            }
            "message_read" -> {
                val obj = eventData.asJsonObject
                val conversationId = obj.getString("conversationId", "ConversationId")
                val messageId = obj.getString("messageId", "MessageId")
                val readByUserId = obj.getString("readByUserId", "ReadByUserId")
                if (conversationId.isNotBlank() && messageId.isNotBlank()) {
                    _messageRead.tryEmit(
                        MessageReadEventDto(
                            type = eventType,
                            timestamp = obj.getString("timestamp", "Timestamp").ifBlank { null },
                            userId = obj.getString("userId", "UserId").ifBlank { null },
                            conversationId = conversationId,
                            messageId = messageId,
                            readByUserId = readByUserId
                        )
                    )
                }
            }
            "typing" -> {
                val obj = eventData.asJsonObject
                val conversationId = obj.getString("conversationId", "ConversationId")
                val isTyping = obj.getBoolean("isTyping", "IsTyping")
                if (conversationId.isNotBlank()) {
                    _typing.tryEmit(
                        TypingEventDto(
                            type = eventType,
                            timestamp = obj.getString("timestamp", "Timestamp").ifBlank { null },
                            userId = obj.getString("userId", "UserId").ifBlank { null },
                            conversationId = conversationId,
                            isTyping = isTyping,
                            userName = obj.getString("userName", "UserName")
                        )
                    )
                }
            }
            "stream_transfer_offer" -> {
                val dto = parseStreamDto(eventData, StreamTransferOfferDto::class.java) { it.transferId }
                if (dto != null) _streamEvents.tryEmit(StreamTransferEvent.Offer(dto))
            }
            "stream_transfer_accepted" -> {
                val dto = parseStreamDto(eventData, StreamTransferAcceptedDto::class.java) { it.transferId }
                if (dto != null) _streamEvents.tryEmit(StreamTransferEvent.Accepted(dto))
            }
            "stream_transfer_rejected" -> {
                val dto = parseStreamDto(eventData, StreamTransferRejectedDto::class.java) { it.transferId }
                if (dto != null) _streamEvents.tryEmit(StreamTransferEvent.Rejected(dto))
            }
            "stream_transfer_chunk" -> {
                val dto = parseStreamDto(eventData, StreamTransferChunkDto::class.java) { it.transferId }
                if (dto != null) _streamEvents.tryEmit(StreamTransferEvent.Chunk(dto))
            }
            "stream_transfer_ack" -> {
                val dto = parseStreamDto(eventData, StreamTransferAckDto::class.java) { it.transferId }
                if (dto != null) _streamEvents.tryEmit(StreamTransferEvent.Ack(dto))
            }
            "stream_transfer_nack" -> {
                val dto = parseStreamDto(eventData, StreamTransferNackDto::class.java) { it.transferId }
                if (dto != null) _streamEvents.tryEmit(StreamTransferEvent.Nack(dto))
            }
            "stream_transfer_resume" -> {
                val dto = parseStreamDto(eventData, StreamTransferResumeRequestDto::class.java) { it.transferId }
                if (dto != null) _streamEvents.tryEmit(StreamTransferEvent.Resume(dto))
            }
            "stream_transfer_complete" -> {
                val dto = parseStreamDto(eventData, StreamTransferCompletedDto::class.java) { it.transferId }
                if (dto != null) _streamEvents.tryEmit(StreamTransferEvent.Complete(dto))
            }
            "stream_transfer_canceled" -> {
                val dto = parseStreamDto(eventData, StreamTransferCanceledDto::class.java) { it.transferId }
                if (dto != null) _streamEvents.tryEmit(StreamTransferEvent.Canceled(dto))
            }
        }
    }

    private fun <T> parseDto(
        eventData: JsonElement,
        clazz: Class<T>,
        isValid: (T) -> Boolean = { true }
    ): T? {
        val primary = runCatching { gson.fromJson(eventData, clazz) }.getOrNull()
        if (primary != null && isValid(primary)) return primary
        val fallback = runCatching { gsonUpper.fromJson(eventData, clazz) }.getOrNull()
        return when {
            fallback != null && isValid(fallback) -> fallback
            primary != null && isValid(primary) -> primary
            fallback != null -> fallback
            else -> primary
        }
    }

    private fun <T> parseStreamDto(
        eventData: JsonElement,
        clazz: Class<T>,
        idProvider: (T) -> String
    ): T? = parseDto(eventData, clazz) { idProvider(it).isNotBlank() }

    private fun handleReceiveEvent(eventData: JsonElement) {
        val obj = eventData.asJsonObject
        val type = obj.getString("type", "Type")
        if (type.isNotBlank()) {
            handleReceiveEvent(type, eventData)
        }
    }

    private fun handleReceiveEvent(event: Any) {
        val json = gson.toJsonTree(event)
        if (json.isJsonObject) {
            handleReceiveEvent(json)
        }
    }

    private fun JsonElement?.asStringOrBlank(): String {
        if (this == null || !this.isJsonPrimitive) {
            return ""
        }
        val value = this.asString
        return value ?: ""
    }

    private fun com.google.gson.JsonObject.getString(vararg keys: String): String {
        for (key in keys) {
            val value = get(key).asStringOrBlank()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun com.google.gson.JsonObject.getBoolean(vararg keys: String): Boolean {
        for (key in keys) {
            val value = get(key)
            if (value != null && value.isJsonPrimitive) {
                return try {
                    value.asBoolean
                } catch (_: Exception) {
                    val asString = value.asStringOrBlank()
                    if (asString.equals("true", true)) return true
                    if (asString.equals("false", true)) return false
                    false
                }
            }
        }
        return false
    }

    private fun toUuid(value: String): UUID? {
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            null
        }
    }
}
