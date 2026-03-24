package com.messenger.client.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.messenger.client.services.createHttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

@Composable
fun RemoteImage(
    url: String,
    modifier: Modifier = Modifier
) {
    val client = remember { createHttpClient() }
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        try {
            val bytes = client.get(url).body<ByteArray>()
            bitmap = decodeImage(bytes)
        } catch (_: Exception) {
            failed = true
        }
    }

    Box(
        modifier = modifier.sizeIn(maxWidth = 240.dp, maxHeight = 240.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> Image(bitmap = bitmap!!, contentDescription = null)
            failed -> Text(
                text = "Не удалось загрузить",
                style = MaterialTheme.typography.bodySmall
            )
            else -> CircularProgressIndicator(strokeWidth = 2.dp)
        }
    }
}

expect fun decodeImage(bytes: ByteArray): ImageBitmap?

