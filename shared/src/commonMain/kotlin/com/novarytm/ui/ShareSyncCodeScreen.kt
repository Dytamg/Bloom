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
import kotlinx.coroutines.launch

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
    var secretKey by remember { mutableStateOf<ByteArray?>(storage.getSyncSecretKey()) }
    val primaryColor = LocalAppColors.current.primary
    val clipboardManager = LocalClipboardManager.current
    var showCopiedToast by remember { mutableStateOf(false) }
    
    var syncCode by remember { mutableStateOf<String?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var partnerEmail by remember { mutableStateOf(storage.getPartnerEmail() ?: "") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (secretKey == null) {
            val newKey = rustBridge.generateRandomKey()
            if (newKey.isNotEmpty()) {
                storage.saveSyncSecretKey(newKey)
                secretKey = newKey
            } else {
                errorMessage = "Failed to generate a secure key."
            }
        }
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
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )

            val performSignInAndUpload = {
                scope.launch {
                    isGenerating = true
                    try {
                        val success = googleAuthManager.signIn()
                        if (success) {
                            storage.savePartnerEmail(partnerEmail.trim())
                            // 1. Prepare and Upload payload AFTER sign in
                            val uploadedFileId = syncManager?.prepareAndUploadPayload()
                            if (uploadedFileId == null) {
                                errorMessage = AppStrings.get("failed_upload") + " (prepareAndUploadPayload returned null)"
                            } else {
                                syncCode = syncManager?.generateEphemeralPayload(uploadedFileId)?.decodeToString()
                            }
                        } else {
                            errorMessage = AppStrings.get("auth_cancelled")
                        }
                    } catch (e: Exception) {
                        errorMessage = AppStrings.get("failed_upload") + "\nException: ${e.message}"
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
                            errorMessage ?: "Error",
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
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(AppStrings.get("sync_code_label"), style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Spacer(Modifier.height(8.dp))
                        // The Sync Code is now a very long Base64 string. Use maxLines/ellipsis or small text.
                        Text(syncCode ?: "", style = MaterialTheme.typography.bodySmall, color = primaryColor, textAlign = TextAlign.Center, maxLines = 4)
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                Text(
                    "This code contains a secure link. Please use the Copy button and send it via a secure messaging app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { 
                        syncCode?.let { storage.copyToClipboardSecurely(it) }
                        showCopiedToast = true
                        // VULN-06 FIX: Auto-clear clipboard after 60s to limit secret key exposure
                        // Bind to application-level scope so it survives screen dismissal
                        syncManager?.scope?.launch {
                            kotlinx.coroutines.delay(60_000)
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
                
                Spacer(Modifier.height(16.dp))
                
                var isRevoking by remember { mutableStateOf(false) }
                
                if (isRevoking) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                } else {
                    Button(
                        onClick = {
                            isRevoking = true
                            scope.launch {
                                val success = syncManager?.revokeAndRegenerate() == true
                                if (success) {
                                    syncCode = null
                                    errorMessage = null
                                    // Optionally show a toast or message
                                } else {
                                    errorMessage = "Failed to revoke access. Please try again."
                                }
                                isRevoking = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Revoke Access & Create New Code", style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else if (errorMessage == null) {
                if (isGenerating) {
                    CircularProgressIndicator(color = primaryColor, modifier = Modifier.padding(32.dp))
                    Text(AppStrings.get("signing_in"), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedTextField(
                        value = partnerEmail,
                        onValueChange = { partnerEmail = it },
                        label = { Text("Partner's Google Email") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        singleLine = true
                    )
                    Button(
                        onClick = { performSignInAndUpload() },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        enabled = partnerEmail.isNotBlank() && partnerEmail.contains("@")
                    ) {
                        Text(AppStrings.get("sign_in_button"), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
