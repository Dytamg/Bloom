package com.novarytm.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PartnerPermissions(
    @SerialName("share_cycle_phase") val shareCyclePhase: Boolean = true,
    @SerialName("share_flow_intensity") val shareFlowIntensity: Boolean = false,
    @SerialName("share_symptoms_moods") val shareSymptomsMoods: Boolean = false,
    @SerialName("share_birth_control") val shareBirthControl: Boolean = false,
    @SerialName("share_pregnancy_tests") val sharePregnancyTests: Boolean = false,
    @SerialName("share_sexual_activity") val shareSexualActivity: Boolean = false
)
