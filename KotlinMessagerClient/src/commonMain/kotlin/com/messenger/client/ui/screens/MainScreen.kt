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
import com.messenger.client.transfer.StreamTransferController
import com.messenger.client.ui.screens.auth.LoginScreen
import com.messenger.client.ui.screens.auth.RegisterScreen
import com.messenger.client.ui.screens.chat.ChatDetailScreen
import com.messenger.client.ui.screens.chat.ChatMenuScreen
import com.messenger.client.ui.screens.chat.TransferChannelScreen
import com.messenger.client.ui.screens.main.MainMenuScreen
import com.messenger.client.ui.screens.profile.UserProfileScreen
import com.messenger.client.ui.screens.sessions.SessionsScreen
import com.messenger.client.media.rememberStreamTransferStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun MainScreen(authState: AuthState) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Loading) }
    var showRegister by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showChatMenu by remember { mutableStateOf(true) }
    var currentConversation by remember { mutableStateOf<ConversationDto?>(null) }
    var currentTransferChannel by remember { mutableStateOf<ConversationDto?>(null) }
    val scope = rememberCoroutineScope()
    val webSocketService = remember { MessengerWebSocketService() }
    val streamStorage = rememberStreamTransferStorage()
    val streamTransferController = remember(webSocketService, streamStorage) {
        StreamTransferController(webSocketService, streamStorage)
    }
    val token by authState.jwtToken.collectAsState()

    LaunchedEffect(
        authState.isLoggedIn.collectAsState().value,
        showRegister,
        showSessions,
        showChatMenu,
        currentConversation,
        currentTransferChannel
    ) {
        val isLoggedIn = authState.isLoggedIn.value
        val conversation = currentConversation
        val transferChannel = currentTransferChannel
        currentScreen = when {
            !isLoggedIn -> if (showRegister) Screen.Register else Screen.Login
            transferChannel != null -> Screen.TransferChannel(transferChannel)
            conversation != null -> Screen.ChatDetail(conversation)
            currentScreen is Screen.Profile -> currentScreen
            showSessions -> Screen.Sessions
            showChatMenu -> Screen.ChatMenu
            else -> Screen.MainMenu
        }
    }

    LaunchedEffect(token) {
        val currentToken = token
        if (currentToken.isNullOrBlank()) {
            webSocketService.disconnect()
            streamTransferController.resetForLogout()
            return@LaunchedEffect
        }
        var wasConnected = false
        while (isActive) {
            if (!webSocketService.isConnected) {
                withContext(Dispatchers.IO) {
                    webSocketService.connect(currentToken)
                }
            }
            val connected = webSocketService.isConnected
            if (connected && !wasConnected) {
                wasConnected = true
            } else if (!connected) {
                wasConnected = false
            }
            delay(3_000)
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
                                streamTransferController.resetForLogout()
                                authState.clearAuth()
                                currentScreen = Screen.Login
                                showSessions = false
                                showChatMenu = false
                                currentConversation = null
                                currentTransferChannel = null
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
                        onOpenProfile = {
                            val selfUserId = authState.getUserId()
                            if (!selfUserId.isNullOrBlank()) {
                                currentScreen = Screen.Profile(selfUserId)
                            }
                        },
                        onOpenChat = { conversation ->
                            currentTransferChannel = null
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
                        onOpenTransferChannel = { transferChannel ->
                            currentTransferChannel = transferChannel
                            currentScreen = Screen.TransferChannel(transferChannel)
                        },
                        onConversationUpdated = { updatedConversation ->
                            currentConversation = updatedConversation
                        },
                        onOpenUserProfile = { profileUserId ->
                            currentScreen = Screen.Profile(profileUserId)
                        },
                        onBack = {
                            currentTransferChannel = null
                            currentConversation = null
                            showChatMenu = true
                            currentScreen = Screen.ChatMenu
                        }
                    )
                }
                is Screen.TransferChannel -> {
                    TransferChannelScreen(
                        authState = authState,
                        conversation = screen.conversation,
                        webSocketService = webSocketService,
                        streamTransferController = streamTransferController,
                        onBack = {
                            currentTransferChannel = null
                            val previousConversation = currentConversation
                            if (previousConversation != null) {
                                currentScreen = Screen.ChatDetail(previousConversation)
                            } else {
                                showChatMenu = true
                                currentScreen = Screen.ChatMenu
                            }
                        }
                    )
                }
                is Screen.Profile -> {
                    UserProfileScreen(
                        authState = authState,
                        userId = screen.userId,
                        onBack = {
                            val transferChannel = currentTransferChannel
                            val conversation = currentConversation
                            currentScreen = when {
                                transferChannel != null -> Screen.TransferChannel(transferChannel)
                                conversation != null -> Screen.ChatDetail(conversation)
                                showSessions -> Screen.Sessions
                                showChatMenu -> Screen.ChatMenu
                                else -> Screen.MainMenu
                            }
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
    data class Profile(val userId: String) : Screen()
    data class ChatDetail(val conversation: ConversationDto) : Screen()
    data class TransferChannel(val conversation: ConversationDto) : Screen()
}
