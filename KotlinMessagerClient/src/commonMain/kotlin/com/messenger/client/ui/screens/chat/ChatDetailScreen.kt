package com.messenger.client.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.messenger.client.ui.components.CachedUserProfilePhoto
import com.messenger.client.ui.components.TypingIndicatorText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun ChatDetailScreen(
    authState: AuthState,
    conversation: ConversationDto,
    webSocketService: MessengerWebSocketService,
    onOpenTransferChannel: (ConversationDto) -> Unit = {},
    onOpenUserProfile: (String) -> Unit = {},
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
    var isCreatingTransferInvite by remember { mutableStateOf(false) }
    var isAcceptingTransferInvite by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val apiService = remember { ApiService() }
    val token by authState.jwtToken.collectAsState()
    val currentUserEmail by authState.currentUserEmail.collectAsState()
    val currentUserDisplayName by authState.currentUserDisplayName.collectAsState()
    val currentUserId by authState.currentUserId.collectAsState()
    val listState = rememberLazyListState()
    val chatBackground = Color(0xFFF6F7FB)
    val chatAccent = Color(0xFF4F6FF0)
    val bubbleColors = ChatBubbleColors(
        ownBubble = chatAccent,
        otherBubble = Color.White,
        ownText = Color.White,
        otherText = Color(0xFF1F2937),
        ownMeta = Color.White.copy(alpha = 0.72f),
        otherMeta = Color(0xFF64748B),
        bubbleBorder = Color(0xFFE2E8F0)
    )
    val inputBorder = Color(0xFFD1D7E2)
    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = chatAccent,
        unfocusedBorderColor = inputBorder,
        focusedLabelColor = chatAccent,
        cursorColor = chatAccent
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

    fun inviteToTransferChannel() {
        if (conversationState.type != "personal") return
        if (isCreatingTransferInvite) return
        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                return@launch
            }
            isCreatingTransferInvite = true
            val createResult = apiService.createStreamInvite(
                token = currentToken,
                personalChatId = conversationState.id,
                streamChatName = "Канал передачи"
            )
            createResult.fold(
                onSuccess = { response ->
                    val inviteToken = response.token.ifBlank { response.tokenPascal.orEmpty() }
                    if (inviteToken.isBlank()) {
                        errorMessage = "Сервер не вернул токен приглашения"
                    } else {
                        val inviteMessage = buildString {
                            append("Приглашение в канал передачи файлов.")
                            if (!response.expiresAt.isNullOrBlank()) {
                                append("\nДействует до: ")
                                append(response.expiresAt)
                            }
                            if (!response.streamChatId.isNullOrBlank()) {
                                append("\nTRANSFER_CHANNEL_ID:")
                                append(response.streamChatId)
                            }
                            append("\nTRANSFER_CHANNEL_INVITE_TOKEN:")
                            append(inviteToken)
                        }
                        val sendResult = apiService.sendMessage(
                            token = currentToken,
                            conversationId = conversationState.id,
                            content = inviteMessage,
                            replyToMessageId = null,
                            attachmentIds = emptyList()
                        )
                        sendResult.fold(
                            onSuccess = { message ->
                                messages = normalizeMessages(messages + message)
                            },
                            onFailure = { error ->
                                errorMessage = error.message ?: "Инвайт создан, но не удалось отправить сообщение"
                            }
                        )
                    }
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось создать инвайт в канал передачи"
                }
            )
            isCreatingTransferInvite = false
        }
    }

    suspend fun findExistingTransferChannel(currentToken: String): ConversationDto? {
        val conversationsResult = apiService.getAllConversations(currentToken)
        val allConversations = conversationsResult.getOrNull() ?: return null
        val personalMemberIds = conversationState.members.map { it.userId }.toSet()
        val matching = allConversations
            .asSequence()
            .filter { it.type == "stream" }
            .filter { streamConversation ->
                if (personalMemberIds.isEmpty()) return@filter true
                val streamMemberIds = streamConversation.members.map { it.userId }.toSet()
                personalMemberIds.all { streamMemberIds.contains(it) }
            }
            .toList()
        return matching.maxByOrNull { streamConversation ->
            parseInstantOrNull(streamConversation.lastMessageAt ?: streamConversation.createdAt)
                ?: Instant.DISTANT_PAST
        }
    }

    fun openTransferChannelFromInvite(inviteToken: String, transferChannelIdHint: String? = null) {
        if (isAcceptingTransferInvite) return
        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                return@launch
            }
            isAcceptingTransferInvite = true
            if (!transferChannelIdHint.isNullOrBlank()) {
                val hintedConversation = apiService.getConversation(currentToken, transferChannelIdHint).getOrNull()
                if (hintedConversation != null) {
                    onOpenTransferChannel(hintedConversation)
                    isAcceptingTransferInvite = false
                    return@launch
                }
            }

            val acceptResult = if (inviteToken.isNotBlank()) {
                apiService.acceptStreamInvite(currentToken, inviteToken)
            } else {
                Result.failure(Exception("Пустой токен приглашения"))
            }
            val acceptedChannelId = acceptResult.getOrNull()?.streamChatId?.ifBlank { null }
            if (acceptedChannelId != null) {
                val acceptedConversation = apiService.getConversation(currentToken, acceptedChannelId).getOrNull()
                if (acceptedConversation != null) {
                    onOpenTransferChannel(acceptedConversation)
                    isAcceptingTransferInvite = false
                    return@launch
                }
            }

            val existingConversation = findExistingTransferChannel(currentToken)
            if (existingConversation != null) {
                onOpenTransferChannel(existingConversation)
                isAcceptingTransferInvite = false
                return@launch
            }

            errorMessage = acceptResult.exceptionOrNull()?.message
                ?: "Не удалось открыть канал передачи по приглашению"
            isAcceptingTransferInvite = false
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
        if (currentToken.isNullOrBlank()) return@LaunchedEffect
        var wasConnected = false
        while (isActive) {
            val connected = webSocketService.isConnected
            if (connected && !wasConnected) {
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
            wasConnected = connected
            delay(3_000)
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
                                        size = file.bytes.size.toLong(),
                                        previewBytes = if (file.contentType.startsWith("image/")) file.bytes else null
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
            .background(chatBackground)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val personalUser = conversationState.members
                .firstOrNull { it.userId != currentUserId }
                ?.user
            val canOpenProfile = conversationState.type == "personal" && !personalUser?.id.isNullOrBlank()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (canOpenProfile) {
                    Modifier.clickable { onOpenUserProfile(personalUser!!.id) }
                } else {
                    Modifier
                }
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBackIos,
                        contentDescription = "Назад"
                    )
                }

                if (conversationState.type == "personal") {
                    CachedUserProfilePhoto(
                        token = token,
                        userId = personalUser?.id.orEmpty(),
                        photoId = personalUser?.latestProfilePhotoId,
                        displayName = personalUser?.displayName ?: "Чат",
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(21.dp),
                        contentScale = ContentScale.Crop,
                        showLoadingIndicator = false
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }

                Column {
                    val headerTitle = when (conversationState.type) {
                        "personal" -> {
                            personalUser?.displayName?.ifBlank { null }
                                ?: personalUser?.email?.ifBlank { null }
                                ?: "Чат"
                        }
                        else -> conversationState.name ?: "Чат"
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
                                    color = chatAccent
                                )
                            )
                        }
                    }
                }
            }

            when (conversationState.type) {
                "group" -> {
                    IconButton(onClick = { showAddMemberDialog = true }) {
                        Icon(
                            Icons.Filled.PersonAdd,
                            contentDescription = "Добавить участника"
                        )
                    }
                }
                "personal" -> {
                    IconButton(
                        onClick = { inviteToTransferChannel() },
                        enabled = !isCreatingTransferInvite
                    ) {
                        Icon(
                            Icons.Filled.OpenInNew,
                            contentDescription = "Пригласить в канал передачи"
                        )
                    }
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
                            showSenderName = conversationState.type == "group",
                            colors = bubbleColors,
                            onReply = { replyToMessage = it },
                            apiService = apiService,
                            token = token,
                            onOpenTransferChannelFromInvite = { inviteToken, transferChannelId ->
                                openTransferChannelFromInvite(inviteToken, transferChannelId)
                            }
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
                        val isImage = attachment.contentType.startsWith("image/")
                        val previewBitmap = if (isImage && attachment.previewBytes != null) {
                            remember(attachment.id, attachment.previewBytes) {
                                decodeImage(attachment.previewBytes)
                            }
                        } else {
                            null
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (previewBitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = previewBitmap,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            val label = if (isImage) {
                                "Фото: ${attachment.fileName}"
                            } else {
                                "Файл: ${attachment.fileName}"
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "$label • ${formatFileSize(attachment.size)}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
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
                    maxLines = 4,
                    colors = inputColors
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (newMessage.isNotBlank() || pendingAttachments.isNotEmpty()) {
                            sendMessage(newMessage, replyToMessage?.id)
                        }
                    },
                    enabled = (newMessage.isNotBlank() || pendingAttachments.isNotEmpty()) && !isLoading && !isUploading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = chatAccent,
                        contentColor = Color.White,
                        disabledContainerColor = chatAccent.copy(alpha = 0.5f),
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
    val size: Long,
    val previewBytes: ByteArray?
)

private data class ChatBubbleColors(
    val ownBubble: Color,
    val otherBubble: Color,
    val ownText: Color,
    val otherText: Color,
    val ownMeta: Color,
    val otherMeta: Color,
    val bubbleBorder: Color
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

private const val TransferInviteTokenPrefix = "TRANSFER_CHANNEL_INVITE_TOKEN:"
private const val TransferChannelIdPrefix = "TRANSFER_CHANNEL_ID:"

private fun extractTransferChannelInviteToken(content: String): String? {
    val prefixes = listOf(
        TransferInviteTokenPrefix,
        "Токен канала:",
        "Stream-инвайт:"
    )
    return content.lineSequence()
        .map { it.trim() }
        .firstNotNullOfOrNull { line ->
            prefixes.firstNotNullOfOrNull { prefix ->
                if (line.startsWith(prefix, ignoreCase = true)) {
                    line.substringAfter(':').trim().ifBlank { null }
                } else {
                    null
                }
            }
        }
}

private fun extractTransferChannelId(content: String): String? {
    return content.lineSequence()
        .map { it.trim() }
        .firstNotNullOfOrNull { line ->
            if (line.startsWith(TransferChannelIdPrefix, ignoreCase = true)) {
                line.substringAfter(':').trim().ifBlank { null }
            } else {
                null
            }
        }
}

private fun sanitizeInviteMetaLines(content: String): String {
    return content.lineSequence()
        .filterNot { line ->
            val trimmed = line.trim()
            trimmed.startsWith(TransferInviteTokenPrefix, ignoreCase = true) ||
                trimmed.startsWith(TransferChannelIdPrefix, ignoreCase = true)
        }
        .joinToString("\n")
        .trim()
}

@Composable
private fun MessageBubble(
    message: MessageDto,
    isOwn: Boolean,
    isRead: Boolean,
    timeLabel: String,
    showSenderName: Boolean,
    colors: ChatBubbleColors,
    onReply: (MessageDto) -> Unit,
    apiService: ApiService,
    token: String?,
    onOpenTransferChannelFromInvite: ((String, String?) -> Unit)?
) {
    var hasTriggered by remember { mutableStateOf(false) }
    var dragTotal by remember { mutableStateOf(0f) }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.78f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start
        ) {
            val bubbleShape = if (isOwn) {
                RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp)
            } else {
                RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 6.dp)
            }
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
                shape = bubbleShape,
                colors = CardDefaults.cardColors(
                    containerColor = if (isOwn) colors.ownBubble else colors.otherBubble
                ),
                border = if (isOwn) null else BorderStroke(1.dp, colors.bubbleBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    if (showSenderName) {
                        val senderLabel = message.sender.displayName.ifBlank {
                            message.sender.email
                        }.ifBlank { "Неизвестно" }
                        Text(
                            text = senderLabel,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            color = if (isOwn) colors.ownText else colors.otherText
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if (message.replyToMessageId != null) {
                        Text(
                            text = "Ответ",
                            fontSize = 10.sp,
                            color = if (isOwn) colors.ownMeta else colors.otherMeta
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (message.content.isNotBlank()) {
                        val inviteToken = extractTransferChannelInviteToken(message.content)
                        val transferChannelId = extractTransferChannelId(message.content)
                        val normalizedContent = sanitizeInviteMetaLines(message.content)
                        if (normalizedContent.isNotBlank()) {
                            Text(
                                text = normalizedContent,
                                fontSize = 14.sp,
                                color = if (isOwn) colors.ownText else colors.otherText
                            )
                        }
                        if ((inviteToken != null || transferChannelId != null) &&
                            onOpenTransferChannelFromInvite != null
                        ) {
                            Spacer(modifier = Modifier.height(6.dp))
                            TextButton(
                                onClick = {
                                    onOpenTransferChannelFromInvite(
                                        inviteToken.orEmpty(),
                                        transferChannelId
                                    )
                                }
                            ) {
                                Text("Перейти в канал")
                            }
                        }
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
                            color = if (isOwn) colors.ownMeta else colors.otherMeta
                        )
                        if (isOwn) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRead) "✓✓" else "✓",
                                fontSize = 10.sp,
                                color = colors.ownMeta
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
                text = "Не удалось загрузить видео",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            else -> Text(
                text = "Загрузка видео...",
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

