package id.reang.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class SettingsActivity : ComponentActivity() {
    private lateinit var previewSwitch: SwitchCompat
    private lateinit var previewContainer: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var previewStatusText: TextView
    private lateinit var cameraChoiceText: TextView
    private var cameraProvider: ProcessCameraProvider? = null

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 201
        private const val CAMERA_PREFS = "camera_settings"
        private const val PREF_PREVIEW_ENABLED = "preview_enabled"
        private const val PREF_LENS_FACING = "lens_facing"
        private const val RECORDING_PREFS = "recording_state"
        private const val PREF_IS_RECORDING = "is_recording"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        previewSwitch = findViewById(R.id.previewSwitch)
        previewContainer = findViewById(R.id.previewContainer)
        previewView = findViewById(R.id.settingsPreviewView)
        previewStatusText = findViewById(R.id.previewStatusText)
        cameraChoiceText = findViewById(R.id.cameraChoiceText)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.switchCameraButton).setOnClickListener {
            if (isRecording()) {
                previewStatusText.text = "Hentikan rekaman sebelum mengganti kamera."
                previewStatusText.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val prefs = getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE)
            val current = prefs.getInt(PREF_LENS_FACING, CameraSelector.LENS_FACING_BACK)
            val next = if (current == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            prefs.edit().putInt(PREF_LENS_FACING, next).apply()
            updateCameraChoice()
            if (previewSwitch.isChecked) startSmallPreview()
        }

        val prefs = getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE)
        previewSwitch.isChecked = prefs.getBoolean(PREF_PREVIEW_ENABLED, false)
        previewSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean(PREF_PREVIEW_ENABLED, enabled).apply()
            previewContainer.visibility = if (enabled) View.VISIBLE else View.GONE
            if (enabled) {
                startSmallPreview()
            } else if (!isRecording()) {
                cameraProvider?.unbindAll()
            }
        }

        updateCameraChoice()
        previewContainer.visibility =
            if (previewSwitch.isChecked) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (previewSwitch.isChecked) startSmallPreview()
    }

    override fun onStop() {
        if (!isRecording()) {
            try {
                cameraProvider?.unbindAll()
            } catch (_: Exception) {
            }
        }
        super.onStop()
    }

    private fun isRecording(): Boolean = getSharedPreferences(RECORDING_PREFS, MODE_PRIVATE)
        .getBoolean(PREF_IS_RECORDING, false)

    private fun updateCameraChoice() {
        val facing = getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE)
            .getInt(PREF_LENS_FACING, CameraSelector.LENS_FACING_BACK)
        cameraChoiceText.text = if (facing == CameraSelector.LENS_FACING_BACK) {
            "Kamera belakang"
        } else {
            "Kamera depan"
        }
    }

    private fun startSmallPreview() {
        previewContainer.visibility = View.VISIBLE
        if (isRecording()) {
            previewView.visibility = View.INVISIBLE
            previewStatusText.visibility = View.VISIBLE
            previewStatusText.text = "Preview tidak tersedia selama kamera sedang merekam."
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
            previewStatusText.text = "Izin kamera diperlukan untuk menampilkan preview."
            return
        }

        previewStatusText.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isRecording() || !previewSwitch.isChecked) return@addListener
            try {
                cameraProvider = future.get()
                cameraProvider?.unbindAll()
                val builder = Preview.Builder()
                Camera2Interop.Extender(builder).setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                    android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
                val preview = builder.build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val facing = getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE)
                    .getInt(PREF_LENS_FACING, CameraSelector.LENS_FACING_BACK)
                val selector = CameraSelector.Builder().requireLensFacing(facing).build()
                cameraProvider?.bindToLifecycle(this, selector, preview)
            } catch (e: Exception) {
                previewStatusText.visibility = View.VISIBLE
                previewStatusText.text =
                    "Preview gagal dibuka: " + (e.localizedMessage ?: "kesalahan kamera")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED && previewSwitch.isChecked
        ) {
            startSmallPreview()
        }
    }
}
