@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.bloom.sync

import com.bloom.db.BloomDatabase
import com.bloom.ffi.RustBridge
import com.bloom.storage.SecureStorageManager
import com.bloom.tracker.CycleTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.round
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.encodeToString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.datetime.LocalDate
import com.bloom.utils.AppJson
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface SyncUploadStateStore {
    fun hasPendingSyncUpload(): Boolean
    fun setPendingSyncUpload(pending: Boolean)
}

interface SyncKeyValueStore : SyncUploadStateStore, DrivePermissionCache {
    fun getUserId(): String?
    fun getTargetSyncId(): String?
    fun saveTargetSyncId(id: String)
    fun getPartnerEmail(): String?
    fun getSyncSecretKey(): ByteArray?
    fun saveSyncSecretKey(key: ByteArray)
    override fun getPrimarySyncFileId(): String?
    fun savePrimarySyncFileId(fileId: String?)
    fun getLastSyncTimestamp(): Long
    fun saveLastSyncTimestamp(timestampMs: Long)
    fun saveLastSuccessfulSyncWallclock(timestampMs: Long)
    fun getLastSuccessfulSyncWallclock(): Long = 0L
    fun savePartnerPermissionsJson(json: String)

    fun getSyncProviderMode(): SyncProviderMode = SyncProviderMode.DRIVE_API
    fun saveSyncProviderMode(mode: SyncProviderMode) {}
    fun getSyncRole(): SyncRole = SyncRole.NONE
    fun saveSyncRole(role: SyncRole) {}
    fun getSafFileUri(): String? = null
    fun saveSafFileUri(uri: String?) {}
    override fun getGrantedPartnerEmail(): String? = null
    override fun saveGrantedPartnerEmail(email: String?) {}
    override fun getPartnerPermissionId(): String? = null
    override fun savePartnerPermissionId(permissionId: String?) {}
    fun getLastUploadedContentHash(): String? = null
    fun saveLastUploadedContentHash(hash: String?) {}
    fun getLastImportedVersion(): Long = 0L
    fun saveLastImportedVersion(version: Long) {}
}

internal class SecureStorageSyncKeyValueStore(
    private val storage: SecureStorageManager
) : SyncKeyValueStore {
    override fun hasPendingSyncUpload(): Boolean = storage.hasPendingSyncUpload()
    override fun setPendingSyncUpload(pending: Boolean) = storage.setPendingSyncUpload(pending)
    override fun getUserId(): String? = storage.getUserId()
    override fun getTargetSyncId(): String? = storage.getTargetSyncId()
    override fun saveTargetSyncId(id: String) = storage.saveTargetSyncId(id)
    override fun getPartnerEmail(): String? = storage.getPartnerEmail()
    override fun getSyncSecretKey(): ByteArray? = storage.getSyncSecretKey()
    override fun saveSyncSecretKey(key: ByteArray) = storage.saveSyncSecretKey(key)
    override fun getPrimarySyncFileId(): String? = storage.getPrimarySyncFileId()
    override fun savePrimarySyncFileId(fileId: String?) = storage.savePrimarySyncFileId(fileId)
    override fun getLastSyncTimestamp(): Long = storage.getLastSyncTimestamp()
    override fun saveLastSyncTimestamp(timestampMs: Long) = storage.saveLastSyncTimestamp(timestampMs)
    override fun saveLastSuccessfulSyncWallclock(timestampMs: Long) = storage.saveLastSuccessfulSyncWallclock(timestampMs)
    override fun getLastSuccessfulSyncWallclock(): Long = storage.getLastSuccessfulSyncWallclock()
    override fun savePartnerPermissionsJson(json: String) = storage.savePartnerPermissionsJson(json)
    override fun getSyncProviderMode(): SyncProviderMode = storage.getSyncProviderMode()
    override fun saveSyncProviderMode(mode: SyncProviderMode) = storage.saveSyncProviderMode(mode)
    override fun getSyncRole(): SyncRole = storage.getSyncRole()
    override fun saveSyncRole(role: SyncRole) = storage.saveSyncRole(role)
    override fun getSafFileUri(): String? = storage.getSafFileUri()
    override fun saveSafFileUri(uri: String?) = storage.saveSafFileUri(uri)
    override fun getGrantedPartnerEmail(): String? = storage.getGrantedPartnerEmail()
    override fun saveGrantedPartnerEmail(email: String?) = storage.saveGrantedPartnerEmail(email)
    override fun getPartnerPermissionId(): String? = storage.getPartnerPermissionId()
    override fun savePartnerPermissionId(permissionId: String?) = storage.savePartnerPermissionId(permissionId)
    override fun getLastUploadedContentHash(): String? = storage.getLastUploadedContentHash()
    override fun saveLastUploadedContentHash(hash: String?) = storage.saveLastUploadedContentHash(hash)
    override fun getLastImportedVersion(): Long = storage.getLastImportedVersion()
    override fun saveLastImportedVersion(version: Long) = storage.saveLastImportedVersion(version)
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

interface SyncCryptoBridge {
    fun getPartnerPermissions(): PartnerPermissions
    fun savePartnerPermissions(permissions: PartnerPermissions)
    fun generateRandomKey(): ByteArray
    fun encryptForSync(payloadJson: String, sharedKeyBase64: String): String
    fun decryptFromSync(encryptedBase64: String, sharedKeyBase64: String): String
}

private class RustSyncCryptoBridge(
    private val bridge: RustBridge
) : SyncCryptoBridge {
    override fun getPartnerPermissions(): PartnerPermissions = bridge.getPartnerPermissions()
    override fun savePartnerPermissions(permissions: PartnerPermissions) = bridge.savePartnerPermissions(permissions)
    override fun generateRandomKey(): ByteArray = bridge.generateRandomKey()
    override fun encryptForSync(payloadJson: String, sharedKeyBase64: String): String =
        bridge.encryptForSync(payloadJson, sharedKeyBase64)
    override fun decryptFromSync(encryptedBase64: String, sharedKeyBase64: String): String =
        bridge.decryptFromSync(encryptedBase64, sharedKeyBase64)
}

class StaleSyncPayloadException(message: String) : Exception(message)

@OptIn(FlowPreview::class)
class SyncManager(
    private val database: BloomDatabase,
    private val rustBridge: SyncCryptoBridge,
    private val storage: SyncKeyValueStore,
    private val googleDriveManager: DriveBlobClient,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val cycleTracker: CycleTracker? = null,
    private val backgroundSyncScheduler: SyncUploadScheduler? = null,
    private val changeDebounceMs: Long = 1_000L
) {
    constructor(
        database: BloomDatabase,
        rustBridge: RustBridge,
        storage: SecureStorageManager,
        googleDriveManager: DriveBlobClient,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        cycleTracker: CycleTracker? = null,
        backgroundSyncScheduler: SyncUploadScheduler? = null,
        changeDebounceMs: Long = 1_000L
    ) : this(
        database = database,
        rustBridge = RustSyncCryptoBridge(rustBridge),
        storage = SecureStorageSyncKeyValueStore(storage),
        googleDriveManager = googleDriveManager,
        scope = scope,
        cycleTracker = cycleTracker,
        backgroundSyncScheduler = backgroundSyncScheduler,
        changeDebounceMs = changeDebounceMs
    )
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
    private var lastOutgoingPayloadVersion: Long = 0L
    private val pendingSyncCoordinator = PendingSyncCoordinator(
        stateStore = storage,
        scheduler = backgroundSyncScheduler
    )

    private val _syncStatusMessage = MutableStateFlow<String?>(null)
    val syncStatusMessage: StateFlow<String?> = _syncStatusMessage.asStateFlow()

    private val _syncWarning = MutableStateFlow<String?>(null)
    val syncWarning: StateFlow<String?> = _syncWarning.asStateFlow()

    private val _syncAuthRequired = MutableStateFlow<Boolean>(false)
    val syncAuthRequired: StateFlow<Boolean> = _syncAuthRequired.asStateFlow()

    private val _lastSuccessfulSyncWallclock = MutableStateFlow(storage.getLastSuccessfulSyncWallclock())
    val lastSuccessfulSyncWallclock: StateFlow<Long> = _lastSuccessfulSyncWallclock.asStateFlow()

    private val _safAccessLost = MutableStateFlow(false)
    val safAccessLost: StateFlow<Boolean> = _safAccessLost.asStateFlow()

    private fun recordSuccessfulSyncWallclock(timestampMs: Long = Clock.System.now().toEpochMilliseconds()) {
        storage.saveLastSuccessfulSyncWallclock(timestampMs)
        _lastSuccessfulSyncWallclock.value = timestampMs
    }

    private fun isOwnerMode(): Boolean {
        return when (storage.getSyncRole()) {
            SyncRole.OWNER -> true
            SyncRole.PARTNER -> false
            SyncRole.NONE -> storage.getUserId() != null && storage.getTargetSyncId().isNullOrEmpty()
        }
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
        _safAccessLost.value = false
    }

    internal fun checkRateLimit(now: Long = Clock.System.now().toEpochMilliseconds()): Boolean {
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
            .debounce(changeDebounceMs.milliseconds) // Avoid rapid consecutive uploads, but keep delay low
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
            if (!isOwnerMode()) {
                hasPendingChanges.value = false
                pendingSyncCoordinator.clearPendingAndCancel()
                return@withContext null
            }
            if (!checkRateLimit()) {
                _syncWarning.value = "Rate limit reached. Please wait before syncing again."
                return@withContext null
            }
            prepareAndUploadPayloadLocked()
        }
    }

    suspend fun executeBackgroundUpload(): UploadOutcome = withContext(Dispatchers.IO) {
        uploadMutex.withLock {
            val outcome = executeUploadLocked()
            if (outcome is UploadOutcome.Success) {
                storage.setPendingSyncUpload(false)
            }
            outcome
        }
    }

    private fun ensureSafSecretKeyIfNeeded() {
        if (storage.getSyncProviderMode() != SyncProviderMode.SAF_FILE) return
        if (storage.getSafFileUri().isNullOrBlank()) return
        val existingKey = storage.getSyncSecretKey()
        if (existingKey != null && existingKey.isNotEmpty()) {
            existingKey.fill(0)
            return
        }
        val generated = rustBridge.generateRandomKey()
        try {
            if (generated.isNotEmpty()) {
                storage.saveSyncSecretKey(generated)
            }
        } finally {
            generated.fill(0)
        }
    }

    private suspend fun prepareAndUploadPayloadLocked(): String? {
        ensureSafSecretKeyIfNeeded()
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
        if (!isOwnerMode()) {
            hasPendingChanges.value = false
            pendingSyncCoordinator.clearPendingAndCancel()
            return UploadOutcome.NoOp
        }
        hasPendingChanges.value = false

        val precheck = validateUploadPreconditions()
        if (precheck != null) return precheck

        val mode = storage.getSyncProviderMode()
        val partnerEmail = if (mode == SyncProviderMode.DRIVE_API) {
            storage.getPartnerEmail()?.trim() ?: return UploadOutcome.NoOp
        } else {
            null
        }
        val encryptionKeyBytes = storage.getSyncSecretKey() ?: return UploadOutcome.NoOp

        return try {
            executeUploadWithKey(partnerEmail, encryptionKeyBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mapUploadExceptionToOutcome(e)
        }
    }

    private fun validateUploadPreconditions(): UploadOutcome? {
        if (storage.getSyncProviderMode() == SyncProviderMode.SAF_FILE) {
            return validateSafUploadPreconditions()
        }
        val partnerEmail = storage.getPartnerEmail()?.trim()
        val encryptionKeyBytes = storage.getSyncSecretKey()
        val hasPriorSync = !storage.getPrimarySyncFileId().isNullOrBlank() || storage.getLastSyncTimestamp() > 0L

        if (partnerEmail.isNullOrBlank()) {
            encryptionKeyBytes?.fill(0)
            handleMissingPartnerEmail(hasPriorSync)
            return UploadOutcome.NoOp
        }
        if (encryptionKeyBytes == null || encryptionKeyBytes.isEmpty()) {
            encryptionKeyBytes?.fill(0)
            handleMissingKey(hasPriorSync)
            return UploadOutcome.NoOp
        }
        encryptionKeyBytes.fill(0)
        return null
    }

    private fun validateSafUploadPreconditions(): UploadOutcome? {
        val hasPriorSync = storage.getLastSyncTimestamp() > 0L || !storage.getLastUploadedContentHash().isNullOrBlank()
        if (storage.getSafFileUri().isNullOrBlank()) {
            handleMissingSafUri(hasPriorSync)
            return UploadOutcome.NoOp
        }
        val encryptionKeyBytes = storage.getSyncSecretKey()
        if (encryptionKeyBytes == null || encryptionKeyBytes.isEmpty()) {
            encryptionKeyBytes?.fill(0)
            handleMissingKey(hasPriorSync)
            return UploadOutcome.NoOp
        }
        encryptionKeyBytes.fill(0)
        return null
    }

    private fun handleMissingSafUri(hasPriorSync: Boolean) {
        if (hasPriorSync) {
            _safAccessLost.value = true
            _syncWarning.value = "Sync file access lost. Please reconnect your sync file."
        } else {
            pendingSyncCoordinator.clearPendingAndCancel()
        }
    }

    private fun handleMissingPartnerEmail(hasPriorSync: Boolean) {
        if (hasPriorSync) {
            _syncWarning.value = "Partner email missing after restore or unlink. Please enter your partner's email in Share Sync Code to resume syncing."
        } else {
            pendingSyncCoordinator.clearPendingAndCancel()
        }
    }

    private fun handleMissingKey(hasPriorSync: Boolean) {
        if (hasPriorSync) {
            _syncWarning.value = "Sync secret key missing. Please regenerate a sync code in Share Sync Code to resume syncing."
        } else {
            pendingSyncCoordinator.clearPendingAndCancel()
        }
    }

    private fun nextPayloadVersion(nowMs: Long): Long {
        val next = maxOf(nowMs, storage.getLastSyncTimestamp() + 1L, lastOutgoingPayloadVersion + 1L)
        lastOutgoingPayloadVersion = next
        return next
    }

    private fun computeCanonicalHash(payload: SyncPayload): String {
        val canonicalJson = AppJson.encodeToString(payload.copy(lastUpdated = 0L, payloadVersion = 0L))
        return sha256Hex(canonicalJson.encodeToByteArray())
    }

    private fun establishedTargetId(partnerEmail: String?): String? {
        if (storage.getSyncProviderMode() == SyncProviderMode.SAF_FILE) {
            return if (!storage.getSafFileUri().isNullOrBlank()) SAF_SYNC_TARGET_ID else null
        }
        val fileId = storage.getPrimarySyncFileId()
        val granted = storage.getGrantedPartnerEmail()?.trim()?.lowercase()
        val requested = partnerEmail?.trim()?.lowercase()
        return if (!fileId.isNullOrBlank() && !requested.isNullOrEmpty() && requested == granted) {
            fileId
        } else {
            null
        }
    }

    private fun handleSkippedUpload(targetId: String, nowMs: Long): UploadOutcome {
        _syncWarning.value = null
        _syncAuthRequired.value = false
        _safAccessLost.value = false
        _syncStatusMessage.value = null
        recordSuccessfulSyncWallclock(nowMs)
        storage.setPendingSyncUpload(false)
        return UploadOutcome.Success(targetId)
    }

    private suspend fun executeUploadWithKey(partnerEmail: String?, encryptionKeyBytes: ByteArray): UploadOutcome {
        return try {
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val payloadObj = buildSyncPayload(nowMs)
            val canonicalHash = computeCanonicalHash(payloadObj)
            val existingTarget = establishedTargetId(partnerEmail)
            if (existingTarget != null && canonicalHash == storage.getLastUploadedContentHash()) {
                return handleSkippedUpload(existingTarget, nowMs)
            }

            @OptIn(ExperimentalEncodingApi::class)
            val encryptionKeyBase64 = Base64.Default.encode(encryptionKeyBytes)
            val payloadJson = AppJson.encodeToString(payloadObj)
            val encryptedBase64 = rustBridge.encryptForSync(payloadJson, encryptionKeyBase64)
            if (encryptedBase64.isEmpty()) return UploadOutcome.PermanentFailure(null)

            val preferredFileId = if (storage.getSyncProviderMode() == SyncProviderMode.SAF_FILE) {
                SAF_SYNC_TARGET_ID
            } else {
                storage.getPrimarySyncFileId()
            }
            val result = googleDriveManager.pushSyncBlob(
                encryptedData = encryptedBase64.encodeToByteArray(),
                preferredFileId = preferredFileId,
                partnerEmail = partnerEmail,
                onPreferredFileNotFound = {
                    storage.savePrimarySyncFileId(null)
                    storage.saveGrantedPartnerEmail(null)
                    storage.savePartnerPermissionId(null)
                }
            )
            handleUploadPushResult(result, partnerEmail, canonicalHash)
        } finally {
            encryptionKeyBytes.fill(0)
        }
    }

    private fun handleUploadPushResult(
        result: String?,
        partnerEmail: String?,
        canonicalHash: String
    ): UploadOutcome {
        return if (result != null) {
            if (storage.getSyncProviderMode() == SyncProviderMode.DRIVE_API) {
                storage.savePrimarySyncFileId(result)
                if (!partnerEmail.isNullOrBlank()) {
                    storage.saveGrantedPartnerEmail(partnerEmail.trim())
                }
            }
            storage.saveLastUploadedContentHash(canonicalHash)
            _syncWarning.value = null
            _syncAuthRequired.value = false
            _safAccessLost.value = false
            _syncStatusMessage.value = null
            recordSuccessfulSyncWallclock()
            UploadOutcome.Success(result)
        } else {
            _syncWarning.value = "Failed to upload sync data."
            UploadOutcome.RetryableFailure(null)
        }
    }

    private fun buildSyncPayload(nowMs: Long): SyncPayload {
        val permissions = rustBridge.getPartnerPermissions()
        val bc = queries.getBirthControl().executeAsOneOrNull()
        val entries = queries.selectAllEntries().executeAsList()
        val pts = queries.selectAllPregnancyTests().executeAsList()
        val reminders = queries.selectAllReminders().executeAsList()

        val filteredEntries = entries.mapNotNull { mapAndFilterEntry(it, permissions) }
        val filteredReminders = reminders.filter { rm ->
            if (rm.type.replace(" ", "").equals("PregnancyTest", ignoreCase = true)) {
                permissions.sharePregnancyTests
            } else {
                permissions.shareBirthControl
            }
        }.map { SyncReminder(it.type, it.frequency, it.timeOfDay, it.nextDate, it.isActive ?: true) }

        return SyncPayload(
            lastUpdated = nowMs,
            birthControl = if (permissions.shareBirthControl) {
                bc?.let { SyncBirthControl(it.type, it.isActive ?: true, it.reminderTime) }
            } else {
                null
            },
            entries = filteredEntries,
            pregnancyTests = if (permissions.sharePregnancyTests) pts.map { SyncPregnancyTest(it.date, it.result) } else emptyList(),
            reminders = filteredReminders,
            permissions = permissions,
            payloadVersion = nextPayloadVersion(nowMs)
        )
    }

    private fun mapAndFilterEntry(entry: com.bloom.db.CycleEntry, permissions: PartnerPermissions): SyncEntry? {
        val redactedIntensity = calculateRedactedIntensity(entry, permissions)
        val safeNotes = resolveSafeNotes(entry, permissions)
        val sharedSymptoms = if (permissions.shareSymptomsMoods) entry.symptoms else null
        val sharedPainLevel = if (permissions.shareSymptomsMoods) entry.painLevel?.toInt() else null
        val sharedSexualRelations = if (permissions.shareSexualActivity) entry.sexualRelations else null

        if (!hasSharedContent(redactedIntensity, safeNotes, sharedSymptoms, sharedPainLevel, sharedSexualRelations)) {
            return null
        }
        return SyncEntry(
            date = entry.date,
            intensity = redactedIntensity,
            notes = safeNotes,
            sexualRelations = sharedSexualRelations,
            painLevel = sharedPainLevel,
            symptoms = sharedSymptoms
        )
    }

    private fun calculateRedactedIntensity(entry: com.bloom.db.CycleEntry, permissions: PartnerPermissions): Int {
        val originalIntensity = entry.intensity?.toInt() ?: 0
        if (!permissions.shareCyclePhase) return 0
        if (permissions.shareFlowIntensity) return originalIntensity
        return if (originalIntensity > 0) 1 else 0
    }

    private fun resolveSafeNotes(entry: com.bloom.db.CycleEntry, permissions: PartnerPermissions): String? {
        val rawNotes = if (permissions.shareSymptomsMoods) entry.notes else null
        if (rawNotes == null || permissions.sharePregnancyTests) return rawNotes
        val lower = rawNotes.lowercase()
        val containsKeywords = lower.contains("pregnan") || lower.contains("hcg") || lower.contains("positive") || lower.contains("negative")
        return if (containsKeywords) null else rawNotes
    }

    private fun hasSharedContent(intensity: Int, notes: String?, symptoms: String?, painLevel: Int?, sexual: String?): Boolean {
        val hasIntensity = intensity > 0
        val hasNotes = !notes.isNullOrBlank()
        val hasSymptoms = !symptoms.isNullOrEmpty()
        val hasPain = (painLevel ?: 0) > 0
        val hasSexual = !sexual.isNullOrEmpty()
        return hasIntensity || hasNotes || hasSymptoms || hasPain || hasSexual
    }

    private fun isAuthException(e: Exception): Boolean {
        val msg = e.message ?: return false
        return msg.contains("auth", ignoreCase = true) ||
            msg.contains("token", ignoreCase = true) ||
            msg.contains("401", ignoreCase = true) ||
            msg.contains("403", ignoreCase = true)
    }

    private fun mapUploadExceptionToOutcome(e: Exception): UploadOutcome {
        if (e is SafAccessLostException) return handleSafAccessLostUpload()
        if (e is DriveStorageFullException) return handleStorageFull(e)
        if (e is DriveAuthRequiredException) return handleDriveAuth(e)
        if (e is DriveSyncException) return handleDriveSyncUpload(e)
        if (isAuthException(e)) return handleAuthRequired()
        _syncWarning.value = e.message ?: "Sync upload failed."
        return UploadOutcome.RetryableFailure(e)
    }

    private fun handleSafAccessLostUpload(): UploadOutcome {
        if (syncAttemptTimestamps.isNotEmpty()) {
            syncAttemptTimestamps.removeAt(syncAttemptTimestamps.lastIndex)
        }
        _safAccessLost.value = true
        _syncAuthRequired.value = true
        _syncWarning.value = "Sync file access lost. Please reconnect your sync file."
        return UploadOutcome.AuthRequired
    }

    private fun handleStorageFull(e: DriveStorageFullException): UploadOutcome {
        val usedGB = formatBytesAsGb(e.usedBytes)
        val limitGB = formatBytesAsGb(e.limitBytes)
        val msg = "Google Drive storage full ($usedGB GB / $limitGB GB). Sync paused until space is freed."
        _syncStatusMessage.value = msg
        _syncWarning.value = msg
        return UploadOutcome.StorageFull
    }

    private fun handleDriveAuth(e: DriveAuthRequiredException): UploadOutcome {
        _syncAuthRequired.value = true
        _syncWarning.value = e.message ?: "Google Drive authorization required."
        return UploadOutcome.AuthRequired
    }

    private fun handleDriveSyncUpload(e: DriveSyncException): UploadOutcome {
        _syncWarning.value = e.message ?: "Sync upload failed."
        return if (e.isRetryable) UploadOutcome.RetryableFailure(e) else UploadOutcome.PermanentFailure(e)
    }

    private fun handleAuthRequired(): UploadOutcome {
        _syncAuthRequired.value = true
        _syncWarning.value = "Google Drive authorization required."
        return UploadOutcome.AuthRequired
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
        if (!forceResync && !checkRateLimit()) {
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
        if (storage.getSyncProviderMode() == SyncProviderMode.SAF_FILE && storage.getSafFileUri().isNullOrBlank()) {
            return handleSafAccessLostPull()
        }
        return try {
            val encryptedBase64 = fetchBlobFromDrive(partnerFileId)
            if (encryptedBase64.isNullOrEmpty()) {
                _syncWarning.value = "No sync data found or failed to download from Google Drive."
                return PullOutcome.RetryableFailure(null)
            }
            if (encryptedBase64 == "REVOKED") {
                return handleRevokedBlob(isOwner)
            }
            processPulledBlob(encryptedBase64, forceResync)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StaleSyncPayloadException) {
            _syncWarning.value = e.message
            PullOutcome.NoOp
        } catch (e: Exception) {
            mapPullExceptionToOutcome(e)
        }
    }

    private suspend fun fetchBlobFromDrive(partnerFileId: String?): String? {
        return if (!partnerFileId.isNullOrEmpty() && partnerFileId != "appDataFolder") {
            googleDriveManager.downloadPublicSyncBlob(partnerFileId)
        } else {
            googleDriveManager.pullSyncBlob()
        }
    }

    private fun handleRevokedBlob(isOwner: Boolean): PullOutcome {
        if (!isOwner) {
            wipePartnerStateSilently()
            return PullOutcome.Revoked
        }
        return PullOutcome.Success
    }

    private fun processPulledBlob(encryptedBase64: String, forceResync: Boolean): PullOutcome {
        val previousWarning = _syncWarning.value
        _syncWarning.value = null
        val success = importEncryptedSyncPayloadInternal(encryptedBase64, forceResync = forceResync)
        return if (success) {
            _syncWarning.value = null
            _syncAuthRequired.value = false
            _safAccessLost.value = false
            _syncStatusMessage.value = null
            recordSuccessfulSyncWallclock()
            PullOutcome.Success
        } else {
            if (_syncWarning.value == null) {
                _syncWarning.value = previousWarning?.takeIf { it.isNotBlank() }
                    ?: "Failed to decrypt or validate sync payload."
            }
            PullOutcome.PermanentFailure(null)
        }
    }

    private fun mapPullExceptionToOutcome(e: Exception): PullOutcome {
        if (e is SafAccessLostException) return handleSafAccessLostPull()
        if (e is DriveAuthRequiredException) return handlePullAuth(e)
        if (e is DriveSyncException) return handleDriveSyncPull(e)
        if (isAuthException(e)) return handlePullAuthRequired()
        _syncWarning.value = e.message ?: "Sync refresh failed."
        return PullOutcome.RetryableFailure(e)
    }

    private fun handleSafAccessLostPull(): PullOutcome {
        if (syncAttemptTimestamps.isNotEmpty()) {
            syncAttemptTimestamps.removeAt(syncAttemptTimestamps.lastIndex)
        }
        _safAccessLost.value = true
        _syncAuthRequired.value = true
        _syncWarning.value = "Sync file access lost. Please reconnect your sync file."
        return PullOutcome.AuthRequired
    }

    private fun handlePullAuth(e: DriveAuthRequiredException): PullOutcome {
        _syncAuthRequired.value = true
        _syncWarning.value = e.message ?: "Google Drive authorization required."
        return PullOutcome.AuthRequired
    }

    private fun handleDriveSyncPull(e: DriveSyncException): PullOutcome {
        _syncWarning.value = e.message ?: "Sync refresh failed."
        return if (e.isRetryable) PullOutcome.RetryableFailure(e) else PullOutcome.PermanentFailure(e)
    }

    private fun handlePullAuthRequired(): PullOutcome {
        _syncAuthRequired.value = true
        _syncWarning.value = "Google Drive authorization required."
        return PullOutcome.AuthRequired
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
        storage.saveLastSuccessfulSyncWallclock(0L)
        storage.saveLastImportedVersion(0L)
        rustBridge.savePartnerPermissions(PartnerPermissions())
        backgroundSyncScheduler?.cancelPartnerPull()
        cycleTracker?.refreshCycleState()
        _lastSuccessfulSyncWallclock.value = 0L
        _syncWarning.value = null
        _syncAuthRequired.value = false
        _safAccessLost.value = false
        _syncStatusMessage.value = null
    }

    suspend fun importEncryptedSyncPayload(
        encryptedBase64: String,
        forceResync: Boolean = false
    ): Boolean = uploadMutex.withLock {
        withContext(Dispatchers.IO) {
            val success = try {
                importEncryptedSyncPayloadInternal(encryptedBase64, forceResync = forceResync)
            } catch (e: StaleSyncPayloadException) {
                _syncWarning.value = e.message
                false
            }
            if (success) {
                _syncWarning.value = null
                _syncAuthRequired.value = false
                _safAccessLost.value = false
                _syncStatusMessage.value = null
                recordSuccessfulSyncWallclock()
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
        val decryptionKeyBytes = storage.getSyncSecretKey()
        if (decryptionKeyBytes == null) {
            _syncWarning.value = "Sync secret key missing on this device. Please re-link partner or regenerate a sync code."
            return false
        }
        return try {
            decryptAndProcessPayload(encryptedBase64, decryptionKeyBytes, forceResync)
        } finally {
            decryptionKeyBytes.fill(0)
        }
    }

    private fun decryptAndProcessPayload(
        encryptedBase64: String,
        decryptionKeyBytes: ByteArray,
        forceResync: Boolean
    ): Boolean {
        @OptIn(ExperimentalEncodingApi::class)
        val decryptionKeyBase64 = Base64.Default.encode(decryptionKeyBytes)
        val rawJson = rustBridge.decryptFromSync(encryptedBase64, decryptionKeyBase64)
        if (rawJson.isEmpty()) {
            _syncWarning.value = "Failed to decrypt sync payload (key mismatch or revoked access)."
            return false
        }

        val payload = decodeAndValidatePayloadHeader(rawJson, forceResync) ?: return false
        val validatedData = validatePayloadItems(payload) ?: return false

        payload.permissions?.let {
            storage.savePartnerPermissionsJson(AppJson.encodeToString(it))
            rustBridge.savePartnerPermissions(it)
        }

        commitPayloadToDatabase(payload, validatedData)
        cycleTracker?.refreshCycleState()
        storage.saveLastSyncTimestamp(payload.lastUpdated)
        if (payload.payloadVersion > 0L) {
            storage.saveLastImportedVersion(payload.payloadVersion)
        }
        return true
    }

    private fun decodeAndValidatePayloadHeader(rawJson: String, forceResync: Boolean): SyncPayload? {
        val payload = try {
            AppJson.decodeFromString<SyncPayload>(rawJson)
        } catch (_: Throwable) {
            _syncWarning.value = "Received malformed sync payload."
            return null
        }
        if (!forceResync && payload.payloadVersion > 0L && payload.payloadVersion < storage.getLastImportedVersion()) {
            throw StaleSyncPayloadException("Sync skipped: remote payload version is older than last imported version.")
        }
        if (!validatePayloadTimestamp(payload, forceResync)) return null
        if (!validatePayloadItemCounts(payload)) return null
        return payload
    }

    private fun validatePayloadTimestamp(payload: SyncPayload, forceResync: Boolean): Boolean {
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
        return true
    }

    private fun validatePayloadItemCounts(payload: SyncPayload): Boolean {
        if (payload.entries.size > 5000 || payload.pregnancyTests.size > 1000 || payload.reminders.size > 100) {
            _syncWarning.value = "Sync payload exceeds item count limits."
            return false
        }
        return true
    }

    private data class ValidatedSyncData(
        val entries: List<ValidatedEntry>,
        val tests: List<ValidatedPregnancyTest>,
        val reminders: List<ValidatedReminder>
    )

    private data class ValidatedEntry(
        val date: String,
        val intensity: Int,
        val notes: String?,
        val sexualRelations: String?,
        val painLevel: Int?,
        val symptoms: String?
    )
    private data class ValidatedPregnancyTest(
        val date: String,
        val result: String
    )
    private data class ValidatedReminder(
        val type: String,
        val frequency: String,
        val timeOfDay: String?,
        val nextDate: String?,
        val isActive: Boolean
    )

    private fun validatePayloadItems(payload: SyncPayload): ValidatedSyncData? {
        val dateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        val validatedEntries = payload.entries.mapNotNull { validateSingleEntry(it, dateRegex) }
        val validatedTests = payload.pregnancyTests.mapNotNull { validateSingleTest(it, dateRegex) }
        val validatedReminders = payload.reminders.mapNotNull { validateSingleReminder(it, dateRegex) }

        return ValidatedSyncData(validatedEntries, validatedTests, validatedReminders)
    }

    private fun validateSingleEntry(entry: SyncEntry, dateRegex: Regex): ValidatedEntry? {
        if (!isValidCalendarDate(entry.date, dateRegex)) return null
        val validIntensity = entry.intensity.coerceIn(0, 5)
        val validPainLevel = entry.painLevel?.coerceIn(0, 10)
        val cleanNotes = sanitizeText(entry.notes, 500)
        val cleanSymptoms = sanitizeText(entry.symptoms, 200)
        val cleanSexual = sanitizeText(entry.sexualRelations, 50)
        return ValidatedEntry(entry.date, validIntensity, cleanNotes, cleanSexual, validPainLevel, cleanSymptoms)
    }

    private fun validateSingleTest(pt: SyncPregnancyTest, dateRegex: Regex): ValidatedPregnancyTest? {
        val cleanResult = sanitizeText(pt.result, 100)
        if (!isValidCalendarDate(pt.date, dateRegex) || cleanResult.isNullOrEmpty()) return null
        return ValidatedPregnancyTest(pt.date, cleanResult)
    }

    private fun validateSingleReminder(rm: SyncReminder, dateRegex: Regex): ValidatedReminder? {
        val cleanType = sanitizeText(rm.type, 50)
        if (cleanType.isNullOrEmpty()) return null
        val cleanFreq = sanitizeText(rm.frequency, 50) ?: ""
        val cleanTime = sanitizeText(rm.timeOfDay, 50)
        val validNext = if (rm.nextDate != null && isValidCalendarDate(rm.nextDate, dateRegex)) rm.nextDate else null
        return ValidatedReminder(cleanType, cleanFreq, cleanTime, validNext, rm.isActive)
    }

    private fun commitPayloadToDatabase(payload: SyncPayload, validatedData: ValidatedSyncData) {
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

            validatedData.entries.forEach { entry ->
                queries.insertEntry(entry.date, entry.intensity, entry.notes, entry.sexualRelations, entry.painLevel, entry.symptoms)
            }

            validatedData.tests.forEach { pt ->
                queries.insertPregnancyTest(pt.date, pt.result)
            }

            validatedData.reminders.forEach { rm ->
                queries.insertReminder(rm.type, rm.frequency, rm.timeOfDay, rm.nextDate, rm.isActive)
            }
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
            if (!isOwnerMode()) return@withLock false
            try {
                if (storage.getSyncProviderMode() == SyncProviderMode.SAF_FILE) {
                    return@withLock revokeAndRegenerateSafLocked()
                }
                val deleted = googleDriveManager.deleteSyncBlob()
                if (!deleted) {
                    _syncWarning.value = "Failed to delete existing sync file on Google Drive."
                    return@withLock false
                }

                clearRevokedOwnerMetadata()
                storage.savePrimarySyncFileId(null)
                hasPendingChanges.value = false
                pendingSyncCoordinator.clearPendingAndCancel()

                rotateSyncSecretKey()
                clearStatusFlags()
                return@withLock true
            } catch (e: CancellationException) {
                throw e
            } catch (e: SafAccessLostException) {
                handleSafAccessLostUpload()
                return@withLock false
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

    private fun clearRevokedOwnerMetadata() {
        storage.saveGrantedPartnerEmail(null)
        storage.savePartnerPermissionId(null)
        storage.saveLastUploadedContentHash(null)
    }

    private fun rotateSyncSecretKey(): ByteArray {
        val newKey = rustBridge.generateRandomKey()
        storage.saveSyncSecretKey(newKey)
        return newKey
    }

    private fun clearStatusFlags() {
        _syncWarning.value = null
        _syncAuthRequired.value = false
        _safAccessLost.value = false
        _syncStatusMessage.value = null
    }

    private suspend fun revokeAndRegenerateSafLocked(): Boolean {
        clearRevokedOwnerMetadata()
        hasPendingChanges.value = false
        pendingSyncCoordinator.clearPendingAndCancel()
        val newKey = rotateSyncSecretKey()
        if (storage.getSafFileUri().isNullOrBlank()) {
            newKey.fill(0)
            clearStatusFlags()
            return true
        }
        val outcome = executeUploadWithKey(partnerEmail = null, encryptionKeyBytes = newKey)
        return if (outcome is UploadOutcome.Success) {
            clearStatusFlags()
            true
        } else {
            false
        }
    }
}

private val SHA256_K = intArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
    0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
    0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
    0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
    0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
    0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
    0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
    0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
    0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
)

internal fun sha256Hex(input: ByteArray): String {
    val bitLen = input.size.toLong() * 8L
    val paddedLen = ((input.size + 9 + 63) / 64) * 64
    val padded = ByteArray(paddedLen)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    for (i in 0 until 8) {
        padded[paddedLen - 1 - i] = ((bitLen ushr (i * 8)) and 0xFFL).toByte()
    }

    val h = intArrayOf(
        0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19
    )
    val w = IntArray(64)
    for (offset in 0 until paddedLen step 64) {
        sha256CompressBlock(padded, offset, h, w)
    }
    return buildString(64) {
        for (value in h) {
            for (shift in 28 downTo 0 step 4) {
                append(((value ushr shift) and 0xF).toString(16))
            }
        }
    }
}

private fun sha256CompressBlock(padded: ByteArray, offset: Int, h: IntArray, w: IntArray) {
    for (i in 0 until 16) {
        val j = offset + i * 4
        w[i] = ((padded[j].toInt() and 0xFF) shl 24) or
            ((padded[j + 1].toInt() and 0xFF) shl 16) or
            ((padded[j + 2].toInt() and 0xFF) shl 8) or
            (padded[j + 3].toInt() and 0xFF)
    }
    for (i in 16 until 64) {
        val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
        val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
        w[i] = w[i - 16] + s0 + w[i - 7] + s1
    }
    var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
    var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
    for (i in 0 until 64) {
        val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
        val ch = (e and f) xor (e.inv() and g)
        val temp1 = hh + s1 + ch + SHA256_K[i] + w[i]
        val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
        val maj = (a and b) xor (a and c) xor (b and c)
        val temp2 = s0 + maj
        hh = g; g = f; f = e; e = d + temp1
        d = c; c = b; b = a; a = temp1 + temp2
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d
    h[4] += e; h[5] += f; h[6] += g; h[7] += hh
}
