package com.novarytm.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

actual class SecureStorageManager(private val context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: android.content.SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        android.util.Log.e("SecureStorage", "EncryptedSharedPreferences corrupted/orphaned. Wiping secure prefs and retrying.", e)
        try {
            val file = java.io.File(context.filesDir.parent, "shared_prefs/secure_prefs.xml")
            if (file.exists()) file.delete()
            EncryptedSharedPreferences.create(
                context,
                "secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e2: Exception) {
            android.util.Log.e("SecureStorage", "Hardware EncryptedSharedPreferences failed completely. Using fallback prefs.", e2)
            context.getSharedPreferences("secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    init {
        try {
            // M-7: Forced one-time migration on startup to promote legacy keys to Keystore-wrapped
            if (sharedPreferences.contains("master_key")) {
                getMasterKey()
            }
            if (sharedPreferences.contains("sync_secret_key")) {
                getSyncSecretKey()
            }
        } catch (e: Exception) {
            android.util.Log.e("SecureStorage", "Safe legacy key migration check failed during init", e)
        }
    }

    // VULN-04 FIX: Hardware-backed Keystore key for wrapping the master encryption key.
    // NOTE: setUserAuthenticationRequired is intentionally false because background sync, notifications,
    // and app restart decryption must succeed without throwing UserNotAuthenticatedException. Application-level
    // security is enforced via PIN hash and BiometricPrompt in LockScreen.kt.
    private val keystoreWrapperKey: SecretKey? get() {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            val alias = "bloom_master_key_wrapper"
            var key = if (ks.containsAlias(alias)) {
                ks.getKey(alias, null) as? SecretKey
            } else {
                null
            }

            if (key != null) {
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, key)
                } catch (e: Exception) {
                    android.util.Log.w("SecureStorage", "Existing Keystore wrapper key unusable or timed out (${e.message}). Recreating clean key.", e)
                    try { ks.deleteEntry(alias) } catch (_: Exception) {}
                    key = null
                }
            }

            if (key == null) {
                val keyGen = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
                )
                val builder = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)

                keyGen.init(builder.build())
                key = keyGen.generateKey()
            }
            key
        } catch (e: Exception) {
            android.util.Log.e("SecureStorage", "Failed to generate/load keystore wrapper key", e)
            null
        }
    }

    actual fun savePinHash(hash: ByteArray) {
        // M-6: PIN hash stored in EncryptedSharedPreferences (AES256_GCM)
        sharedPreferences.edit().putString("pin_hash", android.util.Base64.encodeToString(hash, android.util.Base64.DEFAULT)).apply()
    }

    actual fun getPinHash(): ByteArray? {
        val base64 = sharedPreferences.getString("pin_hash", null) ?: return null
        return try {
            android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        } catch (e: Exception) { null }
    }

    // VULN-04 FIX: Master key is wrapped with hardware-backed Keystore AES-GCM key
    actual fun saveMasterKey(key: ByteArray) {
        val wrapper = keystoreWrapperKey
        if (wrapper != null) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, wrapper)
                val iv = cipher.iv  // 12-byte GCM IV
                val encrypted = cipher.doFinal(key)
                val combined = iv + encrypted  // [12-byte IV | ciphertext+tag]
                // H-6: Atomic SharedPreferences edit
                sharedPreferences.edit()
                    .putString("master_key_wrapped", android.util.Base64.encodeToString(combined, android.util.Base64.DEFAULT))
                    .remove("master_key")
                    .apply()
                return
            } catch (e: Exception) {
                android.util.Log.e("SecureStorage", "Keystore hardware encryption failed. Refusing to downgrade.", e)
                throw IllegalStateException("Failed to wrap master key securely with hardware keystore", e)
            }
        }
        sharedPreferences.edit()
            .putString("master_key", android.util.Base64.encodeToString(key, android.util.Base64.DEFAULT))
            .remove("master_key_wrapped")
            .apply()
    }

    // VULN-04 FIX: Unwrap master key using hardware-backed Keystore AES-GCM key
    actual fun getMasterKey(): ByteArray? {
        val wrappedBase64 = sharedPreferences.getString("master_key_wrapped", null)
        val wrapper = keystoreWrapperKey
        if (wrappedBase64 != null && wrapper != null) {
            try {
                val combined = android.util.Base64.decode(wrappedBase64, android.util.Base64.DEFAULT)
                if (combined.size >= 12) {
                    val iv = combined.sliceArray(0 until 12)
                    val encrypted = combined.sliceArray(12 until combined.size)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, wrapper, GCMParameterSpec(128, iv))
                    return cipher.doFinal(encrypted)
                }
            } catch (e: Exception) {
                android.util.Log.e("SecureStorage", "Keystore unwrap failed in getMasterKey", e)
            }
        }
        // Fallback: read legacy plaintext key (pre-VULN-04 installations)
        val legacyBase64 = sharedPreferences.getString("master_key", null) ?: return null
        return try {
            val legacyKey = android.util.Base64.decode(legacyBase64, android.util.Base64.DEFAULT)
            saveMasterKey(legacyKey)
            legacyKey
        } catch (e: Exception) { null }
    }

    actual fun saveUserId(id: String) {
        sharedPreferences.edit().putString("user_id", id).apply()
    }

    actual fun getUserId(): String? {
        return sharedPreferences.getString("user_id", null)
    }

    actual fun saveTargetSyncId(id: String) {
        sharedPreferences.edit().putString("target_sync_id", id).apply()
    }

    actual fun getTargetSyncId(): String? {
        return sharedPreferences.getString("target_sync_id", null)
    }

    actual fun saveSyncSecretKey(key: ByteArray) {
        val wrapper = keystoreWrapperKey
        if (wrapper != null) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, wrapper)
                val iv = cipher.iv
                val encrypted = cipher.doFinal(key)
                val combined = iv + encrypted
                sharedPreferences.edit()
                    .putString("sync_secret_key_wrapped", android.util.Base64.encodeToString(combined, android.util.Base64.DEFAULT))
                    .remove("sync_secret_key")
                    .apply()
                return
            } catch (e: Exception) {
                android.util.Log.e("SecureStorage", "Keystore hardware encryption failed for sync key. Refusing to downgrade.", e)
                throw IllegalStateException("Failed to wrap sync key securely with hardware keystore", e)
            }
        }
        sharedPreferences.edit()
            .putString("sync_secret_key", android.util.Base64.encodeToString(key, android.util.Base64.DEFAULT))
            .remove("sync_secret_key_wrapped")
            .apply()
    }

    actual fun getSyncSecretKey(): ByteArray? {
        val wrappedBase64 = sharedPreferences.getString("sync_secret_key_wrapped", null)
        val wrapper = keystoreWrapperKey
        if (wrappedBase64 != null && wrapper != null) {
            try {
                val combined = android.util.Base64.decode(wrappedBase64, android.util.Base64.DEFAULT)
                if (combined.size >= 12) {
                    val iv = combined.sliceArray(0 until 12)
                    val encrypted = combined.sliceArray(12 until combined.size)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, wrapper, GCMParameterSpec(128, iv))
                    return cipher.doFinal(encrypted)
                }
            } catch (e: Exception) {
                android.util.Log.e("SecureStorage", "Keystore unwrap failed in getSyncSecretKey", e)
            }
        }
        val legacyBase64 = sharedPreferences.getString("sync_secret_key", null) ?: return null
        return try {
            val legacyKey = android.util.Base64.decode(legacyBase64, android.util.Base64.DEFAULT)
            saveSyncSecretKey(legacyKey)
            legacyKey
        } catch (e: Exception) { null }
    }

    actual fun savePartnerEmail(email: String) {
        sharedPreferences.edit().putString("partner_email", email).apply()
    }

    actual fun getPartnerEmail(): String? {
        return sharedPreferences.getString("partner_email", null)
    }

    actual fun setAuthFlowActive(active: Boolean) {
        sharedPreferences.edit()
            .putBoolean("auth_flow_active", active)
            .putLong("auth_flow_active_ts", if (active) android.os.SystemClock.elapsedRealtime() else 0L)
            .apply()
    }

    actual fun isAuthFlowActive(): Boolean {
        val active = sharedPreferences.getBoolean("auth_flow_active", false)
        if (!active) return false
        val ts = sharedPreferences.getLong("auth_flow_active_ts", 0L)
        if (android.os.SystemClock.elapsedRealtime() - ts > 5 * 60 * 1000L) {
            setAuthFlowActive(false)
            return false
        }
        return true
    }

    actual fun setBiometricsEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean("biometrics_enabled", enabled).apply()
    }

    actual fun isBiometricsEnabled(): Boolean {
        return sharedPreferences.getBoolean("biometrics_enabled", false)
    }

    // VULN-01 FIX: Derivation salt for Argon2id key derivation
    actual fun saveDerivationSalt(salt: ByteArray) {
        sharedPreferences.edit().putString(
            "derivation_salt",
            android.util.Base64.encodeToString(salt, android.util.Base64.DEFAULT)
        ).apply()
    }

    actual fun getDerivationSalt(): ByteArray? {
        val base64 = sharedPreferences.getString("derivation_salt", null) ?: return null
        return try {
            android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        } catch (e: Exception) { null }
    }

    actual fun savePartnerPermissionsJson(json: String) {
        sharedPreferences.edit().putString("partner_permissions_json", json).apply()
    }

    actual fun getPartnerPermissionsJson(): String? {
        return sharedPreferences.getString("partner_permissions_json", null)
    }

    actual fun saveThemePreference(themeName: String) {
        sharedPreferences.edit().putString("theme_preference", themeName).apply()
    }

    actual fun getThemePreference(): String? {
        return sharedPreferences.getString("theme_preference", null)
    }

    actual fun clearAll() {
        sharedPreferences.edit().clear().apply()
    }

    actual fun copyToClipboardSecurely(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Bloom Secret", text)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
    }

    actual fun getFailedPinAttempts(): Int {
        return sharedPreferences.getInt("failed_pin_attempts", 0)
    }

    actual fun saveFailedPinAttempts(attempts: Int) {
        sharedPreferences.edit().putInt("failed_pin_attempts", attempts).apply()
    }

    actual fun getPinLockoutUntil(): Long {
        return sharedPreferences.getLong("pin_lockout_until", 0L)
    }

    actual fun savePinLockoutUntil(timestampMs: Long) {
        sharedPreferences.edit().putLong("pin_lockout_until", timestampMs).apply()
    }

    actual fun clearLockoutState() {
        sharedPreferences.edit()
            .putInt("failed_pin_attempts", 0)
            .putLong("pin_lockout_until", 0L)
            .apply()
    }

    actual fun saveLastSyncTimestamp(timestampMs: Long) {
        sharedPreferences.edit().putLong("last_sync_timestamp", timestampMs).apply()
    }

    actual fun getLastSyncTimestamp(): Long {
        return sharedPreferences.getLong("last_sync_timestamp", 0L)
    }
}
