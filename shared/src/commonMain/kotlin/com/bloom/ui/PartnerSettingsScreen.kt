package com.bloom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bloom.sync.PartnerPermissions
import com.bloom.sync.SyncProviderMode
import com.bloom.utils.AppStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartnerSettingsScreen(
    partnerPerms: PartnerPermissions = PartnerPermissions(),
    syncProviderMode: SyncProviderMode = SyncProviderMode.DRIVE_API,
    hasValidSafPermission: Boolean = true,
    lastSuccessfulSyncWallclock: Long = 0L,
    onReconnectSafFile: () -> Unit = {},
    onBack: () -> Unit,
    onDisconnect: () -> Unit
) {
    val appColors = LocalAppColors.current
    val primaryColor = appColors.primary
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
                title = {
                    Text(
                        AppStrings.get("partner_settings_title"),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
            Spacer(Modifier.height(8.dp))

            Text(
                AppStrings.get("connected_to_owner"),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = appColors.textPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))

            Text(
                AppStrings.get("partner_settings_desc"),
                style = MaterialTheme.typography.bodyMedium,
                color = appColors.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            ActiveSyncStatusCard(
                syncProviderMode = syncProviderMode,
                lastSuccessfulSyncWallclock = lastSuccessfulSyncWallclock,
                hasValidSafPermission = hasValidSafPermission,
                onReconnectSafFile = onReconnectSafFile
            )

            Spacer(Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = appColors.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        AppStrings.get("shared_data_permissions"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = appColors.textPrimary
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
                                    tint = if (granted) primaryColor else appColors.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(label, style = MaterialTheme.typography.bodyMedium, color = appColors.textPrimary)
                            }
                            Text(
                                text = if (granted) AppStrings.get("perm_shared") else AppStrings.get("perm_restricted"),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (granted) primaryColor else appColors.textSecondary
                            )
                        }
                        if (index < permissionRows.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f, fill = true))
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(
                    AppStrings.get("disconnect_logout"),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ActiveSyncStatusCard(
    syncProviderMode: SyncProviderMode,
    lastSuccessfulSyncWallclock: Long,
    hasValidSafPermission: Boolean,
    onReconnectSafFile: () -> Unit
) {
    val appColors = LocalAppColors.current
    val isSaf = syncProviderMode == SyncProviderMode.SAF_FILE
    val isWarning = isSaf && !hasValidSafPermission
    val borderColor = if (isWarning) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = appColors.surface),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = AppStrings.get("sync_status_card_title"),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = appColors.textPrimary
                )
                SyncModeStatusBadge(syncProviderMode = syncProviderMode)
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = appColors.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatSyncStatusLabel(lastSuccessfulSyncWallclock, syncProviderMode),
                    style = MaterialTheme.typography.bodySmall,
                    color = appColors.textSecondary
                )
            }

            if (isSaf) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hasValidSafPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (hasValidSafPermission) appColors.secondary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (hasValidSafPermission) {
                            AppStrings.get("saf_connected_title")
                        } else {
                            AppStrings.get("saf_needs_reconnect_title")
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = appColors.textPrimary
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = AppStrings.get("saf_drive_refresh_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = appColors.textSecondary
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onReconnectSafFile,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = appColors.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        AppStrings.get("saf_reconnect_file_btn"),
                        color = appColors.textPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncModeStatusBadge(syncProviderMode: SyncProviderMode) {
    val appColors = LocalAppColors.current
    val isSaf = syncProviderMode == SyncProviderMode.SAF_FILE
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSaf) Icons.Default.FolderOpen else Icons.Default.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isSaf) AppStrings.get("sync_mode_saf") else AppStrings.get("sync_mode_cloud"),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
