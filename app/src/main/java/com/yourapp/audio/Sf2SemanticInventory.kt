package com.yourapp.audio

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.PriorityQueue
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Diagnostic file reader only. No synth, JNI, MIDI, cache activation or arranger dependency. */
object Sf2SemanticInventory {
    const val COMPACT_BYTES = 48 * 1024
    private const val TABLE_BYTES = 32 * 1024 * 1024
    private const val MAX_RELATIONS = 200000
    enum class Classification { EXACT, COMPATIBLE, APPROXIMATION, INCOMPATIBLE, UNKNOWN }
    data class Source(val identity: String, val name: String, val open: () -> InputStream?)
    data class Discovery(val sources: List<Source>, val errors: List<String> = emptyList())
    data class Summary(val files: Int, val valid: Int, val failed: Int, val compactBytes: Int)
    data class Candidate(val target: Int, val priority: Int, val relation: String, val row: String)
    data class Scan(
        val name: String, val sha256: String?, val bytes: Long, val complete: Boolean,
        val reason: String, val presets: Int, val instruments: Int, val samples: Int,
        val relations: Int, val candidateCounts: Map<Int, Int>, val candidates: List<Candidate>
    )
    private val targets = linkedMapOf(21 to "Hi-Hat Pedal Closed PD", 16 to "Hi-Hat Edge 10 PD", 31 to "Snare 4 PD")
    private val widths = mapOf("phdr" to 38, "pbag" to 4, "pmod" to 10, "pgen" to 4,
        "inst" to 22, "ibag" to 4, "imod" to 10, "igen" to 4, "shdr" to 46)

    /** Every managed file, including subfolders. No mkdir/cache copy and no silent directory skip. */
    fun discover(root: File): Discovery {
        val sources = mutableListOf<Source>(); val errors = mutableListOf<String>()
        if (!root.isDirectory) return Discovery(emptyList(), listOf("managed_folder_unavailable"))
        val base = root.canonicalFile
        val pending = ArrayDeque<File>(); pending.add(base)
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val dir = pending.removeFirst()
            val canonical = try { dir.canonicalFile } catch (e: Exception) {
                errors += "directory_identity_unavailable:${dir.name}"; continue
            }
            if (canonical != base && !canonical.path.startsWith(base.path + File.separator)) {
                errors += "outside_managed_folder:${dir.name}"; continue
            }
            if (!visited.add(canonical.path)) continue
            val children = try { canonical.listFiles() } catch (_: SecurityException) { null }
            if (children == null) { errors += "directory_unreadable:${canonical.name}"; continue }
            for (child in children.sortedBy { it.name }) {
                if (child.isDirectory) pending.add(child)
                else if (child.isFile && child.extension.equals("sf2", true)) {
                    val file = child.canonicalFile
                    if (!file.path.startsWith(base.path + File.separator)) {
                        errors += "outside_managed_folder:${child.name}"; continue
                    }
                    sources += Source(file.toURI().toString(), file.relativeTo(base).path) { file.inputStream() }
                }
            }
        }
        return Discovery(sources.distinctBy { it.identity }.sortedBy { it.name }, errors)
    }

    /** SHA256 covers the entire original file. Only bounded pdta tables are retained, never PCM. */
    fun scan(input: InputStream, name: String, fullLine: (String) -> Unit = {}): Scan {
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = DigestInputStream(BufferedInputStream(input, 64 * 1024), digest)
        val reader = Reader(stream)
        var tables = emptyMap<String, ByteArray>(); var relations = 0
        var complete = false; var reason = "unknown"; var hash: String? = null
        val counts = targets.keys.associateWith { 0 }.toMutableMap()
        val order = compareBy<Candidate> { it.priority }.thenBy { it.relation }
        val pools = targets.keys.associateWith { PriorityQueue(order.reversed()) }
        try {
            tables = reader.tables()
            validate(tables)
            val ph = tables.getValue("phdr"); val inst = tables.getValue("inst"); val sh = tables.getValue("shdr")
            fullLine("TABLES presets=${ph.size / 38 - 1} instruments=${inst.size / 22 - 1} samples=${sh.size / 46 - 1}")
            for (s in 0 until sh.size / 46 - 1) fullLine(sampleRow(sh, s))
            // Raw instrument bags preserve unused/unreferenced instruments as well as globals.
            val instruments = (0 until inst.size / 22 - 1).map { i ->
                val first = u16(inst, i * 22 + 20); val last = u16(inst, (i + 1) * 22 + 20)
                fullLine("I id=$i name='${label(text(inst, i * 22, 20))}' bags=$first:$last")
                var global = Gen()
                val local = mutableListOf<Pair<Int, Gen>>()
                for (bag in first until last) {
                    val g = bag(tables, "i", bag)
                    fullLine("IB instrument=$i bag=$bag ${g.dump()}")
                    if (g.values[53] == null) { require(bag == first) { "nonleading_instrument_global" }; global = g }
                    else local += bag to g.inherit(global)
                }
                local
            }
            for (p in 0 until ph.size / 38 - 1) {
                val bank = u16(ph, p * 38 + 22); val pc = u16(ph, p * 38 + 20)
                require(pc in 0..127) { "invalid_raw_pc" }
                val preset = text(ph, p * 38, 20)
                val first = u16(ph, p * 38 + 24); val last = u16(ph, (p + 1) * 38 + 24)
                fullLine("P id=$p bank=$bank rawPC=$pc displayPC=${pc + 1} name='${label(preset)}' bags=$first:$last")
                var global = Gen()
                for (pb in first until last) {
                    val raw = bag(tables, "p", pb)
                    fullLine("PB preset=$p bag=$pb ${raw.dump()}")
                    val instrument = raw.values[41]
                    if (instrument == null) { require(pb == first) { "nonleading_preset_global" }; global = raw; continue }
                    val pg = raw.inherit(global)
                    for ((ib, ig) in instruments[instrument]) {
                        require(++relations <= MAX_RELATIONS) { "relation_limit" }
                        val sample = ig.values.getValue(53)
                        val kl = maxOf(pg.low(43), ig.low(43)); val kh = minOf(pg.high(43), ig.high(43))
                        val vl = maxOf(pg.low(44), ig.low(44)); val vh = minOf(pg.high(44), ig.high(44))
                        val instrumentName = text(inst, instrument * 22, 20); val sampleName = text(sh, sample * 46, 20)
                        val id = "$p:$pb:$instrument:$ib:$sample"
                        val eligible = kl <= kh && vl <= vh
                        val row = "Z relation=$id bank=$bank rawPC=$pc displayPC=${pc + 1} preset='${label(preset)}' " +
                            "instrument=$instrument:'${label(instrumentName)}' sample=$sample:'${label(sampleName)}' " +
                            "keys=$kl:$kh velocities=$vl:$vh eligible=$eligible originalKey=${u8(sh, sample * 46 + 40)} " +
                            "correction=${sh[sample * 46 + 41].toInt()} overrideRoot=${ig.values[58] ?: "default"} " +
                            "rate=${u32(sh,sample * 46 + 36)} type=${u16(sh,sample * 46 + 44)} link=${u16(sh,sample * 46 + 42)} " +
                            "frames=${u32(sh,sample * 46 + 20)}:${u32(sh,sample * 46 + 24)} PG=${pg.dump()} IG=${ig.dump()} classification=UNKNOWN"
                        fullLine(row)
                        if (!eligible) continue
                        val names = "$preset $instrumentName $sampleName".lowercase(Locale.ROOT)
                        val normalized = names.replace(Regex("[^a-z0-9]"), "")
                        val hat = normalized.contains("hihat") || Regex("\\b(hat|hh)\\b").containsMatchIn(names)
                        val snare = normalized.contains("snare") || Regex("\\bsnr\\b").containsMatchIn(names)
                        val drum = bank == 128 || bank == 127 || hat || snare ||
                            Regex("drum|kit|percuss|cymbal|kick|kendang|tabla").containsMatchIn(names)
                        for (target in targets.keys) {
                            val sameKey = target in kl..kh
                            val semanticHint = when (target) { 21, 16 -> hat; else -> snare }
                            if (!sameKey && !semanticHint && !drum) continue
                            val articulationHint = semanticHint && when (target) {
                                21 -> normalized.contains("pedal") || normalized.contains("foot")
                                16 -> normalized.contains("edge")
                                else -> normalized.contains("snare4pd")
                            }
                            val priority = when { articulationHint -> 0; semanticHint -> 1; sameKey && drum -> 2; drum -> 3; else -> 4 }
                            counts[target] = counts.getValue(target) + 1
                            val candidate = Candidate(target, priority, id,
                                "INDEX target=$target sourceKeys=$kl:$kh sameKey=$sameKey nameHint=$semanticHint articulationHint=$articulationHint $row")
                            val pool = pools.getValue(target); pool.add(candidate)
                            if (pool.size > 32) pool.poll()
                        }
                    }
                }
            }
            complete = true; reason = "metadata_only_not_pcm"
        } catch (e: Exception) { reason = label(e.message ?: e.javaClass.simpleName, 160) }
        // Drain rather than skip: hashing must include sdta and any trailing original file bytes.
        try { reader.drain(); hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } }
        catch (e: Exception) { complete = false; reason = "read_failed:${label(e.message.orEmpty())}" }
        return Scan(name, hash, reader.position, complete, reason,
            (tables["phdr"]?.size?.div(38)?.minus(1) ?: 0).coerceAtLeast(0),
            (tables["inst"]?.size?.div(22)?.minus(1) ?: 0).coerceAtLeast(0),
            (tables["shdr"]?.size?.div(46)?.minus(1) ?: 0).coerceAtLeast(0),
            relations, counts, if (complete) pools.values.flatMap { it.toList() }.sortedWith(compareBy<Candidate> { targets.keys.indexOf(it.target) }.then(order)) else emptyList())
    }

    private data class Gen(val values: Map<Int, Int> = emptyMap(), val mods: Map<List<Int>, Int> = emptyMap(), val modsKnown: Boolean = true) {
        fun inherit(global: Gen) = Gen(global.values + values, global.mods + mods, global.modsKnown && modsKnown)
        fun low(op: Int) = values[op]?.and(255) ?: 0
        fun high(op: Int) = values[op]?.ushr(8)?.and(255) ?: 127
        fun dump() = values.toSortedMap().entries.joinToString(",") { (op, v) ->
            "$op:${if (op in listOf(41,43,44,53,54,57,58)) v else v.toShort().toInt()}"
        }.ifEmpty { "none" } + ";modsKnown=$modsKnown;mods=" + mods.entries.joinToString(",") { (k,v) -> "${k.joinToString(":")}:$v" }.ifEmpty { "none" }
    }
    private fun bag(t: Map<String, ByteArray>, prefix: String, bag: Int): Gen {
        val b = t.getValue(prefix + "bag"); val g = t.getValue(prefix + "gen")
        val first = u16(b, bag * 4); val last = u16(b, (bag + 1) * 4)
        val values = (first until last).associate { u16(g, it * 4) to u16(g, it * 4 + 2) }
        for (op in listOf(43,44)) values[op]?.let { require((it and 255) <= (it ushr 8) && (it ushr 8) <= 127) { "invalid_range" } }
        val m = t[prefix + "mod"] ?: return Gen(values, modsKnown = false)
        val mf = u16(b, bag * 4 + 2); val ml = u16(b, (bag + 1) * 4 + 2)
        val mods = (mf until ml).associate { j ->
            val o = j * 10
            listOf(u16(m,o), u16(m,o+2), u16(m,o+6), u16(m,o+8)) to u16(m,o+4).toShort().toInt()
        }
        return Gen(values, mods)
    }
    private fun validate(t: Map<String, ByteArray>) {
        for ((id, width) in widths) {
            val table = t[id]
            if (id.endsWith("mod") && table == null) continue
            require(table != null && table.isNotEmpty() && table.size % width == 0) { "missing_or_invalid_$id" }
        }
        for ((prefix, header, width, field) in listOf(listOf("p","phdr","38","24"), listOf("i","inst","22","20"))) {
            val h = t.getValue(header); val w = width.toInt(); val f = field.toInt(); val bags = t.getValue(prefix + "bag")
            val indices = (0 until h.size / w).map { u16(h, it * w + f) }
            require(indices.zipWithNext().all { it.first <= it.second } && indices.all { it < bags.size / 4 }) { "invalid_${prefix}header_bags" }
            val gens = t.getValue(prefix + "gen"); val mods = t[prefix + "mod"]
            for (offset in listOf(0,2)) {
                val ix = (0 until bags.size / 4).map { u16(bags, it * 4 + offset) }
                val count = if (offset == 0) gens.size / 4 else mods?.size?.div(10)
                require(ix.zipWithNext().all { it.first <= it.second } && (count == null || ix.all { it <= count })) { "invalid_${prefix}bag_indices" }
            }
            for (j in 0 until gens.size / 4) {
                val op = u16(gens, j * 4); val value = u16(gens, j * 4 + 2)
                if (op == if (prefix == "p") 41 else 53) {
                    val count = if (prefix == "p") t.getValue("inst").size / 22 - 1 else t.getValue("shdr").size / 46 - 1
                    require(value < count) { "invalid_${prefix}link" }
                }
            }
        }
    }
    private fun sampleRow(sh: ByteArray, s: Int): String {
        val o = s * 46
        return "S id=$s name='${label(text(sh,o,20))}' frames=${u32(sh,o+20)}:${u32(sh,o+24)} " +
            "loops=${u32(sh,o+28)}:${u32(sh,o+32)} rate=${u32(sh,o+36)} originalKey=${u8(sh,o+40)} correction=${sh[o+41].toInt()} " +
            "link=${u16(sh,o+42)} type=${u16(sh,o+44)}"
    }
    private class Reader(val input: InputStream) {
        var position = 0L
        private val buffer = ByteArray(64 * 1024)
        private fun read(n: Int): ByteArray {
            val bytes = ByteArray(n); var offset = 0
            while (offset < n) {
                val got = input.read(bytes, offset, n - offset)
                require(got > 0) { "truncated_file" }; offset += got; position += got
            }
            return bytes
        }
        private fun discard(n: Long) {
            var left = n
            while (left > 0) {
                val got = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                require(got > 0) { "truncated_file" }; left -= got; position += got
            }
        }
        fun drain() { while (true) { val n = input.read(buffer); if (n < 0) return; if (n == 0) continue; position += n } }
        fun tables(): Map<String, ByteArray> {
            val header = read(12)
            require(ascii(header,0,4) == "RIFF" && ascii(header,8,4) == "sfbk") { "not_sf2" }
            val end = u32(header,4) + 8; require(end >= 12) { "invalid_riff_size" }
            val tables = mutableMapOf<String, ByteArray>(); var total = 0L
            while (position < end) {
                require(end - position >= 8) { "truncated_chunk_header" }
                val h = read(8); val id = ascii(h,0,4); val size = u32(h,4)
                require(size + (size and 1) <= end - position) { "chunk_outside_riff" }
                if (id == "LIST") {
                    require(size >= 4) { "invalid_list" }
                    val kind = ascii(read(4),0,4); val stop = position + size - 4
                    if (kind == "pdta") {
                        while (position < stop) {
                            require(stop - position >= 8) { "truncated_pdta_header" }
                            val ch = read(8); val key = ascii(ch,0,4); val n = u32(ch,4)
                            require(n + (n and 1) <= stop - position) { "table_outside_pdta" }
                            if (key in widths) {
                                require(key !in tables) { "duplicate_$key" }; total += n
                                require(total <= TABLE_BYTES && n <= TABLE_BYTES) { "metadata_table_limit" }
                                tables[key] = read(n.toInt())
                            } else discard(n)
                            if ((n and 1) != 0L) discard(1)
                        }
                    } else discard(size - 4)
                } else discard(size)
                if ((size and 1) != 0L) discard(1)
            }
            return tables
        }
    }
    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 255
    private fun u16(b: ByteArray, o: Int) = u8(b,o) or (u8(b,o+1) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b,o).toLong() or (u16(b,o+2).toLong() shl 16)
    private fun ascii(b: ByteArray, o: Int, n: Int) = String(b,o,n,Charsets.US_ASCII)
    private fun text(b: ByteArray, o: Int, n: Int): String {
        val end = (o until o+n).firstOrNull { b[it] == 0.toByte() } ?: (o+n)
        return String(b,o,end-o,charset("windows-1252"))
    }
    private fun label(s: String, max: Int = 80) = s.take(max).map { if (it < ' ' || it == '\'') '?' else it }.joinToString("")

    /** Compact text <=48 KiB; optional ZIP contains compact plus complete metadata per file. */
    fun export(discovery: Discovery, output: OutputStream, full: Boolean): Summary {
        val scans = mutableListOf<Pair<Source, Scan>>()
        val zip = if (full) ZipOutputStream(output) else null
        for ((index, source) in discovery.sources.withIndex()) {
            if (zip != null) zip.putNextEntry(ZipEntry("font_${index + 1}.txt"))
            val writer = zip?.bufferedWriter(Charsets.UTF_8)
            writer?.appendLine("SF2 METADATA v1 file='${label(source.name,256)}' stableIdentity='${label(source.identity,512)}'")
            val scan = try {
                source.open()?.use { scan(it, source.name) { row -> writer?.appendLine(row); Unit } }
                    ?: failure(source.name, "cannot_open")
            } catch (e: Exception) { failure(source.name, "read_failed:${label(e.message.orEmpty())}") }
            scans += source to scan
            writer?.appendLine("END sha256=${scan.sha256 ?: "UNKNOWN"} bytes=${scan.bytes} complete=${scan.complete} reason=${scan.reason} relations=${scan.relations} classification=UNKNOWN")
            writer?.flush(); zip?.closeEntry()
        }
        val compact = compact(scans, discovery.errors)
        val bytes = compact.toByteArray(Charsets.UTF_8)
        if (zip != null) {
            zip.putNextEntry(ZipEntry("semantic_compact.txt")); zip.write(bytes); zip.closeEntry(); zip.finish(); zip.flush()
        } else { output.write(bytes); output.flush() }
        return Summary(scans.size, scans.count { it.second.complete }, scans.count { !it.second.complete }, bytes.size)
    }
    private fun failure(name: String, reason: String) = Scan(name,null,0,false,reason,0,0,0,0,emptyMap(),emptyList())
    private fun compact(scans: List<Pair<Source, Scan>>, errors: List<String>): String {
        val out = StringBuilder(); var size = 0; var omittedFiles = 0; var omittedErrors = 0
        fun add(row: String, limit: Int = COMPACT_BYTES - 512): Boolean {
            val bytes = row.toByteArray(Charsets.UTF_8).size + 1
            if (size + bytes > limit) return false
            out.append(row).append('\n'); size += bytes; return true
        }
        add("YAMAHAARRANGER SF2 SEMANTIC INVENTORY v1 maxBytes=$COMPACT_BYTES managedFiles=${scans.size} discoveryErrors=${errors.size}")
        add("DIAGNOSTIC ONLY playback=UNCHANGED PCM=not_measured actual_BASS_sample_voice_ID=unavailable classification=UNKNOWN")
        add("CATEGORIES=${Classification.entries.joinToString("/")} names_and_bank_PC_are_evidence_NOT_identity_proof; no_winner")
        add("TARGET Yamaha MSB127 LSB0 rawPC73 displayPC74 PopDrumKit; numbering=CLOSED")
        for ((key, name) in targets) add("TARGET key=$key name='$name' search=ALL_SOURCE_KEYS_ALL_PRESETS_ALL_MANAGED_FONTS")
        add("INDEX compactLeads=32_per_target_per_font_before_byte_cap full=all_relations; priority=name_articulation_hint_then_name_family_hint_then_same_key_drum_then_other_drum_then_same_key_other; NOT_selection. Omission != missing_zone.")
        add("PG/IG op:value retain global/local inheritance separately;43/44=packed_ranges,41=instrument,53=sample,51/52=tuning,56=fixed_key,57=fixed_velocity,58=root,54=loop,57/58_unsigned; default_modulators/engine_overrides UNKNOWN")
        val fileIds = mutableListOf<Int>()
        for ((i, pair) in scans.withIndex()) {
            val (source, scan) = pair
            val row = "FILE id=$i name='${label(source.name,160)}' sha256=${scan.sha256 ?: "UNKNOWN"} stableIdentity='${label(source.identity,160)}' bytes=${scan.bytes} complete=${scan.complete} reason=${scan.reason} presets=${scan.presets} instruments=${scan.instruments} samples=${scan.samples} relations=${scan.relations}"
            if (add(row, 16 * 1024)) fileIds += i else omittedFiles++
        }
        for (error in errors) if (!add("DISCOVERY_ERROR ${label(error,160)}", 18 * 1024)) omittedErrors++
        // Fair per-file budget, with target21 first. Every font is scanned even when output is capped.
        val share = ((COMPACT_BYTES - 512 - size) / maxOf(1, fileIds.size)).coerceAtLeast(0)
        for (i in fileIds) {
            val scan = scans[i].second; val limit = minOf(COMPACT_BYTES - 512, size + share)
            for ((target, _) in targets) {
                val rows = scan.candidates.filter { it.target == target }
                // Split remaining file budget among remaining targets; target21 receives first opportunity.
                val targetLimit = size + (limit - size) / (3 - targets.keys.indexOf(target))
                var retained = 0
                for (row in rows) if (add("font=$i ${row.row}", targetLimit - 220)) retained++
                add("INDEX_COUNT font=$i target=$target examinedCandidates=${scan.candidateCounts[target] ?: 0} exported=$retained omitted=${(scan.candidateCounts[target] ?: 0) - retained} scanComplete=${scan.complete} classification=UNKNOWN", targetLimit)
            }
        }
        add("END filesScanned=${scans.size} valid=${scans.count { it.second.complete }} failed=${scans.count { !it.second.complete }} omittedFileRows=$omittedFiles omittedDiscoveryErrors=$omittedErrors full_export=optional_ZIP; UNKNOWN_not_permission_to_substitute", COMPACT_BYTES)
        return out.toString()
    }
}
