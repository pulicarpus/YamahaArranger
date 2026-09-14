package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Dark, LCD-inspired palette to match the ORG24-style hardware look.
private val ArrangerColors = darkColorScheme(
    primary = Color(0xFF3DDC97),      // LCD green accent
    secondary = Color(0xFFFFA53D),    // amber section-active indicator
    background = Color(0xFF121417),
    surface = Color(0xFF1C1F24),
    onBackground = Color(0xFFE6E6E6),
    onSurface = Color(0xFFE6E6E6)
)

@Composable
fun YamahaArrangerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ArrangerColors,
        content = content
    )
}
