package com.novarytm.storage

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

class SecureStorageManager : com.novarytm.storage.SecureStorageManager {

    override fun savePinHash(hash: ByteArray) {
        saveString("pin_hash", hash.toBase64String())
    }

    override fun getPinHash(): ByteArray? {
        val base64 = getString("pin_hash") ?: return null
        return base64.fromBase64()
    }

    override fun saveMasterKey(key: ByteArray) {
        saveString("master_key", key.toBase64String())
    }

    override fun getMasterKey(): ByteArray? {
        val base64 = getString("master_key") ?: return null
        return base64.fromBase64()
    }

    override fun saveUserId(id: String) {
        saveString("user_id", id)
    }

    override fun getUserId(): String? {
        return getString("user_id")
    }

    override fun saveTargetSyncId(id: String) {
        saveString("target_sync_id", id)
    }

    override fun getTargetSyncId(): String? {
        return getString("target_sync_id")
    }

    override fun saveSyncSecretKey(key: ByteArray) {
        saveString("sync_secret_key", key.toBase64String())
    }

    override fun getSyncSecretKey(): ByteArray? {
        val base64 = getString("sync_secret_key") ?: return null
        return base64.fromBase64()
    }

    override fun savePartnerEmail(email: String) {
        saveString("partner_email", email)
    }

    override fun getPartnerEmail(): String? {
        return getString("partner_email")
    }

    actual fun setAuthFlowActive(active: Boolean) {
        saveBoolean("auth_flow_active", active)
    }

    actual fun isAuthFlowActive(): Boolean {
        return getBoolean("auth_flow_active")
    }

    actual fun setBiometricsEnabled(enabled: Boolean) {
        saveBoolean("biometrics_enabled", enabled)
    }

    actual fun isBiometricsEnabled(): Boolean {
        return getBoolean("biometrics_enabled")
    }

    // VULN-01 FIX: Derivation salt for Argon2id key derivation
    override fun saveDerivationSalt(salt: ByteArray) {
        saveString("derivation_salt", salt.toBase64String())
    }

    override fun getDerivationSalt(): ByteArray? {
        val base64 = getString("derivation_salt") ?: return null
        return base64.fromBase64()
    }

    actual fun saveThemePreference(themeName: String) {
        saveString("theme_preference", themeName)
    }

    actual fun getThemePreference(): String? {
        return getString("theme_preference")
    }

    override fun clearAll() {
        val query = mutableMapOf<CFTypeRef?, CFTypeRef?>(
            kSecClass to kSecClassGenericPassword
        )
        SecItemDelete(query.toCFDictionary())
    }

    // iOS Keychain Helper Logic
    private fun saveString(key: String, value: String) {
        val data = value.encodeToNSData()
        
        // 1. Try to delete existing item
        val query = mutableMapOf<CFTypeRef?, CFTypeRef?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrAccount to key.toCFString()
        )
        SecItemDelete(query.toCFDictionary())

        // 2. Add new item
        query[kSecValueData] = data
        query[kSecAttrAccessible] = kSecAttrAccessibleWhenUnlocked
        SecItemAdd(query.toCFDictionary(), null)
    }

    private fun getString(key: String): String? {
        val query = mutableMapOf<CFTypeRef?, CFTypeRef?>(
            kSecClass to kSecClassGenericPassword,
            kSecAttrAccount to key.toCFString(),
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne
        )

        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query.toCFDictionary(), result.ptr)
            if (status == errSecSuccess) {
                val data = result.value as? NSData
                return data?.toKotlinString()
            }
        }
        return null
    }

    // Native Platform Converters
    private fun String.toCFString(): CFStringRef? = CFStringCreateWithCString(null, this, kCFStringEncodingUTF8)
    
    private fun Map<CFTypeRef?, CFTypeRef?>.toCFDictionary(): CFDictionaryRef? {
        val keys = this.keys.toList().toTypedArray()
        val values = this.values.toList().toTypedArray()
        return CFDictionaryCreate(
            null,
            keys.filterNotNull().toTypedArray().reinterpret(),
            values.filterNotNull().toTypedArray().reinterpret(),
            this.size.toLong(),
            null,
            null
        )
    }

    private fun String.encodeToNSData(): NSData? = 
        (this as NSString).dataUsingEncoding(NSUTF8StringEncoding)

    actual fun savePartnerPermissionsJson(json: String) {
        saveString("partner_permissions_json", json)
    }

    actual fun getPartnerPermissionsJson(): String? {
        return getString("partner_permissions_json")
    }

    actual fun getFailedPinAttempts(): Int {
        return getString("failed_pin_attempts")?.toIntOrNull() ?: 0
    }

    actual fun saveFailedPinAttempts(attempts: Int) {
        saveString("failed_pin_attempts", attempts.toString())
    }

    actual fun getPinLockoutUntil(): Long {
        return getString("pin_lockout_until")?.toLongOrNull() ?: 0L
    }

    actual fun savePinLockoutUntil(timestampMs: Long) {
        saveString("pin_lockout_until", timestampMs.toString())
    }

    actual fun saveLastSyncTimestamp(timestampMs: Long) {
        saveString("last_sync_timestamp", timestampMs.toString())
    }

    actual fun getLastSyncTimestamp(): Long {
        return getString("last_sync_timestamp")?.toLongOrNull() ?: 0L
    }

    actual fun copyToClipboardSecurely(text: String) {
        val pasteboard = platform.UIKit.UIPasteboard.generalPasteboard
        pasteboard.string = text
        
        // Auto-clear clipboard after 60 seconds to prevent lingering sensitive data
        NSTimer.scheduledTimerWithTimeInterval(60.0, false) { _ ->
            if (pasteboard.string == text) {
                pasteboard.string = ""
            }
        }
    }

    private fun NSData.toKotlinString(): String? = 
        NSString(this, NSUTF8StringEncoding).toString()

    private fun ByteArray.toBase64String(): String = 
        this.toNSData().base64EncodedStringWithOptions(0L)

    private fun String.fromBase64(): ByteArray? = 
        NSData(base64EncodedString = this, options = 0L)?.toByteArray()

    private fun ByteArray.toNSData(): NSData = memScoped {
        NSData.dataWithBytes(allocArrayOf(this@toNSData), this@toNSData.size.toULong())
    }

    private fun NSData.toByteArray(): ByteArray {
        val bytes = this.bytes?.reinterpret<ByteVar>() ?: return ByteArray(0)
        return ByteArray(this.length.toInt()) { i -> bytes[i] }
    }
}
