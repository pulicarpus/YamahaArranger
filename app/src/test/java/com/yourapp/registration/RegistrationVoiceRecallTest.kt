package com.yourapp.yamahaarranger.registration

import android.content.Context
import android.media.midi.MidiInputPort
import com.yourapp.midi.MidiInputManager
import com.yourapp.midi.MidiMixerOutput
import com.yourapp.yamahaarranger.audio.*
import com.yourapp.yamahaarranger.arranger.ArrangerBrain
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.ui.RecallViewModelHarness
import java.io.File
import java.util.Collections
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/**
 * Registration recall must put the voice shown on screen onto the external keyboard through the same Bank/Program values
 * the normal voice selector sends. Host tests see the MIDI bytes; the actual E343 sound still needs a device check.
 */
class RegistrationVoiceRecallTest {
    private val piano = AudioEngineManager.SfPreset("MELODY", 0, 0, "Piano", 1, "a.sf2")
    private val dist = AudioEngineManager.SfPreset("MELODY", 0, 30, "Distortion Guitar", 1, "a.sf2")
    private val strings = AudioEngineManager.SfPreset("MELODY", 0, 48, "Strings", 1, "a.sf2")
    private val sax = AudioEngineManager.SfPreset("MELODY", 0, 65, "Saxophone", 1, "a.sf2")
    private val choir = AudioEngineManager.SfPreset("MELODY", 1025, 52, "Choir", 1, "a.sf2")
    private val all = listOf(piano, dist, strings, sax, choir)

    inner class Rig {
        val bytes = Collections.synchronizedList(mutableListOf<List<Int>>())
        val midi = MidiInputManager(mock(Context::class.java))
        val bridge = mock(NativeAudioBridge::class.java)
        val engine: AudioEngineManager
        val brain: ArrangerBrain
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vm: RecallViewModelHarness
        init {
            val port = object : MidiInputPort() { override fun send(data: ByteArray, offset: Int, count: Int) { bytes += data.slice(offset until offset + count).map { it.toInt() and 255 } } }
            MidiInputManager::class.java.getDeclaredField("inputPort").apply { isAccessible = true }.set(midi, port)
            val router = MidiInputManager::class.java.getDeclaredField("mixerOutput").apply { isAccessible = true }.get(midi) as MidiMixerOutput
            router.setConnected(true); midi.midiOutEnabled = true
            `when`(bridge.nativeLoadMelodySoundFont(anyString())).thenReturn(true)
            `when`(bridge.nativeLoadDrumSoundFont(anyString())).thenReturn(true)
            `when`(bridge.nativeSetKeyboardReleaseTime(anyInt())).thenReturn(true)
            `when`(bridge.nativeSetKeyboardPreset(anyInt(), anyLong(), anyInt(), anyInt())).thenReturn(true)
            `when`(bridge.nativeGetSoundFontPresets()).thenReturn(all.joinToString("") { "MELODY\u001f1\u001fa.sf2\u001f${it.bank}\u001f${it.program}\u001f${it.name}\u001e" })
            engine = AudioEngineManager(bridge, object : SampleProvider { override fun sampleForNote(midiNote: Int): NoteSample? = null })
            engine.loadSoundFontPair("m", "d"); engine.onMixerControllers = midi::setMixerControllers
            brain = ArrangerBrain(engine, ChordDetector(), midi); vm = RecallViewModelHarness(engine, brain, midi, scope)
        }
        /** The normal selector: SF2 voice applied, then exactly the external mirror MainViewModel.setKeyboardVoice performs. */
        suspend fun select(part: KeyboardPart, p: AudioEngineManager.SfPreset) {
            assertEquals(VoiceResult.APPLIED, vm.applyKeyboardVoice(part, p))
            vm.mirrorKeyboardVoiceToExternal(part.channel, p.bank, p.program, p.name)
        }
        /** Same transformation RegistrationLibraryController.saveSlot applies to the live snapshot. */
        suspend fun save(mode: OutputMode, name: String = "REG"): RegistrationSlot {
            val snap = vm.testSnapshot()
            val parts = snap.parts.map { p -> p.copy(sf2 = if (mode == OutputMode.EXTERNAL) null else p.sf2, external = if (mode == OutputMode.INTERNAL) null else p.external) }
            return RegistrationSlot(name, mode, parts, snap.release, null, null, 0)
        }
        suspend fun recall(key: String, slot: RegistrationSlot, freeze: FreezeFlags = FreezeFlags()) = vm.registrationCoordinator.recall(key, slot, freeze).await()
        fun reset() { bytes.clear(); clearInvocations(bridge) }
        fun close() { vm.registrationCoordinator.cancel(); scope.cancel() }
        fun programs(ch: Int) = bytes.filter { it.size == 2 && it[0] == (0xC0 or ch) }.map { it[1] }
        fun voiceMessages(ch: Int) = bytes.filter { (it[0] and 15) == ch && ((it[0] and 0xF0) == 0xC0 || ((it[0] and 0xF0) == 0xB0 && it[1] in listOf(0, 32))) }
    }

    @Test fun mapping_isTheSelectorsExactBankProgram() {
        fun m(b: Int, p: Int) = Sf2ExternalMapping.voiceFor(b, p, "x")!!.let { Triple(it.msb, it.lsb, it.program) }
        assertEquals(Triple(0, 0, 0), m(0, 0)); assertEquals(Triple(0, 0, 30), m(0, 30)); assertEquals(Triple(104, 0, 5), m(104, 5))
        assertEquals(Triple(8, 1, 52), m(1025, 52)); assertEquals(Triple(127, 127, 127), m(16383, 127)); assertEquals(Triple(1, 0, 0), m(128, 0).let { Triple(it.first, it.second, it.third) })
        assertNull(Sf2ExternalMapping.voiceFor(16384, 0, "x")); assertNull(Sf2ExternalMapping.voiceFor(0, 128, "x")); assertNull(Sf2ExternalMapping.voiceFor(-1, 0, "x"))
        assertEquals("0/0/7", Sf2ExternalMapping.voiceFor(0, 7, "  ")!!.label)
        assertTrue(Sf2ExternalMapping.isDerived(Sf2ExternalMapping.voiceFor(0, 7, "a"))); assertFalse(Sf2ExternalMapping.isDerived(ExternalVoice(0, 0, 7, "a")))
    }

    @Test fun distortionGuitar_save_piano_recall_BOTH_restoresDistortionNotPiano() = runBlocking<Unit> {
        val r = Rig(); try {
            r.select(KeyboardPart.RIGHT1, dist)
            assertEquals("normal selection: Bank MSB, LSB, Program in order, channel 1", listOf(listOf(0xB0, 0, 0), listOf(0xB0, 32, 0), listOf(0xC0, 30)), r.voiceMessages(0).take(3))
            val reg1 = r.save(OutputMode.BOTH)
            r.select(KeyboardPart.RIGHT1, piano); assertEquals(0, r.programs(0).last())
            r.reset()
            val report = r.recall("REG1", reg1)
            assertEquals(RecallCompletion.COMPLETED, report.completion)
            assertEquals(listOf(listOf(0xB0, 0, 0), listOf(0xB0, 32, 0), listOf(0xC0, 30)), r.voiceMessages(0))
            assertEquals("no Piano fallback after the voice", listOf(30), r.programs(0))
            verify(r.bridge, times(1)).nativeSetKeyboardPreset(0, 1L, 0, 30)
            assertEquals("Distortion Guitar", r.vm._rightVoices.value[0].name)
        } finally { r.close() }
    }

    @Test fun legacyBothSlotWithoutExternalStillRestoresTheExternalVoice() = runBlocking<Unit> {
        val r = Rig(); try {
            r.select(KeyboardPart.RIGHT1, piano); r.reset()
            val legacy = r.save(OutputMode.BOTH).let { s -> s.copy(parts = s.parts.mapIndexed { i, p -> if (i == 0) p.copy(sf2 = Sf2VoiceRef("a.sf2", 0, 48, "Strings"), external = null) else p.copy(external = null) }) }
            r.recall("L", legacy)
            assertEquals(listOf(48), r.programs(0)); assertEquals("Strings", r.vm._rightVoices.value[0].name)
        } finally { r.close() }
    }

    @Test fun strings_save_sax_recall_EXTERNAL_sendsStringsOnly() = runBlocking<Unit> {
        val r = Rig(); try {
            r.select(KeyboardPart.RIGHT1, strings); val reg = r.save(OutputMode.EXTERNAL)
            assertNull("EXTERNAL stores no SF2 identity", reg.parts[0].sf2); assertEquals(48, reg.parts[0].external!!.program)
            r.select(KeyboardPart.RIGHT1, sax); r.reset()
            assertEquals(RecallCompletion.COMPLETED, r.recall("S", reg).completion)
            assertEquals(listOf(48), r.programs(0)); verify(r.bridge, never()).nativeSetKeyboardPreset(anyInt(), anyLong(), anyInt(), anyInt())
        } finally { r.close() }
    }

    @Test fun allFourPartsRestoreTheirOwnVoiceOnTheirOwnChannel() = runBlocking<Unit> {
        val r = Rig(); try {
            r.select(KeyboardPart.RIGHT1, dist); r.select(KeyboardPart.RIGHT2, strings); r.select(KeyboardPart.RIGHT3, sax); r.select(KeyboardPart.LEFT, choir)
            val reg = r.save(OutputMode.BOTH)
            for (part in KeyboardPart.values()) r.select(part, piano)
            r.reset(); r.recall("ALL", reg)
            assertEquals(listOf(30), r.programs(0)); assertEquals(listOf(48), r.programs(1)); assertEquals(listOf(65), r.programs(2)); assertEquals(listOf(52), r.programs(3))
            assertEquals(listOf(listOf(0xB3, 0, 8), listOf(0xB3, 32, 1), listOf(0xC3, 52)), r.voiceMessages(3))
            for (ch in 0..3) assertEquals("part $ch: CC0, CC32, PC in this order", listOf(0, 32), r.voiceMessages(ch).filter { it[0] and 0xF0 == 0xB0 }.map { it[1] })
        } finally { r.close() }
    }

    @Test fun repeatedRegOneRegTwoSwitchingNeverLeavesTheWrongVoice() = runBlocking<Unit> {
        val r = Rig(); try {
            r.select(KeyboardPart.RIGHT1, dist); val reg1 = r.save(OutputMode.BOTH, "REG 1")
            r.select(KeyboardPart.RIGHT1, strings); val reg2 = r.save(OutputMode.BOTH, "REG 2")
            repeat(6) { i ->
                val (slot, expected) = if (i % 2 == 0) reg1 to 30 else reg2 to 48
                r.reset(); assertEquals(RecallCompletion.COMPLETED, r.recall("K$i", slot).completion)
                assertEquals("recall #$i", listOf(expected), r.programs(0))
            }
        } finally { r.close() }
    }

    @Test fun restartRoundTripThroughTheCodecRecallsTheSameVoice() = runBlocking<Unit> {
        val r = Rig(); try {
            r.select(KeyboardPart.RIGHT1, dist); val reg = r.save(OutputMode.BOTH)
            val bank = RegistrationCodec.decodeBank(RegistrationCodec.encodeBank(RegistrationBank("b", "Bank", listOf(reg) + List(7) { null }))).value
            val restored = bank.slots[0]!!
            assertEquals(reg.parts[0].external, restored.parts[0].external)
            r.select(KeyboardPart.RIGHT1, piano); r.reset()
            r.recall("R", restored); assertEquals(listOf(30), r.programs(0))
        } finally { r.close() }
    }

    @Test fun freezeVoicesAndMidiOutOffSendNothingAndFreezeOffSends() = runBlocking<Unit> {
        val r = Rig(); try {
            r.select(KeyboardPart.RIGHT1, dist); val reg = r.save(OutputMode.BOTH)
            r.select(KeyboardPart.RIGHT1, piano)
            r.reset(); r.recall("F1", reg, FreezeFlags(voices = true))
            assertTrue("FREEZE VOICES ON: no Bank/Program", r.voiceMessages(0).isEmpty()); verify(r.bridge, never()).nativeSetKeyboardPreset(anyInt(), anyLong(), anyInt(), anyInt())
            delay(200); r.reset(); r.recall("F2", reg, FreezeFlags(voices = false)); assertEquals(listOf(30), r.programs(0))
            r.select(KeyboardPart.RIGHT1, piano); r.midi.midiOutEnabled = false; r.reset()
            val off = r.recall("F3", reg)
            assertTrue("MIDI OUT OFF: nothing queued for the keyboard", r.bytes.isEmpty()); assertEquals(RecallCompletion.PARTIAL, off.completion)
            assertEquals("the internal voice is still restored", "Distortion Guitar", r.vm._rightVoices.value[0].name)
            r.midi.midiOutEnabled = true; assertTrue("turning MIDI OUT back on never replays a voice", r.voiceMessages(0).isEmpty())
        } finally { r.close() }
    }

    @Test fun internalRecallNeverTouchesTheExternalKeyboard() = runBlocking<Unit> {
        val r = Rig(); try {
            r.select(KeyboardPart.RIGHT1, dist); val reg = r.save(OutputMode.INTERNAL)
            r.select(KeyboardPart.RIGHT1, piano); r.reset(); r.recall("I", reg)
            assertTrue(r.voiceMessages(0).isEmpty()); verify(r.bridge, times(1)).nativeSetKeyboardPreset(0, 1L, 0, 30)
            val snap = r.vm.testSnapshot(); assertEquals("a later SAVE sees the voice on screen, not a stale one", 30, snap.parts[0].external!!.program)
        } finally { r.close() }
    }

    @Test fun staleExplicitListEntryCannotOverrideTheSelectedVoice() = runBlocking<Unit> {
        val r = Rig(); try {
            r.vm.selectExternalVoice(KeyboardPart.RIGHT1, ExternalVoice(0, 0, 0, "List Piano"))
            r.select(KeyboardPart.RIGHT1, dist)
            assertEquals(30, r.vm.testSnapshot().parts[0].external!!.program)
        } finally { r.close() }
    }

    @Test fun selectorAndRecallShareOneMappingSourceGuard() {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "app/src/main/java").exists()) dir = dir.parentFile
        val vm = File(dir, "app/src/main/java/com/yourapp/ui/MainViewModel.kt").readText()
        val body = vm.substring(vm.indexOf("fun setKeyboardVoice("), vm.indexOf("fun toggleRightVoice("))
        assertTrue("normal selector goes through the shared external mirror", body.contains("mirrorKeyboardVoiceToExternal(layer, preset.bank, preset.program, preset.name)"))
        assertFalse("no second bank arithmetic in the selector", body.contains("preset.bank / 128"))
        val planner = File(dir, "app/src/main/java/com/yourapp/yamahaarranger/registration/RecallPlanner.kt").readText()
        assertTrue(planner.contains("Sf2ExternalMapping::voiceFor"))
        for (file in listOf(vm, planner)) assertFalse("no Piano default or reset", Regex("sendProgramChange\\([^)]*,\\s*0\\s*,\\s*0\\s*,\\s*0\\s*\\)").containsMatchIn(file))
    }
}
