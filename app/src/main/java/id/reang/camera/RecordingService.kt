package id.reang.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RecordingService : Service() {
    companion object {
        const val CHANNEL_ID = "camera_recording"
        const val NOTIFICATION_ID = 2501
        const val ACTION_RECORDING_STARTED = "id.reang.camera.RECORDING_STARTED"
        const val ACTION_STOP_RECORDING = "id.reang.camera.STOP_RECORDING"
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "Perekaman kamera", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Menampilkan status perekaman kamera yang sedang berjalan" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RECORDING) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Perekaman kamera aktif")
            .setContentText("Kamera sedang merekam secara terlihat")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
