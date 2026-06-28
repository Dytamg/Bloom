package com.novarytm.sync

import kotlinx.serialization.Serializable

@Serializable
data class SyncEntry(
    val date: String,
    val intensity: Int,
    val notes: String?,
    val sexualRelations: String? = null,
    val painLevel: Int? = null,
    val symptoms: String? = null
)

@Serializable
data class SyncBirthControl(
    val type: String,
    val isActive: Boolean
)

@Serializable
data class SyncPregnancyTest(
    val date: String,
    val result: String
)

@Serializable
data class SyncReminder(
    val type: String,
    val frequency: String,
    val timeOfDay: String?,
    val nextDate: String?,
    val isActive: Boolean
)

@Serializable
data class SyncPayload(
    val lastUpdated: Long,
    val birthControl: SyncBirthControl?,
    val entries: List<SyncEntry>,
    val pregnancyTests: List<SyncPregnancyTest> = emptyList(),
    val reminders: List<SyncReminder> = emptyList(),
    val permissions: PartnerPermissions? = null
)
