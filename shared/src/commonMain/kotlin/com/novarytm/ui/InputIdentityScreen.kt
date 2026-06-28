package com.novarytm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputIdentityScreen(
    onIdEntered: (String) -> Unit,
    onBack: () -> Unit
) {
    var idInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val primaryColor = LocalAppColors.current.primary

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Restore Account") },
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
                Icons.Default.Fingerprint,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(64.dp)
            )
            
            Spacer(Modifier.height(24.dp))
            
            Text(
                "Enter Your Secure ID",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF3E3636)
            )
            
            Spacer(Modifier.height(16.dp))
            
            Text(
                "Enter the 8-character ID you saved during your previous setup to restore access on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(48.dp))

            OutlinedTextField(
                value = idInput,
                onValueChange = { 
                    if (it.length <= 8) {
                        idInput = it.uppercase()
                        errorMessage = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Secure ID (8 characters)") },
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
                    if (idInput.length == 8 && idInput.all { it.isLetterOrDigit() }) {
                        onIdEntered(idInput)
                    } else {
                        errorMessage = "Invalid ID. Must be 8 alphanumeric characters."
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
            ) {
                Text("Restore and Set PIN", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
