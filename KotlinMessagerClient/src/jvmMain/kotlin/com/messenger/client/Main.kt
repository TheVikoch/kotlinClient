package com.messenger.client

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.messenger.client.services.AuthState
import com.messenger.client.ui.MessengerTheme
import com.messenger.client.ui.screens.MainScreen

fun main() = application {
    val authState = remember { AuthState() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "ViKoch"
    ) {
        MessengerTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                MainScreen(authState = authState)
            }
        }
    }
}
