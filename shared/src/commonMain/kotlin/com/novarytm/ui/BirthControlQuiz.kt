package com.novarytm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BirthControlQuiz(onComplete: (String) -> Unit) {
    var step by remember { mutableStateOf(0) }
    var selectedType by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (step) {
            0 -> {
                Text("Do you use any form of birth control?", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { step = 1 }) { Text("Yes") }
                Button(onClick = { onComplete("None") }) { Text("No") }
            }
            1 -> {
                Text("Which type do you use?", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                val types = listOf("Pill", "IUD", "Patch", "Injection", "Other")
                types.forEach { type ->
                    Button(onClick = { 
                        selectedType = type
                        onComplete(type)
                    }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(type)
                    }
                }
            }
        }
    }
}
