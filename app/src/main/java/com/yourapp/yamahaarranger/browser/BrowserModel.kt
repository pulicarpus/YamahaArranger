package com.yourapp.yamahaarranger.browser

import com.yourapp.yamahaarranger.registration.RegistrationCodec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Pure model of the Style/Voice browsers: a fixed 4x4 page grid, ordered folders with items,
 * Previous/Next that crosses folders, and the remembered position. No Android types.
 */
object BrowserGrid {
    const val COLS = 4
    const val ROWS = 4
    const val PAGE_SIZE = COLS * ROWS
    const val PAGE_BUTTONS = 6

    fun pageCount(items: Int): Int = maxOf(1, (items + PAGE_SIZE - 1) / PAGE_SIZE)

    /** Always exactly [PAGE_SIZE] slots; missing items are null so cell size never depends on the item count. */
    fun <T> slots(items: List<T>, page: Int): List<T?> {
        val safe = page.coerceIn(0, pageCount(items.size) - 1)
        val start = safe * PAGE_SIZE
        return List(PAGE_SIZE) { items.getOrNull(start + it) }
    }

    fun pageOf(index: Int): Int = if (index < 0) 0 else index / PAGE_SIZE

    /** First page shown on the six P buttons (P1-P6, then P7-P12, ...). */
    fun groupStart(page: Int): Int = page.coerceAtLeast(0) / PAGE_BUTTONS * PAGE_BUTTONS

    fun groupCount(pageCount: Int): Int = (pageCount + PAGE_BUTTONS - 1) / PAGE_BUTTONS
}

data class BrowserItem(val key: String, val title: String, val subtitle: String = "")
data class BrowserFolder(val key: String, val name: String, val items: List<BrowserItem>)

/** A stable position: folder key plus item key. Keys are URIs (styles) or SF2 file + bank + program (voices). */
data class BrowserPosition(val folderKey: String, val itemKey: String)

enum class RestoreStatus { RESTORED, ITEM_MISSING, FOLDER_MISSING, NOTHING_SAVED }

data class RestoredBrowser(val folderKey: String?, val itemKey: String?, val page: Int, val status: RestoreStatus)

object BrowserNav {
    private fun nonEmpty(folders: List<BrowserFolder>) = folders.filter { it.items.isNotEmpty() }

    fun first(folders: List<BrowserFolder>): BrowserPosition? =
        nonEmpty(folders).firstOrNull()?.let { BrowserPosition(it.key, it.items.first().key) }

    fun indexOf(folder: BrowserFolder?, itemKey: String?): Int = folder?.items?.indexOfFirst { it.key == itemKey } ?: -1

    /** Next item; at the end of a folder it enters the next non-empty folder. Null at the very end (no wrap-around). */
    fun next(folders: List<BrowserFolder>, from: BrowserPosition?): BrowserPosition? {
        val list = nonEmpty(folders)
        if (list.isEmpty()) return null
        if (from == null) return first(folders)
        val fi = list.indexOfFirst { it.key == from.folderKey }
        if (fi < 0) return first(folders)
        val ii = list[fi].items.indexOfFirst { it.key == from.itemKey }
        if (ii < 0) return BrowserPosition(list[fi].key, list[fi].items.first().key)
        if (ii + 1 < list[fi].items.size) return BrowserPosition(list[fi].key, list[fi].items[ii + 1].key)
        val nf = list.getOrNull(fi + 1) ?: return null
        return BrowserPosition(nf.key, nf.items.first().key)
    }

    /** Previous item; at the start of a folder it enters the last item of the previous non-empty folder. Null at the very start. */
    fun previous(folders: List<BrowserFolder>, from: BrowserPosition?): BrowserPosition? {
        val list = nonEmpty(folders)
        if (list.isEmpty()) return null
        if (from == null) return first(folders)
        val fi = list.indexOfFirst { it.key == from.folderKey }
        if (fi < 0) return first(folders)
        val ii = list[fi].items.indexOfFirst { it.key == from.itemKey }
        if (ii < 0) return BrowserPosition(list[fi].key, list[fi].items.first().key)
        if (ii > 0) return BrowserPosition(list[fi].key, list[fi].items[ii - 1].key)
        val pf = list.getOrNull(fi - 1) ?: return null
        return BrowserPosition(pf.key, pf.items.last().key)
    }

    /** Resolve a remembered position against what exists now; never invents a folder or item. */
    fun restore(folders: List<BrowserFolder>, saved: SavedPosition?): RestoredBrowser {
        if (saved == null) return RestoredBrowser(null, null, 0, RestoreStatus.NOTHING_SAVED)
        val folder = folders.firstOrNull { it.key == saved.folderKey }
            ?: return RestoredBrowser(null, null, 0, RestoreStatus.FOLDER_MISSING)
        val idx = folder.items.indexOfFirst { it.key == saved.itemKey }
        return if (idx >= 0) RestoredBrowser(folder.key, saved.itemKey, BrowserGrid.pageOf(idx), RestoreStatus.RESTORED)
        else RestoredBrowser(folder.key, null, saved.page.coerceIn(0, BrowserGrid.pageCount(folder.items.size) - 1), RestoreStatus.ITEM_MISSING)
    }
}

/** What is remembered for one browser context. [category]/[search] only apply to voice contexts. */
data class SavedPosition(val folderKey: String, val itemKey: String, val page: Int = 0, val category: String? = null, val search: String = "")

/** Contexts are independent: RIGHT1, RIGHT2, RIGHT3, LEFT, STYLE_EDITOR (voices) and STYLE. */
data class BrowserMemory(val style: SavedPosition? = null, val voices: Map<String, SavedPosition> = emptyMap()) {
    fun withStyle(p: SavedPosition?) = copy(style = p)
    fun withVoice(context: String, p: SavedPosition?) = copy(voices = if (p == null) voices - context else voices + (context to p))
}

object BrowserMemoryCodec {
    const val SCHEMA = 1L
    private val json = RegistrationCodec.Json

    private fun pos(p: SavedPosition) = mapOf("folder" to p.folderKey, "item" to p.itemKey, "page" to p.page,
        "category" to p.category, "search" to p.search)

    fun encode(m: BrowserMemory): String = json.write(mapOf("schema" to SCHEMA, "style" to m.style?.let(::pos),
        "voices" to m.voices.toSortedMap().mapValues { pos(it.value) }))

    @Suppress("UNCHECKED_CAST")
    private fun decodePos(o: Any?): SavedPosition? {
        val m = o as? Map<String, Any?> ?: return null
        val folder = m["folder"] as? String ?: return null
        val item = m["item"] as? String ?: return null
        return SavedPosition(folder, item, ((m["page"] as? Long) ?: 0L).toInt().coerceAtLeast(0), m["category"] as? String, m["search"] as? String ?: "")
    }

    /** Anything unreadable or from a newer schema yields an empty memory; the browser then opens at its normal start. */
    @Suppress("UNCHECKED_CAST")
    fun decode(text: String): BrowserMemory = try {
        val root = json.parse(text) as? Map<String, Any?>
        if (root == null || (root["schema"] as? Long) != SCHEMA) BrowserMemory()
        else BrowserMemory(decodePos(root["style"]),
            (root["voices"] as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> decodePos(v)?.let { k to it } }.toMap())
    } catch (_: Exception) { BrowserMemory() }
}

/** One small JSON file, written atomically. Callers run it off the main thread. */
class BrowserMemoryStore(private val file: Path) {
    private val lock = Any()
    fun read(): BrowserMemory = synchronized(lock) {
        try { if (Files.exists(file) && Files.size(file) <= 200_000) BrowserMemoryCodec.decode(String(Files.readAllBytes(file), Charsets.UTF_8)) else BrowserMemory() }
        catch (_: Exception) { BrowserMemory() }
    }
    fun write(m: BrowserMemory) = synchronized(lock) {
        Files.createDirectories(file.toAbsolutePath().parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.write(tmp, BrowserMemoryCodec.encode(m).toByteArray(Charsets.UTF_8))
        Files.move(tmp, file, ATOMIC_MOVE, REPLACE_EXISTING)
    }
}

/** Builders that turn app data into browser folders without touching identities. */
object BrowserSources {
    /** Voice folder = one SoundFont file; item key = file + bank + program (never the runtime font id). */
    fun voiceItemKey(soundFontName: String, bank: Int, program: Int) = "$soundFontName|$bank|$program"

    data class VoiceLike(val soundFontName: String, val bank: Int, val program: Int, val name: String)

    fun voiceFolders(presets: List<VoiceLike>, keep: (VoiceLike) -> Boolean = { true }): List<BrowserFolder> =
        presets.groupBy { it.soundFontName }.toSortedMap(compareBy { it.lowercase() }).map { (file, list) ->
            BrowserFolder(file, file, list.filter(keep).sortedWith(compareBy({ it.bank }, { it.program })).map {
                BrowserItem(voiceItemKey(it.soundFontName, it.bank, it.program), it.name.trim(), "${it.bank}:${it.program} · PC${it.program + 1}")
            })
        }

    /** Style folder = top-level folder; items = the style files whose path lies under it (same rule the LCD always used). */
    fun styleFolders(folders: List<Pair<String, String>>, files: List<Pair<String, String>>, pathOf: (String) -> String?): List<BrowserFolder> =
        folders.map { (uri, name) ->
            val base = pathOf(uri)?.trimEnd('/')
            BrowserFolder(uri, name, if (base == null) emptyList() else files.filter { (u, _) ->
                pathOf(u)?.trimEnd('/')?.startsWith("$base/") == true
            }.map { (u, n) -> BrowserItem(u, n.substringBeforeLast("."), "") })
        }
}
