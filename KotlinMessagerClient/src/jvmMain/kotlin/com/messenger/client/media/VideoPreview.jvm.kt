package com.messenger.client.media

import androidx.compose.ui.graphics.ImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.util.Duration
import javax.imageio.ImageIO

actual fun decodeVideoPreview(path: String): ImageBitmap? {
    ensureFxVideoPreviewStarted()

    val result = AtomicReference<ImageBitmap?>(null)
    val latch = CountDownLatch(1)

    Platform.runLater {
        var player: MediaPlayer? = null

        fun finish(bitmap: ImageBitmap?) {
            result.set(bitmap)
            runCatching { player?.stop() }
            runCatching { player?.dispose() }
            latch.countDown()
        }

        runCatching {
            val media = Media(File(path).toURI().toString())
            val view = MediaView()
            val container = StackPane(view)
            val playerInstance = MediaPlayer(media)
            player = playerInstance
            view.mediaPlayer = playerInstance

            playerInstance.setOnReady {
                val previewWidth = media.width.takeIf { it > 0 } ?: 240
                val previewHeight = media.height.takeIf { it > 0 } ?: 240
                view.isPreserveRatio = true
                view.fitWidth = previewWidth.toDouble()
                view.fitHeight = previewHeight.toDouble()
                Scene(container, previewWidth.toDouble(), previewHeight.toDouble())
                container.applyCss()
                container.layout()

                playerInstance.isMute = true
                playerInstance.seek(Duration.millis(1.0))
                playerInstance.play()

                PauseTransition(Duration.millis(160.0)).apply {
                    setOnFinished {
                        val bytes = runCatching {
                            val snapshot = container.snapshot(null, null)
                            val bufferedImage = SwingFXUtils.fromFXImage(snapshot, null)
                            ByteArrayOutputStream().use { output ->
                                ImageIO.write(bufferedImage, "png", output)
                                output.toByteArray()
                            }
                        }.getOrNull()
                        finish(bytes?.let(::decodeImage))
                    }
                    play()
                }
            }

            playerInstance.setOnError {
                finish(null)
            }
        }.onFailure {
            finish(null)
        }
    }

    return if (latch.await(2, TimeUnit.SECONDS)) {
        result.get()
    } else {
        null
    }
}

private val fxVideoPreviewStarted = AtomicBoolean(false)

private fun ensureFxVideoPreviewStarted() {
    if (fxVideoPreviewStarted.compareAndSet(false, true)) {
        try {
            Platform.startup { }
        } catch (_: IllegalStateException) {
            fxVideoPreviewStarted.set(true)
        }
    }
}
