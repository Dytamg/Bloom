package com.bloom.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.bloom.auth.GoogleAuthManager
import com.bloom.storage.SecureStorageManager
import com.bloom.utils.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

private fun persistSafPermissions(context: Context, uri: Uri, allowReadOnlyFallback: Boolean) {
    val readWriteResult = runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    }
    if (readWriteResult.isFailure && allowReadOnlyFallback) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }
}

private fun checkSafUriPermission(
    context: Context,
    uriString: String?,
    requireWrite: Boolean
): Boolean {
    val cleanUri = uriString?.trim()
    if (cleanUri.isNullOrEmpty()) return false
    return runCatching {
        val targetUri = Uri.parse(cleanUri)
        val matched = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == targetUri }
        matched != null && matched.isReadPermission && (!requireWrite || matched.isWritePermission)
    }.getOrDefault(false)
}

@Composable
actual fun rememberSafDocumentLauncher(
    onFileCreated: (String) -> Unit,
    onFileOpened: (String) -> Unit,
    storage: SecureStorageManager?
): SafDocumentActions {
    val context = LocalContext.current
    val currentOnFileCreated by rememberUpdatedState(onFileCreated)
    val currentOnFileOpened by rememberUpdatedState(onFileOpened)
    val currentStorage by rememberUpdatedState(storage)

    val createLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        currentStorage?.setAuthFlowActive(false)
        if (uri != null) {
            persistSafPermissions(context, uri, allowReadOnlyFallback = false)
            currentOnFileCreated(uri.toString())
        }
    }

    val openDocumentContract = remember {
        object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: Context, input: Array<String>): Intent {
                return super.createIntent(context, input).apply {
                    addFlags(
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        contract = openDocumentContract
    ) { uri ->
        currentStorage?.setAuthFlowActive(false)
        if (uri != null) {
            persistSafPermissions(context, uri, allowReadOnlyFallback = true)
            currentOnFileOpened(uri.toString())
        }
    }

    return remember(context, createLauncher, openLauncher) {
        SafDocumentActions(
            createSyncFile = { suggestedName ->
                currentStorage?.setAuthFlowActive(true)
                runCatching { createLauncher.launch(suggestedName) }.onFailure {
                    currentStorage?.setAuthFlowActive(false)
                }
            },
            openSyncFile = {
                currentStorage?.setAuthFlowActive(true)
                runCatching { openLauncher.launch(arrayOf("*/*")) }.onFailure {
                    currentStorage?.setAuthFlowActive(false)
                }
            },
            hasValidPermission = { uriString, requireWrite ->
                checkSafUriPermission(context, uriString, requireWrite)
            }
        )
    }
}

private class StorageDrivePermissionCache(
    private val storage: SecureStorageManager
) : DrivePermissionCache {
    override fun getPrimarySyncFileId(): String? = storage.getPrimarySyncFileId()
    override fun getGrantedPartnerEmail(): String? = storage.getGrantedPartnerEmail()
    override fun saveGrantedPartnerEmail(email: String?) = storage.saveGrantedPartnerEmail(email)
    override fun getPartnerPermissionId(): String? = storage.getPartnerPermissionId()
    override fun savePartnerPermissionId(permissionId: String?) = storage.savePartnerPermissionId(permissionId)
}

@Composable
actual fun rememberSyncBlobClient(
    storage: SecureStorageManager,
    authManager: GoogleAuthManager,
    providerMode: SyncProviderMode
): DriveBlobClient {
    val context = LocalContext.current
    val httpClient = remember {
        HttpClient {
            install(ContentNegotiation) {
                json(json = AppJson)
            }
        }
    }

    DisposableEffect(httpClient) {
        onDispose {
            runCatching { httpClient.close() }
        }
    }

    return remember(context, storage, authManager, providerMode, httpClient) {
        if (providerMode == SyncProviderMode.SAF_FILE) {
            SafBlobClient(context.applicationContext) { storage.getSafFileUri() }
        } else {
            GoogleDriveManager(
                authProvider = GoogleAuthDriveProvider(authManager),
                httpClient = httpClient,
                permissionCache = StorageDrivePermissionCache(storage)
            )
        }
    }
}
