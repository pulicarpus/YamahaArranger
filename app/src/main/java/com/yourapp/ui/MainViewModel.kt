package com.yourapp.yamahaarranger.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourapp.yamahaarranger.arranger.ArrangerBrain
import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.style.StyleRepository
import com.yourapp.yamahaarranger.style.StyleChannelOverride
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject

object DebugLog {
    private val _lines = ConcurrentLinkedQueue<String>()
    private val _fullLines = ConcurrentLinkedQueue<String>()
    private const val MAX_LINES = 30
    private const val MAX_FULL_LINES = 30000
    @Volatile private var longText: String = ""
    @JvmStatic fun add(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts] $msg"
        _lines.add(line)
        while (_lines.size > MAX_LINES) _lines.poll()
        _fullLines.add(line)
        while (_fullLines.size > MAX_FULL_LINES) _fullLines.poll()
    }
    @JvmStatic fun traceAudio(msg: String) = add("🔊 AUDIO $msg")
    @JvmStatic fun traceMidi(msg: String) = add("📤 MIDI OUT $msg")
    @JvmStatic fun traceError(msg: String) = add("❌ ERROR $msg")
    fun getAll(): List<String> = _lines.toList()
    fun getFullAll(): List<String> = _fullLines.toList()
    fun setLongText(text: String) { longText = text }
    fun getLongText(): String = longText
    fun getFullText(): String = _fullLines.joinToString("\n")
    fun clear() { _lines.clear(); _fullLines.clear(); longText = "" }
}

data class VoiceSlot(val channel: Int, val label: String, val program: Int, val bank: Int = 0, val locked: Boolean = false, val styleVolume: Int = 100, val styleMuted: Boolean = false) {
    fun displayName(): String {
        if (bank == 128) return "DRUM KIT"
        return GM_VOICES.firstOrNull { it.second == program }?.first ?: "prog$program"
    }
    fun isDrum(): Boolean = bank == 128
}

val GM_VOICES: List<Pair<String, Int>> = listOf(
    "Piano" to 0, "Bright Piano" to 1, "E.Grand Piano" to 2, "Honky Tonk" to 3,
    "E.Piano 1" to 4, "E.Piano 2" to 5, "Harpsichord" to 6, "Clavi" to 7,
    "Celesta" to 8, "Glockenspiel" to 9, "Music Box" to 10, "Vibraphone" to 11,
    "Marimba" to 12, "Xylophone" to 13, "Tubular Bells" to 14, "Dulcimer" to 15,
    "Drawbar Organ" to 16, "Perc Organ" to 17, "Rock Organ" to 18, "Church Organ" to 19,
    "Reed Organ" to 20, "Accordion" to 21, "Harmonica" to 22, "Tango Accordion" to 23,
    "Nylon Guitar" to 24, "Steel Guitar" to 25, "Jazz Guitar" to 26, "Clean Guitar" to 27,
    "Muted Guitar" to 28, "Overdrive Gt" to 29, "Distortion Gt" to 30, "Guitar Harmonics" to 31,
    "Acoustic Bass" to 32, "Finger Bass" to 33, "Pick Bass" to 34, "Fretless Bass" to 35,
    "Slap Bass 1" to 36, "Slap Bass 2" to 37, "Synth Bass 1" to 38, "Synth Bass 2" to 39,
    "Violin" to 40, "Viola" to 41, "Cello" to 42, "Contrabass" to 43, "Tremolo Strings" to 44,
    "Pizzicato Strings" to 45, "Orchestral Harp" to 46, "Timpani" to 47,
    "Strings Ensemble 1" to 48, "Strings Ensemble 2" to 49, "Synth Strings 1" to 50, "Synth Strings 2" to 51,
    "Choir Aahs" to 52, "Voice Oohs" to 53, "Synth Voice" to 54, "Orchestra Hit" to 55,
    "Trumpet" to 56, "Trombone" to 57, "Tuba" to 58, "Muted Trumpet" to 59, "French Horn" to 60,
    "Brass Section" to 61, "Synth Brass 1" to 62, "Synth Brass 2" to 63,
    "Soprano Sax" to 64, "Alto Sax" to 65, "Tenor Sax" to 66, "Baritone Sax" to 67,
    "Oboe" to 68, "English Horn" to 69, "Bassoon" to 70, "Clarinet" to 71,
    "Piccolo" to 72, "Flute" to 73, "Recorder" to 74, "Pan Flute" to 75, "Blown Bottle" to 76,
    "Shakuhachi" to 77, "Whistle" to 78, "Ocarina" to 79,
    "Synth Lead (Square)" to 80, "Synth Lead (Saw)" to 81, "Synth Lead (Calliope)" to 82,
    "Synth Lead (Chiff)" to 83, "Synth Lead (Charang)" to 84, "Synth Lead (Voice)" to 85,
    "Synth Lead (Fifths)" to 86, "Synth Lead (Bass+Lead)" to 87,
    "Synth Pad (New Age)" to 88, "Synth Pad (Warm)" to 89, "Synth Pad (Polysynth)" to 90,
    "Synth Pad (Choir)" to 91, "Synth Pad (Bowed)" to 92, "Synth Pad (Metallic)" to 93,
    "Synth Pad (Halo)" to 94, "Synth Pad (Sweep)" to 95,
    "FX (Rain)" to 96, "FX (Soundtrack)" to 97, "FX (Crystal)" to 98, "FX (Atmosphere)" to 99,
    "FX (Brightness)" to 100, "FX (Goblins)" to 101, "FX (Echoes)" to 102, "FX (Sci-Fi)" to 103,
    "Sitar" to 104, "Banjo" to 105, "Shamisen" to 106, "Koto" to 107, "Kalimba" to 108,
    "Bagpipe" to 109, "Fiddle" to 110, "Shanai" to 111, "Tinkle Bell" to 112, "Agogo" to 113,
    "Steel Drums" to 114, "Woodblock" to 115, "Taiko Drum" to 116, "Melodic Tom" to 117,
    "Synth Drum" to 118, "Reverse Cymbal" to 119, "Guitar Fret Noise" to 120, "Breath Noise" to 121,
    "Seashore" to 122, "Bird Tweet" to 123, "Telephone Ring" to 124, "Helicopter" to 125,
    "Applause" to 126, "Gunshot" to 127
)

fun defaultVoices(): List<VoiceSlot> = (0..15).map { ch ->
    when (ch) {
        9 -> VoiceSlot(9, "Ch9 (Drum)", 0, 128)
        10 -> VoiceSlot(10, "Ch10 (Bass)", 33)
        11 -> VoiceSlot(11, "Ch11 (Chord1)", 0)
        12 -> VoiceSlot(12, "Ch12 (Chord2)", 24)
        13 -> VoiceSlot(13, "Ch13 (Pad)", 48)
        14 -> VoiceSlot(14, "Ch14 (Phrase1)", 56)
        15 -> VoiceSlot(15, "Ch15 (Phrase2)", 65)
        else -> VoiceSlot(ch, "Ch$ch", 0)
    }
}

data class MainUiState(
    val styleName: String = "No Style Loaded", val tempoBpm: Int = 120, val transpose: Int = 0,
    val isPlaying: Boolean = false, val activeSection: String = "Main A", val detectedChordLabel: String = "", val autoFill: Boolean = true,
    val midiStatus: String = "No MIDI device", val midiOutEnabled: Boolean = false, val soundFontName: String = "None",
    val styleVolume: Int = 100, val voiceVolume: Int = 100, val masterVolume: Int = 110,
    val activeBank: Int = 1, val activeRegSlot: Int = 0, val voiceName: String = "GrandPiano",
    val right2Name: String = "OFF", val splitPoint: String = "C4", val voiceAssignments: List<VoiceSlot> = defaultVoices()
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val arrangerBrain: ArrangerBrain,
    private val styleRepository: StyleRepository,
    private val contentResolver: ContentResolverProvider,
    private val midiInputManager: MidiInputManager,
    private val audioEngine: AudioEngineManager
) : ViewModel() {
    private val _styleName = MutableStateFlow("No Style Loaded")
    private val _midiStatus = MutableStateFlow("No MIDI device")
    private val _midiOutEnabled = MutableStateFlow(false)
    private val _transpose = MutableStateFlow(0)
    private val _soundFontName = MutableStateFlow("None")
    private val _styleVolume = MutableStateFlow(100)
    private val _voiceVolume = MutableStateFlow(100)
    private val _masterVolume = MutableStateFlow(110)
    private val _activeBank = MutableStateFlow(1)
    private val _activeRegSlot = MutableStateFlow(0)
    private val _voiceAssignments = MutableStateFlow(defaultVoices())
    private var activeChordNotes: List<Int> = emptyList()

    val uiState: StateFlow<MainUiState> = combine(
        arrangerBrain.state,
        combine(_styleName, _midiStatus) { s, m -> s to m },
        combine(_transpose, _soundFontName) { t, sf -> t to sf },
        combine(_voiceVolume, _masterVolume) { vv, mv -> vv to mv },
        combine(_activeBank, _activeRegSlot, _voiceAssignments) { b, r, v -> Triple(b, r, v) }
    ) { arranger, (styleName, midi), (transpose, sfName), (voiceVol, masterVol), (bank, regSlot, voices) ->
        MainUiState(styleName = styleName, tempoBpm = arranger.tempoBpm, transpose = transpose,
            isPlaying = arranger.isPlaying, activeSection = displayLabelFor(arranger.currentSection),
            detectedChordLabel = arranger.currentChordLabel, autoFill = arranger.autoFill, midiStatus = midi, midiOutEnabled = _midiOutEnabled.value,
            soundFontName = sfName, voiceVolume = voiceVol, masterVolume = masterVol,
            activeBank = bank, activeRegSlot = regSlot, voiceAssignments = voices)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    init {
        arrangerBrain.attachScope(viewModelScope)
        audioEngine.start()
        DebugLog.add("🎵 ViewModel init")
        DebugLog.add("📂 SF2 folder: Download/YamahaArranger/SF2")
        viewModelScope.launch {
            withContext(Dispatchers.IO) { contentResolver.ensureSoundFontFolder() }
            autoLoadSoundFont()
        }
        midiInputManager.onNoteOn = { note, velocity -> arrangerBrain.onKeyboardNoteOn(note, velocity / 127f) }
        midiInputManager.onNoteOff = { note -> arrangerBrain.onKeyboardNoteOff(note) }
    }

    fun connectFirstAvailableMidiDevice() {
        viewModelScope.launch {
            repeat(5) { attempt ->
                val ok = midiInputManager.connectFirstAvailableDevice()
                if (ok) {
                    _midiStatus.value = midiInputManager.connectedDeviceName ?: "MIDI connected"
                    DebugLog.add("✅ MIDI: ${_midiStatus.value}")
                    return@launch
                }
                delay(1500L)
            }
            _midiStatus.value = "No MIDI device"
        }
    }
    fun refreshMidiConnection() { _midiStatus.value = "Connecting…"; connectFirstAvailableMidiDevice() }
    fun toggleMidiOut() { val newVal = !_midiOutEnabled.value; _midiOutEnabled.value = newVal; midiInputManager.midiOutEnabled = newVal; DebugLog.add(if (newVal) "📤 MIDI OUT: ON" else "📤 MIDI OUT: OFF") }
    override fun onCleared() { midiInputManager.close(); audioEngine.stop(); super.onCleared() }

    fun onKeyboardNoteOn(midiNote: Int, velocity: Float) = arrangerBrain.onKeyboardNoteOn(midiNote, velocity)
    fun onKeyboardNoteOff(midiNote: Int) = arrangerBrain.onKeyboardNoteOff(midiNote)
    fun toggleAutoFill() = arrangerBrain.setAutoFill(!arrangerBrain.state.value.autoFill)

    fun onSectionSelected(sectionLabel: String) {
        val section = SECTION_BUTTON_MAP[sectionLabel] ?: return
        if (section in MAIN_VARIATIONS) arrangerBrain.selectMainVariation(section) else arrangerBrain.selectSection(section)
    }
    fun onSyncStart() { }
    fun onStartStop() = arrangerBrain.startStop()
    fun onTapTempo() { }
    fun onTempoDown() { arrangerBrain.setTempo((arrangerBrain.state.value.tempoBpm - 5).coerceIn(20, 280)) }
    fun onTempoUp() { arrangerBrain.setTempo((arrangerBrain.state.value.tempoBpm + 5).coerceIn(20, 280)) }
    fun onTransposeDown() { _transpose.value = (_transpose.value - 1).coerceIn(-12, 12) }
    fun onTransposeUp() { _transpose.value = (_transpose.value + 1).coerceIn(-12, 12) }
    fun onStyleVolumeChange(value: Int) { _styleVolume.value = value }
    fun onVoiceVolumeChange(value: Int) { _voiceVolume.value = value }
    fun onMasterVolumeChange(value: Int) { _masterVolume.value = value }

    // Temporary registration-as-chord pads for style development/testing.
    fun onBankChange(bank: Int) { _activeBank.value = bank.coerceIn(1, 8) }
    fun onRegSlotTap(slot: Int) {
        val chords = listOf(
            "C" to intArrayOf(48, 52, 55), "Dm" to intArrayOf(50, 53, 57),
            "Em" to intArrayOf(52, 55, 59), "F" to intArrayOf(53, 57, 60),
            "G" to intArrayOf(55, 59, 62), "Am" to intArrayOf(57, 60, 64),
            "Bdim" to intArrayOf(59, 62, 65), "C7" to intArrayOf(60, 64, 67, 70)
        )
        val index = slot.coerceIn(0, 7)
        activeChordNotes.forEach(arrangerBrain::onKeyboardNoteOff)
        activeChordNotes = chords[index].second.toList()
        activeChordNotes.forEach { arrangerBrain.onKeyboardNoteOn(it, 0.82f) }
        _activeRegSlot.value = index
        DebugLog.add("🎹 CHORD PAD ${index + 1}: ${chords[index].first} (${activeChordNotes.joinToString()})")
    }
    fun onRegSlotSave(slot: Int) { Timber.i("Save reg bank=${_activeBank.value} slot=$slot") }

    fun setChannelVoice(channel: Int, program: Int, bank: Int) {
        val updated = _voiceAssignments.value.map { slot ->
            if (slot.channel == channel) {
                audioEngine.setChannelProgram(channel, program, bank)
                midiInputManager.sendProgramChange(channel, program, bank)
                DebugLog.add("🎼 Ch$channel → prog$program (bank$bank)")
                slot.copy(program = program, bank = bank)
            } else slot
        }
        _voiceAssignments.value = updated
    }
    fun setStyleChannelVolume(channel: Int, volume: Int) {
        val v = volume.coerceIn(0, 127)
        val slot = _voiceAssignments.value.firstOrNull { it.channel == channel } ?: return
        _voiceAssignments.value = _voiceAssignments.value.map { if (it.channel == channel) it.copy(styleVolume = v) else it }
        arrangerBrain.setStyleChannelVolume(channel, v)
    }

    fun toggleStyleChannelMute(channel: Int) {
        val slot = _voiceAssignments.value.firstOrNull { it.channel == channel } ?: return
        val muted = !slot.styleMuted
        _voiceAssignments.value = _voiceAssignments.value.map { if (it.channel == channel) it.copy(styleMuted = muted) else it }
        arrangerBrain.setStyleChannelMute(channel, muted)
        DebugLog.add(if (muted) "🔇 STYLE CH$channel muted" else "🔊 STYLE CH$channel unmuted")
    }

    fun setStyleChannelVoice(channel: Int, program: Int, bank: Int) {
        val slot = _voiceAssignments.value.firstOrNull { it.channel == channel } ?: return
        _voiceAssignments.value = _voiceAssignments.value.map { if (it.channel == channel) it.copy(program = program, bank = bank) else it }
        arrangerBrain.setStyleChannelProgram(channel, program, bank)
        DebugLog.add("🎼 STYLE CH$channel → prog$program bank$bank")
    }

    fun toggleChannelLock(channel: Int) {
        val updated = _voiceAssignments.value.map { slot ->
            if (slot.channel == channel) {
                val newLock = !slot.locked
                DebugLog.add(if (newLock) "🔒 Ch$channel locked" else "🔓 Ch$channel unlocked")
                slot.copy(locked = newLock)
            } else slot
        }
        _voiceAssignments.value = updated
        arrangerBrain.updateLockedChannels(updated.filter { it.locked }.map { it.channel }.toSet())
    }
    fun playTestTone() {
        viewModelScope.launch {
            DebugLog.add("🔊 TEST TONE")
            for (note in intArrayOf(60, 64, 67)) { audioEngine.testTone(note, 0.9f); delay(400); audioEngine.noteOff(note) }
        }
    }

    fun onStyleFilePicked(uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { contentResolver.readBytes(uri) }
            if (bytes == null) { DebugLog.add("❌ Cannot read style"); return@launch }
            val fileName = contentResolver.fileName(uri) ?: "style.sty"
            val parsed = withContext(Dispatchers.Default) { styleRepository.loadStyle(fileName, bytes) }
            if (parsed == null) { DebugLog.add("❌ Parse fail: $fileName"); return@launch }
            arrangerBrain.loadStyle(parsed); _styleName.value = fileName; DebugLog.add("✅ Loaded: $fileName")
        }
    }

    private suspend fun autoLoadSoundFont() {
        val found = withContext(Dispatchers.IO) { contentResolver.findSoundFont() }
        if (found == null) {
            DebugLog.add("📂 SF2 folder ready: Download/YamahaArranger/SF2")
            return
        }
        val (uri, name) = found
        DebugLog.add("🔄 Auto-loading SF2: $name")
        loadSoundFontUri(uri, name)
    }

    private suspend fun loadSoundFontUri(uri: Uri, displayName: String) {
        val cached = withContext(Dispatchers.IO) {
            contentResolver.copySoundFontToCache(uri, displayName)
        }
        if (cached == null) {
            DebugLog.add("❌ SF2 cache failed: $displayName")
            return
        }
        val ok = withContext(Dispatchers.Default) {
            audioEngine.unloadSoundFont()
            audioEngine.loadSoundFont(cached.absolutePath)
        }
        _soundFontName.value = if (ok) displayName else "Load failed"
        DebugLog.add(if (ok) "✅ SF2 loaded: $displayName" else "❌ SF2 load failed: $displayName")
    }

    fun onSoundFontFilePicked(uri: Uri) {
        viewModelScope.launch {
            DebugLog.add("📂 SF2 picker…")
            val sourceName = contentResolver.fileName(uri) ?: "font.sf2"
            val safeName = sourceName.substringAfterLast('/').ifBlank { "font.sf2" }
                .let { if (it.lowercase().endsWith(".sf2")) it else "$it.sf2" }

            val storedUri = withContext(Dispatchers.IO) {
                contentResolver.saveSoundFont(uri, safeName)
            }
            if (storedUri == null) {
                DebugLog.add("❌ Could not store SF2 in Download/YamahaArranger/SF2")
                return@launch
            }
            DebugLog.add("📂 SF2 stored: Download/YamahaArranger/SF2/$safeName")
            DebugLog.add("🔄 Loading stored SF2 directly…")
            loadSoundFontUri(storedUri, safeName)
        }
    }

    companion object {
        private val SECTION_BUTTON_MAP = mapOf(
            "Intro 1" to ArrangerSection.IntroA, "Intro 2" to ArrangerSection.IntroB, "Intro 3" to ArrangerSection.IntroC,
            "Main A" to ArrangerSection.MainA, "Main B" to ArrangerSection.MainB, "Main C" to ArrangerSection.MainC, "Main D" to ArrangerSection.MainD,
            "Fill A" to ArrangerSection.FillAA, "Fill B" to ArrangerSection.FillBB, "Fill C" to ArrangerSection.FillCC, "Fill D" to ArrangerSection.FillDD,
            "Ending 1" to ArrangerSection.EndingA, "Ending 2" to ArrangerSection.EndingB, "Ending 3" to ArrangerSection.EndingC)
        private val MAIN_VARIATIONS = setOf(ArrangerSection.MainA, ArrangerSection.MainB, ArrangerSection.MainC, ArrangerSection.MainD)
        private fun displayLabelFor(section: ArrangerSection): String = SECTION_BUTTON_MAP.entries.firstOrNull { it.value == section }?.key ?: section.name
    }
}