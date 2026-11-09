package id.reang.camera

import android.content.Intent
import android.os.Bundle
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
    private lateinit var stabilizationSwitch: SwitchCompat
    private lateinit var cameraChoiceText: TextView
    private lateinit var qualitySpinner: Spinner
    private lateinit var previewSwitch: SwitchCompat
    private lateinit var dualCameraSwitch: SwitchCompat

    companion object {
        private const val CAMERA_PREFS = "camera_settings"
        private const val PREF_LENS_FACING = "lens_facing"
        private const val PREF_FLOATING_ENABLED = "floating_enabled"
        private const val PREF_QUALITY = "video_quality"
        private const val PREF_STABILIZATION = "video_stabilization"
        private const val PREF_PREVIEW_ENABLED = "floating_preview_enabled"
        private const val PREF_DUAL_CAMERA = "dual_camera_enabled"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        previewSwitch = findViewById(R.id.previewSwitch)
        dualCameraSwitch = findViewById(R.id.dualCameraSwitch)
        stabilizationSwitch = findViewById(R.id.stabilizationSwitch)
        cameraChoiceText = findViewById(R.id.cameraChoiceText)
        qualitySpinner = findViewById(R.id.qualitySpinner)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
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

        updateCameraChoice()
    }

    override fun onResume() {
        super.onResume()
        updateCameraChoice()
    }

    private fun refreshFloatingService() {
        val prefs = getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PREF_FLOATING_ENABLED, false)) {
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
