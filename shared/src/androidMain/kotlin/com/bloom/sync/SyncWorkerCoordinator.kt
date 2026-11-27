package com.bloom.sync

import android.content.Context
import android.os.UserManager
import android.util.Log
import androidx.work.ListenableWorker
import app.cash.sqldelight.db.SqlDriver
import com.bloom.auth.GoogleAuthManager
import com.bloom.db.DriverFactory
import com.bloom.db.createDatabase
import com.bloom.ffi.RustBridge
import com.bloom.storage.SecureStorageManager
import com.bloom.utils.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import java.io.IOException

class SyncWorkerCoordinator(private val context: Context, private val tag: String) {

    fun isUserLocked(): Boolean {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        return userManager != null && !userManager.isUserUnlocked
    }

    fun obtainStorage(): SecureStorageManager? {
        return try {
            SecureStorageManager(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "SecureStorageManager unavailable; retrying sync.", e)
            null
        }
    }

    fun setupRustBridge(storage: SecureStorageManager): RustBridge {
        val rustBridge = RustBridge()
        val savedPermsJson = storage.getPartnerPermissionsJson()
        if (!savedPermsJson.isNullOrBlank()) {
            decodeAndApplyPermissions(rustBridge, savedPermsJson)
        }
        return rustBridge
    }

    private fun decodeAndApplyPermissions(rustBridge: RustBridge, jsonStr: String) {
        try {
            val perms = AppJson.decodeFromString<PartnerPermissions>(jsonStr)
            rustBridge.savePartnerPermissions(perms)
        } catch (e: Exception) {
            Log.w(tag, "Failed to decode saved PartnerPermissions; keeping defaults.", e)
        }
    }

    private fun createBlobClientForMode(storage: SecureStorageManager, client: HttpClient): DriveBlobClient {
        return if (storage.getSyncProviderMode() == SyncProviderMode.SAF_FILE) {
            SafBlobClient(context) { storage.getSafFileUri() }
        } else {
            val authManager = GoogleAuthManager(context, triggerSignIn = { false })
            GoogleDriveManager(
                authProvider = GoogleAuthDriveProvider(authManager),
                httpClient = client,
                permissionCache = SecureStorageDrivePermissionCache(storage)
            )
        }
    }

    private fun classifySyncWorkerFailure(e: Exception): ListenableWorker.Result {
        return when (e) {
            is SafAccessLostException -> {
                Log.w(tag, "SAF sync file access lost; failing without retry loop.", e)
                ListenableWorker.Result.failure()
            }
            is DriveAuthRequiredException -> {
                Log.w(tag, "Auth required during sync; failing without retry loop.", e)
                ListenableWorker.Result.failure()
            }
            is DriveStorageFullException -> {
                Log.w(tag, "Drive storage full during sync.", e)
                ListenableWorker.Result.failure()
            }
            is DriveSyncException -> handleDriveSyncException(e)
            is IOException -> {
                Log.w(tag, "Network IOException during sync; retrying.", e)
                ListenableWorker.Result.retry()
            }
            else -> {
                Log.w(tag, "Unexpected error during sync; retrying.", e)
                ListenableWorker.Result.retry()
            }
        }
    }

    private fun handleDriveSyncException(e: DriveSyncException): ListenableWorker.Result {
        return if (e.isRetryable) {
            Log.w(tag, "Retryable DriveSyncException in sync.", e)
            ListenableWorker.Result.retry()
        } else {
            Log.e(tag, "Non-retryable DriveSyncException in sync.", e)
            ListenableWorker.Result.failure()
        }
    }

    private fun closeResources(manager: SyncManager?, client: HttpClient?, driver: SqlDriver?) {
        manager?.scope?.cancel()
        try { client?.close() } catch (_: Exception) {}
        try { driver?.close() } catch (_: Exception) {}
    }

    private fun buildHttpClient(): HttpClient {
        return HttpClient {
            install(ContentNegotiation) {
                json(json = AppJson)
            }
        }
    }

    private suspend fun initializeSyncEnvironment(
        storage: SecureStorageManager
    ): Triple<SqlDriver, HttpClient, SyncManager> {
        val (openedDriver, database) = createDatabase(DriverFactory(context), storage)
        val rustBridge = setupRustBridge(storage)
        val client = buildHttpClient()
        val blobClient = createBlobClientForMode(storage, client)
        val manager = SyncManager(
            database = database,
            rustBridge = rustBridge,
            storage = storage,
            googleDriveManager = blobClient,
            backgroundSyncScheduler = null
        )
        return Triple(openedDriver, client, manager)
    }

    suspend fun executeWithResources(
        backgroundSyncScheduler: BackgroundSyncScheduler?,
        block: suspend (SyncManager, SecureStorageManager) -> ListenableWorker.Result
    ): ListenableWorker.Result {
        val storage = obtainStorage() ?: return ListenableWorker.Result.retry()
        var driver: SqlDriver? = null
        var httpClient: HttpClient? = null
        var syncManager: SyncManager? = null

        return try {
            val (openedDriver, client, manager) = initializeSyncEnvironment(storage)
            driver = openedDriver
            httpClient = client
            syncManager = manager
            block(manager, storage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            classifySyncWorkerFailure(e)
        } finally {
            closeResources(syncManager, httpClient, driver)
        }
    }
}

private class SecureStorageDrivePermissionCache(
    private val storage: SecureStorageManager
) : DrivePermissionCache {
    override fun getPrimarySyncFileId(): String? = storage.getPrimarySyncFileId()
    override fun getGrantedPartnerEmail(): String? = storage.getGrantedPartnerEmail()
    override fun saveGrantedPartnerEmail(email: String?) = storage.saveGrantedPartnerEmail(email)
    override fun getPartnerPermissionId(): String? = storage.getPartnerPermissionId()
    override fun savePartnerPermissionId(permissionId: String?) = storage.savePartnerPermissionId(permissionId)
}
