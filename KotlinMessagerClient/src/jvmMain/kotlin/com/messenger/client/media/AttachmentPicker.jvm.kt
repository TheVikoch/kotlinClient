package com.messenger.client.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

@Composable
actual fun AttachmentPicker(
    show: Boolean,
    onDismiss: () -> Unit,
    onPicked: (List<PickedFile>) -> Unit,
    onError: (String) -> Unit
) {
    val filePicker = rememberFilePicker(
        onPicked = { files ->
            onPicked(files)
            onDismiss()
        },
        onError = { error ->
            onError(error)
            onDismiss()
        }
    )

    LaunchedEffect(show) {
        if (show) {
            filePicker.pickFiles()
        }
    }
}
