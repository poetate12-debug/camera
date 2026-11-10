package id.reang.camera

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ExperimentalCameraInfo
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService

class RecordingService : LifecycleService() {
    companion object {
        const val CHANNEL_ID = "camera_recording"
        const val NOTIFICATION_ID = 2501

        const val ACTION_START_RECORDING = "id.reang.camera.START_RECORDING"
        const val ACTION_STOP_RECORDING = "id.reang.camera.STOP_RECORDING"
        const val ACTION_STATE_CHANGED = "id.reang.camera.RECORDING_STATE_CHANGED"
        const val ACTION_ENABLE_FLOATING = "id.reang.camera.ENABLE_FLOATING"
        const val ACTION_DISABLE_FLOATING = "id.reang.camera.DISABLE_FLOATING"
        const val ACTION_SWITCH_CAMERA = "id.reang.camera.SWITCH_CAMERA"
        const val ACTION_REFRESH_CAMERA = "id.reang.camera.REFRESH_CAMERA"

        const val EXTRA_LENS_FACING = "lens_facing"
        const val EXTRA_IS_RECORDING = "is_recording"
        const val EXTRA_STATUS = "status"

        private const val PREFS_NAME = "recording_state"
        private const val PREF_IS_RECORDING = "is_recording"
        private const val PREF_STATUS = "status_text"
        private const val PREF_STARTED_AT = "recording_started_at"
        private const val CAMERA_PREFS = "camera_settings"
        private const val PREF_LENS_FACING = "lens_facing"
        private const val PREF_QUALITY = "video_quality"
        private const val PREF_STABILIZATION = "video_stabilization"
        private const val PREF_PREVIEW_ENABLED = "floating_preview_enabled"
        private const val PREF_DUAL_CAMERA = "dual_camera_enabled"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var activeRecording: androidx.camera.video.Recording? = null
    private val activeDualRecordings = mutableListOf<androidx.camera.video.Recording>()
    private var dualFinalizeCount = 0
    private var activePreview: Preview? = null
    private var startInProgress = false
    private var stopRequested = false
    private var overlayEnabled = false
    private var disableOverlayAfterFinalize = false

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayBubble: Button? = null
    private var overlayControls: View? = null
    private var overlayPreview: PreviewView? = null
    private var overlayStatus: TextView? = null
    private var overlayCameraLabel: TextView? = null
    private var overlayQualityLabel: TextView? = null
    private var overlayRecButton: Button? = null
    private var overlayStopButton: Button? = null
    private var overlaySwitchButton: Button? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_ENABLE_FLOATING -> enableFloatingControls()
            ACTION_DISABLE_FLOATING -> {
                if (activeRecording != null || activeDualRecordings.isNotEmpty() || startInProgress) {
                    disableOverlayAfterFinalize = true
                    if (activeDualRecordings.isNotEmpty()) activeDualRecordings.toList().forEach { it.stop() }
                    else activeRecording?.stop()
                    setOverlayStatus("Menghentikan rekaman sebelum menutup panel")
                } else {
                    disableFloatingControls()
                }
            }
            ACTION_SWITCH_CAMERA -> switchCameraFromOverlay()
            ACTION_REFRESH_CAMERA -> {
                updateOverlayUi(activeRecording != null || startInProgress, "Pengaturan kamera diperbarui")
                refreshPreviewBinding()
            }
            ACTION_STOP_RECORDING -> {
                stopRequested = true
                val currentRecording = activeRecording
                if (activeDualRecordings.isNotEmpty()) {
                    activeDualRecordings.toList().forEach { it.stop() }
                } else if (currentRecording != null) {
                    currentRecording.stop()
                } else if (!startInProgress && !overlayEnabled) {
                    finishService("Rekaman dihentikan")
                }
            }
            ACTION_START_RECORDING -> {
                if (startInProgress || activeRecording != null) return START_NOT_STICKY
                stopRequested = false
                startInProgress = true
                startForegroundCompat("Menyiapkan kamera untuk merekam")
                publishState(true, "Menyiapkan kamera untuk merekam...")
                val lensFacing = intent?.getIntExtra(
                    EXTRA_LENS_FACING,
                    selectedLensFacing()
                ) ?: selectedLensFacing()
                prepareCameraAndStartRecording(lensFacing)
            }
        }
        return START_NOT_STICKY
    }

    private fun enableFloatingControls() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        overlayEnabled = true
        disableOverlayAfterFinalize = false
        startForegroundCompat("Kontrol kamera mengambang aktif")
        addFloatingOverlay()
        if (isPreviewEnabled()) bindPreviewOnly()
        publishState(false, "Kontrol mengambang aktif • belum merekam")
    }

    private fun disableFloatingControls() {
        overlayEnabled = false
        removeFloatingOverlay()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(message: String, includeMicrophone: Boolean = true) {
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                (if (includeMicrophone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            startForeground(NOTIFICATION_ID, notification, serviceTypes)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun selectedLensFacing(): Int =
        getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE)
            .getInt(PREF_LENS_FACING, CameraSelector.LENS_FACING_BACK)

    private fun selectedQuality(): Quality =
        when (getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE).getString(PREF_QUALITY, "FHD")) {
            "UHD" -> Quality.UHD
            "HD" -> Quality.HD
            "SD" -> Quality.SD
            else -> Quality.FHD
        }

    private fun isPreviewEnabled(): Boolean = getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE).getBoolean(PREF_PREVIEW_ENABLED, false)

    private fun isDualCameraEnabled(): Boolean = getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE).getBoolean(PREF_DUAL_CAMERA, false)

    private fun isStabilizationEnabled(): Boolean =
        getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE)
            .getBoolean(PREF_STABILIZATION, true)

    private fun cameraName(facing: Int = selectedLensFacing()): String =
        if (facing == CameraSelector.LENS_FACING_FRONT) "KAMERA DEPAN" else "KAMERA BELAKANG"

    private fun qualityName(): String =
        when (getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE).getString(PREF_QUALITY, "FHD")) {
            "UHD" -> "4K"
            "HD" -> "720p"
            "SD" -> "480p"
            else -> "1080p"
        }

    private fun prepareCameraAndStartRecording(lensFacing: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            recoverAfterRecording("Izin kamera belum diberikan")
            return
        }

        if (isDualCameraEnabled()) {
            prepareDualCameraAndStartRecording()
            return
        }
        val providerFuture = ProcessCameraProvider.getInstance(applicationContext)
        providerFuture.addListener({
            if (stopRequested) {
                recoverAfterRecording("Rekaman dibatalkan")
                return@addListener
            }

            try {
                cameraProvider = providerFuture.get()
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                cameraProvider?.unbindAll()

                val requestedQuality = selectedQuality()
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            requestedQuality,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    )
                    .build()

                val videoBuilder = VideoCapture.Builder(recorder)
                Camera2Interop.Extender(videoBuilder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )

                var stabilizationApplied = false
                if (isStabilizationEnabled()) {
                    val modes = Camera2CameraInfo.from(
                        cameraProvider!!.getCameraInfo(selector)
                    ).getCameraCharacteristic(
                        CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
                    )
                    if (modes?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                        Camera2Interop.Extender(videoBuilder).setCaptureRequestOption(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        )
                        stabilizationApplied = true
                    }
                }

                val videoCapture = videoBuilder.build()
                val previewUseCase = if (overlayEnabled && isPreviewEnabled()) buildOverlayPreview() else null
                if (previewUseCase != null) {
                    cameraProvider?.bindToLifecycle(this, selector, previewUseCase, videoCapture)
                    activePreview = previewUseCase
                } else {
                    cameraProvider?.bindToLifecycle(this, selector, videoCapture)
                    activePreview = null
                }

                val values = android.content.ContentValues().apply {
                    put(
                        MediaStore.Video.Media.DISPLAY_NAME,
                        "REC_" + System.currentTimeMillis() + "_" +
                            (if (lensFacing == CameraSelector.LENS_FACING_FRONT) "FRONT" else "BACK") + ".mp4"
                    )
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "Movies/BackgroundCameraRecorder"
                    )
                }

                val outputOptions = MediaStoreOutputOptions.Builder(
                    contentResolver,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                ).setContentValues(values).build()

                var pendingRecording = recorder.prepareRecording(this, outputOptions)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    pendingRecording = pendingRecording.withAudioEnabled()
                }

                activeRecording = pendingRecording.start(
                    ContextCompat.getMainExecutor(this)
                ) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            startInProgress = false
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                .putLong(PREF_STARTED_AT, System.currentTimeMillis())
                                .apply()
                            val stabilText = if (stabilizationApplied) " • Stabilizer ON" else ""
                            publishState(
                                true,
                                "● REC AKTIF • ${cameraName(lensFacing)} • ${qualityName()}$stabilText"
                            )
                            updateNotification("REC aktif • ${cameraName(lensFacing)}")
                            updateOverlayUi(true, "REC AKTIF")
                            if (stopRequested) activeRecording?.stop()
                        }

                        is VideoRecordEvent.Finalize -> {
                            val hasError = event.hasError()
                            activeRecording = null
                            startInProgress = false
                            stopRequested = false
                            val message = if (hasError) {
                                "Rekaman gagal/terhenti (kode ${event.error})"
                            } else {
                                "Video tersimpan • Movies/BackgroundCameraRecorder"
                            }
                            recoverAfterRecording(message)
                        }
                    }
                }
                updateOverlayUi(true, "MENYIAPKAN REKAMAN")
                if (stopRequested) activeRecording?.stop()
            } catch (e: Exception) {
                recoverAfterRecording(
                    "Gagal memulai kamera: " +
                        (e.localizedMessage ?: e.javaClass.simpleName)
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCameraInfo::class)
    private fun prepareDualCameraAndStartRecording() {
        val future = ProcessCameraProvider.getInstance(applicationContext)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                provider.unbindAll()
                val pair = provider.availableConcurrentCameraInfos.firstOrNull { infos ->
                    infos.size == 2 &&
                        infos.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
                        infos.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                }
                if (pair == null) {
                    recoverAfterRecording("Mode dual tidak didukung perangkat ini. Gunakan mode satu kamera.")
                    return@addListener
                }
                val backInfo = pair.first { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                val frontInfo = pair.first { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                val quality = QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                val backRecorder = Recorder.Builder().setQualitySelector(quality).build()
                val frontRecorder = Recorder.Builder().setQualitySelector(quality).build()
                val backCapture = VideoCapture.withOutput(backRecorder)
                val frontCapture = VideoCapture.withOutput(frontRecorder)
                val backConfig = ConcurrentCamera.SingleCameraConfig(
                    backInfo.cameraSelector, UseCaseGroup.Builder().addUseCase(backCapture).build(), this
                )
                val frontConfig = ConcurrentCamera.SingleCameraConfig(
                    frontInfo.cameraSelector, UseCaseGroup.Builder().addUseCase(frontCapture).build(), this
                )
                provider.bindToLifecycle(listOf(backConfig, frontConfig))

                fun outputOptions(label: String): MediaStoreOutputOptions {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "REC_DUAL_${label}_${System.currentTimeMillis()}.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BackgroundCameraRecorder")
                    }
                    return MediaStoreOutputOptions.Builder(
                        contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    ).setContentValues(values).build()
                }
                val audioAllowed = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                dualFinalizeCount = 0
                activeDualRecordings.clear()

                fun startOne(recorder: Recorder, label: String) {
                    var pending = recorder.prepareRecording(this, outputOptions(label))
                    if (audioAllowed && label == "BACK") pending = pending.withAudioEnabled()
                    val recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> {
                                if (activeDualRecordings.size == 2) {
                                    startInProgress = false
                                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                        .putLong(PREF_STARTED_AT, System.currentTimeMillis()).apply()
                                    publishState(true, "● DUAL REC • DEPAN + BELAKANG • 720p")
                                    updateNotification("DUAL REC aktif • depan + belakang")
                                    updateOverlayUi(true, "DUAL REC AKTIF")
                                }
                            }
                            is VideoRecordEvent.Finalize -> {
                                dualFinalizeCount++
                                if (dualFinalizeCount >= 2) {
                                    activeDualRecordings.clear()
                                    activeRecording = null
                                    val msg = if (event.hasError()) "Salah satu rekaman dual gagal (kode ${event.error})"
                                        else "Dua video tersimpan • Movies/BackgroundCameraRecorder"
                                    recoverAfterRecording(msg)
                                }
                            }
                        }
                    }
                    activeDualRecordings.add(recording)
                }
                startOne(backRecorder, "BACK")
                startOne(frontRecorder, "FRONT")
                updateOverlayUi(true, "MENYIAPKAN DUAL CAMERA")
            } catch (e: Exception) {
                activeDualRecordings.toList().forEach { it.stop() }
                activeDualRecordings.clear()
                recoverAfterRecording("Dual camera tidak dapat dijalankan: " + (e.localizedMessage ?: e.javaClass.simpleName))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildOverlayPreview(): Preview? {
        val previewView = overlayPreview ?: return null
        if (!overlayEnabled) return null
        val builder = Preview.Builder()
        Camera2Interop.Extender(builder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        )
        return builder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
    }

    private fun refreshPreviewBinding() {
        val previewView = overlayPreview
        if (!overlayEnabled || previewView == null) return
        if (!isPreviewEnabled()) {
            previewView.visibility = View.GONE
            if (activeRecording == null && !startInProgress) {
                try { cameraProvider?.unbindAll() } catch (_: Exception) {}
                activePreview = null
            }
            return
        }
        previewView.visibility = View.VISIBLE
        if (activeRecording == null && !startInProgress) bindPreviewOnly()
    }

    private fun bindPreviewOnly() {
        if (!overlayEnabled || !isPreviewEnabled() || activeRecording != null || startInProgress) return
        val previewView = overlayPreview ?: return
        val future = ProcessCameraProvider.getInstance(applicationContext)
        future.addListener({
            if (!overlayEnabled || activeRecording != null || overlayPreview !== previewView) return@addListener
            try {
                cameraProvider = future.get()
                cameraProvider?.unbindAll()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(selectedLensFacing())
                    .build()
                val preview = buildOverlayPreview() ?: return@addListener
                cameraProvider?.bindToLifecycle(this, selector, preview)
                activePreview = preview
                updateOverlayUi(false, "PREVIEW AKTIF • BELUM MEREKAM")
                updateNotification("Preview kamera aktif • belum merekam")
            } catch (e: Exception) {
                setOverlayStatus(
                    "Preview gagal: " + (e.localizedMessage ?: "kamera tidak tersedia")
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun recoverAfterRecording(message: String) {
        startInProgress = false
        stopRequested = false
        publishState(false, message)
        if (overlayEnabled && !disableOverlayAfterFinalize) {
            startForegroundCompat("Kontrol mengambang aktif")
            updateOverlayUi(false, message)
            if (isPreviewEnabled()) bindPreviewOnly() else cameraProvider?.unbindAll()
            updateNotification("Kontrol mengambang aktif • belum merekam")
        } else {
            if (disableOverlayAfterFinalize) {
                disableOverlayAfterFinalize = false
                disableFloatingControls()
            } else {
                finishService(message)
            }
        }
    }

    private fun finishService(message: String) {
        startInProgress = false
        activeRecording = null
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        cameraProvider = null
        publishState(false, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun publishState(recording: Boolean, message: String) {
        val stateEditor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_IS_RECORDING, recording)
            .putString(PREF_STATUS, message)
        if (!recording) stateEditor.putLong(PREF_STARTED_AT, 0L)
        stateEditor.apply()

        val update = Intent(ACTION_STATE_CHANGED)
            .setPackage(packageName)
            .putExtra(EXTRA_IS_RECORDING, recording)
            .putExtra(EXTRA_STATUS, message)
        sendBroadcast(update)
    }

    private fun addFloatingOverlay() {
        if (overlayView != null || !Settings.canDrawOverlays(this)) return
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val view = LayoutInflater.from(this).inflate(R.layout.floating_camera_overlay, null)
            overlayView = view
            overlayBubble = view.findViewById(R.id.floatingBubble)
            overlayControls = view.findViewById(R.id.floatingControls)
            overlayPreview = view.findViewById(R.id.floatingPreviewView)
            overlayPreview?.visibility = if (isPreviewEnabled()) View.VISIBLE else View.GONE
            overlayStatus = view.findViewById(R.id.floatingStatusText)
            overlayCameraLabel = view.findViewById(R.id.floatingCameraLabel)
            overlayQualityLabel = view.findViewById(R.id.floatingQualityLabel)
            overlayRecButton = view.findViewById(R.id.floatingRecButton)
            overlayStopButton = view.findViewById(R.id.floatingStopButton)
            overlaySwitchButton = view.findViewById(R.id.floatingSwitchButton)

            overlayBubble?.setOnClickListener {
                expandFloatingControls()
            }
            overlayBubble?.let { attachOverlayDrag(it, performClickOnTap = true) }

            view.findViewById<Button>(R.id.floatingCollapseButton)?.setOnClickListener {
                collapseFloatingControls()
            }
            view.findViewById<Button>(R.id.floatingCloseButton)?.setOnClickListener {
                val closeIntent = Intent(this, RecordingService::class.java)
                    .setAction(ACTION_DISABLE_FLOATING)
                startService(closeIntent)
            }

            view.findViewById<View>(R.id.floatingDragHandle)?.let {
                attachOverlayDrag(it, performClickOnTap = false)
            }

            overlayRecButton?.setOnClickListener {
                if (activeRecording == null && !startInProgress) {
                    stopRequested = false
                    startInProgress = true
                    startForegroundCompat("Menyiapkan kamera dari kontrol mengambang")
                    publishState(true, "Menyiapkan kamera untuk merekam...")
                    prepareCameraAndStartRecording(selectedLensFacing())
                }
            }
            overlayStopButton?.setOnClickListener {
                stopRequested = true
                if (activeDualRecordings.isNotEmpty()) activeDualRecordings.toList().forEach { it.stop() }
                else activeRecording?.stop()
            }
            overlaySwitchButton?.setOnClickListener {
                switchCameraFromOverlay()
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(12)
                y = dp(110)
            }
            overlayLayoutParams = params
            windowManager?.addView(view, params)
            view.post { clampOverlayToScreen() }
            updateOverlayUi(false, "MEMUAT PREVIEW")
        } catch (e: Exception) {
            removeFloatingOverlay()
            publishState(false, "Panel mengambang gagal dibuka: " + (e.localizedMessage ?: "error"))
        }
    }

    private fun expandFloatingControls() {
        overlayBubble?.visibility = View.GONE
        overlayControls?.apply {
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
            visibility = View.VISIBLE
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
        }
        overlayView?.post { clampOverlayToScreen() }
    }

    private fun collapseFloatingControls() {
        overlayControls?.visibility = View.GONE
        overlayBubble?.visibility = View.VISIBLE
        overlayView?.post { clampOverlayToScreen() }
    }

    private fun attachOverlayDrag(handle: View, performClickOnTap: Boolean) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var moved = false
        handle.setOnTouchListener { touchedView, event ->
            val params = overlayLayoutParams
            val root = overlayView
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        moved = true
                    }
                    if (moved && params != null && root != null) {
                        updateOverlayPosition(root, params, initialX + dx, initialY + dy)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (performClickOnTap && !moved) touchedView.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun updateOverlayPosition(
        root: View,
        params: WindowManager.LayoutParams,
        requestedX: Int,
        requestedY: Int
    ) {
        val metrics = resources.displayMetrics
        val minX = dp(6)
        val minY = dp(28)
        val maxX = (metrics.widthPixels - root.width - dp(6)).coerceAtLeast(minX)
        val maxY = (metrics.heightPixels - root.height - dp(72)).coerceAtLeast(minY)
        params.x = requestedX.coerceIn(minX, maxX)
        params.y = requestedY.coerceIn(minY, maxY)
        try {
            windowManager?.updateViewLayout(root, params)
        } catch (_: Exception) {
        }
    }

    private fun clampOverlayToScreen() {
        val root = overlayView ?: return
        val params = overlayLayoutParams ?: return
        updateOverlayPosition(root, params, params.x, params.y)
    }

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private fun removeFloatingOverlay() {
        val view = overlayView
        if (view != null) {
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        overlayBubble = null
        overlayControls = null
        overlayPreview = null
        overlayStatus = null
        overlayCameraLabel = null
        overlayQualityLabel = null
        overlayRecButton = null
        overlayStopButton = null
        overlaySwitchButton = null
        overlayLayoutParams = null
    }

    private fun switchCameraFromOverlay() {
        if (activeRecording != null || activeDualRecordings.isNotEmpty() || startInProgress) {
            setOverlayStatus("STOP rekaman sebelum mengganti kamera")
            return
        }
        val next = if (selectedLensFacing() == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE).edit()
            .putInt(PREF_LENS_FACING, next)
            .apply()
        updateOverlayUi(false, "Mengganti ke ${cameraName(next)}")
        bindPreviewOnly()
    }

    private fun updateOverlayUi(recording: Boolean, status: String) {
        overlayStatus?.text = status
        overlayBubble?.text = if (recording || startInProgress) "●" else ""
        overlayCameraLabel?.text = cameraName()
        overlayQualityLabel?.text = qualityName()
        overlayRecButton?.isEnabled = !recording && !startInProgress
        overlayStopButton?.isEnabled = recording || activeDualRecordings.isNotEmpty() || startInProgress
        overlaySwitchButton?.isEnabled = !recording && activeDualRecordings.isEmpty() && !startInProgress
        overlayRecButton?.alpha = if (!recording && !startInProgress) 1f else 0.55f
        overlayStopButton?.alpha = if (recording || activeDualRecordings.isNotEmpty() || startInProgress) 1f else 0.55f
        overlaySwitchButton?.alpha = if (!recording && !startInProgress) 1f else 0.55f
    }

    private fun setOverlayStatus(message: String) {
        overlayStatus?.text = message
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Perekaman kamera",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Status preview dan perekaman kamera yang sedang berlangsung"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val panelIdle = overlayEnabled && activeRecording == null && activeDualRecordings.isEmpty() && !startInProgress
        val stopServiceIntent = Intent(this, RecordingService::class.java)
            .setAction(if (panelIdle) ACTION_DISABLE_FLOATING else ACTION_STOP_RECORDING)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopServiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Background Camera Recorder")
            .setContentText(message)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_pause,
                    if (panelIdle) "MATIKAN PANEL" else "HENTIKAN",
                    stopPendingIntent
                )
            )
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        removeFloatingOverlay()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
