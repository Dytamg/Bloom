package com.bloom.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bloom.sync.SyncProviderMode
import com.bloom.sync.SyncRole
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual class SecureStorageManager(private val context: Context) {
    private val sharedPreferences: android.content.SharedPreferences =
        context.getSharedPreferences("bloom_secure_vault", Context.MODE_PRIVATE)

    private val keystoreAlias = "bloom_direct_aes_key"

    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keystoreAlias, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                keystoreAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun saveSecretBytes(slot: String, bytes: ByteArray) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(bytes)
            val combined = iv + ciphertext
            sharedPreferences.edit().putString(slot, android.util.Base64.encodeToString(combined, android.util.Base64.DEFAULT)).commit()
        } catch (e: Exception) {
            android.util.Log.e("SecureStorage", "Failed to save secret bytes for $slot", e)
        }
    }

    private fun getSecretBytes(slot: String): ByteArray? {
        val base64 = sharedPreferences.getString(slot, null) ?: return null
        return try {
            val combined = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            if (combined.size < 13) return null
            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        } catch (e: java.security.KeyStoreException) {
            sharedPreferences.edit().remove(slot).commit()
            null
        } catch (e: Exception) {
            android.util.Log.e("SecureStorage", "Failed to read secret bytes for $slot", e)
            null
        }
    }

    actual fun savePinHash(hash: ByteArray) {
        saveSecretBytes("pin_hash", hash)
    }

    actual fun getPinHash(): ByteArray? {
        return getSecretBytes("pin_hash")
    }

    actual fun saveMasterKey(key: ByteArray) {
        saveSecretBytes("master_key", key)
    }

    actual fun getMasterKey(): ByteArray? {
        return getSecretBytes("master_key")
    }

    actual fun saveUserId(id: String) {
        sharedPreferences.edit().putString("user_id", id).commit()
    }

    actual fun getUserId(): String? {
        return sharedPreferences.getString("user_id", null)
    }

    actual fun saveTargetSyncId(id: String) {
        sharedPreferences.edit().putString("target_sync_id", id).commit()
    }

    actual fun getTargetSyncId(): String? {
        return sharedPreferences.getString("target_sync_id", null)
    }

    actual fun saveSyncSecretKey(key: ByteArray) {
        saveSecretBytes("sync_secret_key", key)
    }

    actual fun getSyncSecretKey(): ByteArray? {
        return getSecretBytes("sync_secret_key")
    }

    actual fun savePartnerEmail(email: String) {
        sharedPreferences.edit().putString("partner_email", email).commit()
    }

    actual fun getPartnerEmail(): String? {
        return sharedPreferences.getString("partner_email", null)
    }

    actual fun setAuthFlowActive(active: Boolean) {
        sharedPreferences.edit()
            .putBoolean("auth_flow_active", active)
            .putLong("auth_flow_active_ts", if (active) android.os.SystemClock.elapsedRealtime() else 0L)
            .commit()
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
        sharedPreferences.edit().putBoolean("biometrics_enabled", enabled).commit()
    }

    actual fun isBiometricsEnabled(): Boolean {
        return sharedPreferences.getBoolean("biometrics_enabled", false)
    }

    actual fun saveDerivationSalt(salt: ByteArray) {
        saveSecretBytes("derivation_salt", salt)
    }

    actual fun getDerivationSalt(): ByteArray? {
        return getSecretBytes("derivation_salt")
    }

    actual fun savePartnerPermissionsJson(json: String) {
        sharedPreferences.edit().putString("partner_permissions_json", json).commit()
    }

    actual fun getPartnerPermissionsJson(): String? {
        return sharedPreferences.getString("partner_permissions_json", null)
    }

    actual fun saveThemePreference(themeName: String) {
        sharedPreferences.edit().putString("theme_preference", themeName).commit()
    }

    actual fun getThemePreference(): String? {
        return sharedPreferences.getString("theme_preference", null)
    }

    actual fun clearAll() {
        sharedPreferences.edit().clear().commit()
    }

    actual fun copyToClipboardSecurely(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        if (text.isEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
            return
        }
        val clip = android.content.ClipData.newPlainText("Copied Text", text)
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
        sharedPreferences.edit().putInt("failed_pin_attempts", attempts).commit()
    }

    actual fun getPinLockoutUntil(): Long {
        val wallUntil = sharedPreferences.getLong("pin_lockout_until", 0L)
        if (wallUntil <= 0L) return 0L
        val elapsedUntil = sharedPreferences.getLong("pin_lockout_elapsed", 0L)
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        if (elapsedUntil > nowElapsed) {
            val remainingMs = (elapsedUntil - nowElapsed).coerceAtMost(86_400_000L)
            return maxOf(wallUntil, nowWall + remainingMs)
        }
        return wallUntil
    }

    actual fun savePinLockoutUntil(timestampMs: Long) {
        if (timestampMs <= 0L) {
            sharedPreferences.edit()
                .putLong("pin_lockout_until", 0L)
                .putLong("pin_lockout_elapsed", 0L)
                .commit()
        } else {
            val nowWall = System.currentTimeMillis()
            val durationMs = (timestampMs - nowWall).coerceIn(0L, 86_400_000L)
            val elapsedUntil = android.os.SystemClock.elapsedRealtime() + durationMs
            sharedPreferences.edit()
                .putLong("pin_lockout_until", timestampMs)
                .putLong("pin_lockout_elapsed", elapsedUntil)
                .commit()
        }
    }

    actual fun clearLockoutState() {
        sharedPreferences.edit()
            .putInt("failed_pin_attempts", 0)
            .putLong("pin_lockout_until", 0L)
            .putLong("pin_lockout_elapsed", 0L)
            .commit()
    }

    actual fun getSyncCodeFailedAttempts(): Int {
        return sharedPreferences.getInt("sync_code_failed_attempts", 0)
    }

    private fun resolveSyncCodeLockoutUntilMs(nowWall: Long, nowElapsed: Long): Long {
        val elapsedUntil = sharedPreferences.getLong("sync_code_lockout_elapsed", 0L)
        if (elapsedUntil > nowElapsed) {
            val remainingMs = (elapsedUntil - nowElapsed).coerceAtMost(PairingRateLimiter.LOCKOUT_300S_MS)
            return nowWall + remainingMs
        }
        val wallUntil = sharedPreferences.getLong("sync_code_lockout_until", 0L)
        if (wallUntil > nowWall) {
            val remainingMs = (wallUntil - nowWall).coerceAtMost(PairingRateLimiter.LOCKOUT_300S_MS)
            return nowWall + remainingMs
        }
        return 0L
    }

    actual fun recordSyncCodeFailedAttempt(): Long {
        val attempts = (getSyncCodeFailedAttempts() + 1).coerceAtLeast(1)
        val nowWall = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val previousUntilMs = resolveSyncCodeLockoutUntilMs(nowWall, nowElapsed)
        val lockUntilWallMs = PairingRateLimiter.computeLockoutUntilMs(
            failedAttempts = attempts,
            nowMs = nowWall,
            previousLockoutUntilMs = previousUntilMs
        )
        val editor = sharedPreferences.edit().putInt("sync_code_failed_attempts", attempts)
        if (lockUntilWallMs > nowWall) {
            val durationMs = (lockUntilWallMs - nowWall).coerceIn(0L, PairingRateLimiter.LOCKOUT_300S_MS)
            editor.putLong("sync_code_lockout_until", lockUntilWallMs)
                .putLong("sync_code_lockout_elapsed", nowElapsed + durationMs)
        } else {
            editor.putLong("sync_code_lockout_until", 0L)
                .putLong("sync_code_lockout_elapsed", 0L)
        }
        editor.commit()
        return lockUntilWallMs
    }

    actual fun getSyncCodeLockoutRemainingSeconds(): Long {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val lockUntilMs = resolveSyncCodeLockoutUntilMs(nowWall, nowElapsed)
        return PairingRateLimiter.remainingLockoutSeconds(lockUntilMs, nowWall)
    }

    actual fun resetSyncCodeFailedAttempts() {
        sharedPreferences.edit()
            .putInt("sync_code_failed_attempts", 0)
            .putLong("sync_code_lockout_until", 0L)
            .putLong("sync_code_lockout_elapsed", 0L)
            .commit()
    }

    actual fun isSyncCodeLockedOut(): Boolean {
        return getSyncCodeLockoutRemainingSeconds() > 0L
    }

    actual fun saveLastSyncTimestamp(timestampMs: Long) {
        sharedPreferences.edit().putLong("last_sync_timestamp", timestampMs).commit()
    }

    actual fun getLastSyncTimestamp(): Long {
        return sharedPreferences.getLong("last_sync_timestamp", 0L)
    }

    actual fun savePrimarySyncFileId(fileId: String?) {
        if (fileId == null) {
            sharedPreferences.edit().remove("primary_sync_file_id").commit()
        } else {
            sharedPreferences.edit().putString("primary_sync_file_id", fileId).commit()
        }
    }

    actual fun getPrimarySyncFileId(): String? {
        return sharedPreferences.getString("primary_sync_file_id", null)
    }

    actual fun saveLastSuccessfulSyncWallclock(timestampMs: Long) {
        sharedPreferences.edit().putLong("last_successful_sync_wallclock", timestampMs).commit()
    }

    actual fun getLastSuccessfulSyncWallclock(): Long {
        return sharedPreferences.getLong("last_successful_sync_wallclock", 0L)
    }

    actual fun setPendingSyncUpload(pending: Boolean) {
        sharedPreferences.edit().putBoolean("pending_sync_upload", pending).commit()
    }

    actual fun hasPendingSyncUpload(): Boolean {
        return sharedPreferences.getBoolean("pending_sync_upload", false)
    }

    private fun putOrRemoveString(key: String, value: String?) {
        if (value == null) {
            sharedPreferences.edit().remove(key).commit()
        } else {
            sharedPreferences.edit().putString(key, value).commit()
        }
    }

    actual fun saveSyncProviderMode(mode: SyncProviderMode) {
        sharedPreferences.edit().putString("sync_provider_mode", mode.name).commit()
    }

    actual fun getSyncProviderMode(): SyncProviderMode {
        val raw = sharedPreferences.getString("sync_provider_mode", null) ?: return SyncProviderMode.DRIVE_API
        return runCatching { SyncProviderMode.valueOf(raw) }.getOrDefault(SyncProviderMode.DRIVE_API)
    }

    actual fun saveSyncRole(role: SyncRole) {
        sharedPreferences.edit().putString("sync_role", role.name).commit()
    }

    actual fun getSyncRole(): SyncRole {
        val raw = sharedPreferences.getString("sync_role", null) ?: return SyncRole.NONE
        return runCatching { SyncRole.valueOf(raw) }.getOrDefault(SyncRole.NONE)
    }

    actual fun saveSafFileUri(uri: String?) {
        putOrRemoveString("saf_file_uri", uri)
    }

    actual fun getSafFileUri(): String? {
        return sharedPreferences.getString("saf_file_uri", null)
    }

    actual fun saveGrantedPartnerEmail(email: String?) {
        putOrRemoveString("granted_partner_email", email)
    }

    actual fun getGrantedPartnerEmail(): String? {
        return sharedPreferences.getString("granted_partner_email", null)
    }

    actual fun savePartnerPermissionId(permissionId: String?) {
        putOrRemoveString("partner_permission_id", permissionId)
    }

    actual fun getPartnerPermissionId(): String? {
        return sharedPreferences.getString("partner_permission_id", null)
    }

    actual fun saveLastUploadedContentHash(hash: String?) {
        putOrRemoveString("last_uploaded_content_hash", hash)
    }

    actual fun getLastUploadedContentHash(): String? {
        return sharedPreferences.getString("last_uploaded_content_hash", null)
    }

    actual fun saveLastImportedVersion(version: Long) {
        sharedPreferences.edit().putLong("last_imported_version", version).commit()
    }

    actual fun getLastImportedVersion(): Long {
        return sharedPreferences.getLong("last_imported_version", 0L)
    }
}
