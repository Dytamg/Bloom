package com.novarytm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineExceptionHandler

val GlobalExceptionHandler = CoroutineExceptionHandler { _, throwable ->
    println("CRITICAL COROUTINE ERROR: ${throwable.message}")
    throwable.printStackTrace()
    // In a real app, we would update a global UI state to show the FallbackErrorScreen
}

@Composable
fun FallbackErrorScreen(
    message: String = "A critical error occurred. Please restart the app.",
    onRestart: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalAppColors.current.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = LocalAppColors.current.primary,
                modifier = Modifier.size(64.dp)
            )
            
            Spacer(Modifier.height(24.dp))
            
            Text(
                "Something went wrong",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF3E3636)
            )
            
            Spacer(Modifier.height(16.dp))
            
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(48.dp))

            Button(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LocalAppColors.current.primary)
            ) {
                Text("Attempt Restart", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
