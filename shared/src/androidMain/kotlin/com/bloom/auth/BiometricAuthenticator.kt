package com.bloom.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

private fun findFragmentActivity(context: android.content.Context): FragmentActivity? {
    var currentContext = context
    var activity: FragmentActivity? = null
    while (currentContext is android.content.ContextWrapper) {
        if (currentContext is FragmentActivity) {
            activity = currentContext
            break
        }
        currentContext = currentContext.baseContext
    }
    if (activity == null && context is FragmentActivity) {
        activity = context
    }
    return activity
}

private fun createBiometricCryptoObject(): Cipher? {
    return try {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "bloom_biometric_key"
        if (!keyStore.containsAlias(alias)) {
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(-1)
            }

            keyGen.init(builder.build())
            keyGen.generateKey()
        }
        val secretKey = keyStore.getKey(alias, null) as javax.crypto.SecretKey
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, secretKey)
        }
    } catch (e: Exception) {
        null
    }
}

private fun buildPromptCallback(cipher: Cipher?, onSuccess: () -> Unit, onError: (String) -> Unit): BiometricPrompt.AuthenticationCallback {
    return object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            super.onAuthenticationError(errorCode, errString)
            onError(errString.toString())
        }

        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            super.onAuthenticationSucceeded(result)
            if (cipher != null && result.cryptoObject?.cipher == null) {
                onError("CryptoObject verification failed")
            } else {
                onSuccess()
            }
        }
    }
}

private fun buildPromptInfo(title: String, subtitle: String): BiometricPrompt.PromptInfo {
    return BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setNegativeButtonText("Use PIN")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()
}

@Composable
actual fun BiometricPromptEffect(
    trigger: Int,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(trigger) {
        if (trigger > 0) {
            val activity = findFragmentActivity(context)
            if (activity == null) {
                onError("Activity is not a FragmentActivity")
                return@LaunchedEffect
            }

            val cipher = createBiometricCryptoObject()
            val executor = ContextCompat.getMainExecutor(context)
            val biometricPrompt = BiometricPrompt(
                activity,
                executor,
                buildPromptCallback(cipher, onSuccess, onError)
            )

            val promptInfo = buildPromptInfo(title, subtitle)

            if (cipher != null) {
                biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
            } else {
                biometricPrompt.authenticate(promptInfo)
            }
        }
    }
}
