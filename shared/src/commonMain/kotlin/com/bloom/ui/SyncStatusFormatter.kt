@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.bloom.ui

import com.bloom.sync.SyncProviderMode
import com.bloom.utils.AppStrings
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal fun formatSyncWallclock(
    timestampMs: Long,
    nowMs: Long = Clock.System.now().toEpochMilliseconds()
): String {
    if (timestampMs <= 0L) return AppStrings.get("sync_never")
    val diffMs = (nowMs - timestampMs).coerceAtLeast(0L)
    if (diffMs < 60_000L) return AppStrings.get("sync_just_now")
    if (diffMs < 3_600_000L) {
        val mins = (diffMs / 60_000L).coerceAtLeast(1L)
        return AppStrings.get("sync_mins_ago").replace("{0}", mins.toString())
    }
    if (diffMs < 86_400_000L) {
        val hours = (diffMs / 3_600_000L).coerceAtLeast(1L)
        return AppStrings.get("sync_hours_ago").replace("{0}", hours.toString())
    }
    val dt = Instant.fromEpochMilliseconds(timestampMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val month = dt.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    val hh = dt.hour.toString().padStart(2, '0')
    val mm = dt.minute.toString().padStart(2, '0')
    return "$month ${dt.dayOfMonth}, $hh:$mm"
}

internal fun formatSyncStatusLabel(
    timestampMs: Long,
    syncProviderMode: SyncProviderMode
): String {
    val formatted = formatSyncWallclock(timestampMs)
    val templateKey = if (syncProviderMode == SyncProviderMode.SAF_FILE) {
        "last_saved_label"
    } else {
        "last_synced_label"
    }
    return AppStrings.get(templateKey).replace("{0}", formatted)
}
