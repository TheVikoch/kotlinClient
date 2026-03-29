package com.messenger.client.media

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
actual fun AudioAttachmentPlayer(
    path: String,
    accentColor: Color,
    contentColor: Color,
    secondaryColor: Color,
    compact: Boolean,
    modifier: Modifier
) {
    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember(path) { mutableStateOf(false) }
    var isPlaying by remember(path) { mutableStateOf(false) }
    var durationMs by remember(path) { mutableStateOf(0) }
    var positionMs by remember(path) { mutableStateOf(0) }
    var hasError by remember(path) { mutableStateOf(false) }

    DisposableEffect(path) {
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        runCatching {
            mediaPlayer.setDataSource(path)
            mediaPlayer.setOnPreparedListener { prepared ->
                isPrepared = true
                durationMs = prepared.duration.coerceAtLeast(0)
                positionMs = 0
                hasError = false
            }
            mediaPlayer.setOnCompletionListener {
                isPlaying = false
                positionMs = durationMs
                runCatching { it.seekTo(0) }
                positionMs = 0
            }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                hasError = true
                isPrepared = false
                isPlaying = false
                true
            }
            mediaPlayer.prepareAsync()
        }.onFailure {
            hasError = true
        }

        onDispose {
            runCatching {
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.stop()
                }
            }
            runCatching { mediaPlayer.release() }
            player = null
        }
    }

    LaunchedEffect(isPlaying, path) {
        while (isPlaying) {
            positionMs = player?.currentPosition?.coerceAtLeast(0) ?: positionMs
            delay(200L)
        }
    }

    val progress = when {
        durationMs <= 0 -> 0f
        else -> positionMs.toFloat() / durationMs.toFloat()
    }.coerceIn(0f, 1f)

    Row(
        modifier = modifier
            .then(
                if (compact) {
                    Modifier.widthIn(min = 148.dp, max = 196.dp)
                } else {
                    Modifier.fillMaxWidth()
                }
            )
            .clip(RoundedCornerShape(if (compact) 16.dp else 18.dp))
            .background(accentColor.copy(alpha = if (compact) 0.08f else 0.12f))
            .padding(
                horizontal = if (compact) 8.dp else 12.dp,
                vertical = if (compact) 6.dp else 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 30.dp else 38.dp)
                .clip(CircleShape)
                .background(accentColor)
                .clickable(enabled = isPrepared && !hasError) {
                    val mediaPlayer = player ?: return@clickable
                    if (mediaPlayer.isPlaying) {
                        mediaPlayer.pause()
                        isPlaying = false
                    } else {
                        mediaPlayer.start()
                        isPlaying = true
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                tint = Color.White
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)
        ) {
            if (!compact) {
                Text(
                    text = if (hasError) "Не удалось открыть голосовое" else "Голосовое сообщение",
                    color = contentColor,
                    fontSize = 14.sp
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 3.dp else 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(secondaryColor.copy(alpha = 0.28f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(if (compact) 3.dp else 4.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(accentColor)
                )
            }
            Text(
                text = when {
                    hasError -> "Ошибка воспроизведения"
                    !isPrepared -> "Подготовка..."
                    else -> "${formatMillis(positionMs.toLong())} / ${formatMillis(durationMs.toLong())}"
                },
                color = secondaryColor,
                fontSize = if (compact) 10.sp else 12.sp
            )
        }
    }
}

private fun formatMillis(value: Long): String {
    val totalSeconds = (value / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}
