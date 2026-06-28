@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.novarytm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novarytm.auth.BiometricPromptEffect
import com.novarytm.ffi.RustBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.novarytm.storage.SecureStorageManager

import kotlin.time.Clock

@Composable
fun LockScreen(
    rustBridge: RustBridge,
    storage: SecureStorageManager,
    pinHash: ByteArray?,
    onUnlocked: () -> Unit,
    onReset: () -> Unit
) {
    var enteredPinBytes by remember { mutableStateOf(ByteArray(0)) }
    var failedAttempts by remember { mutableStateOf(storage.getFailedPinAttempts()) }
    var lockoutUntilMs by remember { mutableStateOf(storage.getPinLockoutUntil()) }
    var currentTimeMs by remember { mutableStateOf(Clock.System.now().toEpochMilliseconds()) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    val isLockedOut = currentTimeMs < lockoutUntilMs
    val remainingLockoutSec = if (isLockedOut) ((lockoutUntilMs - currentTimeMs) / 1000).coerceAtLeast(1) else 0

    var triggerBiometric by remember { mutableStateOf(if (storage.isBiometricsEnabled() && !isLockedOut) 1 else 0) }
    var authError by remember { mutableStateOf<String?>(null) }
    var isUnlocked by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val primaryColor = LocalAppColors.current.primary

    val recordFailedAttempt: (String?) -> Boolean = { prefix ->
        failedAttempts++
        storage.saveFailedPinAttempts(failedAttempts)

        if (failedAttempts >= 20) {
            onReset()
            true
        } else {
            val shift = (failedAttempts - 5).coerceAtLeast(0)
            val lockoutDurationMs: Long = if (failedAttempts >= 5) {
                (30_000L * (1L shl shift.coerceAtMost(20))).coerceAtMost(86400_000L)
            } else {
                0L
            }

            if (lockoutDurationMs > 0) {
                lockoutUntilMs = Clock.System.now().toEpochMilliseconds() + lockoutDurationMs
                storage.savePinLockoutUntil(lockoutUntilMs)
                authError = "Locked out due to failed attempts"
            } else {
                val msgPrefix = prefix ?: "Incorrect PIN"
                authError = "$msgPrefix (${20 - failedAttempts} attempts remaining before data wipe)"
            }
            false
        }
    }

    LaunchedEffect(lockoutUntilMs) {
        while (Clock.System.now().toEpochMilliseconds() < lockoutUntilMs) {
            currentTimeMs = Clock.System.now().toEpochMilliseconds()
            kotlinx.coroutines.delay(1000)
        }
        currentTimeMs = Clock.System.now().toEpochMilliseconds()
    }

    // Navigation Trigger: Safely navigate and allow parent to handle backstack
    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            onUnlocked()
        }
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Wipe All Data?") },
            text = { Text("Are you sure you want to wipe and reset Bloom? This action cannot be undone and all local health data will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmDialog = false
                        onReset()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Wipe and Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    BiometricPromptEffect(
        trigger = triggerBiometric,
        title = "Unlock Bloom",
        subtitle = "Confirm your identity",
        onSuccess = {
            if (isLockedOut) return@BiometricPromptEffect
            scope.launch {
                isLoading = true
                val success = withContext(Dispatchers.IO) {
                    val key = storage.getMasterKey()
                    if (key != null) {
                        rustBridge.unlockWithBiometric(key)
                    } else false
                }
                if (success) {
                    storage.saveFailedPinAttempts(0)
                    storage.savePinLockoutUntil(0L)
                    isUnlocked = true
                } else {
                    recordFailedAttempt("Biometric unlock failed")
                }
                isLoading = false
            }
        },
        onError = { errString ->
            if (!isLockedOut) {
                if (errString.contains("cancel", ignoreCase = true) || errString.contains("user", ignoreCase = true)) {
                    authError = "Biometric unlock canceled"
                } else {
                    recordFailedAttempt("Biometric error: $errString")
                }
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = primaryColor)
            Spacer(modifier = Modifier.height(24.dp))
        }

        IconButton(
            onClick = { 
                if (isLockedOut) return@IconButton
                authError = null
                triggerBiometric++ 
            },
            modifier = Modifier.size(64.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Fingerprint,
                contentDescription = "Unlock with Biometrics",
                modifier = Modifier.size(64.dp),
                tint = if (isLockedOut) Color.Gray else primaryColor
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            if (isLockedOut) "App Locked" else "Enter PIN",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = if (isLockedOut) MaterialTheme.colorScheme.error else Color(0xFF3E3636)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isLockedOut) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Text(
                    "Too many failed attempts. Try again in ${remainingLockoutSec}s.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // PIN Indicators
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val totalDots = enteredPinBytes.size.coerceAtLeast(4).coerceAtMost(8)
            for (i in 0 until totalDots) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (i < enteredPinBytes.size) primaryColor else Color.Transparent)
                        .border(1.dp, if (i < enteredPinBytes.size) primaryColor else Color.LightGray, CircleShape)
                )
            }
        }
        
        if (authError != null && !isLockedOut) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(authError ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Keypad
        val keys = listOf(
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "bio", "0", "del"
        )
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            for (row in 0 until 4) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                    for (col in 0 until 3) {
                        val rawKey = keys[row * 3 + col]
                        val key = if (rawKey == "bio" && enteredPinBytes.size >= 4) "OK" else rawKey
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(if (isLockedOut) Color(0xFFE0E0E0) else if (key == "OK") primaryColor else Color.White)
                                .clearAndSetSemantics {
                                    contentDescription = when (key) {
                                        "del" -> "Delete"
                                        "bio" -> "Biometric"
                                        "OK" -> "Submit"
                                        else -> "PIN Key"
                                    }
                                }
                                .clickable {
                                    if (isLoading || isLockedOut) return@clickable
                                    authError = null
                                    when (key) {
                                        "del" -> if (enteredPinBytes.isNotEmpty()) {
                                            val newArr = enteredPinBytes.copyOf(enteredPinBytes.size - 1)
                                            enteredPinBytes.fill(0)
                                            enteredPinBytes = newArr
                                        }
                                        "bio" -> if (enteredPinBytes.isEmpty()) triggerBiometric++
                                        "OK" -> {
                                            scope.launch {
                                                isLoading = true
                                                val success = try {
                                                    withContext(Dispatchers.IO) {
                                                        if (pinHash != null) {
                                                            val salt = storage.getDerivationSalt() ?: ByteArray(0)
                                                            rustBridge.verifyAndUnlock(pinHash, enteredPinBytes, salt)
                                                        } else false
                                                    }
                                                } finally {
                                                    enteredPinBytes.fill(0)
                                                    enteredPinBytes = ByteArray(0)
                                                }
                                                
                                                if (success) {
                                                    storage.clearLockoutState()
                                                    failedAttempts = 0
                                                    lockoutUntilMs = 0L
                                                    isUnlocked = true
                                                } else {
                                                    if (recordFailedAttempt("Incorrect PIN")) return@launch
                                                }
                                                isLoading = false
                                            }
                                        }
                                        else -> if (enteredPinBytes.size < 8) {
                                            val newArr = ByteArray(enteredPinBytes.size + 1)
                                            enteredPinBytes.copyInto(newArr)
                                            newArr[enteredPinBytes.size] = key[0].code.toByte()
                                            enteredPinBytes.fill(0)
                                            enteredPinBytes = newArr
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (key == "del") {
                                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete PIN digit", tint = if (isLockedOut) Color.Gray else Color.DarkGray)
                            } else if (key == "bio") {
                                Icon(Icons.Default.Fingerprint, contentDescription = "Unlock with Biometrics", tint = if (isLockedOut) Color.Gray else primaryColor)
                            } else if (key == "OK") {
                                Text("OK", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = Color.White)
                            } else {
                                Text(key, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = if (isLockedOut) Color.Gray else Color(0xFF3E3636))
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        TextButton(onClick = { showResetConfirmDialog = true }) {
            Text("Forgot PIN? Wipe and Reset", color = Color.Gray, style = MaterialTheme.typography.labelMedium)
        }
    }
}
