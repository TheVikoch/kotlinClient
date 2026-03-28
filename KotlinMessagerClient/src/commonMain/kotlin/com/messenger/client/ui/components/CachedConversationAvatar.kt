package com.messenger.client.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.messenger.client.media.buildConversationAvatarCacheKey
import com.messenger.client.media.decodeImage
import com.messenger.client.media.rememberMediaCache
import com.messenger.client.services.ApiService

@Composable
fun CachedConversationAvatar(
    token: String?,
    conversationId: String,
    photoId: String?,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape,
    contentScale: ContentScale = ContentScale.Crop,
    showLoadingIndicator: Boolean = true
) {
    val apiService = remember { ApiService() }
    val cache = rememberMediaCache()
    val cacheKey = remember(conversationId, photoId) {
        photoId?.takeIf { it.isNotBlank() }?.let { buildConversationAvatarCacheKey(conversationId, it) }
    }

    var bitmap by remember(cacheKey) {
        mutableStateOf(cacheKey?.let(cache::readBytes)?.let(::decodeImage))
    }
    var isLoading by remember(cacheKey) { mutableStateOf(false) }

    LaunchedEffect(token, conversationId, photoId, cacheKey) {
        if (token.isNullOrBlank() || photoId.isNullOrBlank() || cacheKey == null || bitmap != null) {
            return@LaunchedEffect
        }

        isLoading = true
        val urlResult = apiService.getConversationAvatarUrl(token, conversationId, photoId)
        urlResult.fold(
            onSuccess = { response ->
                apiService.downloadMediaBytes(response.url).onSuccess { bytes ->
                    cache.writeBytes(cacheKey, bytes)
                    bitmap = decodeImage(bytes)
                }
            },
            onFailure = {}
        )
        isLoading = false
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale
                )
            }
            isLoading && showLoadingIndicator -> {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
            else -> {
                val letter = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                Text(
                    text = letter,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
