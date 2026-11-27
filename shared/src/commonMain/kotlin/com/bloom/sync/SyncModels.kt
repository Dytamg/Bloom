package com.bloom.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

const val SAF_SYNC_TARGET_ID = "saf_local_sync"

enum class SyncProviderMode {
    DRIVE_API,
    SAF_FILE
}

enum class SyncRole {
    NONE,
    OWNER,
    PARTNER
}

class SafAccessLostException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

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
    val isActive: Boolean = true,
    val reminderTime: String? = null
)

@Serializable
data class SyncPregnancyTest(
    val date: String,
    val result: String
)

@Serializable
data class SyncReminder(
    val type: String,
    val frequency: String = "",
    val timeOfDay: String? = null,
    val nextDate: String? = null,
    val isActive: Boolean = true
)

/**
 * Serializes [PartnerPermissions] canonically as a JSON object while accepting
 * either a canonical JSON object (snake_case or camelCase) or a legacy integer bitmask.
 */
object PartnerPermissionsFlexibleSerializer : KSerializer<PartnerPermissions?> {
    private val delegate = PartnerPermissions.serializer().nullable
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: PartnerPermissions?) {
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): PartnerPermissions? {
        if (decoder !is JsonDecoder) {
            return delegate.deserialize(decoder)
        }
        return when (val element = decoder.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> element.intOrNull?.let { PartnerPermissions.fromBitmask(it) }
            is JsonObject -> decodePermissionsObject(element)
            else -> null
        }
    }

    private fun decodePermissionsObject(obj: JsonObject): PartnerPermissions {
        fun flag(snake: String, camel: String): Boolean =
            (obj[snake] as? JsonPrimitive)?.booleanOrNull
                ?: (obj[camel] as? JsonPrimitive)?.booleanOrNull
                ?: false

        return PartnerPermissions(
            shareCyclePhase = flag("share_cycle_phase", "shareCyclePhase"),
            shareFlowIntensity = flag("share_flow_intensity", "shareFlowIntensity"),
            shareSymptomsMoods = flag("share_symptoms_moods", "shareSymptomsMoods"),
            shareBirthControl = flag("share_birth_control", "shareBirthControl"),
            sharePregnancyTests = flag("share_pregnancy_tests", "sharePregnancyTests"),
            shareSexualActivity = flag("share_sexual_activity", "shareSexualActivity")
        )
    }
}

@Serializable
data class SyncPayload(
    val lastUpdated: Long,
    val birthControl: SyncBirthControl? = null,
    val entries: List<SyncEntry> = emptyList(),
    val pregnancyTests: List<SyncPregnancyTest> = emptyList(),
    val reminders: List<SyncReminder> = emptyList(),
    @Serializable(with = PartnerPermissionsFlexibleSerializer::class)
    val permissions: PartnerPermissions? = null,
    val payloadVersion: Long = 0L
)
