package com.yourapp.yamahaarranger.ui

import com.yourapp.yamahaarranger.registration.*

enum class LivePage { HOME, LIVE, MIXER }
data class LiveNavigation(val page: LivePage = LivePage.HOME, val mixerReturn: LivePage = LivePage.HOME) {
    fun live() = copy(page = LivePage.LIVE)
    fun home() = copy(page = LivePage.HOME)
    fun mixer() = if(page == LivePage.MIXER) this else copy(page = LivePage.MIXER, mixerReturn = page)
    fun back() = copy(page = if(page == LivePage.MIXER) mixerReturn else LivePage.HOME)
}
data class LiveControlGeometry(val width: Float, val height: Float) {
    val supported get() = width >= 960f && height >= 600f
    val padding = 16f
    val gap = 8f
    val header = 64f
    val bankWidth = 160f
    val regHeight get() = if(height < 700f) 80f else 96f
    val innerWidth get() = width - padding * 2
    val styleWidth = 208f
    val partWidth = 232f
    val mainWidth get() = innerWidth - styleWidth - partWidth - gap * 2
    val mainButtonWidth get() = (mainWidth - gap * 3) / 4
    val mainButtonHeight = 112f
    val partButtonWidth get() = (partWidth - gap) / 2
    val regWidth get() = (innerWidth - bankWidth - gap * 8) / 8
    val middleHeight get() = height - padding * 2 - header - regHeight - gap * 2
    val partControlsHeight = 64f * 5 + gap * 4
}
data class LiveRegistrationState(
    val banks: List<RegistrationBank> = emptyList(), val bankIndex: Int = 0,
    val freeze: FreezeFlags = FreezeFlags(), val selectedBankId: String? = null,
    val selectedSlot: Int? = null, val selectedMode: OutputMode? = null,
    val selectedHasExternal: Boolean = false, val recalling: Boolean = false,
    val report: RecallReport? = null, val message: String = "NO BANK LOADED"
) {
    val bank get() = banks.getOrNull(bankIndex)
    val canPrevious get() = bankIndex > 0
    val canNext get() = bankIndex + 1 < banks.size
    fun move(delta: Int): LiveRegistrationState = if(banks.isEmpty()) this else copy(
        bankIndex = (bankIndex.toLong() + delta).coerceIn(0, banks.lastIndex.toLong()).toInt())
    fun slot(index: Int): RegistrationSlot? = if(index in 0..7) bank?.slots?.getOrNull(index) else null
    val warning get() = report?.completion in listOf(RecallCompletion.PARTIAL,RecallCompletion.FAILED)
    val missingVoice get() = report?.issues?.any { it.reason.startsWith("SF2_") } == true
}
fun liveModeBadge(mode: OutputMode?) = when(mode) { OutputMode.INTERNAL -> "I"; OutputMode.EXTERNAL -> "E"; OutputMode.BOTH -> "E+I"; null -> "—" }
fun liveOutputStatus(mode: OutputMode?, midiOut: Boolean, hasExternal: Boolean): String =
    if(!midiOut && hasExternal && mode != OutputMode.INTERNAL) "EXT SKIPPED: MIDI OUT OFF"
    else if(midiOut) "MIDI OUT ON" else "MIDI OUT OFF"
fun liveFreezeLabel(f: FreezeFlags): String = listOf("TEMPO" to f.tempo,"TRANSPOSE" to f.transpose,
    "RELEASE" to f.release,"VOICES" to f.voices,"VOLUMES" to f.volumes,"PART ON/OFF" to f.partOnOff)
    .filter { it.second }.joinToString(", ") { it.first }.ifEmpty { "NONE" }
fun liveTempoStep(heldMs: Long) = if(heldMs >= 1000) 5 else 1

fun liveRecallMessage(report: RecallReport?): String {
    if(report==null) return "RECALL CANCELLED"
    val details=report.issues.map { it.reason }+report.results.filter { it.result!=VoiceResult.APPLIED }.map { result ->
        val layer=when(val step=result.step) {
            is RecallStep.Sf2 -> "SF2 ${step.part.name}"
            is RecallStep.External -> "EXT ${step.part.name}"
            else -> "PARAMETER"
        }
        "$layer ${result.result.name}"
    }
    return if(details.isEmpty()) report.completion.name else details.distinct().joinToString(", ")
}

/** A landscape input panel stays usable while the software keyboard resizes the window. */
fun liveInputViewportSupported(widthDp: Float, heightDp: Float, imeVisible: Boolean): Boolean =
    widthDp >= 960f && (heightDp >= 600f || imeVisible)
