package com.messenger.client.media

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeVideoPreview(path: String): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        val rotationDegrees = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()
            ?: 0
        retriever
            .getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?.let { bitmap -> rotateBitmapIfNeeded(bitmap, rotationDegrees) }
            ?.asImageBitmap()
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun rotateBitmapIfNeeded(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
    val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
    if (normalizedRotation == 0) return bitmap

    return runCatching {
        Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postRotate(normalizedRotation.toFloat()) },
            true
        )
    }.getOrDefault(bitmap)
}
