package com.messenger.client.services

import com.messenger.client.models.*
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

    suspend fun searchUsers(token: String, query: String, limit: Int = 10): Result<List<UserSearchResultDto>> {
        return try {
            val trimmed = query.trim()
            if (trimmed.isBlank()) {
                Result.success(emptyList())
            } else {
                val response = client.get(
                    "$serverUrl/api/chat/users/search?query=${trimmed.encodeURLParameter()}&limit=$limit"
                ) {
                    header("Authorization", "Bearer $token")
                }
                if (response.status.isSuccess()) {
                    Result.success(response.body())
                } else {
                    Result.failure(buildApiException(response, "Search users failed"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMyProfile(token: String): Result<UserProfileDto> {
        return try {
            val response = client.get("$serverUrl/api/profile/me") {
                header("Authorization", "Bearer $token")
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(buildApiException(response, "Get profile failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserProfile(token: String, userId: String): Result<UserProfileDto> {
        return try {
            val response = client.get("$serverUrl/api/profile/$userId") {
                header("Authorization", "Bearer $token")
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(buildApiException(response, "Get profile failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateMyProfile(
        token: String,
        displayName: String,
        aboutMe: String?
    ): Result<UserProfileDto> {
        return try {
            val response = client.put("$serverUrl/api/profile/me") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(UpdateUserProfileDto(displayName = displayName, aboutMe = aboutMe))
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(buildApiException(response, "Update profile failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun initUserProfilePhotoUpload(
        token: String,
        fileName: String,
        contentType: String,
        size: Long
    ): Result<InitUserProfilePhotoUploadResponseDto> {
        return try {
            val response = client.post("$serverUrl/api/profile/me/photos/init") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(
                    InitUserProfilePhotoUploadRequestDto(
                        fileName = fileName,
                        contentType = contentType,
                        size = size
                    )
                )
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(buildApiException(response, "Init profile photo upload failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun completeUserProfilePhotoUpload(
        token: String,
        photoId: String
    ): Result<UserProfileDto> {
        return try {
            val response = client.post("$serverUrl/api/profile/me/photos/complete") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(CompleteUserProfilePhotoUploadRequestDto(photoId = photoId))
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(buildApiException(response, "Complete profile photo upload failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteUserProfilePhoto(
        token: String,
        photoId: String
    ): Result<UserProfileDto> {
        return try {
            val response = client.delete("$serverUrl/api/profile/me/photos/$photoId") {
                header("Authorization", "Bearer $token")
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(buildApiException(response, "Delete profile photo failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserProfilePhotoUrl(
        token: String,
        userId: String,
        photoId: String
    ): Result<MediaUrlResponseDto> {
        return try {
            val response = client.get("$serverUrl/api/profile/$userId/photos/$photoId/url") {
                header("Authorization", "Bearer $token")
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(buildApiException(response, "Get profile photo url failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createStreamInvite(
        token: String,
        personalChatId: String,
        streamChatName: String? = null
    ): Result<CreateStreamInviteResponseDto> {
        return try {
            val response = client.post("$serverUrl/api/stream-invites") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(CreateStreamInviteRequestDto(personalChatId, streamChatName))
            }
            if (response.status.isSuccess()) {
                val dto = response.body<CreateStreamInviteResponseDto>()
                val resolvedToken = dto.token.ifBlank { dto.tokenPascal.orEmpty() }
                val resolvedExpires = dto.expiresAt.ifBlank { dto.expiresAtPascal.orEmpty() }
                val resolved = if (resolvedToken == dto.token && resolvedExpires == dto.expiresAt) {
                    dto
                } else {
                    dto.copy(token = resolvedToken, expiresAt = resolvedExpires)
                }
                Result.success(resolved)
            } else {
                Result.failure(Exception("Create stream invite failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun acceptStreamInvite(
        token: String,
        inviteToken: String
    ): Result<AcceptStreamInviteResponseDto> {
        return try {
            val response = client.post("$serverUrl/api/stream-invites/accept") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(AcceptStreamInviteRequestDto(inviteToken))
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Accept stream invite failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun revokeStreamInvite(
        token: String,
        inviteId: String
    ): Result<Unit> {
        return try {
            val response = client.post("$serverUrl/api/stream-invites/revoke") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(RevokeStreamInviteRequestDto(inviteId))
            }
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Revoke stream invite failed: ${response.status}"))
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
        replyToMessageId: String? = null,
        attachmentIds: List<String> = emptyList()
    ): Result<MessageDto> {
        return try {
            val response = client.post("$serverUrl/api/messages") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(SendMessageDto(conversationId, content, replyToMessageId, attachmentIds))
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

    suspend fun initUpload(
        token: String,
        conversationId: String,
        fileName: String,
        contentType: String,
        size: Long
    ): Result<InitUploadResponseDto> {
        return try {
            val response = client.post("$serverUrl/api/media/init") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(InitUploadRequestDto(conversationId, fileName, contentType, size))
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Init upload failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun completeUpload(
        token: String,
        conversationId: String,
        attachmentId: String
    ): Result<Unit> {
        return try {
            val response = client.post("$serverUrl/api/media/complete") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(CompleteUploadRequestDto(conversationId, attachmentId))
            }
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Complete upload failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadToPresignedUrl(
        uploadUrl: String,
        bytes: ByteArray,
        contentType: String
    ): Result<Unit> {
        return try {
            val response = client.put(uploadUrl) {
                contentType(ContentType.parse(contentType))
                setBody(bytes)
            }
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Upload failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMediaUrl(
        token: String,
        conversationId: String,
        messageId: String,
        attachmentId: String
    ): Result<MediaUrlResponseDto> {
        return try {
            val response = client.get("$serverUrl/api/media/$conversationId/$messageId/$attachmentId/url") {
                header("Authorization", "Bearer $token")
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("Get media url failed: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadMediaBytes(url: String): Result<ByteArray> {
        return try {
            val bytes = client.get(url).body<ByteArray>()
            Result.success(bytes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun buildApiException(response: HttpResponse, fallback: String): Exception {
        val raw = runCatching { response.bodyAsText() }.getOrDefault("")
        val message = runCatching {
            Json.parseToJsonElement(raw)
                .jsonObject["message"]
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()

        return Exception(message?.takeIf { it.isNotBlank() } ?: "$fallback: ${response.status}")
    }
}
