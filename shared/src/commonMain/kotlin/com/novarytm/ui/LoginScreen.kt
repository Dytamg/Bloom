package com.novarytm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import novarytm.shared.generated.resources.Res
import novarytm.shared.generated.resources.app_logo

@Composable
fun LoginScreen(
    onLogin: (String) -> Unit,
    onRestore: () -> Unit,
    onPartnerConnect: () -> Unit
) {
    var userId by remember { mutableStateOf("") }
    
    // Generate a mock secure ID if empty
    LaunchedEffect(Unit) {
        if (userId.isEmpty()) {
            userId = (1..8).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
        }
    }

    val appColors = LocalAppColors.current
    val primaryColor = appColors.primary
    val fertileColor = appColors.secondary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        Image(
            painter = painterResource(Res.drawable.app_logo),
            contentDescription = "Bloom Logo",
            modifier = Modifier.size(110.dp).clip(RoundedCornerShape(26.dp))
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "Bloom",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
            color = Color(0xFF3E3636)
        )
        Text(
            "Zero-Knowledge Health Tracking",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )

        Spacer(Modifier.height(48.dp))

        // ID Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("SECURE IDENTITY GENERATED", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF3F4F6))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val displayId = if (userId.length == 8) "${userId.take(4)}-${userId.takeLast(4)}" else userId
                    Text(displayId, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 4.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    "This is your unique anonymous identifier. Like a crypto wallet address, it represents you without revealing your personal information.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // Privacy Features
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF3F4F6)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(primaryColor.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = primaryColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Zero Knowledge", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Text("No email or phone required", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF3F4F6)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(fertileColor.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = fertileColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("End-to-End Encrypted", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Text("Your data stays on your device", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }

        Spacer(Modifier.height(32.dp))

        // Continue Button
        Button(
            onClick = { onLogin(userId) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
        ) {
            Text("Continue as ${userId.take(4)}...", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }

        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onRestore) {
                Text("Restore Account", color = primaryColor, style = MaterialTheme.typography.bodyMedium)
            }
            Text("|", modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp), color = Color.LightGray)
            TextButton(onClick = onPartnerConnect) {
                Text("Connect Partner", color = fertileColor, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text(
            "By continuing, you acknowledge that Bloom is for educational purposes only and does not provide medical advice.",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color.Gray,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp
        )
        
        Spacer(Modifier.height(16.dp))
    }
}
