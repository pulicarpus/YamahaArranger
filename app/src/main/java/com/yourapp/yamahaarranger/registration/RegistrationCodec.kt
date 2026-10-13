package com.yourapp.yamahaarranger.registration

import java.util.zip.CRC32

class FutureSchemaException(val schema: Long) : IllegalArgumentException("Created by newer version: $schema")
data class RegistrationEnvelope<T>(val value: T, val writeSeq: Long, val savedAt: Long)

/** Pure schema-2 JSON codec. Sorted object keys and UTF-8 define the CRC payload. */
object RegistrationCodec {
    private fun crc(payload: Any?): String = "%08x".format(CRC32().apply { update(Json.write(payload).toByteArray(Charsets.UTF_8)) }.value)
    private fun envelope(kind: String, id: String, payload: Any?, seq: Long, time: Long): String {
        require(seq >= 0 && time >= 0)
        return Json.write(mapOf("schema" to 2, "kind" to kind, "id" to id, "savedAt" to time,
            "writeSeq" to seq, "payload" to payload, "crc32" to crc(payload)))
    }
    fun encodeBank(bank: RegistrationBank, writeSeq: Long = 0, savedAt: Long = 0): String =
        envelope("bank", bank.id, bankPayload(bank), writeSeq, savedAt)
    fun encodeManifest(manifest: RegistrationManifest, writeSeq: Long = 0, savedAt: Long = 0): String =
        envelope("manifest", "manifest", manifestPayload(manifest), writeSeq, savedAt)
    private fun decode(text: String, kind: String): Map<String, Any?> {
        val e = obj(Json.parse(text))
        val schema = number(e, "schema")
        if (schema > 2) throw FutureSchemaException(schema)
        require(schema == 2L) { "Unsupported schema; no released schema-1 data" }
        keys(e, "schema", "kind", "id", "savedAt", "writeSeq", "payload", "crc32")
        require(string(e, "kind") == kind && number(e, "writeSeq") >= 0 && number(e, "savedAt") >= 0)
        require(string(e, "crc32").matches(Regex("[0-9a-f]{8}")) && e["crc32"] == crc(e["payload"])) { "CRC mismatch" }
        return e
    }
    fun decodeBank(text: String): RegistrationEnvelope<RegistrationBank> {
        val e = decode(text, "bank"); val p = obj(e["payload"])
        keys(p, "id", "name", "slots")
        val slots = list(p, "slots"); require(slots.size == 8)
        val bank = RegistrationBank(id(p), name(p), slots.map { it?.let { slot(obj(it)) } })
        require(bank.id == string(e, "id"))
        return RegistrationEnvelope(bank, number(e, "writeSeq"), number(e, "savedAt"))
    }
    fun decodeManifest(text: String): RegistrationEnvelope<RegistrationManifest> {
        val e = decode(text, "manifest"); require(string(e, "id") == "manifest")
        val p = obj(e["payload"])
        keys(p, "schema", "banks", "activeBankId", "freeze", "defaultOutputMode", "externalVoices")
        require(number(p, "schema") == 2L)
        val headers = list(p, "banks").map { obj(it).let { h -> keys(h,"id","name","order"); BankHeader(id(h),name(h), integer(h,"order")) } }
        require(headers.map { it.id }.distinct().size == headers.size)
        val entries = list(p, "externalVoices").map { obj(it).let { v -> keys(v,"id","voice"); ExternalVoiceEntry(id(v), external(obj(v["voice"]))) } }
        require(entries.map { it.id }.distinct().size == entries.size)
        val m = RegistrationManifest(2, headers, string(p,"activeBankId"), freeze(obj(p["freeze"])),
            OutputMode.valueOf(string(p,"defaultOutputMode")), entries)
        require(m.activeBankId.isEmpty() && headers.isEmpty() || headers.any { it.id == m.activeBankId })
        return RegistrationEnvelope(m, number(e,"writeSeq"), number(e,"savedAt"))
    }
    private fun bankPayload(b: RegistrationBank): Any {
        require(b.slots.size == 8); validId(b.id)
        return mapOf("id" to b.id, "name" to b.name, "slots" to b.slots.map { it?.let(::slotPayload) })
    }
    private fun slotPayload(s: RegistrationSlot): Any {
        require(s.parts.size == 4)
        return mapOf("name" to s.name, "outputMode" to s.outputMode.name, "parts" to s.parts.map { p ->
            mapOf("sf2" to p.sf2?.let { mapOf("sf2FileName" to it.sf2FileName,"bank" to it.bank,"program" to it.program,"presetName" to it.presetName) },
                "external" to p.external?.let(::externalPayload), "enabled" to p.enabled,"volume" to p.volume) },
            "release" to mapOf("knobValue" to s.release.knobValue,"enabled" to s.release.enabled),
            "tempoBpm" to s.tempoBpm,"transpose" to s.transpose,"savedAtEpochMs" to s.savedAtEpochMs)
    }
    private fun externalPayload(v: ExternalVoice): Any = mapOf("msb" to v.msb,"lsb" to v.lsb,"program" to v.program,"label" to v.label,"deviceProfile" to v.deviceProfile)
    private fun manifestPayload(m: RegistrationManifest): Any {
        require(m.schema == 2)
        return mapOf("schema" to m.schema,"banks" to m.banks.map { mapOf("id" to it.id,"name" to it.name,"order" to it.order) },
            "activeBankId" to m.activeBankId,"freeze" to mapOf("tempo" to m.freeze.tempo,"transpose" to m.freeze.transpose,"release" to m.freeze.release,"voices" to m.freeze.voices,"volumes" to m.freeze.volumes,"partOnOff" to m.freeze.partOnOff),
            "defaultOutputMode" to m.defaultOutputMode.name,"externalVoices" to m.externalVoices.map { mapOf("id" to it.id,"voice" to externalPayload(it.voice)) })
    }
    private fun slot(p: Map<String,Any?>): RegistrationSlot {
        keys(p,"name","outputMode","parts","release","tempoBpm","transpose","savedAtEpochMs")
        val parts = list(p,"parts"); require(parts.size == 4)
        val r = obj(p["release"]); keys(r,"knobValue","enabled")
        return RegistrationSlot(name(p),OutputMode.valueOf(string(p,"outputMode")),parts.map { obj(it).let { a ->
            keys(a,"sf2","external","enabled","volume")
            PartSetup(a["sf2"]?.let { raw -> val v=obj(raw); keys(v,"sf2FileName","bank","program","presetName")
                Sf2VoiceRef(string(v,"sf2FileName"),bounded(v,"bank",0,65535),bounded(v,"program",0,127),string(v,"presetName")) },
                a["external"]?.let { external(obj(it)) },bool(a,"enabled"),bounded(a,"volume",0,127)) } },
            ReleaseSetup(bounded(r,"knobValue",0,127),bool(r,"enabled")),
            p["tempoBpm"]?.let { bounded(p,"tempoBpm",20,280) },p["transpose"]?.let { bounded(p,"transpose",-12,12) },number(p,"savedAtEpochMs").also { require(it >= 0) })
    }
    private fun external(v: Map<String,Any?>): ExternalVoice {
        keys(v,"msb","lsb","program","label","deviceProfile")
        return ExternalVoice(bounded(v,"msb",0,127),bounded(v,"lsb",0,127),bounded(v,"program",0,127),string(v,"label").take(24),string(v,"deviceProfile"))
    }
    private fun freeze(f: Map<String,Any?>): FreezeFlags {
        keys(f,"tempo","transpose","release","voices","volumes","partOnOff")
        return FreezeFlags(bool(f,"tempo"),bool(f,"transpose"),bool(f,"release"),bool(f,"voices"),bool(f,"volumes"),bool(f,"partOnOff"))
    }
    private fun keys(m: Map<String,Any?>, vararg keys: String) { require(m.keys == keys.toSet()) { "Unexpected/missing fields: ${m.keys}" } }
    @Suppress("UNCHECKED_CAST") private fun obj(v: Any?): Map<String,Any?> = v as? Map<String,Any?> ?: error("Object expected")
    @Suppress("UNCHECKED_CAST") private fun list(m: Map<String,Any?>, k: String): List<Any?> = m[k] as? List<Any?> ?: error("Array expected: $k")
    private fun string(m: Map<String,Any?>, k: String): String = m[k] as? String ?: error("String expected: $k")
    private fun number(m: Map<String,Any?>, k: String): Long = (m[k] as? Long) ?: error("Integer expected: $k")
    private fun integer(m: Map<String,Any?>, k: String): Int = number(m,k).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun bounded(m: Map<String,Any?>, k: String, lo: Int, hi: Int): Int = number(m,k).coerceIn(lo.toLong(),hi.toLong()).toInt()
    private fun bool(m: Map<String,Any?>, k: String): Boolean = m[k] as? Boolean ?: error("Boolean expected: $k")
    private fun name(m: Map<String,Any?>): String = string(m,"name").take(24)
    private fun id(m: Map<String,Any?>): String = string(m,"id").also(::validId)
    internal fun validId(id: String) { require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Unsafe identifier" } }

    /** Small bounded JSON subset: no reflection, coercion, duplicate fields or engine dependencies. */
    internal object Json {
        fun write(v: Any?): String = when (v) {
            null -> "null"
            is String -> "\"" + buildString { for(c in v) when(c) { '"' -> append("\\\""); '\\' -> append("\\\\"); else -> if(c < ' ') append("\\u%04x".format(c.code)) else append(c) } } + "\""
            is Boolean, is Int, is Long -> v.toString()
            is List<*> -> v.joinToString(",","[","]") { write(it) }
            is Map<*,*> -> v.entries.sortedBy { it.key as String }.joinToString(",","{","}") { write(it.key) + ":" + write(it.value) }
            else -> error("Unsupported JSON value")
        }
        fun parse(s: String): Any? { require(s.length <= 4_000_000); return Parser(s).parse() }
        private class Parser(val s: String) {
            var i=0
            fun ws() { while(i<s.length && s[i] in " \r\n\t") i++ }
            fun take(c: Char): Boolean { ws(); return if(i<s.length && s[i]==c) { i++; true } else false }
            fun parse(): Any? { val v=value(0); ws(); require(i==s.length); return v }
            fun value(depth: Int): Any? {
                require(depth <= 32); ws(); require(i<s.length)
                return when(s[i]) {
                    '"' -> str()
                    '{' -> { i++; val m=linkedMapOf<String,Any?>(); if(!take('}')) { do { ws(); val k=str(); require(!m.containsKey(k)); require(take(':')); m[k]=value(depth+1) } while(take(',')); require(take('}')) }; m }
                    '[' -> { i++; val a=mutableListOf<Any?>(); if(!take(']')) { do { a.add(value(depth+1)) } while(take(',')); require(take(']')) }; a }
                    't' -> literal("true",true)
                    'f' -> literal("false",false)
                    'n' -> literal("null",null)
                    else -> { val start=i; if(s[i]=='-') i++; require(i<s.length && s[i].isDigit()); if(s[i]=='0') i++ else while(i<s.length && s[i] in '0'..'9') i++; s.substring(start,i).toLong() }
                }
            }
            fun literal(t: String,v: Any?): Any? { require(s.startsWith(t,i)); i+=t.length; return v }
            fun str(): String {
                require(i<s.length && s[i++]=='"'); val b=StringBuilder()
                while(i<s.length) { val c=s[i++]; if(c=='"') return b.toString(); require(c>=' ')
                    if(c!='\\') b.append(c) else { require(i<s.length); when(val e=s[i++]) {
                        '"','\\','/' -> b.append(e); 'b' -> b.append('\b'); 'f' -> b.append('\u000c'); 'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                        'u' -> { require(i+4<=s.length); b.append(s.substring(i,i+4).toInt(16).toChar()); i+=4 }; else -> error("Invalid escape")
                    } }
                }; error("Unterminated string")
            }
        }
    }
}
