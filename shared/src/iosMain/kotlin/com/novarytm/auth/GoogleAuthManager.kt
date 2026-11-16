package com.novarytm.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.AuthenticationServices.*
import platform.Foundation.*
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UIKit.UIApplication

import com.novarytm.storage.SecureStorageManager

actual class GoogleAuthManager(private val storage: SecureStorageManager) {
    private var accessToken: String? = null
    private var _needsInteractiveReauth: Boolean = false
    actual val needsInteractiveReauth: Boolean
        get() = _needsInteractiveReauth
    private var _lastAuthRecoverableError: Boolean = false
    actual val lastAuthRecoverableError: Boolean
        get() = _lastAuthRecoverableError

    actual suspend fun getAccessToken(scopes: List<String>): String? {
        if (accessToken == null) {
            _needsInteractiveReauth = true
            _lastAuthRecoverableError = true
        }
        return accessToken
    }
    
    actual suspend fun clearToken(token: String) {
        if (accessToken == token) {
            accessToken = null
            _needsInteractiveReauth = true
            _lastAuthRecoverableError = true
        }
    }

    actual suspend fun signIn(): Boolean = suspendCancellableCoroutine { continuation ->
        // Replace with your actual iOS Client ID from Google Cloud Console
        val iosClientId = "YOUR_IOS_CLIENT_ID.apps.googleusercontent.com"
        val redirectUri = "${iosClientId.reversed().substringAfter(".")}.googleusercontent.com:/"
        
        val authUrlString = "https://accounts.google.com/o/oauth2/v2/auth?" +
            "client_id=$iosClientId&" +
            "response_type=token&" +
            "redirect_uri=$redirectUri&" +
            "prompt=consent&" +
            "scope=https://www.googleapis.com/auth/drive.file"

        val authUrl = NSURL.URLWithString(authUrlString)
        
        if (authUrl == null) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        val session = ASWebAuthenticationSession(
            url = authUrl,
            callbackURLScheme = redirectUri.substringBefore(":"),
            completionHandler = { url, error ->
                if (error != null || url == null) {
                    println("iOS Auth Error: ${error?.localizedDescription}")
                    continuation.resume(false)
                } else {
                    // Extract token from URL fragment
                    val fragment = url.fragment ?: ""
                    val params = fragment.split("&").associate { 
                        val parts = it.split("=")
                        parts[0] to (parts.getOrNull(1) ?: "")
                    }
                    
                    val token = params["access_token"]
                    val grantedScopes = params["scope"] ?: ""
                    
                    if (token != null && (grantedScopes.contains("drive.file") || grantedScopes.contains("drive"))) {
                        accessToken = token
                        _needsInteractiveReauth = false
                        _lastAuthRecoverableError = false
                        storage.setAuthFlowActive(false)
                        continuation.resume(true)
                    } else {
                        println("iOS Auth Warning: Token missing or drive.file scope not granted. Scopes: $grantedScopes")
                        accessToken = null
                        _needsInteractiveReauth = true
                        _lastAuthRecoverableError = true
                        storage.setAuthFlowActive(false)
                        continuation.resume(false)
                    }
                }
            }
        )
        
        session.presentationContextProvider = object : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
            override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): platform.UIKit.UIWindow {
                return UIApplication.sharedApplication.keyWindow ?: platform.UIKit.UIWindow()
            }
        }
        
        session.prefersEphemeralWebBrowserSession = true
        storage.setAuthFlowActive(true)
        session.start()
    }
}

@Composable
actual fun rememberGoogleAuthManager(storage: SecureStorageManager): GoogleAuthManager {
    return remember { GoogleAuthManager(storage) }
}
