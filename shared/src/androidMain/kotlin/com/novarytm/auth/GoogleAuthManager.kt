package com.novarytm.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume

import com.novarytm.storage.SecureStorageManager

actual class GoogleAuthManager(
    private val context: Context,
    private val triggerSignIn: suspend () -> Boolean
) {
    private var _needsInteractiveReauth: Boolean = false
    actual val needsInteractiveReauth: Boolean
        get() = _needsInteractiveReauth

    private var _lastAuthRecoverableError: Boolean = false
    actual val lastAuthRecoverableError: Boolean
        get() = _lastAuthRecoverableError

    @Volatile
    private var pendingRecoverableIntent: Intent? = null

    internal fun consumeRecoverableIntent(): Intent? {
        val intent = pendingRecoverableIntent
        pendingRecoverableIntent = null
        return intent
    }

    fun buildSignInClient(): GoogleSignInClient {
        val driveScope = Scope("https://www.googleapis.com/auth/drive.file")
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(driveScope)
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    private fun isTransientStatusCode(statusCode: Int): Boolean {
        return statusCode == CommonStatusCodes.NETWORK_ERROR ||
            statusCode == CommonStatusCodes.TIMEOUT ||
            statusCode == CommonStatusCodes.INTERRUPTED ||
            statusCode == CommonStatusCodes.INTERNAL_ERROR
    }

    actual suspend fun getAccessToken(scopes: List<String>): String? = withContext(Dispatchers.IO) {
        val driveScope = Scope("https://www.googleapis.com/auth/drive.file")
        var account = GoogleSignIn.getLastSignedInAccount(context)

        if (account == null || !GoogleSignIn.hasPermissions(account, driveScope)) {
            val client = buildSignInClient()
            try {
                val task = client.silentSignIn()
                account = Tasks.await(task)
            } catch (e: Exception) {
                val cause = (e as? ExecutionException)?.cause ?: e
                when (cause) {
                    is ApiException -> {
                        android.util.Log.w("BloomAuth", "silentSignIn ApiException: status=${cause.statusCode}, ${cause.message}")
                        if (isTransientStatusCode(cause.statusCode)) {
                            _needsInteractiveReauth = false
                            _lastAuthRecoverableError = false
                            return@withContext null
                        }
                    }
                    is IOException -> {
                        android.util.Log.w("BloomAuth", "silentSignIn transient network error: ${cause.message}")
                        _needsInteractiveReauth = false
                        _lastAuthRecoverableError = false
                        return@withContext null
                    }
                    else -> {
                        android.util.Log.w("BloomAuth", "silentSignIn failed: ${cause::class.simpleName}: ${cause.message}")
                    }
                }
            }
        }

        if (account == null || !GoogleSignIn.hasPermissions(account, driveScope)) {
            _needsInteractiveReauth = true
            _lastAuthRecoverableError = true
            return@withContext null
        }

        val acct = account.account
        if (acct == null) {
            _needsInteractiveReauth = true
            _lastAuthRecoverableError = true
            return@withContext null
        }

        val scopeString = "oauth2:" + scopes.joinToString(" ")
        try {
            val token = GoogleAuthUtil.getToken(context, acct, scopeString)
            _needsInteractiveReauth = false
            _lastAuthRecoverableError = false
            pendingRecoverableIntent = null
            token
        } catch (e: UserRecoverableAuthException) {
            android.util.Log.w("BloomAuth", "Interactive consent/reauth required: ${e.message}")
            pendingRecoverableIntent = e.intent
            _needsInteractiveReauth = true
            _lastAuthRecoverableError = true
            null
        } catch (e: IOException) {
            android.util.Log.w("BloomAuth", "Transient network error getting token: ${e.message}")
            _needsInteractiveReauth = false
            _lastAuthRecoverableError = false
            null
        } catch (e: GoogleAuthException) {
            android.util.Log.w("BloomAuth", "GoogleAuthException in getAccessToken: ${e.message}")
            _needsInteractiveReauth = true
            _lastAuthRecoverableError = true
            null
        } catch (e: Exception) {
            android.util.Log.w("BloomAuth", "getAccessToken failed: ${e::class.simpleName}: ${e.message}")
            _needsInteractiveReauth = true
            _lastAuthRecoverableError = true
            null
        }
    }

    actual suspend fun clearToken(token: String) {
        withContext(Dispatchers.IO) {
            try {
                GoogleAuthUtil.clearToken(context, token)
            } catch (e: Exception) {
                android.util.Log.w("BloomAuth", "clearToken failed: ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    actual suspend fun signIn(): Boolean {
        val success = try {
            triggerSignIn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("BloomAuth", "signIn failed: ${e::class.simpleName}: ${e.message}")
            false
        }
        if (success) {
            _needsInteractiveReauth = false
            _lastAuthRecoverableError = false
            pendingRecoverableIntent = null
        }
        return success
    }
}

@Composable
actual fun rememberGoogleAuthManager(storage: SecureStorageManager): GoogleAuthManager {
    val context = LocalContext.current
    var pendingContinuation by remember { mutableStateOf<kotlin.coroutines.Continuation<Boolean>?>(null) }
    var isRecoverableAuthLaunch by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        storage.setAuthFlowActive(false)
        val continuation = pendingContinuation
        pendingContinuation = null
        val wasRecoverableLaunch = isRecoverableAuthLaunch
        isRecoverableAuthLaunch = false

        if (wasRecoverableLaunch) {
            continuation?.resume(result.resultCode == Activity.RESULT_OK)
            return@rememberLauncherForActivityResult
        }

        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            if (account?.account != null) {
                val driveScope = Scope("https://www.googleapis.com/auth/drive.file")
                if (GoogleSignIn.hasPermissions(account, driveScope)) {
                    continuation?.resume(true)
                } else {
                    android.util.Log.w("BloomAuth", "Drive permission not granted after sign-in")
                    continuation?.resume(false)
                }
            } else {
                android.util.Log.w("BloomAuth", "Sign-in result account was null")
                continuation?.resume(false)
            }
        } catch (e: Exception) {
            android.util.Log.w("BloomAuth", "Sign-in task failed or cancelled: ${e::class.simpleName}: ${e.message}")
            continuation?.resume(false)
        }
    }

    return remember(context, launcher) {
        var managerRef: GoogleAuthManager? = null
        GoogleAuthManager(
            context = context,
            triggerSignIn = {
                suspendCancellableCoroutine { continuation ->
                    pendingContinuation?.resume(false)
                    pendingContinuation = continuation
                    continuation.invokeOnCancellation {
                        if (pendingContinuation === continuation) {
                            pendingContinuation = null
                        }
                    }

                    val recoveryIntent = managerRef?.consumeRecoverableIntent()
                    storage.setAuthFlowActive(true)
                    if (recoveryIntent != null) {
                        isRecoverableAuthLaunch = true
                        launcher.launch(recoveryIntent)
                    } else {
                        isRecoverableAuthLaunch = false
                        val driveScope = Scope("https://www.googleapis.com/auth/drive.file")
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestEmail()
                            .requestScopes(driveScope)
                            .build()
                        val client = GoogleSignIn.getClient(context, gso)
                        launcher.launch(client.signInIntent)
                    }
                }
            }
        ).also { managerRef = it }
    }
}
