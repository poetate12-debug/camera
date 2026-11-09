package id.reang.camera

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.appcompat.widget.SwitchCompat

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var dialStatusText: TextView
    private lateinit var timerText: TextView
    private lateinit var recordButton: Button
    private lateinit var floatingSwitch: SwitchCompat
    private lateinit var overlayPermissionButton: Button
    private var updatingFloatingSwitch = false

    private var isRecording = false
    private var receiverRegistered = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        private const val PREFS_NAME = "recording_state"
        private const val PREF_IS_RECORDING = "is_recording"
        private const val PREF_STATUS = "status_text"
        private const val PREF_STARTED_AT = "recording_started_at"
        private const val CAMERA_PREFS = "camera_settings"
        private const val PREF_LENS_FACING = "lens_facing"
        private const val PREF_FLOATING_ENABLED = "floating_enabled"
    }

    private val timerTicker = object : Runnable {
        override fun run() {
            updateDuration()
            handler.postDelayed(this, 1000L)
        }
    }

    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            isRecording = intent.getBooleanExtra(RecordingService.EXTRA_IS_RECORDING, false)
            renderRecordingState(intent.getStringExtra(RecordingService.EXTRA_STATUS))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        dialStatusText = findViewById(R.id.dialStatusText)
        timerText = findViewById(R.id.timerText)
        recordButton = findViewById(R.id.recordButton)
        floatingSwitch = findViewById(R.id.floatingSwitchHome)
        overlayPermissionButton = findViewById(R.id.overlayPermissionButtonHome)
        floatingSwitch.isChecked = getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE)
            .getBoolean(PREF_FLOATING_ENABLED, false)
        floatingSwitch.setOnCheckedChangeListener { _, enabled ->
            if (updatingFloatingSwitch) return@setOnCheckedChangeListener
            if (enabled) enableFloatingFromHome() else disableFloatingFromHome()
        }
        overlayPermissionButton.setOnClickListener { openOverlayPermissionSettings() }
        updateFloatingPermissionLabel()

        recordButton.setOnClickListener {
            if (isRecording) stopRecording() else startRecording()
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        updateFloatingPermissionLabel()
        val prefs = getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE)
        updatingFloatingSwitch = true
        floatingSwitch.isChecked = prefs.getBoolean(PREF_FLOATING_ENABLED, false)
        updatingFloatingSwitch = false
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
        handler.removeCallbacks(timerTicker)
        handler.post(timerTicker)
    }

    override fun onStop() {
        handler.removeCallbacks(timerTicker)
        if (receiverRegistered) {
            unregisterReceiver(recordingStateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun enableFloatingFromHome() {
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermissionSettings()
            return
        }
        getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_FLOATING_ENABLED, true).apply()
        ContextCompat.startForegroundService(
            this,
            Intent(this, RecordingService::class.java)
                .setAction(RecordingService.ACTION_ENABLE_FLOATING)
        )
        Toast.makeText(this, "Floating Bubble diaktifkan", Toast.LENGTH_SHORT).show()
    }

    private fun disableFloatingFromHome() {
        getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_FLOATING_ENABLED, false).apply()
        startService(
            Intent(this, RecordingService::class.java)
                .setAction(RecordingService.ACTION_DISABLE_FLOATING)
        )
        Toast.makeText(this, "Floating Bubble dinonaktifkan", Toast.LENGTH_SHORT).show()
    }

    private fun openOverlayPermissionSettings() {
        if (Settings.canDrawOverlays(this)) {
            enableFloatingFromHome()
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
        if (requestCode == 301) {
            val granted = Settings.canDrawOverlays(this)
            if (granted) {
                enableFloatingFromHome()
            } else {
                updatingFloatingSwitch = true
                floatingSwitch.isChecked = false
                updatingFloatingSwitch = false
            }
            updateFloatingPermissionLabel()
        }
    }

    private fun updateFloatingPermissionLabel() {
        if (::overlayPermissionButton.isInitialized) {
            overlayPermissionButton.text = if (Settings.canDrawOverlays(this)) {
                "IZIN OVERLAY SUDAH AKTIF"
            } else {
                "BERI IZIN TAMPIL DI ATAS APLIKASI"
            }
        }
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
            statusText.text = "Izin kamera dan mikrofon sudah diberikan"
        } else if (requestCode == REQUEST_PERMISSIONS) {
            statusText.text = "Izin kamera dan mikrofon diperlukan"
            Toast.makeText(this, "Berikan izin kamera dan mikrofon untuk merekam", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshRecordingState() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isRecording = prefs.getBoolean(PREF_IS_RECORDING, false)
        renderRecordingState(prefs.getString(PREF_STATUS, null))
    }

    private fun renderRecordingState(message: String?) {
        recordButton.text = if (isRecording) "HENTIKAN REKAMAN" else "MULAI MEREKAM"
        if (isRecording) {
            dialStatusText.text = "● REC AKTIF"
            dialStatusText.setTextColor(0xFFFF5B65.toInt())
            statusText.text = message ?: "Kamera sedang merekam"
            recordButton.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFFE05260.toInt())
            recordButton.setTextColor(0xFFFFFFFF.toInt())
        } else {
            dialStatusText.text = "SIAP"
            dialStatusText.setTextColor(0xFF36D9C1.toInt())
            statusText.text = message ?: "Siap merekam"
            recordButton.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF20BFA9.toInt())
            recordButton.setTextColor(0xFF061A1A.toInt())
        }
        updateDuration()
    }

    private fun updateDuration() {
        if (!isRecording) {
            timerText.text = "00:00"
            return
        }
        val startedAt = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getLong(PREF_STARTED_AT, 0L)
        if (startedAt <= 0L) {
            timerText.text = "--:--"
            return
        }
        val seconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val remainder = seconds % 60L
        timerText.text = if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, remainder)
        } else {
            "%02d:%02d".format(minutes, remainder)
        }
    }

    private fun startRecording() {
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_PERMISSIONS)
            return
        }

        isRecording = true
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_IS_RECORDING, true)
            .putLong(PREF_STARTED_AT, 0L)
            .putString(PREF_STATUS, "Menyiapkan kamera untuk merekam...")
            .apply()
        renderRecordingState("Menyiapkan kamera untuk merekam...")

        val selectedLens = getSharedPreferences(CAMERA_PREFS, MODE_PRIVATE)
            .getInt(PREF_LENS_FACING, CameraSelector.LENS_FACING_BACK)
        val serviceIntent = Intent(this, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START_RECORDING)
            .putExtra(RecordingService.EXTRA_LENS_FACING, selectedLens)

        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            isRecording = false
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_IS_RECORDING, false)
                .putLong(PREF_STARTED_AT, 0L)
                .putString(PREF_STATUS, "Layanan rekaman gagal dimulai")
                .apply()
            renderRecordingState("Layanan rekaman gagal dimulai")
            Toast.makeText(
                this,
                "Tidak dapat memulai layanan: " + (e.localizedMessage ?: "kesalahan"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopRecording() {
        statusText.text = "Mengakhiri dan menyimpan video..."
        val stopIntent = Intent(this, RecordingService::class.java)
            .setAction(RecordingService.ACTION_STOP_RECORDING)
        startService(stopIntent)
    }
}
