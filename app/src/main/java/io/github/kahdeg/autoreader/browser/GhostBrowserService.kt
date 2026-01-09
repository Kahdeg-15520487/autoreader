package io.github.kahdeg.autoreader.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.kahdeg.autoreader.R

/**
 * Foreground Service that manages the Ghost Browser (headless WebView).
 * Runs in the background to fetch and process chapters.
 */
@AndroidEntryPoint
class GhostBrowserService : Service() {

    companion object {
        const val CHANNEL_ID = "ghost_browser_channel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        // TODO: Initialize Ghost Browser and start processing queue
        
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // TODO: Cleanup Ghost Browser resources
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ghost Browser",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background chapter fetching and processing"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoReader")
            .setContentText("Fetching chapters in background...")
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
