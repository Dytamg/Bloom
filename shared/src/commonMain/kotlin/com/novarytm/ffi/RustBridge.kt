package com.novarytm.ffi

import com.novarytm.sync.PartnerPermissions

expect class RustBridge {
    fun calculatePhase(lastPeriodDate: String, avgLength: Int): String
    fun predictNextPeriod(lastPeriodDate: String, avgLength: Int): String
    fun analyzeCycleHistory(datesCsv: String): String
    
    // VULN-01 FIX: Salt enables Argon2id derivation; empty salt falls back to legacy BLAKE3
    fun deriveKey(pin: ByteArray, salt: ByteArray): ByteArray
    fun generatePinHash(pin: ByteArray): ByteArray
    // VULN-01 FIX: Salt enables Argon2id derivation; empty salt falls back to legacy BLAKE3
    fun verifyAndUnlock(pinHash: ByteArray, inputPin: ByteArray, salt: ByteArray): Boolean
    fun unlockWithBiometric(keyBytes: ByteArray): Boolean
    fun lockDatabase()

    fun savePartnerPermissions(permissions: PartnerPermissions)
    fun getPartnerPermissions(): PartnerPermissions
    fun generateFilteredPayload(
        lastUpdated: Long,
        birthControlJson: String?,
        entriesJson: String,
        partnerId: String,
        sharedKeyBase64: String
    ): ByteArray
    fun decryptPayload(encryptedData: ByteArray, partnerId: String, sharedKeyBase64: String): String
    fun generateRandomKey(): ByteArray
    fun getCyclePredictions(history: List<com.novarytm.db.CycleEntry>): com.novarytm.tracker.CyclePredictionResult
    
    fun encryptForSync(payloadJson: String, sharedKeyBase64: String): String
    fun decryptFromSync(encryptedBase64: String, sharedKeyBase64: String): String
}
