package com.messenger.client.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

enum class VideoPlayerScaleMode {
    Fit,
    Crop
}

enum class VideoPlayerPlaybackMode {
    Loop,
    PlayOnce
}

@Composable
fun InlineVideo(
    path: String,
    muted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .then(modifier)
            .clickable { onClick() }
    ) {
        VideoPlayerView(
            path = path,
            muted = muted,
            scaleMode = VideoPlayerScaleMode.Fit,
            playbackMode = VideoPlayerPlaybackMode.Loop,
            restartToken = 0,
            onPlaybackComplete = null,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun FullScreenVideo(
    path: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        val state = rememberTransformState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onDismiss() }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(state.transformableState)
                    .graphicsLayer(
                        scaleX = state.scale,
                        scaleY = state.scale,
                        translationX = state.offsetX,
                        translationY = state.offsetY
                    ),
                contentAlignment = Alignment.Center
            ) {
                VideoPlayerView(
                    path = path,
                    muted = false,
                    scaleMode = VideoPlayerScaleMode.Fit,
                    playbackMode = VideoPlayerPlaybackMode.Loop,
                    restartToken = 0,
                    onPlaybackComplete = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun ZoomableImage(
    content: @Composable () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        val state = rememberTransformState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onDismiss() }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(state.transformableState)
                    .graphicsLayer(
                        scaleX = state.scale,
                        scaleY = state.scale,
                        translationX = state.offsetX,
                        translationY = state.offsetY
                    ),
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        }
    }
}

private data class TransformState(
    val transformableState: TransformableState,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float
)

@Composable
private fun rememberTransformState(): TransformState {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val state = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        offsetX += offsetChange.x
        offsetY += offsetChange.y
    }
    return remember(scale, offsetX, offsetY) {
        TransformState(state, scale, offsetX, offsetY)
    }
}

@Composable
expect fun VideoPlayerView(
    path: String,
    muted: Boolean,
    scaleMode: VideoPlayerScaleMode,
    playbackMode: VideoPlayerPlaybackMode,
    restartToken: Int,
    onPlaybackComplete: (() -> Unit)?,
    modifier: Modifier
)
