@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.novarytm.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.novarytm.utils.AppStrings
import kotlin.time.Clock
import kotlin.math.roundToInt
import kotlinx.datetime.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogEntryScreen(
    initialEntry: com.novarytm.db.CycleEntry? = null,
    initialEndDate: String? = null,
    isReadOnly: Boolean = false,
    onSave: (startDate: String, endDate: String, intensity: Int, notes: String, sexualRelations: String?, painLevel: Int?, symptoms: String?) -> Unit,
    onCancel: () -> Unit
) {
    var selectedIntensity by remember { mutableStateOf(initialEntry?.intensity ?: 0) }
    var notes by remember { mutableStateOf(initialEntry?.notes ?: "") }
    var painLevel by remember { mutableStateOf(initialEntry?.painLevel?.toFloat() ?: 0f) }
    var selectedSymptoms by remember { 
        mutableStateOf(initialEntry?.symptoms?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet())
    }
    
    // Default to today or initial entry date
    val systemTimeZone = TimeZone.currentSystemDefault()
    var currentDate by remember { 
        mutableStateOf(initialEntry?.date?.let { LocalDate.parse(it) } ?: Clock.System.now().toLocalDateTime(systemTimeZone).date) 
    }
    var endDate by remember { 
        mutableStateOf(
            initialEndDate?.let { LocalDate.parse(it) }
                ?: initialEntry?.date?.let { LocalDate.parse(it) }
                ?: Clock.System.now().toLocalDateTime(systemTimeZone).date
        ) 
    }
    
    var showDateRangePicker by remember { mutableStateOf(false) }
    
    val dateString = currentDate.toString()
    val periodColor = LocalAppColors.current.primary

    if (showDateRangePicker) {
        CustomDateRangePickerDialog(
            startDate = currentDate,
            endDate = endDate,
            onDismiss = { showDateRangePicker = false },
            onConfirm = { s, e ->
                currentDate = s
                endDate = e
                showDateRangePicker = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (isReadOnly) "View Entry" else "How's your flow?", 
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )
        
        // Date Selector
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 16.dp).then(
                if (!isReadOnly) Modifier.clickable { showDateRangePicker = true } else Modifier
            )
        ) {
            Text("Date Range: ", fontWeight = FontWeight.Bold)
            Card(
                colors = CardDefaults.cardColors(containerColor = periodColor.copy(alpha = 0.05f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                val rangeText = if (currentDate == endDate) {
                    currentDate.toHumanReadable()
                } else {
                    "${currentDate.toHumanReadable()} - ${endDate.toHumanReadable()}"
                }
                Text(
                    rangeText, 
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        
        Spacer(Modifier.height(32.dp))
        
        Text("Flow Intensity", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            (0..5).forEach { level ->
                IntensityButton(
                    level = level,
                    isSelected = selectedIntensity == level,
                    color = periodColor,
                    modifier = Modifier.weight(1f),
                    onClick = { if (!isReadOnly) selectedIntensity = level }
                )
            }
        }
        if (selectedIntensity == 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = AppStrings.get("flow_0_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(22.dp))
        } else {
            Spacer(Modifier.height(32.dp))
        }
        
        val haptic = LocalHapticFeedback.current
        val painInt = painLevel.roundToInt()
        val painTrackColor by animateColorAsState(
            targetValue = when {
                painInt == 0 -> periodColor
                painInt <= 3 -> periodColor
                painInt <= 6 -> Color(0xFFE06D53)
                else -> Color(0xFFC62828)
            },
            animationSpec = tween(durationMillis = 200),
            label = "PainTrackColor"
        )
        val painDisplay = if (painInt == 0) "0/10 (No pain)" else "$painInt/10"
        Text("Pain Level: $painDisplay", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = painLevel,
            onValueChange = { newValue ->
                if (!isReadOnly) {
                    val oldStep = painLevel.roundToInt()
                    val newStep = newValue.roundToInt()
                    if (oldStep != newStep) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    painLevel = newValue
                }
            },
            valueRange = 0f..10f,
            steps = 9,
            enabled = !isReadOnly,
            colors = SliderDefaults.colors(
                thumbColor = painTrackColor,
                activeTrackColor = painTrackColor,
                inactiveTrackColor = painTrackColor.copy(alpha = 0.2f),
                activeTickColor = Color.White.copy(alpha = 0.7f),
                inactiveTickColor = painTrackColor.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics {
                contentDescription = "Pain Level $painDisplay"
            }
        )
        
        Spacer(Modifier.height(32.dp))
        
        Text("Quick Symptoms", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val symptomsList = listOf("Cramps", "Headaches", "Breast Pain", "Back Pain", "Nausea")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                symptomsList.take(3).forEachIndexed { index, symptom ->
                    if (index > 0) Spacer(Modifier.width(12.dp))
                    val isSelected = selectedSymptoms.contains(symptom)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            if (!isReadOnly) {
                                selectedSymptoms = if (isSelected) selectedSymptoms - symptom else selectedSymptoms + symptom
                            }
                        },
                        label = { Text(symptom, style = MaterialTheme.typography.bodyMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = periodColor.copy(alpha = 0.15f),
                            selectedLabelColor = periodColor
                        )
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                symptomsList.drop(3).forEachIndexed { index, symptom ->
                    if (index > 0) Spacer(Modifier.width(12.dp))
                    val isSelected = selectedSymptoms.contains(symptom)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            if (!isReadOnly) {
                                selectedSymptoms = if (isSelected) selectedSymptoms - symptom else selectedSymptoms + symptom
                            }
                        },
                        label = { Text(symptom, style = MaterialTheme.typography.bodyMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = periodColor.copy(alpha = 0.15f),
                            selectedLabelColor = periodColor
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        
        OutlinedTextField(
            value = notes,
            onValueChange = { if (!isReadOnly && it.length <= 500) notes = it },
            label = { Text("Symptoms, mood, or notes...") },
            readOnly = isReadOnly,
            modifier = Modifier.fillMaxWidth().height(150.dp),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = periodColor,
                focusedLabelColor = periodColor
            )
        )
        
        Spacer(Modifier.height(32.dp))
        
        if (!isReadOnly) {
            Button(
                onClick = { 
                    val symptomsStr = selectedSymptoms.joinToString(",")
                    onSave(currentDate.toString(), endDate.toString(), selectedIntensity, notes, initialEntry?.sexualRelations, painLevel.roundToInt(), symptomsStr) 
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = periodColor)
            ) {
                Text("Save Entry", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
        }
        
        TextButton(onClick = onCancel) {
            Text(if (isReadOnly) "Close" else "Cancel", color = Color.Gray)
        }
    }
}

@Composable
fun IntensityButton(
    level: Int,
    isSelected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) color else color.copy(alpha = 0.1f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "IntensityBackground"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else color,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "IntensityContent"
    )
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.08f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "IntensityScale"
    )

    val flowLabel = when (level) {
        0 -> "None"
        1 -> "Spot"
        2 -> "Light"
        3 -> "Med"
        4 -> "Heavy"
        5 -> "Very"
        else -> ""
    }
    val selectionState = if (isSelected) "selected" else "not selected"
    val contentDesc = "$flowLabel flow, level $level, $selectionState"

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = contentDesc
        }
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(14.dp))
                .background(backgroundColor)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (level == 0) Icons.Outlined.CheckCircle else Icons.Default.WaterDrop,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = flowLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) color else Color.Gray,
            maxLines = 1
        )
    }
}

fun LocalDate.toHumanReadable(): String {
    return com.novarytm.utils.formatDateByRegion(this.toString())
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CustomDateRangePickerDialog(
    startDate: LocalDate,
    endDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit
) {
    var selStart by remember { mutableStateOf<LocalDate?>(startDate) }
    var selEnd by remember { mutableStateOf<LocalDate?>(endDate) }
    val periodColor = LocalAppColors.current.primary

    val coroutineScope = rememberCoroutineScope()
    val initialPage = 500
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = initialPage,
        pageCount = { 1000 }
    )

    val offset = pagerState.currentPage - initialPage
    val totalMonths = startDate.year * 12 + (startDate.monthNumber - 1) + offset
    val dispYear = totalMonths / 12
    val dispMonth = (totalMonths % 12) + 1

    val monthName = when (dispMonth) {
        1 -> "January"; 2 -> "February"; 3 -> "March"; 4 -> "April"
        5 -> "May"; 6 -> "June"; 7 -> "July"; 8 -> "August"
        9 -> "September"; 10 -> "October"; 11 -> "November"; 12 -> "December"
        else -> ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn),
        containerColor = LocalAppColors.current.surface,
        title = {
            Column {
                Text("Select dates", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                val sStr = selStart?.let { "${it.month.name.take(3)} ${it.dayOfMonth}, ${it.year}" } ?: "Start"
                val curEnd = selEnd
                val curStart = selStart
                val eStr = if (curEnd != null) "${curEnd.month.name.take(3)} ${curEnd.dayOfMonth}, ${curEnd.year}" else if (curStart != null) "${curStart.month.name.take(3)} ${curStart.dayOfMonth}, ${curStart.year}" else "End"
                Text("$sStr - $eStr", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = periodColor)
            }
        },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            if (pagerState.currentPage > 0) {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        }
                    }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Month", tint = periodColor)
                    }
                    Text("$monthName $dispYear", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = {
                        coroutineScope.launch {
                            if (pagerState.currentPage < 999) {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next Month", tint = periodColor)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    listOf("S", "M", "T", "W", "T", "F", "S").forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().height(240.dp)
                ) { page ->
                    val pageOffset = page - initialPage
                    val pageTotalMonths = startDate.year * 12 + (startDate.monthNumber - 1) + pageOffset
                    val pYear = pageTotalMonths / 12
                    val pMonth = (pageTotalMonths % 12) + 1

                    val firstDay = LocalDate(pYear, pMonth, 1)
                    val startOffset = firstDay.dayOfWeek.isoDayNumber % 7
                    val daysInMonth = when(pMonth) {
                        2 -> if (pYear % 4 == 0 && (pYear % 100 != 0 || pYear % 400 == 0)) 29 else 28
                        4, 6, 9, 11 -> 30
                        else -> 31
                    }
                    val gridItems = List(startOffset) { null } + (1..daysInMonth).toList()

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(7),
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = false
                    ) {
                        items(gridItems.size) { idx ->
                            val day = gridItems[idx]
                            if (day != null) {
                                val dt = LocalDate(pYear, pMonth, day)
                                val isStart = selStart == dt
                                val isEnd = selEnd == dt || (selStart == dt && selEnd == null)
                                val s = selStart
                                val e = selEnd
                                val isInRange = s != null && e != null && dt > s && dt < e

                                val bg = when {
                                    isStart || isEnd -> periodColor
                                    isInRange -> periodColor.copy(alpha = 0.15f)
                                    else -> Color.Transparent
                                }
                                val txtColor = when {
                                    isStart || isEnd -> Color.White
                                    isInRange -> periodColor
                                    else -> Color.Unspecified
                                }

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .padding(2.dp)
                                        .clip(CircleShape)
                                        .background(bg)
                                        .clickable {
                                            val curS = selStart
                                            if (curS == null || selEnd != null) {
                                                selStart = dt
                                                selEnd = null
                                            } else if (dt < curS) {
                                                selStart = dt
                                            } else {
                                                selEnd = dt
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("$day", style = MaterialTheme.typography.bodyMedium, color = txtColor)
                                }
                            } else {
                                Spacer(Modifier.size(36.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = selStart ?: startDate
                val e = selEnd ?: s
                onConfirm(s, e)
            }) {
                Text("OK", color = periodColor, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        }
    )
}
