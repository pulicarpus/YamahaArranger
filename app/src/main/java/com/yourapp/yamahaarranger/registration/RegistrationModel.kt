package com.yourapp.yamahaarranger.registration

enum class OutputMode { INTERNAL, EXTERNAL, BOTH }
enum class Part(val channel: Int) { RIGHT1(0), RIGHT2(1), RIGHT3(2), LEFT(3) }
data class Sf2VoiceRef(val sf2FileName: String, val bank: Int, val program: Int, val presetName: String)
data class ExternalVoice(val msb: Int, val lsb: Int, val program: Int, val label: String,
    val deviceProfile: String = "YAMAHA_E_SERIES")
data class PartSetup(val sf2: Sf2VoiceRef?, val external: ExternalVoice?, val enabled: Boolean, val volume: Int)
data class ReleaseSetup(val knobValue: Int, val enabled: Boolean)
data class RegistrationSlot(val name: String, val outputMode: OutputMode, val parts: List<PartSetup>,
    val release: ReleaseSetup, val tempoBpm: Int?, val transpose: Int?, val savedAtEpochMs: Long)
data class RegistrationBank(val id: String, val name: String, val slots: List<RegistrationSlot?>)
data class FreezeFlags(val tempo: Boolean = true, val transpose: Boolean = false, val release: Boolean = false,
    val voices: Boolean = false, val volumes: Boolean = false, val partOnOff: Boolean = false)
data class BankHeader(val id: String, val name: String, val order: Int)
data class ExternalVoiceEntry(val id: String, val voice: ExternalVoice)
data class RegistrationManifest(val schema: Int = 2, val banks: List<BankHeader>, val activeBankId: String,
    val freeze: FreezeFlags, val defaultOutputMode: OutputMode = OutputMode.EXTERNAL,
    val externalVoices: List<ExternalVoiceEntry>)
