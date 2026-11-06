package com.example.maps.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Night-sky background, close to the renderer's clear color. */
val Space = Color(0xFF05080F)

/** Translucent ocean blue of the floating buttons, and their thin outline. */
val ControlBlue = Color(0xCC1D5F8F)
val ControlStroke = Color(0x40FFFFFF)

/** Accent for active toggles and captions, matching the current tour marker. */
val Gold = Color(0xFFFFC547)

/** Always dark: the globe floats in space whatever the system theme. */
@Composable
fun MapsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ControlBlue,
            onPrimary = Color.White,
            background = Space,
            surface = Space,
            onSurface = Color.White,
        ),
        content = content,
    )
}
