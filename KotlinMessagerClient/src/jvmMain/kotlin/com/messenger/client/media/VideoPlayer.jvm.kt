package com.messenger.client.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView

@Composable
actual fun VideoPlayerView(
    path: String,
    muted: Boolean,
    modifier: Modifier
) {
    if (muted) {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Видео",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    ensureFxStarted()
    var showPlayer by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        showPlayer = true
    }

    if (!showPlayer) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Видео",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val panel = remember { JFXPanel() }
    val playerHolder = remember { arrayOfNulls<MediaPlayer>(1) }

    DisposableEffect(path, muted) {
        Platform.runLater {
            playerHolder[0]?.dispose()
            val media = Media(File(path).toURI().toString())
            val player = MediaPlayer(media)
            player.isMute = muted
            player.isAutoPlay = true
            player.cycleCount = MediaPlayer.INDEFINITE
            val view = MediaView(player)
            view.isPreserveRatio = true
            val root = StackPane(view)
            val scene = Scene(root)
            view.fitWidthProperty().bind(scene.widthProperty())
            view.fitHeightProperty().bind(scene.heightProperty())
            panel.scene = scene
            playerHolder[0] = player
            player.play()
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
