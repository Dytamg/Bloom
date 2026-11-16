package com.novarytm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.novarytm.sync.PartnerPermissions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartnerSettingsScreen(
    partnerPerms: PartnerPermissions = PartnerPermissions(),
    onBack: () -> Unit,
    onDisconnect: () -> Unit
) {
    val primaryColor = LocalAppColors.current.primary
    val permissionRows = listOf(
        "Cycle Phase & Predictions" to partnerPerms.shareCyclePhase,
        "Flow Intensity" to partnerPerms.shareFlowIntensity,
        "Symptoms & Moods" to partnerPerms.shareSymptomsMoods,
        "Sexual Activity" to partnerPerms.shareSexualActivity,
        "Birth Control" to partnerPerms.shareBirthControl,
        "Pregnancy Tests" to partnerPerms.sharePregnancyTests
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Partner Settings", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
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
            Spacer(Modifier.height(16.dp))
            
            Text(
                "Connected to Owner",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
            
            Spacer(Modifier.height(12.dp))
            
            Text(
                "You are currently viewing shared health data. The owner controls which data points are visible to you.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Shared Data Permissions",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(Modifier.height(12.dp))
                    permissionRows.forEachIndexed { index, (label, granted) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = if (granted) primaryColor else Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(
                                text = if (granted) "Shared" else "Restricted",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (granted) primaryColor else Color.Gray
                            )
                        }
                        if (index < permissionRows.lastIndex) {
                            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.25f))
                        }
                    }
                }
            }
            
            Spacer(Modifier.weight(1f, fill = true))
            Spacer(Modifier.height(24.dp))
            
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
            ) {
                Text("Disconnect & Log Out", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            
            Spacer(Modifier.height(24.dp))
        }
    }
}
