package com.novarytm.ui

import kotlinx.serialization.Serializable

@Serializable
data class BirthControlInfo(
    val type: String,
    val description: String,
    val howItWorks: String,
    val sideEffects: List<String>,
    val effectiveness: String
)

object BirthControlLibrary {
    val items = listOf(
        BirthControlInfo(
            type = "Combined Pill",
            description = "A daily pill containing estrogen and progestogen.",
            howItWorks = "Prevents ovulation and thickens cervical mucus.",
            sideEffects = listOf("Nausea", "Breast tenderness", "Mood changes", "Headaches"),
            effectiveness = "91% - 99%"
        ),
        BirthControlInfo(
            type = "Hormonal IUD",
            description = "A small T-shaped device placed in the uterus.",
            howItWorks = "Releases progestogen to thin the uterine lining.",
            sideEffects = listOf("Irregular bleeding", "Cramping", "Acne"),
            effectiveness = "99%+"
        ),
        BirthControlInfo(
            type = "Copper IUD (Non-Hormonal)",
            description = "A hormone-free device that uses copper to prevent pregnancy.",
            howItWorks = "Copper is toxic to sperm and prevents fertilization.",
            sideEffects = listOf("Heavier periods", "Increased cramping"),
            effectiveness = "99%+"
        ),
        BirthControlInfo(
            type = "Implant",
            description = "A small rod placed under the skin of the arm.",
            howItWorks = "Slowly releases progestogen over 3 years.",
            sideEffects = listOf("Unpredictable bleeding", "Weight gain"),
            effectiveness = "99.9%"
        )
    )
}
