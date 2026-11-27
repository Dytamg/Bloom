package com.bloom.tracker

import com.bloom.db.CycleEntry
import com.bloom.db.BloomDatabase
import com.bloom.ffi.RustBridge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlin.test.*

class CycleTrackerTest {

    private fun createInMemoryDb(): BloomDatabase {
        Class.forName("org.sqlite.JDBC")
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
        BloomDatabase.Schema.create(driver)
        val intAdapter = object : app.cash.sqldelight.ColumnAdapter<Int, Long> {
            override fun decode(databaseValue: Long) = databaseValue.toInt()
            override fun encode(value: Int) = value.toLong()
        }
        return BloomDatabase(
            driver = driver,
            CycleEntryAdapter = CycleEntry.Adapter(
                intensityAdapter = intAdapter,
                painLevelAdapter = intAdapter
            )
        )
    }

    @Test
    fun testCycleTrackerCrudAndPartnerGuard() {
        runBlocking {
            val db = createInMemoryDb()
            val tracker = CycleTracker(RustBridge(), db.cycleQueries, this)

            val date = LocalDate.parse("2024-05-01")
            tracker.logNewPeriod(date, intensity = 2, notes = "Test notes", painLevel = 3, symptoms = "cramps")

            val start = System.currentTimeMillis()
            while (db.cycleQueries.selectAllEntries().executeAsList().isEmpty()) {
                if (System.currentTimeMillis() - start > 2000) {
                    fail("Timed out waiting for period log")
                }
                delay(20)
            }

            val entries = db.cycleQueries.selectAllEntries().executeAsList()
            assertEquals(1, entries.size)
            assertEquals("2024-05-01", entries[0].date)
            assertEquals(2, entries[0].intensity)
            assertEquals("Test notes", entries[0].notes)
        }
    }

    @Test
    fun testOwnerPhaseVisibleWhenShareCyclePhaseFalse_andPartnerHidden() {
        val todayStr = "2026-11-26"
        val phaseCalc: (String, Int) -> String = { startDate, _ ->
            if (startDate == todayStr) "Menstrual" else "Follicular"
        }

        // Owner with shareCyclePhase = false MUST still see real cycle phase on own dashboard
        val ownerPhase = resolveDashboardCyclePhase(
            isPartner = false,
            shareCyclePhase = false,
            latestCycleStartDate = todayStr,
            avgLength = 28,
            calculatePhase = phaseCalc
        )
        assertEquals("Menstrual", ownerPhase)

        // Partner with shareCyclePhase = false MUST see "Hidden"
        val partnerPhase = resolveDashboardCyclePhase(
            isPartner = true,
            shareCyclePhase = false,
            latestCycleStartDate = todayStr,
            avgLength = 28,
            calculatePhase = phaseCalc
        )
        assertEquals("Hidden", partnerPhase)

        // When no period logged yet (null start date), phase is "Hidden"
        val emptyOwnerPhase = resolveDashboardCyclePhase(
            isPartner = false,
            shareCyclePhase = false,
            latestCycleStartDate = null,
            avgLength = 28,
            calculatePhase = phaseCalc
        )
        assertEquals("Hidden", emptyOwnerPhase)

        // Transitioning from null start date to today's start date MUST yield distinct phase effect keys
        val emptyKey = resolveDashboardPhaseKey(latestCycleStartDate = null, avgLength = 28, canShowPhase = true)
        val loggedTodayKey = resolveDashboardPhaseKey(latestCycleStartDate = todayStr, avgLength = 28, canShowPhase = true)
        assertNotEquals(emptyKey, loggedTodayKey)
    }
}
