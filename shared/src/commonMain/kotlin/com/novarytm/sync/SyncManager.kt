@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.novarytm.sync

import com.novarytm.db.NovarytmDatabase
import com.novarytm.ffi.RustBridge
import com.novarytm.storage.SecureStorageManager
import com.novarytm.tracker.CycleTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.round
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.encodeToString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.datetime.LocalDate
import com.novarytm.utils.AppJson
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SyncUploadStateStore {
    fun hasPendingSyncUpload(): Boolean
    fun setPendingSyncUpload(pending: Boolean)
}

internal class SecureStorageSyncStateStore(
    private val storage: SecureStorageManager
) : SyncUploadStateStore {
    override fun hasPendingSyncUpload(): Boolean = storage.hasPendingSyncUpload()
    override fun setPendingSyncUpload(pending: Boolean) = storage.setPendingSyncUpload(pending)
}

internal class PendingSyncCoordinator(
    private val stateStore: SyncUploadStateStore,
    private val scheduler: SyncUploadScheduler?
) {
    fun markPendingAndSchedule(isOwnerMode: Boolean) {
        if (!isOwnerMode) return
        stateStore.setPendingSyncUpload(true)
        scheduler?.schedulePendingUpload()
    }

    fun clearPendingAndCancel() {
        stateStore.setPendingSyncUpload(false)
        scheduler?.cancelPendingUpload()
    }
}

@OptIn(FlowPreview::class)
class SyncManager(
    private val database: NovarytmDatabase,
    private val rustBridge: RustBridge,
    private val storage: SecureStorageManager,
    private val googleDriveManager: GoogleDriveManager,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val cycleTracker: CycleTracker? = null,
    private val backgroundSyncScheduler: SyncUploadScheduler? = null
) {
    sealed interface UploadOutcome {
        data class Success(val fileId: String) : UploadOutcome
        data object NoOp : UploadOutcome
        data object AuthRequired : UploadOutcome
        data object StorageFull : UploadOutcome
        data class RetryableFailure(val cause: Throwable?) : UploadOutcome
        data class PermanentFailure(val cause: Throwable?) : UploadOutcome
    }

    sealed interface PullOutcome {
        data object Success : PullOutcome
        data object Revoked : PullOutcome
        data object NoOp : PullOutcome
        data object AuthRequired : PullOutcome
        data class RetryableFailure(val cause: Throwable?) : PullOutcome
        data class PermanentFailure(val cause: Throwable?) : PullOutcome
    }

    private val queries = database.cycleQueries
    private val uploadMutex = Mutex()
    private val syncAttemptTimestamps = mutableListOf<Long>()
    private val hasPendingChanges = MutableStateFlow(false)
    private val pendingSyncCoordinator = PendingSyncCoordinator(
        stateStore = SecureStorageSyncStateStore(storage),
        scheduler = backgroundSyncScheduler
    )

    private val _syncStatusMessage = MutableStateFlow<String?>(null)
    val syncStatusMessage: StateFlow<String?> = _syncStatusMessage.asStateFlow()

    private val _syncWarning = MutableStateFlow<String?>(null)
    val syncWarning: StateFlow<String?> = _syncWarning.asStateFlow()

    private val _syncAuthRequired = MutableStateFlow<Boolean>(false)
    val syncAuthRequired: StateFlow<Boolean> = _syncAuthRequired.asStateFlow()

    private fun isOwnerMode(): Boolean {
        return storage.getUserId() != null && storage.getTargetSyncId().isNullOrEmpty()
    }

    private fun isValidCalendarDate(dateStr: String, regex: Regex): Boolean {
        if (!regex.matches(dateStr)) return false
        return try {
            LocalDate.parse(dateStr)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sanitizeText(value: String?, maxLen: Int): String? {
        return value?.take(maxLen)?.replace(Regex("<[^>]*>"), "")?.trim()
    }

    private fun formatBytesAsGb(bytes: Long): String {
        val tenths = round((bytes.coerceAtLeast(0L) / 1_073_741_824.0) * 10.0).toLong()
        return "${tenths / 10}.${tenths % 10}"
    }

    fun clearSyncStatus() {
        _syncStatusMessage.value = null
        _syncWarning.value = null
        _syncAuthRequired.value = false
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
        val partnerFileId = storage.getTargetSyncId()
        if (!isOwnerMode() && !partnerFileId.isNullOrEmpty() && partnerFileId != "appDataFolder") {
            backgroundSyncScheduler?.schedulePeriodicPartnerPull()
        }
    }

    private fun observeChanges() {
        val birthControlFlow = queries.getBirthControl().asFlow().mapToOneOrNull(Dispatchers.IO)
        val entriesFlow = queries.selectAllEntries().asFlow().mapToList(Dispatchers.IO)
        val ptFlow = queries.selectAllPregnancyTests().asFlow().mapToList(Dispatchers.IO)
        val remindersFlow = queries.selectAllReminders().asFlow().mapToList(Dispatchers.IO)

        combine(birthControlFlow, entriesFlow, ptFlow, remindersFlow) { _, _, _, _ ->
            Unit
        }
            .drop(1) // Skip initial DB snapshot emitted upon collection
            .onEach {
                if (isOwnerMode()) {
                    hasPendingChanges.value = true
                    pendingSyncCoordinator.markPendingAndSchedule(isOwnerMode = true)
                }
            }
            .debounce(1000.milliseconds) // Avoid rapid consecutive uploads, but keep delay low
            .onEach {
                if (isOwnerMode() && hasPendingChanges.value) {
                    prepareAndUploadPayload()
                }
            }
            .catch { e ->
                if (e is CancellationException) throw e
                _syncWarning.value = e.message ?: "Sync change observer failed."
            }
            .launchIn(scope)
    }

    /**
     * Flushes any pending local changes accumulated during the debounce window.
     */
    suspend fun flushPendingChanges(): Boolean {
        if (!isOwnerMode() || !hasPendingChanges.value) return true
        return prepareAndUploadPayload() != null
    }

    suspend fun prepareAndUploadPayload(): String? = withContext(Dispatchers.IO) {
        uploadMutex.withLock {
            if (!checkRateLimit()) {
                _syncWarning.value = "Rate limit reached. Please wait before syncing again."
                return@withContext null
            }
            prepareAndUploadPayloadLocked()
        }
    }

    suspend fun executeBackgroundUpload(): UploadOutcome = withContext(Dispatchers.IO) {
        uploadMutex.withLock {
            executeUploadLocked()
        }
    }

    private suspend fun prepareAndUploadPayloadLocked(): String? {
        return when (val outcome = executeUploadLocked()) {
            is UploadOutcome.Success -> {
                pendingSyncCoordinator.clearPendingAndCancel()
                outcome.fileId
            }
            UploadOutcome.NoOp -> null
            UploadOutcome.AuthRequired,
            UploadOutcome.StorageFull,
            is UploadOutcome.PermanentFailure -> {
                backgroundSyncScheduler?.cancelPendingUpload()
                null
            }
            is UploadOutcome.RetryableFailure -> {
                pendingSyncCoordinator.markPendingAndSchedule(isOwnerMode())
                null
            }
        }
    }

    private suspend fun executeUploadLocked(): UploadOutcome {
        // Clear pending marker before reading DB so mutations during upload trigger another cycle,
        // while a manual upload cancels any redundant pending debounce emission.
        hasPendingChanges.value = false

        return try {
            val partnerEmail = storage.getPartnerEmail()?.trim()
            val encryptionKeyBytes = storage.getSyncSecretKey()
            val hasPriorSyncState = encryptionKeyBytes != null ||
                !partnerEmail.isNullOrBlank() ||
                storage.getPrimarySyncFileId() != null ||
                storage.getLastSyncTimestamp() > 0L ||
                storage.getLastSuccessfulSyncWallclock() > 0L ||
                storage.getPartnerPermissionsJson() != null

            if (partnerEmail.isNullOrBlank()) {
                encryptionKeyBytes?.fill(0)
                if (hasPriorSyncState) {
                    _syncWarning.value = "Partner email missing after restore or unlink. Please enter your partner's email in Share Sync Code to resume syncing."
                }
                return UploadOutcome.NoOp
            }

            if (encryptionKeyBytes == null) {
                _syncWarning.value = "Sync secret key missing. Please regenerate a sync code in Share Sync Code to resume syncing."
                return UploadOutcome.NoOp
            }

            try {
                @OptIn(ExperimentalEncodingApi::class)
                val encryptionKeyBase64 = Base64.Default.encode(encryptionKeyBytes)

                val permissions = rustBridge.getPartnerPermissions()

                val bc = queries.getBirthControl().executeAsOneOrNull()
                val entries = queries.selectAllEntries().executeAsList()
                val pts = queries.selectAllPregnancyTests().executeAsList()
                val reminders = queries.selectAllReminders().executeAsList()

                val filteredEntries = entries.mapNotNull { entry ->
                    val originalIntensity = entry.intensity?.toInt() ?: 0
                    val redactedIntensity = when {
                        !permissions.shareCyclePhase -> 0
                        permissions.shareFlowIntensity -> originalIntensity
                        originalIntensity > 0 -> 1
                        else -> 0
                    }
                    val rawNotes = if (permissions.shareSymptomsMoods) entry.notes else null
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
                    val sharedSymptoms = if (permissions.shareSymptomsMoods) entry.symptoms else null
                    val sharedPainLevel = if (permissions.shareSymptomsMoods) entry.painLevel?.toInt() else null
                    val sharedSexualRelations = if (permissions.shareSexualActivity) entry.sexualRelations else null

                    val hasAnySharedContent = redactedIntensity > 0 ||
                        !safeNotes.isNullOrBlank() ||
                        !sharedSymptoms.isNullOrEmpty() ||
                        (sharedPainLevel ?: 0) > 0 ||
                        !sharedSexualRelations.isNullOrEmpty()

                    if (hasAnySharedContent) {
                        SyncEntry(
                            date = entry.date,
                            intensity = redactedIntensity,
                            notes = safeNotes,
                            sexualRelations = sharedSexualRelations,
                            painLevel = sharedPainLevel,
                            symptoms = sharedSymptoms
                        )
                    } else {
                        null
                    }
                }

                val filteredReminders = reminders.filter { rm ->
                    if (rm.type.replace(" ", "").equals("PregnancyTest", ignoreCase = true)) {
                        permissions.sharePregnancyTests
                    } else {
                        permissions.shareBirthControl
                    }
                }.map { SyncReminder(it.type, it.frequency, it.timeOfDay, it.nextDate, it.isActive ?: true) }

                val payloadObj = SyncPayload(
                    lastUpdated = Clock.System.now().toEpochMilliseconds(),
                    birthControl = if (permissions.shareBirthControl) {
                        bc?.let { SyncBirthControl(it.type, it.isActive ?: true, it.reminderTime) }
                    } else {
                        null
                    },
                    entries = filteredEntries,
                    pregnancyTests = if (permissions.sharePregnancyTests) pts.map { SyncPregnancyTest(it.date, it.result) } else emptyList(),
                    reminders = filteredReminders,
                    permissions = permissions
                )
                val fullJson = AppJson.encodeToString(payloadObj)

                val encryptedBase64 = rustBridge.encryptForSync(fullJson, encryptionKeyBase64)
                if (encryptedBase64.isEmpty()) return UploadOutcome.PermanentFailure(null)

                val preferredFileId = storage.getPrimarySyncFileId()
                val result = googleDriveManager.pushSyncBlob(
                    encryptedData = encryptedBase64.encodeToByteArray(),
                    preferredFileId = preferredFileId,
                    partnerEmail = partnerEmail,
                    onPreferredFileNotFound = { storage.savePrimarySyncFileId(null) }
                )
                if (result != null) {
                    storage.savePrimarySyncFileId(result)
                    _syncWarning.value = null
                    _syncAuthRequired.value = false
                    _syncStatusMessage.value = null
                    storage.saveLastSuccessfulSyncWallclock(Clock.System.now().toEpochMilliseconds())
                    UploadOutcome.Success(result)
                } else {
                    _syncWarning.value = "Failed to upload sync data to Google Drive."
                    UploadOutcome.RetryableFailure(null)
                }
            } finally {
                encryptionKeyBytes.fill(0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveStorageFullException) {
            val usedGB = formatBytesAsGb(e.usedBytes)
            val limitGB = formatBytesAsGb(e.limitBytes)
            val msg = "Google Drive storage full ($usedGB GB / $limitGB GB). Sync paused until space is freed."
            _syncStatusMessage.value = msg
            _syncWarning.value = msg
            UploadOutcome.StorageFull
        } catch (e: DriveAuthRequiredException) {
            _syncAuthRequired.value = true
            _syncWarning.value = e.message ?: "Google Drive authorization required."
            UploadOutcome.AuthRequired
        } catch (e: DriveSyncException) {
            _syncWarning.value = e.message ?: "Sync upload failed."
            if (e.isRetryable) UploadOutcome.RetryableFailure(e) else UploadOutcome.PermanentFailure(e)
        } catch (e: Exception) {
            val isAuth = e.message?.let {
                it.contains("auth", ignoreCase = true) ||
                    it.contains("token", ignoreCase = true) ||
                    it.contains("401") ||
                    it.contains("403")
            } == true
            if (isAuth) {
                _syncAuthRequired.value = true
                _syncWarning.value = "Google Drive authorization required."
                UploadOutcome.AuthRequired
            } else {
                _syncWarning.value = e.message ?: "Sync upload failed."
                UploadOutcome.RetryableFailure(e)
            }
        }
    }

    suspend fun forceResync(): Boolean = manualRefresh(forceResync = true)

    suspend fun executeBackgroundPull(): PullOutcome = uploadMutex.withLock {
        withContext(Dispatchers.IO) {
            val partnerFileId = storage.getTargetSyncId()
            val isOwner = isOwnerMode()
            if (isOwner || partnerFileId.isNullOrBlank() || partnerFileId == "appDataFolder") {
                return@withContext PullOutcome.NoOp
            }
            executePullLocked(partnerFileId = partnerFileId, isOwner = false, forceResync = false)
        }
    }

    suspend fun manualRefresh(forceResync: Boolean = false): Boolean = uploadMutex.withLock {
        if (!checkRateLimit()) {
            _syncWarning.value = "Rate limit reached. Please wait before syncing again."
            return@withLock false
        }
        withContext(Dispatchers.IO) {
            val partnerFileId = storage.getTargetSyncId()
            val isOwner = isOwnerMode()
            val hasLocalEntries = database.cycleQueries.selectAllEntries().executeAsList().isNotEmpty()

            if (isOwner && hasLocalEntries) {
                return@withContext prepareAndUploadPayloadLocked() != null
            }

            if (!isOwner && !partnerFileId.isNullOrEmpty() && partnerFileId != "appDataFolder") {
                backgroundSyncScheduler?.schedulePeriodicPartnerPull()
            }

            executePullLocked(partnerFileId = partnerFileId, isOwner = isOwner, forceResync = forceResync) == PullOutcome.Success
        }
    }

    private suspend fun executePullLocked(
        partnerFileId: String?,
        isOwner: Boolean,
        forceResync: Boolean
    ): PullOutcome {
        return try {
            val encryptedBase64 = if (partnerFileId != null && partnerFileId.isNotEmpty() && partnerFileId != "appDataFolder") {
                // Partner downloading shared file
                googleDriveManager.downloadPublicSyncBlob(partnerFileId)
            } else {
                // Owner fetching their own data (e.g. after restore)
                googleDriveManager.pullSyncBlob()
            }
            if (encryptedBase64.isNullOrEmpty()) {
                _syncWarning.value = "No sync data found or failed to download from Google Drive."
                return PullOutcome.RetryableFailure(null)
            }
            if (encryptedBase64 == "REVOKED") {
                if (!isOwner) {
                    wipePartnerStateSilently()
                    return PullOutcome.Revoked
                }
                return PullOutcome.Success
            }

            // Clear prior transient warning so importEncryptedSyncPayloadInternal can set a specific one if it fails
            val previousWarning = _syncWarning.value
            _syncWarning.value = null
            val success = importEncryptedSyncPayloadInternal(encryptedBase64, forceResync = forceResync)
            if (success) {
                _syncWarning.value = null
                _syncAuthRequired.value = false
                _syncStatusMessage.value = null
                storage.saveLastSuccessfulSyncWallclock(Clock.System.now().toEpochMilliseconds())
                PullOutcome.Success
            } else {
                if (_syncWarning.value == null) {
                    _syncWarning.value = previousWarning?.takeIf { it.isNotBlank() }
                        ?: "Failed to decrypt or validate sync payload."
                }
                PullOutcome.PermanentFailure(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveAuthRequiredException) {
            _syncAuthRequired.value = true
            _syncWarning.value = e.message ?: "Google Drive authorization required."
            PullOutcome.AuthRequired
        } catch (e: DriveSyncException) {
            _syncWarning.value = e.message ?: "Sync refresh failed."
            if (e.isRetryable) PullOutcome.RetryableFailure(e) else PullOutcome.PermanentFailure(e)
        } catch (e: Exception) {
            val isAuth = e.message?.let {
                it.contains("auth", ignoreCase = true) ||
                    it.contains("token", ignoreCase = true) ||
                    it.contains("401") ||
                    it.contains("403")
            } == true
            if (isAuth) {
                _syncAuthRequired.value = true
                _syncWarning.value = "Google Drive authorization required."
                PullOutcome.AuthRequired
            } else {
                _syncWarning.value = e.message ?: "Sync refresh failed."
                PullOutcome.RetryableFailure(e)
            }
        }
    }

    private fun wipePartnerStateSilently() {
        database.transaction {
            queries.deleteAllEntries()
            queries.deleteAllPregnancyTests()
            queries.deleteAllReminders()
            queries.deleteBirthControl()
        }
        storage.saveTargetSyncId("")
        storage.saveSyncSecretKey(ByteArray(0))
        storage.savePartnerPermissionsJson("")
        storage.saveLastSyncTimestamp(0L)
        rustBridge.savePartnerPermissions(PartnerPermissions())
        backgroundSyncScheduler?.cancelPartnerPull()
        cycleTracker?.refreshCycleState()
        _syncWarning.value = null
        _syncAuthRequired.value = false
        _syncStatusMessage.value = null
    }

    suspend fun importEncryptedSyncPayload(
        encryptedBase64: String,
        forceResync: Boolean = false
    ): Boolean = uploadMutex.withLock {
        withContext(Dispatchers.IO) {
            val success = importEncryptedSyncPayloadInternal(encryptedBase64, forceResync = forceResync)
            if (success) {
                _syncWarning.value = null
                _syncAuthRequired.value = false
                _syncStatusMessage.value = null
                storage.saveLastSuccessfulSyncWallclock(Clock.System.now().toEpochMilliseconds())
            }
            success
        }
    }

    private fun importEncryptedSyncPayloadInternal(
        encryptedBase64: String,
        forceResync: Boolean = false
    ): Boolean {
        if (encryptedBase64.length > 2 * 1024 * 1024) {
            _syncWarning.value = "Sync payload exceeds maximum allowed size."
            return false
        }
        // Require the specific secret key for decryption
        val decryptionKeyBytes = storage.getSyncSecretKey()
        if (decryptionKeyBytes == null) {
            _syncWarning.value = "Sync secret key missing on this device. Please re-link partner or regenerate a sync code."
            return false
        }
        try {
            @OptIn(ExperimentalEncodingApi::class)
            val decryptionKeyBase64 = Base64.Default.encode(decryptionKeyBytes)

            val rawJson = rustBridge.decryptFromSync(encryptedBase64, decryptionKeyBase64)
            if (rawJson.isEmpty()) {
                _syncWarning.value = "Failed to decrypt sync payload (key mismatch or revoked access)."
                return false
            }

            return try {
                val payload = try {
                    AppJson.decodeFromString<SyncPayload>(rawJson)
                } catch (_: Throwable) {
                    _syncWarning.value = "Received malformed sync payload."
                    return false
                }
                if (payload.lastUpdated <= 0L) {
                    _syncWarning.value = "Sync payload has an invalid timestamp."
                    return false
                }
                val lastTs = storage.getLastSyncTimestamp()
                if (!forceResync && lastTs > 0L && payload.lastUpdated < lastTs - 300_000L) {
                    _syncWarning.value = "Sync skipped: remote timestamp is older than last sync (possible clock skew). Use force resync if clocks changed."
                    return false
                }
                val now = Clock.System.now().toEpochMilliseconds()
                if (!forceResync && payload.lastUpdated > now + 86_400_000L) {
                    _syncWarning.value = "Sync skipped: remote timestamp is more than 24 hours in the future (check device clock)."
                    return false
                }
                if (payload.entries.size > 5000 || payload.pregnancyTests.size > 1000 || payload.reminders.size > 100) {
                    _syncWarning.value = "Sync payload exceeds item count limits."
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
                        val cleanNotes = sanitizeText(entry.notes, 500)
                        val cleanSymptoms = sanitizeText(entry.symptoms, 200)
                        val cleanSexual = sanitizeText(entry.sexualRelations, 50)
                        ValidatedEntry(entry.date, validIntensity, cleanNotes, cleanSexual, validPainLevel, cleanSymptoms)
                    } else null
                }

                val validatedTests = payload.pregnancyTests.mapNotNull { pt ->
                    val cleanResult = sanitizeText(pt.result, 100)
                    if (isValidCalendarDate(pt.date, dateRegex) && !cleanResult.isNullOrEmpty()) {
                        ValidatedPregnancyTest(pt.date, cleanResult)
                    } else null
                }

                val validatedReminders = payload.reminders.mapNotNull { rm ->
                    val cleanType = sanitizeText(rm.type, 50)
                    if (cleanType.isNullOrEmpty()) return@mapNotNull null
                    val cleanFreq = sanitizeText(rm.frequency, 50) ?: ""
                    val cleanTime = sanitizeText(rm.timeOfDay, 50)
                    val validNext = if (rm.nextDate != null && isValidCalendarDate(rm.nextDate, dateRegex)) rm.nextDate else null
                    ValidatedReminder(cleanType, cleanFreq, cleanTime, validNext, rm.isActive)
                }

                // Persist partner permissions before committing DB transaction so reactive observers
                // never read stale permissions on transaction emission.
                payload.permissions?.let {
                    storage.savePartnerPermissionsJson(AppJson.encodeToString(it))
                    rustBridge.savePartnerPermissions(it)
                }

                database.transaction {
                    queries.deleteAllEntries()
                    queries.deleteAllPregnancyTests()
                    queries.deleteAllReminders()

                    val bcType = sanitizeText(payload.birthControl?.type, 50)
                    if (payload.birthControl != null && !bcType.isNullOrEmpty()) {
                        val bcReminder = sanitizeText(payload.birthControl.reminderTime, 50)
                        queries.setBirthControlWithReminder(bcType, bcReminder, payload.birthControl.isActive)
                    } else {
                        queries.deleteBirthControl()
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

                cycleTracker?.refreshCycleState()

                storage.saveLastSyncTimestamp(payload.lastUpdated)
                true
            } catch (_: Exception) {
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
        uploadMutex.withLock {
            try {
                // 1. Delete the existing file on Google Drive
                val deleted = googleDriveManager.deleteSyncBlob()
                if (!deleted) {
                    _syncWarning.value = "Failed to delete existing sync file on Google Drive."
                    return@withLock false
                }

                // 2. Clear cached Drive file ID and pending outbound flag
                storage.savePrimarySyncFileId(null)
                hasPendingChanges.value = false
                pendingSyncCoordinator.clearPendingAndCancel()

                // 3. Delete the local encryption key and generate a new one
                val newKey = rustBridge.generateRandomKey()
                try {
                    storage.saveSyncSecretKey(newKey)
                } finally {
                    newKey.fill(0)
                }

                _syncWarning.value = null
                _syncAuthRequired.value = false
                _syncStatusMessage.value = null
                return@withLock true
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveAuthRequiredException) {
                _syncAuthRequired.value = true
                _syncWarning.value = e.message ?: "Google Drive authorization required."
                return@withLock false
            } catch (e: Exception) {
                _syncWarning.value = e.message ?: "Failed to revoke sync access."
                return@withLock false
            }
        }
    }
}
