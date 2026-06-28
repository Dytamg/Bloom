package com.novarytm.notifications

expect class NotificationScheduler {
    fun scheduleNotification(
        id: Int,
        title: String,
        body: String,
        delayMillis: Long
    )

    fun cancelNotification(id: Int)
}
