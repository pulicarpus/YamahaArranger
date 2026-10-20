package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.browser.BrowserFolder
import com.yourapp.yamahaarranger.browser.BrowserGrid
import com.yourapp.yamahaarranger.browser.BrowserItem
import com.yourapp.yamahaarranger.browser.BrowserMemory
import com.yourapp.yamahaarranger.browser.BrowserMemoryCodec
import com.yourapp.yamahaarranger.browser.BrowserMemoryStore
import com.yourapp.yamahaarranger.browser.BrowserNav
import com.yourapp.yamahaarranger.browser.BrowserPosition
import com.yourapp.yamahaarranger.browser.BrowserSources
import com.yourapp.yamahaarranger.browser.RestoreStatus
import com.yourapp.yamahaarranger.browser.SavedPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BrowserModelTest {
    private fun folder(key: String, n: Int, prefix: String = key) = BrowserFolder(key, key, List(n) { BrowserItem("$key/$prefix$it", "$prefix$it") })

    @Test fun gridAlwaysHasSixteenFixedSlotsWhateverTheItemCount() {
        for (n in listOf(0, 1, 4, 15, 16, 17, 66)) {
            val items = List(n) { "s$it" }
            for (p in 0 until BrowserGrid.pageCount(n)) assertEquals("n=$n page=$p", 16, BrowserGrid.slots(items, p).size)
        }
        val one = BrowserGrid.slots(listOf("only"), 0)
        assertEquals("only", one[0]); assertTrue(one.drop(1).all { it == null })      // 1 filled, 15 empty
        val four = BrowserGrid.slots(List(4) { "s$it" }, 0)
        assertEquals(4, four.count { it != null }); assertEquals(12, four.count { it == null })
        assertEquals(16, BrowserGrid.slots(List(17) { it }, 0).count { it != null })   // 17: 16 on P1 ...
        assertEquals(1, BrowserGrid.slots(List(17) { it }, 1).count { it != null })    // ... and 1 on P2
        assertEquals(listOf(5, 1, 4, 2, 1, 2).size, 6)
        assertEquals(5, BrowserGrid.pageCount(66))
        assertEquals(1, BrowserGrid.pageCount(0)); assertEquals(1, BrowserGrid.pageCount(16))
    }

    @Test fun pageButtonGroupsBeyondSixPages() {
        assertEquals(0, BrowserGrid.groupStart(0)); assertEquals(0, BrowserGrid.groupStart(5))
        assertEquals(6, BrowserGrid.groupStart(6)); assertEquals(12, BrowserGrid.groupStart(13))
        assertEquals(1, BrowserGrid.groupCount(6)); assertEquals(2, BrowserGrid.groupCount(7)); assertEquals(3, BrowserGrid.groupCount(14))
        assertEquals(56, BrowserGrid.pageCount(892))   // Colombo-sized folder
    }

    @Test fun nextAndPreviousCrossFoldersAndSkipEmptyOnes() {
        val pop = folder("Pop", 2); val empty = BrowserFolder("Empty", "Empty", emptyList()); val dut = folder("Dangdut", 2)
        val all = listOf(pop, empty, dut)
        val b = BrowserPosition("Pop", "Pop/Pop1")
        assertEquals(BrowserPosition("Dangdut", "Dangdut/Dangdut0"), BrowserNav.next(all, b))       // Style B -> Style C in the next folder
        assertEquals(b, BrowserNav.previous(all, BrowserPosition("Dangdut", "Dangdut/Dangdut0")))    // and back
        assertEquals(BrowserPosition("Pop", "Pop/Pop1"), BrowserNav.next(all, BrowserPosition("Pop", "Pop/Pop0")))
        // No wrap-around at either end.
        assertNull(BrowserNav.next(all, BrowserPosition("Dangdut", "Dangdut/Dangdut1")))
        assertNull(BrowserNav.previous(all, BrowserPosition("Pop", "Pop/Pop0")))
        // Nothing highlighted yet: both start at the first item. Nothing at all: null.
        assertEquals(BrowserPosition("Pop", "Pop/Pop0"), BrowserNav.next(all, null))
        assertNull(BrowserNav.next(listOf(empty), null))
    }

    @Test fun walkingTheWholeCollectionVisitsEveryItemOnceInOrder() {
        val all = listOf(folder("A", 17), folder("B", 0), folder("C", 3), folder("D", 66))
        var p = BrowserNav.first(all); val seen = ArrayList<String>()
        while (p != null) { seen += p.itemKey; p = BrowserNav.next(all, p) }
        assertEquals(all.flatMap { f -> f.items.map { it.key } }, seen)
        var q = BrowserPosition("D", "D/D65"); val back = ArrayList<String>()
        while (true) { back += q.itemKey; q = BrowserNav.previous(all, q) ?: break }
        assertEquals(seen.reversed(), back)
        // Crossing a page boundary inside a folder: item 16 lives on P2.
        assertEquals(1, BrowserGrid.pageOf(BrowserNav.indexOf(all[0], "A/A16")))
    }

    @Test fun sameFileNameInDifferentFoldersStaysDistinct() {
        val a = BrowserFolder("file:///Styles/Pop", "Pop", listOf(BrowserItem("file:///Styles/Pop/Love Song.STY", "Love Song")))
        val b = BrowserFolder("file:///Styles/Dangdut", "Dangdut", listOf(BrowserItem("file:///Styles/Dangdut/Love Song.STY", "Love Song")))
        val r = BrowserNav.restore(listOf(a, b), SavedPosition(b.key, "file:///Styles/Dangdut/Love Song.STY"))
        assertEquals(b.key, r.folderKey); assertEquals(RestoreStatus.RESTORED, r.status)
        assertEquals(BrowserPosition(b.key, b.items[0].key), BrowserNav.next(listOf(a, b), BrowserPosition(a.key, a.items[0].key)))
    }

    @Test fun restoreFindsFolderItemAndPageAndNeverInventsOne() {
        val big = folder("Dangdut", 40)
        val r = BrowserNav.restore(listOf(folder("Pop", 3), big), SavedPosition("Dangdut", "Dangdut/Dangdut33", page = 2))
        assertEquals(RestoreStatus.RESTORED, r.status); assertEquals(2, r.page); assertEquals("Dangdut/Dangdut33", r.itemKey)
        val gone = BrowserNav.restore(listOf(folder("Pop", 3), big), SavedPosition("Dangdut", "Dangdut/Deleted", page = 9))
        assertEquals(RestoreStatus.ITEM_MISSING, gone.status); assertEquals("Dangdut", gone.folderKey); assertNull(gone.itemKey); assertEquals(2, gone.page)
        val noFolder = BrowserNav.restore(listOf(folder("Pop", 3)), SavedPosition("Dangdut", "x"))
        assertEquals(RestoreStatus.FOLDER_MISSING, noFolder.status); assertNull(noFolder.folderKey)
        assertEquals(RestoreStatus.NOTHING_SAVED, BrowserNav.restore(emptyList(), null).status)
    }

    @Test fun memorySurvivesRestartAndContextsAreIndependent() {
        val dir = Files.createTempDirectory("bm"); val file = dir.resolve("browser_memory.json")
        val store = BrowserMemoryStore(file)
        var m = BrowserMemory().withStyle(SavedPosition("file:///Styles/Dangdut", "file:///Styles/Dangdut/Love Song.STY", 2))
        val k = BrowserSources.voiceItemKey("MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2", 0, 52)
        m = m.withVoice("RIGHT1", SavedPosition("MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2", k, 3, "SAX/WOODWIND", "sul"))
        store.write(m)
        val again = BrowserMemoryStore(file).read()                                     // new process
        assertEquals(m, again)
        // Choosing RIGHT2 leaves RIGHT1 untouched.
        val r2 = again.withVoice("RIGHT2", SavedPosition("ColomboGMGS2_BM.sf2", "ColomboGMGS2_BM.sf2|0|33", 0))
        assertEquals(again.voices.getValue("RIGHT1"), r2.voices.getValue("RIGHT1"))
        assertEquals(setOf("RIGHT1", "RIGHT2"), r2.voices.keys)
        assertEquals(m.style, r2.style)
        assertNull(r2.withVoice("RIGHT2", null).voices["RIGHT2"])
    }

    @Test fun damagedOrNewerMemoryIsIgnoredNotFatal() {
        val dir = Files.createTempDirectory("bm"); val file = dir.resolve("m.json")
        Files.write(file, "{broken".toByteArray()); assertEquals(BrowserMemory(), BrowserMemoryStore(file).read())
        Files.write(file, "{\"schema\":9,\"style\":null,\"voices\":{}}".toByteArray()); assertEquals(BrowserMemory(), BrowserMemoryStore(file).read())
        assertEquals(BrowserMemory(), BrowserMemoryStore(dir.resolve("absent.json")).read())
        assertEquals(BrowserMemory(), BrowserMemoryCodec.decode("{\"schema\":1,\"style\":{\"folder\":1},\"voices\":{\"R\":{}}}"))
    }

    @Test fun voiceFoldersAreSoundFontsAndIdentityIsFileBankProgram() {
        fun v(f: String, b: Int, p: Int, n: String) = BrowserSources.VoiceLike(f, b, p, n)
        val presets = listOf(v("Tyros.sf2", 0, 33, "Fingered"), v("Colombo.sf2", 0, 33, "Finger Bass"), v("Colombo.sf2", 0, 5, "EP"),
            v("MELODI PRIME.sf2", 0, 32, "elctrick bass"), v("MELODI PRIME.sf2", 0, 33, "BASS PSR"))
        val folders = BrowserSources.voiceFolders(presets)
        assertEquals(listOf("Colombo.sf2", "MELODI PRIME.sf2", "Tyros.sf2"), folders.map { it.name })
        assertEquals(listOf("EP", "Finger Bass"), folders[0].items.map { it.title })      // sorted by bank then program
        // Same bank/program in two SoundFonts never collide.
        val keys = folders.flatMap { f -> f.items.map { it.key } }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue("Colombo.sf2|0|33" in keys && "Tyros.sf2|0|33" in keys)
        // Filtering can empty a folder; navigation skips it.
        val filtered = BrowserSources.voiceFolders(presets) { it.soundFontName != "Colombo.sf2" }
        assertTrue(filtered[0].items.isEmpty())
        assertEquals(BrowserPosition("MELODI PRIME.sf2", "MELODI PRIME.sf2|0|32"), BrowserNav.first(filtered))
    }

    @Test fun styleFoldersUseTheSamePathRuleAsTheOldLcd() {
        val folders = listOf("f:/S/A" to "A", "f:/S/B" to "B", "f:/S/C" to "C")
        val files = listOf("f:/S/A/x.STY" to "x.STY", "f:/S/A/sub/y.STY" to "y.STY", "f:/S/B/z.STY" to "z.STY", "f:/S/AB/no.STY" to "no.STY")
        val built = BrowserSources.styleFolders(folders, files) { it.removePrefix("f:") }
        assertEquals(listOf(2, 1, 0), built.map { it.items.size })           // "AB" is not inside "A"
        assertEquals(listOf("x", "y"), built[0].items.map { it.title })
        assertFalse(built[2].items.isNotEmpty())
    }
}
