package com.messenger.client.services

import com.messenger.client.models.*
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess

class ApiService(private val serverUrl: String = defaultServerUrl) {
    private val client = createHttpClient()

    // Auth endpoints
    suspend fun register(email: String, password: String, displayName: String? = null): Result<AuthResponse> {
        return try {
            val response = client.post("$serverUrl/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterDto(email, password, displayName?.ifBlank { null }))
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Registration failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(email: String, password: String): Result<AuthResponse> {
        return try {
            val response = client.post("$serverUrl/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(LoginDto(email, password))
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Login failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSessions(token: String, userId: String): Result<List<SessionDto>> {
        return try {
            val response = client.get("$serverUrl/api/auth/sessions/$userId") {
                header("Authorization", "Bearer $token")
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Get sessions failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun revokeSession(token: String, sessionId: String, userId: String): Result<Unit> {
        return try {
            val response = client.post("$serverUrl/api/auth/sessions/revoke") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(RevokeSessionDto(sessionId, userId))
            }
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Revoke session failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Chat endpoints
    suspend fun createPersonalChat(token: String, identifier: String): Result<ConversationDto> {
        return try {
            val trimmed = identifier.trim()
            val isEmail = trimmed.contains("@")
            val payload = if (isEmail) {
                CreatePersonalChatDto(userEmail = trimmed)
            } else {
                CreatePersonalChatDto(userDisplayName = trimmed.ifBlank { null })
            }
            val response = client.post("$serverUrl/api/chat/personal") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(payload)
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Create personal chat failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createGroupChat(token: String, name: String, memberEmails: List<String>): Result<ConversationDto> {
        return try {
            val response = client.post("$serverUrl/api/chat/group") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(CreateGroupChatDto(name, memberEmails))
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Create group chat failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAllConversations(token: String): Result<List<ConversationDto>> {
        return try {
            val response = client.get("$serverUrl/api/chat") {
                header("Authorization", "Bearer $token")
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Get conversations failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getConversation(token: String, conversationId: String): Result<ConversationDto> {
        return try {
            val response = client.get("$serverUrl/api/chat/$conversationId") {
                header("Authorization", "Bearer $token")
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Get conversation failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addMemberToGroup(token: String, conversationId: String, identifier: String): Result<ConversationDto> {
        return try {
            val trimmed = identifier.trim()
            val isEmail = trimmed.contains("@")
            val payload = if (isEmail) {
                AddMemberDto(userEmail = trimmed)
            } else {
                AddMemberDto(userDisplayName = trimmed.ifBlank { null })
            }
            val response = client.post("$serverUrl/api/chat/$conversationId/members") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(payload)
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Add member failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeMemberFromGroup(token: String, conversationId: String, userId: String): Result<Unit> {
        return try {
            val response = client.delete("$serverUrl/api/chat/$conversationId/members/$userId") {
                header("Authorization", "Bearer $token")
            }
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Remove member failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Message endpoints
    suspend fun sendMessage(
        token: String,
        conversationId: String,
        content: String,
        replyToMessageId: String? = null
    ): Result<MessageDto> {
        return try {
            val response = client.post("$serverUrl/api/messages") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(SendMessageDto(conversationId, content, replyToMessageId))
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Send message failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMessages(
        token: String,
        conversationId: String,
        limit: Int = 50,
        cursor: String? = null
    ): Result<MessagesResponseDto> {
        return try {
            var url = "$serverUrl/api/messages/$conversationId?limit=$limit"
            cursor?.let { url += "&cursor=${it.encodeURLParameter()}" }

            val response = client.get(url) {
                header("Authorization", "Bearer $token")
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Get messages failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUnreadCount(token: String, conversationId: String): Result<UnreadCountDto> {
        return try {
            val response = client.get("$serverUrl/api/messages/$conversationId/unread-count") {
                header("Authorization", "Bearer $token")
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Get unread count failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markAsRead(token: String, conversationId: String, messageId: String): Result<Unit> {
        return try {
            val response = client.post("$serverUrl/api/messages/$conversationId/read/$messageId") {
                header("Authorization", "Bearer $token")
            }
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Mark as read failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
