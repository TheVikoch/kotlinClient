package com.messenger.client.services

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.messenger.client.models.MessageReadEventDto
import com.messenger.client.models.NewMessageEventDto
import com.messenger.client.models.TypingEventDto
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import io.reactivex.rxjava3.core.Single
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

actual class MessengerWebSocketService actual constructor(
    private val serverUrl: String
) {
    private val gson = Gson()
    private var connection: HubConnection? = null
    private var _isConnected: Boolean = false

    private val _newMessages = MutableSharedFlow<NewMessageEventDto>(extraBufferCapacity = 64)
    actual val newMessages: SharedFlow<NewMessageEventDto> = _newMessages

    private val _messageRead = MutableSharedFlow<MessageReadEventDto>(extraBufferCapacity = 64)
    actual val messageRead: SharedFlow<MessageReadEventDto> = _messageRead

    private val _typing = MutableSharedFlow<TypingEventDto>(extraBufferCapacity = 64)
    actual val typing: SharedFlow<TypingEventDto> = _typing

    actual val isConnected: Boolean
        get() = _isConnected

    actual fun connect(jwtToken: String): Boolean {
        return try {
            disconnect()

            val hub = HubConnectionBuilder.create("$serverUrl/messengerHub")
                .withAccessTokenProvider(Single.just(jwtToken))
                .build()

            registerHandlers(hub)

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
            "new_message" -> {
                val dto = gson.fromJson(eventData, NewMessageEventDto::class.java)
                if (dto != null) _newMessages.tryEmit(dto)
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
        }
    }

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
