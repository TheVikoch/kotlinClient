package com.messenger.client.media

import androidx.compose.runtime.Composable

data class PickedFile(
    val name: String,
    val bytes: ByteArray,
    val contentType: String
)

interface FilePicker {
    fun pickFiles()
}

@Composable
expect fun rememberFilePicker(
    onPicked: (List<PickedFile>) -> Unit,
    onError: (String) -> Unit
): FilePicker
