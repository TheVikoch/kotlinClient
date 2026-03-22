package com.messenger.client.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.client.models.ConversationDto
import com.messenger.client.services.ApiService
import com.messenger.client.services.AuthState
import com.messenger.client.services.MessengerWebSocketService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatMenuScreen(
    authState: AuthState,
    webSocketService: MessengerWebSocketService,
    onBack: () -> Unit,
    onOpenChat: (ConversationDto) -> Unit
) {
    var conversations by remember { mutableStateOf<List<ConversationDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var unreadCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var typingByConversation by remember { mutableStateOf<Map<String, Map<String, String>>>(emptyMap()) }
    var showCreatePersonalDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showCreateMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val apiService = remember { ApiService() }
    val token by authState.jwtToken.collectAsState()
    val currentUserId by authState.currentUserId.collectAsState()
    val currentUserEmail by authState.currentUserEmail.collectAsState()

    fun parseInstantOrNull(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
    }

    fun sortConversations(items: List<ConversationDto>): List<ConversationDto> {
        return items.sortedWith(compareByDescending { conversation ->
            parseInstantOrNull(conversation.lastMessageAt)
                ?: parseInstantOrNull(conversation.createdAt)
                ?: Instant.DISTANT_PAST
        })
    }

    fun refreshUnreadCounts(items: List<ConversationDto>) {
        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) return@launch
            val counts = mutableMapOf<String, Int>()
            for (conversation in items) {
                val result = apiService.getUnreadCount(currentToken, conversation.id)
                result.onSuccess { dto ->
                    counts[conversation.id] = dto.count
                }
            }
            unreadCounts = counts
        }
    }

    fun loadConversations() {
        scope.launch {
            isLoading = true
            errorMessage = null
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                isLoading = false
                return@launch
            }

            val result = apiService.getAllConversations(currentToken)
            result.fold(
                onSuccess = { convs ->
                    val sorted = sortConversations(convs)
                    conversations = sorted
                    refreshUnreadCounts(sorted)
                    if (webSocketService.isConnected) {
                        sorted.forEach { webSocketService.joinConversation(it.id) }
                    }
                    isLoading = false
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось загрузить чаты"
                    isLoading = false
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        loadConversations()
    }

    LaunchedEffect(Unit) {
        webSocketService.newMessages.collect { event ->
            val message = event.message
            val conversationId = message.conversationId
            val currentId = authState.getUserId()

            val updated = conversations.map { conversation ->
                if (conversation.id == conversationId) {
                    conversation.copy(
                        lastMessageAt = message.sentAt,
                        lastMessageContent = message.content
                    )
                } else {
                    conversation
                }
            }

            val exists = updated.any { it.id == conversationId }
            if (exists) {
                conversations = sortConversations(updated)
            } else {
                loadConversations()
            }

            if (currentId.isNullOrBlank() || message.senderId != currentId) {
                val currentCount = unreadCounts[conversationId] ?: 0
                unreadCounts = unreadCounts + (conversationId to (currentCount + 1))
            }

            if (typingByConversation.containsKey(conversationId)) {
                typingByConversation = typingByConversation - conversationId
            }
        }
    }

    LaunchedEffect(Unit) {
        webSocketService.typing.collect { event ->
            val conversationId = event.conversationId
            if (conversationId.isBlank()) return@collect
            val currentId = authState.getUserId()
            if (!currentId.isNullOrBlank() && event.userId == currentId) return@collect

            val key = event.userId?.ifBlank { null }
                ?: event.userName?.ifBlank { null }
                ?: return@collect

            val label = event.userName?.ifBlank { null } ?: "Собеседник"

            val existing = typingByConversation[conversationId]?.toMutableMap() ?: mutableMapOf()
            if (event.isTyping) {
                existing[key] = label.ifBlank { "Собеседник" }
            } else {
                existing.remove(key)
            }
            typingByConversation = if (existing.isEmpty()) {
                typingByConversation - conversationId
            } else {
                typingByConversation + (conversationId to existing)
            }
        }
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
                    text = "Чаты",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Ваши беседы",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { showCreateMenu = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Создать чат"
                        )
                    }
                    DropdownMenu(
                        expanded = showCreateMenu,
                        onDismissRequest = { showCreateMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Личный чат") },
                            onClick = {
                                showCreateMenu = false
                                showCreatePersonalDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Групповой чат") },
                            onClick = {
                                showCreateMenu = false
                                showCreateGroupDialog = true
                            }
                        )
                    }
                }
                Box {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Меню"
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("На главный экран") },
                            onClick = {
                                showOverflowMenu = false
                                onBack()
                            }
                        )
                    }
                }
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
        } else if (conversations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Пока нет чатов",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { showCreatePersonalDialog = true }) {
                        Text("Создать чат")
                    }
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(conversations) { conversation ->
                    val typingNames = typingByConversation[conversation.id]?.values?.toSet().orEmpty()
                    val typingText = if (typingNames.isNotEmpty()) {
                        if (typingNames.size == 1) {
                            "${typingNames.first()} печатает..."
                        } else {
                            "Печатают..."
                        }
                    } else {
                        null
                    }
                    val hasUnread = (unreadCounts[conversation.id] ?: 0) > 0
                    ConversationCard(
                        conversation = conversation,
                        currentUserId = currentUserId,
                        currentUserEmail = currentUserEmail,
                        hasUnread = hasUnread,
                        typingText = typingText,
                        onClick = { onOpenChat(conversation) }
                    )
                }
            }
        }
    }

    if (showCreatePersonalDialog) {
        CreatePersonalChatDialog(
            onDismiss = { showCreatePersonalDialog = false },
            onConfirm = { identifier ->
                scope.launch {
                    val currentToken = token
                    if (!currentToken.isNullOrBlank()) {
                        val result = apiService.createPersonalChat(currentToken, identifier)
                        result.fold(
                            onSuccess = {
                                showCreatePersonalDialog = false
                                loadConversations()
                            },
                            onFailure = { error ->
                                errorMessage = error.message ?: "Не удалось создать чат"
                            }
                        )
                    } else {
                        errorMessage = "Нет авторизации"
                    }
                }
            }
        )
    }

    if (showCreateGroupDialog) {
        CreateGroupChatDialog(
            onDismiss = { showCreateGroupDialog = false },
            onConfirm = { name, emails ->
                scope.launch {
                    val currentToken = token
                    if (!currentToken.isNullOrBlank()) {
                        val result = apiService.createGroupChat(currentToken, name, emails)
                        result.fold(
                            onSuccess = {
                                showCreateGroupDialog = false
                                loadConversations()
                            },
                            onFailure = { error ->
                                errorMessage = error.message ?: "Не удалось создать группу"
                            }
                        )
                    } else {
                        errorMessage = "Нет авторизации"
                    }
                }
            }
        )
    }
}

@Composable
private fun ConversationCard(
    conversation: ConversationDto,
    currentUserId: String?,
    currentUserEmail: String?,
    hasUnread: Boolean,
    typingText: String?,
    onClick: () -> Unit
) {
    fun formatLastTime(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val instant = runCatching { Instant.parse(value) }.getOrNull() ?: return value
        val now = kotlinx.datetime.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val time = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        return if (time.date == now) {
            val hour = time.hour.toString().padStart(2, '0')
            val minute = time.minute.toString().padStart(2, '0')
            "$hour:$minute"
        } else {
            val day = time.dayOfMonth
            val month = when (time.monthNumber) {
                1 -> "января"
                2 -> "февраля"
                3 -> "марта"
                4 -> "апреля"
                5 -> "мая"
                6 -> "июня"
                7 -> "июля"
                8 -> "августа"
                9 -> "сентября"
                10 -> "октября"
                11 -> "ноября"
                12 -> "декабря"
                else -> ""
            }
            "$day $month"
        }
    }

    val personalTitle = remember(conversation, currentUserId, currentUserEmail) {
        if (conversation.type != "personal") {
            conversation.name
        } else {
            val otherUser = conversation.members.firstOrNull { it.userId != currentUserId }?.user
            val byDisplayName = otherUser?.displayName?.ifBlank { null }
            val byEmail = otherUser?.email?.ifBlank { null }
            byDisplayName ?: byEmail ?: conversation.name
        }
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = personalTitle ?: "Без названия",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    formatLastTime(conversation.lastMessageAt)?.let { lastTime ->
                        Text(
                            text = lastTime,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    if (hasUnread) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                    }
                }
            }

            val preview = typingText ?: conversation.lastMessageContent
                ?.replace("\n", " ")
                ?.trim()
                ?.let { text ->
                    if (text.length > 16) {
                        text.take(13) + "..."
                    } else {
                        text
                    }
                }
            if (!preview.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = preview,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontStyle = if (typingText != null) FontStyle.Italic else FontStyle.Normal
                )
            }
        }
    }
}

@Composable
private fun CreatePersonalChatDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var identifier by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый личный чат") },
        text = {
            Column {
                OutlinedTextField(
                    value = identifier,
                    onValueChange = { identifier = it },
                    label = { Text("Email или имя пользователя") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(identifier) },
                enabled = identifier.isNotBlank()
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun CreateGroupChatDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, List<String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var emails by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый групповой чат") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название группы") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = emails,
                    onValueChange = { emails = it },
                    label = { Text("Email участников (через запятую)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val emailList = emails.split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    onConfirm(name, emailList)
                },
                enabled = name.isNotBlank()
            ) {
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
