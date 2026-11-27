package com.bloom.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PartnerPermissions(
    @SerialName("share_cycle_phase") val shareCyclePhase: Boolean = false,
    @SerialName("share_flow_intensity") val shareFlowIntensity: Boolean = false,
    @SerialName("share_symptoms_moods") val shareSymptomsMoods: Boolean = false,
    @SerialName("share_birth_control") val shareBirthControl: Boolean = false,
    @SerialName("share_pregnancy_tests") val sharePregnancyTests: Boolean = false,
    @SerialName("share_sexual_activity") val shareSexualActivity: Boolean = false
) {
    fun toBitmask(): Int {
        var mask = 0
        if (shareCyclePhase) mask = mask or BIT_CYCLE_PHASE
        if (shareFlowIntensity) mask = mask or BIT_FLOW_INTENSITY
        if (shareSymptomsMoods) mask = mask or BIT_SYMPTOMS_MOODS
        if (shareSexualActivity) mask = mask or BIT_SEXUAL_ACTIVITY
        if (shareBirthControl) mask = mask or BIT_BIRTH_CONTROL
        if (sharePregnancyTests) mask = mask or BIT_PREGNANCY_TESTS
        return mask
    }

    companion object {
        const val BIT_CYCLE_PHASE = 0x01
        const val BIT_FLOW_INTENSITY = 0x02
        const val BIT_SYMPTOMS_MOODS = 0x04
        const val BIT_SEXUAL_ACTIVITY = 0x08
        const val BIT_BIRTH_CONTROL = 0x10
        const val BIT_PREGNANCY_TESTS = 0x20

        fun fromBitmask(mask: Int): PartnerPermissions = PartnerPermissions(
            shareCyclePhase = (mask and BIT_CYCLE_PHASE) != 0,
            shareFlowIntensity = (mask and BIT_FLOW_INTENSITY) != 0,
            shareSymptomsMoods = (mask and BIT_SYMPTOMS_MOODS) != 0,
            shareBirthControl = (mask and BIT_BIRTH_CONTROL) != 0,
            sharePregnancyTests = (mask and BIT_PREGNANCY_TESTS) != 0,
            shareSexualActivity = (mask and BIT_SEXUAL_ACTIVITY) != 0
        )
    }
}
