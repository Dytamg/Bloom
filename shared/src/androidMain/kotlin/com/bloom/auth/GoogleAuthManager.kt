package com.bloom.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import androidx.credentials.CredentialManager
import androidx.credentials.ClearCredentialStateRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

import com.bloom.storage.SecureStorageManager

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

    actual suspend fun getAccessToken(scopes: List<String>): String? = withContext(Dispatchers.IO) {
        runCatching {
            val reqScopes = scopes.map { Scope(it) }
            val request = AuthorizationRequest.builder().setRequestedScopes(reqScopes).build()
            val task = Identity.getAuthorizationClient(context).authorize(request)
            val result = task.awaitTask()
            if (!result.hasResolution()) result.accessToken else null
        }.getOrNull()
    }

    actual suspend fun clearToken(token: String): Unit = withContext(Dispatchers.IO) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
        Unit
    }

    actual suspend fun invalidateAndRefreshAccessToken(expiredToken: String?): String? = withContext(Dispatchers.IO) {
        if (!expiredToken.isNullOrEmpty()) clearToken(expiredToken)
        getAccessToken(listOf("https://www.googleapis.com/auth/drive.file", "email", "profile"))
    }

    actual suspend fun signIn(): Boolean {
        val success = runCatching { triggerSignIn() }.getOrElse { false }
        if (success) {
            _needsInteractiveReauth = false
            _lastAuthRecoverableError = false
        }
        return success
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = context.getSharedPreferences("bloom_auth_prefs", Context.MODE_PRIVATE)
            val token = prefs.getString("last_token", null)
            prefs.edit().clear().apply()
            if (!token.isNullOrEmpty()) {
                clearToken(token)
            }
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        }
    }

    fun getSignedInEmail(): String? {
        return context.getSharedPreferences("bloom_auth_prefs", Context.MODE_PRIVATE).getString("email", null)
    }

    fun isSignedIn(): Boolean {
        return !getSignedInEmail().isNullOrBlank()
    }
}

private fun fetchAndSaveUserInfo(context: Context, token: String) {
    GlobalScope.launch(Dispatchers.IO) {
        runCatching {
            val url = URL("https://www.googleapis.com/oauth2/v3/userinfo")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val email = JSONObject(text).optString("email")
            if (email.isNotBlank()) {
                context.getSharedPreferences("bloom_auth_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("email", email)
                    .putString("last_token", token)
                    .apply()
            }
        }
    }
}

private fun handleAuthResult(context: Context, intent: Intent?, continuation: kotlin.coroutines.Continuation<Boolean>?) {
    runCatching {
        if (intent == null) {
            continuation?.resume(false)
            return
        }
        val authResult = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)
        GlobalScope.launch(Dispatchers.IO) {
            val token = authResult.accessToken
            if (!token.isNullOrBlank()) {
                fetchAndSaveUserInfo(context, token)
                withContext(Dispatchers.Main) { continuation?.resume(true) }
            } else {
                withContext(Dispatchers.Main) { continuation?.resume(false) }
            }
        }
    }.onFailure {
        continuation?.resume(false)
    }
}

@Composable
actual fun rememberGoogleAuthManager(storage: SecureStorageManager): GoogleAuthManager {
    val context = LocalContext.current
    var pendingContinuation by remember { mutableStateOf<kotlin.coroutines.Continuation<Boolean>?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        storage.setAuthFlowActive(false)
        val continuation = pendingContinuation
        pendingContinuation = null
        if (result.resultCode == Activity.RESULT_OK) {
            handleAuthResult(context, result.data, continuation)
        } else {
            continuation?.resume(false)
        }
    }

    return remember(context, launcher) {
        GoogleAuthManager(
            context = context,
            triggerSignIn = {
                suspendCancellableCoroutine { continuation ->
                    pendingContinuation?.resume(false)
                    pendingContinuation = continuation
                    continuation.invokeOnCancellation { if (pendingContinuation === continuation) pendingContinuation = null }
                    storage.setAuthFlowActive(true)
                    GlobalScope.launch(Dispatchers.IO) {
                        runCatching {
                            val reqScopes = listOf(Scope("https://www.googleapis.com/auth/drive.file"), Scope("email"), Scope("profile"))
                            val request = AuthorizationRequest.builder().setRequestedScopes(reqScopes).build()
                            val task = Identity.getAuthorizationClient(context).authorize(request)
                            val authResult = task.awaitTask()
                            withContext(Dispatchers.Main) {
                                storage.setAuthFlowActive(false)
                                pendingContinuation = null
                                if (!authResult.hasResolution() && !authResult.accessToken.isNullOrBlank()) {
                                    fetchAndSaveUserInfo(context, authResult.accessToken!!)
                                    continuation.resume(true)
                                } else if (authResult.pendingIntent != null) {
                                    val isr = IntentSenderRequest.Builder(authResult.pendingIntent!!).build()
                                    launcher.launch(isr)
                                } else {
                                    continuation.resume(false)
                                }
                            }
                        }.onFailure {
                            withContext(Dispatchers.Main) {
                                storage.setAuthFlowActive(false)
                                pendingContinuation = null
                                continuation.resume(false)
                            }
                        }
                    }
                }
            }
        )
    }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { error ->
            if (cont.isActive) cont.resumeWithException(error)
        }
        addOnCanceledListener {
            cont.cancel()
        }
    }
