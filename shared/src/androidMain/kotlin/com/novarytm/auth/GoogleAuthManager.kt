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
        GoogleAuthUtil.getToken(context, acct, scopeString)
    }

    actual suspend fun clearToken(token: String) {
        withContext(Dispatchers.IO) {
            try {
                GoogleAuthUtil.clearToken(context, token)
            } catch (e: Exception) {
                e.printStackTrace()
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
                // L-4: Use appdata scope instead of drive.file
                val driveScope = Scope("https://www.googleapis.com/auth/drive.appdata")
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
            pendingContinuation?.resumeWithException(Exception("Sign-in task failed: ${e.message}", e))
            pendingContinuation = null
        }
    }

    return remember(context, launcher, scope) {
        GoogleAuthManager(
            context = context,
            triggerSignIn = {
                suspendCancellableCoroutine { continuation ->
                    pendingContinuation = continuation
                    
                    val driveScope = Scope("https://www.googleapis.com/auth/drive.appdata")
                    val existingAccount = GoogleSignIn.getLastSignedInAccount(context)
                    
                    if (existingAccount?.account != null && GoogleSignIn.hasPermissions(existingAccount, driveScope)) {
                        // Already signed in and has permissions
                        continuation.resume(true)
                        pendingContinuation = null
                    } else {
                        // Needs to sign in or request permissions
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestEmail()
                            .requestScopes(driveScope)
                            .build()
                        val client = GoogleSignIn.getClient(context, gso)
                        
                        // If we already have an account but no permissions, calling signInIntent
                        // will prompt the user to grant the missing scopes.
                        // However, to be absolutely sure the user sees the consent screen if they previously denied it,
                        // we revoke access first.
                        if (existingAccount != null) {
                            client.revokeAccess().addOnCompleteListener {
                                storage.setAuthFlowActive(true)
                                launcher.launch(client.signInIntent)
                            }
                        } else {
                            storage.setAuthFlowActive(true)
                            launcher.launch(client.signInIntent)
                        }
                    }
                }
            }
        )
    }
}
