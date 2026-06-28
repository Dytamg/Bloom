package com.novarytm.ffi

import com.novarytm.sync.PartnerPermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

actual class RustBridge {
    // VULN-02/03 SAFETY CONTRACT:
    // All JNI extern functions in lib.rs now return std::ptr::null_mut() on
    // allocation failure instead of panicking via nested .unwrap() calls.
    // All Mutex::lock() calls recover from poisoned state via .unwrap_or_else().
    // The runCatching{} wrappers below catch any resulting null → NullPointerException
    // and return safe defaults, ensuring no JVM crash propagates from Rust-side errors.
    init {
        try {
            System.loadLibrary("rust_core")
        } catch (e: UnsatisfiedLinkError) {
            println("FATAL: Could not load rust_core library: ${e.message}")
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

    actual fun getCyclePredictions(history: List<com.novarytm.db.CycleEntry>): com.novarytm.tracker.CyclePredictionResult {
        @kotlinx.serialization.Serializable
        data class SyncEntry(val date: String, val intensity: Int, val notes: String?)
        val syncHistory = history.map { SyncEntry(it.date, it.intensity ?: 0, it.notes) }
        val jsonPayload = com.novarytm.utils.AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SyncEntry.serializer()), syncHistory)
        val resultJson = runCatching { calculateNextCycleNative(jsonPayload) }.getOrDefault("{}")
        return try {
            com.novarytm.utils.AppJson.decodeFromString(com.novarytm.tracker.CyclePredictionResult.serializer(), resultJson)
        } catch (e: Exception) {
            com.novarytm.tracker.CyclePredictionResult(emptySet(), emptySet(), 1)
        }
    }

    actual fun calculatePhase(lastPeriodDate: String, avgLength: Int): String {
        return runCatching { calculate_cycle_phase(lastPeriodDate, avgLength) }.getOrDefault("Error")
    }

    actual fun predictNextPeriod(lastPeriodDate: String, avgLength: Int): String {
        return runCatching { predict_next_period(lastPeriodDate, avgLength) }.getOrDefault("")
    }

    actual fun analyzeCycleHistory(datesCsv: String): String {
        return runCatching { analyze_cycle_history(datesCsv) }.getOrDefault("28,0")
    }

    actual fun deriveKey(pin: ByteArray, salt: ByteArray): ByteArray {
        return runCatching { derive_key(pin, salt) }.getOrElse {
            android.util.Log.e("RustBridge", "Cryptographic key derivation failed", it)
            throw IllegalStateException("Cryptographic engine failed", it)
        }
    }

    actual fun generatePinHash(pin: ByteArray): ByteArray {
        return runCatching { generate_pin_hash(pin) }.getOrDefault(ByteArray(0))
    }

    actual fun verifyAndUnlock(pinHash: ByteArray, inputPin: ByteArray, salt: ByteArray): Boolean {
        return runCatching { verify_and_unlock(pinHash, inputPin, salt) }.getOrDefault(false)
    }

    actual fun unlockWithBiometric(keyBytes: ByteArray): Boolean {
        return runCatching { unlock_with_biometric(keyBytes) }.getOrDefault(false)
    }

    actual fun lockDatabase() {
        runCatching { lock_database() }
    }

    actual fun savePartnerPermissions(permissions: PartnerPermissions) {
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
        return runCatching {
            val json = get_partner_permissions_json()
            com.novarytm.utils.AppJson.decodeFromString<PartnerPermissions>(json)
        }.getOrDefault(PartnerPermissions())
    }

    actual fun generateFilteredPayload(
        lastUpdated: Long,
        birthControlJson: String?,
        entriesJson: String,
        partnerId: String,
        sharedKeyBase64: String
    ): ByteArray {
        return runCatching {
            generate_filtered_payload(lastUpdated, birthControlJson, entriesJson, partnerId, sharedKeyBase64)
        }.getOrDefault(ByteArray(0))
    }

    actual fun decryptPayload(encryptedData: ByteArray, partnerId: String, sharedKeyBase64: String): String {
        return runCatching { decrypt_payload(encryptedData, partnerId, sharedKeyBase64) }.getOrDefault("")
    }

    actual fun generateRandomKey(): ByteArray {
        return runCatching { generate_random_key() }.getOrElse {
            android.util.Log.e("RustBridge", "Cryptographic RNG failed", it)
            throw IllegalStateException("Cryptographic engine failed", it)
        }
    }

    actual fun encryptForSync(payloadJson: String, sharedKeyBase64: String): String {
        return runCatching { encrypt_for_sync(payloadJson, sharedKeyBase64) }.getOrDefault("")
    }

    actual fun decryptFromSync(encryptedBase64: String, sharedKeyBase64: String): String {
        return runCatching { decrypt_from_sync(encryptedBase64, sharedKeyBase64) }.getOrDefault("")
    }
}
