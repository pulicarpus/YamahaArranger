package com.yourapp.audio

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Diagnostic-only boundary: no arranger, audio manager, production font or MIDI dependency. */
object ManagedSf2Audition {
    data class Font(val identity: String, val name: String, val sha256: String)
    data class Request(val font: Font, val bank: Int, val rawPc: Int, val sourceKey: Int, val velocity: Int)
    data class NativeResult(val wav: ByteArray, val evidence: String)
    data class Result(val wav: ByteArray, val sidecar: String) {
        fun zip(output: java.io.OutputStream, stem: String) {
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("$stem.wav")); zip.write(wav); zip.closeEntry()
                zip.putNextEntry(ZipEntry("$stem.txt")); zip.write(sidecar.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
    }
    /** Explicit file scan only. No synth call, copy, audition or automatic candidate selection. */
    fun fonts(discovery: Sf2SemanticInventory.Discovery): List<Font> {
        check(discovery.errors.isEmpty()) { discovery.errors.joinToString("; ") }
        return discovery.sources.map { source ->
            val scan = source.open()?.use { Sf2SemanticInventory.scan(it, source.name) }
                ?: error("Cannot open ${source.name}")
            check(scan.complete && scan.sha256 != null) { "Invalid metadata: ${source.name}: ${scan.reason}" }
            Font(source.identity, source.name, scan.sha256!!)
        }
    }
    /** Copy to a private, unique snapshot; verify the exact bytes subsequently passed to native. */
    fun render(request: Request, discovery: Sf2SemanticInventory.Discovery, scratch: File,
               allowed: () -> Boolean, native: (File, Request) -> NativeResult): Result {
        require(request.bank in 0..65535 && request.rawPc in 0..127 && request.sourceKey in 0..127 && request.velocity in 1..127) {
            "Bank 0..65535, rawPC/key 0..127, velocity 1..127 required"
        }
        require(Regex("[0-9a-f]{64}").matches(request.font.sha256)) { "Invalid SHA256" }
        check(allowed()) { "STOP before diagnostic audition" }
        check(discovery.errors.isEmpty()) { discovery.errors.joinToString("; ") }
        val source = discovery.sources.singleOrNull { it.identity == request.font.identity && it.name == request.font.name }
            ?: error("Selected SF2 is no longer managed; refresh fingerprints")
        val snapshot = File.createTempFile("sf2-audition-", ".sf2", scratch)
        try {
            source.open()?.use { input -> snapshot.outputStream().use { input.copyTo(it) } }
                ?: error("Cannot open selected SF2")
            val layers = mutableListOf<String>()
            // Full relations, not capped compact candidates. Bound sidecar growth explicitly.
            val scan = snapshot.inputStream().use { input ->
                Sf2SemanticInventory.scan(input, source.name) { row ->
                    if (row.startsWith("Z ")) {
                        val fields = Regex(" bank=(\\d+) rawPC=(\\d+) .* keys=(-?\\d+):(-?\\d+) velocities=(\\d+):(\\d+) eligible=(true|false) ").find(row)
                            ?: error("Invalid relation metadata")
                        val g = fields.groupValues
                        if (g[1].toInt() == request.bank && g[2].toInt() == request.rawPc && g[7] == "true" &&
                            request.sourceKey in g[3].toInt()..g[4].toInt() && request.velocity in g[5].toInt()..g[6].toInt()) {
                            check(layers.size < 4096) { "Too many eligible layers" }; layers += row
                        }
                    }
                }
            }
            check(scan.complete) { "Incomplete inventory: ${scan.reason}" }
            check(scan.sha256 == request.font.sha256) { "SF2 fingerprint changed; refresh fingerprints" }
            check(layers.isNotEmpty()) { "No eligible zone for requested bank/rawPC/sourceKey/velocity" }
            check(allowed()) { "STOP before diagnostic audition" }
            val rendered = native(snapshot, request)
            check(rendered.wav.size >= 44 && rendered.wav.copyOfRange(0,4).contentEquals("RIFF".toByteArray())) { "Diagnostic render failed" }
            val wavHash = MessageDigest.getInstance("SHA-256").digest(rendered.wav).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
            val safeName = source.name.replace('\n',' ').replace('\r',' ')
            return Result(rendered.wav, "YAMAHAARRANGER MANAGED SF2 AUDITION v1\n" +
                "diagnostic_only=true classification=UNKNOWN actual_sample_voice_ID=unavailable\n" +
                "source=$safeName managedIdentity=${source.identity} sf2SHA256=${scan.sha256} sf2Bytes=${scan.bytes}\n" +
                "request bank=${request.bank} rawPC=${request.rawPc} displayPC=${request.rawPc+1} sourceKey=${request.sourceKey} velocity=${request.velocity}\n" +
                "snapshot_verified=true inventoryComplete=true eligibleLayers=${layers.size}\n" +
                "generators 46=keynum 47=velocity 51=coarseTune 52=fineTune 56=scaleTuning 57=exclusiveClass 58=overridingRootKey; PG/IG retain inheritance; defaults/engine overrides UNKNOWN\n" +
                rendered.evidence + "\n" + layers.joinToString("\n") + "\n" +
                "wavSHA256=$wavHash normalization=none productionState=untouched\n")
        } finally { check(snapshot.delete() || !snapshot.exists()) { "Diagnostic snapshot cleanup failed" } }
    }
}
