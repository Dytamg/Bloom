package com.novarytm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novarytm.ffi.RustBridge
import com.novarytm.storage.SecureStorageManager
import com.novarytm.sync.SyncManager
import com.novarytm.auth.GoogleAuthManager
import com.novarytm.db.BirthControl
import com.novarytm.db.CycleEntry
import com.novarytm.utils.AppStrings
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
    onOpenPermissions: () -> Unit
) {
    var secretKey by remember { mutableStateOf<ByteArray?>(null) }
    val primaryColor = LocalAppColors.current.primary
    var showCopiedToast by remember { mutableStateOf(false) }
    
    var syncCode by remember { mutableStateOf<String?>(null) }
    var codeRemainingSeconds by remember { mutableStateOf(0) }
    var isGenerating by remember { mutableStateOf(false) }
    var isRevoking by remember { mutableStateOf(false) }
    var showRevokeConfirmDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var partnerEmail by remember { mutableStateOf(storage.getPartnerEmail() ?: "") }
    var isPaired by remember {
        mutableStateOf(storage.getPrimarySyncFileId() != null || !storage.getPartnerEmail().isNullOrBlank())
    }
    val scope = rememberCoroutineScope()

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
            errorMessage = "Unable to generate a secure sync key. Please try again."
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

    if (showRevokeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { if (!isRevoking) showRevokeConfirmDialog = false },
            containerColor = LocalAppColors.current.surface,
            title = { Text("Revoke Partner Access?") },
            text = {
                Text(
                    "This immediately disconnects your partner, deletes the shared sync file from Google Drive, and rotates your encryption key. Ongoing partner sync will stop until you generate and share a new code."
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
                                    errorMessage = "Unable to revoke partner access right now. Please check your connection and try again."
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                errorMessage = "Unable to revoke partner access right now. Please check your connection and try again."
                            } finally {
                                isRevoking = false
                            }
                        }
                    }
                ) {
                    Text("Revoke Access", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isRevoking,
                    onClick = { showRevokeConfirmDialog = false }
                ) {
                    Text("Cancel", color = Color(0xFF5C5C5C))
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenPermissions) {
                        Icon(Icons.Default.Settings, contentDescription = "Permissions", tint = primaryColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState()).padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                AppStrings.get("link_your_partner"),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF3E3636)
            )
            Text(
                AppStrings.get("sign_in_instruction"),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF5C5C5C),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
            )
            Text(
                "Note: The one-time pairing code expires in 5:00. Once paired, Partner sync stays active continuously until you tap Revoke Access.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF5C5C5C),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 20.dp)
            )

            val performSignInAndUpload = {
                scope.launch {
                    isGenerating = true
                    errorMessage = null
                    try {
                        val success = googleAuthManager.signIn()
                        if (success) {
                            storage.savePartnerEmail(partnerEmail.trim())
                            // 1. Prepare and Upload payload AFTER sign in (offloaded off Main thread)
                            val uploadedFileId = withContext(Dispatchers.Default) {
                                syncManager?.prepareAndUploadPayload()
                            }
                            if (uploadedFileId == null) {
                                errorMessage = AppStrings.get("failed_upload")
                            } else {
                                val generatedCode = withContext(Dispatchers.Default) {
                                    val payloadBytes = syncManager?.generateEphemeralPayload(uploadedFileId)
                                    payloadBytes?.let { bytes ->
                                        try {
                                            bytes.decodeToString()
                                        } finally {
                                            bytes.fill(0)
                                        }
                                    }
                                }
                                if (generatedCode.isNullOrEmpty()) {
                                    errorMessage = "Could not generate a pairing code. Please try again."
                                } else {
                                    isPaired = true
                                    showCopiedToast = false
                                    syncCode = generatedCode
                                }
                            }
                        } else {
                            errorMessage = AppStrings.get("auth_cancelled")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        errorMessage = AppStrings.get("failed_upload")
                    } finally {
                        isGenerating = false
                    }
                }
            }

            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            errorMessage ?: "Something went wrong. Please try again.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { performSignInAndUpload() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(AppStrings.get("retry_sign_in"))
                        }
                    }
                }
            }

            if (syncCode != null) {
                val mins = codeRemainingSeconds / 60
                val secs = (codeRemainingSeconds % 60).toString().padStart(2, '0')
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(AppStrings.get("sync_code_label"), style = MaterialTheme.typography.labelMedium, color = Color(0xFF5C5C5C))
                        Spacer(Modifier.height(8.dp))
                        // The Sync Code is now a very long Base64 string. Use maxLines/ellipsis or small text.
                        Text(syncCode ?: "", style = MaterialTheme.typography.bodySmall, color = primaryColor, textAlign = TextAlign.Center, maxLines = 4)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (codeRemainingSeconds > 0) {
                                "One-time pairing code expires in $mins:$secs"
                            } else {
                                "One-time pairing code expired (5:00 limit reached)"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (codeRemainingSeconds > 0) Color(0xFF3E3636) else MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                Text(
                    "Copy and send this one-time code via a secure messenger. Your partner must sign in with ${partnerEmail.trim().ifBlank { "their authorized Google email" }} within 5:00. After pairing, Partner sync stays active until you tap Revoke Access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF5C5C5C),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(16.dp))

                Button(
                    enabled = codeRemainingSeconds > 0,
                    onClick = { 
                        syncCode?.let { storage.copyToClipboardSecurely(it) }
                        showCopiedToast = true
                        // VULN-06 FIX: Auto-clear clipboard after 60s to limit secret key exposure
                        // Bind to application-level scope so it survives screen dismissal
                        (syncManager?.scope ?: CoroutineScope(Dispatchers.Default)).launch {
                            delay(60_000)
                            storage.copyToClipboardSecurely("")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (showCopiedToast) AppStrings.get("copied_toast") else AppStrings.get("copy_code"), style = MaterialTheme.typography.titleMedium)
                }
            } else if (errorMessage == null) {
                if (isGenerating) {
                    CircularProgressIndicator(color = primaryColor, modifier = Modifier.padding(32.dp))
                    Text(AppStrings.get("signing_in"), color = Color(0xFF5C5C5C), style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(
                        value = partnerEmail,
                        onValueChange = { partnerEmail = it },
                        label = { Text("Partner's Google Email") },
                        supportingText = {
                            Text(
                                "Google Drive restricts sync access to this exact email. Ask your partner to sign in with this same Google account.",
                                color = Color(0xFF5C5C5C)
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        singleLine = true
                    )
                    val isEmailValid = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$").matches(partnerEmail.trim())
                    Button(
                        onClick = { performSignInAndUpload() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        enabled = isEmailValid
                    ) {
                        Text(AppStrings.get("sign_in_button"), style = MaterialTheme.typography.titleMedium)
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
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Revoke Access & Create New Code", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
