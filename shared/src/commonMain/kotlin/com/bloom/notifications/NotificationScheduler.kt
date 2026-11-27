package com.bloom.notifications

interface NotificationScheduling {
    fun scheduleNotification(
        id: Int,
        title: String,
        body: String,
        delayMillis: Long
    )

    fun cancelNotification(id: Int)
}

expect class NotificationScheduler : NotificationScheduling {
    override fun scheduleNotification(
        id: Int,
        title: String,
        body: String,
        delayMillis: Long
    )

    override fun cancelNotification(id: Int)
}

private const val DAY_MS = 24L * 60 * 60 * 1000L
private val RING_WORD_REGEX = Regex("\\bring\\b", RegexOption.IGNORE_CASE)

fun repeatIntervalMsFor(title: String, body: String): Long {
    fun has(token: String) = title.contains(token, ignoreCase = true) || body.contains(token, ignoreCase = true)

    return when {
        has("Every 12 Weeks") || has("injection") || has("shot") -> 84L * DAY_MS
        has("Every 4 Weeks") -> 28L * DAY_MS
        has("Every 3 Weeks") || RING_WORD_REGEX.containsMatchIn(title) || RING_WORD_REGEX.containsMatchIn(body) -> 21L * DAY_MS
        has("Daily") || has("pill") -> DAY_MS
        has("Weekly") || has("patch") -> 7L * DAY_MS
        has("Monthly") || has("strings") || has("check-in") -> 30L * DAY_MS
        else -> 0L
    }
}

fun reminderNotificationId(type: String, frequency: String): Int {
    val normalizedType = type.trim()
    val normalizedFreq = frequency.trim()
    return when {
        normalizedType.contains("Pill", ignoreCase = true) ||
            normalizedFreq.equals("Daily", ignoreCase = true) -> 101
        normalizedType.contains("String", ignoreCase = true) -> 102
        normalizedType.contains("Implant", ignoreCase = true) -> 103
        normalizedType.contains("Patch", ignoreCase = true) -> 104
        normalizedType.contains("Ring", ignoreCase = true) &&
            (normalizedType.contains("Insert", ignoreCase = true) ||
                normalizedFreq.equals("Every 4 Weeks", ignoreCase = true)) -> 110
        normalizedType.contains("Ring", ignoreCase = true) ||
            normalizedFreq.equals("Every 3 Weeks", ignoreCase = true) -> 105
        normalizedType.contains("Hormonal", ignoreCase = true) &&
            normalizedType.contains("IUD", ignoreCase = true) -> 106
        normalizedType.contains("Copper", ignoreCase = true) &&
            normalizedType.contains("IUD", ignoreCase = true) -> 107
        normalizedType.contains("Pregnancy", ignoreCase = true) -> 108
        normalizedType.contains("Injection", ignoreCase = true) ||
            normalizedFreq.equals("Every 12 Weeks", ignoreCase = true) -> 109
        normalizedFreq.equals("Every 4 Weeks", ignoreCase = true) -> 110
        else -> 200 + ((normalizedType.lowercase() + "|" + normalizedFreq.lowercase()).hashCode() and 0x7FFFFFFF) % 1000
    }
}
