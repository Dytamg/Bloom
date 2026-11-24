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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import com.novarytm.ffi.RustBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CreatePinScreen(
    rustBridge: RustBridge,
    onPinCreated: (ByteArray, ByteArray, ByteArray) -> Unit  // (pinHash, masterKey, derivationSalt)
) {
    var step by remember { mutableStateOf(0) } // 0: Enter, 1: Confirm
    var firstEntryBytes by remember { mutableStateOf(ByteArray(0)) }
    var currentEntryBytes by remember { mutableStateOf(ByteArray(0)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    val primaryColor = LocalAppColors.current.primary

    DisposableEffect(Unit) {
        onDispose {
            currentEntryBytes.fill(0)
            firstEntryBytes.fill(0)
        }
    }

    val deriveAndComplete: () -> Unit = {
        if (!isProcessing) {
            isProcessing = true
            val pinSnapshot = currentEntryBytes.copyOf()
            scope.launch {
                try {
                    val (hash, masterKey, salt) = withContext(Dispatchers.Default) {
                        val randomKey = rustBridge.generateRandomKey()
                        val derivedSalt = randomKey.sliceArray(0 until 16)
                        randomKey.fill(0)
                        val pinHash = rustBridge.generatePinHash(pinSnapshot)
                        val key = rustBridge.deriveKey(pinSnapshot, derivedSalt)
                        Triple(pinHash, key, derivedSalt)
                    }
                    onPinCreated(hash, masterKey, salt)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    errorMessage = "Unable to secure PIN. Please try again."
                    step = 0
                } finally {
                    pinSnapshot.fill(0)
                    currentEntryBytes.fill(0)
                    firstEntryBytes.fill(0)
                    currentEntryBytes = ByteArray(0)
                    firstEntryBytes = ByteArray(0)
                    isProcessing = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            if (step == 0) "Create a 4-8 Digit PIN" else "Confirm your PIN",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFF3E3636)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            "This PIN will be required each time you open the app to keep your health data private.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // PIN Indicators
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "${currentEntryBytes.size} digits entered"
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            }
        ) {
            val totalDots = if (step == 0) currentEntryBytes.size.coerceAtLeast(4).coerceAtMost(8) else firstEntryBytes.size
            for (i in 0 until totalDots) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (i < currentEntryBytes.size) primaryColor else Color.Transparent)
                        .border(1.dp, if (i < currentEntryBytes.size) primaryColor else Color.LightGray, CircleShape)
                )
            }
        }
        
        if (isProcessing) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator(color = primaryColor, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        } else if (errorMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(errorMessage ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Keypad
        val keys = listOf(
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "", "0", "del"
        )
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            for (row in 0 until 4) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                    for (col in 0 until 3) {
                        val rawKey = keys[row * 3 + col]
                        val key = if (rawKey.isEmpty() && currentEntryBytes.size >= 4) "OK" else rawKey
                        if (key.isEmpty()) {
                            Box(modifier = Modifier.size(72.dp))
                            continue
                        }
                        
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(if (key == "OK") primaryColor else Color.White)
                                .clickable(enabled = !isProcessing) {
                                    errorMessage = null
                                    if (key == "del") {
                                        if (currentEntryBytes.isNotEmpty()) {
                                            val newArr = currentEntryBytes.copyOf(currentEntryBytes.size - 1)
                                            currentEntryBytes.fill(0)
                                            currentEntryBytes = newArr
                                        }
                                    } else if (key == "OK") {
                                        if (step == 0) {
                                            firstEntryBytes = currentEntryBytes
                                            currentEntryBytes = ByteArray(0)
                                            step = 1
                                        } else {
                                            if (currentEntryBytes.contentEquals(firstEntryBytes)) {
                                                deriveAndComplete()
                                            } else {
                                                errorMessage = "PINs do not match"
                                                currentEntryBytes.fill(0)
                                                firstEntryBytes.fill(0)
                                                currentEntryBytes = ByteArray(0)
                                                firstEntryBytes = ByteArray(0)
                                                step = 0
                                            }
                                        }
                                    } else {
                                        val maxLen = if (step == 0) 8 else firstEntryBytes.size
                                        if (currentEntryBytes.size < maxLen) {
                                            val newArr = ByteArray(currentEntryBytes.size + 1)
                                            currentEntryBytes.copyInto(newArr)
                                            newArr[currentEntryBytes.size] = key[0].code.toByte()
                                            currentEntryBytes.fill(0)
                                            currentEntryBytes = newArr
                                            if (step == 1 && currentEntryBytes.size == firstEntryBytes.size) {
                                                if (currentEntryBytes.contentEquals(firstEntryBytes)) {
                                                    deriveAndComplete()
                                                } else {
                                                    errorMessage = "PINs do not match"
                                                    currentEntryBytes.fill(0)
                                                    firstEntryBytes.fill(0)
                                                    currentEntryBytes = ByteArray(0)
                                                    firstEntryBytes = ByteArray(0)
                                                    step = 0
                                                }
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (key == "del") {
                                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete", tint = Color.DarkGray)
                            } else {
                                Text(
                                    key,
                                    modifier = Modifier.clearAndSetSemantics { contentDescription = if (key == "OK") "Submit" else key },
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), 
                                    color = if (key == "OK") Color.White else Color(0xFF3E3636)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
