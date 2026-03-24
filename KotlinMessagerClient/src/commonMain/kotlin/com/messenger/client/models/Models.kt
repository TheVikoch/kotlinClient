package com.messenger.client.models

import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: String = "",
    val email: String = "",
    val displayName: String = ""
)

@Serializable
data class ConversationMemberDto(
    val userId: String = "",
    val user: UserDto = UserDto(),
    val role: String = "",
    val joinedAt: String = "",
    val isPinned: Boolean = false
)

@Serializable
data class ConversationDto(
    val id: String = "",
    val type: String = "",
    val name: String? = null,
    val createdAt: String = "",
    val lastMessageAt: String? = null,
    val lastMessageContent: String? = null,
    val isDeleted: Boolean = false,
    val members: List<ConversationMemberDto> = emptyList()
)

@Serializable
data class MessageDto(
    val id: String = "",
    val conversationId: String = "",
    val senderId: String = "",
    val sender: UserDto = UserDto(),
    val content: String = "",
    val sentAt: String = "",
    val isDeleted: Boolean = false,
    val replyToMessageId: String? = null,
    val attachments: List<MessageAttachmentDto> = emptyList()
)

@Serializable
data class MessagesResponseDto(
    val messages: List<MessageDto> = emptyList(),
    val hasMore: Boolean = false,
    val nextCursor: String? = null
)

@Serializable
data class UnreadCountDto(
    val count: Int = 0
)

@Serializable
data class AddMemberDto(
    val userEmail: String? = null,
    val userDisplayName: String? = null
)

@Serializable
data class RemoveMemberDto(
    val userId: String = ""
)

@Serializable
data class CreatePersonalChatDto(
    val userEmail: String? = null,
    val userDisplayName: String? = null
)

@Serializable
data class CreateGroupChatDto(
    val name: String = "",
    val memberEmails: List<String> = emptyList()
)

@Serializable
data class SendMessageDto(
    val conversationId: String = "",
    val content: String = "",
    val replyToMessageId: String? = null,
    val attachmentIds: List<String> = emptyList()
)

@Serializable
data class MediaEncryptionMetadataDto(
    val algorithm: String? = null,
    val keyId: String? = null,
    val iv: String? = null
)

@Serializable
data class MessageAttachmentDto(
    val id: String = "",
    val fileName: String = "",
    val contentType: String = "",
    val size: Long = 0,
    val status: String = "",
    val createdAt: String = "",
    val encryption: MediaEncryptionMetadataDto? = null
)

@Serializable
data class InitUploadRequestDto(
    val conversationId: String = "",
    val fileName: String = "",
    val contentType: String = "",
    val size: Long = 0
)

@Serializable
data class InitUploadResponseDto(
    val attachmentId: String = "",
    val uploadUrl: String = "",
    val expiresAt: String = ""
)

@Serializable
data class CompleteUploadRequestDto(
    val conversationId: String = "",
    val attachmentId: String = ""
)

@Serializable
data class MediaUrlResponseDto(
    val url: String = "",
    val expiresAt: String = ""
)

@Serializable
data class RegisterDto(
    val email: String = "",
    val password: String = "",
    val displayName: String? = null
)

@Serializable
data class LoginDto(
    val email: String = "",
    val password: String = ""
)

@Serializable
data class RevokeSessionDto(
    val sessionId: String = "",
    val userId: String = ""
)

@Serializable
data class AuthResponse(
    val token: String = "",
    val expires: String = "",
    val email: String = "",
    val displayName: String? = null,
    val userId: String = "",
    val refreshToken: String? = null,
    val sessionId: String? = null
)

@Serializable
data class SessionDto(
    val id: String = "",
    val deviceInfo: String? = null,
    val ip: String? = null,
    val expiresAt: String = "",
    val isRevoked: Boolean = false,
    val createdAt: String = ""
)

data class NewMessageEventDto(
    val conversationId: String = "",
    val message: MessageDto = MessageDto()
)

data class MessageReadEventDto(
    val type: String = "",
    val timestamp: String? = null,
    val userId: String? = null,
    val conversationId: String = "",
    val messageId: String = "",
    val readByUserId: String = ""
)

data class TypingEventDto(
    val type: String = "",
    val timestamp: String? = null,
    val userId: String? = null,
    val conversationId: String = "",
    val isTyping: Boolean = false,
    val userName: String = ""
)
