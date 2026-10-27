@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.novarytm.sync

import com.novarytm.db.NovarytmDatabase
import com.novarytm.ffi.RustBridge
import com.novarytm.storage.SecureStorageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.encodeToString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.datetime.LocalDate
import com.novarytm.utils.AppJson
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.ktor.util.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(FlowPreview::class)
class SyncManager(
    private val database: NovarytmDatabase,
    private val rustBridge: RustBridge,
    private val storage: SecureStorageManager,
    private val googleDriveManager: GoogleDriveManager,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val queries = database.cycleQueries
    private val uploadMutex = Mutex()
    private val syncAttemptTimestamps = mutableListOf<Long>()
    private val _syncStatusMessage = MutableStateFlow<String?>(null)
    val syncStatusMessage: StateFlow<String?> = _syncStatusMessage.asStateFlow()

    private fun isValidCalendarDate(dateStr: String, regex: Regex): Boolean {
        if (!regex.matches(dateStr)) return false
        return try {
            LocalDate.parse(dateStr)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clearSyncStatus() {
        _syncStatusMessage.value = null
    }

    private fun checkRateLimit(): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        syncAttemptTimestamps.removeAll { now - it > 60_000 }
        if (syncAttemptTimestamps.size >= 15) {
            return false
        }
        syncAttemptTimestamps.add(now)
        return true
    }

    // Reactive Observer for Outbound Sync
    init {
        observeChanges()
    }

    private fun observeChanges() {
        val birthControlFlow = queries.getBirthControl().asFlow().mapToOneOrNull(Dispatchers.IO)
        val entriesFlow = queries.selectAllEntries().asFlow().mapToList(Dispatchers.IO)
        val ptFlow = queries.selectAllPregnancyTests().asFlow().mapToList(Dispatchers.IO)
        val remindersFlow = queries.selectAllReminders().asFlow().mapToList(Dispatchers.IO)

        combine(birthControlFlow, entriesFlow, ptFlow, remindersFlow) { bc, entries, pt, reminders ->
            true // We just need the trigger
        }.debounce(1000.milliseconds) // Avoid rapid consecutive uploads, but keep delay low
        .onEach { 
            // Only owners upload their data
            if (storage.getUserId() != null) {
                prepareAndUploadPayload()
            }
        }.launchIn(scope)
    }

    suspend fun prepareAndUploadPayload(): String? = withContext(Dispatchers.IO) {
        uploadMutex.withLock {
            if (!checkRateLimit()) return@withContext null
            try {
                val partnerEmail = storage.getPartnerEmail()
                if (partnerEmail.isNullOrBlank()) {
                    return@withContext null
                }
                
                // Use random SecretKey if it exists, otherwise we cannot securely upload
                // VULN-07 FIX: Gracefully skip upload if no sync key exists (normal first-run state)
                val encryptionKeyBytes = storage.getSyncSecretKey() ?: return@withContext null
            try {
            @OptIn(ExperimentalEncodingApi::class)
            val encryptionKeyBase64 = Base64.Default.encode(encryptionKeyBytes)

            val permissions = rustBridge.getPartnerPermissions()
            
            val bc = queries.getBirthControl().executeAsOneOrNull()
            val entries = queries.selectAllEntries().executeAsList()
            val pts = queries.selectAllPregnancyTests().executeAsList()
            val reminders = queries.selectAllReminders().executeAsList()
            
            val filteredEntries = if (!permissions.shareCyclePhase) {
                emptyList()
            } else {
                entries.map { 
                    val originalIntensity = it.intensity?.toInt() ?: 0
                    val rawNotes = if (permissions.shareSymptomsMoods) it.notes else null
                    val safeNotes = if (rawNotes != null && !permissions.sharePregnancyTests) {
                        val lower = rawNotes.lowercase()
                        if (lower.contains("pregnan") || lower.contains("hcg") || lower.contains("positive") || lower.contains("negative")) {
                            null
                        } else {
                            rawNotes
                        }
                    } else {
                        rawNotes
                    }
                    SyncEntry(
                        date = it.date, 
                        intensity = when {
                            permissions.shareFlowIntensity -> originalIntensity
                            permissions.shareCyclePhase && originalIntensity > 0 -> 1
                            else -> 0
                        }, 
                        notes = safeNotes,
                        sexualRelations = if (permissions.shareSexualActivity) it.sexualRelations else null,
                        painLevel = if (permissions.shareSymptomsMoods) it.painLevel?.toInt() else null,
                        symptoms = if (permissions.shareSymptomsMoods) it.symptoms else null
                    ) 
                }
            }

            val filteredReminders = reminders.filter { rm ->
                if (rm.type.equals("Pregnancy Test", ignoreCase = true)) {
                    permissions.sharePregnancyTests
                } else {
                    permissions.shareBirthControl
                }
            }.map { SyncReminder(it.type, it.frequency, it.timeOfDay, it.nextDate, it.isActive ?: true) }

            val payloadObj = SyncPayload(
                lastUpdated = Clock.System.now().toEpochMilliseconds(),
                birthControl = if (permissions.shareBirthControl) bc?.let { SyncBirthControl(it.type, it.isActive ?: true) } else null,
                entries = filteredEntries,
                pregnancyTests = if (permissions.sharePregnancyTests) pts.map { SyncPregnancyTest(it.date, it.result) } else emptyList(),
                reminders = filteredReminders,
                permissions = permissions
            )
            val fullJson = AppJson.encodeToString(payloadObj)

            val encryptedBase64 = rustBridge.encryptForSync(fullJson, encryptionKeyBase64)
            if (encryptedBase64.isEmpty()) return@withContext null

            val result = googleDriveManager.pushSyncBlob(encryptedBase64, partnerEmail)
            if (result != null) _syncStatusMessage.value = null // Clear any previous storage warning
            return@withContext result
            } finally {
                encryptionKeyBytes.fill(0)
            }
        } catch (e: DriveStorageFullException) {
            val usedGB = String.format("%.1f", e.usedBytes / 1_073_741_824.0)
            val limitGB = String.format("%.1f", e.limitBytes / 1_073_741_824.0)
            _syncStatusMessage.value = "Google Drive storage full ($usedGB GB / $limitGB GB). Sync paused until space is freed."
            return@withContext null
        } catch (e: Exception) {
            return@withContext null
        }
        }
    }

    suspend fun manualRefresh(): Boolean = uploadMutex.withLock {
        if (!checkRateLimit()) return@withLock false
        withContext(Dispatchers.IO) {
            val partnerFileId = storage.getTargetSyncId()
            val isOwner = storage.getUserId() != null && partnerFileId.isNullOrEmpty()
            val hasLocalEntries = database.cycleQueries.selectAllEntries().executeAsList().isNotEmpty()

            if (isOwner && hasLocalEntries) {
                return@withContext prepareAndUploadPayload() != null
            }
            
            val encryptedBase64 = if (partnerFileId != null && partnerFileId.isNotEmpty() && partnerFileId != "appDataFolder") {
                // Partner downloading public link
                googleDriveManager.downloadPublicSyncBlob(partnerFileId)
            } else {
                // Owner fetching their own data
                googleDriveManager.pullSyncBlob()
            }
            if (encryptedBase64.isNullOrEmpty()) {
                return@withContext true
            }
            if (encryptedBase64 == "REVOKED") {
                if (!isOwner) {
                    database.transaction {
                        queries.deleteAllEntries()
                        queries.deleteAllPregnancyTests()
                        queries.deleteAllReminders()
                        queries.deleteBirthControl()
                    }
                    return@withContext false
                }
                return@withContext true
            }
            importEncryptedSyncPayloadInternal(encryptedBase64)
        }
    }

    suspend fun importEncryptedSyncPayload(encryptedBase64: String): Boolean = uploadMutex.withLock {
        withContext(Dispatchers.IO) {
            importEncryptedSyncPayloadInternal(encryptedBase64)
        }
    }

    private fun importEncryptedSyncPayloadInternal(encryptedBase64: String): Boolean {
        if (encryptedBase64.length > 2 * 1024 * 1024) {
            return false
        }
        // Require the specific secret key for decryption
        val decryptionKeyBytes = storage.getSyncSecretKey() ?: return false
        try {
        @OptIn(ExperimentalEncodingApi::class)
        val decryptionKeyBase64 = Base64.Default.encode(decryptionKeyBytes)
        
        val rawJson = rustBridge.decryptFromSync(encryptedBase64, decryptionKeyBase64)
        
        if (rawJson.isEmpty()) {
             return false
        }

        return try {
            val payload = try {
                AppJson.decodeFromString<SyncPayload>(rawJson)
            } catch (t: Throwable) {
                return false
            }
            val lastTs = storage.getLastSyncTimestamp()
            if (payload.lastUpdated <= 0L || (lastTs > 0L && payload.lastUpdated < lastTs)) {
                return false
            }
            val now = Clock.System.now().toEpochMilliseconds()
            if (payload.lastUpdated > now + 86_400_000L) {
                return false
            }
            if (payload.entries.size > 5000 || payload.pregnancyTests.size > 1000 || payload.reminders.size > 100) {
                return false
            }
            val dateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")
            
            data class ValidatedEntry(
                val date: String,
                val intensity: Int,
                val notes: String?,
                val sexualRelations: String?,
                val painLevel: Int?,
                val symptoms: String?
            )
            data class ValidatedPregnancyTest(
                val date: String,
                val result: String
            )
            data class ValidatedReminder(
                val type: String,
                val frequency: String,
                val timeOfDay: String?,
                val nextDate: String?,
                val isActive: Boolean
            )

            val validatedEntries = payload.entries.mapNotNull { entry ->
                if (isValidCalendarDate(entry.date, dateRegex)) {
                    val validIntensity = entry.intensity.coerceIn(0, 5)
                    val validPainLevel = entry.painLevel?.coerceIn(0, 10)
                    val cleanNotes = entry.notes?.take(500)?.replace(Regex("<[^>]*>"), "")?.trim()
                    val cleanSymptoms = entry.symptoms?.take(200)?.replace(Regex("<[^>]*>"), "")?.trim()
                    ValidatedEntry(entry.date, validIntensity, cleanNotes, entry.sexualRelations?.take(50), validPainLevel, cleanSymptoms)
                } else null
            }
            if (validatedEntries.size != payload.entries.size) {
                return false
            }

            val validatedTests = payload.pregnancyTests.mapNotNull { pt ->
                if (isValidCalendarDate(pt.date, dateRegex)) {
                    ValidatedPregnancyTest(pt.date, pt.result.take(100))
                } else null
            }
            if (validatedTests.size != payload.pregnancyTests.size) {
                return false
            }

            val validatedReminders = payload.reminders.map { rm ->
                val validNext = if (rm.nextDate != null && isValidCalendarDate(rm.nextDate, dateRegex)) rm.nextDate else null
                ValidatedReminder(rm.type.take(50), rm.frequency.take(50), rm.timeOfDay?.take(50), validNext, rm.isActive)
            }
            
            database.transaction {

                queries.deleteAllEntries()
                queries.deleteAllPregnancyTests()
                queries.deleteAllReminders()
                
                payload.birthControl?.let {
                    queries.setBirthControl(it.type.take(50))
                }
                
                validatedEntries.forEach { entry ->
                    queries.insertEntry(entry.date, entry.intensity, entry.notes, entry.sexualRelations, entry.painLevel, entry.symptoms)
                }
                
                validatedTests.forEach { pt ->
                    queries.insertPregnancyTest(pt.date, pt.result)
                }
                
                validatedReminders.forEach { rm ->
                    queries.insertReminder(rm.type, rm.frequency, rm.timeOfDay, rm.nextDate, rm.isActive)
                }
            }
            
            storage.saveLastSyncTimestamp(payload.lastUpdated)
            
            payload.permissions?.let {
                storage.savePartnerPermissionsJson(AppJson.encodeToString(it))
                rustBridge.savePartnerPermissions(it)
            }
            true
        } catch (e: Exception) {
            false
        }
        } finally {
            decryptionKeyBytes.fill(0)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun generateEphemeralPayload(fileId: String): ByteArray {
        val timestamp = Clock.System.now().toEpochMilliseconds()
        val encryptionKeyBytes = storage.getSyncSecretKey() ?: return ByteArray(0)
        val ephemeralTransportKeyBytes = rustBridge.generateRandomKey()
        try {
            val encryptionKeyBase64 = Base64.Default.encode(encryptionKeyBytes)
            val dynamicPayload = "$encryptionKeyBase64|$timestamp|$fileId"
            
            // C-1: Ephemeral transport key instead of hardcoded PAIRING_TRANSPORT_KEY
            val ephemeralTransportKeyBase64 = Base64.Default.encode(ephemeralTransportKeyBytes)
            val encryptedBase64 = rustBridge.encryptForSync(dynamicPayload, ephemeralTransportKeyBase64)
            return "$ephemeralTransportKeyBase64:$encryptedBase64".encodeToByteArray()
        } finally {
            encryptionKeyBytes.fill(0)
            ephemeralTransportKeyBytes.fill(0)
        }
    }

    suspend fun revokeAndRegenerate(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Delete the existing file on Google Drive
            googleDriveManager.deleteSyncBlob()
            
            // 2. Delete the local encryption key and generate a new one
            val newKey = rustBridge.generateRandomKey()
            try {
                storage.saveSyncSecretKey(newKey)
            } finally {
                newKey.fill(0)
            }
            
            return@withContext true
        } catch (e: Exception) {

            return@withContext false
        }
    }
}
