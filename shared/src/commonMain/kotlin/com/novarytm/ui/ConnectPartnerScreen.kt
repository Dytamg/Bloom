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
import com.novarytm.sync.SyncManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

private const val SYNC_CODE_EXPIRATION_MS = 300_000L // 5 minutes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectPartnerScreen(
    rustBridge: RustBridge,
    googleAuthManager: GoogleAuthManager,
    onIdEntered: (String, ByteArray?) -> Unit,
    onBack: () -> Unit
) {
    var syncCodeInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val primaryColor = LocalAppColors.current.primary
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect with Partner") },
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
                "Enter Partner's Details",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF3E3636)
            )
            
            Spacer(Modifier.height(16.dp))
            
            Text(
                "Paste the 5-minute Sync Code provided by your partner.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(48.dp))

            OutlinedTextField(
                value = syncCodeInput,
                onValueChange = { 
                    syncCodeInput = it
                    errorMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Sync Code") },
                singleLine = true,
                isError = errorMessage != null,
                supportingText = { errorMessage?.let { Text(it) } },
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
                            val cleanInput = syncCodeInput.trim()
                            val colonIndex = cleanInput.indexOf(':')
                            if (colonIndex == -1) {
                                errorMessage = "Invalid Sync Code format."
                                return@launch
                            }
                            val ephemeralKey = cleanInput.substring(0, colonIndex)
                            val ciphertext = cleanInput.substring(colonIndex + 1)

                            @OptIn(ExperimentalEncodingApi::class)
                            val decoded = rustBridge.decryptFromSync(ciphertext, ephemeralKey)
                            if (decoded.isEmpty()) {
                                errorMessage = "Invalid or expired Sync Code."
                                return@launch
                            }
                            val parts = decoded.split("|")
                            if (parts.size == 3) {
                                val encryptionKeyBase64 = parts[0]
                                val timestampStr = parts[1]
                                val fileId = parts[2]
                                
                                val timestamp = timestampStr.toLongOrNull()
                                val now = Clock.System.now().toEpochMilliseconds()
                                
                                // 5 minutes (with clock skew protection)
                                if (timestamp != null && kotlin.math.abs(now - timestamp) <= SYNC_CODE_EXPIRATION_MS) {
                                    val keyBytes = Base64.Default.decode(encryptionKeyBase64)
                                        
                                    // Trigger Google Sign In before completing
                                    googleAuthManager.signIn()
                                    onIdEntered(fileId, keyBytes)
                                } else {
                                    errorMessage = "This Sync Code has expired or your clock is out of sync. Please check device time and ask for a new code."
                                }
                            } else {
                                errorMessage = "Invalid code format."
                            }
                        } catch (e: Exception) {
                            errorMessage = "Invalid Sync Code."
                        }
                        isLoading = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text("Connect and Set PIN", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
