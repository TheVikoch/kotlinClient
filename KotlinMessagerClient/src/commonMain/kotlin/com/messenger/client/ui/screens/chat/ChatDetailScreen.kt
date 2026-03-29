package com.messenger.client.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.outlined.InsertEmoticon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.client.models.ConversationDto
import com.messenger.client.models.ConversationAttachmentEntryDto
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
import com.messenger.client.services.ChatMessagesWarmCacheDurationMs
import com.messenger.client.services.ChatMessagesCacheEntry
import com.messenger.client.services.ChatAttachmentUploadManager
import com.messenger.client.services.MessengerWebSocketService
import com.messenger.client.services.OutgoingAttachmentDraft
import com.messenger.client.services.OutgoingMessageUploadStage
import com.messenger.client.services.OutgoingMessageUploadTask
import com.messenger.client.services.buildChatMessagesCacheEntry
import com.messenger.client.services.buildChatMessagesCacheSecret
import com.messenger.client.services.chatMessagesCacheNowMillis
import com.messenger.client.services.rememberChatMessagesCache
import com.messenger.client.ui.components.CachedConversationAvatar
import com.messenger.client.ui.components.CachedUserProfilePhoto
import com.messenger.client.ui.components.TypingIndicatorText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

@Composable
fun ChatDetailScreen(
    authState: AuthState,
    conversation: ConversationDto,
    webSocketService: MessengerWebSocketService,
    uploadManager: ChatAttachmentUploadManager,
    onOpenTransferChannel: (ConversationDto) -> Unit = {},
    onConversationUpdated: (ConversationDto) -> Unit = {},
    onOpenUserProfile: (String) -> Unit = {},
    onBack: () -> Unit
) {
    var conversationState by remember { mutableStateOf(conversation) }
    var messages by remember { mutableStateOf<List<MessageDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var newMessage by remember { mutableStateOf(TextFieldValue("")) }
    var replyToMessage by remember { mutableStateOf<MessageDto?>(null) }
    var pendingAttachments by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    var nextPendingAttachmentId by remember { mutableStateOf(0) }
    var showAttachmentPicker by remember { mutableStateOf(false) }
    var showConversationAvatarPicker by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var selectedEmojiCategoryId by remember { mutableStateOf(EmojiCategories.first().id) }
    var unreadCount by remember { mutableStateOf(0) }
    var readMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var typingUsers by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showAddMemberDialog by remember { mutableStateOf(false) }
    var addMemberIdentifier by remember { mutableStateOf("") }
    var isTyping by remember { mutableStateOf(false) }
    var isConversationAvatarUploading by remember { mutableStateOf(false) }
    var pendingReadMessageId by remember { mutableStateOf<String?>(null) }
    var isCreatingTransferInvite by remember { mutableStateOf(false) }
    var isAcceptingTransferInvite by remember { mutableStateOf(false) }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    var editingMessage by remember { mutableStateOf<MessageDto?>(null) }
    var selectedMessageForActions by remember { mutableStateOf<MessageDto?>(null) }
    var isMessageActionLoading by remember { mutableStateOf(false) }
    var deletingMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAttachmentsBrowser by remember { mutableStateOf(false) }
    var selectedAttachmentsTab by remember { mutableStateOf(ChatAttachmentsTab.Media) }
    var isLoadingHistory by remember { mutableStateOf(false) }
    var hasMoreHistory by remember { mutableStateOf(false) }
    var nextHistoryCursor by remember { mutableStateOf<String?>(null) }
    var initialScrollMessageId by remember { mutableStateOf<String?>(null) }
    var pendingInitialReadMessageId by remember { mutableStateOf<String?>(null) }
    var pendingHistoryRestore by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pendingScrollToBottom by remember { mutableStateOf(false) }
    var isAwaitingInitialPosition by remember { mutableStateOf(false) }
    var hasLoadedChatSnapshot by remember { mutableStateOf(false) }
    var conversationHistoryAttachments by remember { mutableStateOf<List<ConversationAttachmentEntry>>(emptyList()) }
    var areConversationAttachmentsLoading by remember { mutableStateOf(false) }
    var hasLoadedConversationAttachments by remember { mutableStateOf(false) }
    var conversationAttachmentsError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val apiService = remember { ApiService() }
    val token by authState.jwtToken.collectAsState()
    val refreshToken by authState.refreshToken.collectAsState()
    val currentUserEmail by authState.currentUserEmail.collectAsState()
    val currentUserDisplayName by authState.currentUserDisplayName.collectAsState()
    val currentUserId by authState.currentUserId.collectAsState()
    val currentSessionId by authState.currentSessionId.collectAsState()
    val focusManager = LocalFocusManager.current
    val isDraftConversation = conversationState.id.startsWith(DraftConversationIdPrefix)
    val listState = rememberLazyListState()
    val chatMessagesCache = rememberChatMessagesCache()
    val chatBackground = Color(0xFFF6F7FB)
    val chatAccent = Color(0xFF4F6FF0)
    val bubbleColors = ChatBubbleColors(
        ownBubble = chatAccent,
        otherBubble = Color.White,
        ownText = Color.White,
        otherText = Color(0xFF1F2937),
        ownMeta = Color.White.copy(alpha = 0.72f),
        otherMeta = Color(0xFF64748B),
        bubbleBorder = Color(0xFFE2E8F0),
        replyAccent = chatAccent
    )
    val messagesById = remember(messages) { messages.associateBy { it.id } }
    val messageIndexById = remember(messages) {
        messages.mapIndexedNotNull { index, message ->
            message.id.takeIf { it.isNotBlank() }?.let { it to index }
        }.toMap()
    }
    val inputBorder = Color(0xFFD1D7E2)
    val inputColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = chatAccent,
        unfocusedBorderColor = inputBorder,
        focusedLabelColor = chatAccent,
        cursorColor = chatAccent
    )
    val messagePageSize = 50
    val historyPrefetchThreshold = 3
    val chatCacheUserId = currentUserId?.ifBlank { null }
    val chatCacheSecret = remember(currentUserId, refreshToken, token, currentSessionId) {
        buildChatMessagesCacheSecret(
            userId = currentUserId,
            refreshToken = refreshToken,
            jwtToken = token,
            sessionId = currentSessionId
        )
    }
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

    fun normalizeAttachmentEntries(items: List<ConversationAttachmentEntry>): List<ConversationAttachmentEntry> {
        val deduped = LinkedHashMap<String, ConversationAttachmentEntry>()
        for (entry in items) {
            deduped[entry.key] = entry
        }
        return deduped.values
            .sortedWith(
                compareByDescending<ConversationAttachmentEntry> { it.sentAtInstant ?: Instant.DISTANT_PAST }
                    .thenByDescending { it.messageOrder }
                    .thenByDescending { it.key }
            )
    }

    val loadedMessageAttachmentEntries = remember(messages, deletingMessageIds, conversationState.id) {
        messages.withIndex()
            .filter { (_, message) -> message.id !in deletingMessageIds }
            .flatMap { (index, message) ->
                message.attachments.map { attachment ->
                    ConversationAttachmentEntry(
                        key = "${message.id}:${attachment.id}",
                        conversationId = message.conversationId.ifBlank { conversationState.id },
                        messageId = message.id,
                        senderLabel = resolveMessageAuthorLabel(message),
                        sentAt = message.sentAt,
                        sentAtInstant = parseAttachmentInstantOrNull(message.sentAt),
                        messageOrder = index,
                        attachment = attachment
                    )
                }
            }
            .sortedWith(
                compareByDescending<ConversationAttachmentEntry> { it.sentAtInstant ?: Instant.DISTANT_PAST }
                    .thenByDescending { it.messageOrder }
            )
    }
    val attachmentEntries = remember(loadedMessageAttachmentEntries, conversationHistoryAttachments) {
        normalizeAttachmentEntries(conversationHistoryAttachments + loadedMessageAttachmentEntries)
    }
    val mediaAttachmentEntries = remember(attachmentEntries) {
        attachmentEntries.filter { it.isMedia }
    }
    val fileAttachmentEntries = remember(attachmentEntries) {
        attachmentEntries.filterNot { it.isMedia }
    }
    val uploadTasks by uploadManager.tasks.collectAsState()
    val conversationUploadTasks = remember(uploadTasks, conversationState.id) {
        uploadTasks.filter { it.conversationId == conversationState.id }
    }
    val isNearBottom by remember(listState, messages.size, conversationUploadTasks.size) {
        derivedStateOf {
            val totalItems = messages.size + conversationUploadTasks.size
            if (totalItems == 0) return@derivedStateOf true
            val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf true
            lastVisibleItemIndex >= totalItems - 3
        }
    }

    suspend fun ensureConversationForOutgoingMessage(currentToken: String): ConversationDto? {
        if (!isDraftConversation) {
            return conversationState
        }

        val targetUser = conversationState.members
            .firstOrNull { it.userId != currentUserId }
            ?.user
            ?: conversationState.members.firstOrNull()?.user

        val identifier = targetUser?.displayName
            ?.ifBlank { targetUser.email.ifBlank { null } }
            ?: targetUser?.email?.ifBlank { null }

        if (identifier.isNullOrBlank()) {
            errorMessage = "Не удалось определить собеседника для нового чата"
            return null
        }

        val createdConversation = apiService.createPersonalChat(currentToken, identifier).getOrElse { error ->
            errorMessage = error.message ?: "Не удалось создать чат"
            return null
        }

        conversationState = createdConversation
        onConversationUpdated(createdConversation)
        return createdConversation
    }

    fun uploadConversationAvatar(file: PickedFile) {
        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                return@launch
            }
            if (!file.contentType.startsWith("image/", ignoreCase = true)) {
                errorMessage = "Для аватара беседы можно выбрать только изображение"
                return@launch
            }
            if (conversationState.type == "personal" || isDraftConversation) {
                errorMessage = "Аватар можно задать только для групповой беседы или канала"
                return@launch
            }

            isConversationAvatarUploading = true
            errorMessage = null

            val initResponse = apiService.initConversationAvatarUpload(
                token = currentToken,
                conversationId = conversationState.id,
                fileName = file.name,
                contentType = file.contentType,
                size = file.bytes.size.toLong()
            ).getOrElse { error ->
                isConversationAvatarUploading = false
                errorMessage = error.message ?: "Не удалось начать загрузку аватара"
                return@launch
            }

            val uploadResult = apiService.uploadToPresignedUrl(
                uploadUrl = initResponse.uploadUrl,
                bytes = file.bytes,
                contentType = file.contentType
            )
            if (uploadResult.isFailure) {
                isConversationAvatarUploading = false
                errorMessage = uploadResult.exceptionOrNull()?.message ?: "Не удалось загрузить аватар"
                return@launch
            }

            apiService.completeConversationAvatarUpload(
                token = currentToken,
                conversationId = conversationState.id,
                photoId = initResponse.photoId
            ).fold(
                onSuccess = { updatedConversation ->
                    conversationState = updatedConversation
                    onConversationUpdated(updatedConversation)
                    isConversationAvatarUploading = false
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось сохранить аватар беседы"
                    isConversationAvatarUploading = false
                }
            )
        }
    }

    fun loadConversation() {
        scope.launch {
            if (isDraftConversation) return@launch
            val currentToken = token
            if (currentToken.isNullOrBlank()) return@launch
            val result = apiService.getConversation(currentToken, conversationState.id)
            result.fold(
                onSuccess = { updated ->
                    conversationState = updated
                    onConversationUpdated(updated)
                },
                onFailure = { }
            )
        }
    }

    fun loadUnreadCount() {
        scope.launch {
            if (isDraftConversation) {
                unreadCount = 0
                return@launch
            }
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

    fun findFirstUnreadMessageId(items: List<MessageDto>, totalUnreadCount: Int): String? {
        if (totalUnreadCount <= 0) return null
        var unreadSeen = 0
        for (message in items.asReversed()) {
            if (!currentUserId.isNullOrBlank() && message.senderId == currentUserId) {
                continue
            }
            unreadSeen += 1
            if (unreadSeen == totalUnreadCount) {
                return message.id.ifBlank { null }
            }
        }
        return null
    }

    fun findLatestIncomingMessageId(items: List<MessageDto>): String? {
        return items.lastOrNull { message ->
            message.id.isNotBlank() && (currentUserId.isNullOrBlank() || message.senderId != currentUserId)
        }?.id
    }

    suspend fun markConversationReadUpTo(messageId: String) {
        if (messageId.isBlank() || isDraftConversation) return
        val currentToken = token
        if (currentToken.isNullOrBlank()) return

        if (webSocketService.isConnected) {
            webSocketService.markAsRead(conversationState.id, messageId)
        } else {
            val result = apiService.markAsRead(currentToken, conversationState.id, messageId)
            if (result.isFailure) {
                pendingReadMessageId = messageId
                return
            }
        }

        pendingReadMessageId = null
        loadUnreadCount()
    }

    fun requestScrollToBottom() {
        pendingScrollToBottom = true
    }

    fun loadConversationAttachments(force: Boolean = false) {
        if (isDraftConversation) {
            conversationHistoryAttachments = emptyList()
            hasLoadedConversationAttachments = true
            areConversationAttachmentsLoading = false
            conversationAttachmentsError = null
            return
        }
        if (areConversationAttachmentsLoading) return
        if (hasLoadedConversationAttachments && !force) return

        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                return@launch
            }

            areConversationAttachmentsLoading = true
            conversationAttachmentsError = null

            val aggregatedEntries = mutableListOf<ConversationAttachmentEntry>()
            var cursor: String? = null
            var hasMore = true
            var requestIndex = 0
            var failed = false

            while (hasMore) {
                val result = apiService.getConversationAttachments(
                    token = currentToken,
                    conversationId = conversationState.id,
                    limit = 100,
                    cursor = cursor
                )

                result.fold(
                    onSuccess = { response ->
                        aggregatedEntries += response.attachments.mapIndexed { index, dto ->
                            dto.toConversationAttachmentEntry(
                                fallbackConversationId = conversationState.id,
                                order = requestIndex + index
                            )
                        }
                        conversationHistoryAttachments = normalizeAttachmentEntries(aggregatedEntries)
                        requestIndex += response.attachments.size
                        cursor = response.nextCursor
                        hasMore = response.hasMore && !cursor.isNullOrBlank()
                    },
                    onFailure = { error ->
                        conversationAttachmentsError = error.message ?: "Не удалось загрузить вложения"
                        hasMore = false
                        failed = true
                    }
                )
            }

            hasLoadedConversationAttachments = !failed
            areConversationAttachmentsLoading = false
        }
    }

    fun applyLoadedMessages(
        items: List<MessageDto>,
        unreadSnapshot: Int,
        hasMore: Boolean,
        nextCursor: String?,
        shouldMarkReadAfterPositioning: Boolean
    ) {
        val normalizedMessages = normalizeMessages(items)
        val firstUnreadMessageId = findFirstUnreadMessageId(normalizedMessages, unreadSnapshot)

        messages = normalizedMessages
        unreadCount = unreadSnapshot
        hasMoreHistory = hasMore
        nextHistoryCursor = nextCursor
        initialScrollMessageId = when {
            firstUnreadMessageId != null -> firstUnreadMessageId
            unreadSnapshot > 0 -> normalizedMessages.firstOrNull()?.id
            else -> normalizedMessages.lastOrNull()?.id
        }
        pendingInitialReadMessageId = if (shouldMarkReadAfterPositioning && unreadSnapshot > 0) {
            findLatestIncomingMessageId(normalizedMessages)
        } else {
            null
        }
        isAwaitingInitialPosition = normalizedMessages.isNotEmpty()
        hasLoadedChatSnapshot = true
    }

    fun loadOlderMessages() {
        val cursor = nextHistoryCursor ?: return
        if (isLoading || isLoadingHistory || isDraftConversation) return

        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                return@launch
            }

            val anchorIndex = listState.firstVisibleItemIndex
            val anchorOffset = listState.firstVisibleItemScrollOffset
            isLoadingHistory = true

            val result = apiService.getMessages(
                token = currentToken,
                conversationId = conversationState.id,
                limit = messagePageSize,
                cursor = cursor
            )
            result.fold(
                onSuccess = { response ->
                    val previousSize = messages.size
                    val updatedMessages = normalizeMessages(response.messages + messages)
                    val addedCount = updatedMessages.size - previousSize
                    messages = updatedMessages
                    hasMoreHistory = response.hasMore && !response.nextCursor.isNullOrBlank()
                    nextHistoryCursor = response.nextCursor
                    hasLoadedChatSnapshot = true
                    if (addedCount > 0) {
                        pendingHistoryRestore = (anchorIndex + addedCount) to anchorOffset
                    }
                },
                onFailure = { }
            )
            isLoadingHistory = false
        }
    }

    fun loadMessages(cachedEntry: ChatMessagesCacheEntry? = null) {
        scope.launch {
            if (isDraftConversation) {
                messages = emptyList()
                unreadCount = 0
                hasMoreHistory = false
                nextHistoryCursor = null
                initialScrollMessageId = null
                pendingInitialReadMessageId = null
                pendingHistoryRestore = null
                pendingScrollToBottom = false
                isLoadingHistory = false
                isAwaitingInitialPosition = false
                hasLoadedChatSnapshot = true
                isLoading = false
                return@launch
            }

            isLoading = true
            isLoadingHistory = false
            errorMessage = null
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                isLoading = false
                return@launch
            }

            val unreadResult = apiService.getUnreadCount(currentToken, conversationState.id)
            val unreadSnapshot = unreadResult.getOrNull()?.count ?: cachedEntry?.unreadCount ?: 0
            val canUseWarmCache = cachedEntry != null &&
                unreadResult.isSuccess &&
                cachedEntry.unreadCount == unreadSnapshot &&
                cachedEntry.isWarm(
                    nowEpochMillis = chatMessagesCacheNowMillis(),
                    maxAgeMillis = ChatMessagesWarmCacheDurationMs
                )

            if (canUseWarmCache) {
                applyLoadedMessages(
                    items = cachedEntry.messages,
                    unreadSnapshot = unreadSnapshot,
                    hasMore = cachedEntry.hasMoreHistory,
                    nextCursor = cachedEntry.nextHistoryCursor,
                    shouldMarkReadAfterPositioning = true
                )
                isLoading = false
                return@launch
            }

            val initialPage = apiService.getMessages(
                token = currentToken,
                conversationId = conversationState.id,
                limit = messagePageSize
            )

            initialPage.fold(
                onSuccess = { response ->
                    var loadedMessages = normalizeMessages(response.messages)
                    var cursor = response.nextCursor
                    var canLoadMore = response.hasMore && !cursor.isNullOrBlank()
                    var firstUnreadMessageId = findFirstUnreadMessageId(loadedMessages, unreadSnapshot)

                    while (unreadSnapshot > 0 && firstUnreadMessageId == null && canLoadMore) {
                        val olderResponse = apiService.getMessages(
                            token = currentToken,
                            conversationId = conversationState.id,
                            limit = messagePageSize,
                            cursor = cursor
                        ).getOrNull() ?: break

                        loadedMessages = normalizeMessages(olderResponse.messages + loadedMessages)
                        cursor = olderResponse.nextCursor
                        canLoadMore = olderResponse.hasMore && !cursor.isNullOrBlank()
                        firstUnreadMessageId = findFirstUnreadMessageId(loadedMessages, unreadSnapshot)
                    }

                    applyLoadedMessages(
                        items = loadedMessages,
                        unreadSnapshot = unreadSnapshot,
                        hasMore = canLoadMore,
                        nextCursor = cursor,
                        shouldMarkReadAfterPositioning = true
                    )
                    isLoading = false
                },
                onFailure = { error ->
                    if (cachedEntry != null) {
                        applyLoadedMessages(
                            items = cachedEntry.messages,
                            unreadSnapshot = unreadSnapshot,
                            hasMore = cachedEntry.hasMoreHistory,
                            nextCursor = cachedEntry.nextHistoryCursor,
                            shouldMarkReadAfterPositioning = true
                        )
                    } else {
                        errorMessage = error.message ?: "Не удалось загрузить сообщения"
                    }
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

            val resolvedConversation = ensureConversationForOutgoingMessage(currentToken) ?: return@launch
            val attachmentsToUpload = pendingAttachments
            if (attachmentsToUpload.isNotEmpty()) {
                uploadManager.enqueue(
                    token = currentToken,
                    conversationId = resolvedConversation.id,
                    content = content,
                    replyToMessageId = replyToMessageId,
                    senderId = currentUserId.orEmpty(),
                    senderDisplayName = currentUserDisplayName.orEmpty(),
                    senderEmail = currentUserEmail,
                    attachments = attachmentsToUpload.map(PendingAttachment::toOutgoingDraft)
                )
                newMessage = TextFieldValue("")
                replyToMessage = null
                pendingAttachments = emptyList()
                requestScrollToBottom()
                return@launch
            }

            val result = apiService.sendMessage(
                currentToken,
                resolvedConversation.id,
                content,
                replyToMessageId,
                emptyList()
            )
            result.fold(
                onSuccess = { message ->
                    messages = normalizeMessages(messages + message)
                    newMessage = TextFieldValue("")
                    replyToMessage = null
                    pendingAttachments = emptyList()
                    requestScrollToBottom()
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось отправить сообщение"
                }
            )
        }
    }

    fun updateMessage(message: MessageDto, content: String) {
        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                return@launch
            }

            val result = apiService.updateMessage(
                token = currentToken,
                conversationId = conversationState.id,
                messageId = message.id,
                content = content
            )
            result.fold(
                onSuccess = { updatedMessage ->
                    messages = normalizeMessages(messages.map { existing ->
                        if (existing.id == updatedMessage.id) updatedMessage else existing
                    })
                    editingMessage = null
                    newMessage = TextFieldValue("")
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось изменить сообщение"
                }
            )
        }
    }

    fun removeMessageWithAnimation(messageId: String) {
        if (messageId.isBlank()) return
        if (messageId in deletingMessageIds) return

        if (replyToMessage?.id == messageId) {
            replyToMessage = null
        }
        if (editingMessage?.id == messageId) {
            editingMessage = null
            newMessage = TextFieldValue("")
        }
        if (selectedMessageForActions?.id == messageId) {
            selectedMessageForActions = null
        }
        deletingMessageIds = deletingMessageIds + messageId

        scope.launch {
            delay(220)
            messages = normalizeMessages(messages.filterNot { it.id == messageId })
            conversationHistoryAttachments = conversationHistoryAttachments.filterNot { it.messageId == messageId }
            deletingMessageIds = deletingMessageIds - messageId
            loadConversation()
            loadUnreadCount()
        }
    }

    fun deleteMessageForMe(message: MessageDto) {
        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                return@launch
            }

            isMessageActionLoading = true
            val result = apiService.deleteMessageForMe(currentToken, conversationState.id, message.id)
            result.fold(
                onSuccess = {
                    removeMessageWithAnimation(message.id)
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось удалить сообщение"
                }
            )
            isMessageActionLoading = false
        }
    }

    fun deleteMessageForEveryone(message: MessageDto) {
        scope.launch {
            val currentToken = token
            if (currentToken.isNullOrBlank()) {
                errorMessage = "Нет авторизации"
                return@launch
            }

            isMessageActionLoading = true
            val result = apiService.deleteMessageForEveryone(currentToken, conversationState.id, message.id)
            result.fold(
                onSuccess = {
                    removeMessageWithAnimation(message.id)
                },
                onFailure = { error ->
                    errorMessage = error.message ?: "Не удалось удалить сообщение у всех"
                }
            )
            isMessageActionLoading = false
        }
    }

    fun scrollToMessage(messageId: String) {
        val index = messageIndexById[messageId] ?: return
        scope.launch {
            highlightedMessageId = messageId
            listState.animateScrollToItem(maxOf(index - 1, 0))
            delay(1500)
            if (highlightedMessageId == messageId) {
                highlightedMessageId = null
            }
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
                                requestScrollToBottom()
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

    LaunchedEffect(conversationState.id, token, chatCacheUserId, chatCacheSecret) {
        if (token.isNullOrBlank() && !isDraftConversation) return@LaunchedEffect
        messages = emptyList()
        unreadCount = 0
        hasMoreHistory = false
        nextHistoryCursor = null
        initialScrollMessageId = null
        pendingInitialReadMessageId = null
        pendingHistoryRestore = null
        pendingScrollToBottom = false
        pendingReadMessageId = null
        isAwaitingInitialPosition = false
        hasLoadedChatSnapshot = false
        conversationHistoryAttachments = emptyList()
        areConversationAttachmentsLoading = false
        hasLoadedConversationAttachments = false
        conversationAttachmentsError = null

        val cachedEntry = if (!isDraftConversation && chatCacheUserId != null && chatCacheSecret != null) {
            chatMessagesCache.read(
                conversationId = conversationState.id,
                userId = chatCacheUserId,
                secret = chatCacheSecret
            )
        } else {
            null
        }

        loadMessages(cachedEntry)
        loadConversation()
        readMessageIds = emptySet()
        typingUsers = emptyMap()
    }

    LaunchedEffect(showAttachmentsBrowser, conversationState.id, token) {
        if (showAttachmentsBrowser) {
            loadConversationAttachments()
        }
    }

    LaunchedEffect(conversationState.id, token) {
        if (isDraftConversation) return@LaunchedEffect
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

    fun enqueueUploads(files: List<PickedFile>) {
        if (files.isEmpty()) return
        var nextId = nextPendingAttachmentId
        val newAttachments = files.map { file ->
            nextId += 1
            PendingAttachment(
                id = "local-$nextId",
                fileName = file.name,
                contentType = file.contentType,
                size = file.bytes.size.toLong(),
                bytes = file.bytes,
                previewBytes = if (file.contentType.startsWith("image/")) file.bytes else null
            )
        }
        nextPendingAttachmentId = nextId
        pendingAttachments = pendingAttachments + newAttachments
    }

    fun removePendingAttachment(id: String) {
        pendingAttachments = pendingAttachments.filterNot { it.id == id }
    }

    fun insertEmoji(emoji: String) {
        newMessage = insertEmojiAtCursor(newMessage, emoji)
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
                val shouldAutoRead = isNearBottom || messages.isEmpty()
                messages = normalizeMessages(messages + msg)
                if (shouldAutoRead && msg.id.isNotBlank()) {
                    requestScrollToBottom()
                    markConversationReadUpTo(msg.id)
                } else {
                    loadUnreadCount()
                }
            }
        }
    }

    LaunchedEffect(conversationState.id) {
        webSocketService.messageUpdated.collect { event ->
            if (event.conversationId != conversationState.id) return@collect
            val updatedMessage = event.message
            if (updatedMessage.id in deletingMessageIds) return@collect
            messages = normalizeMessages(messages.map { existing ->
                if (existing.id == updatedMessage.id) updatedMessage else existing
            })
            if (replyToMessage?.id == updatedMessage.id) {
                replyToMessage = updatedMessage
            }
            if (editingMessage?.id == updatedMessage.id) {
                editingMessage = updatedMessage
            }
            loadConversation()
        }
    }

    LaunchedEffect(conversationState.id) {
        webSocketService.messageDeleted.collect { event ->
            if (event.conversationId != conversationState.id) return@collect
            removeMessageWithAnimation(event.messageId)
        }
    }

    LaunchedEffect(conversationState.id) {
        webSocketService.conversationDeleted.collect { event ->
            if (event.conversationId != conversationState.id) return@collect
            onBack()
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

    LaunchedEffect(conversationState.id, conversationUploadTasks) {
        var hasMergedSentMessage = false
        conversationUploadTasks
            .filter { it.stage == OutgoingMessageUploadStage.Sent }
            .forEach { task ->
                val sentMessage = task.sentMessage ?: return@forEach
                if (messages.none { it.id == sentMessage.id }) {
                    messages = normalizeMessages(messages + sentMessage)
                    hasMergedSentMessage = true
                }
                uploadManager.removeTask(task.id)
            }
        if (hasMergedSentMessage) {
            loadConversation()
            requestScrollToBottom()
        }
    }

    LaunchedEffect(
        conversationState.id,
        chatCacheUserId,
        chatCacheSecret,
        hasLoadedChatSnapshot,
        messages,
        unreadCount,
        hasMoreHistory,
        nextHistoryCursor
    ) {
        if (isDraftConversation || !hasLoadedChatSnapshot) return@LaunchedEffect
        val userId = chatCacheUserId ?: return@LaunchedEffect
        val secret = chatCacheSecret ?: return@LaunchedEffect
        chatMessagesCache.write(
            conversationId = conversationState.id,
            userId = userId,
            secret = secret,
            entry = buildChatMessagesCacheEntry(
                messages = messages,
                unreadCount = unreadCount,
                hasMoreHistory = hasMoreHistory,
                nextHistoryCursor = nextHistoryCursor
            )
        )
    }

    LaunchedEffect(messages.size, initialScrollMessageId) {
        if (!isAwaitingInitialPosition) return@LaunchedEffect
        if (messages.isEmpty()) {
            initialScrollMessageId = null
            pendingInitialReadMessageId = null
            isAwaitingInitialPosition = false
            return@LaunchedEffect
        }

        val targetIndex = initialScrollMessageId
            ?.let(messageIndexById::get)
            ?: messages.lastIndex
        listState.scrollToItem(targetIndex)
        initialScrollMessageId = null
        val initialReadTarget = pendingInitialReadMessageId
        pendingInitialReadMessageId = null
        isAwaitingInitialPosition = false
        initialReadTarget?.let { messageId ->
            markConversationReadUpTo(messageId)
        }
    }

    LaunchedEffect(messages.size, pendingHistoryRestore) {
        val restore = pendingHistoryRestore ?: return@LaunchedEffect
        if (messages.isEmpty()) {
            pendingHistoryRestore = null
            return@LaunchedEffect
        }
        listState.scrollToItem(
            index = restore.first.coerceAtMost(messages.lastIndex),
            scrollOffset = restore.second
        )
        pendingHistoryRestore = null
    }

    LaunchedEffect(messages.size, conversationUploadTasks.size, pendingScrollToBottom) {
        if (!pendingScrollToBottom) return@LaunchedEffect
        val totalItems = messages.size + conversationUploadTasks.size
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
        pendingScrollToBottom = false
    }

    LaunchedEffect(conversationState.id, hasMoreHistory, isLoadingHistory, isLoading, messages.size) {
        if (isDraftConversation || messages.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .collect { (isScrolling, firstVisibleIndex) ->
                if (
                    isScrolling &&
                    firstVisibleIndex <= historyPrefetchThreshold &&
                    hasMoreHistory &&
                    !isLoading &&
                    !isLoadingHistory
                ) {
                    loadOlderMessages()
                }
            }
    }

    LaunchedEffect(newMessage.text) {
        if (isDraftConversation) return@LaunchedEffect
        val currentText = newMessage.text
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
        if (newMessage.text == currentText && currentText.isNotBlank()) {
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
            if (isDraftConversation) {
                return@onDispose
            }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(chatBackground)
    ) {
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
            val personalUser = conversationState.members
                .firstOrNull { it.userId != currentUserId }
                ?.user
            val canOpenProfile = conversationState.type == "personal" && !personalUser?.id.isNullOrBlank()
            val canEditConversationAvatar = conversationState.type != "personal" && !isDraftConversation
            val headerTitle = when (conversationState.type) {
                "personal" -> {
                    personalUser?.displayName?.ifBlank { null }
                        ?: personalUser?.email?.ifBlank { null }
                        ?: "Чат"
                }
                else -> conversationState.name ?: "Чат"
            }

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
                } else {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .let { baseModifier ->
                                if (canEditConversationAvatar) {
                                    baseModifier.clickable { showConversationAvatarPicker = true }
                                } else {
                                    baseModifier
                                }
                            }
                    ) {
                        CachedConversationAvatar(
                            token = token,
                            conversationId = conversationState.id,
                            photoId = conversationState.avatarPhotoId,
                            title = headerTitle,
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(21.dp),
                            contentScale = ContentScale.Crop,
                            showLoadingIndicator = !isConversationAvatarUploading
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                }

                Column {
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        focusManager.clearFocus(force = true)
                        showEmojiPicker = false
                        showAttachmentsBrowser = true
                        selectedAttachmentsTab = if (
                            mediaAttachmentEntries.isNotEmpty() || fileAttachmentEntries.isEmpty()
                        ) {
                            ChatAttachmentsTab.Media
                        } else {
                            ChatAttachmentsTab.Files
                        }
                    }
                ) {
                    Icon(
                        Icons.Filled.Collections,
                        contentDescription = "Открыть вложения"
                    )
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
                            enabled = !isCreatingTransferInvite && !isDraftConversation
                        ) {
                            Icon(
                                Icons.Filled.OpenInNew,
                                contentDescription = "Пригласить в канал передачи"
                            )
                        }
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
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (isAwaitingInitialPosition) 0f else 1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = messages,
                            key = { message ->
                                message.id.ifBlank {
                                    "${message.senderId}:${message.sentAt}:${message.content.hashCode()}"
                                }
                            }
                        ) { message ->
                            androidx.compose.animation.AnimatedVisibility(
                                visible = message.id !in deletingMessageIds,
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                MessageBubble(
                                    message = message,
                                    repliedMessage = message.replyToMessageId?.let(messagesById::get),
                                    isHighlighted = highlightedMessageId == message.id,
                                    isOwn = message.senderId == authState.getUserId(),
                                    isRead = readMessageIds.contains(message.id),
                                    timeLabel = formatTime(message.sentAt),
                                    showSenderName = conversationState.type == "group",
                                    colors = bubbleColors,
                                    onReply = {
                                        if (editingMessage == null) {
                                            replyToMessage = it
                                        }
                                    },
                                    onOpenRepliedMessage = ::scrollToMessage,
                                    onOpenActions = { selectedMessageForActions = it },
                                    apiService = apiService,
                                    token = token,
                                    onOpenTransferChannelFromInvite = { inviteToken, transferChannelId ->
                                        openTransferChannelFromInvite(inviteToken, transferChannelId)
                                    }
                                )
                            }
                        }
                        items(
                            items = conversationUploadTasks,
                            key = { task -> task.id }
                        ) { task ->
                            PendingUploadBubble(
                                task = task,
                                repliedMessage = task.replyToMessageId?.let(messagesById::get),
                                timeLabel = formatTime(task.createdAt),
                                showSenderName = conversationState.type == "group",
                                colors = bubbleColors
                            )
                        }
                    }

                    if (isLoadingHistory) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                                .size(22.dp)
                        )
                    }

                    if (isAwaitingInitialPosition) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
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

        editingMessage?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ReplyPreviewCard(
                        title = "Редактирование",
                        preview = buildReplyPreviewText(message),
                        accentColor = chatAccent,
                        titleColor = MaterialTheme.colorScheme.onSurface,
                        previewColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        containerColor = Color.Transparent,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            editingMessage = null
                            newMessage = TextFieldValue("")
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Отменить редактирование",
                            modifier = Modifier.height(16.dp)
                        )
                    }
                }
            }
        }
        if (editingMessage == null) {
            replyToMessage?.let { reply ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ReplyPreviewCard(
                            title = resolveMessageAuthorLabel(reply),
                            preview = buildReplyPreviewText(reply),
                            accentColor = chatAccent,
                            titleColor = MaterialTheme.colorScheme.onSurface,
                            previewColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                            containerColor = Color.Transparent,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { replyToMessage = null },
                            modifier = Modifier.size(28.dp)
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
        }

        if (pendingAttachments.isNotEmpty()) {
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
                    pendingAttachments.forEach { attachment ->
                        val isImage = attachment.contentType.startsWith("image/")
                        val isVideo = attachment.contentType.startsWith("video/")
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
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.InsertDriveFile,
                                        contentDescription = null,
                                        tint = if (isVideo) chatAccent else Color(0xFF4566C7)
                                    )
                                }
                            }
                            val title = when {
                                isImage -> attachment.fileName.ifBlank { "Фото" }
                                isVideo -> attachment.fileName.ifBlank { "Видео" }
                                else -> attachment.fileName.ifBlank { "Файл" }
                            }
                            val meta = when {
                                isImage -> "Фото • ${formatFileSize(attachment.size)}"
                                isVideo -> "Видео • ${formatFileSize(attachment.size)}"
                                else -> "Файл • ${formatFileSize(attachment.size)}"
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = title,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = meta,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
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

        AnimatedVisibility(
            visible = showEmojiPicker,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            EmojiPickerPanel(
                selectedCategoryId = selectedEmojiCategoryId,
                onCategorySelected = { selectedEmojiCategoryId = it },
                onEmojiSelected = { emoji -> insertEmoji(emoji) }
            )
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
                    onClick = {
                        if (editingMessage != null) {
                            errorMessage = "Во время редактирования нельзя добавлять вложения"
                        } else {
                            showAttachmentPicker = true
                        }
                    },
                    enabled = editingMessage == null
                ) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "Прикрепить файл"
                    )
                }
                IconButton(
                    onClick = {
                        showEmojiPicker = !showEmojiPicker
                        if (showEmojiPicker) {
                            focusManager.clearFocus(force = true)
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.InsertEmoticon,
                        contentDescription = "Открыть эмоджи",
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp)
                    )
                }
                OutlinedTextField(
                    value = newMessage,
                    onValueChange = { value ->
                        newMessage = value
                    },
                    label = { Text("Сообщение") },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                showEmojiPicker = false
                            }
                        },
                    maxLines = 4,
                    colors = inputColors
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (editingMessage != null) {
                            updateMessage(editingMessage!!, newMessage.text)
                        } else if (newMessage.text.isNotBlank() || pendingAttachments.isNotEmpty()) {
                            sendMessage(newMessage.text, replyToMessage?.id)
                        }
                    },
                    enabled = (
                        if (editingMessage != null) {
                            newMessage.text.isNotBlank() || editingMessage!!.attachments.isNotEmpty()
                        } else {
                            newMessage.text.isNotBlank() || pendingAttachments.isNotEmpty()
                        }
                    ) && !isLoading,
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

        AnimatedVisibility(
            visible = showAttachmentsBrowser,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn() + slideInVertically(initialOffsetY = { fullHeight -> -fullHeight }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { fullHeight -> -fullHeight })
        ) {
            ChatAttachmentsBrowser(
                mediaAttachments = mediaAttachmentEntries,
                fileAttachments = fileAttachmentEntries,
                isLoading = areConversationAttachmentsLoading,
                errorMessage = conversationAttachmentsError,
                selectedTab = selectedAttachmentsTab,
                onSelectTab = { selectedAttachmentsTab = it },
                onClose = { showAttachmentsBrowser = false },
                apiService = apiService,
                token = token
            )
        }
    }

    selectedMessageForActions?.let { message ->
        val isOwnMessage = message.senderId == currentUserId
        val canEditMessage = isOwnMessage && message.kind == "text"
        val canDeleteForEveryone = isOwnMessage && conversationState.type == "personal"

        MessageActionsDialog(
            message = message,
            isOwnMessage = isOwnMessage,
            isLoading = isMessageActionLoading,
            canEdit = canEditMessage,
            canDeleteForEveryone = canDeleteForEveryone,
            onDismiss = {
                if (!isMessageActionLoading) {
                    selectedMessageForActions = null
                }
            },
            onEdit = if (canEditMessage) {
                {
                    selectedMessageForActions = null
                    editingMessage = message
                    replyToMessage = null
                    pendingAttachments = emptyList()
                    showEmojiPicker = false
                    newMessage = TextFieldValue(
                        text = message.content,
                        selection = TextRange(message.content.length)
                    )
                }
            } else {
                null
            },
            onDeleteForMe = { deleteMessageForMe(message) },
            onDeleteForEveryone = if (canDeleteForEveryone) {
                { deleteMessageForEveryone(message) }
            } else {
                null
            }
        )
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

    AttachmentPicker(
        show = showConversationAvatarPicker,
        onDismiss = { showConversationAvatarPicker = false },
        onPicked = { files ->
            showConversationAvatarPicker = false
            val avatarFile = files.firstOrNull { it.contentType.startsWith("image/", ignoreCase = true) }
            if (avatarFile == null) {
                errorMessage = "Для аватара беседы выберите изображение"
            } else {
                uploadConversationAvatar(avatarFile)
            }
        },
        onError = { error ->
            showConversationAvatarPicker = false
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
    val bytes: ByteArray,
    val previewBytes: ByteArray?
)

private fun PendingAttachment.toOutgoingDraft(): OutgoingAttachmentDraft {
    return OutgoingAttachmentDraft(
        id = id,
        fileName = fileName,
        contentType = contentType,
        size = size,
        bytes = bytes,
        previewBytes = previewBytes
    )
}

private data class EmojiCategory(
    val id: String,
    val icon: String,
    val emojis: List<String>
)

@Composable
private fun EmojiPickerPanel(
    selectedCategoryId: String,
    onCategorySelected: (String) -> Unit,
    onEmojiSelected: (String) -> Unit
) {
    val selectedCategory = EmojiCategories.firstOrNull { it.id == selectedCategoryId } ?: EmojiCategories.first()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EmojiCategories.forEach { category ->
                    val isSelected = category.id == selectedCategory.id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                            .clickable { onCategorySelected(category.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = category.icon,
                            fontSize = 20.sp
                        )
                    }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 236.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(selectedCategory.emojis) { emoji ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f))
                            .clickable { onEmojiSelected(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = emoji,
                            fontSize = 24.sp
                        )
                    }
                }
            }
        }
    }
}

private data class ChatBubbleColors(
    val ownBubble: Color,
    val otherBubble: Color,
    val ownText: Color,
    val otherText: Color,
    val ownMeta: Color,
    val otherMeta: Color,
    val bubbleBorder: Color,
    val replyAccent: Color
)

@Composable
private fun ReplyPreviewCard(
    title: String,
    preview: String,
    accentColor: Color,
    titleColor: Color,
    previewColor: Color,
    containerColor: Color,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = onClick != null) {
                onClick?.invoke()
            }
            .background(containerColor)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(accentColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                text = title,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = preview,
                fontSize = 9.sp,
                color = previewColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MessageMetaRow(
    timeLabel: String,
    isOwn: Boolean,
    isRead: Boolean,
    isEdited: Boolean,
    metaColor: Color,
    alignEnd: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isEdited) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Сообщение отредактировано",
                tint = metaColor,
                modifier = Modifier.size(11.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
        }
        Text(
            text = timeLabel,
            fontSize = 10.sp,
            color = metaColor
        )
        if (isOwn) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isRead) "✓✓" else "✓",
                fontSize = 10.sp,
                color = metaColor
            )
        }
    }
}

@Composable
private fun MessageActionOption(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = tint.copy(alpha = 0.08f)
        ),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.14f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    color = tint
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun MessageActionsDialog(
    message: MessageDto,
    isOwnMessage: Boolean,
    isLoading: Boolean,
    canEdit: Boolean,
    canDeleteForEveryone: Boolean,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)?,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: (() -> Unit)?
) {
    var showDeleteOptions by remember(message.id) { mutableStateOf(false) }
    var deleteForEveryone by remember(message.id) { mutableStateOf(false) }
    val previewText = remember(message.id, message.content, message.attachments) {
        buildReplyPreviewText(message)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (showDeleteOptions) {
                    "Удаление сообщения"
                } else if (isOwnMessage) {
                    "Моё сообщение"
                } else {
                    "Сообщение"
                }
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = resolveMessageAuthorLabel(message),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = previewText,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (!showDeleteOptions) {
                    if (canEdit && onEdit != null) {
                        MessageActionOption(
                            title = "Редактировать",
                            subtitle = "Изменить текст этого сообщения",
                            icon = Icons.Filled.Edit,
                            tint = MaterialTheme.colorScheme.primary,
                            enabled = !isLoading,
                            onClick = onEdit
                        )
                    }
                    MessageActionOption(
                        title = "Удалить",
                        subtitle = if (canDeleteForEveryone && onDeleteForEveryone != null) {
                            "Выберите, скрыть сообщение только у себя или удалить у всех"
                        } else {
                            "Сообщение исчезнет только из вашего чата"
                        },
                        icon = Icons.Filled.Delete,
                        tint = MaterialTheme.colorScheme.error,
                        enabled = !isLoading,
                        onClick = {
                            showDeleteOptions = true
                            deleteForEveryone = false
                        }
                    )
                } else {
                    Text(
                        text = "Сообщение можно скрыть только у себя, а для ваших сообщений в личном чате ещё и удалить у всех участников.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    )
                    if (canDeleteForEveryone && onDeleteForEveryone != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = !isLoading) {
                                    deleteForEveryone = !deleteForEveryone
                                }
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = deleteForEveryone,
                                onCheckedChange = if (isLoading) null else ({ checked ->
                                    deleteForEveryone = checked
                                })
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "Удалить у всех",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = "Сообщение исчезнет и у собеседника",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                                )
                            }
                        }
                    }
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            }
        },
        confirmButton = {
            if (showDeleteOptions) {
                Button(
                    onClick = {
                        if (deleteForEveryone && canDeleteForEveryone && onDeleteForEveryone != null) {
                            onDeleteForEveryone()
                        } else {
                            onDeleteForMe()
                        }
                    },
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Удалить")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (isLoading) return@TextButton
                    if (showDeleteOptions) {
                        showDeleteOptions = false
                        deleteForEveryone = false
                    } else {
                        onDismiss()
                    }
                },
                enabled = !isLoading
            ) {
                Text(if (showDeleteOptions) "Назад" else "Закрыть")
            }
        }
    )
}

private fun buildReplyPreviewText(message: MessageDto): String {
    val sanitized = sanitizeInviteMetaLines(message.content)
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .ifBlank {
            message.attachments.firstOrNull()?.let { buildAttachmentPreviewLabel(it, message.attachments.size) }
                ?: "Сообщение"
        }
    return sanitized.take(52)
}

private fun buildAttachmentPreviewLabel(
    attachment: MessageAttachmentDto,
    totalCount: Int = 1
): String {
    val baseLabel = when {
        attachment.contentType.startsWith("image/", ignoreCase = true) -> "Фото"
        attachment.contentType.startsWith("video/", ignoreCase = true) -> "Видео"
        attachment.contentType.startsWith("audio/", ignoreCase = true) -> "Аудио"
        else -> attachment.fileName.ifBlank { "Файл" }
    }
    return if (totalCount > 1) {
        "$baseLabel и ещё ${totalCount - 1}"
    } else {
        baseLabel
    }
}

private fun resolveMessageAuthorLabel(message: MessageDto): String {
    return message.sender.displayName
        .ifBlank { message.sender.email }
        .ifBlank { "Сообщение" }
}

private fun resolveOutgoingTaskAuthorLabel(task: OutgoingMessageUploadTask): String {
    return task.senderDisplayName
        .ifBlank { task.senderEmail.orEmpty() }
        .ifBlank { "Вы" }
}

private fun formatUploadStatusLabel(task: OutgoingMessageUploadTask): String {
    return when (task.stage) {
        OutgoingMessageUploadStage.Uploading -> "Загрузка ${((task.progress * 100f).roundToInt()).coerceIn(0, 100)}%"
        OutgoingMessageUploadStage.Sending -> "Отправка..."
        OutgoingMessageUploadStage.Failed -> "Не отправлено"
        OutgoingMessageUploadStage.Sent -> "Отправлено"
    }
}

private fun insertEmojiAtCursor(currentValue: TextFieldValue, emoji: String): TextFieldValue {
    val start = minOf(currentValue.selection.start, currentValue.selection.end)
    val end = maxOf(currentValue.selection.start, currentValue.selection.end)
    val updatedText = buildString {
        append(currentValue.text.substring(0, start))
        append(emoji)
        append(currentValue.text.substring(end))
    }
    val newCursor = start + emoji.length
    return TextFieldValue(
        text = updatedText,
        selection = TextRange(newCursor)
    )
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

private enum class ChatAttachmentsTab {
    Media,
    Files
}

private data class ConversationAttachmentEntry(
    val key: String,
    val conversationId: String,
    val messageId: String,
    val senderLabel: String,
    val sentAt: String,
    val sentAtInstant: Instant?,
    val messageOrder: Int,
    val attachment: MessageAttachmentDto
) {
    private val normalizedContentType: String
        get() = attachment.contentType.lowercase()

    val isImage: Boolean
        get() = normalizedContentType.startsWith("image/")

    val isVideo: Boolean
        get() = normalizedContentType.startsWith("video/")

    val isMedia: Boolean
        get() = isImage || isVideo
}

private fun ConversationAttachmentEntryDto.toConversationAttachmentEntry(
    fallbackConversationId: String,
    order: Int
): ConversationAttachmentEntry {
    val resolvedConversationId = conversationId.ifBlank { fallbackConversationId }
    val resolvedMessageId = messageId.ifBlank { "attachment-message-$order" }
    return ConversationAttachmentEntry(
        key = "$resolvedMessageId:${attachment.id}",
        conversationId = resolvedConversationId,
        messageId = resolvedMessageId,
        senderLabel = senderLabel.ifBlank { "Собеседник" },
        sentAt = sentAt,
        sentAtInstant = parseAttachmentInstantOrNull(sentAt),
        messageOrder = Int.MAX_VALUE - order,
        attachment = attachment
    )
}

private data class AttachmentDownloadState(
    val cachedPath: String,
    val cachedBytes: ByteArray?,
    val isCached: Boolean,
    val isDownloading: Boolean,
    val failed: Boolean
)

private fun parseAttachmentInstantOrNull(value: String): Instant? {
    if (value.isBlank()) return null
    return runCatching { Instant.parse(value) }.getOrNull()
}

private fun formatAttachmentSectionLabel(instant: Instant?, fallback: String): String {
    val localDateTime = instant?.toLocalDateTime(TimeZone.currentSystemDefault()) ?: return fallback
    val day = localDateTime.dayOfMonth.toString().padStart(2, '0')
    val month = localDateTime.monthNumber.toString().padStart(2, '0')
    val year = localDateTime.year.toString()
    return "$day.$month.$year"
}

private fun formatAttachmentMetaLabel(instant: Instant?, fallback: String): String {
    val localDateTime = instant?.toLocalDateTime(TimeZone.currentSystemDefault()) ?: return fallback
    val day = localDateTime.dayOfMonth.toString().padStart(2, '0')
    val month = localDateTime.monthNumber.toString().padStart(2, '0')
    val hour = localDateTime.hour.toString().padStart(2, '0')
    val minute = localDateTime.minute.toString().padStart(2, '0')
    return "$day.$month • $hour:$minute"
}

private const val TransferInviteTokenPrefix = "TRANSFER_CHANNEL_INVITE_TOKEN:"
private const val TransferChannelIdPrefix = "TRANSFER_CHANNEL_ID:"
private const val DraftConversationIdPrefix = "draft:"

private val EmojiCategories = listOf(
    EmojiCategory(
        id = "faces",
        icon = "😀",
        emojis = listOf(
            "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "🥰",
            "😎", "🤩", "🥳", "🤗", "🙂", "🙃", "😉", "😌",
            "😇", "🥺", "😏", "🤔", "🫠", "🤭", "🤫", "🤝",
            "😴", "🤤", "😤", "😭", "😡", "🥶", "🥵", "😱"
        )
    ),
    EmojiCategory(
        id = "gestures",
        icon = "👍",
        emojis = listOf(
            "👍", "👎", "👏", "🙌", "🫶", "🤝", "👋", "✌️",
            "🤞", "🤟", "👌", "🤌", "🙏", "💪", "🫡", "👀",
            "🫣", "🫠", "💯", "🔥", "❤️", "🩵", "💚", "💛",
            "💜", "🤍", "🖤", "💔", "✨", "🎉", "🎊", "💥"
        )
    ),
    EmojiCategory(
        id = "nature",
        icon = "🐻",
        emojis = listOf(
            "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼",
            "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🐔",
            "🐧", "🐦", "🦄", "🐝", "🦋", "🌸", "🌹", "🌻",
            "🌿", "🍀", "🌵", "🌙", "⭐", "☀️", "🌈", "❄️"
        )
    ),
    EmojiCategory(
        id = "food",
        icon = "🍕",
        emojis = listOf(
            "🍏", "🍎", "🍐", "🍊", "🍋", "🍉", "🍇", "🍓",
            "🫐", "🍒", "🥝", "🍍", "🥥", "🥑", "🍅", "🥕",
            "🌮", "🍕", "🍔", "🍟", "🍜", "🍣", "🍩", "🍪",
            "🍫", "🍿", "🧋", "☕", "🍵", "🥤", "🍷", "🍰"
        )
    ),
    EmojiCategory(
        id = "activity",
        icon = "⚽",
        emojis = listOf(
            "⚽", "🏀", "🏐", "🎾", "🏓", "🏸", "🥊", "🎯",
            "🎮", "🕹️", "🎲", "🧩", "🎸", "🎹", "🎤", "🎧",
            "🎬", "📷", "🎨", "🚴", "🏂", "🏋️", "🧘", "🎻",
            "🛹", "🏄", "🥇", "🏆", "🎪", "🎟️", "🎬", "🎼"
        )
    ),
    EmojiCategory(
        id = "travel",
        icon = "🚗",
        emojis = listOf(
            "🚗", "🚕", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒",
            "🚚", "🚲", "🛵", "🏍️", "✈️", "🛫", "🛬", "🚀",
            "🚁", "⛵", "🚤", "🛳️", "🏝️", "🏖️", "🏙️", "🌆",
            "🌃", "🏠", "🏡", "🏢", "🏰", "🗽", "🗼", "🧭"
        )
    )
)

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
private fun ChatAttachmentsBrowser(
    mediaAttachments: List<ConversationAttachmentEntry>,
    fileAttachments: List<ConversationAttachmentEntry>,
    isLoading: Boolean,
    errorMessage: String?,
    selectedTab: ChatAttachmentsTab,
    onSelectTab: (ChatAttachmentsTab) -> Unit,
    onClose: () -> Unit,
    apiService: ApiService,
    token: String?,
    modifier: Modifier = Modifier
) {
    val groupedMediaAttachments = remember(mediaAttachments) {
        mediaAttachments
            .groupBy { formatAttachmentSectionLabel(it.sentAtInstant, it.sentAt) }
            .toList()
    }
    val totalAttachments = mediaAttachments.size + fileAttachments.size

    Card(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Вложения",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (totalAttachments == 0) {
                            if (isLoading) {
                                "Загружаем вложения из всей истории чата"
                            } else {
                                "В этом диалоге пока нет вложений"
                            }
                        } else {
                            buildString {
                                append("Медиа: ${mediaAttachments.size} • Файлы: ${fileAttachments.size}")
                                if (isLoading) {
                                    append(" • обновляем историю")
                                }
                            }
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                    )
                    if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Закрыть вложения"
                    )
                }
            }

            TabRow(selectedTabIndex = selectedTab.ordinal) {
                Tab(
                    selected = selectedTab == ChatAttachmentsTab.Media,
                    onClick = { onSelectTab(ChatAttachmentsTab.Media) },
                    text = {
                        Text(
                            text = if (mediaAttachments.isEmpty()) "Медиа" else "Медиа (${mediaAttachments.size})"
                        )
                    }
                )
                Tab(
                    selected = selectedTab == ChatAttachmentsTab.Files,
                    onClick = { onSelectTab(ChatAttachmentsTab.Files) },
                    text = {
                        Text(
                            text = if (fileAttachments.isEmpty()) "Файлы" else "Файлы (${fileAttachments.size})"
                        )
                    }
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (selectedTab) {
                    ChatAttachmentsTab.Media -> {
                        if (mediaAttachments.isEmpty()) {
                            AttachmentBrowserEmptyState(
                                title = "Нет медиа",
                                subtitle = "Фото и видео из этого чата появятся здесь."
                            )
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 148.dp),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                groupedMediaAttachments.forEach { (sectionTitle, itemsForDate) ->
                                    items(
                                        count = 1,
                                        span = { GridItemSpan(maxLineSpan) },
                                        key = { "$sectionTitle-header" }
                                    ) {
                                        Text(
                                            text = sectionTitle,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
                                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                                        )
                                    }
                                    items(
                                        items = itemsForDate,
                                        key = { it.key }
                                    ) { entry ->
                                        ConversationMediaAttachmentTile(
                                            entry = entry,
                                            apiService = apiService,
                                            token = token
                                        )
                                    }
                                }
                            }
                        }
                    }

                    ChatAttachmentsTab.Files -> {
                        if (fileAttachments.isEmpty()) {
                            AttachmentBrowserEmptyState(
                                title = "Нет файлов",
                                subtitle = "Документы и другие файлы из чата появятся в этой вкладке."
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(
                                    items = fileAttachments,
                                    key = { it.key }
                                ) { entry ->
                                    ConversationFileAttachmentRow(
                                        entry = entry,
                                        apiService = apiService,
                                        token = token
                                    )
                                }
                            }
                        }
                    }
                }

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .size(22.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentBrowserEmptyState(
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
            ),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                )
            }
        }
    }
}

@Composable
private fun ConversationMediaAttachmentTile(
    entry: ConversationAttachmentEntry,
    apiService: ApiService,
    token: String?
) {
    val downloadState = rememberAttachmentDownloadState(
        attachment = entry.attachment,
        conversationId = entry.conversationId,
        messageId = entry.messageId,
        apiService = apiService,
        token = token
    )
    val bitmap = if (entry.isImage) {
        downloadState.cachedBytes?.let(::decodeImage)
    } else {
        null
    }
    val canOpen = (entry.isVideo && downloadState.isCached) || bitmap != null
    var showFull by remember(entry.key) { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8EEF7))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                entry.isVideo && downloadState.isCached -> {
                    InlineVideo(
                        path = downloadState.cachedPath,
                        muted = true,
                        modifier = Modifier.fillMaxSize(),
                        onClick = { showFull = true }
                    )
                }

                bitmap != null -> {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { showFull = true },
                        contentScale = ContentScale.Crop
                    )
                }

                downloadState.failed -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (entry.isVideo) "Видео недоступно" else "Фото недоступно",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        )
                    }
                }

                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = if (entry.isVideo) "Загрузка видео" else "Загрузка фото",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (entry.isVideo) "Видео" else "Фото",
                    fontSize = 11.sp,
                    color = Color.White
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = entry.attachment.fileName.ifBlank { if (entry.isVideo) "Видео" else "Фото" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        color = Color.White
                    )
                    Text(
                        text = formatAttachmentMetaLabel(entry.sentAtInstant, entry.sentAt),
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }
            }
        }
    }

    if (showFull && canOpen) {
        if (entry.isVideo) {
            FullScreenVideo(
                path = downloadState.cachedPath,
                onDismiss = { showFull = false }
            )
        } else if (bitmap != null) {
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
    }
}

@Composable
private fun ConversationFileAttachmentRow(
    entry: ConversationAttachmentEntry,
    apiService: ApiService,
    token: String?
) {
    val downloadState = rememberAttachmentDownloadState(
        attachment = entry.attachment,
        conversationId = entry.conversationId,
        messageId = entry.messageId,
        apiService = apiService,
        token = token
    )
    val openFile = rememberFileOpener()
    var openFailed by remember(entry.key) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = downloadState.isCached) {
                    openFailed = !openFile(downloadState.cachedPath, entry.attachment.contentType)
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFEFF4FB)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = null,
                    tint = Color(0xFF4566C7)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = entry.attachment.fileName.ifBlank { "Файл" },
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatFileSize(entry.attachment.size)} • ${entry.senderLabel}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                )
                Text(
                    text = formatAttachmentMetaLabel(entry.sentAtInstant, entry.sentAt),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                )
                if (downloadState.failed) {
                    Text(
                        text = "Не удалось загрузить файл",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (openFailed) {
                    Text(
                        text = "Не удалось открыть файл",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            when {
                downloadState.isDownloading && !downloadState.isCached -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                }

                else -> {
                    IconButton(
                        onClick = {
                            openFailed = if (downloadState.isCached) {
                                !openFile(downloadState.cachedPath, entry.attachment.contentType)
                            } else {
                                true
                            }
                        },
                        enabled = downloadState.isCached
                    ) {
                        Icon(
                            Icons.Filled.OpenInNew,
                            contentDescription = "Открыть файл"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberAttachmentDownloadState(
    attachment: MessageAttachmentDto,
    conversationId: String,
    messageId: String,
    apiService: ApiService,
    token: String?
): AttachmentDownloadState {
    val normalizedType = attachment.contentType.lowercase()
    val isImage = normalizedType.startsWith("image/")
    val cache = rememberMediaCache()
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

    return AttachmentDownloadState(
        cachedPath = cachedPath,
        cachedBytes = cachedBytes,
        isCached = isCached,
        isDownloading = isDownloading,
        failed = failed
    )
}

@Composable
private fun PendingUploadBubble(
    task: OutgoingMessageUploadTask,
    repliedMessage: MessageDto?,
    timeLabel: String,
    showSenderName: Boolean,
    colors: ChatBubbleColors
) {
    val replyTitle = repliedMessage?.let(::resolveMessageAuthorLabel) ?: "Ответ на сообщение"
    val replyPreview = when {
        repliedMessage != null -> buildReplyPreviewText(repliedMessage)
        task.replyToMessageId != null -> "Исходное сообщение недоступно"
        else -> null
    }
    val progressValue = when (task.stage) {
        OutgoingMessageUploadStage.Uploading -> task.progress
        OutgoingMessageUploadStage.Sending -> 1f
        OutgoingMessageUploadStage.Failed -> task.progress
        OutgoingMessageUploadStage.Sent -> 1f
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.78f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Card(
                modifier = Modifier.widthIn(max = maxBubbleWidth),
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp),
                colors = CardDefaults.cardColors(containerColor = colors.ownBubble)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (showSenderName) {
                        Text(
                            text = resolveOutgoingTaskAuthorLabel(task),
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            color = colors.ownText
                        )
                    }

                    if (replyPreview != null) {
                        ReplyPreviewCard(
                            title = replyTitle,
                            preview = replyPreview,
                            accentColor = Color.White,
                            titleColor = Color.White,
                            previewColor = Color.White.copy(alpha = 0.84f),
                            containerColor = Color.White.copy(alpha = 0.08f)
                        )
                    }

                    if (task.content.isNotBlank()) {
                        Text(
                            text = task.content,
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            color = colors.ownText
                        )
                    }

                    if (task.attachments.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            task.attachments.forEach { attachment ->
                                PendingUploadAttachmentPreview(
                                    attachment = attachment,
                                    contentColor = colors.ownText,
                                    secondaryColor = colors.ownMeta
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (task.stage != OutgoingMessageUploadStage.Failed) {
                                CircularProgressIndicator(
                                    progress = progressValue.coerceIn(0f, 1f),
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            }
                            Text(
                                text = formatUploadStatusLabel(task),
                                fontSize = 12.sp,
                                color = colors.ownMeta,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = timeLabel,
                            fontSize = 11.sp,
                            color = colors.ownMeta
                        )
                    }

                    if (!task.errorMessage.isNullOrBlank() && task.stage == OutgoingMessageUploadStage.Failed) {
                        Text(
                            text = task.errorMessage,
                            fontSize = 12.sp,
                            color = colors.ownText.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingUploadAttachmentPreview(
    attachment: OutgoingAttachmentDraft,
    contentColor: Color,
    secondaryColor: Color
) {
    val isImage = attachment.contentType.startsWith("image/", ignoreCase = true)
    val isVideo = attachment.contentType.startsWith("video/", ignoreCase = true)
    val previewBitmap = if (isImage && attachment.previewBytes != null) {
        remember(attachment.id, attachment.previewBytes) {
            decodeImage(attachment.previewBytes)
        }
    } else {
        null
    }
    val title = when {
        isImage -> attachment.fileName.ifBlank { "Фото" }
        isVideo -> attachment.fileName.ifBlank { "Видео" }
        else -> attachment.fileName.ifBlank { "Файл" }
    }
    val meta = when {
        isImage -> "Фото • ${formatFileSize(attachment.size)}"
        isVideo -> "Видео • ${formatFileSize(attachment.size)}"
        else -> "Файл • ${formatFileSize(attachment.size)}"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (previewBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = previewBitmap,
                contentDescription = null,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = contentColor
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = meta,
                fontSize = 12.sp,
                color = secondaryColor
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: MessageDto,
    repliedMessage: MessageDto?,
    isHighlighted: Boolean,
    isOwn: Boolean,
    isRead: Boolean,
    timeLabel: String,
    showSenderName: Boolean,
    colors: ChatBubbleColors,
    onReply: (MessageDto) -> Unit,
    onOpenRepliedMessage: (String) -> Unit,
    onOpenActions: (MessageDto) -> Unit,
    apiService: ApiService,
    token: String?,
    onOpenTransferChannelFromInvite: ((String, String?) -> Unit)?
) {
    var hasTriggered by remember(message.id) { mutableStateOf(false) }
    var isDragging by remember(message.id) { mutableStateOf(false) }
    var dragOffsetPx by remember(message.id) { mutableStateOf(0f) }
    val messageText = remember(message.content) { sanitizeInviteMetaLines(message.content) }
    val inviteToken = remember(message.content) { extractTransferChannelInviteToken(message.content) }
    val transferChannelId = remember(message.content) { extractTransferChannelId(message.content) }
    val replySwipeTriggerOffsetPx = with(LocalDensity.current) { 72.dp.toPx() }
    val animatedDragOffsetPx by animateFloatAsState(
        targetValue = dragOffsetPx,
        animationSpec = if (isDragging) {
            tween(durationMillis = 0)
        } else {
            spring(stiffness = Spring.StiffnessMediumLow)
        },
        label = "messageReplySwipeOffset"
    )
    val canOpenTransferInvite = (inviteToken != null || transferChannelId != null) &&
        onOpenTransferChannelFromInvite != null
    val replyTargetId = message.replyToMessageId?.ifBlank { null }
    val replyTitle = repliedMessage?.let(::resolveMessageAuthorLabel) ?: "Ответ на сообщение"
    val replyPreview = when {
        repliedMessage != null -> buildReplyPreviewText(repliedMessage)
        message.replyToMessageId != null -> "Исходное сообщение недоступно"
        else -> null
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.78f
        val inlineMetaReserve = when {
            isOwn && message.editedAt != null -> 64.dp
            isOwn -> 52.dp
            message.editedAt != null -> 50.dp
            else -> 38.dp
        }
        val senderColor = if (isOwn) colors.ownText else colors.otherText
        val metaColor = if (isOwn) colors.ownMeta else colors.otherMeta
        val replyAccentColor = if (isOwn) Color.White else colors.replyAccent
        val replyTitleColor = if (isOwn) Color.White else colors.replyAccent
        val replyPreviewColor = if (isOwn) {
            Color.White.copy(alpha = 0.84f)
        } else {
            colors.otherText.copy(alpha = 0.72f)
        }
        val replyContainerColor = if (isOwn) {
            Color.White.copy(alpha = 0.08f)
        } else {
            Color(0xFFF7FAFD)
        }
        val showInlineMeta = message.attachments.isEmpty() && !canOpenTransferInvite && messageText.isNotBlank()
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
                    .offset { IntOffset(animatedDragOffsetPx.roundToInt(), 0) }
                    .pointerInput(message.id) {
                        detectTapGestures(
                            onLongPress = { onOpenActions(message) }
                        )
                    }
                    .pointerInput(message.id) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                hasTriggered = false
                                isDragging = true
                            },
                            onDragEnd = {
                                hasTriggered = false
                                isDragging = false
                                dragOffsetPx = 0f
                            },
                            onDragCancel = {
                                hasTriggered = false
                                isDragging = false
                                dragOffsetPx = 0f
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                if (hasTriggered) return@detectHorizontalDragGestures

                                val nextOffset = (dragOffsetPx + dragAmount)
                                    .coerceIn(-replySwipeTriggerOffsetPx, 0f)

                                dragOffsetPx = nextOffset
                                if (nextOffset <= -replySwipeTriggerOffsetPx) {
                                    hasTriggered = true
                                    isDragging = false
                                    onReply(message)
                                    dragOffsetPx = 0f
                                }
                            }
                        )
                    },
                shape = bubbleShape,
                colors = CardDefaults.cardColors(
                    containerColor = if (isOwn) colors.ownBubble else colors.otherBubble
                ),
                border = when {
                    isHighlighted -> BorderStroke(1.5.dp, colors.replyAccent.copy(alpha = 0.6f))
                    isOwn -> null
                    else -> BorderStroke(1.dp, colors.bubbleBorder)
                },
                elevation = CardDefaults.cardElevation(defaultElevation = if (isHighlighted) 3.dp else 1.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (showInlineMeta) {
                        Box {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                if (showSenderName) {
                                    Text(
                                        text = resolveMessageAuthorLabel(message),
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 11.sp,
                                        color = senderColor
                                    )
                                }

                                if (replyPreview != null) {
                                    ReplyPreviewCard(
                                        title = replyTitle,
                                        preview = replyPreview,
                                        accentColor = replyAccentColor,
                                        titleColor = replyTitleColor,
                                        previewColor = replyPreviewColor,
                                        containerColor = replyContainerColor,
                                        onClick = replyTargetId?.let { targetId ->
                                            { onOpenRepliedMessage(targetId) }
                                        }
                                    )
                                }

                                Text(
                                    text = messageText,
                                    fontSize = 14.sp,
                                    lineHeight = 18.sp,
                                    color = if (isOwn) colors.ownText else colors.otherText,
                                    modifier = Modifier.padding(end = inlineMetaReserve, bottom = 1.dp)
                                )
                            }
                            MessageMetaRow(
                                timeLabel = timeLabel,
                                isOwn = isOwn,
                                isRead = isRead,
                                isEdited = message.editedAt != null,
                                metaColor = metaColor,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(bottom = 1.dp)
                            )
                        }
                    } else {
                        if (showSenderName) {
                            Text(
                                text = resolveMessageAuthorLabel(message),
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.sp,
                                color = senderColor
                            )
                        }

                        if (replyPreview != null) {
                            ReplyPreviewCard(
                                title = replyTitle,
                                preview = replyPreview,
                                accentColor = replyAccentColor,
                                titleColor = replyTitleColor,
                                previewColor = replyPreviewColor,
                                containerColor = replyContainerColor,
                                onClick = replyTargetId?.let { targetId ->
                                    { onOpenRepliedMessage(targetId) }
                                }
                            )
                        }

                        if (messageText.isNotBlank()) {
                            Text(
                                text = messageText,
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                                color = if (isOwn) colors.ownText else colors.otherText
                            )
                        }
                        if (canOpenTransferInvite) {
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
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            message.attachments.forEach { attachment ->
                                AttachmentPreview(
                                    attachment = attachment,
                                    conversationId = message.conversationId,
                                    messageId = message.id,
                                    apiService = apiService,
                                    token = token,
                                    contentColor = if (isOwn) colors.ownText else colors.otherText,
                                    secondaryColor = if (isOwn) colors.ownMeta else colors.otherMeta,
                                    fileIconTint = if (isOwn) colors.ownText else Color(0xFF4566C7),
                                    fileIconContainerColor = if (isOwn) {
                                        Color.White.copy(alpha = 0.14f)
                                    } else {
                                        Color(0xFFEFF4FB)
                                    }
                                )
                            }
                        }
                    }

                    if (message.attachments.isNotEmpty() || canOpenTransferInvite || messageText.isBlank()) {
                        MessageMetaRow(
                            timeLabel = timeLabel,
                            isOwn = isOwn,
                            isRead = isRead,
                            isEdited = message.editedAt != null,
                            metaColor = metaColor,
                            alignEnd = true,
                            modifier = Modifier.fillMaxWidth()
                        )
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
    token: String?,
    contentColor: Color,
    secondaryColor: Color,
    fileIconTint: Color,
    fileIconContainerColor: Color
) {
    val normalizedType = attachment.contentType.lowercase()
    val isImage = normalizedType.startsWith("image/")
    val isVideo = normalizedType.startsWith("video/")
    val openFile = rememberFileOpener()
    val downloadState = rememberAttachmentDownloadState(
        attachment = attachment,
        conversationId = conversationId,
        messageId = messageId,
        apiService = apiService,
        token = token
    )
    var showFull by remember(attachment.id, messageId) { mutableStateOf(false) }
    var openFailed by remember(attachment.id, messageId) { mutableStateOf(false) }

    if (isVideo) {
        when {
            downloadState.isCached -> InlineVideo(
                path = downloadState.cachedPath,
                muted = true,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                onClick = { showFull = true }
            )
            downloadState.failed -> Text(
                text = "Не удалось загрузить видео",
                fontSize = 12.sp,
                color = secondaryColor
            )
            else -> Text(
                text = "Загрузка видео...",
                fontSize = 12.sp,
                color = secondaryColor
            )
        }
        if (showFull && downloadState.isCached) {
            FullScreenVideo(
                path = downloadState.cachedPath,
                onDismiss = { showFull = false }
            )
        }
    } else if (isImage) {
        val bitmap = downloadState.cachedBytes?.let { decodeImage(it) }
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
            downloadState.failed -> Text(
                text = "Не удалось загрузить фото",
                fontSize = 12.sp,
                color = secondaryColor
            )
            else -> Text(
                text = "Загрузка фото...",
                fontSize = 12.sp,
                color = secondaryColor
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
        val canOpenFile = downloadState.isCached
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = canOpenFile) {
                    openFailed = !openFile(downloadState.cachedPath, attachment.contentType)
                }
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(fileIconContainerColor),
                contentAlignment = Alignment.Center
            ) {
                if (downloadState.isDownloading && !downloadState.isCached) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = contentColor,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.InsertDriveFile,
                        contentDescription = "Открыть файл",
                        tint = fileIconTint
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = attachment.fileName.ifBlank { "Файл" },
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatFileSize(attachment.size),
                    fontSize = 12.sp,
                    color = secondaryColor
                )
                if (downloadState.failed) {
                    Text(
                        text = "Не удалось загрузить файл",
                        fontSize = 12.sp,
                        color = secondaryColor
                    )
                } else if (openFailed) {
                    Text(
                        text = "Не удалось открыть файл",
                        fontSize = 12.sp,
                        color = secondaryColor
                    )
                }
            }

        }
    }
}

