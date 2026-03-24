package com.messenger.client.media

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberFilePicker(
    onPicked: (List<PickedFile>) -> Unit,
    onError: (String) -> Unit
): FilePicker {
    val context = LocalContext.current
    val contentResolver = context.contentResolver
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        try {
            val files = uris.mapNotNull { uri ->
                val name = queryDisplayName(contentResolver, uri)
                val type = contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@mapNotNull null
                PickedFile(name, bytes, type)
            }
            if (files.isNotEmpty()) onPicked(files)
        } catch (e: Exception) {
            onError(e.message ?: "Failed to pick file")
        }
    }

    return remember(launcher) {
        object : FilePicker {
            override fun pickFiles() {
                launcher.launch(arrayOf("*/*"))
            }
        }
    }
}

private fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String {
    val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                val value = it.getString(index)
                if (!value.isNullOrBlank()) return value
            }
        }
    }
    return "file"
}
