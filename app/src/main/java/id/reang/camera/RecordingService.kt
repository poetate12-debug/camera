package id.reang.camera

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
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

        const val EXTRA_LENS_FACING = "lens_facing"
        const val EXTRA_IS_RECORDING = "is_recording"
        const val EXTRA_STATUS = "status"

        private const val PREFS_NAME = "recording_state"
        private const val PREF_IS_RECORDING = "is_recording"
        private const val PREF_STATUS = "status_text"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var activeRecording: androidx.camera.video.Recording? = null
    private var startInProgress = false
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP_RECORDING -> {
                stopRequested = true
                val currentRecording = activeRecording
                if (currentRecording != null) {
                    currentRecording.stop()
                } else if (!startInProgress) {
                    finishService("Rekaman dihentikan")
                }
            }

            ACTION_START_RECORDING, null -> {
                if (startInProgress || activeRecording != null) return START_NOT_STICKY

                stopRequested = false
                startInProgress = true
                startForegroundCompat()
                publishState(true, "Menyiapkan kamera untuk merekam...")

                val lensFacing = intent?.getIntExtra(
                    EXTRA_LENS_FACING,
                    CameraSelector.LENS_FACING_BACK
                ) ?: CameraSelector.LENS_FACING_BACK
                prepareCameraAndStartRecording(lensFacing)
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceTypes =
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIFICATION_ID, notification, serviceTypes)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun prepareCameraAndStartRecording(lensFacing: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            finishService("Izin kamera belum diberikan")
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(applicationContext)
        providerFuture.addListener({
            if (stopRequested) {
                finishService("Rekaman dihentikan")
                return@addListener
            }

            try {
                cameraProvider = providerFuture.get()
                cameraProvider?.unbindAll()

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.FHD))
                    .build()

                val videoBuilder = VideoCapture.Builder(recorder)
                Camera2Interop.Extender(videoBuilder).setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
                val videoCapture = videoBuilder.build()

                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                cameraProvider?.bindToLifecycle(this, selector, videoCapture)

                val values = android.content.ContentValues().apply {
                    put(
                        MediaStore.Video.Media.DISPLAY_NAME,
                        "REC_" + System.currentTimeMillis() + ".mp4"
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
                            publishState(
                                true,
                                "● MEREKAM • Tetap berjalan saat aplikasi di Home"
                            )
                            updateNotification("Perekaman video sedang berlangsung")
                            if (stopRequested) activeRecording?.stop()
                        }

                        is VideoRecordEvent.Finalize -> {
                            val hasError = event.hasError()
                            activeRecording = null
                            startInProgress = false
                            val message = if (hasError) {
                                "Rekaman gagal atau terhenti (kode " + event.error + ")"
                            } else {
                                "Video berhasil disimpan • Movies/BackgroundCameraRecorder"
                            }
                            finishService(message)
                        }
                    }
                }

                if (stopRequested) activeRecording?.stop()
            } catch (e: Exception) {
                finishService(
                    "Gagal memulai kamera: " +
                        (e.localizedMessage ?: e.javaClass.simpleName)
                )
            }
        }, ContextCompat.getMainExecutor(this))
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
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_IS_RECORDING, recording)
            .putString(PREF_STATUS, message)
            .apply()

        val update = Intent(ACTION_STATE_CHANGED)
            .setPackage(packageName)
            .putExtra(EXTRA_IS_RECORDING, recording)
            .putExtra(EXTRA_STATUS, message)
        sendBroadcast(update)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Perekaman kamera",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Status perekaman kamera yang sedang berjalan"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopServiceIntent = Intent(this, RecordingService::class.java)
            .setAction(ACTION_STOP_RECORDING)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopServiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Background Camera Recorder")
            .setContentText("Kamera sedang merekam")
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_pause,
                    "HENTIKAN",
                    stopPendingIntent
                )
            )
            .build()
    }

    private fun updateNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Background Camera Recorder")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                NotificationCompat.Action(
                    android.R.drawable.ic_media_pause,
                    "HENTIKAN",
                    PendingIntent.getService(
                        this,
                        1,
                        Intent(this, RecordingService::class.java)
                            .setAction(ACTION_STOP_RECORDING),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            )
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}
