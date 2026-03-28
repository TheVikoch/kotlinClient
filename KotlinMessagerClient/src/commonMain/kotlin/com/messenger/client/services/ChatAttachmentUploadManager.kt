package com.messenger.client.services

import com.messenger.client.models.MessageDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.random.Random

data class OutgoingAttachmentDraft(
    val id: String,
    val fileName: String,
    val contentType: String,
    val size: Long,
    val bytes: ByteArray,
    val previewBytes: ByteArray?
)

enum class OutgoingMessageUploadStage {
    Uploading,
    Sending,
    Failed,
    Sent
}

data class OutgoingMessageUploadTask(
    val id: String,
    val conversationId: String,
    val content: String,
    val replyToMessageId: String?,
    val senderId: String,
    val senderDisplayName: String,
    val senderEmail: String?,
    val createdAt: String,
    val attachments: List<OutgoingAttachmentDraft>,
    val totalBytes: Long,
    val uploadedBytes: Long = 0L,
    val stage: OutgoingMessageUploadStage = OutgoingMessageUploadStage.Uploading,
    val errorMessage: String? = null,
    val sentMessage: MessageDto? = null
) {
    val progress: Float
        get() = when {
            totalBytes <= 0L && stage == OutgoingMessageUploadStage.Sending -> 1f
            totalBytes <= 0L && stage == OutgoingMessageUploadStage.Sent -> 1f
            totalBytes <= 0L -> 0f
            else -> (uploadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        }
}

class ChatAttachmentUploadManager(
    private val apiService: ApiService = ApiService(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _tasks = MutableStateFlow<List<OutgoingMessageUploadTask>>(emptyList())

    val tasks: StateFlow<List<OutgoingMessageUploadTask>> = _tasks.asStateFlow()

    fun enqueue(
        token: String,
        conversationId: String,
        content: String,
        replyToMessageId: String?,
        senderId: String,
        senderDisplayName: String,
        senderEmail: String?,
        attachments: List<OutgoingAttachmentDraft>
    ): String {
        val now = Clock.System.now().toString()
        val task = OutgoingMessageUploadTask(
            id = "upload-$now-${Random.nextInt(1000, 9999)}",
            conversationId = conversationId,
            content = content,
            replyToMessageId = replyToMessageId,
            senderId = senderId,
            senderDisplayName = senderDisplayName,
            senderEmail = senderEmail,
            createdAt = now,
            attachments = attachments,
            totalBytes = attachments.sumOf { it.size.coerceAtLeast(0L) }
        )

        _tasks.update { existing -> existing + task }
        scope.launch {
            processTask(token, task)
        }
        return task.id
    }

    fun removeTask(taskId: String) {
        _tasks.update { existing -> existing.filterNot { it.id == taskId } }
    }

    fun cancelAll() {
        scope.coroutineContext.cancelChildren()
        _tasks.value = emptyList()
    }

    private suspend fun processTask(token: String, task: OutgoingMessageUploadTask) {
        val uploadedAttachmentIds = mutableListOf<String>()
        var completedBytes = 0L

        try {
            for (attachment in task.attachments) {
                val initResponse = apiService.initUpload(
                    token = token,
                    conversationId = task.conversationId,
                    fileName = attachment.fileName,
                    contentType = attachment.contentType,
                    size = attachment.size
                ).getOrElse { error ->
                    failTask(task.id, error.message ?: "Failed to start file upload")
                    return
                }

                val uploadResult = apiService.uploadToPresignedUrl(
                    uploadUrl = initResponse.uploadUrl,
                    bytes = attachment.bytes,
                    contentType = attachment.contentType,
                    onProgress = { bytesSentTotal, _ ->
                        val fileUploadedBytes = bytesSentTotal.coerceIn(0L, attachment.size.coerceAtLeast(0L))
                        updateTask(task.id) { current ->
                            current.copy(
                                stage = OutgoingMessageUploadStage.Uploading,
                                uploadedBytes = (completedBytes + fileUploadedBytes).coerceAtMost(current.totalBytes)
                            )
                        }
                    }
                )
                if (uploadResult.isFailure) {
                    failTask(task.id, uploadResult.exceptionOrNull()?.message ?: "Failed to upload file")
                    return
                }

                val completeResult = apiService.completeUpload(
                    token = token,
                    conversationId = task.conversationId,
                    attachmentId = initResponse.attachmentId
                )
                if (completeResult.isFailure) {
                    failTask(task.id, completeResult.exceptionOrNull()?.message ?: "Failed to finish file upload")
                    return
                }

                completedBytes += attachment.size.coerceAtLeast(0L)
                uploadedAttachmentIds += initResponse.attachmentId
                updateTask(task.id) { current ->
                    current.copy(
                        stage = OutgoingMessageUploadStage.Uploading,
                        uploadedBytes = completedBytes.coerceAtMost(current.totalBytes)
                    )
                }
            }

            updateTask(task.id) { current ->
                current.copy(
                    stage = OutgoingMessageUploadStage.Sending,
                    uploadedBytes = current.totalBytes
                )
            }

            val sendResult = apiService.sendMessage(
                token = token,
                conversationId = task.conversationId,
                content = task.content,
                replyToMessageId = task.replyToMessageId,
                attachmentIds = uploadedAttachmentIds
            )
            sendResult.fold(
                onSuccess = { message ->
                    updateTask(task.id) { current ->
                        current.copy(
                            stage = OutgoingMessageUploadStage.Sent,
                            uploadedBytes = current.totalBytes,
                            errorMessage = null,
                            sentMessage = message
                        )
                    }
                },
                onFailure = { error ->
                    failTask(task.id, error.message ?: "Failed to send message")
                }
            )
        } catch (error: Exception) {
            failTask(task.id, error.message ?: "Failed to send message")
        }
    }

    private fun failTask(taskId: String, message: String) {
        updateTask(taskId) { current ->
            current.copy(
                stage = OutgoingMessageUploadStage.Failed,
                errorMessage = message
            )
        }
    }

    private fun updateTask(
        taskId: String,
        transform: (OutgoingMessageUploadTask) -> OutgoingMessageUploadTask
    ) {
        _tasks.update { existing ->
            existing.map { task ->
                if (task.id == taskId) transform(task) else task
            }
        }
    }
}
