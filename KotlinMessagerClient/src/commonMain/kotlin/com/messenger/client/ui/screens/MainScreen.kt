package com.messenger.client.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.messenger.client.models.ConversationDto
import com.messenger.client.services.AuthState
import com.messenger.client.services.MessengerWebSocketService
import com.messenger.client.ui.screens.auth.LoginScreen
import com.messenger.client.ui.screens.auth.RegisterScreen
import com.messenger.client.ui.screens.chat.ChatDetailScreen
import com.messenger.client.ui.screens.chat.ChatMenuScreen
import com.messenger.client.ui.screens.main.MainMenuScreen
import com.messenger.client.ui.screens.sessions.SessionsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainScreen(authState: AuthState) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Loading) }
    var showRegister by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showChatMenu by remember { mutableStateOf(true) }
    var currentConversation by remember { mutableStateOf<ConversationDto?>(null) }
    val scope = rememberCoroutineScope()
    val webSocketService = remember { MessengerWebSocketService() }
    val token by authState.jwtToken.collectAsState()

    LaunchedEffect(authState.isLoggedIn.collectAsState().value, showRegister, showSessions, showChatMenu, currentConversation) {
        val isLoggedIn = authState.isLoggedIn.value
        val conversation = currentConversation
        currentScreen = when {
            !isLoggedIn -> if (showRegister) Screen.Register else Screen.Login
            conversation != null -> Screen.ChatDetail(conversation)
            showSessions -> Screen.Sessions
            showChatMenu -> Screen.ChatMenu
            else -> Screen.MainMenu
        }
    }

    LaunchedEffect(token) {
        val currentToken = token
        if (currentToken.isNullOrBlank()) {
            webSocketService.disconnect()
        } else {
            withContext(Dispatchers.IO) {
                webSocketService.connect(currentToken)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            when (val screen = currentScreen) {
                is Screen.Loading -> {
                    CircularProgressIndicator()
                }
                is Screen.Login -> {
                    LoginScreen(
                        authState = authState,
                        onLoginSuccess = {
                            showRegister = false
                            showChatMenu = true
                            currentScreen = Screen.ChatMenu
                        },
                        onNavigateToRegister = {
                            showRegister = true
                            currentScreen = Screen.Register
                        },
                        onError = { error ->
                            println("Error: $error")
                        }
                    )
                }
                is Screen.Register -> {
                    RegisterScreen(
                        authState = authState,
                        onRegisterSuccess = {
                            showRegister = false
                            showChatMenu = true
                            currentScreen = Screen.ChatMenu
                        },
                        onNavigateToLogin = {
                            showRegister = false
                            currentScreen = Screen.Login
                        },
                        onError = { error ->
                            println("Error: $error")
                        }
                    )
                }
                is Screen.MainMenu -> {
                    MainMenuScreen(
                        authState = authState,
                        onShowSessions = {
                            showSessions = true
                            currentScreen = Screen.Sessions
                        },
                        onShowChatMenu = {
                            showChatMenu = true
                            currentScreen = Screen.ChatMenu
                        },
                        onLogout = {
                            scope.launch {
                                authState.clearAuth()
                                currentScreen = Screen.Login
                                showSessions = false
                                showChatMenu = false
                            }
                        }
                    )
                }
                is Screen.Sessions -> {
                    SessionsScreen(
                        authState = authState,
                        onBack = {
                            showSessions = false
                            currentScreen = Screen.MainMenu
                        }
                    )
                }
                is Screen.ChatMenu -> {
                    ChatMenuScreen(
                        authState = authState,
                        webSocketService = webSocketService,
                        onBack = {
                            showChatMenu = false
                            currentConversation = null
                            currentScreen = Screen.MainMenu
                        },
                        onOpenChat = { conversation ->
                            currentConversation = conversation
                            currentScreen = Screen.ChatDetail(conversation)
                        }
                    )
                }
                is Screen.ChatDetail -> {
                    ChatDetailScreen(
                        authState = authState,
                        conversation = screen.conversation,
                        webSocketService = webSocketService,
                        onBack = {
                            currentConversation = null
                            showChatMenu = true
                            currentScreen = Screen.ChatMenu
                        }
                    )
                }
            }
        }
    }
}

sealed class Screen {
    object Loading : Screen()
    object Login : Screen()
    object Register : Screen()
    object MainMenu : Screen()
    object Sessions : Screen()
    object ChatMenu : Screen()
    data class ChatDetail(val conversation: ConversationDto) : Screen()
}
