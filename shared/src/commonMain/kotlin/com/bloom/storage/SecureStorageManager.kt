package com.bloom.storage

import com.bloom.sync.SyncProviderMode
import com.bloom.sync.SyncRole

@OptIn(kotlin.time.ExperimentalTime::class)
class PairingRateLimiter(
    private val nowMsProvider: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }
) {
    private var attempts: Int = 0
    private var lockUntilMs: Long = 0L

    fun getSyncCodeFailedAttempts(): Int = attempts

    fun recordSyncCodeFailedAttempt(nowMs: Long = nowMsProvider()): Long {
        attempts = (attempts + 1).coerceAtLeast(1)
        lockUntilMs = computeLockoutUntilMs(
            failedAttempts = attempts,
            nowMs = nowMs,
            previousLockoutUntilMs = lockUntilMs
        )
        return lockUntilMs
    }

    fun getSyncCodeLockoutRemainingSeconds(nowMs: Long = nowMsProvider()): Long =
        remainingLockoutSeconds(lockUntilMs, nowMs)

    fun isLockedOut(nowMs: Long = nowMsProvider()): Boolean =
        isLockedOut(lockUntilMs, nowMs)

    fun resetSyncCodeFailedAttempts() {
        attempts = 0
        lockUntilMs = 0L
    }

    companion object {
        const val FREE_ATTEMPTS_LIMIT: Int = 2
        const val LOCKOUT_30S_MS: Long = 30_000L
        const val LOCKOUT_60S_MS: Long = 60_000L
        const val LOCKOUT_300S_MS: Long = 300_000L

        fun lockoutDurationMs(failedAttempts: Int): Long = when {
            failedAttempts < 3 -> 0L
            failedAttempts == 3 -> LOCKOUT_30S_MS
            failedAttempts == 4 -> LOCKOUT_60S_MS
            else -> LOCKOUT_300S_MS
        }

        fun computeLockoutUntilMs(
            failedAttempts: Int,
            nowMs: Long,
            previousLockoutUntilMs: Long = 0L
        ): Long {
            val activePrevious = if (previousLockoutUntilMs > nowMs) previousLockoutUntilMs else 0L
            val durationMs = lockoutDurationMs(failedAttempts)
            if (durationMs <= 0L) return activePrevious
            return maxOf(activePrevious, nowMs + durationMs)
        }

        fun remainingLockoutSeconds(lockoutUntilMs: Long, nowMs: Long): Long {
            val remainingMs = lockoutUntilMs - nowMs
            if (remainingMs <= 0L) return 0L
            return ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
        }

        fun isLockedOut(lockoutUntilMs: Long, nowMs: Long): Boolean = nowMs < lockoutUntilMs
    }
}

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
    fun getSyncCodeFailedAttempts(): Int
    fun recordSyncCodeFailedAttempt(): Long
    fun getSyncCodeLockoutRemainingSeconds(): Long
    fun isSyncCodeLockedOut(): Boolean
    fun resetSyncCodeFailedAttempts()
    fun getPrimarySyncFileId(): String?
    fun savePrimarySyncFileId(fileId: String?)
    fun getLastSyncTimestamp(): Long
    fun saveLastSyncTimestamp(timestampMs: Long)
    fun saveLastSuccessfulSyncWallclock(timestampMs: Long)
    fun getLastSuccessfulSyncWallclock(): Long
    fun setPendingSyncUpload(pending: Boolean)
    fun hasPendingSyncUpload(): Boolean
    fun saveSyncProviderMode(mode: SyncProviderMode)
    fun getSyncProviderMode(): SyncProviderMode
    fun saveSyncRole(role: SyncRole)
    fun getSyncRole(): SyncRole
    fun saveSafFileUri(uri: String?)
    fun getSafFileUri(): String?
    fun saveGrantedPartnerEmail(email: String?)
    fun getGrantedPartnerEmail(): String?
    fun savePartnerPermissionId(permissionId: String?)
    fun getPartnerPermissionId(): String?
    fun saveLastUploadedContentHash(hash: String?)
    fun getLastUploadedContentHash(): String?
    fun saveLastImportedVersion(version: Long)
    fun getLastImportedVersion(): Long
}
