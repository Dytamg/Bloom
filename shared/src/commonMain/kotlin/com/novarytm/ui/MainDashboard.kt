@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.novarytm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import kotlin.time.Clock
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novarytm.db.CycleEntry
import com.novarytm.ffi.RustBridge
import com.novarytm.tracker.CycleTracker
import com.novarytm.sync.SyncManager
import kotlinx.datetime.*
import kotlinx.coroutines.launch

@Composable
fun MainDashboard(
    rustBridge: RustBridge, 
    cycleTracker: CycleTracker,
    entries: List<CycleEntry>,
    birthControl: com.novarytm.db.BirthControl?,
    userId: String,
    isPartnerView: Boolean = false,
    syncManager: SyncManager? = null,
    queries: com.novarytm.db.CycleQueries,
    onLogClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onPairClick: () -> Unit,
    currentThemeName: String,
    onThemeChange: (String) -> Unit
) {
    val analysis by produceState(
        initialValue = com.novarytm.tracker.CycleAnalysis(28, false),
        key1 = entries
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            cycleTracker.analyzeHistory(entries)
        }
    }
    
    val avgLength = analysis.averageLength
    
    val lastEntryDate = entries.filter { (it.intensity ?: 0) > 0 }.maxByOrNull { it.date }?.date
    
    val lastDate = lastEntryDate ?: Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
    
    val currentPhase by produceState(
        initialValue = "Loading",
        key1 = lastDate,
        key2 = avgLength
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (lastEntryDate == null) {
                "Hidden"
            } else {
                rustBridge.calculatePhase(lastDate, avgLength)
            }
        }
    }
    
    val currentDay by cycleTracker.currentCycleDay.collectAsState()
    val partnerPerms = remember(isPartnerView) { if (isPartnerView) rustBridge.getPartnerPermissions() else com.novarytm.sync.PartnerPermissions() }
    val canShowPhase = !isPartnerView || partnerPerms.shareCyclePhase

    val appColors = LocalAppColors.current
    val periodColor = appColors.primary
    val fertileColor = appColors.secondary
    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Bloom", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                val greeting = if (isPartnerView) "Partner View" else if (userId.isNotEmpty()) "Welcome, ${userId.take(4)}" else "Good morning"
                Text(greeting, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Theme Switcher
                IconButton(onClick = {
                    val nextTheme = if (currentThemeName == "Default") "Baby Blue" else "Default"
                    onThemeChange(nextTheme)
                }) {
                    Icon(Icons.Default.Palette, contentDescription = "Switch Theme", tint = periodColor)
                }
                
                if (!isPartnerView) {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.Gray)
                    }
                }
            }
        }

        // Cycle Visualizer
        Box(contentAlignment = Alignment.Center) {
            if (canShowPhase && currentPhase != "Invalid Date" && currentPhase != "Hidden") {
                CyclePhaseVisualizer(
                    phase = currentPhase,
                    currentDay = if (lastEntryDate == null) 0 else currentDay,
                    totalDays = avgLength
                )
            } else {
                Box(
                    modifier = Modifier.size(240.dp).clip(CircleShape).background(Color(0xFFF3F4F6)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (!canShowPhase) "Phase hidden by partner" else "Phase hidden", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                }
            }
        }
        
        // Countdown
        Box(modifier = Modifier.padding(top = 16.dp, bottom = 24.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFF3F4F6)).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = null, tint = periodColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                if (canShowPhase && currentPhase != "Hidden") {
                    val daysUntil = avgLength - currentDay
                    val msg = if (daysUntil >= 0) "$daysUntil days until next cycle" else "Period late by ${-daysUntil} days"
                    Text(msg, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                } else {
                    Text(if (!canShowPhase) "Predictions hidden by partner" else "Predictions not shared", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                }
            }
        }

        // Active Reminders
        if (!isPartnerView) {
            var refreshKey by remember { mutableStateOf(0) }
            val reminders = remember(refreshKey) { queries.selectAllReminders().executeAsList() }
            if (reminders.isNotEmpty()) {
                Text("Active Reminders", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(8.dp))
                reminders.forEach { reminder ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = periodColor.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = periodColor)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(reminder.type, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = Color.DarkGray)
                                val dateStr = when (reminder.frequency) {
                                    "Daily" -> ""
                                    "Once" -> " on ${reminder.nextDate ?: ""}"
                                    else -> " starting ${reminder.nextDate ?: ""}"
                                }
                                Text("${reminder.frequency} at ${reminder.timeOfDay ?: "N/A"}$dateStr", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            IconButton(onClick = {
                                queries.deleteReminder(reminder.id)
                                refreshKey++
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Reminder", tint = Color.Gray)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // Quick Log Button (Only for owner)
        if (!isPartnerView) {
            Button(
                onClick = onLogClick,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = periodColor)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Log Symptoms", style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(Modifier.height(24.dp))

        if (isPartnerView) {
            PartnerStatsGrid(entries, birthControl, avgLength, rustBridge.getPartnerPermissions())
        } else {
            OwnerStatsGrid(entries, avgLength)
            
            Spacer(Modifier.height(24.dp))
            
            // Pair with Partner Button
            Button(
                onClick = onPairClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = periodColor),
                border = BorderStroke(1.dp, periodColor.copy(alpha = 0.3f))
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Pair with Partner", style = MaterialTheme.typography.titleMedium)
            }
        }
        
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
fun OwnerStatsGrid(entries: List<CycleEntry>, avgLength: Int) {
    val appColors = LocalAppColors.current
    val periodColor = appColors.primary
    val fertileColor = appColors.secondary

    val sortedPeriodDates = androidx.compose.runtime.remember(entries) {
        entries.filter { (it.intensity ?: 0) > 0 }.map { kotlinx.datetime.LocalDate.parse(it.date) }.sorted()
    }
    val cyclesTracked = androidx.compose.runtime.remember(sortedPeriodDates) {
        if (sortedPeriodDates.isEmpty()) 0 else {
            var count = 1
            for (i in 1 until sortedPeriodDates.size) {
                if (sortedPeriodDates[i].toEpochDays() - sortedPeriodDates[i-1].toEpochDays() > 15L) {
                    count++
                }
            }
            count
        }
    }
    val avgPeriodDuration = androidx.compose.runtime.remember(sortedPeriodDates) {
        if (sortedPeriodDates.isEmpty()) "--" else {
            val durations = mutableListOf<Int>()
            var currentDuration = 1
            for (i in 1 until sortedPeriodDates.size) {
                val gap = sortedPeriodDates[i].toEpochDays() - sortedPeriodDates[i-1].toEpochDays()
                if (gap == 1L) {
                    currentDuration++
                } else if (gap <= 15L) {
                    currentDuration += gap.toInt()
                } else {
                    durations.add(currentDuration)
                    currentDuration = 1
                }
            }
            durations.add(currentDuration)
            if (durations.isEmpty()) "--" else (durations.sum() / durations.size).toString()
        }
    }

    val cycleLengths = androidx.compose.runtime.remember(sortedPeriodDates) {
        if (sortedPeriodDates.size < 2) emptyList() else {
            val lengths = mutableListOf<Long>()
            for (i in 1 until sortedPeriodDates.size) {
                val gap = sortedPeriodDates[i].toEpochDays() - sortedPeriodDates[i - 1].toEpochDays()
                if (gap > 15L) {
                    lengths.add(gap)
                }
            }
            lengths
        }
    }
    val (accuracyStatus, accuracyPercent) = androidx.compose.runtime.remember(cycleLengths, entries) {
        when {
            cycleLengths.isEmpty() -> {
                if (entries.isNotEmpty()) "Learning" to "70%" else "Pending" to "--%"
            }
            cycleLengths.size == 1 -> {
                val len = cycleLengths.first()
                if (len in 24..35) "Good" to "85%" else "Moderate" to "75%"
            }
            else -> {
                val avg = cycleLengths.average()
                val meanDev = cycleLengths.map { kotlin.math.abs(it - avg) }.average()
                when {
                    meanDev <= 1.5 -> "High" to "98%"
                    meanDev <= 3.0 -> "Good" to "90%"
                    meanDev <= 5.0 -> "Moderate" to "80%"
                    else -> "Low" to "65%"
                }
            }
        }
    }

    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Stat 1: Avg. Cycle Length
            Card(
                modifier = Modifier.weight(1f).aspectRatio(1.2f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(periodColor.copy(alpha=0.1f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null, tint = periodColor, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (avgLength == 0) "--" else "$avgLength", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                    Text("Avg. Cycle Length", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            
            // Stat 2: Period Duration
            Card(
                modifier = Modifier.weight(1f).aspectRatio(1.2f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(periodColor.copy(alpha=0.1f)), contentAlignment = Alignment.Center) {
                        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(periodColor))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(avgPeriodDuration, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                    Text("Period Duration", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
        
        Spacer(Modifier.height(12.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Stat 3: Cycles Tracked
            Card(
                modifier = Modifier.weight(1f).aspectRatio(1.2f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(fertileColor.copy(alpha=0.1f)), contentAlignment = Alignment.Center) {
                        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(fertileColor))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("$cyclesTracked", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                    Text("Cycles Tracked", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            
            // Stat 4: Prediction Accuracy
            Card(
                modifier = Modifier.weight(1f).aspectRatio(1.2f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp).clip(RoundedCornerShape(8.dp)).background(fertileColor.copy(alpha = 0.1f)).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                            Text(accuracyStatus, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = fertileColor)
                        }
                        // Pill Badge
                        Box(modifier = Modifier.clip(CircleShape).background(fertileColor.copy(alpha = 0.1f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text(accuracyPercent, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = fertileColor)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = accuracyStatus,
                        style = if (accuracyStatus.length > 5) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("Prediction Accuracy", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun PartnerStatsGrid(entries: List<CycleEntry>, birthControl: com.novarytm.db.BirthControl?, avgLength: Int, permissions: com.novarytm.sync.PartnerPermissions) {
    val appColors = LocalAppColors.current
    val periodColor = appColors.primary
    val fertileColor = appColors.secondary
    val primaryVariant = appColors.primaryVariant

    val sortedPeriodDates = androidx.compose.runtime.remember(entries) {
        entries.filter { (it.intensity ?: 0) > 0 }.map { kotlinx.datetime.LocalDate.parse(it.date) }.sorted()
    }
    val cyclesTracked = androidx.compose.runtime.remember(sortedPeriodDates) {
        if (sortedPeriodDates.isEmpty()) 0 else {
            var count = 1
            for (i in 1 until sortedPeriodDates.size) {
                if (sortedPeriodDates[i].toEpochDays() - sortedPeriodDates[i-1].toEpochDays() > 15L) {
                    count++
                }
            }
            count
        }
    }
    val avgPeriodDuration = androidx.compose.runtime.remember(sortedPeriodDates) {
        if (sortedPeriodDates.isEmpty()) "--" else {
            val durations = mutableListOf<Int>()
            var currentDuration = 1
            for (i in 1 until sortedPeriodDates.size) {
                val gap = sortedPeriodDates[i].toEpochDays() - sortedPeriodDates[i-1].toEpochDays()
                if (gap == 1L) {
                    currentDuration++
                } else if (gap <= 15L) {
                    currentDuration += gap.toInt()
                } else {
                    durations.add(currentDuration)
                    currentDuration = 1
                }
            }
            durations.add(currentDuration)
            if (durations.isEmpty()) "--" else (durations.sum() / durations.size).toString()
        }
    }

    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Stat 1: Avg. Cycle Length
            Card(
                modifier = Modifier.weight(1f).aspectRatio(1.2f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(periodColor.copy(alpha=0.1f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null, tint = periodColor, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (permissions.shareCyclePhase) "$avgLength" else "??", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                    Text("Avg. Cycle Length", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            
            // Stat 2: Period Duration
            Card(
                modifier = Modifier.weight(1f).aspectRatio(1.2f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(periodColor.copy(alpha=0.1f)), contentAlignment = Alignment.Center) {
                        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(periodColor))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (permissions.shareCyclePhase) (if (avgPeriodDuration == "--") "--" else avgPeriodDuration) else "??", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                    Text("Period Duration", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
        
        Spacer(Modifier.height(12.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Stat 3: Cycles Tracked (Previously incorrectly named Flow Intensity)
            Card(
                modifier = Modifier.weight(1f).aspectRatio(1.2f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(fertileColor.copy(alpha=0.1f)), contentAlignment = Alignment.Center) {
                        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(fertileColor))
                    }
                    Spacer(Modifier.weight(1f))
                    val cyclesText = if (permissions.shareCyclePhase) "$cyclesTracked" else "Not shared"
                    Text(
                        text = cyclesText,
                        style = if (cyclesText.length > 5) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("Cycles Tracked", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            
            // Stat 4: Birth Control
            Card(
                modifier = Modifier.weight(1f).aspectRatio(1.2f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(primaryVariant.copy(alpha=0.1f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = primaryVariant, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    val bcText = if (permissions.shareBirthControl && birthControl != null) birthControl.type else "Not shared"
                    Text(
                        text = bcText,
                        style = if (bcText.length > 8) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("Birth Control", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
    }
}
