package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.layout.ColumnScope

/**
 * Compatibility alias for the SX900-inspired MainScreen panel content DSL.
 * MainScreen already imports the Column composable function; Kotlin keeps
 * type and function namespaces separate, so this alias fixes the receiver type.
 */
typealias Column = ColumnScope
