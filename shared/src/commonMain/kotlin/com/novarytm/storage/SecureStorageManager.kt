package com.novarytm.storage

expect class SecureStorageManager {
    fun savePinHash(hash: ByteArray)
    fun getPinHash(): ByteArray?
    fun saveMasterKey(key: ByteArray)
    fun getMasterKey(): ByteArray?
    fun saveUserId(id: String)
    fun getUserId(): String?
    fun saveTargetSyncId(id: String)
    fun getTargetSyncId(): String?
    fun saveSyncSecretKey(key: ByteArray)
    fun getSyncSecretKey(): ByteArray?
    fun savePartnerEmail(email: String)
    fun getPartnerEmail(): String?
    fun setAuthFlowActive(active: Boolean)
    fun isAuthFlowActive(): Boolean
    fun setBiometricsEnabled(enabled: Boolean)
    fun isBiometricsEnabled(): Boolean
    // VULN-01 FIX: Salt for Argon2id key derivation (new installations)
    fun saveDerivationSalt(salt: ByteArray)
    fun getDerivationSalt(): ByteArray?
    fun savePartnerPermissionsJson(json: String)
    fun getPartnerPermissionsJson(): String?
    fun saveThemePreference(themeName: String)
    fun getThemePreference(): String?
    fun clearAll()
    fun copyToClipboardSecurely(text: String)
    fun getFailedPinAttempts(): Int
    fun saveFailedPinAttempts(attempts: Int)
    fun getPinLockoutUntil(): Long
    fun savePinLockoutUntil(timestampMs: Long)
    fun clearLockoutState()
    fun saveLastSyncTimestamp(timestampMs: Long)
    fun getLastSyncTimestamp(): Long
}
