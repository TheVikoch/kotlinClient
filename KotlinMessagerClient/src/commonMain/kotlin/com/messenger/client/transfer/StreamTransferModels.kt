package com.messenger.client.transfer

import com.messenger.client.media.StreamPickedFile
import com.messenger.client.media.StreamSaveTarget
import com.messenger.client.models.StreamTransferOfferDto
import kotlinx.coroutines.sync.Mutex

enum class StreamTransferPhase {
    AwaitingAcceptance,
    Transferring,
    WaitingComplete,
    Verifying,
    Saving,
    Completed,
    Failed,
    Canceled
}

data class StreamTransferUiState(
    val transferId: String,
    val streamChatId: String,
    val fileName: String,
    val fileSize: Long,
    val totalChunks: Int,
    val transferredChunks: Int,
    val isSender: Boolean,
    val phase: StreamTransferPhase,
    val message: String? = null
)

data class StreamTransferUiError(
    val streamChatId: String,
    val message: String
)

data class StreamSenderContext(
    val transferId: String,
    val streamChatId: String,
    val file: StreamPickedFile,
    val fileSize: Long,
    val chunkSize: Int,
    val totalChunks: Int,
    val windowSize: Int,
    val chunkHashAlgorithm: String,
    val minWindowSize: Int = 8,
    val maxWindowSize: Int = if (windowSize < 64) 64 else windowSize,
    val readMutex: Mutex = Mutex(),
    val stateMutex: Mutex = Mutex(),
    val inFlight: MutableSet<Int> = mutableSetOf(),
    val ackedSeqs: MutableSet<Int> = mutableSetOf(),
    val resendQueue: ArrayDeque<Int> = ArrayDeque(),
    val resendSet: MutableSet<Int> = mutableSetOf(),
    var nextSeq: Int = 0,
    var adaptiveWindowSize: Int = windowSize,
    var acksSinceIncrease: Int = 0,
    var sentChunks: Int = 0,
    var resentChunks: Int = 0,
    var congestionEvents: Int = 0,
    var lastAckAt: Long = 0L,
    var lastCongestionAt: Long = 0L,
    var lastWindowAdjustAt: Long = 0L,
    var lastProgressChunks: Int = 0,
    var lastProgressAt: Long = 0L,
    var startedAt: Long = System.currentTimeMillis(),
    var lastTelemetryAt: Long = 0L,
    var lastTelemetryAckedChunks: Int = 0
)

data class StreamReceiverContext(
    val transferId: String,
    val offer: StreamTransferOfferDto,
    val tempPath: String,
    val saveTarget: StreamSaveTarget,
    val receivedSeqs: MutableSet<Int> = mutableSetOf(),
    val pendingAckSeqs: MutableSet<Int> = mutableSetOf(),
    val receivedMutex: Mutex = Mutex(),
    var lastChunkAt: Long = 0L,
    var averageChunkGapMs: Long = 0L,
    var lastAckFlushAt: Long = 0L,
    var dynamicAckBatchSize: Int = 16,
    var dynamicAckFlushIntervalMs: Long = 150L,
    var totalAckSent: Int = 0,
    var totalAckBatches: Int = 0,
    var lastProgressChunks: Int = 0,
    var lastProgressAt: Long = 0L,
    var startedAt: Long = System.currentTimeMillis(),
    var lastTelemetryAt: Long = 0L,
    var lastTelemetryReceivedChunks: Int = 0
)
