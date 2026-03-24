package com.messenger.client.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.client.models.ConversationDto
import com.messenger.client.models.MessageAttachmentDto
import com.messenger.client.models.MessageDto
import com.messenger.client.media.FullScreenVideo
import com.messenger.client.media.InlineVideo
import com.messenger.client.media.PickedFile
import com.messenger.client.media.ZoomableImage
import com.messenger.client.media.AttachmentPicker
import com.messenger.client.media.buildAttachmentCacheKey
import com.messenger.client.media.decodeImage
import com.messenger.client.media.rememberFileOpener
import com.messenger.client.media.rememberMediaCache
import com.messenger.client.services.ApiService
import com.messenger.client.services.AuthState
import com.messenger.client.services.MessengerWebSocketService
import com.messenger.client.ui.components.TypingIndicatorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun ChatDetailScreen(
    authState: AuthState,
    conversation: ConversationDto,
    webSocketService: MessengerWebSocketService,
    onBack: () -> Unit
) {
    var conversationState by remember { mutableStateOf(conversation) }
    var messages by remember { mutableStateOf<List<MessageDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var newMessage by remember { mutableStateOf("") }
    var replyToMessage by remember { mutableStateOf<MessageDto?>(null) }
    var pendingAttachments by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadQueue by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var isQueueActive by remember { mutableStateOf(false) }
    var showAttachmentPicker by remember { mutableStateOf(false) }
    var unreadCount by remember { mutableStateOf(0) }
    var readMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var typingUsers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var addMemberIdentifier by remember { mutableStateOf("") }
    var isTyping by remember { mutableStateOf(false) }
    var pendingReadMessageId by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val apiService = remember { ApiService() }
    val token by authState.jwtToken.collectAsState()
    val currentUserEmail by authState.currentUserEmail.collectAsState()
    val currentUserDisplayName by authState.currentUserDisplayName.collectAsState()
    val listState = rememberLazyListState()

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
        for (msg in items) {
            val key = if (msg.id.isNotBlank()) {
                "id:${msg.id}"
            } else {
                "sig:${msg.senderId}|${msg.content}|${msg.sentAt}"
            }
            deduped[key] = msg
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

    fun loadConversation() {
        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) return@launch
            val result = apiService.getConversation(currentToken, conversationState.id)
            result.fold(
                onSuccess = { updated -> conversationState = updated },
                onFailure = { }
            )
        }
    }

    fun loadUnreadCount() {
        scope.launch {
            val currentToken = token
            if (!currentToken.isNullOrBlank()) {
                val result = apiService.getUnreadCount(currentToken, conversationState.id)
                result.fold(
                    onSuccess = { count -> unreadCount = count.count },
                    onFailure = { }
                )
            }
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
                            loadUnreadCount()
                        } else {
                            pendingReadMessageId = lastIncoming.id
                        }
                    }
                    isLoading = false
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось загрузить сообщения"
                    isLoading = false
                }
            )
        }
    }

    fun sendMessage(content: String, replyToMessageId: String?) {
        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                return@launch
            }

            val result = apiService.sendMessage(
                currentToken,
                conversationState.id,
                content,
                replyToMessageId,
                pendingAttachments.map { it.id }
            )
            result.fold(
                onSuccess = { message ->
                    messages = normalizeMessages(messages + message)
                    newMessage = ""
                    replyToMessage = null
                    pendingAttachments = emptyList()
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось отправить сообщение"
                }
            )
        }
    }

    fun addMember(identifier: String) {
        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) return@launch
            val result = apiService.addMemberToGroup(currentToken, conversationState.id, identifier)
            result.fold(
                onSuccess = { updated ->
                    conversationState = updated
                    addMemberIdentifier = ""
                    showAddMemberDialog = false
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось добавить участника"
                }
            )
        }
    }

    fun removeMember(userId: String) {
        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) return@launch
            val result = apiService.removeMemberFromGroup(currentToken, conversationState.id, userId)
            result.fold(
                onSuccess = {
                    loadConversation()
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось удалить участника"
                }
            )
        }
    }

    LaunchedEffect(conversationState.id) {
        loadMessages()
        loadUnreadCount()
        loadConversation()
        readMessageIds = emptySet()
        typingUsers = emptyMap()
    }

    LaunchedEffect(conversationState.id, token) {
        val currentToken = token
        if (!currentToken.isNullOrBlank()) {
            if (!webSocketService.isConnected) {
                withContext(Dispatchers.IO) {
                    webSocketService.connect(currentToken)
                }
            }
            if (webSocketService.isConnected) {
                webSocketService.joinConversation(conversationState.id)
                pendingReadMessageId?.let { pendingId ->
                    if (pendingId.isNotBlank()) {
                        webSocketService.markAsRead(conversationState.id, pendingId)
                        loadUnreadCount()
                    }
                    pendingReadMessageId = null
                }
                if (isTyping) {
                    webSocketService.sendTypingIndicator(
                        conversationState.id,
                        true,
                        currentUserDisplayName ?: currentUserEmail ?: ""
                    )
                }
            }
        }
    }

    fun uploadAttachment(file: PickedFile) {
        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                return@launch
            }
            isUploading = true
            val initResult = apiService.initUpload(
                currentToken,
                conversationState.id,
                file.name,
                file.contentType,
                file.bytes.size.toLong()
            )
            initResult.fold(
                onSuccess = { init ->
                    val uploadResult = apiService.uploadToPresignedUrl(
                        init.uploadUrl,
                        file.bytes,
                        file.contentType
                    )
                    uploadResult.fold(
                        onSuccess = {
                            val completeResult = apiService.completeUpload(
                                currentToken,
                                conversationState.id,
                                init.attachmentId
                            )
                            completeResult.fold(
                                onSuccess = {
                                    pendingAttachments = pendingAttachments + PendingAttachment(
                                        id = init.attachmentId,
                                        fileName = file.name,
                                        contentType = file.contentType,
                                        size = file.bytes.size.toLong()
                                    )
                                },
                                onFailure = { error ->
                                    errorMessage = error.message ?: "Не удалось завершить загрузку"
                                }
                            )
                        },
                        onFailure = { error ->
                            errorMessage = error.message ?: "Не удалось загрузить файл"
                        }
                    )
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось инициализировать загрузку"
                }
            )
            isUploading = false
        }
    }

    fun enqueueUploads(files: List<PickedFile>) {
        if (files.isEmpty()) return
        uploadQueue = uploadQueue + files
        if (isQueueActive) return
        isQueueActive = true
        scope.launch {
            while (uploadQueue.isNotEmpty()) {
                val next = uploadQueue.first()
                uploadQueue = uploadQueue.drop(1)
                uploadAttachment(next)
                var started = false
                var ticks = 0
                while (true) {
                    if (isUploading) started = true
                    if (started && !isUploading) break
                    if (!started && ticks > 40) break
                    delay(50)
                    ticks++
                }
            }
            isQueueActive = false
        }
    }

    fun removePendingAttachment(id: String) {
        pendingAttachments = pendingAttachments.filterNot { it.id == id }
    }

    LaunchedEffect(conversationState.id) {
        webSocketService.newMessages.collect { event ->
            if (event.conversationId == conversationState.id) {
                val msg = event.message
                val currentUserId = authState.getUserId()
                if (msg.id.isNotBlank() && messages.any { it.id == msg.id }) {
                    return@collect
                }
                if (!currentUserId.isNullOrBlank() && msg.senderId == currentUserId) {
                    return@collect
                }
                messages = normalizeMessages(messages + msg)
                val currentToken = token
                if (!currentToken.isNullOrBlank() && msg.id.isNotBlank()) {
                    if (webSocketService.isConnected) {
                        webSocketService.markAsRead(conversationState.id, msg.id)
                        loadUnreadCount()
                    } else {
                        pendingReadMessageId = msg.id
                    }
                } else {
                    loadUnreadCount()
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

    LaunchedEffect(conversationState.id) {
        webSocketService.typing.collect { event ->
            if (event.conversationId != conversationState.id) return@collect
            val currentUserId = authState.getUserId()
            if (!currentUserId.isNullOrBlank() && event.userId == currentUserId) return@collect
            val key = event.userId?.ifBlank { null }
                ?: event.userName?.ifBlank { null }
                ?: return@collect
            val label = event.userName?.ifBlank { null } ?: "Собеседник"
            typingUsers = if (event.isTyping) {
                typingUsers + (key to label)
            } else {
                typingUsers - key
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(newMessage) {
        val currentText = newMessage
        if (currentText.isBlank()) {
            if (isTyping) {
                isTyping = false
                if (webSocketService.isConnected) {
                    webSocketService.sendTypingIndicator(
                        conversationState.id,
                        false,
                        currentUserDisplayName ?: currentUserEmail ?: ""
                    )
                }
            }
            return@LaunchedEffect
        }

        if (!isTyping) {
            isTyping = true
            if (webSocketService.isConnected) {
                    webSocketService.sendTypingIndicator(
                        conversationState.id,
                        true,
                        currentUserDisplayName ?: currentUserEmail ?: ""
                    )
            }
        }

        delay(5000)
        if (newMessage == currentText && newMessage.isNotBlank()) {
            if (isTyping) {
                isTyping = false
                if (webSocketService.isConnected) {
                    webSocketService.sendTypingIndicator(
                        conversationState.id,
                        false,
                        currentUserDisplayName ?: currentUserEmail ?: ""
                    )
                }
            }
        }
    }

    DisposableEffect(conversationState.id) {
        onDispose {
            if (isTyping && webSocketService.isConnected) {
                webSocketService.sendTypingIndicator(
                    conversationState.id,
                    false,
                    currentUserDisplayName ?: currentUserEmail ?: ""
                )
            }
            webSocketService.leaveConversation(conversationState.id)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBackIos,
                        contentDescription = "Назад"
                    )
                }

                Column {
                    val headerTitle = if (conversationState.type == "personal") {
                        val otherUser = conversationState.members
                            .firstOrNull { it.userId != authState.getUserId() }
                            ?.user
                        otherUser?.displayName?.ifBlank { null }
                            ?: otherUser?.email?.ifBlank { null }
                            ?: "Чат"
                    } else {
                        conversationState.name ?: "Чат"
                    }
                    Text(
                        text = headerTitle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (conversationState.type == "group") {
                        Text(
                            text = "Участников: ${conversationState.members.size}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    val typingNames = typingUsers.values
                        .filter { it.isNotBlank() }
                        .distinct()
                    val typingPrefix = if (typingNames.isNotEmpty()) {
                        if (conversationState.type == "group") {
                            val capped = typingNames.take(3)
                            val base = capped.joinToString(", ")
                            val label = if (typingNames.size > 3) "$base и др." else base
                            if (label.isBlank()) "" else "$label "
                        } else {
                            ""
                        }
                    } else {
                        null
                    }
                    Box(modifier = Modifier.height(16.dp)) {
                        if (typingPrefix != null) {
                            TypingIndicatorText(
                                prefix = typingPrefix,
                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }

            if (conversationState.type == "group") {
                IconButton(onClick = { showAddMemberDialog = true }) {
                    Icon(
                        Icons.Filled.PersonAdd,
                        contentDescription = "Добавить участника"
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (isLoading && messages.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (errorMessage != null) {
                Card(
                    modifier = Modifier.align(Alignment.Center),
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
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages) { message ->
                        MessageBubble(
                            message = message,
                            isOwn = message.senderId == authState.getUserId(),
                            isRead = readMessageIds.contains(message.id),
                            timeLabel = formatTime(message.sentAt),
                            onReply = { replyToMessage = it },
                            apiService = apiService,
                            token = token
                        )
                    }
                }
            }
        }

        if (unreadCount > 0) {
            Card(
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "Непрочитанных: $unreadCount",
                    modifier = Modifier.padding(8.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp
                )
            }
        }

        replyToMessage?.let { reply ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ответ на: ${reply.content.take(50)}",
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    IconButton(
                        onClick = { replyToMessage = null }
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Отменить ответ",
                            modifier = Modifier.height(16.dp)
                        )
                    }
                }
            }
        }

        if (pendingAttachments.isNotEmpty() || isUploading) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isUploading) {
                        Text(
                            text = "Загрузка...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    pendingAttachments.forEach { attachment ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val label = if (attachment.contentType.startsWith("image/")) {
                                "Фото: ${attachment.fileName}"
                            } else {
                                "Файл: ${attachment.fileName}"
                            }
                            Text(
                                text = "$label • ${formatFileSize(attachment.size)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            IconButton(onClick = { removePendingAttachment(attachment.id) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Убрать файл",
                                    modifier = Modifier.height(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { showAttachmentPicker = true }
            ) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = "Прикрепить файл"
                )
            }
            OutlinedTextField(
                value = newMessage,
                onValueChange = { value ->
                    newMessage = value
                },
                label = { Text("Сообщение") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    if (newMessage.isNotBlank() || pendingAttachments.isNotEmpty()) {
                        sendMessage(newMessage, replyToMessage?.id)
                    }
                },
                enabled = (newMessage.isNotBlank() || pendingAttachments.isNotEmpty()) && !isLoading && !isUploading
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

    AttachmentPicker(
        show = showAttachmentPicker,
        onDismiss = { showAttachmentPicker = false },
        onPicked = { files ->
            showAttachmentPicker = false
            enqueueUploads(files)
        },
        onError = { error ->
            showAttachmentPicker = false
            errorMessage = error
        }
    )

    if (showAddMemberDialog) {
        AlertDialog(
            onDismissRequest = { showAddMemberDialog = false },
            title = { Text("Участники чата") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    conversationState.members.forEach { member ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val memberLabel = member.user.displayName.ifBlank {
                                member.user.email
                            }.ifBlank { member.userId }
                            Text(memberLabel)
                            TextButton(onClick = { removeMember(member.userId) }) {
                                Text("Удалить")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = addMemberIdentifier,
                        onValueChange = { addMemberIdentifier = it },
                        label = { Text("Email или имя пользователя") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { addMember(addMemberIdentifier) },
                    enabled = addMemberIdentifier.isNotBlank()
                ) {
                    Text("Добавить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddMemberDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }
}

private data class PendingAttachment(
    val id: String,
    val fileName: String,
    val contentType: String,
    val size: Long
)

private fun formatFileSize(size: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    return when {
        size >= mb -> String.format("%.1f МБ", size / mb)
        size >= kb -> String.format("%.1f КБ", size / kb)
        else -> "$size Б"
    }
}

@Composable
private fun MessageBubble(
    message: MessageDto,
    isOwn: Boolean,
    isRead: Boolean,
    timeLabel: String,
    onReply: (MessageDto) -> Unit,
    apiService: ApiService,
    token: String?
) {
    var hasTriggered by remember { mutableStateOf(false) }
    var dragTotal by remember { mutableStateOf(0f) }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.78f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .pointerInput(message.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                hasTriggered = false
                                dragTotal = 0f
                            },
                            onDragCancel = {
                                hasTriggered = false
                                dragTotal = 0f
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                dragTotal += dragAmount
                                if (!hasTriggered && dragTotal < -72f) {
                                    hasTriggered = true
                                    onReply(message)
                                }
                            }
                        )
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    val senderLabel = message.sender.displayName.ifBlank {
                        message.sender.email
                    }.ifBlank { "Неизвестно" }
                    Text(
                        text = senderLabel,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )

                    if (message.replyToMessageId != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Ответ",
                            fontSize = 10.sp,
                            color = if (isOwn) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            fontSize = 14.sp,
                            color = if (isOwn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (message.attachments.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            message.attachments.forEach { attachment ->
                                AttachmentPreview(
                                    attachment = attachment,
                                    conversationId = message.conversationId,
                                    messageId = message.id,
                                    apiService = apiService,
                                    token = token
                                )
                            }
                        }
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
                            color = if (isOwn) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        if (isOwn) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRead) "✓✓" else "✓",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentPreview(
    attachment: MessageAttachmentDto,
    conversationId: String,
    messageId: String,
    apiService: ApiService,
    token: String?
) {
    val normalizedType = attachment.contentType.lowercase()
    val isImage = normalizedType.startsWith("image/")
    val isVideo = normalizedType.startsWith("video/")
    val cache = rememberMediaCache()
    val openFile = rememberFileOpener()
    val cacheKey = remember(attachment.id, messageId, attachment.fileName) {
        buildAttachmentCacheKey(conversationId, messageId, attachment.id, attachment.fileName)
    }
    val cachedPath = remember(cacheKey) { cache.getPath(cacheKey) }
    var cachedBytes by remember(cacheKey, isImage) {
        mutableStateOf(if (isImage) cache.readBytes(cacheKey) else null)
    }
    var isCached by remember(cacheKey) { mutableStateOf(cache.exists(cacheKey)) }
    var isDownloading by remember(cacheKey) { mutableStateOf(false) }
    var failed by remember(attachment.id, messageId) { mutableStateOf(false) }
    var showFull by remember(attachment.id, messageId) { mutableStateOf(false) }
    var openFailed by remember(attachment.id, messageId) { mutableStateOf(false) }

    LaunchedEffect(cacheKey, token) {
        if (isCached) {
            if (isImage && cachedBytes == null) {
                cachedBytes = cache.readBytes(cacheKey)
            }
            return@LaunchedEffect
        }
        if (token.isNullOrBlank() || isDownloading) return@LaunchedEffect
        isDownloading = true
        val urlResult = apiService.getMediaUrl(token, conversationId, messageId, attachment.id)
        urlResult.fold(
            onSuccess = { response ->
                val downloadResult = apiService.downloadMediaBytes(response.url)
                downloadResult.fold(
                    onSuccess = { bytes ->
                        cache.writeBytes(cacheKey, bytes)
                        isCached = true
                        if (isImage) {
                            cachedBytes = bytes
                        }
                    },
                    onFailure = { failed = true }
                )
            },
            onFailure = { failed = true }
        )
        isDownloading = false
    }

    if (isVideo) {
        when {
            isCached -> InlineVideo(
                path = cachedPath,
                muted = true,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                onClick = { showFull = true }
            )
            failed -> Text(
                text = "РќРµ СѓРґР°Р»РѕСЃСЊ Р·Р°РіСЂСѓР·РёС‚СЊ РІРёРґРµРѕ",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            else -> Text(
                text = "Р—Р°РіСЂСѓР·РєР° РІРёРґРµРѕ...",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        if (showFull && isCached) {
            FullScreenVideo(
                path = cachedPath,
                onDismiss = { showFull = false }
            )
        }
    } else if (isImage) {
        val bitmap = cachedBytes?.let { decodeImage(it) }
        when {
            bitmap != null -> {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showFull = true },
                    contentScale = ContentScale.Crop
                )
            }
            failed -> Text(
                text = "Не удалось загрузить фото",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            else -> Text(
                text = "Загрузка фото...",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        if (showFull && bitmap != null) {
            ZoomableImage(
                content = {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                },
                onDismiss = { showFull = false }
            )
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val label = "Файл: ${attachment.fileName} • ${formatFileSize(attachment.size)}"
            Text(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            IconButton(
                onClick = {
                    openFailed = if (isCached) {
                        !openFile(cachedPath, attachment.contentType)
                    } else {
                        true
                    }
                },
                enabled = isCached
            ) {
                Icon(
                    Icons.Filled.OpenInNew,
                    contentDescription = "Открыть файл"
                )
            }
        }
        if (openFailed) {
            Text(
                text = "Не удалось открыть файл",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
