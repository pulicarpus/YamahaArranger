package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StyleSectionModel
import com.yourapp.yamahaarranger.ui.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** CASM-aware style playback: section policy -> NTR/NTT -> limits -> audio + optional MIDI OUT. */
class StyleSequencer(
    private val audioEngine: AudioEngineManager,
    private val midiInputManager: MidiInputManager,
    private val scope: CoroutineScope
) {
    private var playbackJob: Job? = null
    var tempoBpm: Int = 120
    var currentChord: DetectedChord? = null
    private var loopCount = 0
    private var voiceMap: Map<Int, String> = emptyMap()
    private var lastAppliedSection = ""
    private var lockedChannels: Set<Int> = emptySet()
    private val activeTransposedNotes = mutableMapOf<String, Int>()

    fun setLockedChannels(channels: Set<Int>) {
        lockedChannels = channels
        DebugLog.add("🔒 Locked channels updated: $channels")
    }

    fun setVoiceMap(vm: Map<Int, String>) {
        voiceMap = vm
        DebugLog.add("🎛 Voice map loaded: ${vm.size} channels")
    }
