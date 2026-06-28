package com.novarytm.ffi

import com.novarytm.sync.PartnerPermissions

actual class RustBridge {
    // Note: iOS requires cinterop setup to call Rust functions directly
    // For now, this is a placeholder implementation
    
    actual fun calculatePhase(lastPeriodDate: String, avgLength: Int): String {
        return "Follicular (iOS Mock)"
    }

    actual fun predictNextPeriod(lastPeriodDate: String, avgLength: Int): String {
        return "2024-06-01"
    }

    actual fun analyzeCycleHistory(datesCsv: String): String {
        return "28,0"
    }

    actual fun deriveKey(pin: ByteArray, salt: ByteArray): ByteArray {
        return ByteArray(32) // Mock
    }

    actual fun generatePinHash(pin: ByteArray): ByteArray {
        return pin // Mock
    }

    actual fun verifyAndUnlock(pinHash: ByteArray, inputPin: ByteArray, salt: ByteArray): Boolean {
        return true // Mock
    }

    actual fun unlockWithBiometric(keyBytes: ByteArray): Boolean {
        return true // Mock
    }

    actual fun lockDatabase() {
        // Mock
    }

    actual fun savePartnerPermissions(permissions: PartnerPermissions) {
        // Mock
    }

    actual fun getPartnerPermissions(): PartnerPermissions {
        return PartnerPermissions() // Mock
    }

    actual fun generateFilteredPayload(
        lastUpdated: Long,
        birthControlJson: String?,
        entriesJson: String,
        partnerId: String,
        sharedKeyBase64: String
    ): ByteArray {
        return ByteArray(0) // Mock
    }

    actual fun decryptPayload(encryptedData: ByteArray, partnerId: String, sharedKeyBase64: String): String {
        return "MOCK_DECRYPTED" // Mock
    }

    actual fun generateRandomKey(): ByteArray {
        return ByteArray(32) // Mock
    }

    actual fun getCyclePredictions(history: List<com.novarytm.db.CycleEntry>): com.novarytm.tracker.CyclePredictionResult {
        return com.novarytm.tracker.CyclePredictionResult(emptySet(), emptySet(), 1) // Mock
    }

    actual fun encryptForSync(payloadJson: String, sharedKeyBase64: String): String {
        return "MOCK_ENCRYPTED"
    }

    actual fun decryptFromSync(encryptedBase64: String, sharedKeyBase64: String): String {
        return "MOCK_DECRYPTED"
    }
}
