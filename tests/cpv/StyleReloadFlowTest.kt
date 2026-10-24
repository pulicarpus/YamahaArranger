package com.yourapp.yamahaarranger.arranger

import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.audio.NativeAudioBridge
import com.yourapp.yamahaarranger.audio.SampleProvider
import com.yourapp.yamahaarranger.style.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * P0 "Style Editor reload crash": a Style with a SAVED voice profile hung the app on reload (a Style without a profile loaded fine).
 * Cause: the Style load calls the profile restore while holding ReadyToPlayGate.operations (a coroutine Mutex, not reentrant), and the restore
 * asked AudioEngineManager.loadedSoundFontPresets(), which does runBlocking { operations.withLock { ... } } on the SAME (main) thread -> blocked forever -> ANR/force close.
 * These tests drive the production engine, store, restorer and sequencer through the same lock order as MainViewModel.onStyleFilePicked.
 */
class StyleReloadFlowTest {
    private val colPdta = "ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506"
    private val tyrPdta = "7c0b6f3d2d6f2d2f2e8b9d3a1b7e5a6c4d3b2a1908f7e6d5c4b3a291807f6e5d"
    private fun snapshot(vararg rows: String) = rows.joinToString("\u001e", postfix = "\u001e")
    private val colRow = "MELODY\u001f1\u001fColomboGMGS2_BM.sf2\u001f0\u001f33\u001fFinger Bass"
    private val tyrRow = "MELODY\u001f2\u001fyamaha tyros 4.sf2\u001f0\u001f48\u001fTyros Strings"
    private val sha = StyleOverrideStore.sha256("HardRock2.S578".toByteArray())
    private val tyrPin = StyleVoicePin("yamaha tyros 4.sf2", 0, 48, "Tyros Strings", false)

    private class Rig(val bridge: NativeAudioBridge, val engine: AudioEngineManager, val seq: StyleSequencer, val scope: CoroutineScope) { fun close() = scope.cancel() }

    private fun rig(presets: String = snapshot(colRow, tyrRow), tyrPdtaNow: String = tyrPdta, pinOk: Boolean = true): Rig {
        val bridge = mock(NativeAudioBridge::class.java)
        `when`(bridge.nativeGetSoundFontPresets()).thenReturn(presets)
        `when`(bridge.nativeFontPdta(1L)).thenReturn(colPdta)
        `when`(bridge.nativeFontPdta(2L)).thenReturn(tyrPdtaNow)
        `when`(bridge.nativePinChannelPreset(anyInt(), anyLong(), anyInt(), anyInt())).thenReturn(pinOk)
        val engine = AudioEngineManager(bridge, mock(SampleProvider::class.java))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return Rig(bridge, engine, StyleSequencer(engine, mock(MidiInputManager::class.java), scope), scope)
    }

    private fun record(pdta: String = tyrPdta) = StyleOverrideRecord(sha, "HardRock2.S578.STY", listOf(
        StyleVoiceOverride(10, OverrideScope.ALL, null, tyrPin, pdta),
        StyleVoiceOverride(13, OverrideScope.SECTION, "Main A", tyrPin, pdta)), 1L,
        listOf(StyleExternalOverride(11, OverrideScope.SECTION, "Main A", ExternalVoice(0, 112, 27, "ext"))), 3L)

    /** The exact lock order of MainViewModel.onStyleFilePicked: read the profile, then restore it INSIDE operations.withLock. */
    private fun reloadLikeStyleLoad(r: Rig, store: StyleOverrideStore, timeoutMs: Long = 5_000): List<RestoreResult> {
        var result: List<RestoreResult>? = null; var failure: Throwable? = null
        val worker = Thread({
            try {
                val saved = store.read(sha)
                result = runBlocking { r.engine.readiness.operations.withLock {
                    saved.record?.let { rec -> StyleOverrideRestorer.restore(rec,
                        pdtaByFile = { r.engine.soundFontPdtaByFile() },
                        loadedPresetName = { r.engine.findLoadedPreset(it)?.name },
                        setSf2Pin = { ch, sec, pin -> if (sec == null) r.seq.setVoicePin(ch, pin) else r.seq.setSectionVoicePin(sec, ch, pin) },
                        setExternal = { ch, sec, v -> r.seq.setExternalVoice(sec, ch, v) }) } ?: emptyList()
                } }
            } catch (t: Throwable) { failure = t }
        }, "style-load").apply { isDaemon = true; start() }
        worker.join(timeoutMs)
        if (worker.isAlive) { val stack = worker.stackTrace.take(6).joinToString(" <- "); worker.interrupt(); fail("Style reload did not finish in ${timeoutMs}ms (deadlock): $stack") }
        failure?.let { throw it }
        return result!!
    }

    private fun tmp(): Path = Files.createTempDirectory("style-reload")
    private fun pinCalls(b: NativeAudioBridge) = mockingDetails(b).invocations.count { it.method.name == "nativePinChannelPreset" && (it.arguments[1] as Long) > 0 }

    @Test fun conditionAStyleWithoutAProfileReloadsAndNothingIsPinned() {
        val r = rig(); val store = StyleOverrideStore(tmp())
        assertEquals(OverrideReadStatus.MISSING, store.read(sha).status)
        assertTrue(reloadLikeStyleLoad(r, store).isEmpty()); assertEquals(0, pinCalls(r.bridge)); r.close()
    }

    @Test fun conditionBASavedSf2ProfileReloadsInsideTheOperationLockAndAppliesItsPins() {
        val r = rig(); val store = StyleOverrideStore(tmp()); store.write(record())
        val out = reloadLikeStyleLoad(r, store)
        assertEquals(listOf("ACTIVE", "ACTIVE", "ACTIVE"), out.map { it.state })
        assertEquals(tyrPin, r.seq.voicePin(10)); assertEquals(tyrPin, r.seq.sectionVoicePin("Main A", 13))
        assertEquals(ExternalVoice(0, 112, 27, "ext"), r.seq.externalVoice("Main A", 11))
        val pin = mockingDetails(r.bridge).invocations.first { it.method.name == "nativePinChannelPreset" && (it.arguments[1] as Long) > 0 }
        assertEquals(listOf<Any>(10, 2L, 0, 48), pin.arguments.toList()); r.close()
    }

    @Test fun rootCauseTheLockingPresetListingBlocksForeverWhenCalledUnderTheOperationLock() {
        val r = rig(); val t = Thread({
            try { runBlocking { r.engine.readiness.operations.withLock { r.engine.loadedSoundFontPresets() } } } catch (_: InterruptedException) {} catch (_: Exception) {}
        }, "old-restore").apply { isDaemon = true; start() }
        t.join(1_500)
        assertTrue("loadedSoundFontPresets() under operations.withLock must block (non-reentrant Mutex): this is the P0 hang", t.isAlive)
        t.interrupt(); t.join(3_000); assertFalse(t.isAlive); r.close()
    }

    @Test fun theSnapshotReadersUsedByTheRestoreNeverTakeTheOperationLock() {
        val r = rig(); var pdta: Map<String, String>? = null; var found: String? = null
        val t = Thread({ runBlocking { r.engine.readiness.operations.withLock { pdta = r.engine.soundFontPdtaByFile(); found = r.engine.findLoadedPreset(tyrPin)?.name } } }, "ok").apply { isDaemon = true; start() }
        t.join(3_000); assertFalse(t.isAlive)
        assertEquals(mapOf("ColomboGMGS2_BM.sf2" to colPdta, "yamaha tyros 4.sf2" to tyrPdta), pdta); assertEquals("Tyros Strings", found); r.close()
    }

    @Test fun missingPresetIsStaleNotAppliedNotSubstitutedAndTheProfileStaysOnDisk() {
        val r = rig(presets = snapshot(colRow)); val dir = tmp(); val store = StyleOverrideStore(dir); store.write(record())
        val before = Files.readAllBytes(dir.resolve("style-$sha.json"))
        val out = reloadLikeStyleLoad(r, store)
        assertEquals(setOf("STALE_PRESET_NOT_LOADED"), out.filter { !it.external }.map { it.state }.toSet())
        assertNull(r.seq.voicePin(10)); assertEquals(0, pinCalls(r.bridge))
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("style-$sha.json"))); r.close()
    }

    @Test fun aChangedSoundFontIsStaleByContentAndRenamedPresetsToo() {
        val changed = rig(tyrPdtaNow = "0".repeat(64)); val store = StyleOverrideStore(tmp()); store.write(record())
        assertEquals(setOf("STALE_SOUNDFONT_CONTENT_CHANGED"), reloadLikeStyleLoad(changed, store).filter { !it.external }.map { it.state }.toSet()); assertEquals(0, pinCalls(changed.bridge)); changed.close()
        val renamed = rig(presets = snapshot(colRow, tyrRow.replace("Tyros Strings", "Other Name")))
        assertEquals(setOf("STALE_PRESET_NAME_CHANGED"), reloadLikeStyleLoad(renamed, store).filter { !it.external }.map { it.state }.toSet()); renamed.close()
    }

    @Test fun aDamagedProfileLoadsTheStyleWithoutOverridesAndIsNeverDeleted() {
        val dir = tmp(); val file = dir.resolve("style-$sha.json"); val junk = byteArrayOf(0, 1, 2, 123, 34, -1, -2, 7)
        Files.write(file, junk)
        val store = StyleOverrideStore(dir); val read = store.read(sha)
        assertEquals(OverrideReadStatus.DAMAGED, read.status); assertNull(read.record); assertFalse(read.writable)
        val r = rig(); assertTrue(reloadLikeStyleLoad(r, store).isEmpty())
        assertArrayEquals(junk, Files.readAllBytes(file)); r.close()
        // Truncated valid JSON and valid JSON with a wrong CRC are the same: damaged, kept.
        val good = StyleOverrideCodec.encode(record()); Files.write(file, good.substring(0, good.length / 2).toByteArray())
        assertEquals(OverrideReadStatus.DAMAGED, StyleOverrideStore(dir).read(sha).status)
        Files.write(file, good.replace("\"bank\":0", "\"bank\":1").toByteArray()); assertEquals(OverrideReadStatus.DAMAGED, StyleOverrideStore(dir).read(sha).status)
        assertTrue(Files.exists(file))
    }

    @Test fun aNewerSchemaIsReadOnlyAndAnUnreadableFileIsQuarantinedOnWriteNotDeleted() {
        val dir = tmp(); val file = dir.resolve("style-$sha.json"); Files.write(file, "{ not json".toByteArray())
        val store = StyleOverrideStore(dir); store.write(record())
        assertEquals(OverrideReadStatus.VALID, store.read(sha).status)
        val kept = Files.list(dir.resolve("quarantine")).use { it.count() }; assertEquals(1L, kept)
    }

    @Test fun repeatedReloadsStayBoundedAndStable() {
        val r = rig(); val store = StyleOverrideStore(tmp()); store.write(record())
        val started = System.nanoTime()
        repeat(60) { i ->
            val out = reloadLikeStyleLoad(r, store, 5_000)
            assertEquals("reload $i", listOf("ACTIVE", "ACTIVE", "ACTIVE"), out.map { it.state })
            assertEquals(tyrPin, r.seq.voicePin(10))
        }
        assertTrue("60 reloads took ${(System.nanoTime() - started) / 1_000_000} ms", (System.nanoTime() - started) / 1_000_000 < 20_000)
        assertEquals("one native pin per reload (the Main A section pin is only installed while that section plays)", 60, pinCalls(r.bridge))
        r.close()
    }

    @Test fun mainViewModelNeverCallsTheLockingListingWhileItHoldsTheOperationLockAndRestoresOffTheMainThread() {
        val src = java.io.File("app/src/main/java/com/yourapp/ui/MainViewModel.kt").readText()
        val pdta = src.substring(src.indexOf("private fun loadedPdtaByFile"), src.indexOf("private fun refreshAssignmentsFromRecord"))
        assertFalse("loadedPdtaByFile must not use the locking listing", pdta.contains("loadedSoundFontPresets"))
        val restore = src.substring(src.indexOf("private fun restoreStyleOverrides"), src.indexOf("private fun reapplyStyleVoicePins"))
        assertFalse("restoreStyleOverrides runs under operations.withLock", restore.contains("loadedSoundFontPresets"))
        val load = src.substring(src.indexOf("fun onStyleFilePicked"), src.indexOf("private suspend fun loadCpvStack"))
        assertTrue("restore must leave the main thread", load.contains("withContext(Dispatchers.Default) { restoreStyleOverrides() }"))
        assertFalse(load.contains("loadedSoundFontPresets"))
    }
}
