package com.novarytm.auth

import androidx.compose.runtime.Composable

@Composable
expect fun BiometricPromptEffect(
    trigger: Int,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
)
