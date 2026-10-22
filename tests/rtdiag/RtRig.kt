package com.yourapp.rtdiag

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import com.yourapp.yamahaarranger.arranger.ArrangerBrain
import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.audio.NativeAudioBridge
import com.yourapp.yamahaarranger.audio.SampleProvider
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.chord.ChordMode
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StylePartModel
import com.yourapp.yamahaarranger.style.StyleSectionModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Host rig around the PRODUCTION chain MidiInputManager parser -> ArrangerBrain -> ChordDetector -> StyleSequencer ->
 * AudioEngineManager -> MidiMixerOutput. Only the two ends are fakes: the Android MIDI ports and the JNI bridge.
 * Everything measured here is HOST evidence (x86 JVM, no BASSMIDI, no Oboe, no USB). It says nothing about device speed.
 */
class RtRig(val midiOut: Boolean = false, tempoBpm: Int = 120, private val styleOverride: ParsedStyle? = null) {
    class Ev(val t: Long, val thread: String, val kind: Char, val ch: Int, val note: Int, val kbLock: Boolean, val lcLock: Boolean, val mixLock: Boolean)
    class Wr(val t: Long, val thread: String, val status: Int, val d1: Int, val d2: Int, val kbLock: Boolean, val lcLock: Boolean)

    val engineEvents = ConcurrentLinkedQueue<Ev>()      // kind: 'O' note on, 'F' note off, 'A' all notes off
    val usbWrites = ConcurrentLinkedQueue<Wr>()
    @Volatile var loopback = false
    val loopbackReentries = AtomicLong()
    /** Busy-wait per USB MIDI write (MidiInputPort.send): stands in for socket/USB service latency. A MODEL parameter. */
    @Volatile var usbWriteCostUs = 0L
    private val echoQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private val echoThread = Thread({
        try { while (true) { val b = echoQueue.take(); Thread.sleep(1); receiver?.onSend(b, 0, b.size, System.nanoTime()) } } catch (_: InterruptedException) {}
    }, "echo").apply { isDaemon = true; start() }
    /** Busy-wait per native note call: stands in for the shared synth mutex wait. 0 = free. A MODEL parameter, never a measurement. */
    @Volatile var nativeCallCostUs = 0L
    @Volatile var stallOnceMs = 0L
    @Volatile var stallStartedNs = 0L
    @Volatile var stallThread = "MainSim"

    private val inPort = object : MidiInputPort() {
        override fun send(data: ByteArray, offset: Int, count: Int) {
            val now = System.nanoTime()
            if (count >= 3) usbWrites.add(Wr(now, Thread.currentThread().name, data[offset].toInt() and 0xFF, data[offset + 1].toInt(), data[offset + 2].toInt(),
                holds(kbLockObj), holds(lcLockObj)))
            spin(usbWriteCostUs)
            if (loopback && count >= 3) { loopbackReentries.incrementAndGet(); echoQueue.add(data.copyOfRange(offset, offset + count)) }
        }
    }
    @Volatile var receiver: MidiReceiver? = null
    private val outPort = object : MidiOutputPort() { override fun connect(receiver: MidiReceiver) { this@RtRig.receiver = receiver } }
    private val device = object : MidiDevice() {
        override fun openOutputPort(port: Int) = outPort
        override fun openInputPort(port: Int) = inPort
    }
    private val manager = object : MidiManager() {
        override fun openDevice(info: MidiDeviceInfo, callback: (MidiDevice?) -> Unit, handler: Handler) { callback(device) }
    }
    private val context = object : Context() { override fun getSystemService(name: String): Any? = manager }

    val midi = MidiInputManager(context)
    val bridge: NativeAudioBridge
    val engine: AudioEngineManager
    val brain: ArrangerBrain
    /** Single thread standing in for Android's Main looper (viewModelScope = Dispatchers.Main.immediate). */
    private val mainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "MainSim") }
    val scope = CoroutineScope(SupervisorJob() + mainExecutor.asCoroutineDispatcher())
    val oracle = Oracle()
    private val kbLockObj: Any
    private var lcLockObj: Any = Any()

    class Oracle {
        private val count = HashMap<Int, Int>()
        var polyphony = 0; private set
        var maxPolyphony = 0; private set
        var orphanOffs = 0L; private set
        var ons = 0L; private set
        var offs = 0L; private set
        @Synchronized fun on(ch: Int, note: Int) { ons++; count.merge(ch * 256 + note, 1, Int::plus); polyphony++; if (polyphony > maxPolyphony) maxPolyphony = polyphony }
        /** BASS_MIDI_NOTEOFF1 semantics: one NOTE_OFF releases the oldest instance of that key on that channel. */
        @Synchronized fun off(ch: Int, note: Int) { offs++; val k = ch * 256 + note; val c = count[k] ?: 0; if (c > 0) { if (c == 1) count.remove(k) else count[k] = c - 1; polyphony-- } else orphanOffs++ }
        @Synchronized fun allOff() { count.clear(); polyphony = 0 }
        @Synchronized fun stuck(): Map<Int, Int> = count.toMap()
        @Synchronized fun stuckOnChannels(range: IntRange): Int = count.entries.filter { it.key / 256 in range }.sumOf { it.value }
    }

    init {
        val answer = Answer<Any?> { inv -> handle(inv) }
        bridge = Mockito.mock(NativeAudioBridge::class.java, answer)
        engine = AudioEngineManager(bridge, Mockito.mock(SampleProvider::class.java))
        AudioEngineManager::class.java.getDeclaredField("soundFontLoaded").apply { isAccessible = true }.setBoolean(engine, true)
        brain = ArrangerBrain(engine, ChordDetector(ChordMode.MultiFinger), midi)
        brain.attachScope(scope)
        kbLockObj = field(brain, "keyboardLock")
        // Same wiring as MainViewModel.init (lines 742-748).
        midi.onNoteOn = { note, velocity -> brain.onKeyboardNoteOn(note, velocity / 127f) }
        midi.onNoteOff = { note -> brain.onKeyboardNoteOff(note) }
        midi.onSustainChange = { enabled -> brain.setKeyboardSustain(enabled) }
        midi.connect(MidiDeviceInfo())
        midi.setMixerStyleChannels((4..15).toSet())
        midi.midiOutEnabled = midiOut
        brain.loadStyle(styleOverride?.also { loadedStyle = it } ?: style(tempoBpm))
        val gate = engine.readiness
        val f = gate.beginFonts(); gate.installedFonts(f, true, "m", "d"); gate.finishFonts(f, true)
        gate.finishStyle(gate.beginStyle(), loadedStyle.sections.keys, loadedStyle); gate.outputResult(gate.beginOutput(), true)
        lcLockObj = field(field(brain, "sequencer"), "noteLifecycleLock")
    }

    lateinit var loadedStyle: ParsedStyle

    private fun field(o: Any, name: String): Any { val f = o.javaClass.getDeclaredField(name); f.isAccessible = true; return f.get(o)!! }
    private fun holds(o: Any) = Thread.holdsLock(o)

    private fun spin(us: Long) { if (us <= 0) return; val end = System.nanoTime() + us * 1000; while (System.nanoTime() < end) { Thread.onSpinWait() } }

    private fun handle(inv: InvocationOnMock): Any? {
        val name = inv.method.name
        val a = inv.arguments
        val t = System.nanoTime()
        fun rec(kind: Char, ch: Int, note: Int) {
            // DIAGNOSTIC: one blocking native call (e.g. waiting for the synth mutex) on the named thread, taken while the caller holds its monitors.
            val stall = stallOnceMs
            if (stall > 0 && Thread.currentThread().name == stallThread) { stallOnceMs = 0; stallStartedNs = System.nanoTime(); Thread.sleep(stall) }
            spin(nativeCallCostUs)
            val mixer = try { holds(field(midi, "mixerOutput")) } catch (_: Exception) { false }
            engineEvents.add(Ev(t, Thread.currentThread().name, kind, ch, note, holds(kbLockObj), holds(lcLockObj), mixer))
            if (kind == 'O') oracle.on(ch, note) else if (kind == 'F') oracle.off(ch, note)
        }
        when (name) {
            "nativeSfNoteOnChannel" -> { rec('O', a[0] as Int, a[1] as Int); return null }
            "nativeSfNoteOffChannel" -> { rec('F', a[0] as Int, a[1] as Int); return null }
            "nativeSfNoteOnStyleChannel" -> { rec('O', a[0] as Int, a[1] as Int); return null }
            "nativeSfNoteOffStyleChannel" -> { rec('F', a[0] as Int, a[1] as Int); return null }
            "nativeAllNotesOff" -> { engineEvents.add(Ev(t, Thread.currentThread().name, 'A', -1, -1, holds(kbLockObj), holds(lcLockObj), false)); oracle.allOff(); return null }
        }
        val d = Mockito.RETURNS_DEFAULTS.answer(inv)
        return if (d == null && inv.method.returnType == String::class.java) "" else d
    }

    // ---- MIDI input helpers (bytes exactly as a USB-MIDI device delivers them) ----
    fun feed(vararg bytes: Int) { val b = ByteArray(bytes.size) { bytes[it].toByte() }; receiver!!.onSend(b, 0, b.size, System.nanoTime()) }
    fun noteOn(note: Int, vel: Int = 90, ch: Int = 0) = feed(0x90 or ch, note, vel)
    fun noteOff(note: Int, ch: Int = 0) = feed(0x80 or ch, note, 0)
    fun cc(num: Int, value: Int, ch: Int = 0) = feed(0xB0 or ch, num, value)

    fun close() { scope.cancel(); mainExecutor.shutdownNow(); echoThread.interrupt() }

    fun style(bpm: Int): ParsedStyle {
        fun policy(src: Int, ntt: Int, rtr: Int) = CasmPolicyModel(src, src, "V$src", 0, 0, 0, ntt, 127, 0, 127, rtr, false)
        fun part(src: Int, ntt: Int, rtr: Int, notes: List<StyleNoteEvent>): StylePartModel {
            val p = policy(src, ntt, rtr)
            val setup = listOf(StyleNoteEvent(0, false, 0, 0, src, 0xB0 or src), StyleNoteEvent(0, false, 32, 112, src, 0xB0 or src), StyleNoteEvent(0, false, 0, 0, src, 0xC0 or src))
            return StylePartModel("P$src", setup + notes, casm = p, casmPolicies = listOf(p), program = 0)
        }
        fun note(tick: Int, len: Int, key: Int, ch: Int, vel: Int = 90) = listOf(StyleNoteEvent(tick, true, key, vel, ch), StyleNoteEvent(tick + len, false, key, 0, ch))
        val bar = 1920
        val drums = (0 until 16).flatMap { note(it * 120, 30, 42, 8, 70) } + note(0, 40, 36, 8, 110) + note(960, 40, 36, 8, 110) + note(480, 40, 38, 9, 100) + note(1440, 40, 38, 9, 100)
        val bass = (0 until 4).flatMap { note(it * 480, 400, 36 + (it % 2) * 7, 10) }
        val chord1 = listOf(60, 64, 67).flatMap { note(0, bar - 60, it, 11, 80) }
        val chord2 = listOf(55, 60, 64).flatMap { note(0, bar - 60, it, 12, 70) }
        val pad = listOf(48, 55, 60, 64).flatMap { note(0, bar - 40, it, 13, 60) }
        val phrase = (0 until 8).flatMap { note(it * 240, 200, 72 + (it % 4) * 2, 14, 85) }
        fun section(name: String) = StyleSectionModel(name, bar, listOf(part(8, 0, 0, drums.filter { it.channel == 8 }), part(9, 0, 0, drums.filter { it.channel == 9 }),
            part(10, 3, 1, bass), part(11, 2, 3, chord1), part(12, 2, 1, chord2), part(13, 2, 3, pad), part(14, 1, 1, phrase)))
        val names = ArrangerSection.values().map { it.styleName }
        val s = ParsedStyle("rtdiag.sty", 480, names.associateWith { section(it) }, emptyMap(), bpm)
        loadedStyle = s
        return s
    }

    // ---- statistics ----
    fun count(kind: Char, chRange: IntRange? = null) = engineEvents.count { it.kind == kind && (chRange == null || it.ch in chRange) }
    fun usbCount(on: Boolean) = usbWrites.count { (it.status and 0xF0) == (if (on) 0x90 else 0x80) }
    fun settle(ms: Long) = Thread.sleep(ms)

    /** Peak number of note INSTANCES that are held or still inside a release tail of [releaseSec] (BASS_MIDI_NOTEOFF1 pairing: oldest first).
     *  Derived from the real event stream; one instance is several BASSMIDI voices (zones/layers), so this is a lower bound of the voice load. */
    fun peakInstances(releaseSec: Double): Int {
        val evs = engineEvents.sortedBy { it.t }
        val held = Long.MAX_VALUE
        val live = HashMap<Int, ArrayList<Long>>()   // (ch,note) -> per-instance end time (MAX = still held), oldest first
        var peak = 0
        val tail = (releaseSec * 1e9).toLong()
        for (e in evs) {
            val k = e.ch * 256 + e.note
            when (e.kind) {
                'O' -> live.getOrPut(k) { ArrayList() }.add(held)
                'F' -> live[k]?.let { q -> val idx = q.indexOf(held); if (idx >= 0) q[idx] = e.t + tail }
                'A' -> live.values.forEach { q -> for (i in q.indices) if (q[i] == held) q[i] = e.t + tail }
            }
            var n = 0
            for (q in live.values) { q.removeAll { it != held && it < e.t }; n += q.size }
            if (n > peak) peak = n
        }
        return peak
    }
}

fun pct(values: List<Long>, p: Double): Long { if (values.isEmpty()) return 0; val s = values.sorted(); return s[((s.size - 1) * p).toInt()] }

/** Feeds scripted bytes from one thread standing in for the platform's MIDI dispatch thread; records start lateness (= backlog) and duration. */
class Dispatcher(private val rig: RtRig) {
    class Item(val atNs: Long, val bytes: IntArray)
    val lateNs = ArrayList<Long>(); val costNs = ArrayList<Long>()
    fun run(items: List<Item>) {
        val ex = Executors.newSingleThreadExecutor { r -> Thread(r, "MIDI-dispatch") }
        val t0 = System.nanoTime() + 20_000_000
        ex.submit {
            for (it in items) {
                val target = t0 + it.atNs
                while (System.nanoTime() < target) { val rem = target - System.nanoTime(); if (rem > 2_000_000) Thread.sleep(1) else Thread.onSpinWait() }
                val start = System.nanoTime(); rig.feed(*it.bytes)
                lateNs.add(start - target); costNs.add(System.nanoTime() - start)
            }
        }.get(); ex.shutdown(); ex.awaitTermination(5, TimeUnit.SECONDS)
    }
}
