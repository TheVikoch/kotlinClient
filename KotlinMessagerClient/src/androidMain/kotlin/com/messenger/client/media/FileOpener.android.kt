package com.messenger.client.media

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

@Composable
actual fun rememberFileOpener(): (String, String?) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { path: String, contentType: String? ->
            try {
                val file = File(path)
                if (!file.exists()) return@remember false
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val extension = file.extension.lowercase()
                val inferredType = if (extension.isNotBlank()) {
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                } else {
                    null
                }
                val resolvedType = when {
                    contentType.isNullOrBlank() -> inferredType ?: "*/*"
                    contentType == "application/octet-stream" -> inferredType ?: "*/*"
                    else -> contentType
                }
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, resolvedType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val canOpen = context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
                val finalIntent = if (canOpen) {
                    intent
                } else {
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                val canOpenFinal = context.packageManager.queryIntentActivities(finalIntent, 0).isNotEmpty()
                if (canOpenFinal) {
                    val chooser = Intent.createChooser(finalIntent, "Открыть файл").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                    true
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
