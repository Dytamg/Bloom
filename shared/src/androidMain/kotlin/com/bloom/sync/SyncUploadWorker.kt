package com.bloom.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bloom.storage.SecureStorageManager

class SyncUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val coordinator = SyncWorkerCoordinator(applicationContext, TAG)

    private fun checkUploadOwnerRole(storage: SecureStorageManager): Boolean {
        return when (storage.getSyncRole()) {
            SyncRole.OWNER -> true
            SyncRole.PARTNER -> false
            SyncRole.NONE -> storage.getUserId() != null && storage.getTargetSyncId().isNullOrEmpty()
        }
    }

    private fun checkUploadSyncKey(storage: SecureStorageManager): Boolean {
        val syncKey = storage.getSyncSecretKey()
        val hasKey = syncKey != null && syncKey.isNotEmpty()
        syncKey?.fill(0)
        return hasKey
    }

    private fun checkUploadTarget(storage: SecureStorageManager): Boolean {
        return if (storage.getSyncProviderMode() == SyncProviderMode.SAF_FILE) {
            !storage.getSafFileUri().isNullOrBlank()
        } else {
            !storage.getPartnerEmail()?.trim().isNullOrBlank()
        }
    }

    private fun hasUploadPreconditions(storage: SecureStorageManager): Boolean {
        if (!storage.hasPendingSyncUpload()) return false
        val isOwner = checkUploadOwnerRole(storage)
        val hasKey = checkUploadSyncKey(storage)
        val hasTarget = checkUploadTarget(storage)
        return isOwner && hasTarget && hasKey
    }

    private fun mapUploadOutcome(outcome: SyncManager.UploadOutcome, storage: SecureStorageManager): Result {
        return when (outcome) {
            is SyncManager.UploadOutcome.Success,
            SyncManager.UploadOutcome.NoOp -> {
                storage.setPendingSyncUpload(false)
                Result.success()
            }
            SyncManager.UploadOutcome.AuthRequired,
            SyncManager.UploadOutcome.StorageFull,
            is SyncManager.UploadOutcome.PermanentFailure -> Result.failure()
            is SyncManager.UploadOutcome.RetryableFailure -> Result.retry()
        }
    }

    override suspend fun doWork(): Result {
        if (coordinator.isUserLocked()) {
            Log.w(TAG, "User locked (Direct Boot); retrying background sync later.")
            return Result.retry()
        }

        return coordinator.executeWithResources(null) { manager, storage ->
            if (!hasUploadPreconditions(storage)) {
                return@executeWithResources Result.success()
            }
            mapUploadOutcome(manager.executeBackgroundUpload(), storage)
        }
    }

    companion object {
        private const val TAG = "SyncUploadWorker"
    }
}
