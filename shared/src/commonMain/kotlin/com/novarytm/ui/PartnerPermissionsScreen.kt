package com.novarytm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novarytm.ffi.RustBridge
import com.novarytm.sync.PartnerPermissions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartnerPermissionsScreen(
    rustBridge: RustBridge,
    isBiometricsEnabled: Boolean,
    onToggleBiometrics: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSave: (PartnerPermissions) -> Unit = {}
) {
    var permissions by remember { mutableStateOf(rustBridge.getPartnerPermissions()) }
    val primaryColor = LocalAppColors.current.primary

    var showBiometricPrompt by remember { mutableStateOf(0) }

    com.novarytm.auth.BiometricPromptEffect(
        trigger = showBiometricPrompt,
        title = "Confirm Fingerprint",
        subtitle = "Verify your biometrics to enable quick unlock.",
        onSuccess = {
            onToggleBiometrics(true)
        },
        onError = {
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Partner Permissions", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
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
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                "Security",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            PermissionItem(
                title = "Biometric Unlock",
                description = "Use fingerprint or face unlock to access the app.",
                checked = isBiometricsEnabled,
                onCheckedChange = {
                    if (it) {
                        showBiometricPrompt++
                    } else {
                        onToggleBiometrics(false)
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Partner Sharing",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                "Control what data your partner can see. Changes take effect on the next sync.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            PermissionItem(
                title = "Cycle Phase & Predictions",
                description = "Shares your current phase and next period prediction.",
                enabled = true, // On by default, maybe fixed?
                checked = permissions.shareCyclePhase,
                onCheckedChange = { permissions = permissions.copy(shareCyclePhase = it) }
            )

            PermissionItem(
                title = "Flow Intensity Logs",
                description = "Shares your daily bleeding intensity entries.",
                checked = permissions.shareFlowIntensity,
                onCheckedChange = { permissions = permissions.copy(shareFlowIntensity = it) }
            )

            PermissionItem(
                title = "Symptoms & Moods",
                description = "Shares logged physical and emotional symptoms.",
                checked = permissions.shareSymptomsMoods,
                onCheckedChange = { permissions = permissions.copy(shareSymptomsMoods = it) }
            )

            PermissionItem(
                title = "Birth Control Status",
                description = "Shares pill taken or reminder status.",
                checked = permissions.shareBirthControl,
                onCheckedChange = { permissions = permissions.copy(shareBirthControl = it) }
            )

            PermissionItem(
                title = "Pregnancy Tests",
                description = "Shares your logged pregnancy test results.",
                checked = permissions.sharePregnancyTests,
                onCheckedChange = { permissions = permissions.copy(sharePregnancyTests = it) }
            )

            PermissionItem(
                title = "Sexual Activity Logs",
                description = "Shares your logged sexual relations.",
                checked = permissions.shareSexualActivity,
                onCheckedChange = { permissions = permissions.copy(shareSexualActivity = it) }
            )

            Spacer(modifier = Modifier.height(32.dp))
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    rustBridge.savePartnerPermissions(permissions)
                    onSave(permissions)
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = primaryColor, contentColor = Color.White)
            ) {
                Text("Save Permissions", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun PermissionItem(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color(0xFF3E3636))
                Text(description, style = MaterialTheme.typography.bodySmall, color = Color.Gray, lineHeight = 16.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = LocalAppColors.current.primary)
            )
        }
    }
}
