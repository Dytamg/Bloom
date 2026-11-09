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

    val appColors = LocalAppColors.current
    val primaryColor = appColors.primary
    val secondaryColor = appColors.background // Replaces the F3F4F6 background track
    val fertileColor = appColors.secondary

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
            
            // Background circle
            drawCircle(
                color = secondaryColor,
                style = Stroke(width = strokeWidth)
            )

            // Progress arc
            drawArc(
                color = primaryColor,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Fertile window indicator computed dynamically from totalDays
            val safeTotal = kotlin.math.max(21, totalDays).toFloat()
            val ovulationDay = kotlin.math.max(7, totalDays - 14).toFloat()
            val fertileStartDay = kotlin.math.max(5f, ovulationDay - 4f)
            val fertileEndDay = kotlin.math.min(safeTotal, ovulationDay + 1f)
            val fertileStartAngle = -90f + (fertileStartDay / safeTotal) * 360f
            val fertileSweepAngle = ((fertileEndDay - fertileStartDay + 1f) / safeTotal) * 360f
            drawArc(
                color = fertileColor,
                startAngle = fertileStartAngle,
                sweepAngle = fertileSweepAngle,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Active current-day indicator dot with gentle breathing glow
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
                    color = primaryColor.copy(alpha = breathAlpha * 0.4f),
                    radius = baseDotRadius * 2.2f * breathScale,
                    center = dotCenter
                )

                // Inner breathing glow aura
                drawCircle(
                    color = primaryColor.copy(alpha = breathAlpha * 0.7f),
                    radius = baseDotRadius * 1.5f * breathScale,
                    center = dotCenter
                )

                // Outer dot rim (Crisp white border for contrast against track/arc)
                drawCircle(
                    color = Color.White,
                    radius = baseDotRadius + 1.5.dp.toPx(),
                    center = dotCenter
                )

                // Inner solid dot in primary color
                drawCircle(
                    color = primaryColor,
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
                color = primaryColor,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
