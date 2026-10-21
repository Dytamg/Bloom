package com.novarytm.auth

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

import com.novarytm.storage.SecureStorageManager

actual class GoogleAuthManager(
    private val context: Context,
    private val triggerSignIn: suspend () -> Boolean
) {
    actual suspend fun getAccessToken(scopes: List<String>): String? = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            return@withContext null
        }

        val scopeString = "oauth2:" + scopes.joinToString(" ")
        val acct = account.account ?: return@withContext null
        try {
            GoogleAuthUtil.getToken(context, acct, scopeString)
        } catch (e: Exception) {
            android.util.Log.w("BloomAuth", "getAccessToken failed: ${e::class.simpleName}")
            null
        }
    }

    actual suspend fun clearToken(token: String) {
        withContext(Dispatchers.IO) {
            try {
                GoogleAuthUtil.clearToken(context, token)
            } catch (e: Exception) {
                // Silently handle token clear failure
            }
        }
    }

    actual suspend fun signIn(): Boolean {
        return triggerSignIn()
    }
}

@Composable
actual fun rememberGoogleAuthManager(storage: SecureStorageManager): GoogleAuthManager {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingContinuation by remember { mutableStateOf<kotlin.coroutines.Continuation<Boolean>?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        storage.setAuthFlowActive(false)
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            if (account?.account != null) {
                val driveScope = Scope("https://www.googleapis.com/auth/drive.file")
                if (GoogleSignIn.hasPermissions(account, driveScope)) {
                    pendingContinuation?.resume(true)
                    pendingContinuation = null
                } else {
                    pendingContinuation?.resumeWithException(Exception("Permissions not granted after sign-in"))
                    pendingContinuation = null
                }
            } else {
                pendingContinuation?.resumeWithException(Exception("Sign in result was null"))
                pendingContinuation = null
            }
        } catch (e: Exception) {
            android.util.Log.e("BloomAuth", "Sign-in task failed", e)
            pendingContinuation?.resumeWithException(Exception("Sign-in task failed", e))
            pendingContinuation = null
        }
    }

    return remember(context, launcher, scope) {
        GoogleAuthManager(
            context = context,
            triggerSignIn = {
                suspendCancellableCoroutine { continuation ->
                    pendingContinuation = continuation
                    
                    val driveScope = Scope("https://www.googleapis.com/auth/drive.file")
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestEmail()
                        .requestScopes(driveScope)
                        .build()
                    val client = GoogleSignIn.getClient(context, gso)
                    
                    storage.setAuthFlowActive(true)
                    launcher.launch(client.signInIntent)
                }
            }
        )
    }
}
