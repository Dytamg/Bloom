package com.novarytm.tracker

import com.novarytm.db.CycleEntry
import com.novarytm.ffi.RustBridge
import com.novarytm.db.CycleQueries
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.novarytm.utils.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.plus

@Serializable
data class CyclePredictionResult(
    val expectedPeriodDays: Set<String>,
    val fertileWindow: Set<String>,
    val currentDayOfCycle: Int
)

@Serializable
data class CyclePrediction(
    val estimated_length_days: Int,
    val is_irregular: Boolean
)

data class CycleAnalysis(
    val averageLength: Int,
    val isIrregular: Boolean
)

class CycleTracker(
    private val rustBridge: RustBridge,
    private val cycleQueries: CycleQueries,
    private val coroutineScope: CoroutineScope
) {
    private val trackerMutex = Mutex()

    private val _loggedDates = MutableStateFlow<Set<LocalDate>>(emptySet())
    val loggedDates: StateFlow<Set<LocalDate>> = _loggedDates.asStateFlow()

    private val _predictedDates = MutableStateFlow<Set<LocalDate>>(emptySet())
    val predictedDates: StateFlow<Set<LocalDate>> = _predictedDates.asStateFlow()

    private val _fertileDates = MutableStateFlow<Set<LocalDate>>(emptySet())
    val fertileDates: StateFlow<Set<LocalDate>> = _fertileDates.asStateFlow()
    
    private val _currentCycleDay = MutableStateFlow<Int>(1)
    val currentCycleDay: StateFlow<Int> = _currentCycleDay.asStateFlow()

    private fun sanitizeNote(note: String?): String? {
        if (note == null) return null
        return note.replace(Regex("<[^>]*>"), "").take(500).trim()
    }

    private fun sanitizeSymptoms(symptoms: String?): String? {
        if (symptoms.isNullOrBlank()) return null
        val allowMap = mapOf(
            "cramps" to "Cramps",
            "headaches" to "Headaches",
            "breast pain" to "Breast Pain",
            "back pain" to "Back Pain",
            "nausea" to "Nausea"
        )
        val filtered = symptoms.split(",")
            .mapNotNull { allowMap[it.trim().lowercase()] }
            .distinct()
        return if (filtered.isEmpty()) null else filtered.joinToString(",")
    }

    fun logNewPeriod(
        date: LocalDate, 
        intensity: Int, 
        notes: String? = null, 
        sexualRelations: String? = null, 
        painLevel: Int? = null, 
        symptoms: String? = null
    ) {
        coroutineScope.launch(Dispatchers.Default) {
            trackerMutex.withLock {
                val dateStr = date.toString()
                val existing = cycleQueries.selectEntryByDate(dateStr).executeAsOneOrNull()
                val validIntensity = intensity.coerceIn(0, 5)
                val validPainLevel = (painLevel ?: existing?.painLevel)?.coerceIn(1, 10)
                val cleanSymptoms = sanitizeSymptoms(symptoms ?: existing?.symptoms)
                cycleQueries.insertEntry(dateStr, validIntensity, sanitizeNote(notes), sexualRelations ?: existing?.sexualRelations, validPainLevel, cleanSymptoms)
                refreshCycleStateInternal()
            }
        }
    }

    fun logSexualRelation(date: LocalDate, relation: String?) {
        coroutineScope.launch(Dispatchers.Default) {
            trackerMutex.withLock {
                val dateStr = date.toString()
                val existing = cycleQueries.selectEntryByDate(dateStr).executeAsOneOrNull()
                val cleanRelation = relation?.take(50)
                if (existing != null) {
                    cycleQueries.insertEntry(dateStr, existing.intensity, existing.notes, cleanRelation, existing.painLevel, existing.symptoms)
                } else {
                    cycleQueries.insertEntry(dateStr, 0, null, cleanRelation, null, null)
                }
                refreshCycleStateInternal()
            }
        }
    }

    fun logNewPeriodRange(
        startDate: LocalDate, 
        endDate: LocalDate, 
        intensity: Int, 
        notes: String? = null, 
        sexualRelations: String? = null,
        painLevel: Int? = null,
        symptoms: String? = null
    ) {
        coroutineScope.launch(Dispatchers.Default) {
            trackerMutex.withLock {
                val sanitized = sanitizeNote(notes)
                val cleanSymptoms = sanitizeSymptoms(symptoms)
                val validIntensity = intensity.coerceIn(0, 5)
                val validPain = painLevel?.coerceIn(1, 10)
                var currentDate = startDate
                var isFirstDay = true
                while (currentDate <= endDate) {
                    val dayNotes = if (isFirstDay) sanitized else null
                    val dateStr = currentDate.toString()
                    val existing = cycleQueries.selectEntryByDate(dateStr).executeAsOneOrNull()
                    
                    val finalNotes = dayNotes ?: existing?.notes
                    val finalRelations = existing?.sexualRelations ?: sexualRelations?.take(50)
                    val finalPainLevel = validPain ?: existing?.painLevel
                    val finalSymptoms = cleanSymptoms ?: existing?.symptoms
                    
                    cycleQueries.insertEntry(dateStr, validIntensity, finalNotes, finalRelations, finalPainLevel, finalSymptoms)
                    
                    currentDate = currentDate.plus(1, kotlinx.datetime.DateTimeUnit.DAY)
                    isFirstDay = false
                }
                refreshCycleStateInternal()
            }
        }
    }

    fun deleteEntry(id: Long) {
        coroutineScope.launch(Dispatchers.Default) {
            trackerMutex.withLock {
                cycleQueries.deleteEntry(id)
                refreshCycleStateInternal()
            }
        }
    }

    fun refreshCycleState() {
        coroutineScope.launch(Dispatchers.Default) {
            trackerMutex.withLock {
                refreshCycleStateInternal()
            }
        }
    }

    private fun refreshCycleStateInternal() {
            val history = cycleQueries.selectAllEntries().executeAsList()
            
            _loggedDates.value = history.filter { (it.intensity ?: 0) > 0 }.map { LocalDate.parse(it.date) }.toSet()
            
            val predictions = rustBridge.getCyclePredictions(history)
            
            _predictedDates.value = predictions.expectedPeriodDays.mapNotNull { rawDate ->
                try {
                    // Strip trailing time/timezone data (e.g., 'T00:00:00Z') before parsing
                    val cleanDate = rawDate.substringBefore("T")
                    LocalDate.parse(cleanDate)
                } catch (e: Exception) {
                    null
                }
            }.toSet()

            _fertileDates.value = predictions.fertileWindow.mapNotNull { rawDate ->
                try {
                    val cleanDate = rawDate.substringBefore("T")
                    LocalDate.parse(cleanDate)
                } catch (e: Exception) {
                    null
                }
            }.toSet()
            
            _currentCycleDay.value = predictions.currentDayOfCycle
    }

    fun analyzeHistory(entries: List<CycleEntry>): CycleAnalysis {
        val periodEntries = entries.filter { (it.intensity ?: 0) > 0 }
        if (periodEntries.isEmpty()) {
            return CycleAnalysis(28, false)
        }

        val datesCsv = periodEntries.map { it.date }.sortedDescending().joinToString(",")
        val result = rustBridge.analyzeCycleHistory(datesCsv)

        return try {
            val prediction = AppJson.decodeFromString<CyclePrediction>(result)
            CycleAnalysis(prediction.estimated_length_days, prediction.is_irregular)
        } catch (e: Exception) {
            CycleAnalysis(28, false)
        }
    }
}
