package com.yourapp.yamahaarranger.ui

// BEGIN REG PHASE2 IMPORT
import kotlinx.coroutines.async
// END REG PHASE2 IMPORT
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourapp.yamahaarranger.arranger.ArrangerBrain
import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.audio.MixerConsoleState
import com.yourapp.yamahaarranger.audio.KeyboardMixerDetail
import com.yourapp.yamahaarranger.audio.ReleaseControlState
import com.yourapp.yamahaarranger.audio.StyleMixerChannels
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.yourapp.yamahaarranger.audio.KeyboardPart
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.audio.SoundFontInspector
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.style.StyleRepository
import com.yourapp.yamahaarranger.style.StyleChannelOverride
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
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
import com.yourapp.yamahaarranger.lifecycle.ReadyToPlayGate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

object DebugLog {
    private const val MAX_LINES = 30
    private const val MAX_FULL_LINES = 30000
    // The monitor protects bounded append/copy/clear only. No external call,
    // formatter, JNI, MIDI or callback is made while holding it.
    private val logLock = Any()
    private val _lines = java.util.ArrayDeque<String>(MAX_LINES)
    private val _fullLines = java.util.ArrayDeque<String>(MAX_FULL_LINES)
    private val timestamp = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
    private class LogTiming {
        @Volatile var active = true
        val count = java.util.concurrent.atomic.AtomicLong(0)
        val formatNs = java.util.concurrent.atomic.AtomicLong(0)
        val maxFormatNs = java.util.concurrent.atomic.AtomicLong(0)
        val maxWaitNs = java.util.concurrent.atomic.AtomicLong(0)
        val maxHoldNs = java.util.concurrent.atomic.AtomicLong(0)
        fun maximum(a: java.util.concurrent.atomic.AtomicLong, v: Long) {
            var old = a.get()
            while (v > old && !a.compareAndSet(old, v)) old = a.get()
        }
        fun record(format: Long, wait: Long, hold: Long) {
            if (!active) return
            count.incrementAndGet(); formatNs.addAndGet(format)
            maximum(maxFormatNs, format); maximum(maxWaitNs, wait); maximum(maxHoldNs, hold)
        }
    }
    @Volatile private var timing: LogTiming? = null
    fun beginTiming() { timing?.active = false; timing = LogTiming() }
    fun endTiming() { timing?.active = false }
    fun timingReport(): String {
        val t = timing ?: return "F12_LOGGER unavailable=1"
        return "F12_LOGGER active=${t.active} calls=${t.count.get()} formatTotalNs=${t.formatNs.get()} maxFormatNs=${t.maxFormatNs.get()} maxWaitNs=${t.maxWaitNs.get()} maxHoldNs=${t.maxHoldNs.get()} snapshotAtomic=0 audioCauseProven=0"
    }
    @Volatile private var longText: String = ""
    @JvmStatic fun add(msg: String) {
        val diagnostic = timing?.takeIf { it.active }
        val began = if (diagnostic != null) System.nanoTime() else 0L
        val formatter = timestamp.get()
        // Preserve system timezone changes as well as the original format.
        formatter.timeZone = java.util.TimeZone.getDefault()
        val line = "[${formatter.format(Date())}] $msg"
        val formatted = if (diagnostic != null) System.nanoTime() else 0L
        var acquired = 0L
        var released = 0L
        synchronized(logLock) {
            if (diagnostic != null) acquired = System.nanoTime()
            if (_lines.size == MAX_LINES) _lines.removeFirst()
            _lines.addLast(line)
            if (_fullLines.size == MAX_FULL_LINES) _fullLines.removeFirst()
            _fullLines.addLast(line)
            if (diagnostic != null) released = System.nanoTime()
        }
        diagnostic?.record(formatted - began, acquired - formatted, released - acquired)
    }
    @JvmStatic fun traceAudio(msg: String) = add("🔊 AUDIO $msg")
    @JvmStatic fun traceMidi(msg: String) = add("📤 MIDI OUT $msg")
    @JvmStatic fun traceError(msg: String) = add("❌ ERROR $msg")
    fun getAll(): List<String> = synchronized(logLock) { _lines.toList() }
    fun getFullAll(): List<String> = synchronized(logLock) { _fullLines.toList() }
    fun setLongText(text: String) { longText = text }
    fun getLongText(): String = longText
    fun getFullText(): String = getFullAll().joinToString("\n")
    fun clear() { synchronized(logLock) { _lines.clear(); _fullLines.clear(); longText = "" } }
}

data class VoiceSlot(
    val channel: Int, val label: String, val program: Int, val bank: Int = 0,
    val locked: Boolean = false, val styleVolume: Int = 127, val styleMuted: Boolean = false,
    val stylePan: Int = 64, val styleExpression: Int = 127,
    val styleReverb: Int = 40, val styleChorus: Int = 0, val sf2Name: String? = null,
    /** CPV manual voice saved for this style, if any. */
    val overridePin: com.yourapp.yamahaarranger.style.StyleVoicePin? = null,
    /** ACTIVE, UNVERIFIED (readback failed) or STALE (saved preset not loaded); null without override. */
    val overrideStatus: String? = null
) {
    val hasOverride: Boolean get() = overridePin != null
    fun displayName(): String {
        val raw = sf2Name?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            val n = raw.lowercase().replace(Regex("\\s+"), "")
            when {
                n == "add-dr" || n == "adddr" -> return "Add Drums"
                n == "main-dr" || n == "maindr" -> return "Main Drums"
                n == "piano00" || n == "piano0" -> return "Acoustic Grand Piano"
                n == "bass33" -> return "Finger Bass"
                n == "guitar27" -> return "Clean Guitar"
            }
            val trailing = n.takeLastWhile { it.isDigit() }.toIntOrNull()
            if (trailing != null && trailing in 0..127) {
                GM_VOICES.firstOrNull { it.second == trailing }?.first?.let { return it }
            }
            return raw
        }
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

data class KeyboardVoiceSlot(
    val layer: Int,
    val label: String,
    val program: Int,
    val bank: Int = 0,
    val enabled: Boolean = false,
    val name: String? = null,
    val soundFontId: Long = 0,
    val soundFontName: String = "",
    val available: Boolean = true
) {
    fun displayName(): String {
        if (!available) return "Unavailable: ${name.orEmpty()}"
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return GM_VOICES.firstOrNull { it.second == program }?.first ?: "prog$program"
    }
}

fun defaultKeyboardVoices(): List<KeyboardVoiceSlot> = listOf(
    KeyboardVoiceSlot(0, "RIGHT 1", 0, 0, enabled = true, name = "Piano"),
    KeyboardVoiceSlot(1, "RIGHT 2", 0, 0, enabled = false, name = "Piano"),
    KeyboardVoiceSlot(2, "RIGHT 3", 0, 0, enabled = false, name = "Piano"),
    KeyboardVoiceSlot(3, "LEFT", 0, 0, enabled = true, name = "Piano")
)

data class MainUiState(
    val styleName: String = "No Style Loaded", val tempoBpm: Int = 120, val transpose: Int = 0,
    val isPlaying: Boolean = false, val activeSection: String = "Main A", val detectedChordLabel: String = "", val autoFill: Boolean = true,
    val midiStatus: String = "No MIDI device", val midiOutEnabled: Boolean = false, val soundFontName: String = "None",
    val styleVolume: Int = 127, val leftVolume: Int = 100, val right1Volume: Int = 100, val right2Volume: Int = 100, val right3Volume: Int = 100, val masterVolume: Int = 100,
    val activeBank: Int = 1, val activeRegSlot: Int = 0, val voiceName: String = "GrandPiano",
    val right2Name: String = "OFF", val splitPoint: String = "C4",
    val acmpEnabled: Boolean = true, val leftVoiceEnabled: Boolean = true, val sustainEnabled: Boolean = false, val releaseTime: Int = 64, val releaseEnabled: Boolean = true, val effectiveReleaseTime: Int = 64,
    val rightVoices: List<KeyboardVoiceSlot> = defaultKeyboardVoices(),
    val voiceAssignments: List<VoiceSlot> = emptyList(),
    val availableSoundFonts: List<Pair<Uri, String>> = emptyList(),
    val styleFolders: List<StyleFolder> = emptyList(),
    val styleFiles: List<Pair<Uri, String>> = emptyList(),
    val sf2Presets: List<AudioEngineManager.SfPreset> = emptyList(),
    val sf2Reports: List<SoundFontInspector.Report> = emptyList(),
    val sf2ScanInProgress: Boolean = false,
    val startReady: Boolean = false,
    val readinessReason: String = "Preparing resources..."
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val arrangerBrain: ArrangerBrain,
    private val styleRepository: StyleRepository,
    private val contentResolver: ContentResolverProvider,
    private val midiInputManager: MidiInputManager,
    private val audioEngine: AudioEngineManager
) : ViewModel() {
    // BEGIN LIVE PHASE3 STATE
    private val liveRegistrationState = MutableStateFlow(LiveRegistrationState())
    val liveRegistration: StateFlow<LiveRegistrationState> = liveRegistrationState
    private val liveRequestId = java.util.concurrent.atomic.AtomicLong()

    /** In-memory wiring only; bank creation/SAVE and disk persistence belong to Phase 4. */
    fun installLiveRegistrationBanks(banks: List<com.yourapp.yamahaarranger.registration.RegistrationBank>,
        freeze: com.yourapp.yamahaarranger.registration.FreezeFlags = com.yourapp.yamahaarranger.registration.FreezeFlags()) {
        require(banks.map { it.id }.distinct().size == banks.size && banks.all { it.slots.size == 8 })
        liveRequestId.incrementAndGet()
        liveRegistrationState.value = LiveRegistrationState(banks=banks.map { it.copy(slots=it.slots.toList()) },
            freeze=freeze,message=if(banks.isEmpty()) "NO BANK LOADED" else "READY")
    }
    fun onLiveBankChange(delta: Int) {
        val library=phase4Library
        if(library==null) liveRegistrationState.update { it.move(delta) }
        else { val target=liveRegistrationState.value.move(delta).bank ?: return
            viewModelScope.launch { library.selectBank(target.id) } }
    }
    fun onLiveRegRecall(index: Int): Boolean {
        if(phase4Library?.state?.value?.saving==true) return false
        val snapshot=liveRegistrationState.value
        val bank=snapshot.bank ?: return false
        if(bank.id in (phase4Library?.state?.value?.readOnly ?: emptySet())) return false
        val slot=snapshot.slot(index) ?: return false
        val token=liveRequestId.incrementAndGet()
        liveRegistrationState.update { it.copy(selectedBankId=bank.id,selectedSlot=index,
            selectedMode=slot.outputMode,selectedHasExternal=slot.parts.any { p -> p.external!=null },
            recalling=true,report=null,message="RECALL ${bank.name} ${index+1}…") }
        val completion=registrationCoordinator.recall("${bank.id}:$index",slot,snapshot.freeze)
        viewModelScope.launch {
            val report=try { completion.await() } catch(_: CancellationException) { null }
            if(liveRequestId.get()!=token) return@launch
            liveRegistrationState.update { current -> current.copy(recalling=false,report=report,
                message=liveRecallMessage(report)) }
        }
        return true
    }
    private val liveMainLetter=MutableStateFlow("A")
    init {
        viewModelScope.launch {
            arrangerBrain.state.collect { state ->
                val letter=when(state.currentSection) {
                    ArrangerSection.MainA -> "A"; ArrangerSection.MainB -> "B"
                    ArrangerSection.MainC -> "C"; ArrangerSection.MainD -> "D"; else -> null
                }
                if(letter!=null) liveMainLetter.value=letter
            }
        }
    }
    fun onLiveMainSelected(letter: String) {
        require(letter in listOf("A","B","C","D"))
        liveMainLetter.value=letter
        onSectionSelected("Main $letter")
    }
    fun onLiveFill() { onSectionSelected("Fill ${liveMainLetter.value}") }
    // END LIVE PHASE3 STATE

    // BEGIN LIVE V3 STATE
    /** Read-only: which sections the loaded style's parsed table contains. UNKNOWN until a style has been parsed. */
    private val liveSectionInventoryState = MutableStateFlow(LiveSectionInventory.UNKNOWN)
    val liveSectionInventory: StateFlow<LiveSectionInventory> = liveSectionInventoryState
    // END LIVE V3 STATE

    // BEGIN LIVE PHASE4 ADDITIONS
    private var phase4Library: com.yourapp.yamahaarranger.registration.RegistrationLibraryController? = null
    private val phase4State = MutableStateFlow(com.yourapp.yamahaarranger.registration.RegistrationLibraryState())
    val liveLibrary: StateFlow<com.yourapp.yamahaarranger.registration.RegistrationLibraryState> = phase4State
    fun initializeLiveLibrary(directory: java.nio.file.Path) {
        if(phase4Library!=null) return
        val library=com.yourapp.yamahaarranger.registration.RegistrationLibraryController(
            com.yourapp.yamahaarranger.registration.AtomicBankStore(directory))
        phase4Library=library
        viewModelScope.launch {
            library.state.collect { saved ->
                phase4State.value=saved
                if(saved.loaded) liveRegistrationState.update { old -> old.copy(banks=saved.banks,
                    bankIndex=saved.banks.indexOfFirst { it.id==saved.manifest.activeBankId }.coerceAtLeast(0),
                    freeze=saved.manifest.freeze,message=if(old.recalling) old.message else saved.message) }
            }
        }
        viewModelScope.launch { library.load() }
        viewModelScope.launch { while(true) { delay(100);library.tick() } }
    }
    fun onLiveSaveArm(heldMs: Long) { phase4Library?.arm(heldMs,registrationCoordinator.busy.value) }
    fun onLiveSaveOptions(mode: com.yourapp.yamahaarranger.registration.OutputMode, tempo: Boolean, transpose: Boolean) {
        phase4Library?.options(mode,tempo,transpose)
    }
    fun onLiveSlotTap(index: Int, name: String) {
        val library=phase4Library ?: return
        if(library.state.value.save.armed(System.nanoTime()/1_000_000)) {
            viewModelScope.launch {
                library.saveSlot(index,name,{ registrationCoordinator.busy.value }) {
                    // Wait for any already-started native voice operation, then snapshot actual state.
                    awaitRegistrationVoicesIdle()
                    check(!registrationCoordinator.busy.value) { "WAIT: RECALLING" }
                    check(!audioEngine.readiness.operations.isLocked) { "WAIT: ENGINE OPERATION" }
                    registrationPort.snapshot()
                }
            }
        } else onLiveRegRecall(index)
    }
    fun onLiveUndoSave() { val library=phase4Library ?: return
        viewModelScope.launch { library.undoSave(registrationCoordinator.busy.value) } }
    fun onLiveCreateBank(name: String) { val library=phase4Library ?: return
        if(!registrationCoordinator.busy.value) viewModelScope.launch { library.createBank(name) } }
    fun onLiveFreeze(flags: com.yourapp.yamahaarranger.registration.FreezeFlags) { val library=phase4Library ?: return
        viewModelScope.launch { library.freeze(flags) } }
    fun onLiveDefaultMode(mode: com.yourapp.yamahaarranger.registration.OutputMode) { val library=phase4Library ?: return
        viewModelScope.launch { library.defaultMode(mode) } }
    fun onLiveAddExternal(voice: com.yourapp.yamahaarranger.registration.ExternalVoice) { val library=phase4Library ?: return
        viewModelScope.launch { library.addExternal(voice) } }
    fun onLiveExternalSelected(channel: Int, voice: com.yourapp.yamahaarranger.registration.ExternalVoice) {
        if(registrationCoordinator.busy.value || phase4State.value.saving) return
        selectExternalVoice(KeyboardPart.fromChannel(channel),voice)
    }
    // END LIVE PHASE4 ADDITIONS

    // BEGIN REG PHASE2 ADDITIONS
    private val registrationExternal = MutableStateFlow<List<com.yourapp.yamahaarranger.registration.ExternalVoice?>>(List(4) { null })
    val currentExternal: StateFlow<List<com.yourapp.yamahaarranger.registration.ExternalVoice?>> = registrationExternal
    private val registrationVoiceMutex = kotlinx.coroutines.sync.Mutex()
    private val registrationPending = java.util.concurrent.ConcurrentLinkedQueue<kotlinx.coroutines.Deferred<com.yourapp.yamahaarranger.registration.VoiceResult>>()

    /** Recall SF2 never mirrors bank/program to MIDI OUT; legacy manual API below is unchanged. */
    suspend fun applyKeyboardVoice(part: KeyboardPart, preset: AudioEngineManager.SfPreset,
        mirrorToMidiOut: Boolean = false, isCurrent: () -> Boolean = { true }, timeoutMs: Long = 1500): com.yourapp.yamahaarranger.registration.VoiceResult {
        val failed = com.yourapp.yamahaarranger.registration.VoiceResult.FAILED
        val superseded = com.yourapp.yamahaarranger.registration.VoiceResult.SUPERSEDED
        if(mirrorToMidiOut || timeoutMs <= 0) return failed
        if(!isCurrent()) return superseded
        val layer=part.channel
        val request=voiceRequestIds.incrementAndGet(layer)
        val operation=viewModelScope.async(Dispatchers.Default) {
            withContext(NonCancellable) {
                registrationVoiceMutex.withLock {
                    if(!isCurrent() || voiceRequestIds.get(layer)!=request) return@withLock superseded
                    try {
                        val ok=audioEngine.selectKeyboardPreset(layer,preset) { isCurrent() && voiceRequestIds.get(layer)==request }
                        if(voiceRequestIds.get(layer)!=request) return@withLock superseded
                        // Publish physical native completion even when recall generation changed.
                        // Next serial recall must diff against what the synth actually applied (R-B).
                        if(ok) _rightVoices.update { slots -> slots.map { slot ->
                            if(slot.layer==layer) slot.copy(program=preset.program,bank=preset.bank,name=preset.name,
                                soundFontId=preset.soundFontId,soundFontName=preset.soundFontName,available=true) else slot
                        } }
                        if(!isCurrent()) superseded else if(ok) com.yourapp.yamahaarranger.registration.VoiceResult.APPLIED else failed
                    } catch(_: Exception) { failed }
                }
            }
        }
        registrationPending.add(operation)
        return withContext(NonCancellable) {
            try { kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { operation.await() }
                ?: com.yourapp.yamahaarranger.registration.VoiceResult.TIMEOUT }
            catch(_: CancellationException) { superseded }
            catch(_: Exception) { failed }
        }
    }

    suspend fun awaitRegistrationVoicesIdle() {
        withContext(NonCancellable) {
            while(true) {
                val op=registrationPending.peek() ?: break
                try { op.await() } catch(_: Exception) { /* Result already reported; always release the queue. */ }
                registrationPending.remove(op)
            }
        }
    }

    /**
     * The normal voice selector's external side: remember the identity the external keyboard is told about (so SAVE stores
     * what is on screen, never a stale EXT-list entry) and send exactly Bank MSB/LSB + Program, then the release time.
     * Registration recall reuses [selectExternalVoice] with the same values; there is no second mapping.
     */
    fun mirrorKeyboardVoiceToExternal(layer: Int, bank: Int, program: Int, name: String) {
        val voice=com.yourapp.yamahaarranger.registration.Sf2ExternalMapping.voiceFor(bank,program,name)
        registrationExternal.update { list -> list.mapIndexed { i, old -> if(i==layer) voice else old } }
        if(voice!=null && midiInputManager.midiOutEnabled) {
            midiInputManager.sendProgramChange(layer,voice.program,voice.msb,voice.lsb)
            // A voice change may restore the external voice's own release.
            if(layer in 0..2) midiInputManager.sendReleaseTime(layer,_releaseControl.value.effectiveValue)
        }
    }

    /** Explicit external identity only. OFF stores the selection, never queues MIDI for reconnect. */
    fun selectExternalVoice(part: KeyboardPart, voice: com.yourapp.yamahaarranger.registration.ExternalVoice) {
        require(voice.msb in 0..127 && voice.lsb in 0..127 && voice.program in 0..127)
        registrationExternal.update { list -> list.mapIndexed { i, old -> if(i==part.channel) voice else old } }
        if(midiInputManager.midiOutEnabled) midiInputManager.sendProgramChange(part.channel,voice.program,voice.msb,voice.lsb)
    }
    fun nudgeTempo(delta: Int) { arrangerBrain.setTempo((arrangerBrain.state.value.tempoBpm.toLong()+delta).coerceIn(20,280).toInt()) }

    val registrationCoordinator by lazy { com.yourapp.yamahaarranger.registration.RegistrationCoordinator(viewModelScope, registrationPort) }
    private val registrationPort = object : com.yourapp.yamahaarranger.registration.RegistrationPort {
        override suspend fun snapshot(): com.yourapp.yamahaarranger.registration.RecallSnapshot {
            val brain=arrangerBrain.state.value
            return com.yourapp.yamahaarranger.registration.RecallSnapshot(_rightVoices.value.map { slot ->
                com.yourapp.yamahaarranger.registration.PartSetup(
                    if(slot.soundFontId>0 && slot.available) com.yourapp.yamahaarranger.registration.Sf2VoiceRef(slot.soundFontName,slot.bank,slot.program,slot.name.orEmpty()) else null,
                    registrationExternal.value[slot.layer],if(slot.layer==3) brain.leftVoiceEnabled else slot.enabled,keyboardVolume(slot.layer).value)
            },com.yourapp.yamahaarranger.registration.ReleaseSetup(_releaseControl.value.knobValue,_releaseControl.value.enabled),
                brain.tempoBpm,_transpose.value,List(4) { voiceRequestIds.get(it) })
        }
        override suspend fun catalog() = audioEngine.loadedSoundFontPresets().map {
            com.yourapp.yamahaarranger.registration.Sf2VoiceRef(it.soundFontName,it.bank,it.program,it.name)
        }
        override fun midiOutActive() = midiInputManager.midiOutEnabled
        override suspend fun applySf2(part: com.yourapp.yamahaarranger.registration.Part, voice: com.yourapp.yamahaarranger.registration.Sf2VoiceRef, isCurrent: () -> Boolean): com.yourapp.yamahaarranger.registration.VoiceResult {
            if(!isCurrent()) return com.yourapp.yamahaarranger.registration.VoiceResult.SUPERSEDED
            val matches=audioEngine.loadedSoundFontPresets().filter { it.soundFontName==voice.sf2FileName && it.bank==voice.bank && it.program==voice.program && it.name==voice.presetName }
            val preset=matches.singleOrNull() ?: return com.yourapp.yamahaarranger.registration.VoiceResult.FAILED
            val result=applyKeyboardVoice(KeyboardPart.fromChannel(part.channel),preset,mirrorToMidiOut=false,isCurrent=isCurrent)
            // Keep the saved-external identity in step with the SF2 voice now on screen (no MIDI here: INTERNAL stays silent).
            if(result==com.yourapp.yamahaarranger.registration.VoiceResult.APPLIED) {
                val derived=com.yourapp.yamahaarranger.registration.Sf2ExternalMapping.voiceFor(preset.bank,preset.program,preset.name)
                registrationExternal.update { list -> list.mapIndexed { i, old -> if(i==part.channel) derived else old } }
            }
            return result
        }
        override suspend fun awaitSf2Idle() = awaitRegistrationVoicesIdle()
        override suspend fun apply(step: com.yourapp.yamahaarranger.registration.RecallStep, isCurrent: () -> Boolean): com.yourapp.yamahaarranger.registration.VoiceResult {
            if(!isCurrent()) return com.yourapp.yamahaarranger.registration.VoiceResult.SUPERSEDED
            when(step) {
                is com.yourapp.yamahaarranger.registration.RecallStep.External -> {
                    if(!midiInputManager.midiOutEnabled) return com.yourapp.yamahaarranger.registration.VoiceResult.FAILED
                    selectExternalVoice(KeyboardPart.fromChannel(step.part.channel),step.voice)
                    // Same follow-up as the normal selector (a voice change may restore the external voice's own release).
                    if(step.part.channel in 0..2) midiInputManager.sendReleaseTime(step.part.channel,_releaseControl.value.effectiveValue)
                }
                is com.yourapp.yamahaarranger.registration.RecallStep.Enabled -> {
                    if(step.part.channel==3) setLeftVoiceEnabled(step.value)
                    else if(_rightVoices.value[step.part.channel].enabled!=step.value) toggleRightVoice(step.part.channel)
                }
                is com.yourapp.yamahaarranger.registration.RecallStep.Volume -> when(step.part.channel) {
                    0 -> onRight1VolumeChange(step.value); 1 -> onRight2VolumeChange(step.value)
                    2 -> onRight3VolumeChange(step.value); 3 -> onLeftVolumeChange(step.value)
                }
                is com.yourapp.yamahaarranger.registration.RecallStep.Release -> _releaseControl.value=ReleaseControlState(step.value.knobValue.coerceIn(0,127),step.value.enabled)
                is com.yourapp.yamahaarranger.registration.RecallStep.Transpose -> {
                    _transpose.value=step.value.coerceIn(-12,12); arrangerBrain.setKeyboardTranspose(_transpose.value)
                }
                is com.yourapp.yamahaarranger.registration.RecallStep.Tempo -> arrangerBrain.setTempo(step.value.coerceIn(20,280))
                is com.yourapp.yamahaarranger.registration.RecallStep.Sf2 -> return com.yourapp.yamahaarranger.registration.VoiceResult.FAILED
            }
            return com.yourapp.yamahaarranger.registration.VoiceResult.APPLIED
        }
    }
    // END REG PHASE2 ADDITIONS

    private val _styleName = MutableStateFlow("No Style Loaded")
    private val _midiStatus = MutableStateFlow("No MIDI device")
    private val _midiOutEnabled = MutableStateFlow(false)
    private val _transpose = MutableStateFlow(0)
    private val _sustainEnabled = MutableStateFlow(false)
    private val _releaseControl = MutableStateFlow(ReleaseControlState())
    private val _soundFontName = MutableStateFlow("None")
    private val _availableSoundFonts = MutableStateFlow<List<Pair<Uri, String>>>(emptyList())
    private val _styleFolders = MutableStateFlow<List<StyleFolder>>(emptyList())
    private val _styleFiles = MutableStateFlow<List<Pair<Uri, String>>>(emptyList())
    private val _sf2Presets = MutableStateFlow<List<AudioEngineManager.SfPreset>>(emptyList())
    private val _sf2Reports = MutableStateFlow<List<SoundFontInspector.Report>>(emptyList())
    private val _sf2ScanInProgress = MutableStateFlow(false)
    private val _styleVolume = MutableStateFlow(127)
    private val _leftVolume = MutableStateFlow(100)
    private val _right1Volume = MutableStateFlow(100)
    private val _right2Volume = MutableStateFlow(100)
    private val _right3Volume = MutableStateFlow(100)
    private val _masterVolume = MutableStateFlow(100)
    private val _mixerConsole = MutableStateFlow(MixerConsoleState())
    val mixerConsole: StateFlow<MixerConsoleState> = _mixerConsole
    private val _keyboardMixerDetails = MutableStateFlow(List(4) { KeyboardMixerDetail() })
    val keyboardMixerDetails: StateFlow<List<KeyboardMixerDetail>> = _keyboardMixerDetails
    private fun keyboardVolume(channel: Int) = listOf(_right1Volume, _right2Volume, _right3Volume, _leftVolume)[channel]
    private fun sendKeyboardGain(channel: Int) {
        com.yourapp.yamahaarranger.audio.PcmSessionControls.observeMute(channel,_mixerConsole.value.muted(channel))
        audioEngine.setChannelExpression(channel,_mixerConsole.value.keyboardGain(channel, keyboardVolume(channel).value))
    }
    private fun syncMixerMidiMute() {
        val focus = _mixerConsole.value
        midiInputManager.setMixerMutedChannels((0..3).filter { focus.muted(it) }.toSet() +
            _voiceAssignments.value.filter { focus.muted(it.channel, it.styleMuted) }.map { it.channel })
    }
    private fun applyMixerFocus() {
        syncMixerMidiMute()
        (0..3).forEach(::sendKeyboardGain)
        _voiceAssignments.value.forEach { slot ->
            arrangerBrain.setStyleChannelMute(slot.channel, _mixerConsole.value.muted(slot.channel, slot.styleMuted))
        }
    }
    fun toggleMixerKeyboardMute(channel: Int) {
        _mixerConsole.update { it.toggleMute(channel) }; syncMixerMidiMute(); sendKeyboardGain(channel)
    }
    fun toggleMixerSolo(channel: Int) {
        if (channel !in 0..3 && _voiceAssignments.value.none { it.channel == channel }) return
        _mixerConsole.update { it.toggleSolo(channel) }; applyMixerFocus()
    }
    fun clearMixerSolo() {
        if (_mixerConsole.value.solo.isEmpty()) return
        _mixerConsole.update { it.copy(solo = emptySet()) }; applyMixerFocus()
    }
    fun reconcileMixerChannels() {
        val channels = (0..3).toSet() + _voiceAssignments.value.map { it.channel }
        val previous = _mixerConsole.value
        _mixerConsole.update { it.retainChannels(channels) }
        syncMixerMidiMute()
        if (previous != _mixerConsole.value || _mixerConsole.value.solo.isNotEmpty() || _mixerConsole.value.keyboardMuted.isNotEmpty()) applyMixerFocus()
    }
    fun setKeyboardMixerDetail(channel: Int, detail: KeyboardMixerDetail) {
        require(channel in 0..3)
        val clamped = detail.copy(pan = detail.pan.coerceIn(0, 127), reverb = detail.reverb.coerceIn(0, 127), chorus = detail.chorus.coerceIn(0, 127))
        _keyboardMixerDetails.update { list -> list.mapIndexed { index, old -> if (index == channel) clamped else old } }
        audioEngine.setChannelMixer(channel, 127, clamped.pan,
            _mixerConsole.value.keyboardGain(channel, keyboardVolume(channel).value), clamped.reverb, clamped.chorus)
    }

    private val _activeBank = MutableStateFlow(1)
    private val _activeRegSlot = MutableStateFlow(0)
    private val _voiceAssignments = MutableStateFlow<List<VoiceSlot>>(emptyList())
    private val _rightVoices = MutableStateFlow(defaultKeyboardVoices())
    private val voiceRequestIds = java.util.concurrent.atomic.AtomicLongArray(4)
    private var activeChordNotes: List<Int> = emptyList()

    private val volumeState = combine(
        combine(_styleVolume, _leftVolume, _right1Volume, _right2Volume, _right3Volume) { s, l, r1, r2, r3 ->
            listOf(s, l, r1, r2, r3)
        },
        _masterVolume
    ) { volumes, master -> volumes to master }

    private val sf2InspectorState = combine(_sf2Reports, _sf2ScanInProgress) { reports, scanning ->
        reports to scanning
    }

    private val voiceAndSoundFontState = combine(
        combine(_activeBank, _activeRegSlot, _voiceAssignments) { b, r, v -> Triple(b, r, v) },
        combine(
            _rightVoices,
            combine(_availableSoundFonts, combine(_styleFolders, _styleFiles) { folders, styles -> folders to styles }) { sf, folderAndStyles -> sf to folderAndStyles },
            _sf2Presets,
            sf2InspectorState
        ) { rightVoices, filesAndStyles, presets, inspector ->
            Triple(rightVoices, filesAndStyles, presets) to inspector
        }
    ) { voiceData, sfData -> voiceData to sfData }

    private val baseUiState: StateFlow<MainUiState> = combine(
        arrangerBrain.state,
        combine(_styleName, _midiStatus) { s, m -> s to m },
        combine(_transpose, combine(_sustainEnabled, _releaseControl) { sustain, release -> sustain to release }, _soundFontName) { t, sustainRelease, sf -> Triple(t, sustainRelease, sf) },
        volumeState,
        voiceAndSoundFontState
    ) { arranger, styleMidi, transposeSf, volumesMaster, voiceDataSf ->
        val (styleName, midi) = styleMidi
        val (transpose, sustainRelease, sfName) = transposeSf
        val (sustain, releaseControl) = sustainRelease
        val (voiceVolumes, masterVol) = volumesMaster
        val (voiceData, sfData) = voiceDataSf
        val (bank, regSlot, voices) = voiceData
        val sfDataCore = sfData.first
        val inspectorData = sfData.second
        val rightVoices = sfDataCore.first
        val sfFiles = sfDataCore.second.first
        val styleFolders = sfDataCore.second.second.first
        val styleFiles = sfDataCore.second.second.second
        val sfPresets = sfDataCore.third
        val sf2Reports = inspectorData.first
        val sf2ScanInProgress = inspectorData.second
        MainUiState(
            styleName = styleName,
            styleFolders = styleFolders,
            styleFiles = styleFiles,
            tempoBpm = arranger.tempoBpm,
            transpose = transpose,
            isPlaying = arranger.isPlaying,
            activeSection = displayLabelFor(arranger.currentSection),
            detectedChordLabel = arranger.currentChordLabel,
            autoFill = arranger.autoFill,
            acmpEnabled = arranger.acmpEnabled,
            leftVoiceEnabled = arranger.leftVoiceEnabled,
            sustainEnabled = sustain,
            releaseTime = releaseControl.knobValue,
            releaseEnabled = releaseControl.enabled,
            effectiveReleaseTime = releaseControl.effectiveValue,
            midiStatus = midi,
            midiOutEnabled = _midiOutEnabled.value,
            soundFontName = sfName,
            styleVolume = voiceVolumes[0],
            leftVolume = voiceVolumes[1],
            right1Volume = voiceVolumes[2],
            right2Volume = voiceVolumes[3],
            right3Volume = voiceVolumes[4],
            masterVolume = masterVol,
            sf2Presets = sfPresets,
            sf2Reports = sf2Reports,
            sf2ScanInProgress = sf2ScanInProgress,
            activeBank = bank,
            activeRegSlot = regSlot,
            voiceName = rightVoices.getOrNull(0)?.displayName() ?: "OFF",
            right2Name = if (rightVoices.getOrNull(1)?.enabled == true) rightVoices.getOrNull(1)?.displayName() ?: "OFF" else "OFF",
            rightVoices = rightVoices.map { slot ->
                slot.copy(enabled = if (slot.layer == 3) arranger.leftVoiceEnabled else slot.enabled,
                    available = slot.soundFontId == 0L || sfPresets.any {
                        it.soundFontId == slot.soundFontId && it.bank == slot.bank && it.program == slot.program
                    })
            },
            voiceAssignments = voices,
            availableSoundFonts = sfFiles
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    val uiState = combine(baseUiState, audioEngine.readiness.state) { ui, ready ->
        val section = SECTION_BUTTON_MAP[ui.activeSection]?.styleName.orEmpty()
        ui.copy(startReady = ready.permits(section), readinessReason =
            if (ready.phase == ReadyToPlayGate.Phase.READY && !ready.permits(section))
                "Selected section unavailable" else ready.reason)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

    private fun launchFontRequest(block: suspend (ReadyToPlayGate.Request) -> Unit) {
        _sf2Presets.value = emptyList()
        val request = audioEngine.readiness.beginFonts()
        viewModelScope.launch {
            var ok = false
            try {
                audioEngine.readiness.fontRequests.withLock {
                    if (!audioEngine.readiness.current(request)) return@withLock
                    fontRequestSucceeded = false
                    block(request)
                    ok = audioEngine.readiness.current(request) && fontRequestSucceeded
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DebugLog.add("❌ SF2 load failed: ${error.javaClass.simpleName}")
            } finally {
                try {
                    if (audioEngine.readiness.current(request)) {
                        val presets = withContext(NonCancellable + Dispatchers.Default) { audioEngine.loadedSoundFontPresets() }
                        if (audioEngine.readiness.current(request)) _sf2Presets.value = presets
                    }
                } finally { audioEngine.readiness.finishFonts(request, ok) }
            }
        }
    }
    // Serialized by the shared fontRequests mutex, including cache writes.
    private var fontRequestSucceeded = false

    init {
        audioEngine.onMixerControllers = midiInputManager::setMixerControllers
        audioEngine.onMixerMasterVolume = midiInputManager::setMixerMasterVolume
        midiInputManager.onOutputReady = {
            for (channel in 0..2) midiInputManager.sendReleaseTime(channel, _releaseControl.value.effectiveValue)
        }
        arrangerBrain.attachScope(viewModelScope)
        // Serial/conflated delivery keeps the last knob value authoritative,
        // without blocking the UI on a concurrent large SF2 load.
        viewModelScope.launch(Dispatchers.Default) {
            _releaseControl.map { it.effectiveValue }.distinctUntilChanged().collect { value ->
                arrangerBrain.setKeyboardReleaseTime(value)
                // Mirror RELEASE separately as CC72; mixer controllers use the effective-value observer.
                for (channel in 0..2) midiInputManager.sendReleaseTime(channel, value)
            }
        }
        DebugLog.add("🎵 ViewModel init")
        DebugLog.add("📂 SF2 folder: /storage/emulated/0/YamahaArranger/SF2")

        // IMPORTANT: initialize/load SF2 before opening the Oboe stream.
        // Loading a second SoundFont while the render stream is already
        // running has been the crash path on the device. FluidSynth can load
        // multiple SoundFonts, but SF2 loading is a non-realtime operation.
        // We therefore discover/copy/load first, enumerate presets, then start
        // the audio callback exactly once.
        launchFontRequest { request ->
            fontRequestSucceeded = false
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.ensureSoundFontFolder()
                    _availableSoundFonts.value = contentResolver.listSoundFonts()
                }
                autoLoadSoundFont(request)
                DebugLog.add("📋 Cached SF2 inventory will be published after loading")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                DebugLog.add("❌ Startup SF2 init failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                withContext(Dispatchers.Default) { audioEngine.start() }
            }
        }

        midiInputManager.onNoteOn = { note, velocity -> arrangerBrain.onKeyboardNoteOn(note, velocity / 127f) }
        midiInputManager.onNoteOff = { note -> arrangerBrain.onKeyboardNoteOff(note) }
        midiInputManager.onSustainChange = { enabled ->
            _sustainEnabled.value = enabled
            arrangerBrain.setKeyboardSustain(enabled)
        }
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
    fun toggleMidiOut() {
        val newVal = !_midiOutEnabled.value
        if (newVal) {
            midiInputManager.setMixerStyleChannels(_voiceAssignments.value.map { it.channel }.toSet())
            midiInputManager.setMixerMasterVolume(_masterVolume.value)
            syncMixerMidiMute()
        }
        _midiOutEnabled.value = newVal; midiInputManager.midiOutEnabled = newVal
        if (newVal) for (channel in 0..2) midiInputManager.sendReleaseTime(channel, _releaseControl.value.effectiveValue)
        DebugLog.add(if (newVal) "📤 MIDI OUT: ON" else "📤 MIDI OUT: OFF")
    }
    override fun onCleared() {
        audioEngine.onMixerControllers = null; audioEngine.onMixerMasterVolume = null
        midiInputManager.onOutputReady = null
        midiInputManager.close(); audioEngine.stop(); super.onCleared()
    }

    fun onKeyboardNoteOn(midiNote: Int, velocity: Float) = arrangerBrain.onKeyboardNoteOn(midiNote, velocity)
    fun onKeyboardNoteOff(midiNote: Int) = arrangerBrain.onKeyboardNoteOff(midiNote)
    fun toggleAutoFill() = arrangerBrain.setAutoFill(!arrangerBrain.state.value.autoFill)
    fun toggleAcmp() = arrangerBrain.toggleAcmp()
    fun setAcmpEnabled(enabled: Boolean) = arrangerBrain.setAcmpEnabled(enabled)
    fun toggleLeftVoice() = arrangerBrain.toggleLeftVoice()
    fun setLeftVoiceEnabled(enabled: Boolean) = arrangerBrain.setLeftVoiceEnabled(enabled)

    fun onSectionSelected(sectionLabel: String) {
        val section = SECTION_BUTTON_MAP[sectionLabel] ?: return
        if (section in MAIN_VARIATIONS) arrangerBrain.selectMainVariation(section) else arrangerBrain.selectSection(section)
    }
    fun onSyncStart() { }
    fun onStartStop() = arrangerBrain.startStop()
    fun onTapTempo() { }
    fun onTempoDown() { arrangerBrain.setTempo((arrangerBrain.state.value.tempoBpm - 5).coerceIn(20, 280)) }
    fun onTempoUp() { arrangerBrain.setTempo((arrangerBrain.state.value.tempoBpm + 5).coerceIn(20, 280)) }
    fun onTransposeDown() {
        val value = (_transpose.value - 1).coerceIn(-12, 12)
        _transpose.value = value
        arrangerBrain.setKeyboardTranspose(value)
    }
    fun onTransposeUp() {
        val value = (_transpose.value + 1).coerceIn(-12, 12)
        _transpose.value = value
        arrangerBrain.setKeyboardTranspose(value)
    }
    fun onReleaseDown() { _releaseControl.update { it.step(-1) } }
    fun onReleaseUp() { _releaseControl.update { it.step(1) } }
    fun toggleRelease() { _releaseControl.update { it.toggle() } }
    fun toggleSustain() {
        val enabled = !_sustainEnabled.value
        _sustainEnabled.value = enabled
        arrangerBrain.setKeyboardSustain(enabled)
    }
    fun onStyleVolumeChange(value: Int) {
        val v = value.coerceIn(0, 127); _styleVolume.value = v
        audioEngine.setAuxiliaryStyleBusTrim(v)
        arrangerBrain.setStyleBusTrim(v)
    }
    fun onLeftVolumeChange(value: Int) { keyboardVolume(3).value = value.coerceIn(0, 127); sendKeyboardGain(3) }
    fun onRight1VolumeChange(value: Int) { keyboardVolume(0).value = value.coerceIn(0, 127); sendKeyboardGain(0) }
    fun onRight2VolumeChange(value: Int) { keyboardVolume(1).value = value.coerceIn(0, 127); sendKeyboardGain(1) }
    fun onRight3VolumeChange(value: Int) { keyboardVolume(2).value = value.coerceIn(0, 127); sendKeyboardGain(2) }

    /** Assign RIGHT1/2/3 or LEFT by an exact loaded SF2 identity. */
    fun setKeyboardVoice(part: KeyboardPart, preset: AudioEngineManager.SfPreset, onResult: (Boolean) -> Unit = {}) {
        val layer = part.channel
        val requestId = voiceRequestIds.incrementAndGet(layer)
        DebugLog.add("VOICE UI part=${part.label} channel=$layer request=$requestId fontId=${preset.soundFontId} bank=${preset.bank} pc=${preset.program}")
        viewModelScope.launch(Dispatchers.Default) {
            val ok = audioEngine.selectKeyboardPreset(layer, preset) { voiceRequestIds.get(layer) == requestId }
            if (voiceRequestIds.get(layer) != requestId) return@launch
            if (ok) {
                _rightVoices.update { slots -> slots.map { slot ->
                    if (slot.layer == layer) slot.copy(program = preset.program, bank = preset.bank,
                        name = preset.name, soundFontId = preset.soundFontId,
                        soundFontName = preset.soundFontName, available = true) else slot
                } }
                // An external MIDI device cannot receive an SF2 identity. Only
                // mirror standard MIDI-sized values; never clamp a native preset.
                mirrorKeyboardVoiceToExternal(layer, preset.bank, preset.program, preset.name)
            } else {
                DebugLog.add("❌ VOICE selection unavailable/failed: ${preset.soundFontName} ${preset.bank}:${preset.program}")
            }
            withContext(Dispatchers.Main) { if (voiceRequestIds.get(layer) == requestId) onResult(ok) }
        }
    }


    fun toggleRightVoice(layer: Int) {
        if (layer == 3) { arrangerBrain.toggleLeftVoice(); return }
        if (layer !in 0..2) return
        _rightVoices.update { slots -> slots.map { slot ->
            if (slot.layer == layer) slot.copy(enabled = !slot.enabled) else slot
        } }
        val slot = _rightVoices.value.first { it.layer == layer }
        arrangerBrain.setRightVoiceEnabled(layer, slot.enabled)
        DebugLog.add("🎹 " + slot.label + ": " + if (slot.enabled) "ON" else "OFF")
    }

    fun onMasterVolumeChange(value: Int) { _masterVolume.value = value.coerceIn(0, 127); audioEngine.setMasterVolume(_masterVolume.value) }

    // Temporary registration-as-chord pads for style development/testing.
    fun onBankChange(bank: Int) { _activeBank.value = bank.coerceIn(1, 8) }
    fun onRegSlotTap(slot: Int) {
        // Chord-test pads must stay inside the ACMP range (<= split note 54).
        // Use one octave lower than the original pads so every test note is
        // interpreted as a chord note instead of leaking its top note to RIGHT 1.
        val chords = listOf(
            "C" to intArrayOf(36, 40, 43), "Dm" to intArrayOf(38, 41, 45),
            "Em" to intArrayOf(40, 43, 47), "F" to intArrayOf(41, 45, 48),
            "G" to intArrayOf(43, 47, 50), "Am" to intArrayOf(45, 48, 52),
            "Bdim" to intArrayOf(47, 50, 53), "C7" to intArrayOf(36, 40, 43, 46)
        )
        val index = slot.coerceIn(0, 7)
        activeChordNotes.forEach(arrangerBrain::onKeyboardNoteOff)
        activeChordNotes = chords[index].second.toList()
        activeChordNotes.forEach { arrangerBrain.onKeyboardNoteOn(it, 0.82f) }
        _activeRegSlot.value = index
        DebugLog.add("🎹 CHORD PAD ${index + 1}: ${chords[index].first} (${activeChordNotes.joinToString()})")
    }
    fun onRegSlotSave(slot: Int) { Timber.i("Save reg bank=${_activeBank.value} slot=$slot") }

    /** Build the mixer from channels/instruments actually present in the loaded style. */
    private fun voiceSlotsFromStyle(style: com.yourapp.yamahaarranger.style.ParsedStyle): List<VoiceSlot> {
        data class Candidate(val name: String, val program: Int, val bank: Int, val pan: Int, val reverb: Int, val chorus: Int)
        val candidates = linkedMapOf<Int, Candidate>()

        style.sections.values.forEach { section ->
            section.parts.forEach { part ->
                StyleMixerChannels.destinations(part).forEach { channel ->
                    val policy = part.casmPolicies.firstOrNull { it.destinationChannel == channel } ?: part.casm
                    val rawName = policy?.voiceName?.trim().orEmpty()
                    val styleName = rawName.ifBlank {
                        style.voiceMap[channel]?.trim().orEmpty()
                    }.ifBlank {
                        GM_VOICES.firstOrNull { it.second == part.program }?.first ?: "Ch$channel"
                    }
                    val drum = channel == 8 || channel == 9 || rawName.lowercase().let {
                        it.contains("drum") || it.contains("kit") || it.contains("perc") || it.startsWith("dr")
                    }
                    val program = if (part.program in 0..127) part.program else
                        GM_VOICES.firstOrNull { it.first.equals(styleName, ignoreCase = true) }?.second ?: 0
                    val bank = if (drum) 128 else (part.bankMsb.coerceIn(0, 127) * 128 + part.bankLsb.coerceIn(0, 127))

                    // Keep the first explicit instrument for a destination. CASM can
                    // legitimately have multiple source channels feeding one destination.
                    if (channel !in candidates) candidates[channel] = Candidate(styleName, program, bank, part.pan.takeIf { it in 0..127 } ?: 64,
                        part.reverbSend.takeIf { it in 0..127 } ?: 40, part.chorusSend.takeIf { it in 0..127 } ?: 0)
                }
            }
        }

        return candidates.toSortedMap().map { (channel, candidate) ->
            val role = when (channel) {
                8 -> "Rhythm1"
                9 -> "Rhythm2"
                10 -> "Bass"
                11 -> "Chord1"
                12 -> "Chord2"
                13 -> "Pad"
                14 -> "Phrase1"
                15 -> "Phrase2"
                else -> "Style"
            }
            VoiceSlot(
                channel = channel,
                label = "Ch$channel ($role)",
                program = candidate.program,
                bank = candidate.bank,
                sf2Name = candidate.name, stylePan = candidate.pan,
                styleReverb = candidate.reverb, styleChorus = candidate.chorus
            )
        }
    }

    fun setChannelVoice(channel: Int, program: Int, bank: Int) {
        val updated = _voiceAssignments.value.map { slot ->
            if (slot.channel == channel) {
                audioEngine.setChannelProgram(channel, program, bank,
                    _sf2Presets.value.firstOrNull { it.bank == bank && it.program == program }?.name)
                midiInputManager.sendProgramChange(channel, program, bank)
                DebugLog.add("🎼 Ch$channel → prog$program (bank$bank)")
                slot.copy(program = program, bank = bank, sf2Name = _sf2Presets.value.firstOrNull { it.bank == bank && it.program == program }?.name)
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

    fun setStyleChannelMixer(channel: Int, volume: Int, pan: Int, expression: Int, reverb: Int, chorus: Int) {
        val previous = _voiceAssignments.value.firstOrNull { it.channel == channel } ?: return
        val v = volume.coerceIn(0, 127)
        val p = pan.coerceIn(0, 127)
        val e = expression.coerceIn(0, 127)
        val r = reverb.coerceIn(0, 127)
        val c = chorus.coerceIn(0, 127)
        _voiceAssignments.value = _voiceAssignments.value.map { slot ->
            if (slot.channel == channel) slot.copy(styleVolume = v, stylePan = p, styleExpression = e, styleReverb = r, styleChorus = c) else slot
        }
        arrangerBrain.setStyleChannelMixer(channel, v, p.takeIf { it != previous.stylePan }, e,
            r.takeIf { it != previous.styleReverb }, c.takeIf { it != previous.styleChorus })
    }

    fun toggleStyleChannelMute(channel: Int) {
        val slot = _voiceAssignments.value.firstOrNull { it.channel == channel } ?: return
        val muted = !slot.styleMuted
        _voiceAssignments.value = _voiceAssignments.value.map { if (it.channel == channel) it.copy(styleMuted = muted) else it }
        syncMixerMidiMute()
        arrangerBrain.setStyleChannelMute(channel, _mixerConsole.value.muted(channel, muted))
        DebugLog.add(if (muted) "🔇 STYLE CH$channel muted" else "🔊 STYLE CH$channel unmuted")
    }

    // ---- Style / Voice browser memory (last folder + item per context; survives restart) ----
    private val browserStore by lazy { com.yourapp.yamahaarranger.browser.BrowserMemoryStore(contentResolver.browserMemoryPath()) }
    private val _browserMemory = MutableStateFlow<com.yourapp.yamahaarranger.browser.BrowserMemory?>(null)
    /** Null until the file has been read, so a restore never races the load. */
    val browserMemory: StateFlow<com.yourapp.yamahaarranger.browser.BrowserMemory?> = _browserMemory
    private val browserWriteLock = kotlinx.coroutines.sync.Mutex()
    init { viewModelScope.launch(Dispatchers.IO) { _browserMemory.value = browserStore.read() } }
    private fun updateBrowserMemory(change: (com.yourapp.yamahaarranger.browser.BrowserMemory) -> com.yourapp.yamahaarranger.browser.BrowserMemory) {
        val next = change(_browserMemory.value ?: com.yourapp.yamahaarranger.browser.BrowserMemory())
        _browserMemory.value = next
        viewModelScope.launch(Dispatchers.IO) { browserWriteLock.withLock { try { browserStore.write(next) } catch (e: Exception) { DebugLog.add("Browser memory not saved: ${e.javaClass.simpleName}") } } }
    }
    fun rememberStylePosition(p: com.yourapp.yamahaarranger.browser.SavedPosition?) = updateBrowserMemory { it.withStyle(p) }
    /** [context] is RIGHT1, RIGHT2, RIGHT3, LEFT or STYLE_EDITOR; contexts never share a position. */
    fun rememberVoicePosition(context: String, p: com.yourapp.yamahaarranger.browser.SavedPosition?) = updateBrowserMemory { it.withVoice(context, p) }

    // ---- CPV per-style manual voice overrides ----
    private val overrideStore by lazy { com.yourapp.yamahaarranger.style.StyleOverrideStore(contentResolver.styleOverrideDirectory()) }
    private var currentStyleSha: String? = null
    private var currentStyleFileName: String = ""
    private var currentOverrides: com.yourapp.yamahaarranger.style.StyleOverrideRecord? = null
    private var overridesWritable = true

    // Style Voice Editor state: working profile (currentOverrides) vs what is on disk (savedOverrides).
    private var savedOverrides: com.yourapp.yamahaarranger.style.StyleOverrideRecord? = null
    private val overrideStates = HashMap<String, String>()
    private fun stateKey(external: Boolean, scope: com.yourapp.yamahaarranger.style.OverrideScope, section: String?, channel: Int) =
        "${if (external) "X" else "S"}|$scope|${section ?: "*"}|$channel"
    data class StyleEditorUiState(val dirty: Boolean = false, val writable: Boolean = true, val savedAt: Long = 0L,
        val revision: Long = 0L, val message: String = "", val auditioning: Boolean = false)
    private val _styleEditorUi = MutableStateFlow(StyleEditorUiState())
    val styleEditorUi: StateFlow<StyleEditorUiState> = _styleEditorUi
    private fun editorSnapshot(message: String = _styleEditorUi.value.message): StyleEditorUiState {
        val empty = com.yourapp.yamahaarranger.style.StyleOverrideRecord(currentStyleSha ?: "0".repeat(64), currentStyleFileName, emptyList())
        val w = currentOverrides ?: empty; val sv = savedOverrides ?: empty
        val dirty = w.overrides != sv.overrides || w.externalOverrides != sv.externalOverrides
        return StyleEditorUiState(dirty, overridesWritable, sv.savedAt, w.revision, message, _styleEditorUi.value.auditioning)
    }
    private fun loadedPdtaByFile(): Map<String, String> =
        audioEngine.loadedSoundFontPresets().distinctBy { it.soundFontName }.associate { it.soundFontName to audioEngine.soundFontPdta(it.soundFontId) }

    private fun refreshAssignmentsFromRecord() {
        val record = currentOverrides
        _voiceAssignments.value = _voiceAssignments.value.map { slot ->
            val entry = record?.overrides?.firstOrNull { it.channel == slot.channel && it.scope == com.yourapp.yamahaarranger.style.OverrideScope.ALL }
            slot.copy(overridePin = entry?.pin,
                overrideStatus = entry?.let { overrideStates[stateKey(false, it.scope, it.section, it.channel)] ?: "UNVERIFIED" })
        }
        _styleEditorUi.value = editorSnapshot()
    }

    /** Apply the saved overrides that match the loaded presets; others stay visible as STALE with their reason. */
    private fun restoreStyleOverrides() {
        val record = currentOverrides
        overrideStates.clear()
        if (record != null) {
            val pdta = loadedPdtaByFile()
            val checks = com.yourapp.yamahaarranger.style.StyleOverridePins.check(record, { pdta[it] }) { sf2, bank, pc ->
                audioEngine.findLoadedPreset(com.yourapp.yamahaarranger.style.StyleVoicePin(sf2, bank, pc, "", false))?.name
            }
            checks.forEach { c ->
                val all = c.scope == com.yourapp.yamahaarranger.style.OverrideScope.ALL
                val state = if (!c.valid) {
                    if (all) arrangerBrain.setStyleVoicePin(c.channel, null) else arrangerBrain.setStyleSectionVoicePin(c.section!!, c.channel, null)
                    c.reason
                } else {
                    val applied = if (all) arrangerBrain.setStyleVoicePin(c.channel, c.pin) else arrangerBrain.setStyleSectionVoicePin(c.section!!, c.channel, c.pin)
                    if (applied) "ACTIVE" else "UNVERIFIED"
                }
                overrideStates[stateKey(false, c.scope, c.section, c.channel)] = state
                DebugLog.add("CPV restore ch=${c.channel} ${c.scope} ${c.section ?: "*"} ${c.pin.soundFontName} ${c.pin.bank}:${c.pin.program} '${c.pin.presetName}' ${c.reason}")
            }
            record.externalOverrides.forEach { e ->
                val ok = arrangerBrain.setStyleExternalVoice(e.section, e.channel, e.voice)
                overrideStates[stateKey(true, e.scope, e.section, e.channel)] = if (ok) "ACTIVE" else "UNVERIFIED"
                DebugLog.add("VOICE OVERRIDE restore ch=${e.channel} ${e.scope} ${e.section ?: "*"} ${e.voice.msb}:${e.voice.lsb}:${e.voice.program} ${if (ok) "ACTIVE" else "UNVERIFIED"}")
            }
        }
        refreshAssignmentsFromRecord()
    }

    /** After an SF2 (re)load the native font ids change: re-check and re-install pins. */
    private fun reapplyStyleVoicePins() {
        arrangerBrain.invalidateAppliedVoices()
        if (currentOverrides != null) restoreStyleOverrides()
    }

    /** Pick a Colombo/Tyros preset for a style channel; saved for this style immediately. */
    fun pickStyleChannelVoice(channel: Int, preset: AudioEngineManager.SfPreset, onResult: (Boolean) -> Unit = {}) {
        val sha = currentStyleSha ?: return onResult(false)
        val pin = com.yourapp.yamahaarranger.style.StyleVoicePin(preset.soundFontName, preset.bank, preset.program, preset.name, preset.isDrum)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { arrangerBrain.setStyleVoicePin(channel, pin) }
            overrideStates[stateKey(false, com.yourapp.yamahaarranger.style.OverrideScope.ALL, null, channel)] = if (ok) "ACTIVE" else "UNVERIFIED"
            _voiceAssignments.value = _voiceAssignments.value.map {
                if (it.channel == channel) it.copy(overridePin = pin, overrideStatus = if (ok) "ACTIVE" else "UNVERIFIED") else it
            }
            persistOverride(sha, channel, pin)
            onResult(ok)
        }
    }

    /** Load the cached native preset snapshot for the Mixer picker. */
    fun refreshLoadedPresets() {
        viewModelScope.launch(Dispatchers.Default) { _sf2Presets.value = audioEngine.loadedSoundFontPresets() }
    }

    /** Remove the manual voice; the style's own request plays again. */
    fun resetStyleChannelVoice(channel: Int) {
        val sha = currentStyleSha ?: return
        viewModelScope.launch {
            withContext(Dispatchers.Default) { arrangerBrain.setStyleVoicePin(channel, null) }
            overrideStates.remove(stateKey(false, com.yourapp.yamahaarranger.style.OverrideScope.ALL, null, channel))
            _voiceAssignments.value = _voiceAssignments.value.map {
                if (it.channel == channel) it.copy(overridePin = null, overrideStatus = null) else it
            }
            persistOverride(sha, channel, null)
        }
    }

    private suspend fun persistOverride(sha: String, channel: Int, pin: com.yourapp.yamahaarranger.style.StyleVoicePin?) {
        if (!overridesWritable) { DebugLog.add("CPV overrides not saved: stored file is newer or damaged (kept read-only)"); return }
        val empty = com.yourapp.yamahaarranger.style.StyleOverrideRecord(sha, currentStyleFileName, emptyList())
        val pdta = pin?.let { withContext(Dispatchers.Default) { loadedPdtaByFile()[it.soundFontName] } }.orEmpty()
        fun change(r: com.yourapp.yamahaarranger.style.StyleOverrideRecord) =
            if (pin == null) r.withAllSectionPin(channel, null)
            else r.withSf2(com.yourapp.yamahaarranger.style.StyleVoiceOverride(channel, com.yourapp.yamahaarranger.style.OverrideScope.ALL, null, pin, pdta))
        // The Mixer saves at once, but only its own change: unsaved Style Voice Editor edits stay unsaved.
        currentOverrides = change(currentOverrides ?: savedOverrides ?: empty)
        val next = change(savedOverrides ?: empty).copy(styleFileName = currentStyleFileName, savedAt = System.currentTimeMillis())
        savedOverrides = next
        try {
            withContext(Dispatchers.IO) {
                if (next.isEmpty) overrideStore.delete(sha) else overrideStore.write(next)
            }
            DebugLog.add("CPV overrides saved style=$currentStyleFileName entries=${next.overrides.size}")
        } catch (error: Exception) {
            DebugLog.add("❌ CPV overrides save failed: ${error.javaClass.simpleName}")
        }
        _styleEditorUi.value = editorSnapshot()
    }

    fun setStyleChannelVoice(channel: Int, program: Int, bank: Int) {
        val slot = _voiceAssignments.value.firstOrNull { it.channel == channel } ?: return
        _voiceAssignments.value = _voiceAssignments.value.map { if (it.channel == channel) it.copy(program = program, bank = bank, sf2Name = _sf2Presets.value.firstOrNull { p -> p.bank == bank && p.program == program }?.name) else it }
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
    fun logKeyboardVoiceSnapshot() {
        viewModelScope.launch(Dispatchers.Default) {
            DebugLog.add("KEYBOARD UI release=${_releaseControl.value.effectiveValue} knob=${_releaseControl.value.knobValue} releaseEnabled=${_releaseControl.value.enabled} sustain=${_sustainEnabled.value} " +
                _rightVoices.value.joinToString { "${it.label}:enabled=${it.enabled},fontId=${it.soundFontId},bank=${it.bank},pc=${it.program}" })
            audioEngine.keyboardVoiceSnapshot().lineSequence().filter { it.isNotBlank() }.forEach(DebugLog::add)
        }
    }

    /** On-screen keyboard: plays RIGHT 1/2/3 through ArrangerBrain's virtual path (engine only, never MIDI OUT). */
    val virtualKeyboard = com.yourapp.yamahaarranger.audio.VirtualKeyboardController(arrangerBrain.virtualKeyboardSink)
    private val _virtualKeyboardEpoch = MutableStateFlow(0)
    /** Bumps whenever the keyboard was force-closed (pause/focus loss) so the panel resyncs its controls. */
    val virtualKeyboardEpoch: StateFlow<Int> = _virtualKeyboardEpoch
    fun closeVirtualKeyboard() { virtualKeyboard.closePanel(); _virtualKeyboardEpoch.update { it + 1 } }

    // ---- RIGHT latency A/B (docs/latency/RIGHT_LATENCY_AB.md). Nothing here is persisted: every launch starts at A. ----
    private val _bufferMode = MutableStateFlow(0)
    val bufferMode: StateFlow<Int> = _bufferMode
    private var bufferTickJob: kotlinx.coroutines.Job? = null

    fun setBufferMode(mode: Int) {
        val clamped = mode.coerceIn(0, 3)
        _bufferMode.value = clamped
        bufferTickJob?.cancel(); bufferTickJob = null
        viewModelScope.launch(Dispatchers.Default) {
            val frames = audioEngine.setBufferMode(clamped)
            DebugLog.add("LATENCY BUFFER mode=$clamped bufferNow=$frames")
        }
        if (clamped == 3) bufferTickJob = viewModelScope.launch(Dispatchers.Default) {
            while (_bufferMode.value == 3) { audioEngine.bufferTick(); delay(250) }
        }
    }

    /** A/B: B skips the keyboard NOTE log lines (measured ~0.6 ms each on the MIDI thread). Not persisted. */
    fun setLeanNoteLog(on: Boolean) {
        com.yourapp.yamahaarranger.audio.NoteLogTuning.lean = on
        com.yourapp.yamahaarranger.audio.NoteLogTuning.resetCounter()
    }
    fun leanNoteLog(): Boolean = com.yourapp.yamahaarranger.audio.NoteLogTuning.lean

    /** Path A (live render) vs path B (isolated decode) for the next RIGHT note; style must be stopped. */
    suspend fun armLiveOnset(): String = withContext(Dispatchers.Default) {
        if (audioEngine.armLiveOnset { !arrangerBrain.state.value.isPlaying }) "armed" else "refused: stop the style first"
    }
    suspend fun liveOnsetStatusText(): String = withContext(Dispatchers.Default) { audioEngine.liveOnsetStatus() }
    suspend fun liveOnsetReportText(): String = withContext(Dispatchers.Default) { audioEngine.liveOnsetReport() }

    suspend fun bufferStatusText(): String = withContext(Dispatchers.Default) { audioEngine.bufferStatus() }
    suspend fun latencyReportText(): String = withContext(Dispatchers.Default) { audioEngine.bufferStatus() + audioEngine.latencyReport() }

    /** Offline onset of the first enabled RIGHT part at C4 (MIDI 60). Needs the style stopped. */
    suspend fun runOnsetTest(): String = withContext(Dispatchers.Default) {
        val layer = _rightVoices.value.firstOrNull { it.layer in 0..2 && it.enabled }?.layer
            ?: return@withContext "ONSET unavailable: no RIGHT part is ON"
        audioEngine.rightOnsetReport(layer, 60) { !arrangerBrain.state.value.isPlaying }
    }

    /** App-side (NOT backend-read) values for one RIGHT layer; the panel labels them as APP STATE. */
    data class RightDiagApp(
        val label: String, val enabled: Boolean, val voiceName: String, val soundFontName: String,
        val bank: Int, val program: Int, val volumeKnob: Int, val expressionSent: Int, val reverb: Int, val chorus: Int
    )
    data class RightDiagSnapshot(
        val backend: List<com.yourapp.yamahaarranger.audio.RightVoiceDiagRow>,
        val app: List<RightDiagApp>
    )

    /** Read-only. Backend readback plus app state; never changes voice, SF2 or controllers. */
    suspend fun rightVoiceDiagSnapshot(): RightDiagSnapshot = withContext(Dispatchers.Default) {
        val backend = com.yourapp.yamahaarranger.audio.RightVoiceDiagParser.parse(audioEngine.rightVoiceDiagnostic())
        val details = _keyboardMixerDetails.value
        val app = _rightVoices.value.filter { it.layer in 0..2 }.map { slot ->
            RightDiagApp(slot.label, slot.enabled, slot.displayName(), slot.soundFontName, slot.bank, slot.program,
                keyboardVolume(slot.layer).value, _mixerConsole.value.keyboardGain(slot.layer, keyboardVolume(slot.layer).value),
                details[slot.layer].reverb, details[slot.layer].chorus)
        }
        RightDiagSnapshot(backend, app)
    }

    fun resetRightDiagStats() {
        com.yourapp.yamahaarranger.audio.RightVelocityMonitor.shared.reset()
        viewModelScope.launch(Dispatchers.Default) { audioEngine.resetRightVelocityStats() }
    }

    fun runKeyboardReleaseProbe(part: KeyboardPart, onResult: (ByteArray) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            var wav = byteArrayOf()
            try {
                val probe = audioEngine.diagnosticKeyboardRelease(part) { !arrangerBrain.state.value.isPlaying }
                wav = probe.wav
                probe.report.lineSequence().filter { it.isNotBlank() }.forEach(DebugLog::add)
                DebugLog.add("RELEASE CONTEXT part=${part.label} knob=${_releaseControl.value.knobValue} effective=${_releaseControl.value.effectiveValue} releaseEnabled=${_releaseControl.value.enabled} panelSustain=${_sustainEnabled.value} " +
                    "midiOut=${_midiOutEnabled.value} externalAudioUsesCC72=${_midiOutEnabled.value}")
                audioEngine.keyboardVoiceSnapshot().lineSequence().filter { it.isNotBlank() }.forEach(DebugLog::add)
            } catch (error: Exception) {
                DebugLog.add("❌ RELEASE PCM failed: ${error.javaClass.simpleName}: ${error.message}")
            } finally { withContext(NonCancellable + Dispatchers.Main) { onResult(wav) } }
        }
    }

    fun playTestTone() {
        viewModelScope.launch {
            DebugLog.add("🔊 TEST TONE")
            for (note in intArrayOf(60, 64, 67)) { audioEngine.testTone(note, 0.9f); delay(400); audioEngine.noteOff(note) }
        }
    }

    // Snapshot of the parsed style for explicit read-only Inspector export.
    // Does not record inside the note/timing loop or modify ArrangerBrain.
    private var drumAuditStyle: com.yourapp.yamahaarranger.style.ParsedStyle? = null

    suspend fun diagnosticDrumWav(bank: Int, pc: Int, key: Int, velocity: Int): ByteArray {
        if (uiState.value.isPlaying) return byteArrayOf()
        val style = drumAuditStyle ?: return byteArrayOf()
        val profiles = style.sections.keys.map {
            com.yourapp.yamahaarranger.arranger.DrumStyleAuditProfile.from(style, it)
        }
        // Only audition an event key/velocity present in the loaded raw style; no guessed remap.
        if (profiles.none { profile -> profile.histogram.toList().chunked(4).any { it[1] == key && it[2] == velocity } })
            return byteArrayOf()
        return withContext(Dispatchers.IO) {
            if (uiState.value.isPlaying) byteArrayOf() else audioEngine.diagnosticDrumWav(bank, pc, key, velocity)
        }
    }

    fun armChordDiagnostic() {
        DebugLog.beginTiming()
        arrangerBrain.armChordDiagnostic()
        DebugLog.add("CHORD CAPTURE armed for 60s; play C -> F -> G -> C, then END CAPTURE and SAVE CHORD (small file)")
    }
    fun stopChordDiagnostic() {
        arrangerBrain.stopChordDiagnostic()
        DebugLog.endTiming()
        DebugLog.add("CHORD CAPTURE ended; SAVE CHORD exports the small focused report")
    }

    suspend fun compactChordReport(): String = withContext(Dispatchers.Default) {
        val header = "YAMAHA ARRANGER CHORD CAPTURE (compact)\nStyle=${uiState.value.styleName.take(64)} section=${uiState.value.activeSection} maxFileBytes=49152\nNo SF2 inventories/zone dumps. Channel numbers zero-based.\n"
        val rows = (DebugLog.timingReport() + "\n" + arrangerBrain.compactChordDiagnosticReport() + "\n" + audioEngine.compactChordDiagnosticReport()).lineSequence().toList()
        com.yourapp.yamahaarranger.arranger.ChordReportBounds.lines(header, rows)
    }

    fun diagnosticSoloStrings2() {
        for (channel in 8..15) {
            val muted = channel != 14
            _voiceAssignments.value = _voiceAssignments.value.map {
                if (it.channel == channel) it.copy(styleMuted = muted) else it
            }
            arrangerBrain.setStyleChannelMute(channel, muted)
        }
        DebugLog.add("DIAGNOSTIC SOLO Strings2 ch14=UNMUTED otherStyleChannels=MUTED; wait a full MainD section for new notes")
    }

    /** File-only scanner: no AudioEngineManager/ArrangerBrain calls, no import or cache writes. */
    suspend fun exportSf2Metadata(output: java.io.OutputStream, full: Boolean): com.yourapp.audio.Sf2SemanticInventory.Summary {
        check(!uiState.value.isPlaying) { "STOP before SF2 metadata export" }
        return withContext(Dispatchers.IO) {
            val sources = contentResolver.sf2MetadataSources()
            com.yourapp.audio.Sf2SemanticInventory.export(sources, output, full)
        }
    }

    /** File-only selection and independent JNI backend; no production audio/arranger calls. */
    suspend fun managedAuditionFonts(): List<com.yourapp.audio.ManagedSf2Audition.Font> {
        check(!uiState.value.isPlaying) { "STOP before scanning audition fingerprints" }
        return withContext(Dispatchers.IO) {
            com.yourapp.audio.ManagedSf2Audition.fonts(contentResolver.sf2MetadataSources())
        }
    }

    suspend fun managedSf2Audition(
        request: com.yourapp.audio.ManagedSf2Audition.Request,
        scratch: java.io.File,
        backend: (java.io.File, com.yourapp.audio.ManagedSf2Audition.Request) -> com.yourapp.audio.ManagedSf2Audition.NativeResult =
            com.yourapp.audio.ManagedSf2AuditionNative::decode
    ): com.yourapp.audio.ManagedSf2Audition.Result {
        check(!uiState.value.isPlaying) { "STOP before diagnostic audition" }
        return withContext(Dispatchers.IO) {
            com.yourapp.audio.ManagedSf2Audition.render(request, contentResolver.sf2MetadataSources(), scratch,
                { !uiState.value.isPlaying }, backend)
        }
    }

    private val drumShadowCache = com.yourapp.audio.DrumShadowPlanner.Cache()

    /** Whole-demand generic milestone; resource/lane attestation absence causes per-note ABSTAIN. */
    suspend fun exportGenericDrumShadow(allowApproximation: Boolean = false, requestExperimental: Boolean = false): String {
        check(!uiState.value.isPlaying) { "STOP before preparing generic shadow resolver" }
        val style=drumAuditStyle ?: error("Load a style first")
        return withContext(Dispatchers.IO) {
            val p=com.yourapp.audio.DrumShadowPlanner
            val fonts=p.inventory(contentResolver.sf2MetadataSources())
            val demand=p.requests(style)
            val registry=com.yourapp.audio.DrumSemanticEvidenceRegistry.bundled()
            check(!uiState.value.isPlaying && drumAuditStyle===style) { "Style/playback changed; STOP and retry" }
            val before=p.snapshot(audioEngine.shadowDrumSnapshot(),fonts)
            val generic=com.yourapp.audio.GenericDrumResolver
            // No native resource/lane adapter is invented from a metadata-only readiness observation.
            val plan=generic.compile(demand,fonts,registry,before,before,com.yourapp.audio.GenericDrumResolver.Policy(allowApproximation),sourceDigest=p.styleDigest(style))
            val after=p.snapshot(audioEngine.shadowDrumSnapshot(),fonts)
            check(!uiState.value.isPlaying && drumAuditStyle===style) { "Style/playback changed during resolver preparation; retry" }
            val result=if(com.yourapp.audio.DrumEngineeringProof.generation(before,after).status==com.yourapp.audio.DrumEngineeringProof.Status.PASS)plan
                else generic.compile(demand,fonts,registry,before,after,com.yourapp.audio.GenericDrumResolver.Policy(allowApproximation),sourceDigest=p.styleDigest(style))
            check(!uiState.value.isPlaying && drumAuditStyle===style) { "Style/playback changed during final shadow compilation; retry" }
            val rehearsal=com.yourapp.audio.GenericDrumShadowRehearsal
            val legacy=rehearsal.replay(style,result,after,false)
            val proposed=rehearsal.replay(style,result,after,true)
            check(!uiState.value.isPlaying && drumAuditStyle===style) { "Style/playback changed during rehearsal; retry" }
            com.yourapp.audio.GenericDrumShadowExport.export(result,requestExperimental,
                "legacy=$legacy proposed=$proposed projectionTraceIdentical=${legacy.traceSHA256==proposed.traceSHA256};scope=RAW_SOURCE_TOKEN_CONTRACT_NOT_OBSERVED_PRODUCTION_SCHEDULER")
        }
    }

    /** Explicit STOP preparation/export only. No sequencer or runtime activation. */
    suspend fun exportShadowDrum(evidenceText: String = "", allowApproximation: Boolean = false, useAuditedRegistry: Boolean = true): String {
        check(!uiState.value.isPlaying) { "STOP before preparing shadow export" }
        val style = drumAuditStyle ?: error("Load a style first")
        return withContext(Dispatchers.IO) {
            val p = com.yourapp.audio.DrumShadowPlanner
            val demand = p.requests(style)
            val fonts = p.inventory(contentResolver.sf2MetadataSources())
            val evidence = p.evidence(evidenceText)
            val registry = if(useAuditedRegistry) com.yourapp.audio.DrumSemanticEvidenceRegistry.bundled() else com.yourapp.audio.DrumSemanticEvidenceRegistry.empty()
            check(!uiState.value.isPlaying && drumAuditStyle === style) { "Style/playback changed during preparation; STOP and retry" }
            val snapshot = p.snapshot(audioEngine.shadowDrumSnapshot(), fonts)
            val plan = drumShadowCache.prepare(demand,fonts,evidence,com.yourapp.audio.DrumShadowPlanner.Policy(allowApproximation),snapshot,p.styleDigest(style),registry)
            check(!uiState.value.isPlaying && drumAuditStyle === style) { "Style/playback changed during preparation; retry" }
            val proof = com.yourapp.audio.DrumEngineeringProof
            val engineering = proof.audit(plan,fonts,snapshot,snapshot,style)
            // Second observational getter catches changes across the whole worker audit; no resource preparation.
            val after = p.snapshot(audioEngine.shadowDrumSnapshot(), fonts)
            check(!uiState.value.isPlaying && drumAuditStyle === style) { "Style/playback changed during engineering audit; retry" }
            p.export(plan,fonts,snapshot,registry,proof.seal(engineering,snapshot,after))
        }
    }

    suspend fun compactDrumCompatibilityReport(comparison: String = ""): String {
        if (uiState.value.isPlaying) return "DRUM COMPATIBILITY unavailable: STOP before export\n"
        val style = drumAuditStyle ?: return "DRUM COMPATIBILITY unavailable: load a style first\n"
        return withContext(Dispatchers.Default) {
            val profile = com.yourapp.yamahaarranger.arranger.DrumCompatibilityProfile.from(style)
            // Capture after STOP; no resets, audition, preset activation or controller writes.
            val kits = com.yourapp.yamahaarranger.arranger.DrumCompatibilityProfile.comparisonKits(comparison)
                ?: return@withContext "DRUM COMPARE unavailable: enter 1..4 bank:PC pairs separated by commas\n"
            val provenance = if (kits.isEmpty()) profile.header else profile.header.replace("(compact v1)", "(compact compare v2)")
            provenance + if (kits.isEmpty()) audioEngine.drumCompatibilityReport(profile.histogram)
                else audioEngine.drumCompatibilityComparison(profile.histogram, kits)
        }
    }

    suspend fun compactPartPresenceReport(): String = arrangerBrain.compactPartPresenceReport()

    private var pcmSessionUiMark = "PCM_SESSION_UI status=UNMARKED"
    fun setR22ShadowEnabled(value:Boolean) { if(!uiState.value.isPlaying) com.yourapp.yamahaarranger.arranger.DrumKitShadowRuntime.setEnabled(value) }
    fun r22ShadowReport():String = com.yourapp.yamahaarranger.arranger.DrumKitShadowRuntime.report()
    // CPV Style Voice Inspector: read-only style requests and native channel bindings.
    fun styleVoiceRequests(): List<com.yourapp.yamahaarranger.style.StyleVoiceRequest> =
        drumAuditStyle?.let { com.yourapp.yamahaarranger.style.StyleVoiceRequests.extract(it) } ?: emptyList()
    fun channelBindings(): Map<Int, com.yourapp.yamahaarranger.audio.ChannelBinding> =
        (4..15).mapNotNull { ch -> audioEngine.channelBinding(ch)?.let { ch to it } }.toMap()
    fun styleVoiceReportText(): String = buildString {
        appendLine("style='${_styleName.value}'")
        append(com.yourapp.yamahaarranger.style.StyleVoiceRequests.report(styleVoiceRequests()))
        channelBindings().values.sortedBy { it.channel }.forEach { b ->
            appendLine("STYLE_VOICE_ACTUAL ch=${b.channel} status=${b.status} source=${b.source} sf2='${b.soundFontName}' " +
                "bank=${b.bank} pc=${b.program} preset='${b.presetName}' reason=${b.reason}")
        }
    }
    /** Loaded presets of every SoundFont with their content fingerprint (identity is never the name alone). */
    suspend fun loadEditorCatalog(): List<com.yourapp.yamahaarranger.style.CatalogPreset> = withContext(Dispatchers.Default) {
        val pdta = HashMap<Long, String>()
        audioEngine.loadedSoundFontPresets().map {
            com.yourapp.yamahaarranger.style.CatalogPreset(it.soundFontName, pdta.getOrPut(it.soundFontId) { audioEngine.soundFontPdta(it.soundFontId) },
                it.bank, it.program, it.name, it.isDrum)
        }
    }

    /**
     * Style Voice Inspector snapshot. Reads the loaded-preset snapshot and the native channel bindings,
     * so it must run off the main thread. Read-only: nothing here sends MIDI or changes a binding.
     */
    suspend fun loadStyleVoiceInventory(knownCatalog: List<com.yourapp.yamahaarranger.style.CatalogPreset>? = null): com.yourapp.yamahaarranger.style.StyleVoiceInventory? = withContext(Dispatchers.Default) {
        val style = drumAuditStyle ?: return@withContext null
        val catalog = knownCatalog ?: loadEditorCatalog()
        val observed = channelBindings().mapValues { (ch, b) ->
            com.yourapp.yamahaarranger.style.ObservedBinding(ch, b.status, b.source, b.soundFontName, b.pdta, b.bank, b.program, b.presetName, b.reason)
        }
        val activeKey = SECTION_BUTTON_MAP[uiState.value.activeSection]?.styleName
        val overrides = com.yourapp.yamahaarranger.style.StyleVoiceEditorOps.rowOverrides(
            currentOverrides, style.sections.keys, _midiOutEnabled.value,
            { overrideStates[stateKey(false, it.scope, it.section, it.channel)] ?: "UNVERIFIED" },
            { overrideStates[stateKey(true, it.scope, it.section, it.channel)] ?: "UNVERIFIED" })
        com.yourapp.yamahaarranger.style.StyleVoiceInventoryBuilder.build(style, catalog, observed, activeKey, overrides,
            session = com.yourapp.yamahaarranger.style.InspectorSession(playing = uiState.value.isPlaying))
    }

    /** External Voices the user has saved in the Registration EXT list (the only PSR-E343 numbers this app can offer). */
    fun editorExternalChoices(): List<com.yourapp.yamahaarranger.style.ExternalVoice> =
        liveLibrary.value.manifest.externalVoices.map { com.yourapp.yamahaarranger.style.ExternalVoice(it.voice.msb, it.voice.lsb, it.voice.program, it.voice.label) }

    private fun executeOverrideChanges(old: com.yourapp.yamahaarranger.style.StyleOverrideRecord?, new: com.yourapp.yamahaarranger.style.StyleOverrideRecord?) {
        for (c in com.yourapp.yamahaarranger.style.StyleVoiceEditorOps.diff(old, new)) {
            val all = c.scope == com.yourapp.yamahaarranger.style.OverrideScope.ALL
            val key = stateKey(c.external, c.scope, c.section, c.channel)
            if (!c.external) {
                val pin = c.sf2?.pin
                val ok = if (all) arrangerBrain.setStyleVoicePin(c.channel, pin) else arrangerBrain.setStyleSectionVoicePin(c.section!!, c.channel, pin)
                if (pin != null) overrideStates[key] = if (ok) "ACTIVE" else "UNVERIFIED" else overrideStates.remove(key)
            } else {
                val voice = c.externalEntry?.voice
                val ok = arrangerBrain.setStyleExternalVoice(c.section, c.channel, voice)
                if (voice != null) overrideStates[key] = if (ok) "ACTIVE" else "UNVERIFIED" else overrideStates.remove(key)
            }
        }
    }

    private fun emptyProfile() = com.yourapp.yamahaarranger.style.StyleOverrideRecord(currentStyleSha ?: "0".repeat(64), currentStyleFileName, emptyList())

    /** APPLY: plays at once and marks the profile unsaved. [onResult] gets an error text or null. */
    fun editorApply(section: String, channel: Int, original: com.yourapp.yamahaarranger.style.OriginalRef?,
        scope: com.yourapp.yamahaarranger.style.EditScope, replacement: com.yourapp.yamahaarranger.style.Replacement, onResult: (String?) -> Unit) {
        if (currentStyleSha == null) return onResult("No Style is loaded.")
        if (!overridesWritable) return onResult("This Style's stored profile is newer or damaged; it is kept read-only.")
        viewModelScope.launch(Dispatchers.Default) {
            val old = currentOverrides ?: emptyProfile()
            val next = try {
                com.yourapp.yamahaarranger.style.StyleVoiceEditorOps.apply(old, com.yourapp.yamahaarranger.style.EditTarget(section, channel, original), scope, replacement)
            } catch (e: IllegalArgumentException) { return@launch onResult(e.message ?: "Not allowed.") }
            executeOverrideChanges(old, next)
            currentOverrides = next
            refreshAssignmentsFromRecord()
            val problem = com.yourapp.yamahaarranger.style.StyleVoiceEditorOps.diff(old, next).firstOrNull { c ->
                overrideStates[stateKey(c.external, c.scope, c.section, c.channel)] == "UNVERIFIED" }
            onResult(if (problem != null) "Applied, but the engine could not verify it (preset not loaded?). It is kept and marked UNVERIFIED." else null)
        }
    }

    private fun editorReset(change: (com.yourapp.yamahaarranger.style.StyleOverrideRecord) -> com.yourapp.yamahaarranger.style.StyleOverrideRecord) {
        if (currentStyleSha == null) return
        viewModelScope.launch(Dispatchers.Default) {
            val old = currentOverrides ?: emptyProfile()
            val next = change(old)
            executeOverrideChanges(old, next)
            currentOverrides = next
            refreshAssignmentsFromRecord()
        }
    }
    /** RESET PART: every level of one part; the Style's own request plays again. */
    fun editorResetPart(channel: Int) = editorReset { it.withoutPart(channel) }
    /** RESET SECTION: section-specific entries of one section; part-wide entries stay. */
    fun editorResetSection(section: String) = editorReset { it.withoutSection(section) }
    /** RESET ALL: the Style plays exactly as authored. Saving afterwards deletes the stored profile. */
    fun editorResetAll() = editorReset { it.cleared() }

    /** SAVE PROFILE: writes the working profile next to (not into) the Style file, keyed by its content hash. */
    fun editorSave(onResult: (String) -> Unit) {
        val sha = currentStyleSha ?: return onResult("No Style is loaded.")
        if (!overridesWritable) return onResult("Not saved: the stored profile is newer or damaged and is kept read-only.")
        viewModelScope.launch {
            val record = (currentOverrides ?: emptyProfile()).copy(styleFileName = currentStyleFileName, savedAt = System.currentTimeMillis())
            val message = try {
                withContext(Dispatchers.IO) { if (record.isEmpty) overrideStore.delete(sha) else overrideStore.write(record) }
                currentOverrides = record; savedOverrides = record
                if (record.isEmpty) "Profile removed: the Style plays as authored." else "Profile saved (revision ${record.revision})."
            } catch (error: Exception) { "Not saved: ${error.javaClass.simpleName}" }
            _styleEditorUi.value = editorSnapshot(message)
            onResult(message)
        }
    }

    private var auditionJob: kotlinx.coroutines.Job? = null
    /**
     * AUDITION: four short test notes on the part's own Style channel, only while the Style is stopped.
     * [replacement] null with [useOriginal] plays the Style's own request. Nothing is saved; the effective voice comes back at the end.
     */
    fun editorAudition(row: com.yourapp.yamahaarranger.style.StyleVoiceRow, replacement: com.yourapp.yamahaarranger.style.Replacement?,
        useOriginal: Boolean, onResult: (String?) -> Unit) {
        if (uiState.value.isPlaying) return onResult("Stop the Style first: audition uses this part's own channel.")
        if (row.program == null && useOriginal) return onResult("The Style sends no Program Change for this part.")
        val channel = row.destinationChannel
        auditionJob?.cancel()
        auditionJob = viewModelScope.launch(Dispatchers.Default) {
            val pin = (replacement as? com.yourapp.yamahaarranger.style.Replacement.Sf2)?.preset?.let {
                com.yourapp.yamahaarranger.style.StyleVoicePin(it.soundFontName, it.bank, it.program, it.name, false) }
            val original = if (useOriginal) Triple(row.program!!, com.yourapp.yamahaarranger.style.StyleVoiceInventoryBuilder.requestedBank(row.msb, row.lsb), row.casmVoiceName.ifBlank { null }) else null
            val external = when {
                replacement is com.yourapp.yamahaarranger.style.Replacement.External -> replacement.voice
                useOriginal -> com.yourapp.yamahaarranger.style.ExternalVoice(row.msb ?: 0, row.lsb ?: 0, row.program!!, "Style request")
                else -> null
            }
            val refusal = arrangerBrain.auditionBegin(channel, pin, original, external)
            if (refusal != null) return@launch onResult(refusal)
            _styleEditorUi.value = _styleEditorUi.value.copy(auditioning = true)
            var sounding = -1
            try {
                for (note in com.yourapp.yamahaarranger.style.StyleVoiceEditorOps.auditionNotes(channel)) {
                    if (uiState.value.isPlaying) break
                    arrangerBrain.auditionNote(channel, note, true); sounding = note
                    kotlinx.coroutines.delay(380)
                    arrangerBrain.auditionNote(channel, note, false); sounding = -1
                    kotlinx.coroutines.delay(60)
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (sounding >= 0) arrangerBrain.auditionNote(channel, sounding, false)
                    arrangerBrain.auditionEnd(channel)
                    _styleEditorUi.value = _styleEditorUi.value.copy(auditioning = false)
                }
            }
            onResult(null)
        }
    }
    fun editorAuditionStop() { auditionJob?.cancel() }

    // LATENCY_PROBE: statistics only; both calls are safe during playback but belong off the realtime path.
    fun latencyReport(): String = audioEngine.latencyReport()
    fun latencyReset() = audioEngine.latencyReset()
    fun drumABResolved():Boolean = audioEngine.drumABResolved
    fun setDrumABMode(resolved:Boolean):Boolean = !uiState.value.isPlaying && arrangerBrain.setDrumABMode(resolved)
    fun markPcmSession(): Long {
        if (uiState.value.isPlaying) return 0
        val id = audioEngine.markPcmSession()
        if (id > 0) pcmSessionUiMark = "PCM_SESSION_UI id=$id markTransport=STOP markSection=${uiState.value.activeSection}"
        return id
    }

    suspend fun drumKitAuditReport(): String {
        val style = drumAuditStyle ?: return "counters=process_lifetime; use PCM_SESSION for per-test deltas\n" +
            audioEngine.pcmSessionReport() + pcmSessionUiMark + "\nDRUM KIT AUDIT unavailable: load a style first\n"
        return withContext(Dispatchers.Default) {
            buildString {
                appendLine("counters=process_lifetime; use PCM_SESSION for per-test deltas")
                appendLine(audioEngine.pcmSessionReport())
                appendLine(pcmSessionUiMark)
                appendLine(audioEngine.drumABReport())
                appendLine(com.yourapp.yamahaarranger.arranger.DrumKitShadowRuntime.report())
                appendLine(arrangerBrain.partPresenceReport(style))
                appendLine(arrangerBrain.chordDiagnosticReport())
                appendLine(audioEngine.chordDiagnosticReport())
                val nativeFidelitySnapshot = audioEngine.noteZoneReport()
                appendLine(nativeFidelitySnapshot)
                if (!uiState.value.isPlaying) appendLine(com.yourapp.yamahaarranger.arranger.StyleFidelityReport.export(
                    style, uiState.value.activeSection, nativeFidelitySnapshot))
                else appendLine("STYLE FIDELITY U0 unavailable: STOP before diagnostic export")
                appendLine("=== ALL LOADED DEDICATED DRUM KITS / STYLE SECTION COVERAGE ===")
                val profiles = style.sections.keys.sorted().map { sectionName ->
                    com.yourapp.yamahaarranger.arranger.DrumStyleAuditProfile.from(style, sectionName)
                }
                val mainD = com.yourapp.yamahaarranger.arranger.DrumStyleAuditProfile.from(style)
                appendLine("=== MAIND BASELINE (one raw section) ===")
                appendLine(mainD.header)
                if (mainD.histogram.isNotEmpty()) appendLine(audioEngine.drumKitCoverage(mainD.histogram))
                appendLine("=== ALL STYLE SECTIONS (each once; source keys/velocities, not PCM) ===")
                profiles.forEach { appendLine(it.header) }
                if (profiles.any { it.header.contains("ambiguous") }) {
                    appendLine("ALL SECTION AUDIT unavailable: ambiguous rhythm routing; no invented union")
                } else {
                    val allBins = profiles.flatMap { it.histogram.toList() }.toIntArray()
                    appendLine(audioEngine.drumKitCoverage(allBins))
                }
            }
        }
    }

    fun onStyleFilePicked(uri: Uri) {
        val request = audioEngine.readiness.beginStyle()
        viewModelScope.launch {
            var committed = false
            try {
                val bytes = withContext(Dispatchers.IO) { contentResolver.readBytes(uri) }
                if (bytes == null) { DebugLog.add("❌ Cannot read style"); return@launch }
                // Prefer the actual URI/path filename. Some Android providers expose
                // a generic name such as "style.sty", which loses the real style name.
                val uriFileName = uri.path?.let { path ->
                    path.substringAfterLast('/').takeIf { it.isNotBlank() }
                }
                val fileName = uriFileName ?: contentResolver.fileName(uri) ?: "style.sty"
                val parsed = withContext(Dispatchers.Default) { styleRepository.loadStyle(fileName, bytes) }
                if (parsed == null) { DebugLog.add("❌ Parse fail: $fileName"); return@launch }
                audioEngine.readiness.operations.withLock {
                    if (!audioEngine.readiness.current(request)) return@withLock
                    // R22 preparation: read-only metadata, completed before any playback.
                    try { com.yourapp.yamahaarranger.arranger.DrumKitShadowRuntime.prepare(parsed,bytes,contentResolver.r22CatalogInput()) }
                    catch(error:Exception) { DebugLog.add("R22 metadata unavailable: ${error.javaClass.simpleName}") }
                    drumAuditStyle = parsed
                    arrangerBrain.loadStyle(parsed)
                    liveSectionInventoryState.value = LiveSectionInventory.from(parsed) // LIVE V3: read-only metadata
                    _voiceAssignments.value = voiceSlotsFromStyle(parsed)
                    // CPV: restore this style's saved manual voices (pins were cleared by loadStyle).
                    currentStyleSha = com.yourapp.yamahaarranger.style.StyleOverrideStore.sha256(bytes)
                    currentStyleFileName = fileName
                    val saved = withContext(Dispatchers.IO) { overrideStore.read(currentStyleSha!!) }
                    currentOverrides = saved.record
                    savedOverrides = saved.record
                    overridesWritable = saved.writable
                    DebugLog.add("CPV overrides style=$fileName status=${saved.status} entries=${saved.record?.overrides?.size ?: 0}")
                    restoreStyleOverrides()
                    // Register destinations before publishing READY; the first note must not race a UI collector.
                    midiInputManager.setMixerStyleChannels(_voiceAssignments.value.map { it.channel }.toSet())
                    syncMixerMidiMute()
                    _styleName.value = fileName
                    DebugLog.add("🎼 Mixer channels from style: ${_voiceAssignments.value.map { "${it.channel}:${it.sf2Name}@${it.bank}/${it.program}" }.joinToString()}")
                    DebugLog.add("✅ Loaded: $fileName")
                    audioEngine.readiness.finishStyle(request, parsed.sections.keys, parsed)
                    committed = true
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { DebugLog.add("❌ Style load failed: ${error.javaClass.simpleName}")
            } finally { if (!committed) audioEngine.readiness.finishStyle(request, null) }
        }
    }

    /**
     * CPV font stack: Colombo GM/GS2 primary, Tyros 4 fallback, no Prime and no
     * dedicated drum SF2. Returns false when neither Colombo nor Tyros exists.
     */
    private suspend fun loadCpvStack(files: List<Pair<Uri, String>>, request: ReadyToPlayGate.Request): Boolean {
        val plan = com.yourapp.yamahaarranger.audio.SoundFontRolePlanner.plan(files) { it.second }
        val primary = plan.primary ?: return false
        DebugLog.add("🎯 CPV SF2 plan: PRIMARY='${primary.second}' FALLBACK='${plan.fallback?.second ?: "-"}' " +
            "notAutoLoaded=${plan.excluded.joinToString { it.second }}")
        val primaryCache = withContext(Dispatchers.IO) {
            contentResolver.copySoundFontToCache(primary.first, primary.second, "ready-${request.generation}")
        }
        val fallbackCache = plan.fallback?.let { f -> withContext(Dispatchers.IO) {
            contentResolver.copySoundFontToCache(f.first, f.second, "ready-${request.generation}")
        } }
        if (primaryCache == null) { DebugLog.add("❌ CPV SF2 cache failed"); _soundFontName.value = "Load failed"; return true }
        // SX Prime Melody joins as a manual-only SF2 so its presets can be chosen by hand (Voice Selector, Style Voice Editor).
        val prime = plan.excluded.firstOrNull { com.yourapp.yamahaarranger.audio.SoundFontRolePlanner.kind(it.second) == com.yourapp.yamahaarranger.audio.SoundFontRolePlanner.Kind.PRIME_MELODY }
        val primeCache = prime?.let { f -> withContext(Dispatchers.IO) { contentResolver.copySoundFontToCache(f.first, f.second, "ready-${request.generation}") } }
        val ok = withContext(Dispatchers.Default) {
            audioEngine.loadColomboPrimaryStack(primaryCache.absolutePath, fallbackCache?.absolutePath, request, primeCache?.absolutePath)
        }
        fontRequestSucceeded = ok
        _soundFontName.value = if (ok) primary.second + (plan.fallback?.let { " + " + it.second } ?: "") + (prime?.let { " + " + it.second + " (manual)" } ?: "") else "Load failed"
        if (ok) withContext(Dispatchers.Default) { reapplyStyleVoicePins() }
        return true
    }

    private suspend fun autoLoadSoundFont(request: ReadyToPlayGate.Request) {
        val files = withContext(Dispatchers.IO) { contentResolver.listSoundFonts() }
        if (files.isEmpty()) {
            DebugLog.add("📂 SF2 folder ready: /storage/emulated/0/YamahaArranger/SF2")
            return
        }
        if (loadCpvStack(files, request)) return
        // No Colombo/Tyros: Prime Melodi/Drum and other SF2 files are never auto-loaded.
        DebugLog.add("❌ CPV: Colombo/Tyros SF2 not found; Prime Melodi/Drum are not loaded automatically")
        _soundFontName.value = "Colombo/Tyros SF2 not found"
    }

    private suspend fun loadSoundFontUri(uri: Uri, displayName: String, role: String? = null, replaceAll: Boolean = false, request: ReadyToPlayGate.Request) {
        val cached = withContext(Dispatchers.IO) {
            contentResolver.copySoundFontToCache(uri, displayName, "ready-${request.generation}")
        }
        if (cached == null) {
            DebugLog.add("❌ SF2 cache failed: $displayName")
            return
        }
        val ok = withContext(Dispatchers.Default) {
            if (!audioEngine.readiness.current(request)) return@withContext false
            if (replaceAll) audioEngine.unloadSoundFont()
            when (role) {
                "DRUM" -> audioEngine.loadDrumSoundFont(cached.absolutePath, request)
                "MELODY" -> audioEngine.loadMelodySoundFont(cached.absolutePath, request)
                else -> audioEngine.loadSingleSoundFont(cached.absolutePath, request)
            }
        }
        fontRequestSucceeded = ok
        _soundFontName.value = if (ok) displayName else "Load failed"
        // Do not enumerate every preset immediately after loading a large SF2.
        // Native preset iteration can be expensive and, on some Android builds,
        // can kill the process before the UI can record a log. Presets will be
        // loaded lazily by the explicit refresh/preset UI path.
        DebugLog.add(if (ok) "✅ SF2 loaded: $displayName (preset scan deferred)" else "❌ SF2 load failed: $displayName")
    }

    fun refreshStyleList() {
        viewModelScope.launch(Dispatchers.IO) {
            _styleFolders.value = contentResolver.listStyleFolders()
            // Style Select is folder-first. Keep the file list empty until a
            // folder is explicitly selected, avoiding a refresh race that can
            // overwrite the selected folder's files with the root file list.
            _styleFiles.value = contentResolver.listAllStylesInFolders()
            DebugLog.add("🎼 Style folders found: " + _styleFolders.value.size + " all styles=" + _styleFiles.value.size)
        }
    }

    fun refreshStylesInFolder(folderUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _styleFiles.value = contentResolver.listStylesInFolder(folderUri)
            DebugLog.add("🎼 Styles in folder: " + _styleFiles.value.size)
        }
    }

    fun refreshSoundFontList() {
        viewModelScope.launch(Dispatchers.IO) {
            val files = contentResolver.listSoundFonts()
            _availableSoundFonts.value = files
            _sf2ScanInProgress.value = true
            try {
                val reports = buildList {
                    files.forEachIndexed { index, (uri, name) ->
                        try {
                            val size = uri.path?.let { File(it).length() } ?: -1L
                            val report = contentResolver.openInputStream(uri)?.use { input ->
                                SoundFontInspector.inspect(input, name, size)
                            }
                            if (report != null) {
                                add(report)
                                DebugLog.add(
                                    "🔎 SF2 INSPECT ${index + 1}/${files.size}: $name " +
                                        "valid=${report.validSf2} presets=${report.presets.size}"
                                )
                            } else {
                                DebugLog.add("❌ SF2 INSPECT: cannot open $name")
                            }
                        } catch (t: Throwable) {
                            DebugLog.add("❌ SF2 INSPECT failed: $name ${t.javaClass.simpleName}: ${t.message}")
                        }
                    }
                }
                _sf2Reports.value = reports
            } finally {
                _sf2ScanInProgress.value = false
            }
            _sf2Presets.value = audioEngine.loadedSoundFontPresets()
            DebugLog.add(
                "📂 SF2 found=${files.size} inspected=${_sf2Reports.value.size} " +
                    "loadedPresets=${_sf2Presets.value.size}"
            )
        }
    }

    fun selectSoundFont(uri: Uri, name: String) {
        launchFontRequest { request ->
            fontRequestSucceeded = false
            DebugLog.add("🔄 Selecting SF2: $name")

            // The SF2 Manager lists individual files, but when the managed
            // folder contains a MELODY + DRUM pair they must remain loaded
            // simultaneously. The old code called loadSoundFontUri() with
            // role=null here, which invokes loadSingleSoundFont() and replaces
            // both roles with the selected file. That made selecting the drum
            // font remove the melody font, and vice versa.
            val files = withContext(Dispatchers.IO) { contentResolver.listSoundFonts() }
            // CPV: the managed folder always loads as Colombo primary + Tyros fallback.
            if (loadCpvStack(files, request)) return@launchFontRequest
            DebugLog.add("❌ CPV: Colombo/Tyros SF2 not found; Prime Melodi/Drum are not loaded automatically")
            _soundFontName.value = "Colombo/Tyros SF2 not found"
        }
    }

    fun onSoundFontFilePicked(uri: Uri) {
        launchFontRequest { request ->
            fontRequestSucceeded = false
            DebugLog.add("📂 SF2 picker…")
            val sourceName = contentResolver.fileName(uri) ?: "font.sf2"
            val safeName = sourceName.substringAfterLast('/').ifBlank { "font.sf2" }
                .let { if (it.lowercase().endsWith(".sf2")) it else "$it.sf2" }

            // If the picker already returned a file from our managed SF2 folder,
            // do NOT copy it again. Re-importing such a URI used to create
            // MELODY (1), (2), … duplicates and could even delete the source
            // before copying because saveSoundFont replaces an existing name.
            val alreadyStored = withContext(Dispatchers.IO) {
                contentResolver.isSoundFontInManagedFolder(uri)
            }
            val storedUri = if (alreadyStored) {
                DebugLog.add("📂 SF2 already in managed folder; no copy needed")
                uri
            } else {
                withContext(Dispatchers.IO) {
                    contentResolver.saveSoundFont(uri, safeName)
                }
            }
            if (storedUri == null) {
                DebugLog.add("❌ Could not store SF2 in /storage/emulated/0/YamahaArranger/SF2")
                return@launchFontRequest
            }
            DebugLog.add(if (alreadyStored) {
                "📂 Using existing SF2: /storage/emulated/0/YamahaArranger/SF2/$safeName"
            } else {
                "📂 SF2 stored: /storage/emulated/0/YamahaArranger/SF2/$safeName"
            })
            DebugLog.add("🔄 Loading SF2 directly…")
            loadSoundFontUri(storedUri, safeName, request = request)
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
