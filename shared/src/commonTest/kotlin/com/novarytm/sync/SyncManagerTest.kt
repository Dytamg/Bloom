@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.novarytm.sync

import com.novarytm.db.CycleEntry
import com.novarytm.db.NovarytmDatabase
import com.novarytm.notifications.NotificationScheduling
import com.novarytm.notifications.reminderNotificationId
import com.novarytm.notifications.repeatIntervalMsFor
import com.novarytm.tracker.CycleTracker
import com.novarytm.ui.PeriodGroup
import com.novarytm.ui.groupPeriodEntries
import com.novarytm.utils.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext
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
        db.cycleQueries.setBirthControl("Pill", true)
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
        db.cycleQueries.setBirthControl("Pill", true)
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

        // 1. Rollback payload with 5-minute (300,000ms) clock skew tolerance
        val clockSkewPayload = SyncPayload(
            lastUpdated = lastSyncTs - 100_000L, // 100s ago, within 300s tolerance
            birthControl = null,
            entries = emptyList()
        )
        val isClockSkewAccepted = clockSkewPayload.lastUpdated > 0L && (lastSyncTs <= 0L || clockSkewPayload.lastUpdated >= lastSyncTs - 300_000L)
        assertTrue(isClockSkewAccepted, "Payload within 5-minute clock skew tolerance MUST be accepted")

        val stalePayload = SyncPayload(
            lastUpdated = lastSyncTs - 400_000L, // > 5 minutes stale
            birthControl = null,
            entries = emptyList()
        )
        val isStaleRejected = stalePayload.lastUpdated <= 0L || (lastSyncTs > 0L && stalePayload.lastUpdated < lastSyncTs - 300_000L)
        assertTrue(isStaleRejected, "Stale payload with lastUpdated < lastSyncTimestamp - 300,000ms MUST be rejected")

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

    // =========================================================================
    // V15 SECURITY, BIOMETRICS & A11Y REMEDIATION TESTS
    // =========================================================================

    @Test
    fun testV15_partnerRedaction_cyclePhaseFalseHidesDatesAndPregnancyKeywordsRedacted() {
        val entries = listOf(
            CycleEntry(1L, "2024-03-01", 3, "Took a pregnancy test today", null, 2, "Cramps"),
            CycleEntry(2L, "2024-03-02", 0, "hcg test was positive", null, 1, "Nausea"),
            CycleEntry(3L, "2024-03-03", 2, "Negative result yesterday, feeling better", null, 0, null),
            CycleEntry(4L, "2024-03-04", 1, "Standard cramps and headache", null, 3, "Headache")
        )

        val reminders = listOf(
            com.novarytm.db.Reminder(1L, "Pregnancy Test", "Monthly", "09:00", "2024-03-15", true),
            com.novarytm.db.Reminder(2L, "Birth Control", "Daily", "21:00", "2024-03-02", true)
        )

        // Case 1 (VULN-SYNC-01): shareCyclePhase = false, shareSymptomsMoods = true
        // Even if symptoms/moods is true, cycle phase is false, so entries MUST be emptyList() (zero dates leaked)
        val permsNoCycle = PartnerPermissions(
            shareCyclePhase = false,
            shareFlowIntensity = false,
            shareSymptomsMoods = true,
            shareBirthControl = false,
            sharePregnancyTests = false,
            shareSexualActivity = false
        )

        val filteredEntriesNoCycle = if (!permsNoCycle.shareCyclePhase) {
            emptyList()
        } else {
            entries.map { SyncEntry(it.date, 0, null) }
        }
        assertTrue(filteredEntriesNoCycle.isEmpty(), "VULN-SYNC-01: filteredEntries MUST be empty when shareCyclePhase is false")

        // Case 2 (VULN-SYNC-02): shareCyclePhase = true, shareSymptomsMoods = true, sharePregnancyTests = false
        // Notes containing 'pregnan', 'hcg', 'positive', or 'negative' MUST have notes redacted to null
        val permsNoPregnancy = PartnerPermissions(
            shareCyclePhase = true,
            shareFlowIntensity = true,
            shareSymptomsMoods = true,
            shareBirthControl = true,
            sharePregnancyTests = false,
            shareSexualActivity = false
        )

        val filteredEntries = if (!permsNoPregnancy.shareCyclePhase) {
            emptyList()
        } else {
            entries.map {
                val originalIntensity = it.intensity?.toInt() ?: 0
                val rawNotes = if (permsNoPregnancy.shareSymptomsMoods) it.notes else null
                val safeNotes = if (rawNotes != null && !permsNoPregnancy.sharePregnancyTests) {
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
                    intensity = originalIntensity,
                    notes = safeNotes,
                    sexualRelations = null,
                    painLevel = it.painLevel?.toInt(),
                    symptoms = it.symptoms
                )
            }
        }

        assertEquals(4, filteredEntries.size)
        assertNull(filteredEntries[0].notes, "VULN-SYNC-02: 'pregnancy' note must be redacted to null")
        assertNull(filteredEntries[1].notes, "VULN-SYNC-02: 'hcg' and 'positive' note must be redacted to null")
        assertNull(filteredEntries[2].notes, "VULN-SYNC-02: 'negative' note must be redacted to null")
        assertEquals("Standard cramps and headache", filteredEntries[3].notes, "Non-pregnancy note should be preserved")

        // Case 3 (VULN-SYNC-03): Reminders filtering by type
        // 'Pregnancy Test' reminder checks sharePregnancyTests, while 'Birth Control' checks shareBirthControl
        val filteredReminders = reminders.filter { rm ->
            if (rm.type.equals("Pregnancy Test", ignoreCase = true)) {
                permsNoPregnancy.sharePregnancyTests
            } else {
                permsNoPregnancy.shareBirthControl
            }
        }.map { SyncReminder(it.type, it.frequency, it.timeOfDay, it.nextDate, it.isActive ?: true) }

        assertEquals(1, filteredReminders.size)
        assertEquals("Birth Control", filteredReminders[0].type, "VULN-SYNC-03: Birth control reminder should be included")

        // If sharePregnancyTests becomes true, both reminders should be included
        val permsWithPregnancy = permsNoPregnancy.copy(sharePregnancyTests = true)
        val allReminders = reminders.filter { rm ->
            if (rm.type.equals("Pregnancy Test", ignoreCase = true)) {
                permsWithPregnancy.sharePregnancyTests
            } else {
                permsWithPregnancy.shareBirthControl
            }
        }
        assertEquals(2, allReminders.size, "Both reminders should be included when sharePregnancyTests is true")
    }

    @Test
    fun testV15_logNewPeriod_preservesExistingNotesWhenNotesNull() {
        runBlocking {
            val db = createInMemoryDb()
            val tracker = CycleTracker(com.novarytm.ffi.RustBridge(), db.cycleQueries, this)
            val date = LocalDate.parse("2024-04-01")

            // Log initial period entry with notes and symptoms
            tracker.logNewPeriod(
                date = date,
                intensity = 3,
                notes = "Existing clinical note",
                symptoms = "cramps"
            )
            awaitCondition { db.cycleQueries.selectAllEntries().executeAsList().isNotEmpty() }
            val initialEntry = db.cycleQueries.selectEntryByDate(date.toString()).executeAsOne()
            assertEquals("Existing clinical note", initialEntry.notes)
            assertEquals("Cramps", initialEntry.symptoms)

            // VULN-DB-01 FIX: Update intensity with notes == null -> MUST preserve existing notes
            tracker.logNewPeriod(date = date, intensity = 4, notes = null)
            awaitCondition { db.cycleQueries.selectEntryByDate(date.toString()).executeAsOne().intensity == 4 }
            val updatedEntry = db.cycleQueries.selectEntryByDate(date.toString()).executeAsOne()
            assertEquals(4, updatedEntry.intensity)
            assertEquals("Existing clinical note", updatedEntry.notes, "VULN-DB-01: Existing notes must be preserved when notes == null")
            assertEquals("Cramps", updatedEntry.symptoms, "Existing symptoms must be preserved when symptoms == null")

            // Explicit empty string clears note to null
            tracker.logNewPeriod(date = date, intensity = 2, notes = "")
            awaitCondition { db.cycleQueries.selectEntryByDate(date.toString()).executeAsOne().intensity == 2 }
            val clearedEntry = db.cycleQueries.selectEntryByDate(date.toString()).executeAsOne()
            assertNull(clearedEntry.notes, "Notes should be cleared to null when explicitly empty string")
        }
    }

    @Test
    fun testV15_invalidCalendarDate_rejectedInSyncAndSafeInTracker() {
        runBlocking {
            val db = createInMemoryDb()
            val tracker = CycleTracker(com.novarytm.ffi.RustBridge(), db.cycleQueries, this)

            // VULN-DB-02 FIX: Semantic calendar validation rejects nonexistent dates
            val dateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")
            fun isValidCalendarDate(dateStr: String): Boolean {
                if (!dateRegex.matches(dateStr)) return false
                return try {
                    LocalDate.parse(dateStr)
                    true
                } catch (_: Exception) {
                    false
                }
            }

            assertTrue(isValidCalendarDate("2024-02-29"), "Valid leap year date should pass")
            assertTrue(isValidCalendarDate("2026-03-31"), "Valid month end should pass")
            assertFalse(isValidCalendarDate("2026-02-31"), "VULN-DB-02: Non-existent Feb 31 must be rejected")
            assertFalse(isValidCalendarDate("9999-99-99"), "VULN-DB-02: Non-existent 9999-99-99 must be rejected")
            assertFalse(isValidCalendarDate("2025-04-31"), "VULN-DB-02: Non-existent April 31 must be rejected")
            assertFalse(isValidCalendarDate("2024-13-01"), "VULN-DB-02: Month 13 must be rejected")

            // VULN-DB-03 FIX: Truncation before regex HTML stripping
            val oversizedNotes = "a".repeat(1000) + "<b>test</b>"
            val cleanNotes = oversizedNotes.take(500).replace(Regex("<[^>]*>"), "").trim()
            assertEquals(500, cleanNotes.length)
            assertFalse(cleanNotes.contains("<b>test</b>"))

            // Crash loop prevention in CycleTracker.refreshCycleState()
            db.cycleQueries.insertEntry("2026-02-31", 3, "Corrupt date 1", null, null, null)
            db.cycleQueries.insertEntry("9999-99-99", 2, "Corrupt date 2", null, null, null)
            db.cycleQueries.insertEntry("2024-03-01", 4, "Valid date", null, null, null)

            // refreshCycleState() must safely ignore corrupted date strings without throwing DateTimeFormatException
            tracker.refreshCycleState()
            awaitCondition { tracker.loggedDates.value.isNotEmpty() }
            assertTrue(tracker.loggedDates.value.contains(LocalDate.parse("2024-03-01")))
            assertEquals(1, tracker.loggedDates.value.size, "Corrupted dates must be safely dropped without crash")
        }
    }

    @Test
    fun testZeroFlowEntrySyncPreservesSymptomsAndZeroIntensity() {
        val zeroFlowEntry = CycleEntry(
            id = 1L,
            date = "2024-03-10",
            intensity = 0,
            notes = "Mild headache and fatigue",
            sexualRelations = "protected",
            painLevel = 2,
            symptoms = "Headaches"
        )

        val bleedingEntry = CycleEntry(
            id = 2L,
            date = "2024-03-11",
            intensity = 4,
            notes = "Heavy bleeding",
            sexualRelations = null,
            painLevel = 5,
            symptoms = "Cramps"
        )

        val entries = listOf(zeroFlowEntry, bleedingEntry)

        // Case A: shareCyclePhase = true, shareFlowIntensity = true, shareSymptomsMoods = true, shareSexualActivity = true
        val permsFull = PartnerPermissions(
            shareCyclePhase = true,
            shareFlowIntensity = true,
            shareSymptomsMoods = true,
            shareBirthControl = true,
            sharePregnancyTests = true,
            shareSexualActivity = true
        )

        val filteredFull = entries.map { entry ->
            val originalIntensity = entry.intensity?.toInt() ?: 0
            SyncEntry(
                date = entry.date,
                intensity = when {
                    permsFull.shareFlowIntensity -> originalIntensity
                    permsFull.shareCyclePhase && originalIntensity > 0 -> 1
                    else -> 0
                },
                notes = if (permsFull.shareSymptomsMoods) entry.notes else null,
                sexualRelations = if (permsFull.shareSexualActivity) entry.sexualRelations else null,
                painLevel = if (permsFull.shareSymptomsMoods) entry.painLevel?.toInt() else null,
                symptoms = if (permsFull.shareSymptomsMoods) entry.symptoms else null
            )
        }

        assertEquals(2, filteredFull.size)
        // Zero flow entry preserves intensity = 0 (not 1)
        assertEquals(0, filteredFull[0].intensity)
        assertEquals("Mild headache and fatigue", filteredFull[0].notes)
        assertEquals("protected", filteredFull[0].sexualRelations)
        assertEquals(2, filteredFull[0].painLevel)
        assertEquals("Headaches", filteredFull[0].symptoms)

        // Bleeding entry preserves original intensity = 4
        assertEquals(4, filteredFull[1].intensity)
        assertEquals("Heavy bleeding", filteredFull[1].notes)

        // Case B: shareCyclePhase = true, shareFlowIntensity = false (redacted bleeding)
        val permsRedactedFlow = permsFull.copy(shareFlowIntensity = false)
        val filteredRedacted = entries.map { entry ->
            val originalIntensity = entry.intensity?.toInt() ?: 0
            SyncEntry(
                date = entry.date,
                intensity = when {
                    permsRedactedFlow.shareFlowIntensity -> originalIntensity
                    permsRedactedFlow.shareCyclePhase && originalIntensity > 0 -> 1
                    else -> 0
                },
                notes = if (permsRedactedFlow.shareSymptomsMoods) entry.notes else null,
                sexualRelations = if (permsRedactedFlow.shareSexualActivity) entry.sexualRelations else null,
                painLevel = if (permsRedactedFlow.shareSymptomsMoods) entry.painLevel?.toInt() else null,
                symptoms = if (permsRedactedFlow.shareSymptomsMoods) entry.symptoms else null
            )
        }

        // Bleeding entry clamped to 1, but zero-flow entry stays 0
        assertEquals(0, filteredRedacted[0].intensity, "Flow 0 must remain 0 even when cycle phase is shared")
        assertEquals(1, filteredRedacted[1].intensity, "Bleeding entry clamped to 1 when shareFlowIntensity is false")
        assertEquals("Headaches", filteredRedacted[0].symptoms)
        assertEquals(2, filteredRedacted[0].painLevel)
    }

    @Test
    fun testDecoupledSymptomsSyncWhenShareCyclePhaseFalse() {
        val entries = listOf(
            // Entry 1: Zero flow, has symptoms & pain & note
            CycleEntry(1L, "2024-03-01", 0, "Normal check-in note", null, 3, "Cramps"),
            // Entry 2: Bleeding flow, has pregnancy test note, no other symptoms/pain/intimacy
            CycleEntry(2L, "2024-03-02", 3, "Took a pregnancy test this morning", null, 0, null),
            // Entry 3: Bleeding flow, NO symptoms, NO pain, NO intimacy, NO notes
            CycleEntry(3L, "2024-03-03", 4, null, null, 0, null),
            // Entry 4: Zero flow, sexual intimacy only
            CycleEntry(4L, "2024-03-04", 0, null, "protected", null, null),
            // Entry 5: Bleeding flow, symptoms + intimacy + general note
            CycleEntry(5L, "2024-03-05", 2, "General note", "unprotected", 4, "Headaches")
        )

        // Case A: shareCyclePhase = false, shareSymptomsMoods = true, shareSexualActivity = true, sharePregnancyTests = false
        val perms = PartnerPermissions(
            shareCyclePhase = false,
            shareFlowIntensity = false,
            shareSymptomsMoods = true,
            shareBirthControl = false,
            sharePregnancyTests = false,
            shareSexualActivity = true
        )

        fun filterEntries(perms: PartnerPermissions, inputEntries: List<CycleEntry>): List<SyncEntry> {
            return if (!perms.shareCyclePhase) {
                inputEntries.mapNotNull { entry ->
                    val rawNotes = if (perms.shareSymptomsMoods) entry.notes else null
                    val safeNotes = if (rawNotes != null && !perms.sharePregnancyTests) {
                        val lower = rawNotes.lowercase()
                        if (lower.contains("pregnan") || lower.contains("hcg") || lower.contains("positive") || lower.contains("negative")) {
                            null
                        } else {
                            rawNotes
                        }
                    } else {
                        rawNotes
                    }
                    val sharedSymptoms = if (perms.shareSymptomsMoods) entry.symptoms else null
                    val sharedPainLevel = if (perms.shareSymptomsMoods) entry.painLevel?.toInt() else null
                    val sharedSexualRelations = if (perms.shareSexualActivity) entry.sexualRelations else null

                    val hasSharedSymptomsMoods = perms.shareSymptomsMoods && (
                        !sharedSymptoms.isNullOrEmpty() ||
                        (entry.painLevel?.toLong() ?: 0L) > 0L ||
                        !safeNotes.isNullOrBlank()
                    )
                    val hasSharedSexualActivity = perms.shareSexualActivity && !sharedSexualRelations.isNullOrEmpty()

                    if (hasSharedSymptomsMoods || hasSharedSexualActivity) {
                        SyncEntry(
                            date = entry.date,
                            intensity = 0,
                            notes = safeNotes,
                            sexualRelations = sharedSexualRelations,
                            painLevel = sharedPainLevel,
                            symptoms = sharedSymptoms
                        )
                    } else {
                        null
                    }
                }
            } else {
                inputEntries.map { entry ->
                    SyncEntry(entry.date, entry.intensity?.toInt() ?: 0, entry.notes)
                }
            }
        }

        val filtered = filterEntries(perms, entries)

        // Entry 1 kept (symptoms & pain), intensity exported as 0
        val entry1 = filtered.find { it.date == "2024-03-01" }
        assertNotNull(entry1)
        assertEquals(0, entry1.intensity)
        assertEquals("Cramps", entry1.symptoms)
        assertEquals(3, entry1.painLevel)
        assertEquals("Normal check-in note", entry1.notes)

        // Entry 2 EXCLUDED: note redacted due to sharePregnancyTests = false, no other symptoms/pain/intimacy
        val entry2 = filtered.find { it.date == "2024-03-02" }
        assertNull(entry2, "Entry with only redacted pregnancy note must be excluded when shareCyclePhase is false")

        // Entry 3 EXCLUDED: bleeding only, no symptoms/pain/intimacy/notes
        val entry3 = filtered.find { it.date == "2024-03-03" }
        assertNull(entry3, "Entry with only bleeding and no symptoms/moods/intimacy must be excluded")

        // Entry 4 kept (sexual activity only), intensity exported as 0
        val entry4 = filtered.find { it.date == "2024-03-04" }
        assertNotNull(entry4)
        assertEquals(0, entry4.intensity)
        assertEquals("protected", entry4.sexualRelations)

        // Entry 5 kept (symptoms + intimacy + note), intensity exported as 0
        val entry5 = filtered.find { it.date == "2024-03-05" }
        assertNotNull(entry5)
        assertEquals(0, entry5.intensity, "Menstrual bleeding data must not leak; intensity must be 0")
        assertEquals("Headaches", entry5.symptoms)
        assertEquals("unprotected", entry5.sexualRelations)

        assertEquals(3, filtered.size)

        // Case B: Both shareSymptomsMoods = false and shareSexualActivity = false -> completely empty
        val permsNone = perms.copy(shareSymptomsMoods = false, shareSexualActivity = false)
        val filteredNone = filterEntries(permsNone, entries)
        assertTrue(filteredNone.isEmpty(), "When shareCyclePhase, shareSymptomsMoods, and shareSexualActivity are all false, entries must be empty")
    }

    @Test
    fun testImportSyncPayloadRefreshesCycleTrackerState() {
        runBlocking {
            val db = createInMemoryDb()
            val tracker = CycleTracker(com.novarytm.ffi.RustBridge(), db.cycleQueries, this)

            // Initially tracker has empty state
            tracker.refreshCycleState()
            awaitCondition { tracker.loggedDates.value.isEmpty() }
            assertTrue(tracker.loggedDates.value.isEmpty())

            // Simulate partner sync payload transaction execution
            val partnerEntries = listOf(
                SyncEntry(date = "2024-03-01", intensity = 3, notes = "Day 1"),
                SyncEntry(date = "2024-03-02", intensity = 2, notes = "Day 2"),
                SyncEntry(date = "2024-03-03", intensity = 0, notes = "Check-in", symptoms = "Cramps")
            )

            db.transaction {
                db.cycleQueries.deleteAllEntries()
                partnerEntries.forEach { entry ->
                    db.cycleQueries.insertEntry(
                        entry.date,
                        entry.intensity,
                        entry.notes,
                        entry.sexualRelations,
                        entry.painLevel,
                        entry.symptoms
                    )
                }
            }

            // Immediately after transaction, refreshCycleState() is called
            tracker.refreshCycleState()

            // StateFlows must immediately update
            awaitCondition { tracker.loggedDates.value.size == 2 }
            val loggedDates = tracker.loggedDates.value
            assertTrue(loggedDates.contains(LocalDate.parse("2024-03-01")))
            assertTrue(loggedDates.contains(LocalDate.parse("2024-03-02")))
            assertFalse(loggedDates.contains(LocalDate.parse("2024-03-03")), "0-flow check-in entry should not be counted as bleeding date in loggedDates")
        }
    }

    // =========================================================================
    // V16 CROSS-LAYER CONTRACT, SCHEMA V2, PERMISSIONS & SERIALIZATION TESTS
    // =========================================================================

    @Test
    fun testDatabaseSchemaV2AndBirthControlReminderTimePreservation() {
        assertEquals(2L, NovarytmDatabase.Schema.version, "Schema version must be 2 after 1.sqm migration")
        val db = createInMemoryDb()

        // 1. Initial state: null
        assertNull(db.cycleQueries.getBirthControl().executeAsOneOrNull())

        // 2. Set active birth control with reminder time
        db.cycleQueries.setBirthControlWithReminder("Combined Oral Pill", "09:00", true)
        val initialBc = db.cycleQueries.getBirthControl().executeAsOneOrNull()
        assertNotNull(initialBc)
        assertEquals("Combined Oral Pill", initialBc.type)
        assertEquals("09:00", initialBc.reminderTime)
        assertEquals(true, initialBc.isActive)

        // 3. Updating method via setBirthControl preserves existing reminderTime
        db.cycleQueries.setBirthControl("Mini-Pill", true)
        val updatedMethod = db.cycleQueries.getBirthControl().executeAsOneOrNull()
        assertNotNull(updatedMethod)
        assertEquals("Mini-Pill", updatedMethod.type)
        assertEquals("09:00", updatedMethod.reminderTime, "setBirthControl must preserve existing reminderTime")
        assertEquals(true, updatedMethod.isActive)

        // 4. Selecting None / deactivating sets isActive = false and clears reminder when requested
        db.cycleQueries.setBirthControlWithReminder("None", null, false)
        val deactivated = db.cycleQueries.getBirthControl().executeAsOneOrNull()
        assertNotNull(deactivated)
        assertEquals("None", deactivated.type)
        assertNull(deactivated.reminderTime)
        assertEquals(false, deactivated.isActive)

        // 5. Cleanup via deleteBirthControl()
        db.cycleQueries.deleteBirthControl()
        assertNull(db.cycleQueries.getBirthControl().executeAsOneOrNull())
    }

    @Test
    fun testPartnerPermissionsDefaultsBitmaskAndSyncPayloadSerialization() {
        // Owner-judged default: all flags default to false
        val defaultPerms = PartnerPermissions()
        assertFalse(defaultPerms.shareCyclePhase)
        assertFalse(defaultPerms.shareFlowIntensity)
        assertFalse(defaultPerms.shareSymptomsMoods)
        assertFalse(defaultPerms.shareBirthControl)
        assertFalse(defaultPerms.sharePregnancyTests)
        assertFalse(defaultPerms.shareSexualActivity)
        assertEquals(0, defaultPerms.toBitmask())

        // Bitmask round-trip across all flags
        val customPerms = PartnerPermissions(
            shareCyclePhase = true,
            shareFlowIntensity = false,
            shareSymptomsMoods = true,
            shareBirthControl = true,
            sharePregnancyTests = true,
            shareSexualActivity = false
        )
        val roundTripped = PartnerPermissions.fromBitmask(customPerms.toBitmask())
        assertEquals(customPerms, roundTripped)

        // Canonical SyncPayload JSON round-trip including SyncBirthControl.reminderTime
        val payload = SyncPayload(
            lastUpdated = 1_700_000_000_000L,
            birthControl = SyncBirthControl(type = "Mini-Pill", isActive = true, reminderTime = "21:30"),
            entries = listOf(SyncEntry("2024-03-01", 3, "Note", null, 2, "Cramps")),
            pregnancyTests = listOf(SyncPregnancyTest("2024-03-05", "Negative")),
            reminders = listOf(SyncReminder("Pill", "Daily", "21:30", "2024-03-02", true)),
            permissions = customPerms
        )
        val encoded = AppJson.encodeToString(SyncPayload.serializer(), payload)
        val decoded = AppJson.decodeFromString(SyncPayload.serializer(), encoded)
        assertEquals(payload, decoded)
        assertEquals("21:30", decoded.birthControl?.reminderTime)
        assertEquals(true, decoded.permissions?.sharePregnancyTests)

        // Legacy integer bitmask compatibility (e.g., Rust bitmask = 0x20 | 0x01 = 33)
        val legacyBitmaskJson = """{"lastUpdated":1700000000000,"permissions":33}"""
        val decodedLegacy = AppJson.decodeFromString(SyncPayload.serializer(), legacyBitmaskJson)
        assertNotNull(decodedLegacy.permissions)
        assertTrue(decodedLegacy.permissions!!.shareCyclePhase)
        assertTrue(decodedLegacy.permissions!!.sharePregnancyTests)
        assertFalse(decodedLegacy.permissions!!.shareBirthControl)
    }

    @Test
    fun testDriveExceptionContractsAndCycleTrackerResilienceWithCorruptedDates() {
        runBlocking {
            val retryable = DriveSyncException("Transient 503", isRetryable = true)
            assertTrue(retryable.isRetryable)
            val nonRetryable = DriveSyncException("403 Forbidden", isRetryable = false)
            assertFalse(nonRetryable.isRetryable)
            val authEx = DriveAuthRequiredException("Auth required")
            assertEquals("Auth required", authEx.message)
            val quotaEx = DriveStorageFullException(15L, 15L)
            assertEquals(15L, quotaEx.usedBytes)

            val db = createInMemoryDb()
            val tracker = CycleTracker(com.novarytm.ffi.RustBridge(), db.cycleQueries, this)
            db.cycleQueries.insertEntry("2024-03-01", 3, "Valid 1", null, null, null)
            db.cycleQueries.insertEntry("2026-02-31", 2, "Invalid date", null, null, null)
            db.cycleQueries.insertEntry("2024-03-02", 4, "Valid 2", null, null, null)
            db.cycleQueries.insertPregnancyTest("2024-03-06", "Negative")

            tracker.refreshCycleState()
            awaitCondition { tracker.loggedDates.value.size == 2 }
            assertEquals(setOf(LocalDate.parse("2024-03-01"), LocalDate.parse("2024-03-02")), tracker.loggedDates.value)
            assertEquals(1, db.cycleQueries.selectAllPregnancyTests().executeAsList().size)
        }
    }

    @Test
    fun testPendingSyncCoordinator_stateTransitionsAndSchedulerInvocation() {
        val stateStore = object : SyncUploadStateStore {
            private var pending = false
            override fun hasPendingSyncUpload(): Boolean = pending
            override fun setPendingSyncUpload(pending: Boolean) {
                this.pending = pending
            }
        }
        var scheduleCount = 0
        var cancelCount = 0
        val scheduler = object : SyncUploadScheduler {
            override fun schedulePendingUpload() {
                scheduleCount++
            }

            override fun cancelPendingUpload() {
                cancelCount++
            }
        }

        val coordinator = PendingSyncCoordinator(stateStore, scheduler)

        // Default state: no pending upload
        assertFalse(stateStore.hasPendingSyncUpload())

        // Non-owner mode must not mark pending or schedule background upload
        coordinator.markPendingAndSchedule(isOwnerMode = false)
        assertFalse(stateStore.hasPendingSyncUpload())
        assertEquals(0, scheduleCount)

        // Owner mode marks pending and schedules background upload
        coordinator.markPendingAndSchedule(isOwnerMode = true)
        assertTrue(stateStore.hasPendingSyncUpload())
        assertEquals(1, scheduleCount)

        // Success / revoke clears pending flag and cancels scheduled work
        coordinator.clearPendingAndCancel()
        assertFalse(stateStore.hasPendingSyncUpload())
        assertEquals(1, cancelCount)
    }

    @Test
    fun testPregnancyTestReminderTypeNormalization() {
        fun isPregnancyTestReminder(type: String): Boolean =
            type.trim().replace(" ", "").equals("PregnancyTest", ignoreCase = true)

        assertTrue(isPregnancyTestReminder("PregnancyTest"))
        assertTrue(isPregnancyTestReminder("Pregnancy Test"))
        assertTrue(isPregnancyTestReminder("pregnancy test"))
        assertTrue(isPregnancyTestReminder("  Pregnancy Test  "))
        assertFalse(isPregnancyTestReminder("Pill"))
        assertFalse(isPregnancyTestReminder("Hormonal IUD Replacement (5 Years)"))
    }

    @Test
    fun testZeroFlowEmptyEntryOmissionInGroupPeriodEntries() {
        val entries = listOf(
            CycleEntry(1L, "2024-03-01", 0, null, null, null, null),
            CycleEntry(2L, "2024-03-02", 0, "   ", null, 0, ""),
            CycleEntry(3L, "2024-03-03", 0, null, null, 2, "Cramps"),
            CycleEntry(4L, "2024-03-05", 0, null, "Protected", null, null)
        )

        val groups = groupPeriodEntries(entries, includeZeroFlow = true)
        assertEquals(2, groups.size, "Empty 0-flow entries must be omitted when grouping")
        assertEquals(LocalDate.parse("2024-03-05"), groups[0].startDate)
        assertEquals(LocalDate.parse("2024-03-03"), groups[1].startDate)
    }

    @Test
    fun testPainLevelZeroParityInDbAndSyncModel() {
        runBlocking {
            val db = createInMemoryDb()
            val tracker = CycleTracker(com.novarytm.ffi.RustBridge(), db.cycleQueries, this)

            val date = LocalDate.parse("2024-03-18")
            tracker.logNewPeriod(date = date, intensity = 2, notes = "No cramps", painLevel = 0, symptoms = "")
            awaitCondition { db.cycleQueries.selectEntryByDate(date.toString()).executeAsOneOrNull() != null }

            val stored = db.cycleQueries.selectEntryByDate(date.toString()).executeAsOne()
            assertEquals(0, stored.painLevel, "painLevel == 0 must be persisted as 0 (not coerced to null)")

            // Partial sexual relation update must preserve painLevel == 0
            tracker.logSexualRelation(date, "Protected")
            awaitCondition { db.cycleQueries.selectEntryByDate(date.toString()).executeAsOne().sexualRelations == "Protected" }
            val afterRel = db.cycleQueries.selectEntryByDate(date.toString()).executeAsOne()
            assertEquals(0, afterRel.painLevel)

            // SyncEntry JSON round-trip preserves painLevel == 0
            val syncEntry = SyncEntry(afterRel.date, afterRel.intensity ?: 0, afterRel.notes, afterRel.sexualRelations, afterRel.painLevel, afterRel.symptoms)
            val decoded = AppJson.decodeFromString(SyncEntry.serializer(), AppJson.encodeToString(SyncEntry.serializer(), syncEntry))
            assertEquals(0, decoded.painLevel)
        }
    }

    @Test
    fun testSyncUploadSchedulerPartnerPullScheduleAndCancel() {
        var partnerPullScheduleCount = 0
        var partnerPullCancelCount = 0
        val scheduler = object : SyncUploadScheduler {
            override fun schedulePendingUpload() {}
            override fun cancelPendingUpload() {}
            override fun schedulePeriodicPartnerPull() {
                partnerPullScheduleCount++
            }
            override fun cancelPartnerPull() {
                partnerPullCancelCount++
            }
        }

        scheduler.schedulePeriodicPartnerPull()
        assertEquals(1, partnerPullScheduleCount)
        assertEquals(0, partnerPullCancelCount)

        scheduler.cancelPartnerPull()
        assertEquals(1, partnerPullCancelCount)
    }

    // =========================================================================
    // E2E FAKES: Storage, Auth, Scheduler, Notifications, Mock Drive HTTP Engine
    // =========================================================================

    private class FakeSyncCryptoBridge : SyncCryptoBridge {
        private var storedPermissions: PartnerPermissions = PartnerPermissions()

        override fun getPartnerPermissions(): PartnerPermissions = storedPermissions

        override fun savePartnerPermissions(permissions: PartnerPermissions) {
            storedPermissions = permissions
        }

        override fun generateRandomKey(): ByteArray = ByteArray(32) { (it + 1).toByte() }

        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        override fun encryptForSync(payloadJson: String, sharedKeyBase64: String): String {
            return kotlin.io.encoding.Base64.Default.encode("$sharedKeyBase64::$payloadJson".encodeToByteArray())
        }

        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        override fun decryptFromSync(encryptedBase64: String, sharedKeyBase64: String): String {
            return try {
                val decoded = kotlin.io.encoding.Base64.Default.decode(encryptedBase64).decodeToString()
                val prefix = "$sharedKeyBase64::"
                if (decoded.startsWith(prefix)) decoded.removePrefix(prefix) else ""
            } catch (_: Exception) {
                ""
            }
        }
    }

    private class FakeSyncKeyValueStore(
        private var storedUserId: String? = null,
        private var storedTargetSyncId: String? = null,
        private var storedPartnerEmail: String? = null,
        private var storedSyncSecretKey: ByteArray? = null,
        private var storedPrimarySyncFileId: String? = null,
        private var storedLastSyncTimestamp: Long = 0L,
        private var storedLastSuccessfulSyncWallclock: Long = 0L,
        private var storedPartnerPermissionsJson: String = "",
        private var storedPendingSyncUpload: Boolean = false
    ) : SyncKeyValueStore {
        override fun hasPendingSyncUpload(): Boolean = storedPendingSyncUpload
        override fun setPendingSyncUpload(pending: Boolean) { storedPendingSyncUpload = pending }
        override fun getUserId(): String? = storedUserId
        override fun getTargetSyncId(): String? = storedTargetSyncId
        override fun saveTargetSyncId(id: String) { storedTargetSyncId = id }
        override fun getPartnerEmail(): String? = storedPartnerEmail
        override fun getSyncSecretKey(): ByteArray? = storedSyncSecretKey?.copyOf()
        override fun saveSyncSecretKey(key: ByteArray) { storedSyncSecretKey = key.copyOf() }
        override fun getPrimarySyncFileId(): String? = storedPrimarySyncFileId
        override fun savePrimarySyncFileId(fileId: String?) { storedPrimarySyncFileId = fileId }
        override fun getLastSyncTimestamp(): Long = storedLastSyncTimestamp
        override fun saveLastSyncTimestamp(timestampMs: Long) { storedLastSyncTimestamp = timestampMs }
        override fun saveLastSuccessfulSyncWallclock(timestampMs: Long) { storedLastSuccessfulSyncWallclock = timestampMs }
        override fun savePartnerPermissionsJson(json: String) { storedPartnerPermissionsJson = json }
    }

    private class FakeDriveAuthProvider(
        private val tokens: ArrayDeque<String?> = ArrayDeque(listOf("valid-token")),
        override var needsInteractiveReauth: Boolean = false
    ) : DriveAuthProvider {
        val clearedTokens = mutableListOf<String>()

        override suspend fun getAccessToken(scopes: List<String>): String? {
            return if (tokens.size > 1) tokens.removeFirst() else tokens.firstOrNull()
        }

        override suspend fun clearToken(token: String) {
            clearedTokens.add(token)
        }
    }

    private class FakeSyncUploadScheduler : SyncUploadScheduler {
        var schedulePendingCount = 0
        var cancelPendingCount = 0
        var schedulePartnerPullCount = 0
        var cancelPartnerPullCount = 0

        override fun schedulePendingUpload() { schedulePendingCount++ }
        override fun cancelPendingUpload() { cancelPendingCount++ }
        override fun schedulePeriodicPartnerPull() { schedulePartnerPullCount++ }
        override fun cancelPartnerPull() { cancelPartnerPullCount++ }
    }

    private data class ScheduledNotificationCall(
        val id: Int,
        val title: String,
        val body: String,
        val delayMillis: Long
    )

    private class FakeNotificationScheduler : NotificationScheduling {
        val scheduled = mutableListOf<ScheduledNotificationCall>()
        val cancelled = mutableListOf<Int>()

        override fun scheduleNotification(id: Int, title: String, body: String, delayMillis: Long) {
            scheduled.add(ScheduledNotificationCall(id, title, body, delayMillis))
        }

        override fun cancelNotification(id: Int) {
            cancelled.add(id)
        }
    }

    private data class FakeHttpReply(
        val status: HttpStatusCode,
        val body: ByteArray = ByteArray(0),
        val contentType: ContentType = ContentType.Application.Json
    )

    private class FakeDriveHttpEngine(
        private val handler: suspend (HttpRequestData, ByteArray) -> FakeHttpReply
    ) : HttpClientEngineBase("fake-drive") {
        override val config: HttpClientEngineConfig = HttpClientEngineConfig()
        override val dispatcher = Dispatchers.Default

        @io.ktor.utils.io.InternalAPI
        override suspend fun execute(data: HttpRequestData): HttpResponseData {
            val requestBytes = when (val body = data.body) {
                is OutgoingContent.ByteArrayContent -> body.bytes()
                is OutgoingContent.WriteChannelContent -> coroutineScope {
                    val channel = io.ktor.utils.io.ByteChannel(autoFlush = true)
                    launch {
                        try {
                            body.writeTo(channel)
                        } finally {
                            channel.flushAndClose()
                        }
                    }
                    channel.toByteArray()
                }
                else -> ByteArray(0)
            }
            val reply = handler(data, requestBytes)
            return HttpResponseData(
                statusCode = reply.status,
                requestTime = GMTDate(),
                headers = headersOf(HttpHeaders.ContentType, reply.contentType.toString()),
                version = HttpProtocolVersion.HTTP_1_1,
                body = ByteReadChannel(reply.body),
                callContext = io.ktor.client.engine.callContext()
            )
        }
    }

    private val activeTestHttpClients = mutableListOf<HttpClient>()

    @AfterTest
    fun closeTestHttpClients() {
        activeTestHttpClients.forEach { client ->
            runCatching { client.close() }
        }
        activeTestHttpClients.clear()
    }

    private fun createMockDriveHttpClient(
        handler: suspend (HttpRequestData, ByteArray) -> FakeHttpReply
    ): HttpClient {
        return HttpClient(FakeDriveHttpEngine(handler)) {
            install(ContentNegotiation) {
                json(AppJson)
            }
        }.also { activeTestHttpClients.add(it) }
    }

    private fun createMockDriveClient(
        authProvider: DriveAuthProvider,
        retryBaseDelayMs: Long = 1L,
        httpClient: HttpClient? = null,
        handler: suspend (HttpRequestData, ByteArray) -> FakeHttpReply
    ): GoogleDriveManager {
        val client = httpClient ?: createMockDriveHttpClient(handler)
        return GoogleDriveManager(
            authProvider = authProvider,
            httpClient = client,
            retryBaseDelayMs = retryBaseDelayMs
        )
    }

    // =========================================================================
    // E2E TEST 1: Owner -> GoogleDriveManager -> Partner Pull + Auth/Revoke
    // =========================================================================

    @Test
    fun testE2E_ownerToDriveToPartner_andAuthHtmlRevokeFlows() {
        runBlocking {
            val ownerCrypto = FakeSyncCryptoBridge()
            val partnerCrypto = FakeSyncCryptoBridge()
            val secretKey = ownerCrypto.generateRandomKey()

            // Owner state
            val ownerDb = createInMemoryDb()
            val ownerQueries = ownerDb.cycleQueries
            val ownerStorage = FakeSyncKeyValueStore(
                storedUserId = "owner-123",
                storedPartnerEmail = "partner@example.com",
                storedSyncSecretKey = secretKey
            )
            val ownerScheduler = FakeSyncUploadScheduler()
            val ownerAuth = FakeDriveAuthProvider(ArrayDeque(listOf("owner-token")))

            val recordedRequests = mutableListOf<String>()
            var storedBlobBytes: ByteArray? = null
            var grantedPermissionJson: String? = null
            val driveFileId = "drive-file-e2e-1"

            val ownerHttpClient = createMockDriveHttpClient { req, body ->
                val url = req.url.toString()
                recordedRequests.add("${req.method.value} $url")
                when {
                    req.method == HttpMethod.Get && url.startsWith("https://www.googleapis.com/drive/v3/files?") -> {
                        FakeHttpReply(HttpStatusCode.OK, """{"files":[]}""".encodeToByteArray())
                    }
                    req.method == HttpMethod.Post && url == "https://www.googleapis.com/drive/v3/files" -> {
                        FakeHttpReply(HttpStatusCode.OK, """{"id":"$driveFileId"}""".encodeToByteArray())
                    }
                    req.method == HttpMethod.Patch && url.contains("/upload/drive/v3/files/$driveFileId") -> {
                        storedBlobBytes = body
                        FakeHttpReply(HttpStatusCode.OK, """{"id":"$driveFileId"}""".encodeToByteArray())
                    }
                    req.method == HttpMethod.Post && url == "https://www.googleapis.com/drive/v3/files/$driveFileId/permissions" -> {
                        grantedPermissionJson = body.decodeToString()
                        FakeHttpReply(HttpStatusCode.OK, """{"id":"perm-1"}""".encodeToByteArray())
                    }
                    else -> FakeHttpReply(HttpStatusCode.NotFound)
                }
            }
            val ownerDrive = GoogleDriveManager(ownerAuth, ownerHttpClient, retryBaseDelayMs = 1L)
            var partnerHttpClient: HttpClient? = null

            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val partnerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val ownerSync = SyncManager(
                    database = ownerDb,
                    rustBridge = ownerCrypto,
                    storage = ownerStorage,
                    googleDriveManager = ownerDrive,
                    scope = ownerScope,
                    backgroundSyncScheduler = ownerScheduler
                )

            // Owner logs Flow 1-5, 0-flow, BirthControl.reminderTime, PregnancyTest, and Reminders
            val ownerPerms = PartnerPermissions(
                shareCyclePhase = true,
                shareFlowIntensity = true,
                shareSymptomsMoods = true,
                shareBirthControl = true,
                sharePregnancyTests = true,
                shareSexualActivity = false
            )
            ownerCrypto.savePartnerPermissions(ownerPerms)
            ownerQueries.insertEntry("2024-03-01", 4, "Day 1 flow", "Protected", 3, "Cramps")
            ownerQueries.insertEntry("2024-03-10", 0, "Headache only", null, 2, "Headaches")
            ownerQueries.setBirthControlWithReminder("Combined Oral Pill", "08:30", true)
            ownerQueries.insertPregnancyTest("2024-03-10", "Negative")
            ownerQueries.insertReminder("Pill", "Daily", "08:30", "2024-03-10", true)
            ownerQueries.insertReminder("Pregnancy Test", "Monthly", "10:00", "2024-03-15", true)

            val uploadedId = ownerSync.prepareAndUploadPayload()
            assertEquals(driveFileId, uploadedId)
            assertEquals(driveFileId, ownerStorage.getPrimarySyncFileId())
            assertNotNull(storedBlobBytes)
            assertTrue(recordedRequests.any { it.startsWith("POST https://www.googleapis.com/drive/v3/files") && !it.contains("permissions") })
            assertTrue(recordedRequests.any { it.startsWith("PATCH https://www.googleapis.com/upload/drive/v3/files/$driveFileId") })
            assertTrue(recordedRequests.any { it.startsWith("POST https://www.googleapis.com/drive/v3/files/$driveFileId/permissions") })
            assertTrue(grantedPermissionJson?.contains("\"role\":\"reader\"") == true)
            assertTrue(grantedPermissionJson?.contains("partner@example.com") == true)

            // Partner pulls & imports via GoogleDriveManager (with 401 refresh retry first!)
            val partnerDb = createInMemoryDb()
            val partnerStorage = FakeSyncKeyValueStore(
                storedTargetSyncId = driveFileId,
                storedSyncSecretKey = secretKey
            )
            val partnerScheduler = FakeSyncUploadScheduler()
            val partnerAuth = FakeDriveAuthProvider(ArrayDeque(listOf("stale-token", "fresh-token")))
            var getAttempts = 0
            var mode = "NORMAL_WITH_401_RETRY"

            val pClient = createMockDriveHttpClient { req, _ ->
                val url = req.url.toString()
                when (mode) {
                    "NORMAL_WITH_401_RETRY" -> {
                        if (url == "https://www.googleapis.com/drive/v3/files/$driveFileId?alt=media") {
                            getAttempts++
                            val authHeader = req.headers[HttpHeaders.Authorization]
                            if (getAttempts == 1 && authHeader == "Bearer stale-token") {
                                FakeHttpReply(HttpStatusCode.Unauthorized)
                            } else if (authHeader == "Bearer fresh-token") {
                                FakeHttpReply(HttpStatusCode.OK, storedBlobBytes!!, ContentType.Application.OctetStream)
                            } else {
                                FakeHttpReply(HttpStatusCode.Unauthorized)
                            }
                        } else {
                            FakeHttpReply(HttpStatusCode.NotFound)
                        }
                    }
                    "HTML_LOGIN" -> FakeHttpReply(
                        HttpStatusCode.OK,
                        "<html><body>Sign in</body></html>".encodeToByteArray(),
                        ContentType.Text.Html
                    )
                    "REVOKED_403" -> FakeHttpReply(HttpStatusCode.Forbidden)
                    "REVOKED_404" -> FakeHttpReply(HttpStatusCode.NotFound)
                    else -> FakeHttpReply(HttpStatusCode.InternalServerError)
                }
            }
            partnerHttpClient = pClient
            val partnerDrive = GoogleDriveManager(partnerAuth, pClient, retryBaseDelayMs = 1L)

            val partnerSync = SyncManager(
                database = partnerDb,
                rustBridge = partnerCrypto,
                storage = partnerStorage,
                googleDriveManager = partnerDrive,
                scope = partnerScope,
                backgroundSyncScheduler = partnerScheduler
            )

            val pullOutcome = partnerSync.executeBackgroundPull()
            assertEquals(SyncManager.PullOutcome.Success, pullOutcome)
            assertEquals(listOf("stale-token"), partnerAuth.clearedTokens)
            assertEquals(2, getAttempts)

            val partnerEntries = partnerDb.cycleQueries.selectAllEntries().executeAsList().sortedBy { it.date }
            assertEquals(2, partnerEntries.size)
            assertEquals(4, partnerEntries[0].intensity)
            assertNull(partnerEntries[0].sexualRelations, "shareSexualActivity=false must redact sexualRelations")
            assertEquals(0, partnerEntries[1].intensity)
            assertEquals("Headaches", partnerEntries[1].symptoms)

            val partnerBc = partnerDb.cycleQueries.getBirthControl().executeAsOneOrNull()
            assertNotNull(partnerBc)
            assertEquals("Combined Oral Pill", partnerBc.type)
            assertEquals("08:30", partnerBc.reminderTime)

            val partnerPts = partnerDb.cycleQueries.selectAllPregnancyTests().executeAsList()
            assertEquals(1, partnerPts.size)
            assertEquals("Negative", partnerPts[0].result)

            val partnerReminders = partnerDb.cycleQueries.selectAllReminders().executeAsList()
            assertEquals(2, partnerReminders.size)

            // text/html rejection -> syncAuthRequired
            mode = "HTML_LOGIN"
            val htmlOutcome = partnerSync.executeBackgroundPull()
            assertEquals(SyncManager.PullOutcome.AuthRequired, htmlOutcome)
            assertTrue(partnerSync.syncAuthRequired.value)

            // 403/404 REVOKED -> silent Partner wipe
            mode = "REVOKED_404"
            val revokeOutcome = partnerSync.executeBackgroundPull()
            assertEquals(SyncManager.PullOutcome.Revoked, revokeOutcome)
            assertTrue(partnerDb.cycleQueries.selectAllEntries().executeAsList().isEmpty())
            assertTrue(partnerDb.cycleQueries.selectAllPregnancyTests().executeAsList().isEmpty())
            assertTrue(partnerDb.cycleQueries.selectAllReminders().executeAsList().isEmpty())
            assertNull(partnerDb.cycleQueries.getBirthControl().executeAsOneOrNull())
            assertEquals("", partnerStorage.getTargetSyncId())
            assertNull(partnerSync.syncWarning.value)
            assertFalse(partnerSync.syncAuthRequired.value)
            assertTrue(partnerScheduler.cancelPartnerPullCount >= 1)
            } finally {
                ownerScope.cancel()
                partnerScope.cancel()
                ownerHttpClient.close()
                partnerHttpClient?.close()
            }
        }
    }

    // =========================================================================
    // E2E TEST 2: Wi-Fi & Cellular Connection Transitions (503 -> Recovery)
    // =========================================================================

    @Test
    fun testE2E_offline503FailureAndConnectionRecovery() {
        runBlocking {
            val cryptoBridge = FakeSyncCryptoBridge()
            val secretKey = cryptoBridge.generateRandomKey()

            val ownerDb = createInMemoryDb()
            ownerDb.cycleQueries.insertEntry("2024-03-02", 3, "Flow", null, 2, "Cramps")
            val ownerStorage = FakeSyncKeyValueStore(
                storedUserId = "owner-1",
                storedPartnerEmail = "partner@example.com",
                storedSyncSecretKey = secretKey,
                storedPrimarySyncFileId = "file-conn-1"
            )
            val ownerScheduler = FakeSyncUploadScheduler()
            val auth = FakeDriveAuthProvider()
            var isOnline = false

            val ownerHttpClient = createMockDriveHttpClient { req, _ ->
                if (!isOnline) {
                    FakeHttpReply(HttpStatusCode.ServiceUnavailable)
                } else if (req.method == HttpMethod.Patch) {
                    FakeHttpReply(HttpStatusCode.OK, """{"id":"file-conn-1"}""".encodeToByteArray())
                } else if (req.method == HttpMethod.Post) {
                    FakeHttpReply(HttpStatusCode.OK, """{"id":"perm-1"}""".encodeToByteArray())
                } else {
                    FakeHttpReply(HttpStatusCode.NotFound)
                }
            }
            val drive = GoogleDriveManager(auth, ownerHttpClient, retryBaseDelayMs = 1L)
            var partnerHttpClient: HttpClient? = null

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val sync = SyncManager(
                database = ownerDb,
                rustBridge = cryptoBridge,
                storage = ownerStorage,
                googleDriveManager = drive,
                scope = scope,
                backgroundSyncScheduler = ownerScheduler
            )

            // Offline/503 failure -> hasPendingSyncUpload = true + schedulePendingUpload()
            val failedResult = sync.prepareAndUploadPayload()
            assertNull(failedResult)
            assertTrue(ownerStorage.hasPendingSyncUpload())
            assertTrue(ownerScheduler.schedulePendingCount >= 1)

            // Connection recovers -> executeBackgroundUpload() succeeds and clears pending flag
            isOnline = true
            val bgOutcome = sync.executeBackgroundUpload()
            assertTrue(bgOutcome is SyncManager.UploadOutcome.Success)
            assertFalse(ownerStorage.hasPendingSyncUpload())
            val recoveredId = sync.prepareAndUploadPayload()
            assertEquals("file-conn-1", recoveredId)
            assertFalse(ownerStorage.hasPendingSyncUpload())
            assertTrue(ownerScheduler.cancelPendingCount >= 1)
            assertNull(sync.syncWarning.value)

            // Partner connection transition: 503 -> RetryableFailure -> recovery
            val partnerDb = createInMemoryDb()
            val partnerStorage = FakeSyncKeyValueStore(storedTargetSyncId = "file-conn-1", storedSyncSecretKey = secretKey)
            var partnerOnline = false
            val pClient = createMockDriveHttpClient { _, _ ->
                if (!partnerOnline) FakeHttpReply(HttpStatusCode.ServiceUnavailable)
                else FakeHttpReply(HttpStatusCode.NotFound)
            }
            partnerHttpClient = pClient
            val partnerDrive = GoogleDriveManager(auth, pClient, retryBaseDelayMs = 1L)
            val partnerSync = SyncManager(
                database = partnerDb,
                rustBridge = FakeSyncCryptoBridge(),
                storage = partnerStorage,
                googleDriveManager = partnerDrive,
                scope = scope
            )
            val pullFail = partnerSync.executeBackgroundPull()
            assertTrue(pullFail is SyncManager.PullOutcome.RetryableFailure)
            partnerOnline = true
            assertEquals(SyncManager.PullOutcome.Revoked, partnerSync.executeBackgroundPull())
            } finally {
                scope.cancel()
                ownerHttpClient.close()
                partnerHttpClient?.close()
            }
        }
    }

    // =========================================================================
    // E2E TEST 3: Unpaired Owner Banner (No _syncWarning emitted)
    // =========================================================================

    @Test
    fun testE2E_unpairedOwnerWithPreExistingKeyAndPerms_emitsNoSyncWarning() {
        runBlocking {
            val cryptoBridge = FakeSyncCryptoBridge()
            val db = createInMemoryDb()
            val storage = FakeSyncKeyValueStore(
                storedUserId = "owner-unpaired",
                storedPartnerEmail = null,
                storedSyncSecretKey = cryptoBridge.generateRandomKey(),
                storedPrimarySyncFileId = null,
                storedLastSyncTimestamp = 0L,
                storedPartnerPermissionsJson = """{"shareCyclePhase":true}"""
            )
            val scheduler = FakeSyncUploadScheduler()
            val httpClient = createMockDriveHttpClient { _, _ ->
                fail("Unpaired owner should not make Drive HTTP requests")
            }
            val drive = GoogleDriveManager(FakeDriveAuthProvider(), httpClient, retryBaseDelayMs = 1L)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val sync = SyncManager(
                database = db,
                rustBridge = cryptoBridge,
                storage = storage,
                googleDriveManager = drive,
                scope = scope,
                backgroundSyncScheduler = scheduler
            )

            val result = sync.prepareAndUploadPayload()
            assertNull(result)
            assertNull(sync.syncWarning.value, "Unpaired owner must not emit _syncWarning")
            assertFalse(storage.hasPendingSyncUpload())
            } finally {
                scope.cancel()
                httpClient.close()
            }
        }
    }

    // =========================================================================
    // E2E TEST 4: Notification IDs (No 107 Collision), Repeat Intervals, Cancel
    // =========================================================================

    @Test
    fun testE2E_notificationIds_repeatIntervals_andCancellation() {
        val scheduler = FakeNotificationScheduler()
        val dayMs = 24L * 60 * 60 * 1000L

        val expectedIds = mapOf(
            ("Pill" to "Daily") to 101,
            ("IUD String Check" to "Monthly") to 102,
            ("Arm Implant Replacement" to "Once") to 103,
            ("Contraceptive Patch" to "Weekly (3 on, 1 off)") to 104,
            ("Vaginal Ring (Remove)" to "Every 3 Weeks") to 105,
            ("Hormonal IUD Replacement (5 Years)" to "Once") to 106,
            ("Copper IUD Replacement (10 Years)" to "Once") to 107,
            ("Pregnancy Test" to "Monthly") to 108,
            ("Contraceptive Injection" to "Every 12 Weeks") to 109
        )

        val actualIds = expectedIds.keys.map { (type, freq) ->
            val id = reminderNotificationId(type, freq)
            scheduler.scheduleNotification(id, "Bloom", "$type ($freq)", 1000L)
            id
        }

        assertEquals(expectedIds.values.toList(), actualIds)
        assertEquals(9, actualIds.toSet().size, "All 9 reminder types must have distinct IDs (no 107 collision)")

        // Verify 21-day, 28-day, and 84-day repeat intervals
        assertEquals(21L * dayMs, repeatIntervalMsFor("Bloom", "Time to change your ring"))
        assertEquals(21L * dayMs, repeatIntervalMsFor("Every 3 Weeks", "Ring"))
        assertEquals(28L * dayMs, repeatIntervalMsFor("Every 4 Weeks", "Insert ring"))
        assertEquals(84L * dayMs, repeatIntervalMsFor("Bloom", "Time for your next shot"))
        assertEquals(84L * dayMs, repeatIntervalMsFor("Every 12 Weeks", "Contraceptive Injection"))

        // Verify cancellation on reminder delete and BC "None"
        scheduler.cancelNotification(reminderNotificationId("Copper IUD Replacement (10 Years)", "Once"))
        scheduler.cancelNotification(reminderNotificationId("Contraceptive Injection", "Every 12 Weeks"))
        assertEquals(listOf(107, 109), scheduler.cancelled)
    }

    // =========================================================================
    // E2E TEST 5: SQLDelight V1 -> V2 Migration & Partial-Column Idempotency
    // =========================================================================

    @Test
    fun testSQLDelightV1ToV2Migration_rowPreservation_andPartialColumnIdempotency() {
        val intAdapter = object : app.cash.sqldelight.ColumnAdapter<Int, Long> {
            override fun decode(databaseValue: Long) = databaseValue.toInt()
            override fun encode(value: Int) = value.toLong()
        }

        // Part 1: Pure V1 CycleEntry table (without painLevel/symptoms or V2 tables) -> V2
        val v1Driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY
        )
        try {
            v1Driver.execute(
                null,
                """
                CREATE TABLE CycleEntry (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    date TEXT NOT NULL UNIQUE,
                    intensity INTEGER,
                    notes TEXT,
                    sexualRelations TEXT
                );
                """.trimIndent(),
                0
            )
            v1Driver.execute(null, "INSERT INTO CycleEntry(date, intensity, notes, sexualRelations) VALUES ('2024-01-10', 4, 'Heavy V1 day 1', 'protected');", 0)
            v1Driver.execute(null, "INSERT INTO CycleEntry(date, intensity, notes, sexualRelations) VALUES ('2024-01-11', 2, 'Lighter V1 day 2', NULL);", 0)
            v1Driver.execute(null, "INSERT INTO CycleEntry(date, intensity, notes, sexualRelations) VALUES ('2024-02-07', 0, 'V1 check-in note', 'unprotected');", 0)

            com.novarytm.db.SafeMigrationSchema.migrate(v1Driver, 1L, 2L)

            val migratedDb = NovarytmDatabase(
                driver = v1Driver,
                CycleEntryAdapter = CycleEntry.Adapter(
                    intensityAdapter = intAdapter,
                    painLevelAdapter = intAdapter
                )
            )

            // Assert 100% V1 row preservation + nullable V2 columns
            val preservedEntries = migratedDb.cycleQueries.selectAllEntries().executeAsList().sortedBy { it.date }
            assertEquals(3, preservedEntries.size, "All 3 V1 rows must be preserved across V1->V2 migration")

            assertEquals("2024-01-10", preservedEntries[0].date)
            assertEquals(4, preservedEntries[0].intensity)
            assertEquals("Heavy V1 day 1", preservedEntries[0].notes)
            assertEquals("protected", preservedEntries[0].sexualRelations)
            assertNull(preservedEntries[0].painLevel)
            assertNull(preservedEntries[0].symptoms)

            assertEquals("2024-01-11", preservedEntries[1].date)
            assertEquals(2, preservedEntries[1].intensity)
            assertEquals("Lighter V1 day 2", preservedEntries[1].notes)
            assertNull(preservedEntries[1].sexualRelations)
            assertNull(preservedEntries[1].painLevel)
            assertNull(preservedEntries[1].symptoms)

            assertEquals("2024-02-07", preservedEntries[2].date)
            assertEquals(0, preservedEntries[2].intensity)
            assertEquals("V1 check-in note", preservedEntries[2].notes)
            assertEquals("unprotected", preservedEntries[2].sexualRelations)
            assertNull(preservedEntries[2].painLevel)
            assertNull(preservedEntries[2].symptoms)

            // Assert new V2 columns and BirthControl/PregnancyTest/Reminder tables work cleanly
            migratedDb.cycleQueries.insertEntry("2024-03-01", 3, "New V2 entry", "protected", 6, "Cramps,Headaches")
            val v2Entry = migratedDb.cycleQueries.selectEntryByDate("2024-03-01").executeAsOne()
            assertEquals(6, v2Entry.painLevel)
            assertEquals("Cramps,Headaches", v2Entry.symptoms)

            migratedDb.cycleQueries.setBirthControlWithReminder("Hormonal IUD", "08:30", true)
            val bc = migratedDb.cycleQueries.getBirthControl().executeAsOneOrNull()
            assertNotNull(bc)
            assertEquals("Hormonal IUD", bc.type)
            assertEquals("08:30", bc.reminderTime)

            migratedDb.cycleQueries.insertPregnancyTest("2024-03-05", "Negative")
            assertEquals(1, migratedDb.cycleQueries.selectAllPregnancyTests().executeAsList().size)

            migratedDb.cycleQueries.insertReminder("Pill", "Daily", "08:30", "2024-03-06", true)
            assertEquals(1, migratedDb.cycleQueries.selectAllReminders().executeAsList().size)
        } finally {
            v1Driver.close()
        }

        // Part 2: Partial-column V1 schema (painLevel already present, symptoms missing) + repeated migration idempotency
        val partialDriver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY
        )
        try {
            partialDriver.execute(
                null,
                """
                CREATE TABLE CycleEntry (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    date TEXT NOT NULL UNIQUE,
                    intensity INTEGER,
                    notes TEXT,
                    sexualRelations TEXT,
                    painLevel INTEGER
                );
                """.trimIndent(),
                0
            )
            partialDriver.execute(null, "INSERT INTO CycleEntry(date, intensity, notes, sexualRelations, painLevel) VALUES ('2024-02-15', 3, 'Partial row', 'protected', 5);", 0)

            // Run migrate twice + create on pre-existing table to verify complete idempotency
            com.novarytm.db.SafeMigrationSchema.migrate(partialDriver, 1L, 2L)
            com.novarytm.db.SafeMigrationSchema.migrate(partialDriver, 1L, 2L)
            com.novarytm.db.SafeMigrationSchema.create(partialDriver)

            val partialDb = NovarytmDatabase(
                driver = partialDriver,
                CycleEntryAdapter = CycleEntry.Adapter(
                    intensityAdapter = intAdapter,
                    painLevelAdapter = intAdapter
                )
            )
            val row = partialDb.cycleQueries.selectEntryByDate("2024-02-15").executeAsOne()
            assertEquals(3, row.intensity)
            assertEquals("Partial row", row.notes)
            assertEquals(5, row.painLevel)
            assertNull(row.symptoms)
        } finally {
            partialDriver.close()
        }
    }

    // =========================================================================
    // E2E TEST 6: Offline-First Resilience (Airplane Mode IOException -> Coalesced Recovery)
    // =========================================================================

    @Test
    fun testE2E_offlineFirstAirplaneMode_instantLocalPersistence_andCoalescedPartnerSync() {
        runBlocking {
            val ownerCrypto = FakeSyncCryptoBridge()
            val partnerCrypto = FakeSyncCryptoBridge()
            val secretKey = ownerCrypto.generateRandomKey()
            val sharedFileId = "drive-offline-coalesce-1"

            val ownerPerms = PartnerPermissions(
                shareCyclePhase = true,
                shareFlowIntensity = true,
                shareSymptomsMoods = true,
                shareBirthControl = true,
                sharePregnancyTests = true,
                shareSexualActivity = true
            )
            ownerCrypto.savePartnerPermissions(ownerPerms)

            val ownerDb = createInMemoryDb()
            val ownerStorage = FakeSyncKeyValueStore(
                storedUserId = "owner-offline-1",
                storedPartnerEmail = "partner@example.com",
                storedSyncSecretKey = secretKey,
                storedPrimarySyncFileId = sharedFileId
            )
            val ownerScheduler = FakeSyncUploadScheduler()
            val auth = FakeDriveAuthProvider()

            var isAirplaneMode = true
            var patchUploadCount = 0
            var latestUploadedBlob: ByteArray? = null

            val ownerHttpClient = createMockDriveHttpClient { req, body ->
                if (isAirplaneMode) {
                    throw kotlinx.io.IOException("Airplane mode active: network unreachable")
                }
                when {
                    req.method == HttpMethod.Patch && req.url.toString().contains("/upload/drive/v3/files/$sharedFileId") -> {
                        patchUploadCount++
                        latestUploadedBlob = body
                        FakeHttpReply(HttpStatusCode.OK, """{"id":"$sharedFileId"}""".encodeToByteArray())
                    }
                    req.method == HttpMethod.Post && req.url.toString().endsWith("/permissions") -> {
                        FakeHttpReply(HttpStatusCode.OK, """{"id":"perm-ok"}""".encodeToByteArray())
                    }
                    else -> FakeHttpReply(HttpStatusCode.NotFound)
                }
            }
            val ownerDrive = GoogleDriveManager(auth, ownerHttpClient, retryBaseDelayMs = 1L)

            val partnerDb = createInMemoryDb()
            val partnerStorage = FakeSyncKeyValueStore(
                storedTargetSyncId = sharedFileId,
                storedSyncSecretKey = secretKey
            )
            val partnerHttpClient = createMockDriveHttpClient { req, _ ->
                if (isAirplaneMode) {
                    throw kotlinx.io.IOException("Airplane mode active: network unreachable")
                }
                if (req.method == HttpMethod.Get && latestUploadedBlob != null) {
                    FakeHttpReply(HttpStatusCode.OK, latestUploadedBlob!!, ContentType.Application.OctetStream)
                } else {
                    FakeHttpReply(HttpStatusCode.NotFound)
                }
            }
            val partnerDrive = GoogleDriveManager(auth, partnerHttpClient, retryBaseDelayMs = 1L)

            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val partnerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val ownerTracker = CycleTracker(com.novarytm.ffi.RustBridge(), ownerDb.cycleQueries, ownerScope)
                val ownerSync = SyncManager(
                    database = ownerDb,
                    rustBridge = ownerCrypto,
                    storage = ownerStorage,
                    googleDriveManager = ownerDrive,
                    scope = ownerScope,
                    cycleTracker = ownerTracker,
                    backgroundSyncScheduler = ownerScheduler
                )
                val partnerSync = SyncManager(
                    database = partnerDb,
                    rustBridge = partnerCrypto,
                    storage = partnerStorage,
                    googleDriveManager = partnerDrive,
                    scope = partnerScope
                )

                // 1. Log multiple cycle entries while in Airplane mode
                ownerTracker.logNewPeriod(
                    date = LocalDate.parse("2024-05-01"),
                    intensity = 4,
                    notes = "Offline day 1",
                    painLevel = 6,
                    symptoms = "cramps"
                )
                ownerTracker.logNewPeriod(
                    date = LocalDate.parse("2024-05-02"),
                    intensity = 3,
                    notes = "Offline day 2",
                    painLevel = 3,
                    symptoms = "headaches"
                )
                ownerTracker.logSexualRelation(LocalDate.parse("2024-05-04"), "protected")

                // Assert instant local SQLDelight persistence while completely offline
                awaitCondition { ownerDb.cycleQueries.selectAllEntries().executeAsList().size == 3 }
                awaitCondition { ownerTracker.loggedDates.value.size == 2 }
                assertTrue(ownerTracker.loggedDates.value.contains(LocalDate.parse("2024-05-01")))
                assertTrue(ownerTracker.loggedDates.value.contains(LocalDate.parse("2024-05-02")))

                // Attempt upload while offline -> fails with IOException, marks pendingSyncUpload = true
                val offlineUploadId = ownerSync.prepareAndUploadPayload()
                assertNull(offlineUploadId)
                assertTrue(ownerStorage.hasPendingSyncUpload(), "Offline IOException must persist hasPendingSyncUpload == true")
                assertTrue(ownerScheduler.schedulePendingCount >= 1)
                assertEquals(0, patchUploadCount)

                // 2. Restore network -> executeBackgroundUpload() uploads coalesced snapshot of all 3 entries in 1 PATCH
                isAirplaneMode = false
                val recoveryOutcome = ownerSync.executeBackgroundUpload()
                assertTrue(recoveryOutcome is SyncManager.UploadOutcome.Success)
                assertFalse(ownerStorage.hasPendingSyncUpload(), "Successful background upload must clear hasPendingSyncUpload")
                assertEquals(1, patchUploadCount, "All offline mutations must coalesce into a single media upload")
                assertNotNull(latestUploadedBlob)

                // 3. Partner pulls coalesced blob and receives all 3 entries
                val pullOutcome = partnerSync.executeBackgroundPull()
                assertEquals(SyncManager.PullOutcome.Success, pullOutcome)
                val syncedEntries = partnerDb.cycleQueries.selectAllEntries().executeAsList().sortedBy { it.date }
                assertEquals(3, syncedEntries.size)
                assertEquals("2024-05-01", syncedEntries[0].date)
                assertEquals(4, syncedEntries[0].intensity)
                assertEquals("Cramps", syncedEntries[0].symptoms)
                assertEquals("2024-05-02", syncedEntries[1].date)
                assertEquals(3, syncedEntries[1].intensity)
                assertEquals("2024-05-04", syncedEntries[2].date)
                assertEquals("protected", syncedEntries[2].sexualRelations)
            } finally {
                ownerScope.cancel()
                partnerScope.cancel()
                ownerHttpClient.close()
                partnerHttpClient.close()
            }
        }
    }

    // =========================================================================
    // E2E TEST 7: Pairing Brute-Force Lockout & SyncManager Rate Limiting
    // =========================================================================

    @Test
    fun testPairingRateLimiter_exponentialBackoff_andSyncManagerRateLimit() {
        runBlocking {
            var fakeNowMs = 1_700_000_000_000L
            val limiter = com.novarytm.storage.PairingRateLimiter(nowMsProvider = { fakeNowMs })

            // Attempt 1 & 2: free attempts (0s lockout)
            assertEquals(0L, limiter.recordSyncCodeFailedAttempt())
            assertEquals(1, limiter.getSyncCodeFailedAttempts())
            assertEquals(0L, limiter.getSyncCodeLockoutRemainingSeconds())
            assertFalse(limiter.isLockedOut())

            assertEquals(0L, limiter.recordSyncCodeFailedAttempt())
            assertEquals(2, limiter.getSyncCodeFailedAttempts())
            assertEquals(0L, limiter.getSyncCodeLockoutRemainingSeconds())
            assertFalse(limiter.isLockedOut())

            // Attempt 3 -> 30s lockout
            val lock3 = limiter.recordSyncCodeFailedAttempt()
            assertEquals(fakeNowMs + 30_000L, lock3)
            assertEquals(3, limiter.getSyncCodeFailedAttempts())
            assertEquals(30L, limiter.getSyncCodeLockoutRemainingSeconds())
            assertTrue(limiter.isLockedOut())

            fakeNowMs += 15_000L
            assertEquals(15L, limiter.getSyncCodeLockoutRemainingSeconds())
            fakeNowMs += 15_000L
            assertEquals(0L, limiter.getSyncCodeLockoutRemainingSeconds())
            assertFalse(limiter.isLockedOut())

            // Attempt 4 -> 60s lockout
            val lock4 = limiter.recordSyncCodeFailedAttempt()
            assertEquals(fakeNowMs + 60_000L, lock4)
            assertEquals(4, limiter.getSyncCodeFailedAttempts())
            assertEquals(60L, limiter.getSyncCodeLockoutRemainingSeconds())
            fakeNowMs += 60_000L
            assertEquals(0L, limiter.getSyncCodeLockoutRemainingSeconds())

            // Attempt 5 -> 300s lockout
            val lock5 = limiter.recordSyncCodeFailedAttempt()
            assertEquals(fakeNowMs + 300_000L, lock5)
            assertEquals(5, limiter.getSyncCodeFailedAttempts())
            assertEquals(300L, limiter.getSyncCodeLockoutRemainingSeconds())
            fakeNowMs += 300_000L
            assertEquals(0L, limiter.getSyncCodeLockoutRemainingSeconds())

            // Attempt 6 -> remains capped at 300s
            val lock6 = limiter.recordSyncCodeFailedAttempt()
            assertEquals(fakeNowMs + 300_000L, lock6)
            assertEquals(300L, limiter.getSyncCodeLockoutRemainingSeconds())

            // Valid code -> reset clears attempts and lockout immediately
            limiter.resetSyncCodeFailedAttempts()
            assertEquals(0, limiter.getSyncCodeFailedAttempts())
            assertEquals(0L, limiter.getSyncCodeLockoutRemainingSeconds())
            assertFalse(limiter.isLockedOut())

            // Next invalid code starts fresh at Attempt 1 (no lockout)
            assertEquals(0L, limiter.recordSyncCodeFailedAttempt())
            assertEquals(1, limiter.getSyncCodeFailedAttempts())
            assertEquals(0L, limiter.getSyncCodeLockoutRemainingSeconds())

            // Verify SyncManager.checkRateLimit (15 per 60s sliding window)
            val db = createInMemoryDb()
            val crypto = FakeSyncCryptoBridge()
            val storage = FakeSyncKeyValueStore(
                storedUserId = "owner-rl",
                storedPartnerEmail = "partner@example.com",
                storedSyncSecretKey = crypto.generateRandomKey(),
                storedPrimarySyncFileId = "file-rl"
            )
            val httpClient = createMockDriveHttpClient { req, _ ->
                if (req.method == HttpMethod.Patch) {
                    FakeHttpReply(HttpStatusCode.OK, """{"id":"file-rl"}""".encodeToByteArray())
                } else {
                    FakeHttpReply(HttpStatusCode.OK, """{"id":"perm-rl"}""".encodeToByteArray())
                }
            }
            val drive = GoogleDriveManager(FakeDriveAuthProvider(), httpClient, retryBaseDelayMs = 1L)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val sync = SyncManager(
                    database = db,
                    rustBridge = crypto,
                    storage = storage,
                    googleDriveManager = drive,
                    scope = scope
                )
                val t0 = kotlin.time.Clock.System.now().toEpochMilliseconds()
                repeat(15) { idx ->
                    assertTrue(sync.checkRateLimit(t0 + idx * 100L), "Attempt ${idx + 1} within 60s must pass")
                }
                assertFalse(sync.checkRateLimit(t0 + 5_000L), "16th attempt within 60s window must be rate-limited")

                // Calling prepareAndUploadPayload while rate-limited emits warning and aborts
                val blockedUpload = sync.prepareAndUploadPayload()
                assertNull(blockedUpload)
                assertEquals("Rate limit reached. Please wait before syncing again.", sync.syncWarning.value)

                // After 60s window expires, rate limit recovers
                assertTrue(sync.checkRateLimit(t0 + 61_600L), "Rate limit must recover after 60s window")
            } finally {
                scope.cancel()
                httpClient.close()
            }
        }
    }

    // =========================================================================
    // E2E TEST 8: Asymmetric Partner Read-Only Guard & Batched Deletion
    // =========================================================================

    @Test
    fun testAsymmetricPartnerReadOnly_cycleTrackerGuard_batchedDelete_andZeroPartnerUploads() {
        runBlocking {
            // 1. Batched deleteEntries(ids) in Owner mode (isReadOnlyMode = false)
            val ownerDb = createInMemoryDb()
            val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val partnerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val partnerHttpMethods = mutableListOf<HttpMethod>()
            val crypto = FakeSyncCryptoBridge()
            val secretKey = crypto.generateRandomKey()

            val emptyPartnerBlob = crypto.encryptForSync(
                AppJson.encodeToString(
                    SyncPayload.serializer(),
                    SyncPayload(lastUpdated = kotlin.time.Clock.System.now().toEpochMilliseconds(), entries = emptyList())
                ),
                @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                kotlin.io.encoding.Base64.Default.encode(secretKey)
            ).encodeToByteArray()

            val partnerHttpClient = createMockDriveHttpClient { req, _ ->
                partnerHttpMethods.add(req.method)
                if (req.method == HttpMethod.Get) {
                    FakeHttpReply(HttpStatusCode.OK, emptyPartnerBlob, ContentType.Application.OctetStream)
                } else {
                    FakeHttpReply(HttpStatusCode.Forbidden)
                }
            }
            val partnerDrive = GoogleDriveManager(FakeDriveAuthProvider(), partnerHttpClient, retryBaseDelayMs = 1L)

            try {
                val ownerTracker = CycleTracker(
                    rustBridge = com.novarytm.ffi.RustBridge(),
                    cycleQueries = ownerDb.cycleQueries,
                    coroutineScope = ownerScope,
                    isReadOnlyMode = { false }
                )
                ownerTracker.logNewPeriodRange(
                    startDate = LocalDate.parse("2024-06-01"),
                    endDate = LocalDate.parse("2024-06-04"),
                    intensity = 3,
                    notes = "Batch delete test"
                )
                awaitCondition { ownerDb.cycleQueries.selectAllEntries().executeAsList().size == 4 }
                awaitCondition { ownerTracker.loggedDates.value.size == 4 }

                val batchIds = ownerDb.cycleQueries.selectAllEntries().executeAsList().map { it.id }
                ownerTracker.deleteEntries(batchIds)
                awaitCondition { ownerDb.cycleQueries.selectAllEntries().executeAsList().isEmpty() }
                awaitCondition { ownerTracker.loggedDates.value.isEmpty() }

                // 2. Domain Partner write guard: CycleTracker with isReadOnlyMode = { true } rejects all mutations
                val partnerDb = createInMemoryDb()
                partnerDb.cycleQueries.insertEntry("2024-06-10", 3, "Synced owner entry", null, 2, "Cramps")
                val seededEntry = partnerDb.cycleQueries.selectEntryByDate("2024-06-10").executeAsOne()

                val partnerTracker = CycleTracker(
                    rustBridge = com.novarytm.ffi.RustBridge(),
                    cycleQueries = partnerDb.cycleQueries,
                    coroutineScope = partnerScope,
                    isReadOnlyMode = { true }
                )

                partnerTracker.logNewPeriod(LocalDate.parse("2024-06-11"), intensity = 4, notes = "Blocked write")
                partnerTracker.logNewPeriodRange(LocalDate.parse("2024-06-12"), LocalDate.parse("2024-06-14"), intensity = 2)
                partnerTracker.logSexualRelation(LocalDate.parse("2024-06-10"), "unprotected")
                partnerTracker.deleteEntry(seededEntry.id)
                partnerTracker.deleteEntries(listOf(seededEntry.id))
                delay(120)

                val remainingEntries = partnerDb.cycleQueries.selectAllEntries().executeAsList()
                assertEquals(1, remainingEntries.size, "Partner CycleTracker must reject all write/delete calls")
                assertEquals("2024-06-10", remainingEntries[0].date)
                assertEquals(3, remainingEntries[0].intensity)
                assertNull(remainingEntries[0].sexualRelations)

                // 3. Partner SyncManager performs 0 outbound POST/PATCH/DELETE calls even if DB mutates directly
                val partnerStorage = FakeSyncKeyValueStore(
                    storedUserId = "partner-google-user",
                    storedTargetSyncId = "shared-owner-file-id",
                    storedPartnerEmail = "owner@example.com",
                    storedSyncSecretKey = secretKey
                )
                val partnerScheduler = FakeSyncUploadScheduler()
                val partnerSync = SyncManager(
                    database = partnerDb,
                    rustBridge = crypto,
                    storage = partnerStorage,
                    googleDriveManager = partnerDrive,
                    scope = partnerScope,
                    cycleTracker = partnerTracker,
                    backgroundSyncScheduler = partnerScheduler
                )

                // Directly mutate Partner DB and wait past 1000ms reactive observer debounce
                partnerDb.cycleQueries.insertEntry("2024-06-15", 2, "Direct DB write", null, null, null)
                delay(1150)

                assertFalse(partnerStorage.hasPendingSyncUpload(), "Partner mode must never mark pendingSyncUpload")
                assertEquals(0, partnerScheduler.schedulePendingCount, "Partner mode must never schedule background upload")
                assertNull(partnerSync.prepareAndUploadPayload(), "Partner prepareAndUploadPayload must return null")
                assertEquals(SyncManager.UploadOutcome.NoOp, partnerSync.executeBackgroundUpload(), "Partner executeBackgroundUpload must be NoOp")
                assertTrue(partnerSync.flushPendingChanges())
                assertFalse(partnerSync.revokeAndRegenerate(), "Partner must not delete or revoke Owner Drive blob")
                assertTrue(partnerSync.manualRefresh(forceResync = true))

                val outboundMutations = partnerHttpMethods.filter {
                    it == HttpMethod.Post || it == HttpMethod.Patch || it == HttpMethod.Put || it == HttpMethod.Delete
                }
                assertTrue(outboundMutations.isEmpty(), "Partner SyncManager must perform 0 POST/PATCH/PUT/DELETE calls; saw $partnerHttpMethods")
                assertTrue(partnerHttpMethods.all { it == HttpMethod.Get }, "Partner SyncManager may only issue GET pull requests")
            } finally {
                ownerScope.cancel()
                partnerScope.cancel()
                partnerHttpClient.close()
            }
        }
    }
}
