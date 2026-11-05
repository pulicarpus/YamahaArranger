package com.yourapp.yamahaarranger.routing

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.ui.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path

/**
 * DEVICE ROUTING V1 on Android: lists the real MIDI and audio devices, follows plug/unplug, and applies the saved choices through the
 * existing paths only — MIDI IN into the existing parser, MIDI OUT into the existing router (MidiInputManager.connectRouting), audio as the
 * Oboe stream's device (AudioEngineManager.setOutputDevice). Decisions are RoutingResolver's (pure, JVM-tested).
 */
class DeviceRoutingController(
    private val context: Context,
    private val midi: MidiInputManager,
    private val audio: AudioEngineManager,
    private val file: Path,
    private val scope: CoroutineScope,
) {
    data class Ui(
        val settings: RoutingSettings = RoutingSettings(),
        val midiDevices: List<MidiEndpoint> = emptyList(),
        val audioDevices: List<AudioEndpoint> = emptyList(),
        val midiIn: MidiResolution = MidiResolution(null, RouteStatus.UNAVAILABLE, "…"),
        val midiOut: MidiResolution = MidiResolution(null, RouteStatus.UNAVAILABLE, "…"),
        val audioOut: AudioResolution = AudioResolution(0, null, RouteStatus.CONNECTED, "System Default"),
        val inOpen: Boolean = false, val outOpen: Boolean = false,
        val actualAudioId: Int = 0, val audioFallback: Boolean = false,
        val e343Active: Boolean = false,
        val message: String = "",
    )
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    /** OUTPUT MODE effect, applied by the app: (MIDI OUT on, internal BASSMIDI on). */
    var onOutputMode: ((Boolean, Boolean) -> Unit)? = null
    /** True when the MIDI OUT device now in use is a confirmed PSR-E343 (the E343 profile may apply). */
    var onE343Device: ((Boolean) -> Unit)? = null

    @Volatile private var settings = RoutingSettings()
    private var requestedIn = -1
    private var requestedOut = -1
    private val main = Handler(Looper.getMainLooper())
    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var started = false

    fun start() {
        if (started) return
        started = true
        settings = try { RoutingSettings.decode(if (Files.isRegularFile(file)) String(Files.readAllBytes(file), Charsets.UTF_8) else null) } catch (e: Exception) { RoutingSettings() }
        DebugLog.add("🔀 ROUTING restored: in=${settings.midiIn} out=${settings.midiOut} audio=${settings.audioOut} mode=${settings.mode.name}")
        midi.onRoutingOpened = { publish() }
        try {
            midiManager?.registerDeviceCallback(object : MidiManager.DeviceCallback() {
                override fun onDeviceAdded(device: MidiDeviceInfo) { DebugLog.add("🔌 MIDI device added: ${name(device)}"); apply(false) }
                override fun onDeviceRemoved(device: MidiDeviceInfo) {
                    DebugLog.add("🔌 MIDI device removed: ${name(device)}")
                    if (device.id == requestedIn) { midi.inputDeviceRemoved(); requestedIn = -1 }
                    if (device.id == requestedOut) { midi.outputDeviceRemoved(); requestedOut = -1 }
                    apply(false)
                }
            }, main)
        } catch (e: Exception) { DebugLog.add("⚠ MIDI device callback: ${e.javaClass.simpleName}") }
        try {
            audioManager?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { apply(false) }
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { apply(false) }
            }, main)
        } catch (e: Exception) { DebugLog.add("⚠ audio device callback: ${e.javaClass.simpleName}") }
        // Stream watchdog: the Oboe error callback only raises a flag; the stream is reopened here, off the audio thread, under the engine locks.
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(1000)
                if (audio.outputDisconnected()) {
                    DebugLog.add("⚠ AUDIO OUTPUT stream disconnected: reopening")
                    audio.setOutputDevice(_ui.value.audioOut.deviceId)
                    publish()
                }
            }
        }
        apply(true)
    }

    /** CONNECT button / auto-connect at start: reopen MIDI with the saved choices (never another device than they allow). */
    fun reconnect() = main.post { apply(true) }

    fun setMidiIn(choice: String) = update { it.copy(midiIn = choice) }
    fun setMidiOut(choice: String) = update { it.copy(midiOut = choice) }
    fun setAudioOut(choice: String) = update { it.copy(audioOut = choice) }
    fun setMode(mode: OutputMode) = update { it.copy(mode = mode) }
    /** The user confirms (or withdraws) that a MIDI OUT device is a PSR-E343. */
    fun setE343(key: String, on: Boolean) = update { it.copy(e343Devices = if (on) it.e343Devices + key else it.e343Devices - key) }
    /** The MIDI OUT bar button was used: ON keeps the internal sound (BOTH, as before), OFF = INTERNAL. */
    fun onMidiOutToggled(on: Boolean) {
        val m = if (on) (if (settings.mode == OutputMode.INTERNAL) OutputMode.BOTH else settings.mode) else OutputMode.INTERNAL
        if (m != settings.mode) update { it.copy(mode = m) }   // re-applying is idempotent: MIDI OUT already matches, internal follows the mode
    }
    fun currentSettings(): RoutingSettings = settings

    private fun update(applyMode: Boolean = true, change: (RoutingSettings) -> RoutingSettings) = main.post {
        val labels = HashMap(settings.labels)
        _ui.value.midiDevices.forEach { labels[it.key] = it.label }; _ui.value.audioDevices.forEach { labels[it.key] = it.label }
        settings = change(settings).let { s -> s.copy(labels = labels.filterKeys { it == s.midiIn || it == s.midiOut || it == s.audioOut || it in s.e343Devices }) }
        save()
        apply(false, applyMode)
    }

    private fun save() = scope.launch(Dispatchers.IO) {
        try { Files.createDirectories(file.parent); Files.write(file, RoutingSettings.encode(settings).toByteArray(Charsets.UTF_8)) }
        catch (e: Exception) { DebugLog.add("⚠ ROUTING not saved: ${e.javaClass.simpleName}") }
    }

    private fun name(i: MidiDeviceInfo) = i.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device"
    private fun midiInfos(): List<MidiDeviceInfo> = try { midi.listAvailableDevices() } catch (e: Exception) { emptyList() }
    private fun endpoint(i: MidiDeviceInfo) = MidiEndpoint(i.id, name(i),
        i.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER).orEmpty(), i.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT).orEmpty(),
        when (i.type) { MidiDeviceInfo.TYPE_USB -> "USB"; MidiDeviceInfo.TYPE_BLUETOOTH -> "BLUETOOTH"; MidiDeviceInfo.TYPE_VIRTUAL -> "VIRTUAL"; else -> "OTHER" },
        i.inputPortCount, i.outputPortCount)
    private fun audioDevices(): List<AudioEndpoint> = try {
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().mapNotNull { d ->
            RoutingResolver.audioKind(d.type)?.let { AudioEndpoint(d.id, it, d.productName?.toString().orEmpty()) }
        }.distinctBy { it.key }
    } catch (e: Exception) { emptyList() }

    private fun apply(forceMidi: Boolean, applyMode: Boolean = true) {
        val infos = midiInfos()
        val devices = infos.map(::endpoint)
        val s = settings
        val inRes = RoutingResolver.midiIn(s, devices)
        // AUTO MIDI OUT follows the MIDI IN device; otherwise only hardware (USB / Bluetooth) devices, never another app's virtual port.
        val outRes = RoutingResolver.midiOut(s, if (s.midiOut == RoutingSettings.AUTO) devices.filter { it.transport != "VIRTUAL" || it.id == inRes.endpoint?.id } else devices, inRes.endpoint)
        val wantIn = inRes.endpoint?.id ?: -1
        val wantOut = outRes.endpoint?.id ?: -1
        if (forceMidi || wantIn != requestedIn || wantOut != requestedOut) {
            requestedIn = wantIn; requestedOut = wantOut
            midi.connectRouting(infos.firstOrNull { it.id == wantIn }, infos.firstOrNull { it.id == wantOut })
            DebugLog.add("🔀 MIDI IN=${inRes.text} | MIDI OUT=${outRes.text}")
        }
        val aRes = RoutingResolver.audio(s, audioDevices())
        if (aRes.deviceId != audio.wantedOutputDevice) scope.launch(Dispatchers.IO) { audio.setOutputDevice(aRes.deviceId); publish() }
        if (applyMode) onOutputMode?.invoke(s.mode.midiOut, s.mode.internal)
        onE343Device?.invoke(RoutingResolver.isE343(outRes.endpoint, s))
        _ui.value = _ui.value.copy(settings = s, midiDevices = devices, midiIn = inRes, midiOut = outRes, audioOut = aRes)
        publish()
    }

    private fun publish() {
        val u = _ui.value
        _ui.value = u.copy(audioDevices = audioDevices(), inOpen = midi.connectedInId >= 0 && midi.connectedInId == u.midiIn.endpoint?.id,
            outOpen = midi.connectedOutId >= 0 && midi.connectedOutId == u.midiOut.endpoint?.id,
            actualAudioId = audio.actualOutputDevice(), audioFallback = audio.lastOutputFallback,
            e343Active = RoutingResolver.isE343(u.midiOut.endpoint, settings))
    }
}
