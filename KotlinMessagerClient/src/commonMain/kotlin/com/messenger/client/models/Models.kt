package com.messenger.client.models

import kotlinx.serialization.SerialName
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
    val kind: String = "text",
    val metadataJson: String? = null,
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

@Serializable
data class CreateStreamInviteRequestDto(
    val personalChatId: String = "",
    val streamChatName: String? = null
)

@Serializable
data class CreateStreamInviteResponseDto(
    val inviteId: String = "",
    val personalChatId: String = "",
    val creatorId: String = "",
    val targetUserId: String = "",
    val streamChatId: String? = null,
    val token: String = "",
    @SerialName("Token") val tokenPascal: String? = null,
    val streamChatName: String? = null,
    val expiresAt: String = "",
    @SerialName("ExpiresAt") val expiresAtPascal: String? = null
)

@Serializable
data class AcceptStreamInviteRequestDto(
    val token: String = ""
)

@Serializable
data class AcceptStreamInviteResponseDto(
    val inviteId: String = "",
    val personalChatId: String = "",
    val creatorId: String = "",
    val targetUserId: String = "",
    val streamChatId: String = "",
    val streamChatName: String? = null,
    val acceptedAt: String = "",
    val expiresAt: String = ""
)

@Serializable
data class RevokeStreamInviteRequestDto(
    val inviteId: String = ""
)

@Serializable
data class StreamInviteMetadataDto(
    val inviteId: String = "",
    val personalChatId: String = "",
    val creatorId: String = "",
    val targetUserId: String = "",
    val streamChatId: String? = null,
    val status: String = "",
    val expiresAt: String = "",
    val acceptedAt: String? = null,
    val revokedAt: String? = null
)

@Serializable
data class StreamTransferInitRequestDto(
    val streamChatId: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val fileHash: String = "",
    val fileHashAlgorithm: String = "SHA-256",
    val chunkHashAlgorithm: String = "CRC32",
    val chunkSize: Int = 0,
    val totalChunks: Int = 0,
    val contentType: String? = null,
    val caption: String? = null
)

@Serializable
data class StreamTransferStartResponseDto(
    val transferId: String = "",
    val streamChatId: String = "",
    val receiverId: String = "",
    val expiresAt: String = ""
)

@Serializable
data class StreamTransferOfferDto(
    val transferId: String = "",
    val streamChatId: String = "",
    val senderId: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val fileHash: String = "",
    val fileHashAlgorithm: String = "SHA-256",
    val chunkHashAlgorithm: String = "CRC32",
    val chunkSize: Int = 0,
    val totalChunks: Int = 0,
    val contentType: String? = null,
    val caption: String? = null
)

@Serializable
data class StreamTransferAcceptRequestDto(
    val transferId: String = ""
)

@Serializable
data class StreamTransferRejectRequestDto(
    val transferId: String = "",
    val reason: String? = null
)

@Serializable
data class StreamTransferAcceptedDto(
    val transferId: String = "",
    val streamChatId: String = "",
    val receiverId: String = ""
)

@Serializable
data class StreamTransferRejectedDto(
    val transferId: String = "",
    val reason: String? = null
)

@Serializable
data class StreamTransferChunkDto(
    val transferId: String = "",
    val seq: Int = 0,
    val data: String = "",
    val chunkHash: String = "",
    val isLast: Boolean = false
)

@Serializable
data class StreamTransferAckDto(
    val transferId: String = "",
    val seqs: List<Int> = emptyList()
)

@Serializable
data class StreamTransferNackDto(
    val transferId: String = "",
    val seqs: List<Int> = emptyList()
)

@Serializable
data class StreamTransferResumeRequestDto(
    val transferId: String = "",
    val missingSeqs: List<Int> = emptyList()
)

@Serializable
data class StreamTransferCompleteRequestDto(
    val transferId: String = ""
)

@Serializable
data class StreamTransferCancelRequestDto(
    val transferId: String = "",
    val reason: String? = null
)

@Serializable
data class StreamTransferCompletedDto(
    val transferId: String = ""
)

@Serializable
data class StreamTransferCanceledDto(
    val transferId: String = "",
    val reason: String? = null
)

@Serializable
data class StreamTransferReportDto(
    val streamChatId: String = "",
    val senderId: String = "",
    val receiverId: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val fileHash: String = "",
    val status: String = "completed",
    val chunkSize: Int = 0,
    val totalChunks: Int = 0,
    val startedAt: String = "",
    val completedAt: String = ""
)
