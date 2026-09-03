package com.chisadin.hudwz.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chisadin.hudwz.MainActivity
import com.chisadin.hudwz.R

class BubbleKeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "waze_hud_bubble_active"
        private const val NOTIFICATION_ID = 0x425542 // "BUB"

        fun sync(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, BubbleKeepAliveService::class.java)
            try {
                if (BubbleManager.isBubbleRequested(app)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        app.startForegroundService(intent)
                    } else {
                        app.startService(intent)
                    }
                } else {
                    app.stopService(intent)
                }
            } catch (_: Throwable) { }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BubbleManager.isBubbleRequested(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        return try {
            startForeground(NOTIFICATION_ID, buildNotification())
            START_STICKY
        } catch (_: Throwable) {
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bong bóng Waze HUD",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Giữ hiển thị tốc độ và cảnh báo nổi khi chuyển app"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val content = PendingIntent.getActivity(this, 0x4255, launchIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Bong bóng Waze HUD đang hoạt động")
            .setContentText("Duy trì tốc độ và cảnh báo nổi trên ứng dụng khác.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(content)
            .build()
    }
}
