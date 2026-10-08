package com.yourapp.yamahaarranger.ui
/** Log-only facade: production MIDI sends and ownership remain real. */
object DebugLog {
    fun add(message:String){};fun setLongText(message:String){}
    fun traceMidi(message:String){};fun traceError(message:String){}
}
