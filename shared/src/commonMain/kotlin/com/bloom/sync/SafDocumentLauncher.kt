package com.bloom.sync

import androidx.compose.runtime.Composable
import com.bloom.auth.GoogleAuthManager
import com.bloom.storage.SecureStorageManager

class SafDocumentActions(
    val createSyncFile: (suggestedName: String) -> Unit,
    val openSyncFile: () -> Unit,
    val hasValidPermission: (uriString: String?, requireWrite: Boolean) -> Boolean
)

@Composable
expect fun rememberSafDocumentLauncher(
    onFileCreated: (String) -> Unit,
    onFileOpened: (String) -> Unit,
    storage: SecureStorageManager? = null
): SafDocumentActions

@Composable
expect fun rememberSyncBlobClient(
    storage: SecureStorageManager,
    authManager: GoogleAuthManager,
    providerMode: SyncProviderMode
): DriveBlobClient
