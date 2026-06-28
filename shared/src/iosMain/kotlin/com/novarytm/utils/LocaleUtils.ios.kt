package com.novarytm.utils

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.countryCode

actual fun getCurrentLanguageCode(): String {
    return NSLocale.currentLocale.languageCode
}

actual fun getCurrentRegionCode(): String {
    return NSLocale.currentLocale.countryCode ?: "US"
}
