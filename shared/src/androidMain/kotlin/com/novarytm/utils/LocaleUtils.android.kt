package com.novarytm.utils

import java.util.Locale

actual fun getCurrentLanguageCode(): String {
    return Locale.getDefault().language
}

actual fun getCurrentRegionCode(): String {
    return Locale.getDefault().country
}
