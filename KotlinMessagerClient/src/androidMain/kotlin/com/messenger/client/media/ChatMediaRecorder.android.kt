package com.messenger.client.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.CamcorderProfile
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

@Composable
actual fun rememberChatMediaRecorder(
    onDraftReady: (ChatRecordedAttachment) -> Unit,
    onError: (String) -> Unit
): ChatMediaRecorder {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnDraftReady = rememberUpdatedState(onDraftReady)
    val latestOnError = rememberUpdatedState(onError)
    var handlePermissionResult by remember {
        mutableStateOf<(Map<String, Boolean>) -> Unit>({})
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        handlePermissionResult(result)
    }

    val recorder = remember(context.applicationContext, scope) {
        AndroidChatMediaRecorder(
            appContext = context.applicationContext,
            scope = scope,
            requestPermissions = { permissions -> permissionLauncher.launch(permissions) },
            onDraftReady = { draft -> latestOnDraftReady.value(draft) },
            onError = { message -> latestOnError.value(message) }
        )
    }

    DisposableEffect(recorder) {
        handlePermissionResult = recorder::onPermissionsResult
        onDispose {
            handlePermissionResult = {}
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
) {
    val androidRecorder = recorder as? AndroidChatMediaRecorder ?: return
    if (!state.supportsVideoNotes || state.selectedMode != ChatMediaCaptureMode.VideoNote) return

    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { context ->
            RecorderPreviewTextureView(context, androidRecorder)
        },
        update = { view ->
            view.bind(androidRecorder)
        }
    )

    LaunchedEffect(androidRecorder, state.selectedMode, state.selectedCameraLens, state.phase) {
        androidRecorder.refreshPreviewIfNeeded()
    }
}

private class AndroidChatMediaRecorder(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val requestPermissions: (Array<String>) -> Unit,
    private val onDraftReady: (ChatRecordedAttachment) -> Unit,
    private val onError: (String) -> Unit
) : ChatMediaRecorder {
    private val state = MutableStateFlow(
        ChatMediaRecorderUiState(
            supportsVoiceNotes = true,
            supportsVideoNotes = true
        )
    )
    private var recorder: MediaRecorder? = null
    private var camera: Camera? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private var previewWidth: Int = 0
    private var previewHeight: Int = 0
    private var fallbackPreviewTexture: SurfaceTexture? = null
    private var fallbackPreviewSurface: Surface? = null
    private var currentFile: File? = null
    private var currentMode: ChatMediaCaptureMode? = null
    private var timerJob: Job? = null
    private var accumulatedDurationMs = 0L
    private var activeStartedAtMs = 0L
    private var isAwaitingPermissionGrant = false

    override val uiState: StateFlow<ChatMediaRecorderUiState> = state

    fun attachPreviewTexture(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (previewTexture === surfaceTexture && previewSurface != null) return
        releasePreviewSurface()
        previewTexture = surfaceTexture
        previewWidth = width
        previewHeight = height
        previewSurface = Surface(surfaceTexture)
        previewTexture?.setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        refreshPreviewIfNeeded()
    }

    fun detachPreviewTexture(surfaceTexture: SurfaceTexture?) {
        if (surfaceTexture != null && previewTexture !== surfaceTexture) return
        if (!state.value.isIdle) {
            previewTexture = null
            previewWidth = 0
            previewHeight = 0
            return
        }
        releasePreviewSurface()
        releaseCamera()
    }

    fun refreshPreviewIfNeeded() {
        if (state.value.selectedMode != ChatMediaCaptureMode.VideoNote || !state.value.isIdle) return
        if (previewTexture == null) return
        ensurePreviewCamera()
    }

    override fun toggleSelectedMode() {
        state.update { current ->
            if (!current.isIdle) return@update current
            current.copy(
                selectedMode = if (current.selectedMode == ChatMediaCaptureMode.VoiceNote) {
                    ChatMediaCaptureMode.VideoNote
                } else {
                    ChatMediaCaptureMode.VoiceNote
                }
            )
        }
        if (state.value.selectedMode == ChatMediaCaptureMode.VideoNote) {
            refreshPreviewIfNeeded()
        } else if (state.value.isIdle) {
            releaseCamera()
        }
    }

    override fun switchCamera() {
        val currentState = state.value
        if (currentState.selectedMode != ChatMediaCaptureMode.VideoNote || currentState.isFinalizing) {
            return
        }

        val nextLens = if (currentState.selectedCameraLens == ChatMediaCameraLens.Front) {
            ChatMediaCameraLens.Back
        } else {
            ChatMediaCameraLens.Front
        }
        val shouldRestartCapture = !currentState.isIdle

        if (shouldRestartCapture) {
            cleanup(deleteFile = true)
        }

        state.update { current ->
            current.copy(
                selectedMode = ChatMediaCaptureMode.VideoNote,
                selectedCameraLens = nextLens
            )
        }

        if (shouldRestartCapture) {
            beginCapture()
        } else {
            releaseCamera()
            refreshPreviewIfNeeded()
        }
    }

    override fun beginCapture() {
        val currentState = state.value
        if (!currentState.isIdle) return

        val permissions = requiredPermissions(currentState.selectedMode)
        if (!permissions.all(::hasPermission)) {
            isAwaitingPermissionGrant = true
            requestPermissions(permissions)
            return
        }
        isAwaitingPermissionGrant = false

        val outputDir = File(appContext.filesDir, "captured-notes").apply { mkdirs() }
        val sessionId = System.currentTimeMillis().toString()
        val outputFile = File(
            outputDir,
            when (currentState.selectedMode) {
                ChatMediaCaptureMode.VoiceNote -> "voice-note-$sessionId.m4a"
                ChatMediaCaptureMode.VideoNote -> "video-note-$sessionId.mp4"
            }
        )

        runCatching {
            if (outputFile.exists()) {
                outputFile.delete()
            }
            when (currentState.selectedMode) {
                ChatMediaCaptureMode.VoiceNote -> startAudioCapture(outputFile)
                ChatMediaCaptureMode.VideoNote -> startVideoCapture(outputFile, currentState.selectedCameraLens)
            }
        }.onSuccess {
            currentFile = outputFile
            currentMode = currentState.selectedMode
            accumulatedDurationMs = 0L
            activeStartedAtMs = SystemClock.elapsedRealtime()
            state.update {
                it.copy(
                    phase = ChatMediaRecorderPhase.Recording,
                    durationMillis = 0L,
                    isLocked = false
                )
            }
            startTimer()
        }.onFailure { error ->
            cleanup(deleteFile = true)
            onError(error.message ?: "Не удалось начать запись")
        }
    }

    fun onPermissionsResult(result: Map<String, Boolean>) {
        if (!isAwaitingPermissionGrant) return
        isAwaitingPermissionGrant = false

        val allGranted = result.isNotEmpty() && result.values.all { it }
        if (!allGranted) {
            onError("Без разрешений запись недоступна")
            return
        }

        beginCapture()
    }

    override fun lockCapture() {
        state.update { current ->
            if (!current.isRecording) return@update current
            current.copy(isLocked = true)
        }
    }

    override fun pauseCapture() {
        val currentState = state.value
        if (!currentState.isRecording) return

        runCatching {
            recorder?.pause()
        }.onFailure { error ->
            onError(error.message ?: "Не удалось приостановить запись")
            return
        }

        accumulatedDurationMs = computeCurrentDuration()
        activeStartedAtMs = 0L
        stopTimer()
        state.update {
            it.copy(
                phase = ChatMediaRecorderPhase.Paused,
                durationMillis = accumulatedDurationMs,
                isLocked = false
            )
        }
    }

    override fun resumeCapture() {
        val currentState = state.value
        if (!currentState.isPaused) return

        runCatching {
            recorder?.resume()
        }.onFailure { error ->
            onError(error.message ?: "Не удалось возобновить запись")
            return
        }

        activeStartedAtMs = SystemClock.elapsedRealtime()
        state.update {
            it.copy(
                phase = ChatMediaRecorderPhase.Recording,
                isLocked = true
            )
        }
        startTimer()
    }

    override fun finalizeCapture() {
        val currentState = state.value
        if (currentState.isIdle || currentState.isFinalizing) return

        val file = currentFile
        val mode = currentMode
        if (file == null || mode == null) {
            cleanup(deleteFile = true)
            return
        }

        accumulatedDurationMs = computeCurrentDuration()
        stopTimer()
        state.update {
            it.copy(
                phase = ChatMediaRecorderPhase.Finalizing,
                durationMillis = accumulatedDurationMs,
                isLocked = false
            )
        }

        val stopResult = runCatching {
            recorder?.stop()
        }
        cleanupMediaRecorder(deleteFile = false)
        if (stopResult.isFailure) {
            cleanup(deleteFile = true)
            onError("Запись получилась слишком короткой или была прервана")
            return
        }

        scope.launch {
            val draft = withContext(Dispatchers.IO) {
                buildDraft(file = file, mode = mode, durationMillis = accumulatedDurationMs)
            }
            if (draft == null) {
                file.delete()
                cleanup(deleteFile = false)
                onError("Не удалось подготовить запись к отправке")
                return@launch
            }

            cleanup(deleteFile = false)
            onDraftReady(draft)
        }
    }

    override fun discardCapture() {
        cleanup(deleteFile = true)
    }

    fun dispose() {
        cleanup(deleteFile = true)
    }

    private fun startAudioCapture(outputFile: File) {
        releaseCamera()
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
    }

    private fun startVideoCapture(outputFile: File, lens: ChatMediaCameraLens) {
        val cameraId = resolveCameraId(lens)
        val profile = resolveCamcorderProfile(cameraId)
        val localCamera = camera ?: Camera.open(cameraId)
        configureCamera(localCamera, lens)
        val recordingSurface = obtainRecordingPreviewSurface(profile)

        runCatching { localCamera.stopPreview() }
        runCatching {
            previewTexture?.setDefaultBufferSize(profile.videoFrameWidth, profile.videoFrameHeight)
        }
        if (previewTexture != null) {
            localCamera.setPreviewTexture(previewTexture)
        } else {
            fallbackPreviewTexture?.setDefaultBufferSize(profile.videoFrameWidth, profile.videoFrameHeight)
            localCamera.setPreviewTexture(fallbackPreviewTexture)
        }
        localCamera.startPreview()
        localCamera.unlock()

        val localRecorder = MediaRecorder().apply {
            setCamera(localCamera)
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.CAMERA)
            setOutputFormat(profile.fileFormat)
            setVideoEncoder(profile.videoCodec)
            setAudioEncoder(profile.audioCodec)
            setVideoEncodingBitRate(profile.videoBitRate)
            setVideoFrameRate(profile.videoFrameRate)
            setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight)
            if (profile.audioBitRate > 0) {
                setAudioEncodingBitRate(profile.audioBitRate)
            }
            if (profile.audioSampleRate > 0) {
                setAudioSamplingRate(profile.audioSampleRate)
            }
            setOrientationHint(
                when (lens) {
                    ChatMediaCameraLens.Front -> 270
                    ChatMediaCameraLens.Back -> 90
                }
            )
            setPreviewDisplay(recordingSurface)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }

        camera = localCamera
        recorder = localRecorder
    }

    private fun startTimer() {
        stopTimer()
        timerJob = scope.launch {
            while (isActive) {
                state.update { current ->
                    if (!current.isRecording) {
                        return@update current
                    }
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
            SystemClock.elapsedRealtime() - activeStartedAtMs
        } else {
            0L
        }
        return (accumulatedDurationMs + runningPart).coerceAtLeast(0L)
    }

    private fun cleanup(deleteFile: Boolean) {
        stopTimer()
        cleanupMediaRecorder(deleteFile = deleteFile)
        accumulatedDurationMs = 0L
        activeStartedAtMs = 0L
        currentMode = null
        isAwaitingPermissionGrant = false
        state.value = state.value.copy(
            phase = ChatMediaRecorderPhase.Idle,
            durationMillis = 0L,
            isLocked = false
        )
        refreshPreviewIfNeeded()
    }

    private fun cleanupMediaRecorder(deleteFile: Boolean) {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null

        releaseCamera()
        releaseFallbackPreview()

        if (deleteFile) {
            currentFile?.delete()
        }
        currentFile = null
    }

    private fun requiredPermissions(mode: ChatMediaCaptureMode): Array<String> {
        return when (mode) {
            ChatMediaCaptureMode.VoiceNote -> arrayOf(Manifest.permission.RECORD_AUDIO)
            ChatMediaCaptureMode.VideoNote -> arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePreviewCamera() {
        if (state.value.selectedMode != ChatMediaCaptureMode.VideoNote || !state.value.isIdle) return
        if (previewTexture == null) return
        if (camera != null) return

        runCatching {
            val localCamera = Camera.open(resolveCameraId(state.value.selectedCameraLens))
            configureCamera(localCamera, state.value.selectedCameraLens)
            previewTexture?.setDefaultBufferSize(
                previewWidth.coerceAtLeast(1),
                previewHeight.coerceAtLeast(1)
            )
            localCamera.setPreviewTexture(previewTexture)
            localCamera.startPreview()
            camera = localCamera
        }.onFailure {
            releaseCamera()
        }
    }

    private fun configureCamera(localCamera: Camera, lens: ChatMediaCameraLens) {
        localCamera.setDisplayOrientation(
            when (lens) {
                ChatMediaCameraLens.Front -> 270
                ChatMediaCameraLens.Back -> 90
            }
        )
    }

    private fun obtainRecordingPreviewSurface(profile: CamcorderProfile): Surface {
        previewSurface?.let { surface ->
            previewTexture?.setDefaultBufferSize(profile.videoFrameWidth, profile.videoFrameHeight)
            return surface
        }

        releaseFallbackPreview()
        fallbackPreviewTexture = SurfaceTexture(false).apply {
            setDefaultBufferSize(profile.videoFrameWidth, profile.videoFrameHeight)
        }
        return Surface(fallbackPreviewTexture).also { createdSurface ->
            fallbackPreviewSurface = createdSurface
        }
    }

    private fun releaseCamera() {
        runCatching { camera?.stopPreview() }
        runCatching { camera?.lock() }
        runCatching { camera?.release() }
        camera = null
    }

    private fun releasePreviewSurface() {
        runCatching { previewSurface?.release() }
        previewSurface = null
        previewTexture = null
        previewWidth = 0
        previewHeight = 0
    }

    private fun releaseFallbackPreview() {
        runCatching { fallbackPreviewSurface?.release() }
        fallbackPreviewSurface = null
        runCatching { fallbackPreviewTexture?.release() }
        fallbackPreviewTexture = null
    }
}

private class RecorderPreviewTextureView(
    context: Context,
    private var recorder: AndroidChatMediaRecorder
) : TextureView(context), TextureView.SurfaceTextureListener {
    init {
        surfaceTextureListener = this
    }

    fun bind(updatedRecorder: AndroidChatMediaRecorder) {
        recorder = updatedRecorder
        if (isAvailable) {
            surfaceTexture?.let { texture ->
                recorder.attachPreviewTexture(texture, width, height)
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        recorder.attachPreviewTexture(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        recorder.attachPreviewTexture(surface, width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        recorder.detachPreviewTexture(surface)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}

private fun resolveCameraId(lens: ChatMediaCameraLens): Int {
    val desiredFacing = when (lens) {
        ChatMediaCameraLens.Front -> Camera.CameraInfo.CAMERA_FACING_FRONT
        ChatMediaCameraLens.Back -> Camera.CameraInfo.CAMERA_FACING_BACK
    }

    var fallbackId = 0
    for (cameraId in 0 until Camera.getNumberOfCameras()) {
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(cameraId, info)
        if (cameraId == 0) {
            fallbackId = cameraId
        }
        if (info.facing == desiredFacing) {
            return cameraId
        }
    }
    return fallbackId
}

private fun resolveCamcorderProfile(cameraId: Int): CamcorderProfile {
    val qualities = listOf(
        CamcorderProfile.QUALITY_480P,
        CamcorderProfile.QUALITY_LOW
    )
    val quality = qualities.firstOrNull { CamcorderProfile.hasProfile(cameraId, it) }
        ?: error("На устройстве нет доступного профиля записи видео")
    return CamcorderProfile.get(cameraId, quality)
}

private fun buildDraft(
    file: File,
    mode: ChatMediaCaptureMode,
    durationMillis: Long
): ChatRecordedAttachment? {
    if (!file.exists()) return null

    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    val timeSuffix = System.currentTimeMillis().toString()
    return when (mode) {
        ChatMediaCaptureMode.VoiceNote -> ChatRecordedAttachment(
            fileName = "voice-note-$timeSuffix.m4a",
            contentType = "audio/mp4",
            bytes = bytes,
            previewBytes = null,
            durationMillis = durationMillis,
            kind = ChatMediaCaptureMode.VoiceNote,
            localPath = file.absolutePath
        )
        ChatMediaCaptureMode.VideoNote -> ChatRecordedAttachment(
            fileName = "video-note-$timeSuffix.mp4",
            contentType = "video/mp4",
            bytes = bytes,
            previewBytes = buildVideoPreview(file),
            durationMillis = durationMillis,
            kind = ChatMediaCaptureMode.VideoNote,
            localPath = file.absolutePath
        )
    }
}

private fun buildVideoPreview(file: File): ByteArray? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        compressBitmap(frame)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun compressBitmap(bitmap: Bitmap): ByteArray? {
    return runCatching {
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output)
            output.toByteArray()
        }
    }.getOrNull()
}

private fun Long.toTimerLabel(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
