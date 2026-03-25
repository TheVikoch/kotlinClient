package com.messenger.client.transfer

import com.messenger.client.media.StreamPickedFile
import com.messenger.client.media.StreamTransferStorage
import com.messenger.client.media.base64Decode
import com.messenger.client.media.base64Encode
import com.messenger.client.media.crc32Hex
import com.messenger.client.media.sha256Base64ForFile
import com.messenger.client.models.StreamTransferChunkDto
import com.messenger.client.models.StreamTransferEvent
import com.messenger.client.models.StreamTransferInitRequestDto
import com.messenger.client.models.StreamTransferOfferDto
import com.messenger.client.models.StreamTransferStartResponseDto
import com.messenger.client.services.MessengerWebSocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min

class StreamTransferController(
    private val webSocketService: MessengerWebSocketService,
    private val streamStorage: StreamTransferStorage,
    private val streamChunkSize: Int = 2 * 1024 * 1024,
    private val streamWindowSize: Int = 64
) {
    companion object {
        private const val DEFERRED_FILE_HASH = "DEFERRED"
        private const val CHUNK_HASH_NONE = "NONE"
        private const val CHUNK_HASH_CRC32 = "CRC32"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ackBatchSize = 16
    private val ackFlushIntervalMs = 150L
    private val progressUpdateIntervalMs = 200L
    private val progressUpdateChunkStep = 8

    private val _state = MutableStateFlow<StreamTransferUiState?>(null)
    val state: StateFlow<StreamTransferUiState?> = _state.asStateFlow()

    private val _error = MutableStateFlow<StreamTransferUiError?>(null)
    val error: StateFlow<StreamTransferUiError?> = _error.asStateFlow()

    private val _offer = MutableStateFlow<StreamTransferOfferDto?>(null)
    val offer: StateFlow<StreamTransferOfferDto?> = _offer.asStateFlow()

    private var senderContext: StreamSenderContext? = null
    private var receiverContext: StreamReceiverContext? = null
    private var sendJob: Job? = null
    private var phaseTimeoutJob: Job? = null

    init {
        scope.launch {
            webSocketService.streamEvents.collect { event ->
                handleStreamEvent(event)
            }
        }
        startAckWatchdog()
        startReceiverAckFlushLoop()
        startReceiverResumeLoop()
        startTelemetryLoop()
    }

    fun startTransfer(file: StreamPickedFile, streamChatId: String) {
        val phase = _state.value?.phase
        if (isActiveStreamPhase(phase)) {
            emitError(streamChatId, "Передача уже активна")
            file.source.close()
            return
        }
        if (!webSocketService.isConnected) {
            emitError(streamChatId, "Нет подключения к серверу")
            file.source.close()
            return
        }
        clearError()
        scope.launch {
            var fileSize = file.size
            val actualSize = withContext(Dispatchers.IO) { file.source.getSize() }
            if (actualSize > 0) {
                fileSize = actualSize
            }
            if (fileSize <= 0) {
                emitError(streamChatId, "Файл пустой или недоступен")
                file.source.close()
                return@launch
            }
            val totalChunks = ((fileSize + streamChunkSize - 1) / streamChunkSize).toInt()
            val request = StreamTransferInitRequestDto(
                streamChatId = streamChatId,
                fileName = file.name,
                fileSize = fileSize,
                fileHash = DEFERRED_FILE_HASH,
                fileHashAlgorithm = "NONE",
                chunkHashAlgorithm = CHUNK_HASH_NONE,
                chunkSize = streamChunkSize,
                totalChunks = totalChunks,
                contentType = file.contentType
            )
            val response: StreamTransferStartResponseDto? = withContext(Dispatchers.IO) {
                webSocketService.startStreamTransfer(request)
            }
            if (response == null || response.transferId.isBlank()) {
                emitError(streamChatId, "Не удалось начать передачу")
                file.source.close()
                return@launch
            }
            senderContext = StreamSenderContext(
                transferId = response.transferId,
                streamChatId = streamChatId,
                file = file,
                fileSize = fileSize,
                chunkSize = streamChunkSize,
                totalChunks = totalChunks,
                windowSize = streamWindowSize,
                chunkHashAlgorithm = request.chunkHashAlgorithm
            )
            println("[STREAM][TX] start id=${response.transferId} chunks=$totalChunks chunk=$streamChunkSize window=$streamWindowSize")
            updateState(
                StreamTransferUiState(
                    transferId = response.transferId,
                    streamChatId = streamChatId,
                    fileName = file.name,
                    fileSize = fileSize,
                    totalChunks = totalChunks,
                    transferredChunks = 0,
                    isSender = true,
                    phase = StreamTransferPhase.AwaitingAcceptance
                )
            )
        }
    }

    suspend fun acceptOffer(offer: StreamTransferOfferDto) {
        clearError()
        val available = streamStorage.availableBytes()
        if (available < offer.fileSize) {
            emitError(offer.streamChatId, "Недостаточно места для файла")
            webSocketService.rejectStreamTransfer(offer.transferId, "not_enough_space")
            _offer.value = null
            return
        }
        val target = try {
            withContext(Dispatchers.Main) { streamStorage.pickSaveTarget(offer.fileName, offer.contentType) }
        } catch (e: Exception) {
            emitError(offer.streamChatId, "Не удалось открыть выбор папки")
            webSocketService.rejectStreamTransfer(offer.transferId, "picker_failed")
            _offer.value = null
            return
        }
        if (target == null) {
            webSocketService.rejectStreamTransfer(offer.transferId, "canceled")
            _offer.value = null
            return
        }
        val tempPath = streamStorage.createTempFile(offer.transferId, offer.fileName)
        receiverContext = StreamReceiverContext(
            transferId = offer.transferId,
            offer = offer,
            tempPath = tempPath,
            saveTarget = target,
            lastChunkAt = System.currentTimeMillis()
        )
        println("[STREAM][RX] start id=${offer.transferId} chunks=${offer.totalChunks} chunk=${offer.chunkSize}")
        updateState(
            StreamTransferUiState(
                transferId = offer.transferId,
                streamChatId = offer.streamChatId,
                fileName = offer.fileName,
                fileSize = offer.fileSize,
                totalChunks = offer.totalChunks,
                transferredChunks = 0,
                isSender = false,
                phase = StreamTransferPhase.Transferring
            )
        )
        webSocketService.acceptStreamTransfer(offer.transferId)
        _offer.value = null
    }

    fun rejectOffer(offer: StreamTransferOfferDto, reason: String = "rejected") {
        if (offer.transferId.isNotBlank()) {
            webSocketService.rejectStreamTransfer(offer.transferId, reason)
        }
        _offer.value = null
    }

    fun cancelActiveTransfer(reason: String = "user_canceled") {
        val transferId = _state.value?.transferId
            ?: senderContext?.transferId
            ?: receiverContext?.transferId
            ?: return
        val phase = _state.value?.phase
        if (phase == StreamTransferPhase.Completed ||
            phase == StreamTransferPhase.Failed ||
            phase == StreamTransferPhase.Canceled
        ) {
            return
        }
        closeSenderContext()
        receiverContext?.let { streamStorage.deleteTempFile(it.tempPath) }
        receiverContext = null
        updateState(_state.value?.copy(phase = StreamTransferPhase.Canceled, message = "Отменено пользователем"))
        if (webSocketService.isConnected) {
            webSocketService.cancelStreamTransfer(transferId, reason)
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun resetForLogout() {
        cancelActiveTransfer("logout")
        _offer.value = null
        _state.value = null
        _error.value = null
    }

    private fun updateState(value: StreamTransferUiState?) {
        _state.value = value
        phaseTimeoutJob?.cancel()
        val state = value ?: return
        if (state.phase == StreamTransferPhase.AwaitingAcceptance ||
            state.phase == StreamTransferPhase.WaitingComplete
        ) {
            phaseTimeoutJob = scope.launch {
                delay(30_000)
                val current = _state.value ?: return@launch
                if (current.transferId != state.transferId || current.phase != state.phase) return@launch
                if (current.phase == StreamTransferPhase.AwaitingAcceptance) {
                    emitError(current.streamChatId, "Нет подтверждения от получателя")
                } else if (current.phase == StreamTransferPhase.WaitingComplete) {
                    emitError(current.streamChatId, "Получатель не завершил передачу")
                }
            }
        }
    }

    private fun emitError(streamChatId: String, message: String) {
        _error.value = StreamTransferUiError(streamChatId, message)
    }

    private fun closeSenderContext() {
        val ctx = senderContext
        senderContext = null
        sendJob?.cancel()
        sendJob = null
        if (ctx != null) {
            scope.launch(Dispatchers.IO) {
                ctx.readMutex.withLock {
                    ctx.file.source.close()
                }
            }
        }
    }

    private suspend fun sendStreamChunk(context: StreamSenderContext, seq: Int) {
        if (context.fileSize <= 0) {
            throw IllegalStateException("Размер файла недоступен для отправки")
        }
        val offset = seq.toLong() * context.chunkSize.toLong()
        if (offset >= context.fileSize) return
        val remaining = context.fileSize - offset
        val readSize = min(context.chunkSize.toLong(), remaining).toInt()
        val chunkBytes = context.readMutex.withLock {
            context.file.source.readChunk(offset, readSize)
        }
        if (chunkBytes.isEmpty()) {
            throw IllegalStateException("Не удалось прочитать чанк")
        }
        val chunkHash = if (context.chunkHashAlgorithm.equals(CHUNK_HASH_CRC32, ignoreCase = true)) {
            crc32Hex(chunkBytes)
        } else {
            ""
        }
        val chunkBase64 = base64Encode(chunkBytes)
        val isLast = seq == context.totalChunks - 1
        webSocketService.sendStreamChunk(
            StreamTransferChunkDto(
                transferId = context.transferId,
                seq = seq,
                data = chunkBase64,
                chunkHash = chunkHash,
                isLast = isLast
            )
        )
    }

    private fun startSendingChunks(context: StreamSenderContext) {
        sendJob?.cancel()
        sendJob = scope.launch(Dispatchers.IO) {
            context.stateMutex.withLock {
                if (context.lastAckAt <= 0) {
                    context.lastAckAt = System.currentTimeMillis()
                }
            }
            updateState(
                _state.value?.copy(
                    phase = StreamTransferPhase.Transferring,
                    transferredChunks = context.ackedSeqs.size
                )
            )
            while (isActive) {
                if (!webSocketService.isConnected) {
                    delay(250)
                    continue
                }
                val seqToSend: Int? = context.stateMutex.withLock {
                    while (context.resendQueue.isNotEmpty()) {
                        val seq = context.resendQueue.removeFirst()
                        context.resendSet.remove(seq)
                        if (context.ackedSeqs.contains(seq)) continue
                        context.inFlight.add(seq)
                        return@withLock seq
                    }
                    if (context.inFlight.size >= context.adaptiveWindowSize) {
                        return@withLock -1
                    }
                    if (context.nextSeq >= context.totalChunks) {
                        return@withLock if (context.inFlight.isEmpty()) null else -1
                    }
                    val seq = context.nextSeq
                    context.nextSeq += 1
                    context.inFlight.add(seq)
                    seq
                }
                when (seqToSend) {
                    null -> break
                    -1 -> {
                        delay(10)
                        continue
                    }
                    else -> {
                        val sendResult = runCatching { sendStreamChunk(context, seqToSend) }
                        if (sendResult.isFailure) {
                            val error = sendResult.exceptionOrNull()
                            if (error is IllegalStateException) {
                                emitError(context.streamChatId, error.message ?: "Ошибка чтения файла")
                                updateState(_state.value?.copy(phase = StreamTransferPhase.Failed, message = error.message))
                                closeSenderContext()
                                return@launch
                            }
                            // Don't cancel transfer, just requeue for resend.
                            context.stateMutex.withLock {
                                if (!context.ackedSeqs.contains(seqToSend) && context.resendSet.add(seqToSend)) {
                                    context.resendQueue.add(seqToSend)
                                }
                                context.inFlight.remove(seqToSend)
                            }
                            registerCongestion(context, 1, "send_error")
                            emitError(context.streamChatId, "Связь нестабильна, пробуем возобновить…")
                            delay(500)
                        } else {
                            context.stateMutex.withLock {
                                context.sentChunks += 1
                            }
                        }
                    }
                }
            }
            if (!isActive) return@launch
            updateState(_state.value?.copy(phase = StreamTransferPhase.WaitingComplete))
        }
    }

    private suspend fun handleIncomingChunk(context: StreamReceiverContext, chunk: StreamTransferChunkDto) {
            var nackSeqs: List<Int> = emptyList()
            var ackSeqs: List<Int> = emptyList()
            var received = 0
            var shouldUpdateProgress = false
            var isComplete = false
            context.receivedMutex.withLock {
                val payloadBytes = try {
                    base64Decode(chunk.data)
                } catch (_: Exception) {
                    nackSeqs = listOf(chunk.seq)
                    return@withLock
                }
                if (shouldValidateChunkHash(context.offer)) {
                    if (chunk.chunkHash.isBlank()) {
                        nackSeqs = listOf(chunk.seq)
                        return@withLock
                    }
                    val expected = crc32Hex(payloadBytes)
                    if (!expected.equals(chunk.chunkHash, ignoreCase = true)) {
                        nackSeqs = listOf(chunk.seq)
                        return@withLock
                    }
                }
                if (!context.receivedSeqs.add(chunk.seq)) {
                    context.pendingAckSeqs.add(chunk.seq)
                    ackSeqs = drainPendingAcksLocked(context, force = true)
                    return@withLock
                }
                val now = System.currentTimeMillis()
                if (context.lastChunkAt > 0) {
                    val gap = (now - context.lastChunkAt).coerceAtLeast(1L)
                    context.averageChunkGapMs = if (context.averageChunkGapMs <= 0) {
                        gap
                    } else {
                        (context.averageChunkGapMs * 7 + gap) / 8
                    }
                }
                context.lastChunkAt = now
                tuneAckStrategy(context)
                val offset = chunk.seq.toLong() * context.offer.chunkSize.toLong()
                streamStorage.writeChunk(context.tempPath, offset, payloadBytes)
                context.pendingAckSeqs.add(chunk.seq)
                received = context.receivedSeqs.size
                isComplete = received >= context.offer.totalChunks
                if (isComplete ||
                    context.pendingAckSeqs.size >= context.dynamicAckBatchSize ||
                    now - context.lastAckFlushAt >= context.dynamicAckFlushIntervalMs
                ) {
                    ackSeqs = drainPendingAcksLocked(context, force = true)
                }
                if (shouldEmitProgress(
                        lastChunks = context.lastProgressChunks,
                        currentChunks = received,
                        totalChunks = context.offer.totalChunks,
                        lastAt = context.lastProgressAt,
                        now = now
                    )
                ) {
                    shouldUpdateProgress = true
                    context.lastProgressChunks = received
                    context.lastProgressAt = now
                }
            }
            if (nackSeqs.isNotEmpty()) {
                webSocketService.nackStreamChunks(context.transferId, nackSeqs)
                return
            }
            if (ackSeqs.isNotEmpty()) {
                webSocketService.ackStreamChunks(context.transferId, ackSeqs)
            }
            if (shouldUpdateProgress) {
                clearError()
                updateState(_state.value?.copy(transferredChunks = received))
            }
            if (isComplete) {
                val finalAck = context.receivedMutex.withLock {
                    drainPendingAcksLocked(context, force = true)
                }
                if (finalAck.isNotEmpty()) {
                    webSocketService.ackStreamChunks(context.transferId, finalAck)
                }
                updateState(_state.value?.copy(phase = StreamTransferPhase.Verifying))
                val shouldVerifyHash = context.offer.fileHash.isNotBlank() &&
                    !context.offer.fileHash.equals(DEFERRED_FILE_HASH, ignoreCase = true) &&
                    !context.offer.fileHashAlgorithm.equals("NONE", ignoreCase = true)
                if (shouldVerifyHash) {
                    val fileHash = sha256Base64ForFile(context.tempPath)
                    if (!fileHash.equals(context.offer.fileHash, ignoreCase = true)) {
                        webSocketService.cancelStreamTransfer(context.transferId, "hash_mismatch")
                        updateState(
                            _state.value?.copy(
                                phase = StreamTransferPhase.Failed,
                                message = "Хэш не совпал"
                            )
                        )
                        streamStorage.deleteTempFile(context.tempPath)
                        receiverContext = null
                        return
                    }
                }
                updateState(_state.value?.copy(phase = StreamTransferPhase.Saving))
                val copyResult = streamStorage.copyTempToTarget(context.tempPath, context.saveTarget)
                streamStorage.deleteTempFile(context.tempPath)
                if (copyResult.isSuccess) {
                    webSocketService.completeStreamTransfer(context.transferId)
                    updateState(_state.value?.copy(phase = StreamTransferPhase.Completed))
                } else {
                    webSocketService.cancelStreamTransfer(context.transferId, "save_failed")
                    updateState(
                        _state.value?.copy(
                            phase = StreamTransferPhase.Failed,
                            message = copyResult.exceptionOrNull()?.message
                        )
                    )
                }
                receiverContext = null
            }
        }
    }

    private suspend fun handleStreamEvent(event: StreamTransferEvent) {
        when (event) {
            is StreamTransferEvent.Offer -> {
                val offer = event.data
                if (isActiveStreamPhase(_state.value?.phase)) {
                    webSocketService.rejectStreamTransfer(offer.transferId, "busy")
                    return
                }
                if (_state.value != null) {
                    closeSenderContext()
                    receiverContext?.let { streamStorage.deleteTempFile(it.tempPath) }
                    receiverContext = null
                    _state.value = null
                    clearError()
                }
                _offer.value = offer
            }
            is StreamTransferEvent.Accepted -> {
                val ctx = senderContext ?: return
                if (event.data.transferId != ctx.transferId) return
                startSendingChunks(ctx)
            }
            is StreamTransferEvent.Rejected -> {
                val ctx = senderContext ?: return
                if (event.data.transferId != ctx.transferId) return
                updateState(
                    _state.value?.copy(
                        phase = StreamTransferPhase.Canceled,
                        message = event.data.reason
                    )
                )
                closeSenderContext()
            }
            is StreamTransferEvent.Canceled -> {
                val transferId = event.data.transferId
                if (senderContext?.transferId == transferId) {
                    updateState(
                        _state.value?.copy(
                            phase = StreamTransferPhase.Canceled,
                            message = event.data.reason
                        )
                    )
                    closeSenderContext()
                }
                if (receiverContext?.transferId == transferId) {
                    receiverContext?.let { streamStorage.deleteTempFile(it.tempPath) }
                    receiverContext = null
                    updateState(
                        _state.value?.copy(
                            phase = StreamTransferPhase.Canceled,
                            message = event.data.reason
                        )
                    )
                }
            }
            is StreamTransferEvent.Complete -> {
                val ctx = senderContext ?: return
                if (event.data.transferId != ctx.transferId) return
                updateState(_state.value?.copy(phase = StreamTransferPhase.Completed))
                closeSenderContext()
            }
            is StreamTransferEvent.Chunk -> {
                val ctx = receiverContext ?: return
                if (event.data.transferId != ctx.transferId) return
                handleIncomingChunk(ctx, event.data)
            }
            is StreamTransferEvent.Nack -> {
                val ctx = senderContext ?: return
                if (event.data.transferId != ctx.transferId) return
                ctx.stateMutex.withLock {
                    event.data.seqs.forEach { seq ->
                        if (ctx.ackedSeqs.contains(seq)) return@forEach
                        ctx.inFlight.remove(seq)
                        if (ctx.resendSet.add(seq)) {
                            ctx.resendQueue.add(seq)
                        }
                    }
                    ctx.lastAckAt = System.currentTimeMillis()
                }
                registerCongestion(ctx, max(1, event.data.seqs.size), "nack")
            }
            is StreamTransferEvent.Resume -> {
                val ctx = senderContext ?: return
                if (event.data.transferId != ctx.transferId) return
                ctx.stateMutex.withLock {
                    event.data.missingSeqs.forEach { seq ->
                        if (ctx.ackedSeqs.contains(seq)) return@forEach
                        ctx.inFlight.remove(seq)
                        if (ctx.resendSet.add(seq)) {
                            ctx.resendQueue.add(seq)
                        }
                    }
                    ctx.lastAckAt = System.currentTimeMillis()
                }
                registerCongestion(ctx, max(1, event.data.missingSeqs.size), "resume")
            }
            is StreamTransferEvent.Ack -> {
                val ctx = senderContext ?: return
                if (event.data.transferId != ctx.transferId) return
                var ackedCount = 0
                var newAcks = 0
                var shouldUpdateProgress = false
                ctx.stateMutex.withLock {
                    event.data.seqs.forEach { seq ->
                        if (ctx.ackedSeqs.add(seq)) {
                            ctx.inFlight.remove(seq)
                            newAcks += 1
                        }
                        ctx.resendSet.remove(seq)
                    }
                    ackedCount = ctx.ackedSeqs.size
                    val now = System.currentTimeMillis()
                    ctx.lastAckAt = now
                    if (newAcks > 0) {
                        ctx.acksSinceIncrease += newAcks
                        tryIncreaseWindowLocked(ctx, now)
                    }
                    if (shouldEmitProgress(
                            lastChunks = ctx.lastProgressChunks,
                            currentChunks = ackedCount,
                            totalChunks = ctx.totalChunks,
                            lastAt = ctx.lastProgressAt,
                            now = now
                        )
                    ) {
                        shouldUpdateProgress = true
                        ctx.lastProgressChunks = ackedCount
                        ctx.lastProgressAt = now
                    }
                }
                clearError()
                if (shouldUpdateProgress) {
                    updateState(_state.value?.copy(transferredChunks = ackedCount))
                }
                if (ackedCount >= ctx.totalChunks &&
                    _state.value?.phase == StreamTransferPhase.Transferring
                ) {
                    updateState(_state.value?.copy(phase = StreamTransferPhase.WaitingComplete))
                }
            }
        }
    }

    private fun startAckWatchdog() {
        scope.launch {
            var lastAcked = 0
            var lastTransferId: String? = null
            while (isActive) {
                delay(5_000)
                val ctx = senderContext ?: continue
                if (ctx.transferId != lastTransferId) {
                    lastTransferId = ctx.transferId
                    lastAcked = 0
                }
                val current = _state.value
                if (current == null || !current.isSender || current.phase != StreamTransferPhase.Transferring) {
                    continue
                }
                val now = System.currentTimeMillis()
                val (ackedCount, lastAckAt, inflight) = ctx.stateMutex.withLock {
                    Triple(ctx.ackedSeqs.size, ctx.lastAckAt, ctx.inFlight.toList())
                }
                if (ackedCount > lastAcked) {
                    lastAcked = ackedCount
                    continue
                }
                if (ackedCount < ctx.totalChunks && lastAckAt > 0 && now - lastAckAt > 20_000) {
                    ctx.stateMutex.withLock {
                        inflight.forEach { seq ->
                            if (ctx.ackedSeqs.contains(seq)) return@forEach
                            ctx.inFlight.remove(seq)
                            if (ctx.resendSet.add(seq)) {
                                ctx.resendQueue.add(seq)
                            }
                        }
                        ctx.lastAckAt = now
                    }
                    registerCongestion(ctx, max(1, inflight.size), "ack_timeout")
                    emitError(ctx.streamChatId, "Нет подтверждений, пробуем возобновить…")
                }
            }
        }
    }

    private fun startReceiverResumeLoop() {
        scope.launch {
            while (isActive) {
                delay(5_000)
                val ctx = receiverContext ?: continue
                val current = _state.value
                if (current == null || current.phase != StreamTransferPhase.Transferring) {
                    continue
                }
                val lastAt = ctx.lastChunkAt
                val now = System.currentTimeMillis()
                if (lastAt > 0 && now - lastAt > 5_000) {
                    val missing = computeMissingSeqs(ctx)
                    if (missing.isNotEmpty()) {
                        webSocketService.requestStreamTransferResume(ctx.transferId, missing)
                        ctx.lastChunkAt = now
                    }
                }
            }
        }
    }

    private fun startReceiverAckFlushLoop() {
        scope.launch {
            while (isActive) {
                delay(80)
                val ctx = receiverContext ?: continue
                val current = _state.value
                if (current == null || current.phase != StreamTransferPhase.Transferring) {
                    continue
                }
                val ackSeqs = ctx.receivedMutex.withLock {
                    drainPendingAcksLocked(ctx, force = false)
                }
                if (ackSeqs.isNotEmpty()) {
                    webSocketService.ackStreamChunks(ctx.transferId, ackSeqs)
                }
            }
        }
    }

    private fun tuneAckStrategy(context: StreamReceiverContext) {
        val gap = context.averageChunkGapMs
        val batch = when {
            gap in 1..10 -> 64
            gap in 11..20 -> 48
            gap in 21..40 -> 32
            else -> ackBatchSize
        }
        val flush = when {
            gap in 1..10 -> 80L
            gap in 11..20 -> 100L
            gap in 21..40 -> 130L
            else -> ackFlushIntervalMs
        }
        context.dynamicAckBatchSize = batch
        context.dynamicAckFlushIntervalMs = flush
    }

    private suspend fun registerCongestion(context: StreamSenderContext, severity: Int, reason: String) {
        var oldWindow = 0
        var newWindow = 0
        context.stateMutex.withLock {
            val now = System.currentTimeMillis()
            val applied = max(1, severity)
            context.resentChunks += applied
            context.congestionEvents += 1
            context.lastCongestionAt = now
            context.acksSinceIncrease = 0
            oldWindow = context.adaptiveWindowSize
            newWindow = max(context.minWindowSize, (context.adaptiveWindowSize * 7) / 10)
            context.adaptiveWindowSize = newWindow
            context.lastWindowAdjustAt = now
        }
        if (newWindow < oldWindow) {
            println("[STREAM][CC] id=${context.transferId} congestion=$reason window=$oldWindow->$newWindow")
        }
    }

    private fun tryIncreaseWindowLocked(context: StreamSenderContext, now: Long) {
        if (context.adaptiveWindowSize >= context.maxWindowSize) return
        if (now - context.lastCongestionAt < 3_000) return
        if (now - context.lastWindowAdjustAt < 400) return
        val threshold = max(4, context.adaptiveWindowSize / 2)
        if (context.acksSinceIncrease < threshold) return
        val old = context.adaptiveWindowSize
        context.adaptiveWindowSize = min(context.maxWindowSize, context.adaptiveWindowSize + 1)
        context.acksSinceIncrease = 0
        context.lastWindowAdjustAt = now
        if (context.adaptiveWindowSize > old) {
            println("[STREAM][CC] id=${context.transferId} window=$old->${context.adaptiveWindowSize}")
        }
    }

    private fun startTelemetryLoop() {
        scope.launch {
            while (isActive) {
                delay(2_000)
                emitSenderTelemetry()
                emitReceiverTelemetry()
            }
        }
    }

    private suspend fun emitSenderTelemetry() {
        val ctx = senderContext ?: return
        val current = _state.value ?: return
        if (!current.isSender) return
        if (current.phase != StreamTransferPhase.Transferring &&
            current.phase != StreamTransferPhase.WaitingComplete
        ) {
            return
        }
        var line: String? = null
        ctx.stateMutex.withLock {
            val now = System.currentTimeMillis()
            val previousAt = if (ctx.lastTelemetryAt > 0) ctx.lastTelemetryAt else now
            val elapsedMs = (now - previousAt).coerceAtLeast(1L)
            val acked = ctx.ackedSeqs.size
            val deltaAcked = (acked - ctx.lastTelemetryAckedChunks).coerceAtLeast(0)
            val bytesPerSec = deltaAcked.toDouble() * ctx.chunkSize.toDouble() * 1000.0 / elapsedMs.toDouble()
            val mbPerSec = ((bytesPerSec / (1024.0 * 1024.0)) * 10.0).toInt() / 10.0
            val resendRate = if (ctx.sentChunks > 0) {
                (ctx.resentChunks.toDouble() * 1000.0 / ctx.sentChunks.toDouble()).toInt() / 10.0
            } else {
                0.0
            }
            line = "[STREAM][TX] id=${ctx.transferId} speed=${mbPerSec}MB/s " +
                "acked=${acked}/${ctx.totalChunks} window=${ctx.adaptiveWindowSize}/${ctx.maxWindowSize} " +
                "resendRate=${resendRate}%"
            ctx.lastTelemetryAt = now
            ctx.lastTelemetryAckedChunks = acked
        }
        line?.let { println(it) }
    }

    private suspend fun emitReceiverTelemetry() {
        val ctx = receiverContext ?: return
        val current = _state.value ?: return
        if (current.phase != StreamTransferPhase.Transferring &&
            current.phase != StreamTransferPhase.Verifying &&
            current.phase != StreamTransferPhase.Saving
        ) {
            return
        }
        var line: String? = null
        ctx.receivedMutex.withLock {
            val now = System.currentTimeMillis()
            val previousAt = if (ctx.lastTelemetryAt > 0) ctx.lastTelemetryAt else now
            val elapsedMs = (now - previousAt).coerceAtLeast(1L)
            val received = ctx.receivedSeqs.size
            val deltaReceived = (received - ctx.lastTelemetryReceivedChunks).coerceAtLeast(0)
            val bytesPerSec = deltaReceived.toDouble() * ctx.offer.chunkSize.toDouble() * 1000.0 / elapsedMs.toDouble()
            val mbPerSec = ((bytesPerSec / (1024.0 * 1024.0)) * 10.0).toInt() / 10.0
            line = "[STREAM][RX] id=${ctx.transferId} speed=${mbPerSec}MB/s " +
                "received=${received}/${ctx.offer.totalChunks} ackBatch=${ctx.dynamicAckBatchSize} " +
                "ackFlush=${ctx.dynamicAckFlushIntervalMs}ms"
            ctx.lastTelemetryAt = now
            ctx.lastTelemetryReceivedChunks = received
        }
        line?.let { println(it) }
    }

    private fun drainPendingAcksLocked(context: StreamReceiverContext, force: Boolean): List<Int> {
        if (context.pendingAckSeqs.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        if (!force &&
            context.pendingAckSeqs.size < context.dynamicAckBatchSize &&
            now - context.lastAckFlushAt < context.dynamicAckFlushIntervalMs
        ) {
            return emptyList()
        }
        val ackSeqs = context.pendingAckSeqs.toList()
        context.pendingAckSeqs.clear()
        context.lastAckFlushAt = now
        context.totalAckSent += ackSeqs.size
        context.totalAckBatches += 1
        return ackSeqs
    }

    private fun shouldValidateChunkHash(offer: StreamTransferOfferDto): Boolean {
        return offer.chunkHashAlgorithm.equals(CHUNK_HASH_CRC32, ignoreCase = true)
    }

    private fun shouldEmitProgress(
        lastChunks: Int,
        currentChunks: Int,
        totalChunks: Int,
        lastAt: Long,
        now: Long
    ): Boolean {
        if (currentChunks <= 0) return false
        if (currentChunks >= totalChunks) return true
        if (lastChunks <= 0) return true
        if (currentChunks - lastChunks >= progressUpdateChunkStep) return true
        return now - lastAt >= progressUpdateIntervalMs
    }

    private suspend fun computeMissingSeqs(context: StreamReceiverContext, maxCount: Int = 64): List<Int> {
        val total = context.offer.totalChunks
        if (total <= 0) return emptyList()
        return context.receivedMutex.withLock {
            val missing = ArrayList<Int>(min(maxCount, total))
            for (seq in 0 until total) {
                if (!context.receivedSeqs.contains(seq)) {
                    missing.add(seq)
                    if (missing.size >= maxCount) break
                }
            }
            missing
        }
    }

    private fun isActiveStreamPhase(phase: StreamTransferPhase?): Boolean {
        return phase == StreamTransferPhase.AwaitingAcceptance ||
            phase == StreamTransferPhase.Transferring ||
            phase == StreamTransferPhase.WaitingComplete ||
            phase == StreamTransferPhase.Verifying ||
            phase == StreamTransferPhase.Saving
    }
}


