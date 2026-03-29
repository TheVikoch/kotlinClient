package com.messenger.client.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
expect fun AudioAttachmentPlayer(
    path: String,
    accentColor: Color,
    contentColor: Color,
    secondaryColor: Color,
    compact: Boolean = false,
    modifier: Modifier = Modifier
)
