package com.bloom.auth

import androidx.compose.runtime.Composable

import com.bloom.storage.SecureStorageManager

expect class GoogleAuthManager {
    val needsInteractiveReauth: Boolean
    val lastAuthRecoverableError: Boolean
    suspend fun getAccessToken(scopes: List<String>): String?
    suspend fun clearToken(token: String)
    suspend fun signIn(): Boolean
    suspend fun invalidateAndRefreshAccessToken(expiredToken: String? = null): String?
}

@Composable
expect fun rememberGoogleAuthManager(storage: SecureStorageManager): GoogleAuthManager
