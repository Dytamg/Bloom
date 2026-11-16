package com.novarytm.sync

interface SyncUploadScheduler {
    fun schedulePendingUpload()
    fun cancelPendingUpload()
    fun schedulePeriodicPartnerPull() {}
    fun cancelPartnerPull() {}
}

expect class BackgroundSyncScheduler : SyncUploadScheduler {
    override fun schedulePendingUpload()
    override fun cancelPendingUpload()
    override fun schedulePeriodicPartnerPull()
    override fun cancelPartnerPull()
}
