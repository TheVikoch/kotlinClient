package com.messenger.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.messenger.client.services.AuthState
import com.messenger.client.ui.MessengerTheme
import com.messenger.client.ui.screens.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        setContent {
            MessengerTheme {
                MainScreen(authState = AuthState())
            }
        }
    }
}
