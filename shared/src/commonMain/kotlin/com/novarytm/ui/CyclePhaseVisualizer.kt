package com.novarytm.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CyclePhaseVisualizer(phase: String, currentDay: Int, totalDays: Int) {
    val progress = if (totalDays > 0) currentDay.toFloat() / totalDays.toFloat() else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing)
    )

    val appColors = LocalAppColors.current
    val primaryColor = appColors.primary
    val secondaryColor = appColors.background // Replaces the F3F4F6 background track
    val fertileColor = appColors.secondary

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(224.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 8.dp.toPx()
            val fertileStrokeWidth = 3.dp.toPx()
            
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

            // Fertile window indicator (approximate, static for UI parity with prototype)
            drawArc(
                color = fertileColor,
                startAngle = 50f, // roughly day 10-15
                sweepAngle = 60f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
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
