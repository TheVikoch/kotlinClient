package com.messenger.client.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.client.media.StreamPickedFile
import com.messenger.client.media.rememberStreamFilePicker
import com.messenger.client.models.ConversationDto
import com.messenger.client.models.MessageDto
import com.messenger.client.models.StreamTransferOfferDto
import com.messenger.client.services.ApiService
import com.messenger.client.services.AuthState
import com.messenger.client.services.MessengerWebSocketService
import com.messenger.client.transfer.StreamTransferController
import com.messenger.client.transfer.StreamTransferPhase
import com.messenger.client.transfer.StreamTransferUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun TransferChannelScreen(
    authState: AuthState,
    conversation: ConversationDto,
    webSocketService: MessengerWebSocketService,
    streamTransferController: StreamTransferController,
    onBack: () -> Unit
) {
    var conversationState by remember { mutableStateOf(conversation) }
    var messages by remember { mutableStateOf<List<MessageDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var newMessage by remember { mutableStateOf("") }
    var readMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingReadMessageId by remember { mutableStateOf<String?>(null) }
    var sentTransferStateMessages by remember { mutableStateOf<Set<String>>(emptySet()) }

    val streamOffer by streamTransferController.offer.collectAsState()
    val streamTransferState by streamTransferController.state.collectAsState()
    val streamTransferError by streamTransferController.error.collectAsState()
    val activeTransferState = streamTransferState?.takeIf { it.streamChatId == conversationState.id }
    val activeOffer = streamOffer?.takeIf { it.streamChatId == conversationState.id }
    val activeTransferError = streamTransferError
        ?.takeIf { it.streamChatId == conversationState.id }
        ?.message

    val scope = rememberCoroutineScope()
    val apiService = remember { ApiService() }
    val token by authState.jwtToken.collectAsState()
    val currentUserEmail by authState.currentUserEmail.collectAsState()
    val currentUserDisplayName by authState.currentUserDisplayName.collectAsState()
    val listState = rememberLazyListState()

    val channelBackground = Color(0xFFF4F8F6)
    val channelAccent = Color(0xFF17765D)
    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = channelAccent,
        unfocusedBorderColor = Color(0xFFD1D7E2),
        focusedLabelColor = channelAccent,
        cursorColor = channelAccent
    )

    fun parseInstantOrNull(value: String): Instant? {
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
    }

    fun formatTime(value: String): String {
        val instant = parseInstantOrNull(value) ?: return value
        val time = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val hour = time.hour.toString().padStart(2, '0')
        val minute = time.minute.toString().padStart(2, '0')
        return "$hour:$minute"
    }

    fun normalizeMessages(items: List<MessageDto>): List<MessageDto> {
        val deduped = LinkedHashMap<String, MessageDto>()
        for (message in items) {
            val key = if (message.id.isNotBlank()) {
                "id:${message.id}"
            } else {
                "sig:${message.senderId}|${message.content}|${message.sentAt}"
            }
            deduped[key] = message
        }
        val list = deduped.values.toList()
        val instants = list.map { msg -> parseInstantOrNull(msg.sentAt) }
        val hasAnyTimestamp = instants.any { it != null }
        return if (hasAnyTimestamp) {
            list.withIndex()
                .sortedBy { instants[it.index] ?: Instant.DISTANT_PAST }
                .map { it.value }
        } else {
            list.reversed()
        }
    }

    fun loadMessages() {
        scope.launch {
            isLoading = true
            errorMessage = null
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                isLoading = false
                return@launch
            }
            val result = apiService.getMessages(currentToken, conversationState.id)
            result.fold(
                onSuccess = { response ->
                    messages = normalizeMessages(response.messages)
                    val lastIncoming = messages.lastOrNull { it.senderId != authState.getUserId() }
                    if (lastIncoming?.id?.isNotBlank() == true) {
                        if (webSocketService.isConnected) {
                            webSocketService.markAsRead(conversationState.id, lastIncoming.id)
                        } else {
                            pendingReadMessageId = lastIncoming.id
                        }
                    }
                    isLoading = false
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось загрузить сообщения канала"
                    isLoading = false
                }
            )
        }
    }

    fun sendMessage(content: String) {
        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                return@launch
            }
            val result = apiService.sendMessage(
                token = currentToken,
                conversationId = conversationState.id,
                content = content,
                replyToMessageId = null,
                attachmentIds = emptyList()
            )
            result.fold(
                onSuccess = { message ->
                    messages = normalizeMessages(messages + message)
                    newMessage = ""
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось отправить сообщение"
                }
            )
        }
    }

    fun startTransfer(file: StreamPickedFile) {
        streamTransferController.startTransfer(file, conversationState.id)
    }

    fun acceptOffer(offer: StreamTransferOfferDto) {
        scope.launch {
            streamTransferController.acceptOffer(offer)
        }
    }

    fun cancelTransfer(reason: String = "user_canceled") {
        streamTransferController.cancelActiveTransfer(reason)
    }

    fun shouldKeepChannelJoined(state: StreamTransferUiState?): Boolean {
        return state?.phase == StreamTransferPhase.AwaitingAcceptance ||
            state?.phase == StreamTransferPhase.Transferring ||
            state?.phase == StreamTransferPhase.WaitingComplete ||
            state?.phase == StreamTransferPhase.Verifying ||
            state?.phase == StreamTransferPhase.Saving
    }

    val streamFilePicker = rememberStreamFilePicker(
        onPicked = { file -> startTransfer(file) },
        onError = { errorMessage = it }
    )

    LaunchedEffect(conversationState.id) {
        loadMessages()
        readMessageIds = emptySet()
    }

    LaunchedEffect(conversationState.id, token) {
        val currentToken = token
        if (currentToken.isNullOrBlank()) return@LaunchedEffect
        var wasConnected = false
        while (isActive) {
            val connected = webSocketService.isConnected
            if (connected && !wasConnected) {
                webSocketService.joinConversation(conversationState.id)
                pendingReadMessageId?.let { pendingId ->
                    if (pendingId.isNotBlank()) {
                        webSocketService.markAsRead(conversationState.id, pendingId)
                    }
                    pendingReadMessageId = null
                }
            }
            wasConnected = connected
            delay(3_000)
        }
    }

    LaunchedEffect(conversationState.id) {
        webSocketService.newMessages.collect { event ->
            if (event.conversationId != conversationState.id) return@collect
            val msg = event.message
            val currentUserId = authState.getUserId()
            if (msg.id.isNotBlank() && messages.any { it.id == msg.id }) return@collect
            if (!currentUserId.isNullOrBlank() && msg.senderId == currentUserId) return@collect
            messages = normalizeMessages(messages + msg)
            if (msg.id.isNotBlank()) {
                if (webSocketService.isConnected) {
                    webSocketService.markAsRead(conversationState.id, msg.id)
                } else {
                    pendingReadMessageId = msg.id
                }
            }
        }
    }

    LaunchedEffect(conversationState.id) {
        webSocketService.messageRead.collect { event ->
            if (event.conversationId == conversationState.id && event.messageId.isNotBlank()) {
                readMessageIds = readMessageIds + event.messageId
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(
        activeTransferState?.transferId,
        activeTransferState?.phase,
        token
    ) {
        val state = activeTransferState ?: return@LaunchedEffect
        if (!state.isSender) return@LaunchedEffect
        val phase = state.phase
        if (phase != StreamTransferPhase.Completed &&
            phase != StreamTransferPhase.Failed &&
            phase != StreamTransferPhase.Canceled
        ) {
            return@LaunchedEffect
        }
        val currentToken = token ?: return@LaunchedEffect
        val stateKey = "${state.transferId}:${phase.name}"
        if (sentTransferStateMessages.contains(stateKey)) return@LaunchedEffect
        sentTransferStateMessages = sentTransferStateMessages + stateKey

        val baseLabel = if (state.isSender) "Отправка файла" else "Получение файла"
        val statusMessage = when (phase) {
            StreamTransferPhase.Completed -> "$baseLabel завершена успешно: ${state.fileName}"
            StreamTransferPhase.Failed -> {
                val reason = state.message?.ifBlank { null } ?: "ошибка передачи"
                "$baseLabel завершилась с ошибкой: $reason"
            }
            StreamTransferPhase.Canceled -> {
                val reason = state.message?.ifBlank { null } ?: "передача отменена"
                "$baseLabel отменена: $reason"
            }
            else -> return@LaunchedEffect
        }
        if (messages.any { it.senderId == authState.getUserId() && it.content == statusMessage }) {
            return@LaunchedEffect
        }
        val sendResult = apiService.sendMessage(
            token = currentToken,
            conversationId = conversationState.id,
            content = statusMessage,
            replyToMessageId = null,
            attachmentIds = emptyList()
        )
        sendResult.onSuccess { message ->
            if (messages.none { it.id == message.id }) {
                messages = normalizeMessages(messages + message)
            }
        }
    }

    DisposableEffect(conversationState.id, activeTransferState?.phase) {
        onDispose {
            if (!shouldKeepChannelJoined(activeTransferState)) {
                webSocketService.leaveConversation(conversationState.id)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(channelBackground)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBackIos,
                        contentDescription = "Назад"
                    )
                }
                Column {
                    Text(
                        text = conversationState.name ?: "Канал передачи",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Файлы и служебные сообщения передачи",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        activeTransferState?.let { state ->
            val canCancel = state.phase == StreamTransferPhase.AwaitingAcceptance ||
                state.phase == StreamTransferPhase.Transferring ||
                state.phase == StreamTransferPhase.WaitingComplete ||
                state.phase == StreamTransferPhase.Verifying ||
                state.phase == StreamTransferPhase.Saving
            StreamTransferStatusCard(
                state = state,
                modifier = Modifier.padding(bottom = 10.dp),
                onCancel = if (canCancel) {
                    { cancelTransfer() }
                } else {
                    null
                }
            )
        }
        if (!activeTransferError.isNullOrBlank()) {
            Text(
                text = activeTransferError,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (isLoading && messages.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (errorMessage != null) {
                Card(
                    modifier = Modifier.align(Alignment.Center),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = errorMessage ?: "",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages) { message ->
                        TransferMessageBubble(
                            message = message,
                            isOwn = message.senderId == authState.getUserId(),
                            isRead = readMessageIds.contains(message.id),
                            timeLabel = formatTime(message.sentAt)
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { streamFilePicker.pickFiles() }) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "Отправить файл"
                    )
                }
                OutlinedTextField(
                    value = newMessage,
                    onValueChange = { newMessage = it },
                    label = { Text("Сообщение") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    colors = inputColors
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (newMessage.isNotBlank()) {
                            sendMessage(newMessage)
                        }
                    },
                    enabled = newMessage.isNotBlank() && !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = channelAccent,
                        contentColor = Color.White,
                        disabledContainerColor = channelAccent.copy(alpha = 0.5f),
                        disabledContentColor = Color.White.copy(alpha = 0.7f)
                    )
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Отправить"
                        )
                    }
                }
            }
        }
    }

    activeOffer?.let { offer ->
        AlertDialog(
            onDismissRequest = {
                streamTransferController.rejectOffer(offer, "dismissed")
            },
            title = { Text("Входящий файл") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Файл: ${offer.fileName}")
                    Text("Размер: ${formatFileSize(offer.fileSize)}")
                }
            },
            confirmButton = {
                Button(onClick = { acceptOffer(offer) }) {
                    Text("Принять")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        streamTransferController.rejectOffer(offer, "rejected")
                    }
                ) {
                    Text("Отклонить")
                }
            }
        )
    }
}

@Composable
private fun TransferMessageBubble(
    message: MessageDto,
    isOwn: Boolean,
    isRead: Boolean,
    timeLabel: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.78f),
            colors = CardDefaults.cardColors(
                containerColor = if (isOwn) Color(0xFF17765D) else Color.White
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.content.isNotBlank()) {
                    Text(
                        text = message.content,
                        color = if (isOwn) Color.White else Color(0xFF1F2937),
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeLabel,
                        fontSize = 10.sp,
                        color = if (isOwn) Color.White.copy(alpha = 0.72f) else Color(0xFF64748B)
                    )
                    if (isOwn) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isRead) "✓✓" else "✓",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.72f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamTransferStatusCard(
    state: StreamTransferUiState,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null
) {
    val progress = if (state.totalChunks > 0) {
        (state.transferredChunks.toFloat() / state.totalChunks.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val roleLabel = if (state.isSender) "Отправка" else "Получение"
    val phaseLabel = when (state.phase) {
        StreamTransferPhase.AwaitingAcceptance -> "ожидание принятия"
        StreamTransferPhase.Transferring -> "передача"
        StreamTransferPhase.WaitingComplete -> "ожидание подтверждения"
        StreamTransferPhase.Verifying -> "проверка"
        StreamTransferPhase.Saving -> "сохранение"
        StreamTransferPhase.Completed -> "завершено"
        StreamTransferPhase.Failed -> "ошибка"
        StreamTransferPhase.Canceled -> "отменено"
    }
    val progressPercent = (progress * 100).toInt().coerceIn(0, 100)
    val transferredBytes = (state.fileSize * progress).toLong().coerceIn(0, state.fileSize)
    val accent = if (state.isSender) Color(0xFF17765D) else MaterialTheme.colorScheme.secondary
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$roleLabel: ${state.fileName}",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (onCancel != null) {
                    TextButton(onClick = onCancel) {
                        Text("Отменить")
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = progress,
                color = accent,
                trackColor = accent.copy(alpha = 0.18f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = phaseLabel,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    text = "$progressPercent%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
            val detail = state.message?.ifBlank { null }
            val chunkLabel = if (state.totalChunks > 0) {
                "${state.transferredChunks}/${state.totalChunks}"
            } else {
                null
            }
            val sizeLabel = "${formatFileSize(transferredBytes)} из ${formatFileSize(state.fileSize)}"
            Text(
                text = detail ?: listOfNotNull(sizeLabel, chunkLabel).joinToString(" • "),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

private fun formatFileSize(size: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        size >= mb -> String.format("%.1f МБ", size / mb)
        size >= kb -> String.format("%.1f КБ", size / kb)
        else -> "$size Б"
    }
}
