package com.messenger.client.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Desktop
import java.io.File

@Composable
actual fun rememberFileOpener(): (String, String?) -> Boolean {
    return remember {
        { path: String, _: String? ->
            try {
                val file = File(path)
                if (file.exists()) {
                    Desktop.getDesktop().open(file)
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
