package com.messenger.client.media

import androidx.compose.runtime.Composable

data class StreamSaveTarget(
    val id: String,
    val fileName: String,
    val mimeType: String? = null,
    val isContentUri: Boolean = false
)

interface StreamTransferStorage {
    fun createTempFile(transferId: String, fileName: String): String
    fun writeChunk(path: String, offset: Long, data: ByteArray)
    fun availableBytes(): Long
    suspend fun pickSaveTarget(suggestedName: String, mimeType: String?): StreamSaveTarget?
    fun copyTempToTarget(tempPath: String, target: StreamSaveTarget): Result<Unit>
    fun deleteTempFile(tempPath: String)
}

@Composable
expect fun rememberStreamTransferStorage(): StreamTransferStorage
