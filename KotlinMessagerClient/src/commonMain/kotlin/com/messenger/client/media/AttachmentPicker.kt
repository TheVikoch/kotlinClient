package com.messenger.client.media

import androidx.compose.runtime.Composable

@Composable
expect fun AttachmentPicker(
    show: Boolean,
    onDismiss: () -> Unit,
    onPicked: (List<PickedFile>) -> Unit,
    onError: (String) -> Unit
)
