package com.messenger.client.media

import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeVideoPreview(path: String): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever
            .getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?.asImageBitmap()
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}
