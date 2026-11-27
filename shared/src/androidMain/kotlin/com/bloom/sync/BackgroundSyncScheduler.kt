package com.bloom.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bloom.storage.SecureStorageManager
import java.util.concurrent.TimeUnit
import kotlin.random.Random

actual class BackgroundSyncScheduler(private val context: Context) : SyncUploadScheduler {

    private fun buildSyncConstraints(): Constraints {
        val networkType = if (SecureStorageManager(context).getSyncProviderMode() == SyncProviderMode.SAF_FILE) {
            NetworkType.NOT_REQUIRED
        } else {
            NetworkType.CONNECTED
        }
        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()
    }

    actual override fun schedulePendingUpload() {
        val request = OneTimeWorkRequestBuilder<SyncUploadWorker>()
            .setConstraints(buildSyncConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    actual override fun cancelPendingUpload() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    actual override fun schedulePeriodicPartnerPull() {
        val request = PeriodicWorkRequestBuilder<SyncPullWorker>(6, TimeUnit.HOURS)
            .setConstraints(buildSyncConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PARTNER_PULL_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    actual override fun cancelPartnerPull() {
        WorkManager.getInstance(context).cancelUniqueWork(PARTNER_PULL_WORK_NAME)
    }

    actual override fun schedulePeriodicOwnerUpload() {
        val jitterMinutes = Random.nextLong(0L, 16L)
        val request = PeriodicWorkRequestBuilder<SyncUploadWorker>(12, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setConstraints(buildSyncConstraints())
            .setInitialDelay(jitterMinutes, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            OWNER_PERIODIC_UPLOAD_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    actual override fun cancelPeriodicOwnerUpload() {
        WorkManager.getInstance(context).cancelUniqueWork(OWNER_PERIODIC_UPLOAD_WORK_NAME)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "bloom_pending_sync_upload"
        const val PARTNER_PULL_WORK_NAME = "bloom_partner_periodic_pull"
        const val OWNER_PERIODIC_UPLOAD_WORK_NAME = "bloom_owner_periodic_upload"
    }
}
