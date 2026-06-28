package com.novarytm.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class AppColors(
    val primary: Color,
    val primaryVariant: Color,
    val secondary: Color,
    val secondaryVariant: Color,
    val background: Color,
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val todayHighlight: Color,
    val name: String
)

val DefaultThemeColors = AppColors(
    primary = Color(0xFFD48C95), // Pink
    primaryVariant = Color(0xFFF18A8E), // Lighter Pink
    secondary = Color(0xFF88BC8F), // Sage Green
    secondaryVariant = Color(0xFFD6EFD8), // Lighter Green
    background = Color(0xFFFAF8F7),
    surface = Color.White,
    textPrimary = Color.Black,
    textSecondary = Color.Gray,
    todayHighlight = Color(0xFF6D4C41),
    name = "Default"
)

val BabyBlueThemeColors = AppColors(
    primary = Color(0xFFAEC6CF), // Pastel Blue
    primaryVariant = Color(0xFFCDE0E7), // Lighter Pastel Blue
    secondary = Color(0xFF9FB5A8), // Cool Sage Green
    secondaryVariant = Color(0xFFD6E3D8), // Lighter Cool Sage
    background = Color(0xFFF6F9FB), // Soft Cool White
    surface = Color.White,
    textPrimary = Color.Black,
    textSecondary = Color.Gray,
    todayHighlight = Color(0xFF546E7A), // Slate Blue
    name = "Baby Blue"
)

val LocalAppColors = staticCompositionLocalOf<AppColors> {
    error("No AppColors provided")
}
