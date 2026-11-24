package com.novarytm.notifications

actual class NotificationScheduler : NotificationScheduling {
    actual override fun scheduleNotification(
        id: Int,
        title: String,
        body: String,
        delayMillis: Long
    ) {
        // iOS implementation would use UNUserNotificationCenter
        // For this demo, we leave it as a stub
    }

    actual override fun cancelNotification(id: Int) {
        // Stub for iOS
    }
}
