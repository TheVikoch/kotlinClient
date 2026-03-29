package com.messenger.client.media

import androidx.compose.ui.graphics.ImageBitmap

expect fun decodeVideoPreview(path: String): ImageBitmap?
