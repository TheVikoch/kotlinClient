package com.messenger.client.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

@Composable
actual fun rememberChatMediaRecorder(
    onDraftReady: (ChatRecordedAttachment) -> Unit,
    onError: (String) -> Unit
): ChatMediaRecorder {
    val scope = rememberCoroutineScope()
    val recorder = remember(onDraftReady, onError, scope) {
        DesktopChatMediaRecorder(
            scope = scope,
            onDraftReady = onDraftReady,
            onError = onError
        )
    }

    DisposableEffect(recorder) {
        onDispose {
            recorder.dispose()
        }
    }

    return recorder
}

@Composable
actual fun ChatMediaRecorderPreview(
    recorder: ChatMediaRecorder,
    state: ChatMediaRecorderUiState,
    modifier: Modifier
) = Unit

private class DesktopChatMediaRecorder(
    private val scope: CoroutineScope,
    private val onDraftReady: (ChatRecordedAttachment) -> Unit,
    private val onError: (String) -> Unit
) : ChatMediaRecorder {
    private val state = MutableStateFlow(
        ChatMediaRecorderUiState(
            supportsVoiceNotes = true,
            supportsVideoNotes = false
        )
    )
    private val preferredFormats = listOf(
        AudioFormat(48_000f, 16, 1, true, false),
        AudioFormat(44_100f, 16, 1, true, false),
        AudioFormat(32_000f, 16, 1, true, false),
        AudioFormat(22_050f, 16, 1, true, false),
        AudioFormat(48_000f, 16, 2, true, false),
        AudioFormat(44_100f, 16, 2, true, false)
    )
    private var line: TargetDataLine? = null
    private var selectedAudioFormat: AudioFormat? = null
    private var captureJob: Job? = null
    private var timerJob: Job? = null
    private var rawAudio = ByteArrayOutputStream()
    private var isCapturing = false
    private var isPaused = false
    private var activeStartedAtMs = 0L
    private var accumulatedDurationMs = 0L
    private var outputFile: File? = null

    override val uiState: StateFlow<ChatMediaRecorderUiState> = state

    override fun toggleSelectedMode() = Unit

    override fun switchCamera() = Unit

    override fun beginCapture() {
        if (!state.value.isIdle) return
        if (state.value.selectedMode != ChatMediaCaptureMode.VoiceNote) {
            onError("Запись кружков на desktop пока недоступна")
            return
        }

        runCatching {
            val (newLine, format) = openInputLine()
                ?: error("На desktop не удалось открыть микрофон для записи")

            rawAudio = ByteArrayOutputStream()
            isCapturing = true
            isPaused = false
            accumulatedDurationMs = 0L
            activeStartedAtMs = nowMs()
            line = newLine
            selectedAudioFormat = format
            outputFile = File(
                File(System.getProperty("java.io.tmpdir"), "messenger-captured-notes").apply { mkdirs() },
                "voice-note-${System.currentTimeMillis()}.wav"
            )
            captureJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(4096)
                newLine.start()
                while (isActive && isCapturing) {
                    if (isPaused) {
                        delay(40L)
                        continue
                    }
                    val read = newLine.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        rawAudio.write(buffer, 0, read)
                    }
                }
            }
            state.value = state.value.copy(
                phase = ChatMediaRecorderPhase.Recording,
                durationMillis = 0L,
                isLocked = false,
                selectedMode = ChatMediaCaptureMode.VoiceNote
            )
            startTimer()
        }.onFailure { error ->
            cleanup(deleteFile = true)
            onError(error.message ?: "Не удалось начать запись на desktop")
        }
    }

    override fun lockCapture() {
        state.update { current ->
            if (!current.isRecording) return@update current
            current.copy(isLocked = true)
        }
    }

    override fun pauseCapture() {
        if (!state.value.isRecording) return
        isPaused = true
        accumulatedDurationMs = computeCurrentDuration()
        activeStartedAtMs = 0L
        stopTimer()
        runCatching { line?.stop() }
        state.update { current ->
            current.copy(
                phase = ChatMediaRecorderPhase.Paused,
                durationMillis = accumulatedDurationMs,
                isLocked = false
            )
        }
    }

    override fun resumeCapture() {
        if (!state.value.isPaused) return
        isPaused = false
        activeStartedAtMs = nowMs()
        runCatching { line?.start() }
        state.update { current ->
            current.copy(
                phase = ChatMediaRecorderPhase.Recording,
                isLocked = true
            )
        }
        startTimer()
    }

    override fun finalizeCapture() {
        if (state.value.isIdle || state.value.isFinalizing) return
        accumulatedDurationMs = computeCurrentDuration()
        stopTimer()
        state.update { current ->
            current.copy(
                phase = ChatMediaRecorderPhase.Finalizing,
                durationMillis = accumulatedDurationMs,
                isLocked = false
            )
        }

        val localLine = line
        val localJob = captureJob
        val localFile = outputFile
        val localFormat = selectedAudioFormat
        isCapturing = false
        isPaused = false
        activeStartedAtMs = 0L
        runCatching { localLine?.stop() }
        runCatching { localLine?.close() }
        line = null

        scope.launch(Dispatchers.IO) {
            runCatching { localJob?.join() }
            val rawBytes = rawAudio.toByteArray()
            if (rawBytes.isEmpty() || localFile == null || localFormat == null) {
                cleanup(deleteFile = true)
                onError("Не удалось подготовить голосовое на desktop")
                return@launch
            }

            val wavBytes = buildWavBytes(rawBytes, localFormat) ?: run {
                cleanup(deleteFile = true)
                onError("Не удалось сохранить голосовое на desktop")
                return@launch
            }

            localFile.writeBytes(wavBytes)
            cleanup(deleteFile = false)
            onDraftReady(
                ChatRecordedAttachment(
                    fileName = localFile.name,
                    contentType = "audio/wav",
                    bytes = wavBytes,
                    previewBytes = null,
                    durationMillis = accumulatedDurationMs,
                    kind = ChatMediaCaptureMode.VoiceNote,
                    localPath = localFile.absolutePath
                )
            )
        }
    }

    override fun discardCapture() {
        cleanup(deleteFile = true)
    }

    fun dispose() {
        cleanup(deleteFile = true)
    }

    private fun startTimer() {
        stopTimer()
        timerJob = scope.launch {
            while (isActive) {
                state.update { current ->
                    if (!current.isRecording) return@update current
                    current.copy(durationMillis = computeCurrentDuration())
                }
                delay(200L)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun computeCurrentDuration(): Long {
        val runningPart = if (activeStartedAtMs > 0L) {
            nowMs() - activeStartedAtMs
        } else {
            0L
        }
        return (accumulatedDurationMs + runningPart).coerceAtLeast(0L)
    }

    private fun openInputLine(): Pair<TargetDataLine, AudioFormat>? {
        preferredFormats.forEach { format ->
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) {
                return@forEach
            }

            val openedLine = runCatching {
                (AudioSystem.getLine(info) as TargetDataLine).apply {
                    open(format)
                }
            }.getOrNull()

            if (openedLine != null) {
                return openedLine to format
            }
        }
        return null
    }

    private fun cleanup(deleteFile: Boolean) {
        stopTimer()
        isCapturing = false
        isPaused = false
        activeStartedAtMs = 0L
        accumulatedDurationMs = 0L
        runCatching { line?.stop() }
        runCatching { line?.close() }
        line = null
        selectedAudioFormat = null
        captureJob?.cancel()
        captureJob = null
        rawAudio = ByteArrayOutputStream()
        if (deleteFile) {
            outputFile?.delete()
        }
        outputFile = null
        state.value = state.value.copy(
            phase = ChatMediaRecorderPhase.Idle,
            durationMillis = 0L,
            isLocked = false,
            selectedMode = ChatMediaCaptureMode.VoiceNote
        )
    }
}

private fun buildWavBytes(rawBytes: ByteArray, format: AudioFormat): ByteArray? {
    return runCatching {
        val frameLength = rawBytes.size / format.frameSize
        ByteArrayOutputStream().use { output ->
            AudioInputStream(ByteArrayInputStream(rawBytes), format, frameLength.toLong()).use { input ->
                AudioSystem.write(input, AudioFileFormat.Type.WAVE, output)
            }
            output.toByteArray()
        }
    }.getOrNull()
}

private fun nowMs(): Long = System.nanoTime() / 1_000_000L
