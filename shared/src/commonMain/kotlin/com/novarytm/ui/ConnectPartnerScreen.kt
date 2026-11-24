@file:OptIn(kotlin.time.ExperimentalTime::class)
package com.novarytm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.ktor.util.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock

import com.novarytm.auth.GoogleAuthManager
import com.novarytm.ffi.RustBridge
import com.novarytm.storage.SecureStorageManager
import com.novarytm.sync.SyncManager
import com.novarytm.utils.AppStrings
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SYNC_CODE_EXPIRATION_MS = 300_000L // 5 minutes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectPartnerScreen(
    rustBridge: RustBridge,
    storage: SecureStorageManager,
    googleAuthManager: GoogleAuthManager,
    onIdEntered: (String, ByteArray?) -> Unit,
    onBack: () -> Unit
) {
    var syncCodeInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lockoutRemainingSeconds by remember { mutableStateOf(storage.getSyncCodeLockoutRemainingSeconds()) }
    var isLoading by remember { mutableStateOf(false) }
    val primaryColor = LocalAppColors.current.primary
    val scope = rememberCoroutineScope()

    LaunchedEffect(lockoutRemainingSeconds > 0L) {
        while (lockoutRemainingSeconds > 0L) {
            delay(1_000L)
            val remaining = storage.getSyncCodeLockoutRemainingSeconds()
            lockoutRemainingSeconds = remaining
            if (remaining <= 0L && errorMessage?.startsWith("Too many") == true) {
                errorMessage = null
            }
        }
    }

    val recordFailedSyncAttempt: (String) -> Unit = { fallbackMessage ->
        storage.recordSyncCodeFailedAttempt()
        val remaining = storage.getSyncCodeLockoutRemainingSeconds()
        lockoutRemainingSeconds = remaining
        errorMessage = if (remaining > 0L) {
            "Too many failed attempts. Try again in ${remaining}s."
        } else {
            fallbackMessage
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(AppStrings.get("connect_title")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                Icons.Default.GroupAdd,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(64.dp)
            )
            
            Spacer(Modifier.height(24.dp))
            
            Text(
                AppStrings.get("enter_details"),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF3E3636)
            )
            
            Spacer(Modifier.height(16.dp))
            
            Text(
                AppStrings.get("paste_code_instruction"),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF5C5C5C),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "When prompted, sign in with the exact Google account email authorized by your partner. One-time pairing codes expire 5:00 after generation.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF5C5C5C),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(36.dp))

            val activeSupportMessage = if (lockoutRemainingSeconds > 0L) {
                "Too many failed attempts. Try again in ${lockoutRemainingSeconds}s."
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
                    unfocusedBorderColor = Color.LightGray
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
                                errorMessage = "Too many failed attempts. Try again in ${currentLockout}s."
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

                                    // Require successful Google Sign-In before completing pairing
                                    val signedIn = googleAuthManager.signIn()
                                    if (signedIn) {
                                        onIdEntered(fileId, keyBytes)
                                    } else {
                                        keyBytes.fill(0)
                                        errorMessage = AppStrings.get("auth_cancelled")
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
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                enabled = !isLoading && lockoutRemainingSeconds <= 0L
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else if (lockoutRemainingSeconds > 0L) {
                    Text("Locked (${lockoutRemainingSeconds}s)", style = MaterialTheme.typography.titleMedium)
                } else {
                    Text(AppStrings.get("connect_button"), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
