package com.yourapp.yamahaarranger.ui
object DebugLog {val rows=mutableListOf<String>();fun add(s:String){rows.add("${audit.Clock.ms}|$s")};fun setLongText(s:String){};fun traceAudio(s:String){};fun traceError(s:String){} }
