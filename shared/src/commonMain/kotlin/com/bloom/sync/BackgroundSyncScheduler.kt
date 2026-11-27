package com.bloom.sync

interface SyncUploadScheduler {
    fun schedulePendingUpload()
    fun cancelPendingUpload()
    fun schedulePeriodicPartnerPull() {}
    fun cancelPartnerPull() {}
    fun schedulePeriodicOwnerUpload() {}
    fun cancelPeriodicOwnerUpload() {}
}

expect class BackgroundSyncScheduler : SyncUploadScheduler {
    override fun schedulePendingUpload()
    override fun cancelPendingUpload()
    override fun schedulePeriodicPartnerPull()
    override fun cancelPartnerPull()
    override fun schedulePeriodicOwnerUpload()
    override fun cancelPeriodicOwnerUpload()
}
