package com.novarytm.utils

expect fun getCurrentLanguageCode(): String
expect fun getCurrentRegionCode(): String

fun formatDateByRegion(dateStr: String): String {
    val parts = dateStr.split("-")
    if (parts.size != 3) return dateStr
    val (year, month, day) = parts
    
    return when (getCurrentRegionCode().uppercase()) {
        "US" -> "$month/$day/${year.takeLast(2)}"
        "JP", "CN", "KR", "TW" -> "${year.takeLast(2)}/$month/$day"
        else -> "$day/$month/${year.takeLast(2)}"
    }
}
