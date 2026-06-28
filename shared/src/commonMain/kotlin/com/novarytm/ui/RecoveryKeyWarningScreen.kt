package com.novarytm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RecoveryKeyWarningScreen(
    userId: String,
    onConfirmed: () -> Unit
) {
    var hasSaved by remember { mutableStateOf(false) }
    val primaryColor = LocalAppColors.current.primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = Color(0xFFB07D3E),
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(Modifier.height(24.dp))
        
        Text(
            "Save Your Secure ID",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFF3E3636)
        )
        
        Spacer(Modifier.height(16.dp))
        
        Text(
            "Bloom is zero-knowledge; if you lose this device and this ID, your data cannot be recovered.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        // Prominent ID Display
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("YOUR SECURE IDENTITY", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Text(
                    userId,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 4.sp
                    ),
                    color = Color(0xFF3E3636)
                )
            }
        }

        Spacer(Modifier.height(48.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = hasSaved,
                onCheckedChange = { hasSaved = it },
                colors = CheckboxDefaults.colors(checkedColor = primaryColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "I have saved my ID safely in a secure manager or written it down.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF3E3636)
            )
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onConfirmed,
            enabled = hasSaved,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
        ) {
            Text("Continue to PIN Setup", style = MaterialTheme.typography.titleMedium)
        }
    }
}
