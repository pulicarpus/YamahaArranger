package com.yourapp.yamahaarranger.audio

/** Host compilation facade only; Mockito captures these boundary calls.
 * Never used by the Android app and contains no routing/CASM/ownership logic.
 */
class AudioEngineManager {
    fun allNotesOff() {}
    fun armChordDiagnostic() {}
    fun markChordDiagnostic(id: Long) {}
    fun stopChordDiagnostic() {}
    fun noteOnChannel(channel: Int, midiNote: Int, velocity01: Float) {}
    fun noteOffChannel(channel: Int, midiNote: Int) {}
    fun noteOnStyleChannel(channel: Int, note: Int, velocity: Float, sourceChannel: Int,
        sourceNote: Int, styleBank: Int, tick: Long, id: Long, sampled: Boolean,
        chordId: Long = 0L, operation: Int = 0) {}
    fun noteOffStyleChannel(channel: Int, note: Int, sourceChannel: Int, sourceNote: Int,
        bank: Int, tick: Long, id: Long, chordId: Long, operation: Int) {}
    fun setChannelProgram(channel: Int, program: Int, bank: Int = 0, voiceName: String? = null) {}
    fun setChannelMixer(channel: Int, volume: Int = 127, pan: Int = 64, expression: Int = 127,
        reverbSend: Int = 40, chorusSend: Int = 0) {}
    fun setChannelExpression(channel: Int, expression: Int) {}
    fun noteOn(note:Int,velocity:Float) {}
    fun noteOff(note:Int) {}
    fun setKeyboardReleaseTime(value:Int) {}
    fun setKeyboardSustain(enabled:Boolean) {}
    fun noteZoneReport():String=""
}
