package com.novarytm.sync

actual class BackgroundSyncScheduler : SyncUploadScheduler {
    actual override fun schedulePendingUpload() {
        // No-op on iOS
    }

    actual override fun cancelPendingUpload() {
        // No-op on iOS
    }

    actual override fun schedulePeriodicPartnerPull() {
        // No-op on iOS
    }

    actual override fun cancelPartnerPull() {
        // No-op on iOS
    }
}
