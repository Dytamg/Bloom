package com.novarytm.auth

import androidx.compose.runtime.Composable

import com.novarytm.storage.SecureStorageManager

expect class GoogleAuthManager {
    val needsInteractiveReauth: Boolean
    val lastAuthRecoverableError: Boolean
    suspend fun getAccessToken(scopes: List<String>): String?
    suspend fun clearToken(token: String)
    suspend fun signIn(): Boolean
}

@Composable
expect fun rememberGoogleAuthManager(storage: SecureStorageManager): GoogleAuthManager
