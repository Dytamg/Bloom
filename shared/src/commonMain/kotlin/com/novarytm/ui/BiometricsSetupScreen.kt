package com.novarytm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import androidx.compose.runtime.*
import com.novarytm.auth.BiometricPromptEffect

@Composable
fun BiometricsSetupScreen(
    onEnable: () -> Unit,
    onSkip: () -> Unit
) {
    val primaryColor = LocalAppColors.current.primary
    var showBiometricPrompt by remember { mutableStateOf(0) }

    BiometricPromptEffect(
        trigger = showBiometricPrompt,
        title = "Confirm Fingerprint",
        subtitle = "Verify your biometrics to enable quick unlock.",
        onSuccess = {
            onEnable()
        },
        onError = {
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(primaryColor.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Fingerprint, contentDescription = null, tint = primaryColor, modifier = Modifier.size(60.dp))
        }

        Spacer(Modifier.height(32.dp))

        Text(
            "Enable Biometrics",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFF3E3636)
        )
        
        Spacer(Modifier.height(16.dp))
        
        Text(
            "Use your fingerprint or face to quickly and securely unlock Bloom without entering your PIN every time.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { showBiometricPrompt++ },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
        ) {
            Text("Enable Biometrics", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(16.dp))

        TextButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Skip for now", color = Color.Gray, style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(32.dp))
    }
}
