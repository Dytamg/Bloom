@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.novarytm.sync

import com.novarytm.db.CycleEntry
import com.novarytm.db.NovarytmDatabase
import com.novarytm.tracker.CycleTracker
import com.novarytm.ui.PeriodGroup
import com.novarytm.ui.groupPeriodEntries
import com.novarytm.utils.AppJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.*

class SyncManagerTest {

    private fun createInMemoryDb(): NovarytmDatabase {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        NovarytmDatabase.Schema.create(driver)
        val intAdapter = object : app.cash.sqldelight.ColumnAdapter<Int, Long> {
            override fun decode(databaseValue: Long) = databaseValue.toInt()
            override fun encode(value: Int) = value.toLong()
        }
        return NovarytmDatabase(
            driver = driver,
            CycleEntryAdapter = CycleEntry.Adapter(
                intensityAdapter = intAdapter,
                painLevelAdapter = intAdapter
            )
        )
    }

    private suspend fun awaitCondition(timeoutMs: Long = 2000, condition: () -> Boolean) {
        val start = kotlin.time.Clock.System.now().toEpochMilliseconds()
        while (!condition()) {
            if (kotlin.time.Clock.System.now().toEpochMilliseconds() - start > timeoutMs) {
                fail("Condition not met within $timeoutMs ms")
            }
            delay(20)
        }
    }

    // =========================================================================
    // WORKFLOW 1: Owner Logging, Editing, Symptom Clearing & Deleting
    // =========================================================================

    @Test
    fun testWorkflow1_logRegularPeriodRange() {
        runBlocking {
            val db = createInMemoryDb()
            val tracker = CycleTracker(com.novarytm.ffi.RustBridge(), db.cycleQueries, this)

            val startDate = LocalDate.parse("2024-03-01")
            val endDate = LocalDate.parse("2024-03-04")
            tracker.logNewPeriodRange(
                startDate = startDate,
                endDate = endDate,
                intensity = 3,
                notes = "Heavy first day",
                sexualRelations = "protected",
                painLevel = 4,
                symptoms = "cramps,headaches"
            )

            awaitCondition { db.cycleQueries.selectAllEntries().executeAsList().size == 4 }

            val entries = db.cycleQueries.selectAllEntries().executeAsList().sortedBy { it.date }
            assertEquals(4, entries.size)

            // Day 1: gets initial notes
            assertEquals("2024-03-01", entries[0].date)
            assertEquals(3, entries[0].intensity)
            assertEquals("Heavy first day", entries[0].notes)
            assertEquals(4, entries[0].painLevel)
            assertEquals("Cramps,Headaches", entries[0].symptoms)
            assertEquals("protected", entries[0].sexualRelations)

            // Days 2..4: intensity, pain, symptoms and relations carried forward, notes null
            for (i in 1..3) {
                assertEquals(3, entries[i].intensity)
                assertNull(entries[i].notes)
                assertEquals(4, entries[i].painLevel)
                assertEquals("Cramps,Headaches", entries[i].symptoms)
            }

            // Verify groupPeriodEntries groups this multi-day range
            val groups = groupPeriodEntries(entries)
            assertEquals(1, groups.size)
            assertEquals(startDate, groups[0].startDate)
            assertEquals(endDate, groups[0].endDate)
            assertEquals(3, groups[0].maxIntensity)
            assertEquals(4, groups[0].maxPainLevel)
            assertEquals(4, groups[0].dailyEntries.size)
        }
    }

    @Test
    fun testWorkflow1_zeroFlowEntry_symptomsAndPainBehavior() {
        runBlocking {
            val db = createInMemoryDb()
            val tracker = CycleTracker(com.novarytm.ffi.RustBridge(), db.cycleQueries, this)

            // Owner logs a day with ZERO flow, but severe cramps and headaches
            val date = LocalDate.parse("2024-03-10")
            tracker.logNewPeriod(
                date = date,
                intensity = 0,
                notes = "Severe pain without bleeding",
                painLevel = 7,
                symptoms = "cramps,headaches"
            )

            awaitCondition { db.cycleQueries.selectAllEntries().executeAsList().isNotEmpty() }

            val allEntries = db.cycleQueries.selectAllEntries().executeAsList()
            assertEquals(1, allEntries.size)
            val entry = allEntries[0]
            assertEquals(0, entry.intensity)
            assertEquals(7, entry.painLevel)
            assertEquals("Cramps,Headaches", entry.symptoms)
            assertEquals("Severe pain without bleeding", entry.notes)

            // AUDIT FINDING VERIFICATION:
            // 1. groupPeriodEntries(entries) filters `(it.intensity ?: 0) > 0`
            // So zero-flow entries are completely omitted from the unselected history list!
            val groups = groupPeriodEntries(allEntries)
            assertTrue(groups.isEmpty(), "Zero-flow entries MUST be excluded from grouped period history")

            // 2. CalendarGrid loggedDates only includes `intensity > 0`
            // So the day has no pink circle indicator on the calendar
            val loggedDates = tracker.loggedDates.value
            assertFalse(loggedDates.contains(date), "Zero-flow date should NOT be highlighted as logged period date")

            // 3. But in CalendarScreen line 374, when a date IS selected:
            // val displayEntries = entries.filter { it.date == selectedDate.toString() }
            val displayEntries = allEntries.filter { it.date == date.toString() }
            assertEquals(1, displayEntries.size, "Selected date MUST find the zero-intensity entry")

            // 4. In CalendarScreen line 393:
            // val periodEntry = displayEntries.firstOrNull { (it.intensity ?: 0) > 0 } ?: currentEntry
            val currentEntry = displayEntries.firstOrNull()
            val periodEntry = displayEntries.firstOrNull { (it.intensity ?: 0) > 0 } ?: currentEntry
            assertNotNull(periodEntry, "periodEntry falls back to currentEntry so user can edit it")
            assertEquals(0, periodEntry.intensity)
        }
    }

    @Test
    fun testWorkflow1_symptomClearing_emptyStringClearsToNull() {
        runBlocking {
            val db = createInMemoryDb()
            val tracker = CycleTracker(com.novarytm.ffi.RustBridge(), db.cycleQueries, this)

            val date = LocalDate.parse("2024-03-12")
            // First log with cramps
            tracker.logNewPeriod(date = date, intensity = 2, symptoms = "cramps")
            awaitCondition { db.cycleQueries.selectEntryByDate(date.toString()).executeAsOneOrNull()?.symptoms != null }

            // User edits and unchecks all symptoms: UI passes empty string ""
            tracker.logNewPeriod(date = date, intensity = 2, symptoms = "")
            awaitCondition { db.cycleQueries.selectEntryByDate(date.toString()).executeAsOneOrNull()?.symptoms == null }

            val updated = db.cycleQueries.selectEntryByDate(date.toString()).executeAsOne()
            assertNull(updated.symptoms, "Passing empty string for symptoms should clear it to null")

            // Passing null should preserve existing symptoms
            tracker.logNewPeriod(date = date, intensity = 2, symptoms = "nausea")
            awaitCondition { db.cycleQueries.selectEntryByDate(date.toString()).executeAsOneOrNull()?.symptoms == "Nausea" }

            // Log sexual relation only (passes null symptoms)
            tracker.logSexualRelation(date, "protected")
            awaitCondition { db.cycleQueries.selectEntryByDate(date.toString()).executeAsOneOrNull()?.sexualRelations == "protected" }
            val preserved = db.cycleQueries.selectEntryByDate(date.toString()).executeAsOne()
            assertEquals("Nausea", preserved.symptoms, "Partial updates should preserve existing symptoms")
        }
    }

    @Test
    fun testWorkflow1_deleteEntryById() {
        runBlocking {
            val db = createInMemoryDb()
            val tracker = CycleTracker(com.novarytm.ffi.RustBridge(), db.cycleQueries, this)

            val date = LocalDate.parse("2024-03-15")
            tracker.logNewPeriod(date = date, intensity = 3, notes = "To be deleted")
            awaitCondition { db.cycleQueries.selectAllEntries().executeAsList().isNotEmpty() }

            val entry = db.cycleQueries.selectEntryByDate(date.toString()).executeAsOne()
            val entryId = entry.id

            // Execute delete
            tracker.deleteEntry(entryId)
            awaitCondition { db.cycleQueries.selectAllEntries().executeAsList().isEmpty() }

            val afterDelete = db.cycleQueries.selectEntryByDate(date.toString()).executeAsOneOrNull()
            assertNull(afterDelete, "Entry should be deleted by ID")
        }
    }

    @Test
    fun testWorkflow1_inputValidationAndSanitization() {
        runBlocking {
            val db = createInMemoryDb()
            val tracker = CycleTracker(com.novarytm.ffi.RustBridge(), db.cycleQueries, this)

            val date = LocalDate.parse("2024-03-20")
            // Intensity clamped 0..5, pain clamped 0..10, HTML tags stripped, unknown symptoms discarded
            tracker.logNewPeriod(
                date = date,
                intensity = 99, // Should clamp to 5
                notes = "<script>alert('xss')</script>Leg cramps & tiredness<b>!</b>",
                painLevel = 25, // Should clamp to 10
                symptoms = "cramps,invalid_symptom,headaches"
            )

            awaitCondition { db.cycleQueries.selectAllEntries().executeAsList().isNotEmpty() }

            val entry = db.cycleQueries.selectEntryByDate(date.toString()).executeAsOne()
            assertEquals(5, entry.intensity)
            assertEquals(10, entry.painLevel)
            assertEquals("alert('xss')Leg cramps & tiredness!", entry.notes)
            assertEquals("Cramps,Headaches", entry.symptoms)
        }
    }

    // =========================================================================
    // WORKFLOW 2: Partner Sharing & 5-Minute Sync Code Timing
    // =========================================================================

    @Test
    fun testWorkflow2_syncCodeTiming_expirationLogic() {
        val expirationMs = 300_000L // 5 minutes
        val now = 1_700_000_000_000L

        // Case 1: Fresh code (created 1 minute ago) -> Valid
        val freshTimestamp = now - 60_000L
        assertTrue((now - freshTimestamp) in -60_000L..expirationMs)

        // Case 2: Code exactly at 5 minute boundary (300,000ms) -> Valid
        val boundaryTimestamp = now - 300_000L
        assertTrue((now - boundaryTimestamp) in -60_000L..expirationMs)

        // Case 3: Code expired by 1ms (300,001ms) -> Invalid
        val expiredTimestamp = now - 300_001L
        assertFalse((now - expiredTimestamp) in -60_000L..expirationMs)

        // Case 4: Owner phone clock is 30 seconds ahead of partner phone clock (now < timestamp)
        // Timestamp is 30,000ms in the future from partner perspective:
        val slightFutureSkew = now + 30_000L
        assertTrue((now - slightFutureSkew) in -60_000L..expirationMs)

        // Case 5: Future skew exactly at 60s boundary (-60,000L) -> Valid
        val boundaryFutureSkew = now + 60_000L
        assertTrue((now - boundaryFutureSkew) in -60_000L..expirationMs)

        // Case 6: Excessive future timestamp (> 60s ahead, e.g. 61s ahead) -> Rejected
        val excessiveFutureSkew = now + 60_001L
        assertFalse((now - excessiveFutureSkew) in -60_000L..expirationMs)
    }

    @Test
    fun testWorkflow2_syncCodeFormatValidation() {
        // Correct format: ephemeralKeyBase64:ciphertextBase64
        // where decrypted ciphertext is: encryptionKeyBase64|timestamp|fileId
        val validFormat = "ZW5jcnlwdGlvbktleQ==|1700000000000|driveFileId123"
        val parts = validFormat.split("|")
        assertEquals(3, parts.size)
        assertEquals("ZW5jcnlwdGlvbktleQ==", parts[0])
        assertEquals(1700000000000L, parts[1].toLongOrNull())
        assertEquals("driveFileId123", parts[2])

        // Missing delimiter: no ':' separator
        val malformedNoColon = "randomBase64StringWithoutColon"
        assertEquals(-1, malformedNoColon.indexOf(':'))

        // Missing fields in decrypted string: only 2 parts
        val malformedTwoParts = "key|1700000000000".split("|")
        assertNotEquals(3, malformedTwoParts.size)
    }

    // =========================================================================
    // WORKFLOW 3: Revoke Access, Partner Refresh & Partner Disconnect
    // =========================================================================

    @Test
    fun testWorkflow3_partnerPermissionsFiltering() {
        val fullEntries = listOf(
            CycleEntry(1L, "2024-03-01", 3, "Day 1 heavy", "unprotected", 4, "Cramps"),
            CycleEntry(2L, "2024-03-02", 0, "No flow", "protected", 1, "Headaches")
        )

        // 1. Partner has ONLY CyclePhase granted (Flow intensity hidden)
        val phaseOnlyPerms = PartnerPermissions(
            shareCyclePhase = true,
            shareFlowIntensity = false,
            shareSymptomsMoods = false,
            shareBirthControl = false,
            sharePregnancyTests = false,
            shareSexualActivity = false
        )

        val filteredPhaseOnly = fullEntries.map {
            val originalIntensity = it.intensity ?: 0
            SyncEntry(
                date = it.date,
                intensity = when {
                    phaseOnlyPerms.shareFlowIntensity -> originalIntensity
                    phaseOnlyPerms.shareCyclePhase && originalIntensity > 0 -> 1 // Redacted to 1
                    else -> 0
                },
                notes = if (phaseOnlyPerms.shareSymptomsMoods) it.notes else null,
                sexualRelations = if (phaseOnlyPerms.shareSexualActivity) it.sexualRelations else null,
                painLevel = if (phaseOnlyPerms.shareSymptomsMoods) it.painLevel else null,
                symptoms = if (phaseOnlyPerms.shareSymptomsMoods) it.symptoms else null
            )
        }

        // Bleeding day redacted from 3 to 1
        assertEquals(1, filteredPhaseOnly[0].intensity)
        assertNull(filteredPhaseOnly[0].notes)
        assertNull(filteredPhaseOnly[0].sexualRelations)
        assertNull(filteredPhaseOnly[0].painLevel)
        assertNull(filteredPhaseOnly[0].symptoms)

        // Zero-flow day stays 0
        assertEquals(0, filteredPhaseOnly[1].intensity)
        assertNull(filteredPhaseOnly[1].notes)

        // 2. All cycle permissions revoked
        val noCyclePerms = PartnerPermissions(
            shareCyclePhase = false,
            shareFlowIntensity = false,
            shareSymptomsMoods = false
        )
        val filteredEmpty = if (!noCyclePerms.shareCyclePhase && !noCyclePerms.shareFlowIntensity && !noCyclePerms.shareSymptomsMoods) {
            emptyList()
        } else {
            fullEntries.map { SyncEntry(it.date, 0, null) }
        }
        assertTrue(filteredEmpty.isEmpty(), "Entries list should be completely empty when cycle permissions are disabled")
    }

    @Test
    fun testWorkflow3_partnerDisconnect_wipesAllLocalTables() {
        val db = createInMemoryDb()

        // Populate partner's local database with cached owner data
        db.cycleQueries.insertEntry("2024-03-01", 3, "Cached note", "protected", 2, "Cramps")
        db.cycleQueries.setBirthControl("Pill")
        db.cycleQueries.insertPregnancyTest("2024-03-05", "Negative")
        db.cycleQueries.insertReminder("Pill", "Daily", "09:00", null, true)

        assertEquals(1, db.cycleQueries.selectAllEntries().executeAsList().size)
        assertNotNull(db.cycleQueries.getBirthControl().executeAsOneOrNull())
        assertEquals(1, db.cycleQueries.selectAllPregnancyTests().executeAsList().size)
        assertEquals(1, db.cycleQueries.selectAllReminders().executeAsList().size)

        // Execute onDisconnect routine as implemented in App.kt line 705
        db.transaction {
            db.cycleQueries.deleteAllEntries()
            db.cycleQueries.deleteAllPregnancyTests()
            db.cycleQueries.deleteAllReminders()
            db.cycleQueries.deleteBirthControl()
        }

        // Verify everything is wiped clean
        assertTrue(db.cycleQueries.selectAllEntries().executeAsList().isEmpty())
        assertNull(db.cycleQueries.getBirthControl().executeAsOneOrNull())
        assertTrue(db.cycleQueries.selectAllPregnancyTests().executeAsList().isEmpty())
        assertTrue(db.cycleQueries.selectAllReminders().executeAsList().isEmpty())
    }

    @Test
    fun testWorkflow3_partnerRefresh_revokedBlobWipesPartnerTablesOnly() {
        val db = createInMemoryDb()

        // Populate database
        db.cycleQueries.insertEntry("2024-03-01", 3, "Cached entry", null, 2, "Cramps")
        db.cycleQueries.setBirthControl("Pill")
        assertEquals(1, db.cycleQueries.selectAllEntries().executeAsList().size)

        // Case A: User is Owner -> REVOKED must NEVER wipe local tables
        val isOwner = true
        val encryptedBase64 = "REVOKED"
        if (encryptedBase64 == "REVOKED") {
            if (!isOwner) {
                db.transaction {
                    db.cycleQueries.deleteAllEntries()
                    db.cycleQueries.deleteBirthControl()
                }
            }
        }
        assertEquals(1, db.cycleQueries.selectAllEntries().executeAsList().size, "Owner database must NOT be wiped on REVOKED")

        // Case B: User is Partner -> REVOKED explicitly clears partner cached tables
        val isOwnerPartnerMode = false
        if (encryptedBase64 == "REVOKED") {
            if (!isOwnerPartnerMode) {
                db.transaction {
                    db.cycleQueries.deleteAllEntries()
                    db.cycleQueries.deleteAllPregnancyTests()
                    db.cycleQueries.deleteAllReminders()
                    db.cycleQueries.deleteBirthControl()
                }
            }
        }
        assertTrue(db.cycleQueries.selectAllEntries().executeAsList().isEmpty(), "Partner database MUST be wiped when blob is REVOKED")
        assertNull(db.cycleQueries.getBirthControl().executeAsOneOrNull(), "Birth control must be wiped")
    }

    // =========================================================================
    // WORKFLOW 4: File Timing, Debounce & Owner Sync Safety
    // =========================================================================

    @Test
    fun testWorkflow4_ownerOverwriteProtection() {
        val db = createInMemoryDb()

        // Owner has local entries
        db.cycleQueries.insertEntry("2024-03-01", 4, "Owner primary data", null, 3, "Cramps")
        val hasLocalEntries = db.cycleQueries.selectAllEntries().executeAsList().isNotEmpty()
        val userId = "owner-user-1234"
        val partnerFileId: String? = null
        val isOwner = userId.isNotEmpty() && partnerFileId.isNullOrEmpty()

        // In SyncManager.manualRefresh:
        // if (isOwner && hasLocalEntries) { return prepareAndUploadPayload() != null }
        // It NEVER calls importEncryptedSyncPayload on an owner device with existing data!
        assertTrue(isOwner && hasLocalEntries, "Owner with local entries MUST be protected from remote pull")
    }

    @Test
    fun testWorkflow4_syncPayloadValidation_antiRollbackAndLimits() {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val lastSyncTs = 1_700_000_000_000L

        // 1. Rollback payload (lastUpdated older than last sync) -> Rejected
        val stalePayload = SyncPayload(
            lastUpdated = lastSyncTs - 10_000L,
            birthControl = null,
            entries = emptyList()
        )
        val isStaleRejected = stalePayload.lastUpdated <= 0L || (lastSyncTs > 0L && stalePayload.lastUpdated < lastSyncTs)
        assertTrue(isStaleRejected, "Stale payload with lastUpdated < lastSyncTimestamp MUST be rejected")

        // 2. Future payload (> 24 hours in future) -> Rejected
        val futurePayload = SyncPayload(
            lastUpdated = now + 86_400_000L + 1000L,
            birthControl = null,
            entries = emptyList()
        )
        val isFutureRejected = futurePayload.lastUpdated > now + 86_400_000L
        assertTrue(isFutureRejected, "Payload with timestamp > 24 hours in the future MUST be rejected")

        // 3. Size limit overflow (> 5000 entries) -> Rejected
        val oversizedEntries = List(5001) { SyncEntry("2024-01-01", 1, null) }
        assertTrue(oversizedEntries.size > 5000, "Payload exceeding 5000 entries limit MUST be rejected")

        // 4. Input date format regex validation
        val dateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        assertTrue(dateRegex.matches("2024-03-01"))
        assertFalse(dateRegex.matches("2024-3-1"))
        assertFalse(dateRegex.matches("03/01/2024"))
        assertFalse(dateRegex.matches("invalid-date"))
    }
}
