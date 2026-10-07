package com.yourapp.midi

/** Output API type only: no backend, MIDI decisions or copied predicates. */
class MidiInputManager {
    fun allNotesOff() {}
    fun sendNoteOn(channel: Int, note: Int, velocity: Int) {}
    fun sendNoteOff(channel: Int, note: Int) {}
    fun sendProgramChange(channel: Int, program: Int, bankMsb: Int = 0, bankLsb: Int = 0) {}
}
