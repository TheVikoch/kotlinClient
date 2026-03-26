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
    val senderLaneCount: Int,
    val totalChunks: Int,
    val windowSize: Int,
    val chunkHashAlgorithm: String,
    val minWindowSize: Int = when {
        windowSize <= 2 -> 1
        windowSize <= 4 -> 2
        else -> maxOf(4, windowSize / 2)
    },
    val maxWindowSize: Int = maxOf(windowSize, minWindowSize),
    val readMutex: Mutex = Mutex(),
    val stateMutex: Mutex = Mutex(),
    val inFlight: MutableSet<Int> = mutableSetOf(),
    val sentSeqs: MutableSet<Int> = mutableSetOf(),
    val ackedSeqs: MutableSet<Int> = mutableSetOf(),
    val resendQueues: MutableList<ArrayDeque<Int>> = MutableList(senderLaneCount) { ArrayDeque<Int>() },
    val resendSet: MutableSet<Int> = mutableSetOf(),
    val nextSeqByLane: MutableList<Int> = MutableList(senderLaneCount) { it },
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
    var lastTelemetryAckedChunks: Int = 0,
    var lastTelemetrySentChunks: Int = 0
)

data class StreamReceiverContext(
    val transferId: String,
    val offer: StreamTransferOfferDto,
    val tempPath: String,
    val saveTarget: StreamSaveTarget,
    val receiverLaneCount: Int,
    val receivedSeqs: MutableSet<Int> = mutableSetOf(),
    val pendingAckSeqs: MutableSet<Int> = mutableSetOf(),
    val receivedMutex: Mutex = Mutex(),
    var highestSeqReceived: Int = -1,
    var lastChunkReceived: Boolean = false,
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
