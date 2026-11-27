package com.bloom.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bloom.storage.SecureStorageManager

class SyncPullWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val coordinator = SyncWorkerCoordinator(applicationContext, "SyncPullWorker")

    private fun checkOwnerRole(storage: SecureStorageManager): Boolean {
        return when (storage.getSyncRole()) {
            SyncRole.OWNER -> true
            SyncRole.PARTNER -> false
            SyncRole.NONE -> storage.getUserId() != null && storage.getTargetSyncId().isNullOrEmpty()
        }
    }

    private fun checkSyncKey(storage: SecureStorageManager): Boolean {
        val syncKey = storage.getSyncSecretKey()
        val hasKey = syncKey != null && syncKey.isNotEmpty()
        syncKey?.fill(0)
        return hasKey
    }

    private fun checkSafTarget(storage: SecureStorageManager): Boolean {
        return storage.getSyncProviderMode() == SyncProviderMode.SAF_FILE &&
            !storage.getSafFileUri().isNullOrBlank()
    }

    private fun hasPullPreconditions(storage: SecureStorageManager): Boolean {
        val targetSyncId = storage.getTargetSyncId()
        val isOwner = checkOwnerRole(storage)
        val hasKey = checkSyncKey(storage)
        val hasSafTarget = checkSafTarget(storage)
        val hasValidTarget = hasSafTarget || (!targetSyncId.isNullOrBlank() && targetSyncId != "appDataFolder")
        return !isOwner && hasValidTarget && hasKey
    }

    private fun mapPullOutcome(outcome: SyncManager.PullOutcome): Result {
        return when (outcome) {
            SyncManager.PullOutcome.Success,
            SyncManager.PullOutcome.Revoked,
            SyncManager.PullOutcome.NoOp -> Result.success()
            SyncManager.PullOutcome.AuthRequired,
            is SyncManager.PullOutcome.PermanentFailure -> Result.failure()
            is SyncManager.PullOutcome.RetryableFailure -> Result.retry()
        }
    }

    override suspend fun doWork(): Result {
        if (coordinator.isUserLocked()) {
            Log.w("SyncPullWorker", "User locked; retrying later.")
            return Result.retry()
        }

        return coordinator.executeWithResources(BackgroundSyncScheduler(applicationContext)) { manager, storage ->
            if (!hasPullPreconditions(storage)) {
                return@executeWithResources Result.success()
            }
            mapPullOutcome(manager.executeBackgroundPull())
        }
    }
}
