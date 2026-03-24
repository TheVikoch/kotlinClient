package com.messenger.client.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import java.nio.file.Files
import javax.swing.JFileChooser

@Composable
actual fun rememberFilePicker(
    onPicked: (List<PickedFile>) -> Unit,
    onError: (String) -> Unit
): FilePicker {
    return remember {
        object : FilePicker {
            override fun pickFiles() {
                try {
                    val chooser = JFileChooser().apply {
                        isMultiSelectionEnabled = true
                    }
                    val result = chooser.showOpenDialog(null)
                    if (result != JFileChooser.APPROVE_OPTION) return
                    val files = chooser.selectedFiles
                        .filter { it.exists() }
                        .mapNotNull { file ->
                            val bytes = file.readBytes()
                            val contentType = Files.probeContentType(file.toPath()) ?: "application/octet-stream"
                            PickedFile(file.name, bytes, contentType)
                        }
                    if (files.isNotEmpty()) onPicked(files)
                } catch (e: Exception) {
                    onError(e.message ?: "Failed to pick file")
                }
            }
        }
    }
}
