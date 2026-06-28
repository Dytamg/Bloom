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
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val showIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, showIntent)
        try {
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
        } catch (e: Exception) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
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
