package com.novarytm.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

actual class NotificationScheduler(private val context: Context) {
    actual fun scheduleNotification(
        id: Int,
        title: String,
        body: String,
        delayMillis: Long
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java).apply {
            putExtra("notification_id", id)
            putExtra("notification_title", title)
            putExtra("notification_body", body)
            val repeatIntervalMs = when {
                title.contains("Daily", ignoreCase = true) || body.contains("Daily", ignoreCase = true) || body.contains("pill", ignoreCase = true) -> 24L * 60 * 60 * 1000L
                title.contains("Weekly", ignoreCase = true) || body.contains("Weekly", ignoreCase = true) || body.contains("patch", ignoreCase = true) -> 7L * 24 * 60 * 60 * 1000L
                title.contains("Monthly", ignoreCase = true) || body.contains("Monthly", ignoreCase = true) || body.contains("strings", ignoreCase = true) || body.contains("check-in", ignoreCase = true) -> 30L * 24 * 60 * 60 * 1000L
                else -> 0L
            }
            if (repeatIntervalMs > 0L) {
                putExtra("REPEAT_INTERVAL_MS", repeatIntervalMs)
            }
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val triggerTime = System.currentTimeMillis() + delayMillis
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val hasPermission = try {
                alarmManager.canScheduleExactAlarms()
            } catch (e: Exception) {
                false
            }
            if (hasPermission) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                } catch (e: SecurityException) {
                    scheduleFallbackAlarm(alarmManager, triggerTime, pendingIntent)
                }
            } else {
                scheduleFallbackAlarm(alarmManager, triggerTime, pendingIntent)
            }
        } else {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } catch (e: SecurityException) {
                scheduleFallbackAlarm(alarmManager, triggerTime, pendingIntent)
            }
        }
    }

    private fun scheduleFallbackAlarm(alarmManager: AlarmManager, triggerTime: Long, pendingIntent: PendingIntent) {
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } catch (e: Exception) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
    }

    actual fun cancelNotification(id: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}
