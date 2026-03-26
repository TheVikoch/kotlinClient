package com.messenger.client.media

import androidx.compose.runtime.Composable

data class StreamPickedFile(
    val name: String,
    val size: Long,
    val contentType: String,
    val source: StreamFileSource
)

interface StreamFileSource {
    fun prepareForStreaming() {}
    fun readChunk(offset: Long, size: Int): ByteArray
    fun computeSha256Base64(): String
    fun getSize(): Long
    fun close()
}

@Composable
expect fun rememberStreamFilePicker(
    onPicked: (StreamPickedFile) -> Unit,
    onError: (String) -> Unit
): FilePicker
