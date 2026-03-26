package com.messenger.client.transfer

import com.messenger.client.services.createHttpClient
import com.messenger.client.services.defaultServerUrl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.takeFrom
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class StreamTransferSocketRole(val wireName: String) {
    Sender("sender"),
    Receiver("receiver")
}

data class StreamTransferBinaryFrame(
    val transferId: String,
    val lane: Int,
    val seq: Int,
    val buffer: ByteArray,
    val dataOffset: Int,
    val dataLength: Int,
    val isLast: Boolean
)

data class StreamTransferBinaryLaneState(
    val lane: Int,
    val connected: Boolean = false,
    val lastError: String? = null
)

data class StreamTransferBinaryChannelState(
    val transferId: String? = null,
    val role: StreamTransferSocketRole? = null,
    val lanes: Map<Int, StreamTransferBinaryLaneState> = emptyMap(),
    val lastError: String? = null
)

class StreamTransferBinaryChannel(
    private val serverUrl: String = defaultServerUrl,
    private val client: HttpClient = createHttpClient()
) {
    companion object {
        private const val HEADER_SIZE = 5
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionMutex = Mutex()
    private var activeTransferId: String? = null
    private var activeRole: StreamTransferSocketRole? = null
    private val sessions = mutableMapOf<Int, WebSocketSession>()
    private val receiveJobs = mutableMapOf<Int, Job>()

    private val _incomingFrames = MutableSharedFlow<StreamTransferBinaryFrame>(extraBufferCapacity = 64)
    val incomingFrames: SharedFlow<StreamTransferBinaryFrame> = _incomingFrames.asSharedFlow()

    private val _state = MutableStateFlow(StreamTransferBinaryChannelState())
    val state: StateFlow<StreamTransferBinaryChannelState> = _state.asStateFlow()

    fun isConnectedFor(transferId: String, role: StreamTransferSocketRole, lane: Int): Boolean {
        val current = _state.value
        return current.transferId == transferId &&
            current.role == role &&
            current.lanes[lane]?.connected == true
    }

    suspend fun connectLane(
        transferId: String,
        role: StreamTransferSocketRole,
        authToken: String,
        lane: Int
    ): Boolean {
        return connectionMutex.withLock {
            if (isConnectedFor(transferId, role, lane)) {
                return@withLock true
            }
            if ((activeTransferId != null && activeTransferId != transferId) ||
                (activeRole != null && activeRole != role)
            ) {
                closeCurrentLocked(null)
            }
            activeTransferId = transferId
            activeRole = role
            closeLaneLocked(lane, null)

            val targetUrl = buildSocketUrl(serverUrl, transferId, role, lane)
            val opened = runCatching {
                client.webSocketSession {
                    url {
                        takeFrom(targetUrl)
                    }
                    header(HttpHeaders.Authorization, "Bearer $authToken")
                }
            }.getOrElse { error ->
                updateLaneStateLocked(
                    transferId = transferId,
                    role = role,
                    lane = lane,
                    connected = false,
                    laneError = error.message ?: "failed_to_connect",
                    globalError = error.message ?: "failed_to_connect"
                )
                return@withLock false
            }

            sessions[lane] = opened
            updateLaneStateLocked(
                transferId = transferId,
                role = role,
                lane = lane,
                connected = true,
                laneError = null,
                globalError = null
            )
            receiveJobs[lane] = scope.launch {
                receiveLoop(opened, transferId, role, lane)
            }
            true
        }
    }

    suspend fun connectAll(
        transferId: String,
        role: StreamTransferSocketRole,
        authToken: String,
        laneCount: Int
    ): Boolean {
        for (lane in 0 until laneCount) {
            if (!connectLane(transferId, role, authToken, lane)) {
                return false
            }
        }
        return true
    }

    suspend fun sendChunk(
        transferId: String,
        lane: Int,
        seq: Int,
        payload: ByteArray,
        isLast: Boolean
    ) {
        val activeSession: WebSocketSession = connectionMutex.withLock {
            val current = _state.value
            val active = sessions[lane]
            if (!currentMatches(current, transferId, lane) || active == null) {
                throw IllegalStateException("binary_channel_not_connected")
            }
            active
        }
        activeSession.send(encodeFrame(seq, payload, isLast))
    }

    fun disconnectLane(lane: Int) {
        scope.launch {
            connectionMutex.withLock {
                closeLaneLocked(lane, null)
            }
        }
    }

    fun disconnect() {
        scope.launch {
            connectionMutex.withLock {
                closeCurrentLocked(null)
            }
        }
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    private suspend fun receiveLoop(
        socketSession: WebSocketSession,
        transferId: String,
        role: StreamTransferSocketRole,
        lane: Int
    ) {
        var errorMessage: String? = null
        var fragmentedFrames: MutableList<ByteArray>? = null
        var fragmentedBytes = 0
        try {
            for (frame in socketSession.incoming) {
                when (frame) {
                    is Frame.Binary -> {
                        val rawBytes = frame.readBytes()
                        val completeMessage = when {
                            frame.fin && fragmentedFrames == null -> rawBytes
                            else -> {
                                val parts = fragmentedFrames ?: ArrayList<ByteArray>(4).also {
                                    fragmentedFrames = it
                                }
                                if (rawBytes.isNotEmpty()) {
                                    parts.add(rawBytes)
                                    fragmentedBytes += rawBytes.size
                                }
                                if (!frame.fin) {
                                    continue
                                }
                                val merged = ByteArray(fragmentedBytes)
                                var offset = 0
                                parts.forEach { part ->
                                    part.copyInto(merged, offset)
                                    offset += part.size
                                }
                                fragmentedFrames = null
                                fragmentedBytes = 0
                                merged
                            }
                        }
                        val decoded = decodeFrame(transferId, lane, completeMessage)
                        _incomingFrames.emit(decoded)
                    }
                    is Frame.Close -> break
                    else -> Unit
                }
            }
        } catch (_: CancellationException) {
        } catch (e: Exception) {
            errorMessage = e.message ?: "binary_channel_closed"
        } finally {
            connectionMutex.withLock {
                if (sessions[lane] === socketSession &&
                    _state.value.transferId == transferId &&
                    _state.value.role == role
                ) {
                    closeLaneLocked(lane, errorMessage)
                }
            }
        }
    }

    private suspend fun closeCurrentLocked(lastError: String?) {
        val lanes = sessions.keys.toList()
        lanes.forEach { lane ->
            closeLaneLocked(lane, lastError)
        }
        activeTransferId = null
        activeRole = null
        _state.value = StreamTransferBinaryChannelState(lastError = lastError)
    }

    private suspend fun closeLaneLocked(lane: Int, lastError: String?) {
        receiveJobs.remove(lane)?.cancel()
        val currentSession = sessions.remove(lane)
        currentSession?.let { session ->
            runCatching {
                session.close(CloseReason(CloseReason.Codes.NORMAL, "closed"))
            }
        }
        val currentState = _state.value
        val remainingLanes = currentState.lanes.toMutableMap()
        if (remainingLanes.containsKey(lane) || lastError != null) {
            remainingLanes[lane] = StreamTransferBinaryLaneState(
                lane = lane,
                connected = false,
                lastError = lastError
            )
        }
        _state.value = StreamTransferBinaryChannelState(
            transferId = activeTransferId,
            role = activeRole,
            lanes = remainingLanes,
            lastError = lastError ?: currentState.lastError
        )
        if (sessions.isEmpty()) {
            activeTransferId = null
            activeRole = null
            _state.value = StreamTransferBinaryChannelState(
                lastError = lastError ?: currentState.lastError
            )
        }
    }

    private fun updateLaneStateLocked(
        transferId: String,
        role: StreamTransferSocketRole,
        lane: Int,
        connected: Boolean,
        laneError: String?,
        globalError: String?
    ) {
        val current = _state.value
        val laneStates = current.lanes.toMutableMap()
        laneStates[lane] = StreamTransferBinaryLaneState(
            lane = lane,
            connected = connected,
            lastError = laneError
        )
        _state.value = StreamTransferBinaryChannelState(
            transferId = transferId,
            role = role,
            lanes = laneStates,
            lastError = globalError
        )
    }

    private fun currentMatches(
        current: StreamTransferBinaryChannelState,
        transferId: String,
        lane: Int
    ): Boolean {
        return current.transferId == transferId &&
            current.role == StreamTransferSocketRole.Sender &&
            current.lanes[lane]?.connected == true
    }

    private fun encodeFrame(seq: Int, payload: ByteArray, isLast: Boolean): ByteArray {
        val data = ByteArray(HEADER_SIZE + payload.size)
        data[0] = ((seq ushr 24) and 0xFF).toByte()
        data[1] = ((seq ushr 16) and 0xFF).toByte()
        data[2] = ((seq ushr 8) and 0xFF).toByte()
        data[3] = (seq and 0xFF).toByte()
        data[4] = if (isLast) 1 else 0
        payload.copyInto(data, destinationOffset = HEADER_SIZE)
        return data
    }

    private fun decodeFrame(transferId: String, lane: Int, raw: ByteArray): StreamTransferBinaryFrame {
        require(raw.size >= HEADER_SIZE) { "binary_frame_too_small" }
        val seq = ((raw[0].toInt() and 0xFF) shl 24) or
            ((raw[1].toInt() and 0xFF) shl 16) or
            ((raw[2].toInt() and 0xFF) shl 8) or
            (raw[3].toInt() and 0xFF)
        val isLast = raw[4].toInt() != 0
        return StreamTransferBinaryFrame(
            transferId = transferId,
            lane = lane,
            seq = seq,
            buffer = raw,
            dataOffset = HEADER_SIZE,
            dataLength = raw.size - HEADER_SIZE,
            isLast = isLast
        )
    }
}

private fun buildSocketUrl(
    serverUrl: String,
    transferId: String,
    role: StreamTransferSocketRole,
    lane: Int
): String {
    val builder = URLBuilder(serverUrl)
    builder.protocol = when (builder.protocol) {
        URLProtocol.HTTPS -> URLProtocol.WSS
        else -> URLProtocol.WS
    }
    val baseSegments = builder.pathSegments.filter { it.isNotBlank() }
    builder.pathSegments = baseSegments + listOf("stream-transfer", "ws")
    builder.parameters.append("transferId", transferId)
    builder.parameters.append("role", role.wireName)
    builder.parameters.append("lane", lane.toString())
    return builder.buildString()
}
