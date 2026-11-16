package com.novarytm.sync

import android.content.Context
import android.os.UserManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.cash.sqldelight.db.SqlDriver
import com.novarytm.auth.GoogleAuthManager
import com.novarytm.db.DriverFactory
import com.novarytm.db.createDatabase
import com.novarytm.ffi.RustBridge
import com.novarytm.storage.SecureStorageManager
import com.novarytm.utils.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import java.io.IOException

class SyncUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Direct Boot guard: Keystore / EncryptedSharedPreferences are unavailable before first unlock.
        val userManager = applicationContext.getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager != null && !userManager.isUserUnlocked) {
            Log.w(TAG, "User locked (Direct Boot); retrying background sync later.")
            return Result.retry()
        }

        val storage = try {
            SecureStorageManager(applicationContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "SecureStorageManager unavailable; retrying background sync.", e)
            return Result.retry()
        }

        if (!storage.hasPendingSyncUpload()) {
            return Result.success()
        }

        val isOwner = storage.getUserId() != null && storage.getTargetSyncId().isNullOrEmpty()
        val partnerEmail = storage.getPartnerEmail()?.trim()
        val syncKey = storage.getSyncSecretKey()
        val hasSyncKey = syncKey != null && syncKey.isNotEmpty()
        syncKey?.fill(0)

        if (!isOwner || partnerEmail.isNullOrBlank() || !hasSyncKey) {
            return Result.success()
        }

        var driver: SqlDriver? = null
        var httpClient: HttpClient? = null
        var syncManager: SyncManager? = null

        return try {
            val (openedDriver, database) = createDatabase(DriverFactory(applicationContext), storage)
            driver = openedDriver
            val rustBridge = RustBridge()
            val savedPermsJson = storage.getPartnerPermissionsJson()
            if (!savedPermsJson.isNullOrBlank()) {
                try {
                    val perms = AppJson.decodeFromString<PartnerPermissions>(savedPermsJson)
                    rustBridge.savePartnerPermissions(perms)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode saved PartnerPermissions; keeping defaults.", e)
                }
            }
            val authManager = GoogleAuthManager(applicationContext, triggerSignIn = { false })
            val client = HttpClient {
                install(ContentNegotiation) {
                    json(json = AppJson)
                }
            }
            httpClient = client
            val driveManager = GoogleDriveManager(authManager, client)
            val manager = SyncManager(
                database = database,
                rustBridge = rustBridge,
                storage = storage,
                googleDriveManager = driveManager,
                backgroundSyncScheduler = null
            )
            syncManager = manager

            when (manager.executeBackgroundUpload()) {
                is SyncManager.UploadOutcome.Success,
                SyncManager.UploadOutcome.NoOp -> {
                    storage.setPendingSyncUpload(false)
                    Result.success()
                }
                SyncManager.UploadOutcome.AuthRequired,
                SyncManager.UploadOutcome.StorageFull,
                is SyncManager.UploadOutcome.PermanentFailure -> {
                    Result.failure()
                }
                is SyncManager.UploadOutcome.RetryableFailure -> {
                    Result.retry()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveAuthRequiredException) {
            Log.w(TAG, "Auth required during background sync; failing without retry loop.", e)
            Result.failure()
        } catch (e: DriveStorageFullException) {
            Log.w(TAG, "Drive storage full during background sync.", e)
            Result.failure()
        } catch (e: DriveSyncException) {
            if (e.isRetryable) {
                Log.w(TAG, "Retryable DriveSyncException in background sync.", e)
                Result.retry()
            } else {
                Log.e(TAG, "Non-retryable DriveSyncException in background sync.", e)
                Result.failure()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network IOException during background sync; retrying.", e)
            Result.retry()
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error during background sync; retrying.", e)
            Result.retry()
        } finally {
            syncManager?.scope?.cancel()
            try {
                httpClient?.close()
            } catch (_: Exception) {
            }
            try {
                driver?.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "SyncUploadWorker"
    }
}
