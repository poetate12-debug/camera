package id.reang.camera

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat

class SettingsActivity : ComponentActivity() {
    private lateinit var overlaySwitch: SwitchCompat
    private lateinit var stabilizationSwitch: SwitchCompat
    private lateinit var cameraChoiceText: TextView
    private lateinit var qualitySpinner: Spinner
    private lateinit var previewSwitch: SwitchCompat
    private lateinit var dualCameraSwitch: SwitchCompat
    private var updatingSwitch = false

    companion object {
        private const val CAMERA_PREFS = "camera_settings"
        private const val PREF_FLOATING_ENABLED = "floating_enabled"
        private const val PREF_LENS_FACING = "lens_facing"
        private const val PREF_QUALITY = "video_quality"
        private const val PREF_STABILIZATION = "video_stabilization"
        private const val PREF_PREVIEW_ENABLED = "floating_preview_enabled"
        private const val PREF_DUAL_CAMERA = "dual_camera_enabled"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        overlaySwitch = findViewById(R.id.floatingSwitch)
        previewSwitch = findViewById(R.id.previewSwitch)
        dualCameraSwitch = findViewById(R.id.dualCameraSwitch)
        stabilizationSwitch = findViewById(R.id.stabilizationSwitch)
        cameraChoiceText = findViewById(R.id.cameraChoiceText)
        qualitySpinner = findViewById(R.id.qualitySpinner)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.overlayPermissionButton).setOnClickListener {
            openOverlayPermissionSettings()
        }
        findViewById<Button>(R.id.switchCameraButton).setOnClickListener {
            switchCamera()
        }

        val qualities = listOf("480p", "720p", "1080p", "4K")
        qualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            qualities
        )
        val prefs = getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE)
        val selectedQuality = when (prefs.getString(PREF_QUALITY, "FHD")) {
            "SD" -> 0
            "HD" -> 1
            "UHD" -> 3
            else -> 2
        }
        qualitySpinner.setSelection(selectedQuality)
        qualitySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                val value = when (position) {
                    0 -> "SD"
                    1 -> "HD"
                    3 -> "UHD"
                    else -> "FHD"
                }
                prefs.edit().putString(PREF_QUALITY, value).apply()
                refreshFloatingService()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        previewSwitch.isChecked = prefs.getBoolean(PREF_PREVIEW_ENABLED, false)
        previewSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean(PREF_PREVIEW_ENABLED, enabled).apply()
            refreshFloatingService()
        }
        dualCameraSwitch.isChecked = prefs.getBoolean(PREF_DUAL_CAMERA, false)
        dualCameraSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean(PREF_DUAL_CAMERA, enabled).apply()
            Toast.makeText(this, if (enabled) "Mode dual aktif jika perangkat mendukung" else "Mode satu kamera", Toast.LENGTH_SHORT).show()
        }

        stabilizationSwitch.isChecked = prefs.getBoolean(PREF_STABILIZATION, true)
        stabilizationSwitch.setOnCheckedChangeListener { _, enabled ->
            prefs.edit().putBoolean(PREF_STABILIZATION, enabled).apply()
        }

        overlaySwitch.isChecked = prefs.getBoolean(PREF_FLOATING_ENABLED, false)
        overlaySwitch.setOnCheckedChangeListener { _, enabled ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (enabled) {
                if (Settings.canDrawOverlays(this)) {
                    enableFloatingControls()
                } else {
                    openOverlayPermissionSettings()
                }
            } else {
                prefs.edit().putBoolean(PREF_FLOATING_ENABLED, false).apply()
                val intent = Intent(this, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_DISABLE_FLOATING)
                startService(intent)
            }
        }
        updateCameraChoice()
    }

    override fun onResume() {
        super.onResume()
        val granted = Settings.canDrawOverlays(this)
        findViewById<Button>(R.id.overlayPermissionButton).text =
            if (granted) "IZIN OVERLAY SUDAH AKTIF" else "BERI IZIN TAMPIL DI ATAS APLIKASI"
        if (overlaySwitch.isChecked && granted) {
            enableFloatingControls()
        } else if (overlaySwitch.isChecked && !granted) {
            updatingSwitch = true
            overlaySwitch.isChecked = false
            updatingSwitch = false
        }
        updateCameraChoice()
    }

    private fun openOverlayPermissionSettings() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izin overlay sudah aktif", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 301)
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 301 && Settings.canDrawOverlays(this)) {
            enableFloatingControls()
        }
    }

    private fun enableFloatingControls() {
        if (!Settings.canDrawOverlays(this)) return
        getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_FLOATING_ENABLED, true)
            .apply()
        updatingSwitch = true
        overlaySwitch.isChecked = true
        updatingSwitch = false
        val intent = Intent(this, RecordingService::class.java)
            .setAction(RecordingService.ACTION_ENABLE_FLOATING)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun refreshFloatingService() {
        if (!Settings.canDrawOverlays(this)) return
        val enabled = getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE)
            .getBoolean(PREF_FLOATING_ENABLED, false)
        if (enabled) {
            val intent = Intent(this, RecordingService::class.java)
                .setAction(RecordingService.ACTION_ENABLE_FLOATING)
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun switchCamera() {
        val prefs = getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE)
        val current = prefs.getInt(PREF_LENS_FACING, CameraSelector.LENS_FACING_BACK)
        val next = if (current == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        prefs.edit().putInt(PREF_LENS_FACING, next).apply()
        updateCameraChoice()
        val enabled = prefs.getBoolean(PREF_FLOATING_ENABLED, false)
        if (enabled) {
            val intent = Intent(this, RecordingService::class.java)
                .setAction(RecordingService.ACTION_REFRESH_CAMERA)
            startService(intent)
        }
    }

    private fun updateCameraChoice() {
        val facing = getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE)
            .getInt(PREF_LENS_FACING, CameraSelector.LENS_FACING_BACK)
        cameraChoiceText.text = if (facing == CameraSelector.LENS_FACING_BACK) {
            "Kamera aktif: BELAKANG"
        } else {
            "Kamera aktif: DEPAN"
        }
    }
}
