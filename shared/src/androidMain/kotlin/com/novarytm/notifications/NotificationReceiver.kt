package com.novarytm.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("notification_id", 0)
        val title = intent.getStringExtra("notification_title") ?: "Bloom"
        val body = intent.getStringExtra("notification_body") ?: "Time for a quick check-in!"

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Create channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("bloom_reminders", "Bloom Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders for health logs and updates"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val themePref = com.novarytm.storage.SecureStorageManager(context).getThemePreference()
        val notifColor = if (themePref == "Baby Blue") 0xFFAEC6CF.toInt() else 0xFFD48C95.toInt()

        val notification = NotificationCompat.Builder(context, "bloom_reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: use app icon
            .setContentTitle(title)
            .setContentText(body)
            .setColor(notifColor)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(id, notification)
    }
}
