package com.yourapp.yamahaarranger.ui

/** Discard only logs for plain JVM tests. Actual diagnostic classes run. */
object DebugLog {
    fun add(message: String) {}
    fun setLongText(message: String) {}
}
