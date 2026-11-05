package com.yourapp.rtdiag

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.routing.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * DEVICE ROUTING V1 on the host: the PRODUCTION MidiInputManager (parser + router) with fake Android MIDI devices, plus the pure
 * RoutingResolver / RoutingSettings rules. HOST evidence: no USB, no Android MIDI service, no audio device is involved.
 */
class DeviceRoutingRtTest {
    private fun row(s: String) = println("ROUTING $s")

    class FakeDev(id: Int, sends: Boolean, receives: Boolean, type: Int = MidiDeviceInfo.TYPE_USB) {
        val info = MidiDeviceInfo(id, if (sends) 1 else 0, if (receives) 1 else 0, type)
        val written = ConcurrentLinkedQueue<IntArray>()
        @Volatile var receiver: MidiReceiver? = null
        var closed = 0
        private val inPort = object : MidiInputPort() { override fun send(data: ByteArray, offset: Int, count: Int) { written.add(IntArray(count) { data[offset + it].toInt() and 0xFF }) } }
        private val outPort = object : MidiOutputPort() { override fun connect(receiver: MidiReceiver) { this@FakeDev.receiver = receiver } }
        val device = object : MidiDevice() {
            override fun openOutputPort(port: Int): MidiOutputPort? = if (receives) outPort else null
            override fun openInputPort(port: Int): MidiInputPort? = if (sends) inPort else null
            override fun close() { closed++ }
        }
        fun play(vararg b: Int) { receiver!!.onSend(ByteArray(b.size) { b[it].toByte() }, 0, b.size, 0) }
        fun ons(note: Int) = written.count { it.size == 3 && (it[0] and 0xF0) == 0x90 && it[1] == note && it[2] > 0 }
        fun offs(note: Int) = written.count { it.size == 3 && ((it[0] and 0xF0) == 0x80 || ((it[0] and 0xF0) == 0x90 && it[2] == 0)) && it[1] == note }
        fun cc(cc: Int, v: Int) = written.count { it.size == 3 && (it[0] and 0xF0) == 0xB0 && it[1] == cc && it[2] == v }
    }
    class FakeMidi(val devs: List<FakeDev>) : MidiManager() {
        var deferred = false
        val pending = ArrayList<() -> Unit>()
        val opens = HashMap<Int, Int>()
        override val devices: Array<MidiDeviceInfo> get() = devs.map { it.info }.toTypedArray()
        override fun openDevice(info: MidiDeviceInfo, callback: (MidiDevice?) -> Unit, handler: Handler) {
            opens.merge(info.id, 1, Int::plus)
            val d = devs.first { it.info.id == info.id }
            if (deferred) pending += { callback(d.device) } else callback(d.device)
        }
    }
    private class Setup(vararg d: FakeDev) {
        val mgr = FakeMidi(d.toList())
        val midi = MidiInputManager(object : Context() { override fun getSystemService(name: String): Any? = mgr })
        val ons = ArrayList<Int>(); val offs = ArrayList<Int>(); val sustain = ArrayList<Boolean>()
        init { midi.onNoteOn = { n, _ -> ons += n }; midi.onNoteOff = { offs += it }; midi.onSustainChange = { sustain += it }; midi.midiOutEnabled = true }
    }

    @Test fun separateInAndOutDevicesAndUnselectedDeviceGetsNothing() {
        val kb = FakeDev(1, sends = false, receives = true); val mod = FakeDev(2, sends = true, receives = false); val other = FakeDev(3, sends = true, receives = true)
        val s = Setup(kb, mod, other)
        s.midi.connectRouting(kb.info, mod.info)
        kb.play(0x90, 60, 100); kb.play(0x80, 60, 0)
        assertEquals(listOf(60), s.ons); assertEquals(listOf(60), s.offs)
        s.midi.sendNoteOn(0, 64, 100); s.midi.sendNoteOff(0, 64)
        assertEquals(1, mod.ons(64)); assertEquals(1, mod.offs(64))
        assertTrue("an unselected device is never opened or written", other.written.isEmpty() && s.mgr.opens[3] == null)
        assertEquals(1, s.midi.connectedInId); assertEquals(2, s.midi.connectedOutId)
        row("separate IN=kb OUT=module: in NOTE ON=${s.ons.size} out NOTE ON=${mod.ons(64)} other device writes=${other.written.size}")
    }

    @Test fun sameDeviceIsOpenedOnceAndNothingIsDuplicated() {
        val e = FakeDev(1, sends = true, receives = true)
        val s = Setup(e)
        s.midi.connectRouting(e.info, e.info)
        assertEquals("one openDevice for IN + OUT", 1, s.mgr.opens[1])
        e.play(0x90, 60, 90)
        assertEquals(listOf(60), s.ons)
        s.midi.sendNoteOn(0, 67, 100)
        assertEquals("one NOTE ON on the cable", 1, e.ons(67))
    }

    @Test fun midiOutOffSendsNothing() {
        val e = FakeDev(1, sends = true, receives = true)
        val s = Setup(e)
        s.midi.connectRouting(e.info, null)
        s.midi.sendNoteOn(0, 60, 100); s.midi.sendProgramChange(10, 33, 0, 112)
        assertFalse(s.midi.sendSongBytes(byteArrayOf(0x90.toByte(), 60, 100)))
        assertTrue("MIDI OUT OFF: nothing written", e.written.isEmpty())
        e.play(0x90, 61, 90); assertEquals("MIDI IN still works", listOf(61), s.ons)
    }

    @Test fun switchingOutputReleasesNotesOnTheOldDeviceOnly() {
        val kb = FakeDev(1, false, true); val m1 = FakeDev(2, true, false); val m2 = FakeDev(3, true, false)
        val s = Setup(kb, m1, m2)
        s.midi.connectRouting(kb.info, m1.info)
        s.midi.sendNoteOn(0, 60, 100)
        s.midi.connectRouting(kb.info, m2.info)
        assertEquals("held note released on the old device", 1, m1.offs(60))
        assertEquals("sustain up on 16 channels", 16, m1.cc(64, 0)); assertEquals("All Notes Off on 16 channels", 16, m1.cc(123, 0))
        assertTrue("new device gets nothing it did not ask for", m2.written.none { (it[0] and 0xF0) == 0x90 })
        s.midi.sendNoteOn(0, 62, 100); s.midi.sendNoteOff(0, 62)
        assertEquals(1, m2.ons(62)); assertEquals(1, m2.offs(62)); assertEquals(0, m1.ons(62))
        assertEquals("old device closed", 1, m1.closed)
        row("switch OUT m1->m2: m1 NOTE OFF=${m1.offs(60)} CC64=${m1.cc(64, 0)} CC123=${m1.cc(123, 0)}; m2 NOTE ON 62=${m2.ons(62)}")
    }

    @Test fun unpluggingTheKeyboardReleasesHeldKeysAndSustainOnce() {
        val e = FakeDev(1, true, true)
        val s = Setup(e)
        s.midi.connectRouting(e.info, e.info)
        e.play(0x90, 60, 90); e.play(0x90, 64, 90); e.play(0xB0, 64, 127); e.play(0x80, 64, 0)
        assertEquals(listOf(64), s.offs)
        s.midi.inputDeviceRemoved()
        assertEquals("only the still-held key is released, once", listOf(64, 60), s.offs)
        assertEquals(listOf(true, false), s.sustain)
        s.midi.inputDeviceRemoved()   // second removal event: nothing more
        assertEquals(listOf(64, 60), s.offs)
        s.midi.sendNoteOn(0, 70, 100); assertEquals("shared device: MIDI OUT closed too", 0, e.ons(70))
        // replug
        s.midi.connectRouting(e.info, e.info)
        e.play(0x90, 72, 90); assertEquals(72, s.ons.last())
        s.midi.sendNoteOn(0, 70, 100); assertEquals(1, e.ons(70))
        row("unplug keyboard: released=${s.offs} sustain=${s.sustain}; replug works")
    }

    @Test fun unpluggingTheModuleStopsWritesAndReplugResumes() {
        val kb = FakeDev(1, false, true); val m = FakeDev(2, true, false)
        val s = Setup(kb, m)
        s.midi.connectRouting(kb.info, m.info)
        s.midi.outputDeviceRemoved()
        val before = m.written.size
        s.midi.sendNoteOn(0, 60, 100)
        assertEquals("no write to a removed device", before, m.written.size)
        kb.play(0x90, 61, 90); assertEquals("MIDI IN keeps working", listOf(61), s.ons)
        s.midi.connectRouting(kb.info, m.info)
        s.midi.sendNoteOn(0, 62, 100); assertEquals(1, m.ons(62))
    }

    @Test fun anOlderOpenThatFinishesLateIsClosedNotUsed() {
        val kb = FakeDev(1, false, true); val m1 = FakeDev(2, true, false); val m2 = FakeDev(3, true, false)
        val s = Setup(kb, m1, m2)
        s.mgr.deferred = true
        s.midi.connectRouting(kb.info, m1.info)
        s.midi.connectRouting(kb.info, m2.info)
        while (s.mgr.pending.isNotEmpty()) s.mgr.pending.removeAt(0)()   // callbacks of both requests arrive late, in order
        while (s.mgr.pending.isNotEmpty()) s.mgr.pending.removeAt(0)()
        s.midi.sendNoteOn(0, 60, 100)
        assertEquals(0, m1.ons(60)); assertEquals(1, m2.ons(60))
        assertEquals(3, s.midi.connectedOutId)
    }

    // ---- pure rules ----
    private fun ep(id: Int, name: String, sends: Int, recv: Int, t: String = "USB", man: String = "Yamaha", prod: String = name) = MidiEndpoint(id, name, man, prod, t, sends, recv)

    @Test fun resolverRules() {
        val e343 = ep(5, "Digital Keyboard", 1, 1); val other = ep(6, "UM-ONE", 1, 1, man = "Roland"); val app = ep(7, "Synth App", 1, 0, "VIRTUAL")
        val all = listOf(e343, other, app)
        val def = RoutingSettings()
        assertEquals("default IN AUTO = first keyboard", 5, RoutingResolver.midiIn(def, all).endpoint!!.id)
        assertEquals("default OUT AUTO = the MIDI IN device (previous behaviour)", 5, RoutingResolver.midiOut(def, all, e343).endpoint!!.id)
        val chosen = def.copy(midiOut = other.key)
        assertEquals(6, RoutingResolver.midiOut(chosen, all, e343).endpoint!!.id)
        val gone = RoutingResolver.midiOut(chosen.copy(labels = mapOf(other.key to "UM-ONE")), listOf(e343), e343)
        assertNull("a saved device that is missing is never replaced by another one", gone.endpoint)
        assertEquals(RouteStatus.DISCONNECTED, gone.status); assertTrue(gone.text.contains("UM-ONE"))
        assertEquals(RouteStatus.OFF, RoutingResolver.midiOut(def.copy(midiOut = RoutingSettings.OFF), all, e343).status)
        assertNull(RoutingResolver.midiOut(def.copy(midiOut = RoutingSettings.OFF), all, e343).endpoint)
        assertEquals(RouteStatus.UNAVAILABLE, RoutingResolver.midiIn(def, emptyList()).status)
        // identity survives a new Android id after replug
        assertEquals(other.key, other.copy(id = 99).key)
        // audio
        val spk = AudioEndpoint(2, AudioKind.SPEAKER, "Redmi Pad"); val usb = AudioEndpoint(30, AudioKind.USB, "USB DAC")
        assertEquals(0, RoutingResolver.audio(def, listOf(spk, usb)).deviceId)
        assertEquals(30, RoutingResolver.audio(def.copy(audioOut = usb.key), listOf(spk, usb)).deviceId)
        assertEquals(31, RoutingResolver.audio(def.copy(audioOut = usb.key), listOf(spk, usb.copy(id = 31))).deviceId)
        val lost = RoutingResolver.audio(def.copy(audioOut = usb.key), listOf(spk))
        assertEquals("USB DAC unplugged: System Default", 0, lost.deviceId); assertEquals(RouteStatus.DISCONNECTED, lost.status)
        assertEquals(AudioKind.USB, RoutingResolver.audioKind(11)); assertEquals(AudioKind.BLUETOOTH, RoutingResolver.audioKind(8))
        assertEquals(AudioKind.SPEAKER, RoutingResolver.audioKind(2)); assertNull("earpiece not offered", RoutingResolver.audioKind(1)); assertNull("SCO call path not offered", RoutingResolver.audioKind(7))
        // output modes
        assertTrue(OutputMode.INTERNAL.internal && !OutputMode.INTERNAL.midiOut)
        assertTrue(!OutputMode.MIDI_OUT.internal && OutputMode.MIDI_OUT.midiOut)
        assertTrue(OutputMode.BOTH.internal && OutputMode.BOTH.midiOut)
        // E343 profile never assumed
        assertFalse("a Yamaha 'Digital Keyboard' is not assumed to be an E343", RoutingResolver.isE343(e343, def))
        assertTrue(RoutingResolver.isE343(e343, def.copy(e343Devices = setOf(e343.key))))
        assertFalse("confirmation does not leak to another device", RoutingResolver.isE343(other, def.copy(e343Devices = setOf(e343.key))))
        assertTrue(RoutingResolver.isE343(ep(8, "PSR-E343", 1, 1), def))
        assertFalse(RoutingResolver.isE343(null, def.copy(e343Devices = setOf(e343.key))))
        row("resolver: AUTO/OFF/saved/missing/identity/audio fallback/modes/E343 gate OK")
    }

    @Test fun settingsSurviveRestartAndDamagedFilesGiveDefaults() {
        val s = RoutingSettings(midiIn = "usb|yamaha|digital keyboard|digital keyboard", midiOut = RoutingSettings.OFF, audioOut = "USB|usb dac",
            mode = OutputMode.BOTH, e343Devices = setOf("usb|yamaha|digital keyboard|digital keyboard"), labels = mapOf("USB|usb dac" to "USB audio: USB\tDAC"))
        assertEquals(s, RoutingSettings.decode(RoutingSettings.encode(s)))
        assertEquals(RoutingSettings(), RoutingSettings.decode(null))
        assertEquals(RoutingSettings(), RoutingSettings.decode("garbage"))
        val d = RoutingSettings()
        assertEquals("old users: previous behaviour", listOf("AUTO", "AUTO", "DEFAULT", "INTERNAL"), listOf(d.midiIn, d.midiOut, d.audioOut, d.mode.name))
    }

    /** Production chain (RtRig: real parser -> ArrangerBrain -> StyleSequencer -> engine -> router) through connectRouting: one NOTE ON per note, NOTE OFF paired. */
    @Test fun styleThroughRoutedConnectionKeepsPairing() {
        val st = StyleTsv.load() ?: return
        val r = RtRig(true, st.defaultTempoBpm.takeIf { it > 0 } ?: 120, st)
        try {
            r.wireProductionMixerOutput()
            r.midi.connectRouting(MidiDeviceInfo(11, 1, 1), MidiDeviceInfo(11, 1, 1))   // the rig's single fake device, now through DEVICE ROUTING
            r.brain.setAcmpEnabled(true); r.settle(30)
            listOf(48, 52, 55).forEach { r.noteOn(it, 90) }; r.settle(60)
            val mark = r.usbWrites.size
            r.brain.startStop(); r.settle(2500)
            if (r.brain.state.value.isPlaying) r.brain.startStop()
            r.settle(400); listOf(48, 52, 55).forEach { r.noteOff(it) }; r.settle(50)
            val w = r.usbWrites.toList().drop(mark).sortedBy { it.t }
            var ons = 0
            for (ch in 10..15) {
                val open = HashMap<Int, Int>()
                for (x in w.filter { (it.status and 15) == ch }) {
                    val on = (x.status and 0xF0) == 0x90 && x.d2 > 0
                    val off = (x.status and 0xF0) == 0x80 || ((x.status and 0xF0) == 0x90 && x.d2 == 0)
                    if (on) { ons++; open.merge(x.d1, 1, Int::plus) }
                    else if (off) open.computeIfPresent(x.d1) { _, c -> (c - 1).takeIf { it > 0 } }
                    else if ((x.status and 0xF0) == 0xB0 && (x.d1 == 123 || x.d1 == 120)) open.clear()
                }
                assertTrue("ch${ch + 1} stuck $open", open.isEmpty())
            }
            assertTrue("the Style reached MIDI OUT", ons > 0)
            row("production chain through connectRouting: melodic NOTE ON=$ons, all paired, style='${st.fileName}'")
        } finally { r.close() }
    }
}
