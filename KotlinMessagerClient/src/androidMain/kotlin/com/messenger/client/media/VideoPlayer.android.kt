package com.messenger.client.media

import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
actual fun VideoPlayerView(
    path: String,
    muted: Boolean,
    modifier: Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = {
            VideoView(it).apply {
                tag = path
                setVideoPath(path)
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    val volume = if (muted) 0f else 1f
                    mp.setVolume(volume, volume)
                    start()
                }
                setOnErrorListener { _, _, _ -> true }
            }
        },
        update = { view ->
            if (view.tag != path) {
                view.tag = path
                view.setVideoPath(path)
            }
            view.setOnPreparedListener { mp ->
                mp.isLooping = true
                val volume = if (muted) 0f else 1f
                mp.setVolume(volume, volume)
                if (!view.isPlaying) {
                    view.start()
                }
            }
        }
    )
}
