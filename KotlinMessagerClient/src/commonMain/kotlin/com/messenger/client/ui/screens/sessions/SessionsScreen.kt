package com.messenger.client.ui.screens.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIos
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.client.models.SessionDto
import com.messenger.client.services.ApiService
import com.messenger.client.services.AuthState
import kotlinx.coroutines.launch

@Composable
fun SessionsScreen(
    authState: AuthState,
    onBack: () -> Unit
) {
    var sessions by remember { mutableStateOf<List<SessionDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val apiService = remember { ApiService() }
    val token by authState.jwtToken.collectAsState()
    val userId by authState.currentUserId.collectAsState()

    fun loadSessions() {
        scope.launch {
            isLoading = true
            errorMessage = null
            val currentToken = token
            val currentUserId = userId
            if (currentToken.isNullOrBlank() || currentUserId.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                isLoading = false
                return@launch
            }

            val result = apiService.getSessions(currentToken, currentUserId)
            result.fold(
                onSuccess = { sessionList ->
                    sessions = sessionList
                    isLoading = false
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось загрузить сессии"
                    isLoading = false
                }
            )
        }
    }

    fun revokeSession(sessionId: String) {
        scope.launch {
            val currentToken = token
            val currentUserId = userId
            if (currentToken.isNullOrBlank() || currentUserId.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                return@launch
            }
            val result = apiService.revokeSession(currentToken, sessionId, currentUserId)
            result.fold(
                onSuccess = {
                    selectedSessionId = null
                    loadSessions()
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось отозвать сессию"
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        loadSessions()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Сессии",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Управление входами",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }

            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBackIos,
                    contentDescription = "Back"
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (errorMessage != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Ошибка",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        } else if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Нет активных сессий",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions) { session ->
                    SessionCard(
                        session = session,
                        isSelected = selectedSessionId == session.id,
                        onRevoke = {
                            selectedSessionId = session.id
                            revokeSession(session.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionDto,
    isSelected: Boolean,
    onRevoke: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Сессия ${session.id.take(8)}...",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (session.isRevoked) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Отозвана") },
                        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            session.deviceInfo?.let { deviceInfo ->
                Text(
                    text = "Устройство: $deviceInfo",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }

            session.ip?.let { ip ->
                Text(
                    text = "IP: $ip",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }

            Text(
                text = "Создана: ${session.createdAt}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Text(
                text = "Истекает: ${session.expiresAt}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            if (!session.isRevoked) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onRevoke,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Отозвать сессию")
                }
            }
        }
    }
}
