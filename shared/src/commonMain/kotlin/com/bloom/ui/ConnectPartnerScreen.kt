@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.bloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bloom.auth.GoogleAuthManager
import com.bloom.ffi.RustBridge
import com.bloom.storage.SecureStorageManager
import com.bloom.sync.SAF_SYNC_TARGET_ID
import com.bloom.sync.SafDocumentActions
import com.bloom.sync.SyncProviderMode
import com.bloom.sync.SyncRole
import com.bloom.sync.rememberSafDocumentLauncher
import com.bloom.utils.AppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock

private const val SYNC_CODE_EXPIRATION_MS = 300_000L // 5 minutes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectPartnerScreen(
    rustBridge: RustBridge,
    storage: SecureStorageManager,
    googleAuthManager: GoogleAuthManager,
    onIdEntered: (String, ByteArray?) -> Unit,
    onBack: () -> Unit,
    onProviderModeChanged: (SyncProviderMode) -> Unit = {}
) {
    val appColors = LocalAppColors.current
    val primaryColor = appColors.primary
    var syncCodeInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lockoutRemainingSeconds by remember { mutableStateOf(storage.getSyncCodeLockoutRemainingSeconds()) }
    var isLoading by remember { mutableStateOf(false) }
    var pendingSafKeyBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingSafOnboarding by remember { mutableStateOf<Pair<String, ByteArray?>?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            pendingSafKeyBytes?.fill(0)
            pendingSafOnboarding?.second?.fill(0)
        }
    }

    LaunchedEffect(pendingSafOnboarding) {
        val pending = pendingSafOnboarding ?: return@LaunchedEffect
        pendingSafOnboarding = null
        onIdEntered(pending.first, pending.second)
    }

    val safActionsHolder = remember { arrayOfNulls<SafDocumentActions>(1) }
    val safActions = rememberSafDocumentLauncher(
        onFileCreated = {},
        onFileOpened = { uri ->
            val hasReadPermission = safActionsHolder[0]?.hasValidPermission(uri, false) == true
            if (!hasReadPermission) {
                errorMessage = AppStrings.get("saf_partner_permission_failed")
                return@rememberSafDocumentLauncher
            }
            val keyForOnboarding = pendingSafKeyBytes
            pendingSafKeyBytes = null
            errorMessage = null
            storage.saveSafFileUri(uri)
            storage.saveSyncProviderMode(SyncProviderMode.SAF_FILE)
            storage.saveSyncRole(SyncRole.PARTNER)
            onProviderModeChanged(SyncProviderMode.SAF_FILE)
            pendingSafOnboarding = SAF_SYNC_TARGET_ID to keyForOnboarding
        },
        storage = storage
    )
    safActionsHolder[0] = safActions

    LaunchedEffect(lockoutRemainingSeconds > 0L) {
        while (lockoutRemainingSeconds > 0L) {
            delay(1_000L)
            val remaining = storage.getSyncCodeLockoutRemainingSeconds()
            lockoutRemainingSeconds = remaining
            if (remaining <= 0L && storage.getSyncCodeFailedAttempts() > 0) {
                errorMessage = null
            }
        }
    }

    val recordFailedSyncAttempt: (String) -> Unit = { fallbackMessage ->
        storage.recordSyncCodeFailedAttempt()
        val remaining = storage.getSyncCodeLockoutRemainingSeconds()
        lockoutRemainingSeconds = remaining
        errorMessage = if (remaining > 0L) {
            AppStrings.get("too_many_attempts_wait").replace("{0}", remaining.toString())
        } else {
            fallbackMessage
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(AppStrings.get("connect_title")) },
                navigationIcon = {
                    IconButton(onClick = {
                        pendingSafKeyBytes?.fill(0)
                        pendingSafKeyBytes = null
                        pendingSafOnboarding?.second?.fill(0)
                        pendingSafOnboarding = null
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                if (pendingSafKeyBytes != null) Icons.Default.FolderOpen else Icons.Default.GroupAdd,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(64.dp)
            )

            Spacer(Modifier.height(24.dp))

            if (pendingSafKeyBytes != null) {
                SafPartnerFileStepCard(
                    errorMessage = errorMessage,
                    onSelectSharedFile = { safActions.openSyncFile() },
                    onUseDifferentCode = {
                        pendingSafKeyBytes?.fill(0)
                        pendingSafKeyBytes = null
                        errorMessage = null
                    }
                )
                return@Column
            }

            Text(
                AppStrings.get("enter_details"),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = appColors.textPrimary
            )

            Spacer(Modifier.height(16.dp))

            Text(
                AppStrings.get("paste_code_instruction"),
                style = MaterialTheme.typography.bodyMedium,
                color = appColors.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                AppStrings.get("connect_modes_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = appColors.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(36.dp))

            val activeSupportMessage = if (lockoutRemainingSeconds > 0L) {
                AppStrings.get("too_many_attempts_wait").replace("{0}", lockoutRemainingSeconds.toString())
            } else {
                errorMessage
            }

            OutlinedTextField(
                value = syncCodeInput,
                onValueChange = {
                    syncCodeInput = it
                    if (lockoutRemainingSeconds <= 0L) {
                        errorMessage = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(AppStrings.get("sync_code_field")) },
                singleLine = true,
                enabled = !isLoading && lockoutRemainingSeconds <= 0L,
                isError = activeSupportMessage != null,
                supportingText = {
                    activeSupportMessage?.let { msg ->
                        Text(
                            text = msg,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            }
                        )
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = primaryColor,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        try {
                            val currentLockout = storage.getSyncCodeLockoutRemainingSeconds()
                            if (currentLockout > 0L) {
                                lockoutRemainingSeconds = currentLockout
                                errorMessage = AppStrings.get("too_many_attempts_wait").replace("{0}", currentLockout.toString())
                                return@launch
                            }
                            val cleanInput = syncCodeInput.trim()
                            val colonIndex = cleanInput.indexOf(':')
                            if (colonIndex == -1) {
                                recordFailedSyncAttempt(AppStrings.get("invalid_format"))
                                return@launch
                            }
                            val ephemeralKey = cleanInput.substring(0, colonIndex)
                            val ciphertext = cleanInput.substring(colonIndex + 1)

                            val decoded = withContext(Dispatchers.Default) {
                                rustBridge.decryptFromSync(ciphertext, ephemeralKey)
                            }
                            if (decoded.isEmpty()) {
                                recordFailedSyncAttempt(AppStrings.get("invalid_code"))
                                return@launch
                            }
                            val parts = decoded.split("|")
                            if (parts.size == 3) {
                                val encryptionKeyBase64 = parts[0]
                                val timestampStr = parts[1]
                                val fileId = parts[2]

                                val timestamp = timestampStr.toLongOrNull()
                                val now = Clock.System.now().toEpochMilliseconds()

                                // 5 minutes (with asymmetric clock skew protection: up to 60s future skew)
                                if (timestamp != null && (now - timestamp) in -60_000L..SYNC_CODE_EXPIRATION_MS) {
                                    @OptIn(ExperimentalEncodingApi::class)
                                    val keyBytes = withContext(Dispatchers.Default) {
                                        Base64.Default.decode(encryptionKeyBase64)
                                    }
                                    storage.resetSyncCodeFailedAttempts()
                                    lockoutRemainingSeconds = 0L

                                    if (fileId == SAF_SYNC_TARGET_ID) {
                                        pendingSafKeyBytes?.fill(0)
                                        pendingSafKeyBytes = keyBytes
                                        errorMessage = null
                                    } else {
                                        val signedIn = googleAuthManager.signIn()
                                        if (signedIn) {
                                            storage.saveSafFileUri(null)
                                            storage.saveSyncProviderMode(SyncProviderMode.DRIVE_API)
                                            storage.saveSyncRole(SyncRole.PARTNER)
                                            onProviderModeChanged(SyncProviderMode.DRIVE_API)
                                            onIdEntered(fileId, keyBytes)
                                        } else {
                                            keyBytes.fill(0)
                                            errorMessage = AppStrings.get("auth_cancelled")
                                        }
                                    }
                                } else {
                                    recordFailedSyncAttempt(AppStrings.get("expired_code"))
                                }
                            } else {
                                recordFailedSyncAttempt(AppStrings.get("invalid_format"))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            recordFailedSyncAttempt(AppStrings.get("invalid_code"))
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryColor,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                enabled = !isLoading && lockoutRemainingSeconds <= 0L
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                } else if (lockoutRemainingSeconds > 0L) {
                    Text(
                        AppStrings.get("locked_seconds").replace("{0}", lockoutRemainingSeconds.toString()),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        AppStrings.get("connect_button"),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun SafPartnerFileStepCard(
    errorMessage: String?,
    onSelectSharedFile: () -> Unit,
    onUseDifferentCode: () -> Unit
) {
    val appColors = LocalAppColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = appColors.surface),
        border = BorderStroke(1.dp, appColors.primary.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = AppStrings.get("saf_select_shared_file"),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = appColors.textPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = AppStrings.get("saf_partner_step_body"),
                style = MaterialTheme.typography.bodyMedium,
                color = appColors.textSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = AppStrings.get("saf_drive_refresh_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = appColors.textSecondary,
                textAlign = TextAlign.Center
            )
            if (!errorMessage.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onSelectSharedFile,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = appColors.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    AppStrings.get("saf_select_shared_file"),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onUseDifferentCode,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(
                    AppStrings.get("saf_use_different_code"),
                    color = appColors.textSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
