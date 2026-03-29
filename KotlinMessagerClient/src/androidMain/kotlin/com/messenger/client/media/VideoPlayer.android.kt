package com.messenger.client.media

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

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
    key(path, muted, scaleMode, playbackMode, restartToken) {
        when (scaleMode) {
            VideoPlayerScaleMode.Fit -> {
                AndroidView(
                    modifier = modifier,
                    factory = { context ->
                        LoopingVideoView(context).apply {
                            bind(
                                path = path,
                                muted = muted,
                                playbackMode = playbackMode,
                                onPlaybackComplete = onPlaybackComplete
                            )
                        }
                    },
                    update = { view ->
                        view.bind(
                            path = path,
                            muted = muted,
                            playbackMode = playbackMode,
                            onPlaybackComplete = onPlaybackComplete
                        )
                    }
                )
            }

            VideoPlayerScaleMode.Crop -> {
                AndroidView(
                    modifier = modifier,
                    factory = { context ->
                        CropVideoTextureView(context).apply {
                            bind(
                                path = path,
                                muted = muted,
                                playbackMode = playbackMode,
                                onPlaybackComplete = onPlaybackComplete
                            )
                        }
                    },
                    update = { view ->
                        view.bind(
                            path = path,
                            muted = muted,
                            playbackMode = playbackMode,
                            onPlaybackComplete = onPlaybackComplete
                        )
                    }
                )
            }
        }
    }
}

private class LoopingVideoView(context: android.content.Context) : VideoView(context) {
    private var mediaPlayer: MediaPlayer? = null
    private var boundPath: String? = null
    private var playbackMode: VideoPlayerPlaybackMode = VideoPlayerPlaybackMode.Loop
    private var completionCallback: (() -> Unit)? = null

    init {
        setOnPreparedListener { mp ->
            mediaPlayer = mp
            applyPlayerConfig()
            if (!isPlaying) {
                start()
            }
        }
        setOnCompletionListener {
            if (playbackMode == VideoPlayerPlaybackMode.PlayOnce) {
                post { completionCallback?.invoke() }
            }
        }
        setOnErrorListener { _, _, _ -> true }
    }

    fun bind(
        path: String,
        muted: Boolean,
        playbackMode: VideoPlayerPlaybackMode,
        onPlaybackComplete: (() -> Unit)?
    ) {
        tag = muted
        this.playbackMode = playbackMode
        completionCallback = onPlaybackComplete
        if (boundPath != path) {
            boundPath = path
            setVideoPath(path)
        }
        applyPlayerConfig()
        if (!isPlaying) {
            start()
        }
    }

    private fun applyPlayerConfig() {
        mediaPlayer?.isLooping = playbackMode == VideoPlayerPlaybackMode.Loop
        val volume = if ((tag as? Boolean) == true) 0f else 1f
        mediaPlayer?.setVolume(volume, volume)
    }
}

private class CropVideoTextureView(
    context: android.content.Context
) : TextureView(context), TextureView.SurfaceTextureListener {
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
    private var boundPath: String? = null
    private var pendingPath: String? = null
    private var pendingMuted: Boolean = true
    private var pendingPlaybackMode: VideoPlayerPlaybackMode = VideoPlayerPlaybackMode.Loop
    private var completionCallback: (() -> Unit)? = null
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun bind(
        path: String,
        muted: Boolean,
        playbackMode: VideoPlayerPlaybackMode,
        onPlaybackComplete: (() -> Unit)?
    ) {
        pendingMuted = muted
        pendingPlaybackMode = playbackMode
        completionCallback = onPlaybackComplete
        if (!isAvailable) {
            pendingPath = path
            return
        }
        if (boundPath != path || mediaPlayer == null) {
            startPlayback(path)
        } else {
            applyPlayerConfig()
            applyCropTransform()
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(surfaceTexture)
        val pathToPlay = pendingPath ?: boundPath
        if (!pathToPlay.isNullOrBlank()) {
            startPlayback(pathToPlay)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyCropTransform()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releasePlayer()
        this.surface?.release()
        this.surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun startPlayback(path: String) {
        val renderSurface = surface ?: return
        releasePlayer()
        boundPath = path
        pendingPath = path
        videoWidth = 0
        videoHeight = 0

        mediaPlayer = MediaPlayer().apply {
            setSurface(renderSurface)
            setDataSource(path)
            setOnPreparedListener { player ->
                this@CropVideoTextureView.videoWidth = player.videoWidth
                this@CropVideoTextureView.videoHeight = player.videoHeight
                applyPlayerConfig()
                applyCropTransform()
                player.start()
            }
            setOnVideoSizeChangedListener { _, width, height ->
                this@CropVideoTextureView.videoWidth = width
                this@CropVideoTextureView.videoHeight = height
                applyCropTransform()
            }
            setOnCompletionListener {
                if (pendingPlaybackMode == VideoPlayerPlaybackMode.PlayOnce) {
                    post { completionCallback?.invoke() }
                }
            }
            setOnErrorListener { _, _, _ -> true }
            prepareAsync()
        }
    }

    private fun applyPlayerConfig() {
        mediaPlayer?.isLooping = pendingPlaybackMode == VideoPlayerPlaybackMode.Loop
        val volume = if (pendingMuted) 0f else 1f
        mediaPlayer?.setVolume(volume, volume)
    }

    private fun applyCropTransform() {
        val currentWidth = width.toFloat()
        val currentHeight = height.toFloat()
        if (currentWidth <= 0f || currentHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) {
            return
        }

        val viewAspect = currentWidth / currentHeight
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (videoAspect > viewAspect) {
            scaleX = videoAspect / viewAspect
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = viewAspect / videoAspect
        }

        setTransform(
            Matrix().apply {
                setScale(scaleX, scaleY, currentWidth / 2f, currentHeight / 2f)
            }
        )
    }

    private fun releasePlayer() {
        runCatching {
            mediaPlayer?.stop()
        }
        runCatching {
            mediaPlayer?.release()
        }
        mediaPlayer = null
    }
}
