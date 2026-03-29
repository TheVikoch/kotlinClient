package com.messenger.client.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

enum class ChatMediaCaptureMode {
    VoiceNote,
    VideoNote
}

enum class ChatMediaCameraLens {
    Front,
    Back
}

enum class ChatMediaRecorderPhase {
    Idle,
    Recording,
    Paused,
    Finalizing
}

data class ChatRecordedAttachment(
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
    val previewBytes: ByteArray?,
    val durationMillis: Long,
    val kind: ChatMediaCaptureMode,
    val localPath: String?
)

data class ChatMediaRecorderUiState(
    val selectedMode: ChatMediaCaptureMode = ChatMediaCaptureMode.VoiceNote,
    val selectedCameraLens: ChatMediaCameraLens = ChatMediaCameraLens.Front,
    val phase: ChatMediaRecorderPhase = ChatMediaRecorderPhase.Idle,
    val durationMillis: Long = 0L,
    val isLocked: Boolean = false,
    val supportsVoiceNotes: Boolean = true,
    val supportsVideoNotes: Boolean = true
) {
    val isIdle: Boolean
        get() = phase == ChatMediaRecorderPhase.Idle

    val isRecording: Boolean
        get() = phase == ChatMediaRecorderPhase.Recording

    val isPaused: Boolean
        get() = phase == ChatMediaRecorderPhase.Paused

    val isFinalizing: Boolean
        get() = phase == ChatMediaRecorderPhase.Finalizing
}

interface ChatMediaRecorder {
    val uiState: StateFlow<ChatMediaRecorderUiState>

    fun toggleSelectedMode()

    fun switchCamera()

    fun beginCapture()

    fun lockCapture()

    fun pauseCapture()

    fun resumeCapture()

    fun finalizeCapture()

    fun discardCapture()
}

@Composable
expect fun rememberChatMediaRecorder(
    onDraftReady: (ChatRecordedAttachment) -> Unit,
    onError: (String) -> Unit
): ChatMediaRecorder

@Composable
expect fun ChatMediaRecorderPreview(
    recorder: ChatMediaRecorder,
    state: ChatMediaRecorderUiState,
    modifier: Modifier = Modifier
)
