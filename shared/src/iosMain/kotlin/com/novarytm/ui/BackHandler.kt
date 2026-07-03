package com.novarytm.ui

import androidx.compose.runtime.Composable

@Composable
actual fun AppBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // iOS swipe back / edge navigation gesture is handled natively by iOS view controllers
}
