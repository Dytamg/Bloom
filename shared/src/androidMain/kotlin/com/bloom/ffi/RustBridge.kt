package com.bloom.ffi

import com.bloom.sync.PartnerPermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

actual class RustBridge {
    companion object {
        var isLoaded = false
        init {
            try {
                System.loadLibrary("rust_core")
                isLoaded = true
            } catch (e: Throwable) {
                println("FATAL: Could not load rust_core library: ${e.message}")
                isLoaded = false
            }
        }
    }

    private external fun calculate_cycle_phase(lastPeriodDate: String, avgLength: Int): String
    private external fun predict_next_period(lastPeriodDate: String, avgLength: Int): String
    private external fun analyze_cycle_history(datesCsv: String): String
    private external fun derive_key(pin: ByteArray, salt: ByteArray): ByteArray
    private external fun generate_pin_hash(pin: ByteArray): ByteArray
    private external fun verify_and_unlock(pinHash: ByteArray, inputPin: ByteArray, salt: ByteArray): Boolean
    private external fun unlock_with_biometric(keyBytes: ByteArray): Boolean
    private external fun lock_database(): Unit
    
    private external fun save_partner_permissions(
        share_cycle_phase: Boolean,
        share_flow_intensity: Boolean,
        share_symptoms_moods: Boolean,
        share_birth_control: Boolean,
        share_pregnancy_tests: Boolean,
        share_sexual_activity: Boolean
    ): Unit
    private external fun get_partner_permissions_json(): String
    private external fun generate_filtered_payload(
        lastUpdated: Long,
        birthControlJson: String?,
        entries_json: String,
        partner_id: String,
        sharedKeyBase64: String
    ): ByteArray
    private external fun decrypt_payload(
        encrypted_data: ByteArray,
        partner_id: String,
        sharedKeyBase64: String
    ): String
    private external fun generate_random_key(): ByteArray
    private external fun calculateNextCycleNative(historyJson: String): String
    private external fun encrypt_for_sync(payloadJson: String, sharedKeyBase64: String): String
    private external fun decrypt_from_sync(encryptedBase64: String, sharedKeyBase64: String): String

    actual fun getCyclePredictions(history: List<com.bloom.db.CycleEntry>): com.bloom.tracker.CyclePredictionResult {
        if (!isLoaded) return com.bloom.tracker.CyclePredictionResult(emptySet(), emptySet(), 1)
        @kotlinx.serialization.Serializable
        data class SyncEntry(val date: String, val intensity: Int, val notes: String?)
        val syncHistory = history.map { SyncEntry(it.date, it.intensity ?: 0, it.notes) }
        val jsonPayload = com.bloom.utils.AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SyncEntry.serializer()), syncHistory)
        val resultJson = runCatching { calculateNextCycleNative(jsonPayload) }.getOrDefault("{}")
        return try {
            com.bloom.utils.AppJson.decodeFromString(com.bloom.tracker.CyclePredictionResult.serializer(), resultJson)
        } catch (e: Exception) {
            com.bloom.tracker.CyclePredictionResult(emptySet(), emptySet(), 1)
        }
    }

    actual fun calculatePhase(lastPeriodDate: String, avgLength: Int): String {
        if (!isLoaded) return "Follicular"
        return runCatching { calculate_cycle_phase(lastPeriodDate, avgLength) }.getOrDefault("Error")
    }

    actual fun predictNextPeriod(lastPeriodDate: String, avgLength: Int): String {
        if (!isLoaded) return "2024-04-01"
        return runCatching { predict_next_period(lastPeriodDate, avgLength) }.getOrDefault("")
    }

    actual fun analyzeCycleHistory(datesCsv: String): String {
        if (!isLoaded) return "28,0"
        return runCatching { analyze_cycle_history(datesCsv) }.getOrDefault("28,0")
    }

    actual fun deriveKey(pin: ByteArray, salt: ByteArray): ByteArray {
        if (!isLoaded) return ByteArray(32) { 1 }
        val key = runCatching { derive_key(pin, salt) }.getOrElse {
            android.util.Log.e("RustBridge", "Cryptographic key derivation failed", it)
            throw IllegalStateException("Native key derivation failed", it)
        }
        if (key.size < 32) {
            key.fill(0)
            android.util.Log.e("RustBridge", "Native key derivation returned insufficient length: ${key.size}")
            throw IllegalStateException("Native key derivation failed")
        }
        return key
    }

    fun deriveKey(pin: String, salt: ByteArray): ByteArray {
        val pinBytes = pin.encodeToByteArray()
        try {
            return deriveKey(pinBytes, salt)
        } finally {
            pinBytes.fill(0)
        }
    }

    actual fun generatePinHash(pin: ByteArray): ByteArray {
        if (!isLoaded) return ByteArray(32) { 1 }
        return runCatching { generate_pin_hash(pin) }.getOrDefault(ByteArray(0))
    }

    fun generatePinHash(pin: String): ByteArray {
        val pinBytes = pin.encodeToByteArray()
        try {
            return generatePinHash(pinBytes)
        } finally {
            pinBytes.fill(0)
        }
    }

    actual fun verifyAndUnlock(pinHash: ByteArray, inputPin: ByteArray, salt: ByteArray): Boolean {
        if (!isLoaded) return true
        return runCatching { verify_and_unlock(pinHash, inputPin, salt) }.getOrDefault(false)
    }

    fun verifyAndUnlock(pinHash: ByteArray, inputPin: String, salt: ByteArray): Boolean {
        val pinBytes = inputPin.encodeToByteArray()
        try {
            return verifyAndUnlock(pinHash, pinBytes, salt)
        } finally {
            pinBytes.fill(0)
        }
    }

    actual fun unlockWithBiometric(keyBytes: ByteArray): Boolean {
        if (!isLoaded) return true
        try {
            return runCatching { unlock_with_biometric(keyBytes) }.getOrDefault(false)
        } finally {
            keyBytes.fill(0)
        }
    }

    actual fun lockDatabase() {
        if (!isLoaded) return
        runCatching { lock_database() }
    }

    actual fun savePartnerPermissions(permissions: PartnerPermissions) {
        if (!isLoaded) return
        runCatching {
            save_partner_permissions(
                permissions.shareCyclePhase,
                permissions.shareFlowIntensity,
                permissions.shareSymptomsMoods,
                permissions.shareBirthControl,
                permissions.sharePregnancyTests,
                permissions.shareSexualActivity
            )
        }
    }

    actual fun getPartnerPermissions(): PartnerPermissions {
        if (!isLoaded) return PartnerPermissions()
        return runCatching {
            val json = get_partner_permissions_json()
            com.bloom.utils.AppJson.decodeFromString<PartnerPermissions>(json)
        }.getOrDefault(PartnerPermissions())
    }

    actual fun generateFilteredPayload(
        lastUpdated: Long,
        birthControlJson: String?,
        entriesJson: String,
        partnerId: String,
        sharedKeyBase64: String
    ): ByteArray {
        if (!isLoaded) return ByteArray(0)
        return runCatching {
            generate_filtered_payload(lastUpdated, birthControlJson, entriesJson, partnerId, sharedKeyBase64)
        }.getOrDefault(ByteArray(0))
    }

    actual fun decryptPayload(encryptedData: ByteArray, partnerId: String, sharedKeyBase64: String): String {
        if (!isLoaded) return "{}"
        return runCatching { decrypt_payload(encryptedData, partnerId, sharedKeyBase64) }.getOrDefault("")
    }

    actual fun generateRandomKey(): ByteArray {
        if (!isLoaded) return ByteArray(32) { 1 }
        return runCatching { generate_random_key() }.getOrElse {
            android.util.Log.e("RustBridge", "Cryptographic RNG failed", it)
            throw IllegalStateException("Cryptographic engine failed", it)
        }
    }

    actual fun encryptForSync(payloadJson: String, sharedKeyBase64: String): String {
        if (!isLoaded) return payloadJson
        return runCatching { encrypt_for_sync(payloadJson, sharedKeyBase64) }.getOrDefault(payloadJson)
    }

    actual fun decryptFromSync(encryptedBase64: String, sharedKeyBase64: String): String {
        if (!isLoaded) return encryptedBase64
        return runCatching { decrypt_from_sync(encryptedBase64, sharedKeyBase64) }.getOrDefault(encryptedBase64)
    }
}
