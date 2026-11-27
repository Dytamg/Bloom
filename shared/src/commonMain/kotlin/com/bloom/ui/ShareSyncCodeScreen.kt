package com.bloom.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bloom.auth.GoogleAuthManager
import com.bloom.db.BirthControl
import com.bloom.db.CycleEntry
import com.bloom.ffi.RustBridge
import com.bloom.storage.SecureStorageManager
import com.bloom.sync.SAF_SYNC_TARGET_ID
import com.bloom.sync.SafDocumentActions
import com.bloom.sync.SyncManager
import com.bloom.sync.SyncProviderMode
import com.bloom.sync.SyncRole
import com.bloom.sync.rememberSafDocumentLauncher
import com.bloom.utils.AppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSyncCodeScreen(
    ownerId: String,
    storage: SecureStorageManager,
    rustBridge: RustBridge,
    syncManager: SyncManager?,
    googleAuthManager: GoogleAuthManager,
    birthControl: BirthControl?,
    entries: List<CycleEntry>,
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    activeProviderMode: SyncProviderMode = storage.getSyncProviderMode(),
    onProviderModeChanged: (SyncProviderMode) -> Unit = {}
) {
    val appColors = LocalAppColors.current
    val primaryColor = appColors.primary
    var secretKey by remember { mutableStateOf<ByteArray?>(null) }
    var showCopiedToast by remember { mutableStateOf(false) }

    var selectedMode by remember { mutableStateOf(storage.getSyncProviderMode()) }
    var safFileUri by remember { mutableStateOf(storage.getSafFileUri()) }
    var safPermissionTick by remember { mutableStateOf(0) }
    var pendingUploadMode by remember { mutableStateOf<SyncProviderMode?>(null) }

    var syncCode by remember { mutableStateOf<String?>(null) }
    var codeRemainingSeconds by remember { mutableStateOf(0) }
    var isGenerating by remember { mutableStateOf(false) }
    var isRevoking by remember { mutableStateOf(false) }
    var showRevokeConfirmDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var partnerEmail by remember { mutableStateOf(storage.getPartnerEmail() ?: "") }
    var isPaired by remember {
        mutableStateOf(
            storage.getPrimarySyncFileId() != null ||
                !storage.getPartnerEmail().isNullOrBlank() ||
                (storage.getSyncProviderMode() == SyncProviderMode.SAF_FILE && !storage.getSafFileUri().isNullOrBlank())
        )
    }
    val scope = rememberCoroutineScope()

    val safActionsHolder = remember { arrayOfNulls<SafDocumentActions>(1) }
    val onSafFileLinked: (String) -> Unit = { uri ->
        val hasWritePermission = safActionsHolder[0]?.hasValidPermission(uri, true) == true
        if (!hasWritePermission) {
            syncCode = null
            errorMessage = AppStrings.get("saf_owner_write_required")
        } else {
            storage.saveSafFileUri(uri)
            storage.saveSyncProviderMode(SyncProviderMode.SAF_FILE)
            storage.saveSyncRole(SyncRole.OWNER)
            storage.saveLastUploadedContentHash(null)
            safFileUri = uri
            safPermissionTick++
            syncCode = null
            errorMessage = null
            onProviderModeChanged(SyncProviderMode.SAF_FILE)
        }
    }

    val safActions = rememberSafDocumentLauncher(
        onFileCreated = onSafFileLinked,
        onFileOpened = onSafFileLinked,
        storage = storage
    )
    safActionsHolder[0] = safActions

    val hasValidSafFile = remember(safFileUri, safPermissionTick) {
        !safFileUri.isNullOrBlank() && safActions.hasValidPermission(safFileUri, true)
    }

    DisposableEffect(Unit) {
        onDispose {
            secretKey?.fill(0)
        }
    }

    LaunchedEffect(Unit) {
        val loadedKey = withContext(Dispatchers.Default) {
            storage.getSyncSecretKey() ?: rustBridge.generateRandomKey().also { generated ->
                if (generated.isNotEmpty()) {
                    storage.saveSyncSecretKey(generated)
                }
            }
        }
        if (loadedKey.isNotEmpty()) {
            secretKey?.fill(0)
            secretKey = loadedKey
        } else {
            errorMessage = AppStrings.get("sync_key_gen_failed")
        }
    }

    LaunchedEffect(syncCode) {
        if (syncCode != null) {
            codeRemainingSeconds = 300
            while (codeRemainingSeconds > 0) {
                delay(1_000L)
                codeRemainingSeconds--
            }
        } else {
            codeRemainingSeconds = 0
        }
    }

    LaunchedEffect(syncManager, pendingUploadMode, activeProviderMode) {
        val targetMode = pendingUploadMode ?: return@LaunchedEffect
        val manager = syncManager ?: return@LaunchedEffect
        if (activeProviderMode != targetMode || storage.getSyncProviderMode() != targetMode) return@LaunchedEffect
        try {
            val uploadedFileId = withContext(Dispatchers.Default) {
                manager.prepareAndUploadPayload()
            }
            if (uploadedFileId == null) {
                errorMessage = if (targetMode == SyncProviderMode.SAF_FILE) {
                    AppStrings.get("saf_write_failed")
                } else {
                    AppStrings.get("failed_upload")
                }
            } else {
                val targetId = if (targetMode == SyncProviderMode.SAF_FILE) SAF_SYNC_TARGET_ID else uploadedFileId
                val generatedCode = withContext(Dispatchers.Default) {
                    val payloadBytes = manager.generateEphemeralPayload(targetId)
                    payloadBytes.let { bytes ->
                        try {
                            bytes.decodeToString()
                        } finally {
                            bytes.fill(0)
                        }
                    }
                }
                if (generatedCode.isEmpty()) {
                    errorMessage = AppStrings.get("pairing_code_gen_failed")
                } else {
                    isPaired = true
                    showCopiedToast = false
                    syncCode = generatedCode
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            errorMessage = if (targetMode == SyncProviderMode.SAF_FILE) {
                AppStrings.get("saf_write_failed")
            } else {
                AppStrings.get("failed_upload")
            }
        } finally {
            pendingUploadMode = null
            isGenerating = false
        }
    }

    val performCloudSignInAndUpload = {
        scope.launch {
            isGenerating = true
            errorMessage = null
            try {
                val success = googleAuthManager.signIn()
                if (success) {
                    storage.savePartnerEmail(partnerEmail.trim())
                    storage.saveSyncProviderMode(SyncProviderMode.DRIVE_API)
                    storage.saveSyncRole(SyncRole.OWNER)
                    onProviderModeChanged(SyncProviderMode.DRIVE_API)
                    pendingUploadMode = SyncProviderMode.DRIVE_API
                } else {
                    errorMessage = AppStrings.get("auth_cancelled")
                    isGenerating = false
                }
            } catch (e: CancellationException) {
                isGenerating = false
                throw e
            } catch (_: Exception) {
                errorMessage = AppStrings.get("failed_upload")
                isGenerating = false
            }
        }
    }

    val performSafUploadAndGenerate = {
        if (!hasValidSafFile) {
            errorMessage = AppStrings.get("saf_select_file_first")
        } else {
            isGenerating = true
            errorMessage = null
            storage.saveSyncProviderMode(SyncProviderMode.SAF_FILE)
            storage.saveSyncRole(SyncRole.OWNER)
            onProviderModeChanged(SyncProviderMode.SAF_FILE)
            pendingUploadMode = SyncProviderMode.SAF_FILE
        }
    }

    if (showRevokeConfirmDialog) {
        val isSafActive = storage.getSyncProviderMode() == SyncProviderMode.SAF_FILE
        AlertDialog(
            onDismissRequest = { if (!isRevoking) showRevokeConfirmDialog = false },
            containerColor = appColors.surface,
            title = { Text(AppStrings.get("revoke_dialog_title")) },
            text = {
                Text(
                    if (isSafActive) {
                        AppStrings.get("revoke_dialog_saf_body")
                    } else {
                        AppStrings.get("revoke_dialog_cloud_body")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isRevoking,
                    onClick = {
                        showRevokeConfirmDialog = false
                        isRevoking = true
                        scope.launch {
                            try {
                                val success = withContext(Dispatchers.Default) {
                                    syncManager?.revokeAndRegenerate() == true
                                }
                                if (success) {
                                    secretKey?.fill(0)
                                    secretKey = withContext(Dispatchers.Default) {
                                        storage.getSyncSecretKey()
                                    }
                                    syncCode = null
                                    errorMessage = null
                                    isPaired = false
                                    partnerEmail = ""
                                    storage.savePartnerEmail("")
                                } else {
                                    errorMessage = AppStrings.get("revoke_failed")
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                errorMessage = AppStrings.get("revoke_failed")
                            } finally {
                                isRevoking = false
                            }
                        }
                    }
                ) {
                    Text(
                        AppStrings.get("revoke_access_btn"),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isRevoking,
                    onClick = { showRevokeConfirmDialog = false }
                ) {
                    Text(AppStrings.get("cancel"), color = appColors.textSecondary)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(AppStrings.get("pair_with_partner_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenPermissions) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = primaryColor)
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
            Text(
                AppStrings.get("link_your_partner"),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = appColors.textPrimary
            )
            Text(
                text = if (selectedMode == SyncProviderMode.SAF_FILE) {
                    AppStrings.get("saf_mode_subtitle")
                } else {
                    AppStrings.get("sign_in_instruction")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = appColors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )

            SyncModeSelector(
                selectedMode = selectedMode,
                enabled = !isGenerating && !isRevoking,
                onModeSelected = { newMode ->
                    selectedMode = newMode
                    syncCode = null
                    errorMessage = null
                }
            )

            Spacer(Modifier.height(12.dp))

            Text(
                AppStrings.get("pairing_code_lifetime_note"),
                style = MaterialTheme.typography.bodySmall,
                color = appColors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 20.dp)
            )

            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            errorMessage ?: AppStrings.get("failed_upload"),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                if (selectedMode == SyncProviderMode.SAF_FILE) {
                                    if (hasValidSafFile) {
                                        performSafUploadAndGenerate()
                                    } else {
                                        errorMessage = null
                                        safActions.createSyncFile("Bloom-sync.bloom")
                                    }
                                } else {
                                    performCloudSignInAndUpload()
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            )
                        ) {
                            Text(
                                text = when {
                                    selectedMode != SyncProviderMode.SAF_FILE -> AppStrings.get("retry_sign_in")
                                    hasValidSafFile -> AppStrings.get("try_again")
                                    else -> AppStrings.get("saf_create_file_btn")
                                },
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            if (syncCode != null) {
                val mins = codeRemainingSeconds / 60
                val secs = (codeRemainingSeconds % 60).toString().padStart(2, '0')
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = appColors.surface),
                    border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.2f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(AppStrings.get("sync_code_label"), style = MaterialTheme.typography.labelMedium, color = appColors.textSecondary)
                        Spacer(Modifier.height(8.dp))
                        Text(syncCode ?: "", style = MaterialTheme.typography.bodySmall, color = appColors.textPrimary, textAlign = TextAlign.Center, maxLines = 4)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (codeRemainingSeconds > 0) {
                                AppStrings.get("pairing_code_expires_in").replace("{0}", "$mins:$secs")
                            } else {
                                AppStrings.get("pairing_code_expired_limit")
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (codeRemainingSeconds > 0) appColors.textPrimary else MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (selectedMode == SyncProviderMode.SAF_FILE) {
                        AppStrings.get("saf_share_instruction")
                    } else {
                        val targetEmail = partnerEmail.trim().ifBlank { AppStrings.get("cloud_authorized_email_fallback") }
                        AppStrings.get("cloud_code_share_note").replace("{0}", targetEmail)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = appColors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(Modifier.height(16.dp))

                Button(
                    enabled = codeRemainingSeconds > 0,
                    onClick = {
                        syncCode?.let { storage.copyToClipboardSecurely(it) }
                        showCopiedToast = true
                        (syncManager?.scope ?: CoroutineScope(Dispatchers.Default)).launch {
                            delay(60_000)
                            storage.copyToClipboardSecurely("")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (showCopiedToast) AppStrings.get("copied_toast") else AppStrings.get("copy_code"),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (errorMessage == null) {
                if (isGenerating) {
                    CircularProgressIndicator(color = primaryColor, modifier = Modifier.padding(32.dp))
                    Text(
                        text = if (selectedMode == SyncProviderMode.SAF_FILE) {
                            AppStrings.get("saf_encrypting_writing")
                        } else {
                            AppStrings.get("signing_in")
                        },
                        color = appColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (selectedMode == SyncProviderMode.SAF_FILE) {
                    SafOwnerSetupSection(
                        hasValidSafFile = hasValidSafFile,
                        onCreateFile = { safActions.createSyncFile("Bloom-sync.bloom") },
                        onSelectExistingFile = { safActions.openSyncFile() },
                        onGenerateCode = { performSafUploadAndGenerate() }
                    )
                } else {
                    OutlinedTextField(
                        value = partnerEmail,
                        onValueChange = { partnerEmail = it },
                        label = { Text(AppStrings.get("partner_google_email_label")) },
                        supportingText = {
                            Text(
                                AppStrings.get("partner_google_email_hint"),
                                color = appColors.textSecondary
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                    val isEmailValid = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$").matches(partnerEmail.trim())
                    Button(
                        onClick = { performCloudSignInAndUpload() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        enabled = isEmailValid
                    ) {
                        Text(
                            AppStrings.get("sign_in_button"),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            if (isPaired || syncCode != null) {
                Spacer(Modifier.height(16.dp))
                if (isRevoking) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                } else {
                    Button(
                        onClick = { showRevokeConfirmDialog = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text(
                            AppStrings.get("revoke_access_new_code_btn"),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncModeSelector(
    selectedMode: SyncProviderMode,
    enabled: Boolean,
    onModeSelected: (SyncProviderMode) -> Unit
) {
    val appColors = LocalAppColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = appColors.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SyncModeTab(
                label = AppStrings.get("sync_mode_cloud"),
                icon = Icons.Default.Cloud,
                selected = selectedMode == SyncProviderMode.DRIVE_API,
                enabled = enabled,
                onClick = { onModeSelected(SyncProviderMode.DRIVE_API) },
                modifier = Modifier.weight(1f)
            )
            SyncModeTab(
                label = AppStrings.get("sync_mode_saf"),
                icon = Icons.Default.FolderOpen,
                selected = selectedMode == SyncProviderMode.SAF_FILE,
                enabled = enabled,
                onClick = { onModeSelected(SyncProviderMode.SAF_FILE) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SyncModeTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "SyncModeTabBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "SyncModeTabFg"
    )
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SafOwnerSetupSection(
    hasValidSafFile: Boolean,
    onCreateFile: () -> Unit,
    onSelectExistingFile: () -> Unit,
    onGenerateCode: () -> Unit
) {
    val appColors = LocalAppColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = appColors.surface),
        border = BorderStroke(1.dp, appColors.secondary.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (hasValidSafFile) Icons.Default.CheckCircle else Icons.Default.NoteAdd,
                    contentDescription = null,
                    tint = if (hasValidSafFile) appColors.secondary else appColors.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (hasValidSafFile) AppStrings.get("saf_file_ready_title") else AppStrings.get("saf_step1_title"),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = appColors.textPrimary
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = AppStrings.get("saf_share_instruction"),
                style = MaterialTheme.typography.bodySmall,
                color = appColors.textSecondary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = AppStrings.get("saf_drive_refresh_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = appColors.textSecondary
            )
            Spacer(Modifier.height(14.dp))
            if (hasValidSafFile) {
                OutlinedButton(
                    onClick = onCreateFile,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        AppStrings.get("saf_change_file_btn"),
                        color = appColors.textPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Button(
                    onClick = onCreateFile,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = appColors.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.NoteAdd, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        AppStrings.get("saf_create_file_btn"),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onSelectExistingFile,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(
                        AppStrings.get("saf_select_existing_btn"),
                        color = appColors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (hasValidSafFile) {
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onGenerateCode,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = appColors.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(
                AppStrings.get("saf_generate_code_btn"),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}
