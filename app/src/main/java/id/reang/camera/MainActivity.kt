package id.reang.camera

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var recordButton: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var isRecording = false
    private var receiverRegistered = false

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        private const val PREFS_NAME = "recording_state"
        private const val PREF_IS_RECORDING = "is_recording"
        private const val PREF_STATUS = "status_text"
    }

    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            isRecording = intent.getBooleanExtra(RecordingService.EXTRA_IS_RECORDING, false)
            val message = intent.getStringExtra(RecordingService.EXTRA_STATUS)
            renderRecordingState(message)
            if (!isRecording &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                allPermissionsGranted()
            ) {
                startCamera()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        recordButton = findViewById(R.id.recordButton)

        recordButton.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }

        findViewById<Button>(R.id.switchCameraButton).setOnClickListener {
            if (isRecording) {
                Toast.makeText(this, "Hentikan rekaman sebelum mengganti kamera", Toast.LENGTH_SHORT).show()
            } else {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                cameraProvider?.unbindAll()
                startCamera()
            }
        }

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            recordingStateReceiver,
            IntentFilter(RecordingService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        refreshRecordingState()
    }

    override fun onResume() {
        super.onResume()
        refreshRecordingState()
        if (!isRecording && allPermissionsGranted()) startCamera()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(recordingStateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun allPermissionsGranted(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && allPermissionsGranted()) {
            startCamera()
        } else if (requestCode == REQUEST_PERMISSIONS) {
            statusText.text = "Izin kamera dan mikrofon diperlukan"
        }
    }

    private fun refreshRecordingState() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isRecording = prefs.getBoolean(PREF_IS_RECORDING, false)
        renderRecordingState(prefs.getString(PREF_STATUS, null))
    }

    private fun renderRecordingState(message: String?) {
        recordButton.text = if (isRecording) "HENTIKAN REKAMAN" else "MULAI MEREKAM"
        if (!message.isNullOrBlank()) {
            statusText.text = message
        } else {
            statusText.text = if (isRecording) {
                "● MEREKAM • Rekaman tetap berjalan saat kembali ke Home"
            } else {
                "Siap merekam"
            }
        }
    }

    private fun startCamera() {
        if (isRecording || !allPermissionsGranted()) return

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            // Jangan memasang ulang kamera milik Activity jika service rekaman
            // sudah mengambil alih kamera saat proses provider sedang disiapkan.
            if (isRecording || isFinishing) return@addListener

            try {
                cameraProvider = future.get()
                val previewBuilder = Preview.Builder()
                Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
                val preview = previewBuilder.build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.FHD))
                    .build()
                val videoBuilder = VideoCapture.Builder(recorder)
                Camera2Interop.Extender(videoBuilder).setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
                videoCapture = videoBuilder.build()

                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    videoCapture
                )
                statusText.text = "Kamera aktif • Continuous Video AF"

                if (previewView.width > 0 && previewView.height > 0) {
                    val point = previewView.meteringPointFactory.createPoint(
                        previewView.width / 2f,
                        previewView.height / 2f
                    )
                    camera?.cameraControl?.startFocusAndMetering(
                        androidx.camera.core.FocusMeteringAction.Builder(point)
                            .setAutoCancelDuration(5, TimeUnit.SECONDS)
                            .build()
                    )
                }
            } catch (e: Exception) {
                statusText.text = "Kamera gagal dibuka: " + (e.localizedMessage ?: "kesalahan tidak diketahui")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
            return
        }

        // Lepaskan use case milik Activity sebelum service mengikat kamera
        // ke lifecycle service yang tetap hidup setelah Activity masuk Home.
        cameraProvider?.unbindAll()
        camera = null
        videoCapture = null
        isRecording = true
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_IS_RECORDING, true)
            .putString(PREF_STATUS, "Menyiapkan kamera untuk merekam...")
            .apply()
        renderRecordingState("Menyiapkan kamera untuk merekam...")

        val serviceIntent = Intent(this, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START_RECORDING)
            .putExtra(RecordingService.EXTRA_LENS_FACING, lensFacing)

        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            isRecording = false
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_IS_RECORDING, false)
                .putString(PREF_STATUS, "Layanan rekaman gagal dimulai")
                .apply()
            renderRecordingState("Layanan rekaman gagal dimulai")
            Toast.makeText(
                this,
                "Tidak dapat memulai layanan: " + (e.localizedMessage ?: "kesalahan"),
                Toast.LENGTH_LONG
            ).show()
            startCamera()
        }
    }

    private fun stopRecording() {
        statusText.text = "Mengakhiri dan menyimpan video..."
        val stopIntent = Intent(this, RecordingService::class.java)
            .setAction(RecordingService.ACTION_STOP_RECORDING)
        startService(stopIntent)
    }
}
