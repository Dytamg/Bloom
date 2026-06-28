package com.novarytm.utils

import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
    encodeDefaults = true
    prettyPrint = false
}
