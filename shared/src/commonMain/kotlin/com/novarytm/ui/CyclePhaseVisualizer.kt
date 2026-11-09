package com.novarytm.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

@Composable
fun CyclePhaseVisualizer(phase: String, currentDay: Int, totalDays: Int) {
    val progress = if (totalDays > 0) currentDay.toFloat() / totalDays.toFloat() else 0f
    
    // Smooth tween progress sweep on initial composition (FastOutSlowInEasing)
    var animationStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        animationStarted = true
    }
    val animatedProgress by animateFloatAsState(
        targetValue = if (animationStarted) progress.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "CycleProgressSweep"
    )

    // Gentle breathing glow animation on active current-day indicator dot
    val infiniteTransition = rememberInfiniteTransition(label = "DotBreathingTransition")
    val breathScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "DotBreathScale"
    )
    val breathAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "DotBreathAlpha"
    )

    // Harmonic 4-phase color palette
    val menstrualColor = Color(0xFFD48C95)   // Gentle Rose / Coral (Days 1–5)
    val follicularColor = Color(0xFFE8A598)  // Warm Peach / Apricot (Days 6 to Fertile Start)
    val fertileColor = Color(0xFF88BC8F)     // Soft Sage / Mint Green (Fertile Window / Ovulation)
    val lutealColor = Color(0xFFB5838D)      // Delicate Lavender / Mauve (Post-ovulation to cycle end)

    val safeTotal = kotlin.math.max(21, totalDays).toFloat()
    val ovulationDay = kotlin.math.max(7f, safeTotal - 14f)
    val menstrualEnd = kotlin.math.min(5f, safeTotal)
    val fertileStart = kotlin.math.max(menstrualEnd, ovulationDay - 4f)
    val fertileEnd = kotlin.math.min(safeTotal, ovulationDay + 2f)

    // Dynamic active phase color resolution
    val activePhaseColor = when {
        phase.contains("Menstrual", ignoreCase = true) || phase.contains("Period", ignoreCase = true) -> menstrualColor
        phase.contains("Follicular", ignoreCase = true) -> follicularColor
        phase.contains("Ovulat", ignoreCase = true) || phase.contains("Fertil", ignoreCase = true) -> fertileColor
        phase.contains("Luteal", ignoreCase = true) -> lutealColor
        currentDay in 1..menstrualEnd.toInt() -> menstrualColor
        currentDay <= fertileStart.toInt() -> follicularColor
        currentDay <= fertileEnd.toInt() -> fertileColor
        currentDay > fertileEnd.toInt() && currentDay <= safeTotal -> lutealColor
        else -> menstrualColor
    }

    data class PhaseSegment(val color: Color, val startDay: Float, val endDay: Float)
    val segments = remember(safeTotal, ovulationDay, menstrualEnd, fertileStart, fertileEnd) {
        listOf(
            PhaseSegment(menstrualColor, 0f, menstrualEnd),
            PhaseSegment(follicularColor, menstrualEnd, fertileStart),
            PhaseSegment(fertileColor, fertileStart, fertileEnd),
            PhaseSegment(lutealColor, fertileEnd, safeTotal)
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(224.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Day $currentDay of $totalDays days, $phase"
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 8.dp.toPx()

            if (totalDays <= 0) {
                // Fallback track when cycle length is unknown
                drawCircle(
                    color = Color.LightGray.copy(alpha = 0.25f),
                    style = Stroke(width = strokeWidth)
                )
            } else {
                // 1. Draw 4 background phase segments around single dial track at soft gentle opacity (0.25f)
                segments.forEach { seg ->
                    if (seg.endDay > seg.startDay) {
                        val segStartAngle = -90f + (seg.startDay / safeTotal) * 360f
                        val segSweepAngle = ((seg.endDay - seg.startDay) / safeTotal) * 360f
                        drawArc(
                            color = seg.color.copy(alpha = 0.25f),
                            startAngle = segStartAngle,
                            sweepAngle = segSweepAngle,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                        )
                    }
                }

                // 2. Active progress arc sweeping over segments at full vibrant opacity with matching phase colors
                if (animatedProgress > 0f) {
                    val currentProgressDay = animatedProgress * safeTotal
                    segments.forEach { seg ->
                        if (seg.endDay > seg.startDay && currentProgressDay > seg.startDay) {
                            val segStartAngle = -90f + (seg.startDay / safeTotal) * 360f
                            val activeDays = kotlin.math.min(seg.endDay, currentProgressDay) - seg.startDay
                            val activeSweepAngle = (activeDays / safeTotal) * 360f
                            if (activeSweepAngle > 0f) {
                                drawArc(
                                    color = seg.color,
                                    startAngle = segStartAngle,
                                    sweepAngle = activeSweepAngle,
                                    useCenter = false,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                                )
                            }
                        }
                    }
                }
            }

            // 3. Active current-day indicator dot with gentle breathing glow aura using active phase color
            if (currentDay > 0 && animatedProgress > 0.005f) {
                val currentAngle = -90f + 360f * animatedProgress
                val angleRad = (currentAngle * kotlin.math.PI / 180.0)
                val radius = size.minDimension / 2f
                val dotX = center.x + radius * kotlin.math.cos(angleRad).toFloat()
                val dotY = center.y + radius * kotlin.math.sin(angleRad).toFloat()
                val dotCenter = Offset(dotX, dotY)

                val baseDotRadius = 5.dp.toPx()

                // Outer gentle breathing glow aura
                drawCircle(
                    color = activePhaseColor.copy(alpha = breathAlpha * 0.4f),
                    radius = baseDotRadius * 2.2f * breathScale,
                    center = dotCenter
                )

                // Inner breathing glow aura
                drawCircle(
                    color = activePhaseColor.copy(alpha = breathAlpha * 0.7f),
                    radius = baseDotRadius * 1.5f * breathScale,
                    center = dotCenter
                )

                // Outer dot rim (Crisp white border for contrast against track/arc)
                drawCircle(
                    color = Color.White,
                    radius = baseDotRadius + 1.5.dp.toPx(),
                    center = dotCenter
                )

                // Inner solid dot in active phase color
                drawCircle(
                    color = activePhaseColor,
                    radius = baseDotRadius,
                    center = dotCenter
                )
            }
        }
        
        // Center text
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = currentDay.toString(),
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF3E3636)
            )
            Text(
                text = "of $totalDays days",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Text(
                text = phase,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = activePhaseColor,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
