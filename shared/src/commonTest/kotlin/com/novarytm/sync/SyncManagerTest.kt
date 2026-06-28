package com.novarytm.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json

class SyncManagerTest {

    @Test
    fun testSerialization() {
        val payload = SyncPayload(
            lastUpdated = 123456789L,
            birthControl = SyncBirthControl("Pill", true),
            entries = listOf(
                SyncEntry("2024-01-01", 3, "Notes")
            )
        )
        
        val json = Json { prettyPrint = true }
        val jsonString = json.encodeToString(SyncPayload.serializer(), payload)
        
        val decoded = json.decodeFromString(SyncPayload.serializer(), jsonString)
        
        assertEquals(payload.lastUpdated, decoded.lastUpdated)
        assertEquals(payload.birthControl?.type, decoded.birthControl?.type)
        assertEquals(payload.entries.size, decoded.entries.size)
    }
}
