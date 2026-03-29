package com.messenger.client.media

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.util.Duration

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
    ensureFxStarted()
    val panel = remember { JFXPanel() }
    val playerHolder = remember { arrayOfNulls<MediaPlayer>(1) }

    DisposableEffect(path, muted, scaleMode, playbackMode, restartToken) {
        Platform.runLater {
            runCatching {
                playerHolder[0]?.dispose()
                val media = Media(File(path).toURI().toString())
                val player = MediaPlayer(media).apply {
                    isMute = muted
                    cycleCount = if (playbackMode == VideoPlayerPlaybackMode.Loop) {
                        MediaPlayer.INDEFINITE
                    } else {
                        1
                    }
                    setOnEndOfMedia {
                        if (playbackMode == VideoPlayerPlaybackMode.PlayOnce) {
                            SwingUtilities.invokeLater {
                                onPlaybackComplete?.invoke()
                            }
                        }
                    }
                }
                val view = MediaView(player).apply {
                    isPreserveRatio = scaleMode == VideoPlayerScaleMode.Fit
                }
                val scene = Scene(StackPane(view))
                view.fitWidthProperty().bind(scene.widthProperty())
                view.fitHeightProperty().bind(scene.heightProperty())
                panel.scene = scene
                playerHolder[0] = player
                player.seek(Duration.ZERO)
                player.play()
            }.onFailure {
                panel.scene = null
                playerHolder[0] = null
            }
        }

        onDispose {
            Platform.runLater {
                playerHolder[0]?.stop()
                playerHolder[0]?.dispose()
                playerHolder[0] = null
            }
        }
    }

    SwingPanel(
        modifier = modifier.fillMaxSize(),
        factory = { panel }
    )
}

private val fxStarted = AtomicBoolean(false)

private fun ensureFxStarted() {
    if (fxStarted.compareAndSet(false, true)) {
        try {
            Platform.startup { }
        } catch (_: IllegalStateException) {
            fxStarted.set(true)
        }
    }
}
