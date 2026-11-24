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
        val rawTitle = intent.getStringExtra("notification_title") ?: "Bloom"
        val rawBody = intent.getStringExtra("notification_body") ?: "You have a new reminder"
        val title = rawTitle.replace(Regex("<[^>]*>"), "").take(120).trim().ifEmpty { "Bloom" }
        val body = rawBody.replace(Regex("<[^>]*>"), "").take(240).trim().ifEmpty { "You have a new reminder" }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create channel for Android O+ with strict lockscreen redaction (VISIBILITY_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("bloom_reminders", "Bloom Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Scheduled app reminders"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
            notificationManager.createNotificationChannel(channel)
        }

        val themePref = runCatching {
            com.novarytm.storage.SecureStorageManager(context).getThemePreference()
        }.getOrNull()
        val notifColor = if (themePref == "Baby Blue") 0xFFAEC6CF.toInt() else 0xFFD48C95.toInt()

        // Generic lockscreen notification omitting all health/cycle/medication details
        val publicNotification = NotificationCompat.Builder(context, "bloom_reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Bloom")
            .setContentText("You have a new reminder")
            .setTicker(null)
            .setColor(notifColor)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()

        val notification = NotificationCompat.Builder(context, "bloom_reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setTicker(null)
            .setColor(notifColor)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(id, notification)

        val repeatMs = intent.getLongExtra("REPEAT_INTERVAL_MS", 0L)
            .takeIf { it > 0L }
            ?: repeatIntervalMsFor(rawTitle, rawBody)
        if (repeatMs > 0L) {
            NotificationScheduler(context).scheduleNotification(id, title, body, repeatMs)
        }
    }
}
