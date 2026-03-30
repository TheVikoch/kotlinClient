package com.messenger.client.media

import android.graphics.Color as AndroidColor
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.LayoutInflater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.messenger.client.R
import java.io.File

@Composable
actual fun VideoPlayerView(
    path: String,
    muted: Boolean,
    scaleMode: VideoPlayerScaleMode,
    playbackMode: VideoPlayerPlaybackMode,
    restartToken: Int,
    onPlaybackComplete: (() -> Unit)?,
    modifier: Modifier
) {
    val context = LocalContext.current
    val latestOnPlaybackComplete = rememberUpdatedState(onPlaybackComplete)
    val mediaFile = remember(path) { File(path) }
    val mediaUri = remember(mediaFile) { Uri.fromFile(mediaFile) }
    val manualRotationDegrees = remember(path, scaleMode) {
        if (scaleMode == VideoPlayerScaleMode.Crop) {
            readVideoRotationDegrees(path)?.normalizeRotationDegrees() ?: 0
        } else {
            0
        }
    }
    val exoPlayer = remember(context, mediaUri) {
        ExoPlayer.Builder(context)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(mediaUri))
                prepare()
                playWhenReady = true
            }
    }
    var completionDispatched by remember(exoPlayer) { mutableStateOf(false) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED &&
                    exoPlayer.repeatMode == Player.REPEAT_MODE_OFF &&
                    !completionDispatched
                ) {
                    completionDispatched = true
                    latestOnPlaybackComplete.value?.invoke()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer, muted, playbackMode) {
        exoPlayer.volume = if (muted) 0f else 1f
        exoPlayer.repeatMode = when (playbackMode) {
            VideoPlayerPlaybackMode.Loop -> Player.REPEAT_MODE_ONE
            VideoPlayerPlaybackMode.PlayOnce -> Player.REPEAT_MODE_OFF
        }
        if (!exoPlayer.isPlaying) {
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        }
    }

    LaunchedEffect(exoPlayer, restartToken, playbackMode) {
        completionDispatched = false
        exoPlayer.seekTo(0)
        exoPlayer.playWhenReady = true
        exoPlayer.play()
    }

    key(path, scaleMode) {
        AndroidView(
            modifier = modifier.graphicsLayer(
                rotationZ = manualRotationDegrees.toFloat()
            ),
            factory = { viewContext ->
                (LayoutInflater.from(viewContext)
                    .inflate(R.layout.media3_texture_player_view, null, false) as PlayerView).apply {
                    useController = false
                    player = exoPlayer
                    setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                    resizeMode = resolveResizeMode(scaleMode)
                    enableComposeSurfaceSyncWorkaroundIfAvailable()
                }
            },
            update = { playerView ->
                playerView.player = exoPlayer
                playerView.resizeMode = resolveResizeMode(scaleMode)
            }
        )
    }
}

private fun resolveResizeMode(scaleMode: VideoPlayerScaleMode): Int {
    return when (scaleMode) {
        VideoPlayerScaleMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoPlayerScaleMode.Crop -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }
}

private fun PlayerView.enableComposeSurfaceSyncWorkaroundIfAvailable() {
    runCatching {
        javaClass.getMethod("setEnableComposeSurfaceSyncWorkaround", Boolean::class.javaPrimitiveType)
            .invoke(this, true)
    }
}

private fun readVideoRotationDegrees(path: String): Int? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()
            ?: readVideoRotationDegreesFromExtractor(path)
    } catch (_: Exception) {
        readVideoRotationDegreesFromExtractor(path)
    } finally {
        runCatching { retriever.release() }
    }
}

private fun readVideoRotationDegreesFromExtractor(path: String): Int? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(path)
        (0 until extractor.trackCount)
            .asSequence()
            .map { trackIndex -> extractor.getTrackFormat(trackIndex) }
            .firstNotNullOfOrNull { format ->
                val mime = format.getString("mime").orEmpty()
                if (!mime.startsWith("video/")) {
                    return@firstNotNullOfOrNull null
                }
                when {
                    format.containsKey("rotation-degrees") -> format.getInteger("rotation-degrees")
                    else -> null
                }
            }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { extractor.release() }
    }
}

private fun Int.normalizeRotationDegrees(): Int = ((this % 360) + 360) % 360
