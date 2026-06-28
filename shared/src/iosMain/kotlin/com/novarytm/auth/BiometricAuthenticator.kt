package com.novarytm.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics

@Composable
actual fun BiometricPromptEffect(
    trigger: Int,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            val context = LAContext()
            var authError: kotlinx.cinterop.ObjCObjectVar<platform.Foundation.NSError?>? = null
            
            if (context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, authError)) {
                context.evaluatePolicy(
                    LAPolicyDeviceOwnerAuthenticationWithBiometrics,
                    localizedReason = title,
                    reply = { success, error ->
                        if (success) {
                            onSuccess()
                        } else {
                            onError(error?.localizedDescription ?: "Authentication failed")
                        }
                    }
                )
            } else {
                onError("Biometrics not available")
            }
        }
    }
}
