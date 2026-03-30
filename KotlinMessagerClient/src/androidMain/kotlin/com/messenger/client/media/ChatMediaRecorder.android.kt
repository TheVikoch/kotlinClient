package com.messenger.client.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.display.DisplayManager
import android.media.CamcorderProfile
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Transformer
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

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
    private var previewBufferWidth: Int = 0
    private var previewBufferHeight: Int = 0
    private var previewContentWidth: Int = 0
    private var previewContentHeight: Int = 0
    private var fallbackPreviewTexture: SurfaceTexture? = null
    private var fallbackPreviewSurface: Surface? = null
    private var currentFile: File? = null
    private var currentMode: ChatMediaCaptureMode? = null
    private var timerJob: Job? = null
    private var accumulatedDurationMs = 0L
    private var activeStartedAtMs = 0L
    private var isAwaitingPermissionGrant = false
    private var captureSessionId: String? = null
    private val completedVideoSegments = mutableListOf<File>()
    private val completedVideoSegmentRotations = mutableListOf<Int>()
    private var currentVideoSegmentRotationDegrees = 0
    private var normalizedVideoZoom = 0f
    private var zoomFocusJob: Job? = null
    private var preferredFocusMode: String? = null

    override val uiState: StateFlow<ChatMediaRecorderUiState> = state

    fun attachPreviewTexture(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (previewTexture === surfaceTexture && previewSurface != null) return
        releasePreviewSurface()
        previewTexture = surfaceTexture
        previewWidth = width
        previewHeight = height
        previewSurface = Surface(surfaceTexture)
        previewTexture?.setDefaultBufferSize(
            previewBufferWidth.takeIf { it > 0 } ?: width.coerceAtLeast(1),
            previewBufferHeight.takeIf { it > 0 } ?: height.coerceAtLeast(1)
        )
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
        val currentState = state.value
        if (currentState.selectedMode != ChatMediaCaptureMode.VideoNote) return
        if (!(currentState.isIdle || currentState.isPaused) || currentState.isFinalizing) return
        if (recorder != null) return
        if (previewTexture == null) return
        ensurePreviewCamera()
    }

    fun currentPreviewContentSize(): Pair<Int, Int>? {
        val width = previewContentWidth
        val height = previewContentHeight
        return if (width > 0 && height > 0) width to height else null
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
        val wasRecording = currentState.isRecording
        val wasPaused = currentState.isPaused

        state.update { current ->
            current.copy(
                selectedMode = ChatMediaCaptureMode.VideoNote,
                selectedCameraLens = nextLens
            )
        }
        normalizedVideoZoom = 0f

        if (currentState.isIdle) {
            releaseCamera()
            refreshPreviewIfNeeded()
            return
        }

        if (currentMode != ChatMediaCaptureMode.VideoNote) return

        if (wasRecording) {
            accumulatedDurationMs = computeCurrentDuration()
            activeStartedAtMs = 0L
            stopTimer()
        }

        val segmentStopped = if (recorder != null) {
            stopActiveVideoSegment(storeSegment = true)
        } else {
            true
        }
        if (!segmentStopped) {
            cleanup(deleteFile = true)
            onError("Не удалось переключить камеру во время записи кружка")
            return
        }

        if (wasPaused) {
            state.update { current ->
                current.copy(
                    phase = ChatMediaRecorderPhase.Paused,
                    durationMillis = accumulatedDurationMs,
                    isLocked = false
                )
            }
            refreshPreviewIfNeeded()
            return
        }

        val outputFile = createNextVideoSegmentFile()
        runCatching {
            startVideoCapture(outputFile, nextLens)
        }.onSuccess {
            currentFile = outputFile
            activeStartedAtMs = SystemClock.elapsedRealtime()
            state.update { current ->
                current.copy(
                    phase = ChatMediaRecorderPhase.Recording,
                    durationMillis = accumulatedDurationMs,
                    isLocked = current.isLocked
                )
            }
            startTimer()
        }.onFailure { error ->
            state.update { current ->
                current.copy(
                    phase = ChatMediaRecorderPhase.Paused,
                    durationMillis = accumulatedDurationMs,
                    isLocked = false
                )
            }
            refreshPreviewIfNeeded()
            onError(error.message ?: "Не удалось продолжить запись после смены камеры")
        }
    }

    override fun setVideoZoom(normalizedZoom: Float) {
        if (state.value.selectedMode != ChatMediaCaptureMode.VideoNote) return
        normalizedVideoZoom = normalizedZoom.coerceIn(0f, 1f)
        camera?.let(::applyVideoZoom)
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
        val outputFile = when (currentState.selectedMode) {
            ChatMediaCaptureMode.VoiceNote -> File(
                outputDir,
                "voice-note-${System.currentTimeMillis()}.m4a"
            )
            ChatMediaCaptureMode.VideoNote -> {
                resetVideoSession()
                createNextVideoSegmentFile(outputDir)
            }
        }

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

        if (currentState.selectedMode == ChatMediaCaptureMode.VideoNote) {
            accumulatedDurationMs = computeCurrentDuration()
            activeStartedAtMs = 0L
            stopTimer()

            if (!stopActiveVideoSegment(storeSegment = true)) {
                cleanup(deleteFile = true)
                onError("Не удалось приостановить запись кружка")
                return
            }

            state.update {
                it.copy(
                    phase = ChatMediaRecorderPhase.Paused,
                    durationMillis = accumulatedDurationMs,
                    isLocked = false
                )
            }
            refreshPreviewIfNeeded()
            return
        }

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

        if (currentState.selectedMode == ChatMediaCaptureMode.VideoNote && recorder == null) {
            val outputFile = createNextVideoSegmentFile()
            runCatching {
                startVideoCapture(outputFile, currentState.selectedCameraLens)
            }.onSuccess {
                currentFile = outputFile
                activeStartedAtMs = SystemClock.elapsedRealtime()
                state.update {
                    it.copy(
                        phase = ChatMediaRecorderPhase.Recording,
                        isLocked = true
                    )
                }
                startTimer()
            }.onFailure { error ->
                onError(error.message ?: "Не удалось возобновить запись кружка")
            }
            return
        }

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

        if (currentMode == ChatMediaCaptureMode.VideoNote) {
            finalizeVideoNoteCapture()
            return
        }

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
        val cameraInfo = resolveCameraInfo(cameraId)
        val profile = resolveCamcorderProfile(cameraId)
        val localCamera = camera ?: Camera.open(cameraId)
        configureCamera(localCamera, cameraInfo, profile)
        val recordingSurface = obtainRecordingPreviewSurface(profile)
        val displayRotationDegrees = appContext.currentDisplayRotationDegrees()

        runCatching { localCamera.stopPreview() }
        runCatching {
            previewTexture?.setDefaultBufferSize(
                previewBufferWidth.takeIf { it > 0 } ?: profile.videoFrameWidth,
                previewBufferHeight.takeIf { it > 0 } ?: profile.videoFrameHeight
            )
        }
        if (previewTexture != null) {
            localCamera.setPreviewTexture(previewTexture)
        } else {
            fallbackPreviewTexture?.setDefaultBufferSize(profile.videoFrameWidth, profile.videoFrameHeight)
            localCamera.setPreviewTexture(fallbackPreviewTexture)
        }
        localCamera.startPreview()
        localCamera.unlock()
        val orientationHint = resolveVideoOrientationHint(cameraInfo, displayRotationDegrees)

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
            setOrientationHint(orientationHint)
            setPreviewDisplay(recordingSurface)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }

        camera = localCamera
        recorder = localRecorder
        currentVideoSegmentRotationDegrees = orientationHint.normalizeRotationDegrees()
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

    private fun finalizeVideoNoteCapture() {
        accumulatedDurationMs = computeCurrentDuration()
        stopTimer()
        state.update {
            it.copy(
                phase = ChatMediaRecorderPhase.Finalizing,
                durationMillis = accumulatedDurationMs,
                isLocked = false
            )
        }

        if (recorder != null && !stopActiveVideoSegment(storeSegment = true)) {
            cleanup(deleteFile = true)
            onError("Запись получилась слишком короткой или была прервана")
            return
        }

        val segmentFiles = completedVideoSegments.toList()
        val segmentRotations = completedVideoSegmentRotations.toList()
        if (segmentFiles.isEmpty()) {
            cleanup(deleteFile = true)
            onError("Не удалось сохранить кружок")
            return
        }

        scope.launch {
            val finalVideoFile = withContext(Dispatchers.IO) {
                prepareFinalVideoNoteFile(segmentFiles, segmentRotations)
            }
            if (finalVideoFile == null) {
                cleanup(deleteFile = true)
                onError("Не удалось подготовить кружок к отправке")
                return@launch
            }

            val draft = withContext(Dispatchers.IO) {
                buildDraft(
                    file = finalVideoFile,
                    mode = ChatMediaCaptureMode.VideoNote,
                    durationMillis = accumulatedDurationMs
                )
            }
            if (draft == null) {
                if (finalVideoFile !in segmentFiles) {
                    finalVideoFile.delete()
                }
                cleanup(deleteFile = true)
                onError("Не удалось подготовить запись к отправке")
                return@launch
            }

            if (finalVideoFile !in segmentFiles) {
                segmentFiles.forEach(File::delete)
            }

            cleanup(deleteFile = false)
            onDraftReady(draft)
        }
    }

    private fun stopActiveVideoSegment(storeSegment: Boolean): Boolean {
        val segmentFile = currentFile
        val stopResult = runCatching { recorder?.stop() }
        cleanupMediaRecorder(deleteFile = false)

        val readyFile = segmentFile?.takeIf { it.exists() && it.length() > 0L }
        if (stopResult.isFailure || readyFile == null) {
            segmentFile?.delete()
            return false
        }

        if (storeSegment) {
            completedVideoSegments += readyFile
            completedVideoSegmentRotations += currentVideoSegmentRotationDegrees.normalizeRotationDegrees()
        } else {
            readyFile.delete()
        }
        currentVideoSegmentRotationDegrees = 0
        return true
    }

    private fun resetVideoSession() {
        completedVideoSegments.forEach(File::delete)
        completedVideoSegments.clear()
        completedVideoSegmentRotations.clear()
        currentVideoSegmentRotationDegrees = 0
        captureSessionId = System.currentTimeMillis().toString()
        normalizedVideoZoom = 0f
    }

    private fun createNextVideoSegmentFile(
        outputDir: File = File(appContext.filesDir, "captured-notes").apply { mkdirs() }
    ): File {
        val sessionId = captureSessionId ?: System.currentTimeMillis().toString().also {
            captureSessionId = it
        }
        val segmentIndex = completedVideoSegments.size + 1
        return File(outputDir, "video-note-$sessionId-segment-$segmentIndex.mp4")
    }

    private suspend fun prepareFinalVideoNoteFile(
        segmentFiles: List<File>,
        segmentRotations: List<Int>
    ): File? {
        if (segmentFiles.isEmpty()) return null

        val outputDir = File(appContext.filesDir, "captured-notes").apply { mkdirs() }
        val sessionId = captureSessionId ?: System.currentTimeMillis().toString()
        if (segmentFiles.size == 1) {
            return normalizeVideoFileOrientationIfNeeded(
                inputFile = segmentFiles.first(),
                clockwiseRotationDegrees = segmentRotations.firstOrNull(),
                outputFile = File(outputDir, "video-note-$sessionId-normalized-final.mp4").apply {
                    if (exists()) {
                        delete()
                    }
                }
            )
        }

        return mergeVideoSegments(
            segmentFiles = segmentFiles,
            segmentRotationHints = segmentRotations,
            outputDir = outputDir,
            sessionId = sessionId
        )
    }

    private suspend fun mergeVideoSegments(
        segmentFiles: List<File>,
        segmentRotationHints: List<Int>,
        outputDir: File,
        sessionId: String
    ): File? {
        if (segmentFiles.isEmpty()) return null

        val mergedFile = File(outputDir, "video-note-$sessionId.mp4")
        if (mergedFile.exists()) {
            mergedFile.delete()
        }

        val segmentRotations = segmentFiles.mapIndexed { index, file ->
            segmentRotationHints.getOrNull(index)
                ?.normalizeRotationDegrees()
                ?: readVideoRotation(file)?.normalizeRotationDegrees()
                ?: 0
        }
        val normalizedSegments = normalizeVideoSegmentsForMerge(
            segmentFiles = segmentFiles,
            segmentRotations = segmentRotations,
            outputDir = outputDir,
            sessionId = sessionId
        ) ?: return null
        val generatedNormalizedSegments = normalizedSegments.filter { it !in segmentFiles }

        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var videoOffsetUs = 0L
        var audioOffsetUs = 0L
        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)

        return try {
            muxer = MediaMuxer(
                mergedFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            normalizedSegments.forEach { file ->
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                try {
                    for (trackIndex in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(trackIndex)
                        val mime = format.getString("mime").orEmpty()
                        if (mime.startsWith("video/") && videoTrackIndex == -1) {
                            videoTrackIndex = muxer!!.addTrack(format)
                        } else if (mime.startsWith("audio/") && audioTrackIndex == -1) {
                            audioTrackIndex = muxer!!.addTrack(format)
                        }
                    }

                    if (!muxerStarted) {
                        if (videoTrackIndex == -1 && audioTrackIndex == -1) {
                            return null
                        }
                        muxer!!.start()
                        muxerStarted = true
                    }

                    for (trackIndex in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(trackIndex)
                        val mime = format.getString("mime").orEmpty()
                        val muxerTrack = when {
                            mime.startsWith("video/") -> videoTrackIndex
                            mime.startsWith("audio/") -> audioTrackIndex
                            else -> -1
                        }
                        if (muxerTrack == -1) continue

                        extractor.selectTrack(trackIndex)
                        val baseOffsetUs = if (mime.startsWith("video/")) {
                            videoOffsetUs
                        } else {
                            audioOffsetUs
                        }
                        var maxSampleTimeUs = 0L

                        while (true) {
                            val sampleSize = extractor.readSampleData(buffer, 0)
                            if (sampleSize < 0) break

                            val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                            val info = MediaCodec.BufferInfo().apply {
                                offset = 0
                                size = sampleSize
                                presentationTimeUs = baseOffsetUs + sampleTimeUs
                                flags = extractor.sampleFlags
                            }
                            muxer!!.writeSampleData(muxerTrack, buffer, info)
                            maxSampleTimeUs = max(maxSampleTimeUs, sampleTimeUs)
                            extractor.advance()
                        }

                        extractor.unselectTrack(trackIndex)
                        if (mime.startsWith("video/")) {
                            videoOffsetUs += max(maxSampleTimeUs, 33_000L)
                        } else {
                            audioOffsetUs += max(maxSampleTimeUs, 23_000L)
                        }
                    }
                } finally {
                    runCatching { extractor.release() }
                }
            }

            mergedFile.takeIf { it.exists() && it.length() > 0L }
        } catch (_: Exception) {
            mergedFile.delete()
            null
        } finally {
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            generatedNormalizedSegments.forEach(File::delete)
        }
    }

    private suspend fun normalizeVideoFileOrientationIfNeeded(
        inputFile: File,
        clockwiseRotationDegrees: Int?,
        outputFile: File
    ): File? {
        val rotationDegrees = clockwiseRotationDegrees
            ?.normalizeRotationDegrees()
            ?.takeIf { it != 0 }
            ?: readVideoRotation(inputFile)?.normalizeRotationDegrees()
            ?: 0
        return if (rotationDegrees == 0) {
            inputFile
        } else {
            normalizeVideoSegmentForMerge(
                inputFile = inputFile,
                clockwiseRotationDegrees = rotationDegrees,
                outputFile = outputFile
            )
        }
    }

    private suspend fun normalizeVideoSegmentsForMerge(
        segmentFiles: List<File>,
        segmentRotations: List<Int>,
        outputDir: File,
        sessionId: String
    ): List<File>? {
        val generatedFiles = mutableListOf<File>()
        return try {
            segmentFiles.mapIndexed { index, segmentFile ->
                val rotationDegrees = segmentRotations.getOrElse(index) { 0 }.normalizeRotationDegrees()
                if (rotationDegrees == 0) {
                    segmentFile
                } else {
                    val normalizedFile = File(
                        outputDir,
                        "video-note-$sessionId-normalized-${index + 1}.mp4"
                    ).apply {
                        if (exists()) {
                            delete()
                        }
                    }
                    val exportedFile = normalizeVideoSegmentForMerge(
                        inputFile = segmentFile,
                        clockwiseRotationDegrees = rotationDegrees,
                        outputFile = normalizedFile
                    ) ?: return null
                    generatedFiles += exportedFile
                    exportedFile
                }
            }
        } catch (_: Exception) {
            generatedFiles.forEach(File::delete)
            null
        }
    }

    private suspend fun normalizeVideoSegmentForMerge(
        inputFile: File,
        clockwiseRotationDegrees: Int,
        outputFile: File
    ): File? {
        val effectRotationDegrees = ((360 - clockwiseRotationDegrees.normalizeRotationDegrees()) % 360)
            .toFloat()
        if (effectRotationDegrees == 0f) {
            return inputFile
        }

        return runCatching {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val transformer = Transformer.Builder(appContext)
                        .addListener(
                            object : Transformer.Listener {
                                override fun onCompleted(
                                    composition: Composition,
                                    exportResult: ExportResult
                                ) {
                                    if (continuation.isActive) {
                                        continuation.resume(outputFile)
                                    }
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException
                                ) {
                                    if (continuation.isActive) {
                                        continuation.resume(null)
                                    }
                                }
                            }
                        )
                        .build()

                    continuation.invokeOnCancellation {
                        runCatching { transformer.cancel() }
                        outputFile.delete()
                    }

                    val effects = Effects(
                        emptyList(),
                        listOf(
                            ScaleAndRotateTransformation.Builder()
                                .setRotationDegrees(effectRotationDegrees)
                                .build()
                        )
                    )
                    val editedMediaItem = EditedMediaItem.Builder(
                        MediaItem.fromUri(inputFile.absolutePath)
                    )
                        .setEffects(effects)
                        .build()

                    transformer.start(editedMediaItem, outputFile.absolutePath)
                }
            }
        }.getOrNull()
    }

    private fun readVideoRotation(file: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
                ?: readVideoRotationFromExtractor(file)
        } catch (_: Exception) {
            readVideoRotationFromExtractor(file)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readVideoRotationFromExtractor(file: File): Int? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount)
                .asSequence()
                .map { trackIndex -> extractor.getTrackFormat(trackIndex) }
                .firstNotNullOfOrNull { format ->
                    val mime = format.getString("mime").orEmpty()
                    if (!mime.startsWith("video/")) {
                        return@firstNotNullOfOrNull null
                    }
                    when {
                        format.containsKey("rotation-degrees") -> format.getInteger("rotation-degrees")
                        else -> null
                    }
                }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun cleanup(deleteFile: Boolean) {
        stopTimer()
        zoomFocusJob?.cancel()
        zoomFocusJob = null
        cleanupMediaRecorder(deleteFile = deleteFile)
        accumulatedDurationMs = 0L
        activeStartedAtMs = 0L
        currentMode = null
        isAwaitingPermissionGrant = false
        normalizedVideoZoom = 0f
        preferredFocusMode = null
        if (deleteFile) {
            completedVideoSegments.forEach(File::delete)
        }
        completedVideoSegments.clear()
        completedVideoSegmentRotations.clear()
        currentVideoSegmentRotationDegrees = 0
        captureSessionId = null
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
        val currentState = state.value
        if (currentState.selectedMode != ChatMediaCaptureMode.VideoNote) return
        if (!(currentState.isIdle || currentState.isPaused) || currentState.isFinalizing) return
        if (recorder != null) return
        if (previewTexture == null) return
        if (camera != null) return

        runCatching {
            val cameraId = resolveCameraId(currentState.selectedCameraLens)
            val localCamera = Camera.open(cameraId)
            configureCamera(
                localCamera = localCamera,
                cameraInfo = resolveCameraInfo(cameraId),
                profile = resolveCamcorderProfile(cameraId)
            )
            previewTexture?.setDefaultBufferSize(
                previewBufferWidth.takeIf { it > 0 } ?: previewWidth.coerceAtLeast(1),
                previewBufferHeight.takeIf { it > 0 } ?: previewHeight.coerceAtLeast(1)
            )
            localCamera.setPreviewTexture(previewTexture)
            localCamera.startPreview()
            camera = localCamera
        }.onFailure {
            releaseCamera()
        }
    }

    private fun configureCamera(
        localCamera: Camera,
        cameraInfo: Camera.CameraInfo,
        profile: CamcorderProfile
    ) {
        val previewOrientation = resolvePreviewDisplayOrientation(
            cameraInfo = cameraInfo,
            displayRotationDegrees = appContext.currentDisplayRotationDegrees()
        )
        localCamera.setDisplayOrientation(previewOrientation)
        runCatching {
            val parameters = localCamera.parameters
            val previewSize = choosePreviewSize(
                supportedSizes = parameters.supportedPreviewSizes.orEmpty(),
                targetWidth = profile.videoFrameWidth,
                targetHeight = profile.videoFrameHeight
            ) ?: parameters.previewSize
            if (previewSize != null) {
                parameters.setPreviewSize(previewSize.width, previewSize.height)
                previewBufferWidth = previewSize.width
                previewBufferHeight = previewSize.height
                if (previewOrientation == 90 || previewOrientation == 270) {
                    previewContentWidth = previewSize.height
                    previewContentHeight = previewSize.width
                } else {
                    previewContentWidth = previewSize.width
                    previewContentHeight = previewSize.height
                }
            }
            runCatching { parameters.setRecordingHint(true) }
            applyPreferredFocusMode(parameters)
            runCatching {
                if (parameters.isVideoStabilizationSupported) {
                    parameters.videoStabilization = true
                }
            }
            localCamera.parameters = parameters
        }.onFailure {
            previewBufferWidth = profile.videoFrameWidth
            previewBufferHeight = profile.videoFrameHeight
            if (previewOrientation == 90 || previewOrientation == 270) {
                previewContentWidth = profile.videoFrameHeight
                previewContentHeight = profile.videoFrameWidth
            } else {
                previewContentWidth = profile.videoFrameWidth
                previewContentHeight = profile.videoFrameHeight
            }
        }
        applyVideoZoom(localCamera)
    }

    private fun applyVideoZoom(localCamera: Camera) {
        runCatching {
            val parameters = localCamera.parameters
            applyPreferredFocusMode(parameters)
            if (!parameters.isZoomSupported || parameters.maxZoom <= 0) {
                normalizedVideoZoom = 0f
                return
            }
            val targetZoom = (parameters.maxZoom * normalizedVideoZoom)
                .roundToInt()
                .coerceIn(0, parameters.maxZoom)
            val zoomChanged = parameters.zoom != targetZoom
            parameters.zoom = targetZoom
            localCamera.parameters = parameters
            if (zoomChanged) {
                scheduleFocusRefreshAfterZoom(localCamera)
            }
        }
    }

    private fun applyPreferredFocusMode(parameters: Camera.Parameters) {
        val supportedFocusModes = parameters.supportedFocusModes.orEmpty()
        preferredFocusMode = choosePreferredFocusMode(supportedFocusModes)
        preferredFocusMode
            ?.takeIf { it in supportedFocusModes }
            ?.let { focusMode ->
                parameters.focusMode = focusMode
            }
    }

    private fun scheduleFocusRefreshAfterZoom(localCamera: Camera) {
        zoomFocusJob?.cancel()
        zoomFocusJob = scope.launch(Dispatchers.Main) {
            delay(140L)
            if (camera !== localCamera) return@launch
            refreshFocusAfterZoom(localCamera)
        }
    }

    private fun refreshFocusAfterZoom(localCamera: Camera) {
        if (camera !== localCamera) return

        val supportedFocusModes = runCatching {
            localCamera.parameters.supportedFocusModes.orEmpty()
        }.getOrDefault(emptyList())
        val continuousFocusMode = chooseContinuousFocusMode(supportedFocusModes)

        when {
            Camera.Parameters.FOCUS_MODE_AUTO in supportedFocusModes -> {
                runCatching { localCamera.cancelAutoFocus() }
                runCatching {
                    val parameters = localCamera.parameters
                    if (parameters.focusMode != Camera.Parameters.FOCUS_MODE_AUTO &&
                        Camera.Parameters.FOCUS_MODE_AUTO in parameters.supportedFocusModes.orEmpty()
                    ) {
                        parameters.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
                        localCamera.parameters = parameters
                    }
                    localCamera.autoFocus { _, _ ->
                        if (camera !== localCamera) return@autoFocus
                        restorePreferredFocusMode(localCamera, fallbackMode = continuousFocusMode)
                    }
                }.onFailure {
                    restorePreferredFocusMode(localCamera, fallbackMode = continuousFocusMode)
                }
            }

            continuousFocusMode != null -> {
                restorePreferredFocusMode(localCamera, fallbackMode = continuousFocusMode)
                runCatching { localCamera.cancelAutoFocus() }
            }
        }
    }

    private fun restorePreferredFocusMode(localCamera: Camera, fallbackMode: String? = null) {
        val focusMode = preferredFocusMode ?: fallbackMode ?: return
        runCatching {
            val parameters = localCamera.parameters
            val supportedFocusModes = parameters.supportedFocusModes.orEmpty()
            if (focusMode in supportedFocusModes && parameters.focusMode != focusMode) {
                parameters.focusMode = focusMode
                localCamera.parameters = parameters
            }
        }
    }

    private fun obtainRecordingPreviewSurface(profile: CamcorderProfile): Surface {
        previewSurface?.let { surface ->
            previewTexture?.setDefaultBufferSize(
                previewBufferWidth.takeIf { it > 0 } ?: profile.videoFrameWidth,
                previewBufferHeight.takeIf { it > 0 } ?: profile.videoFrameHeight
            )
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
        zoomFocusJob?.cancel()
        zoomFocusJob = null
        runCatching { camera?.stopPreview() }
        runCatching { camera?.lock() }
        runCatching { camera?.release() }
        camera = null
        preferredFocusMode = null
        previewBufferWidth = 0
        previewBufferHeight = 0
        previewContentWidth = 0
        previewContentHeight = 0
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
        applyPreviewTransform()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        recorder.attachPreviewTexture(surface, width, height)
        applyPreviewTransform()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        recorder.attachPreviewTexture(surface, width, height)
        applyPreviewTransform()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        recorder.detachPreviewTexture(surface)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        applyPreviewTransform()
    }

    private fun applyPreviewTransform() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val contentSize = recorder.currentPreviewContentSize()
        if (contentSize == null) {
            setTransform(Matrix())
            return
        }

        val contentAspect = contentSize.first.toFloat() / contentSize.second.toFloat()
        val viewAspect = viewWidth / viewHeight
        val scaleX: Float
        val scaleY: Float
        if (contentAspect > viewAspect) {
            scaleX = contentAspect / viewAspect
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = viewAspect / contentAspect
        }

        setTransform(
            Matrix().apply {
                setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
            }
        )
    }
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

private fun resolveCameraInfo(cameraId: Int): Camera.CameraInfo {
    return Camera.CameraInfo().also { info ->
        Camera.getCameraInfo(cameraId, info)
    }
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

private fun choosePreviewSize(
    supportedSizes: List<Camera.Size>,
    targetWidth: Int,
    targetHeight: Int
): Camera.Size? {
    if (supportedSizes.isEmpty()) return null

    val normalizedTargetWidth = max(targetWidth, targetHeight)
    val normalizedTargetHeight = minOf(targetWidth, targetHeight).coerceAtLeast(1)
    val targetAspect = normalizedTargetWidth.toFloat() / normalizedTargetHeight.toFloat()
    val targetArea = normalizedTargetWidth * normalizedTargetHeight

    return supportedSizes.minByOrNull { size ->
        val normalizedWidth = max(size.width, size.height)
        val normalizedHeight = minOf(size.width, size.height).coerceAtLeast(1)
        val aspectDelta = abs((normalizedWidth.toFloat() / normalizedHeight.toFloat()) - targetAspect)
        val areaDelta = abs((size.width * size.height) - targetArea).toFloat() / targetArea.toFloat()
        aspectDelta * 10f + areaDelta
    }
}

private fun choosePreferredFocusMode(supportedFocusModes: List<String>): String? {
    return when {
        Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO in supportedFocusModes -> {
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
        }

        Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in supportedFocusModes -> {
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
        }

        Camera.Parameters.FOCUS_MODE_AUTO in supportedFocusModes -> {
            Camera.Parameters.FOCUS_MODE_AUTO
        }

        else -> null
    }
}

private fun chooseContinuousFocusMode(supportedFocusModes: List<String>): String? {
    return when {
        Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO in supportedFocusModes -> {
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
        }

        Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in supportedFocusModes -> {
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
        }

        else -> null
    }
}

private fun resolvePreviewDisplayOrientation(
    cameraInfo: Camera.CameraInfo,
    displayRotationDegrees: Int
): Int {
    return if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
        (360 - ((cameraInfo.orientation + displayRotationDegrees) % 360)) % 360
    } else {
        (cameraInfo.orientation - displayRotationDegrees + 360) % 360
    }
}

private fun resolveVideoOrientationHint(
    cameraInfo: Camera.CameraInfo,
    displayRotationDegrees: Int
): Int {
    return if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
        (cameraInfo.orientation + displayRotationDegrees) % 360
    } else {
        (cameraInfo.orientation - displayRotationDegrees + 360) % 360
    }
}

private fun Context.currentDisplayRotationDegrees(): Int {
    val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    val rotation = displayManager
        ?.displays
        ?.firstOrNull()
        ?.rotation
        ?: Surface.ROTATION_0
    return when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
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
        val rotationDegrees = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()
            ?: 0
        val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        compressBitmap(rotateBitmapIfNeeded(frame, rotationDegrees))
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

private fun rotateBitmapIfNeeded(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
    val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
    if (normalizedRotation == 0) return bitmap
    return runCatching {
        Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postRotate(normalizedRotation.toFloat()) },
            true
        )
    }.getOrDefault(bitmap)
}

private fun Int.normalizeRotationDegrees(): Int = ((this % 360) + 360) % 360

private fun Long.toTimerLabel(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
