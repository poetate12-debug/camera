package id.reang.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.Recording
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        recordButton = findViewById(R.id.recordButton)

        recordButton.setOnClickListener {
            if (activeRecording == null) startRecording() else stopRecording()
        }
        findViewById<Button>(R.id.switchCameraButton).setOnClickListener {
            if (activeRecording == null) {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                startCamera()
            } else Toast.makeText(this, "Hentikan rekaman sebelum mengganti kamera", Toast.LENGTH_SHORT).show()
        }
        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && allPermissionsGranted()) startCamera()
        else if (requestCode == REQUEST_PERMISSIONS) statusText.text = "Izin kamera dan mikrofon diperlukan"
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
            val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build()
            videoCapture = VideoCapture.withOutput(recorder)
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(this, selector, preview, videoCapture)
                statusText.text = "Kamera aktif • Continuous Video AF"
                if (previewView.width > 0 && previewView.height > 0) {
                    val point = previewView.meteringPointFactory.createPoint(previewView.width / 2f, previewView.height / 2f)
                    camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).setAutoCancelDuration(5, TimeUnit.SECONDS).build())
                }
            } catch (e: Exception) {
                statusText.text = "Kamera gagal dibuka: ${e.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        val capture = videoCapture ?: return
        val serviceIntent = Intent(this, RecordingService::class.java)
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak dapat memulai layanan: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            return
        }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "REC_${System.currentTimeMillis()}.mp4")
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/BackgroundCameraRecorder")
        }
        val output = androidx.camera.video.MediaStoreOutputOptions.Builder(
            contentResolver, android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()
        var pending = capture.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending = pending.withAudioEnabled()
        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is androidx.camera.video.VideoRecordEvent.Start -> {
                    recordButton.text = "HENTIKAN REKAMAN"
                    statusText.text = "● MEREKAM • Movies/BackgroundCameraRecorder"
                }
                is androidx.camera.video.VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    recordButton.text = "MULAI MEREKAM"
                    statusText.text = if (event.hasError()) "Rekaman gagal: ${event.error}" else "Video tersimpan"
                    stopService(Intent(this, RecordingService::class.java))
                    if (event.hasError()) Toast.makeText(this, "Kesalahan rekaman: ${event.error}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        recordButton.text = "MULAI MEREKAM"
        statusText.text = "Mengakhiri dan menyimpan video..."
    }

    override fun onDestroy() {
        activeRecording?.stop()
        super.onDestroy()
    }
}
