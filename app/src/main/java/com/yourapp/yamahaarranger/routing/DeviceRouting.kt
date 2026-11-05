package com.yourapp.yamahaarranger.routing

/**
 * DEVICE ROUTING V1 — pure decisions (no Android types), so every rule is tested on the JVM:
 * which MIDI IN / MIDI OUT / audio output device a saved choice means right now, its status, and what the OUTPUT MODE switches.
 *
 * Identity: Android device ids change on every plug-in, so a saved choice is a stable key built from what the device reports
 * (MIDI: transport + manufacturer + product + name; audio: kind + product name). The id is only used to open the device now.
 */
enum class OutputMode(val label: String, val internal: Boolean, val midiOut: Boolean) {
    INTERNAL("INTERNAL", true, false), MIDI_OUT("MIDI OUT", false, true), BOTH("BOTH", true, true)
}

enum class RouteStatus { CONNECTED, DISCONNECTED, UNAVAILABLE, OFF }

/** A MIDI device as Android lists it. [sendPorts] = its input ports (we send MIDI OUT there); [receivePorts] = its output ports (MIDI IN). */
data class MidiEndpoint(val id: Int, val name: String, val manufacturer: String, val product: String, val transport: String,
                        val sendPorts: Int, val receivePorts: Int) {
    val key: String get() = listOf(transport, manufacturer, product, name).joinToString("|") { it.trim().lowercase() }
    val label: String get() = name.ifBlank { listOf(manufacturer, product).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "MIDI device" } }
    val canIn: Boolean get() = receivePorts > 0
    val canOut: Boolean get() = sendPorts > 0
}

enum class AudioKind(val label: String) { SPEAKER("Internal speaker"), USB("USB audio"), BLUETOOTH("Bluetooth"), WIRED("Wired headphones") }

/** An audio output device as AudioManager lists it (only the kinds above are offered). */
data class AudioEndpoint(val id: Int, val kind: AudioKind, val productName: String) {
    val key: String get() = kind.name + "|" + productName.trim().lowercase()
    val label: String get() = if (productName.isBlank() || kind == AudioKind.SPEAKER) kind.label else "${kind.label}: $productName"
}

/** The user's choices. MIDI IN: AUTO or a device key. MIDI OUT: OFF, AUTO or a device key. Audio: DEFAULT or a device key. */
data class RoutingSettings(
    val midiIn: String = AUTO,
    val midiOut: String = AUTO,
    val audioOut: String = DEFAULT,
    val mode: OutputMode = OutputMode.INTERNAL,
    /** MIDI OUT device keys the user confirmed as a PSR-E343 (the E343 profile applies only there). */
    val e343Devices: Set<String> = emptySet(),
    /** Labels remembered for saved device keys, to name a device that is not plugged in now. */
    val labels: Map<String, String> = emptyMap(),
) {
    companion object {
        const val AUTO = "AUTO"; const val OFF = "OFF"; const val DEFAULT = "DEFAULT"
        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
        private fun unesc(s: String): String { val b = StringBuilder(); var i = 0
            while (i < s.length) { val c = s[i]; if (c == '\\' && i + 1 < s.length) { b.append(when (s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i + 1] }); i += 2 } else { b.append(c); i++ } }
            return b.toString() }

        fun encode(s: RoutingSettings): String = buildString {
            append("ROUTING 1\n")
            append("midiIn\t${esc(s.midiIn)}\n"); append("midiOut\t${esc(s.midiOut)}\n"); append("audioOut\t${esc(s.audioOut)}\n"); append("mode\t${s.mode.name}\n")
            for (k in s.e343Devices.sorted()) append("e343\t${esc(k)}\n")
            for ((k, v) in s.labels.toSortedMap()) append("label\t${esc(k)}\t${esc(v)}\n")
        }

        /** Unknown or damaged content gives the defaults (old users: AUTO / AUTO / System Default / INTERNAL = previous behaviour). */
        fun decode(text: String?): RoutingSettings {
            if (text == null) return RoutingSettings()
            val lines = text.lines()
            if (lines.firstOrNull()?.trim() != "ROUTING 1") return RoutingSettings()
            var r = RoutingSettings(); val e343 = HashSet<String>(); val labels = HashMap<String, String>()
            for (l in lines.drop(1)) {
                val p = l.split('\t')
                when (p[0]) {
                    "midiIn" -> if (p.size >= 2) r = r.copy(midiIn = unesc(p[1]).ifBlank { AUTO })
                    "midiOut" -> if (p.size >= 2) r = r.copy(midiOut = unesc(p[1]).ifBlank { AUTO })
                    "audioOut" -> if (p.size >= 2) r = r.copy(audioOut = unesc(p[1]).ifBlank { DEFAULT })
                    "mode" -> if (p.size >= 2) OutputMode.entries.firstOrNull { it.name == p[1].trim() }?.let { r = r.copy(mode = it) }
                    "e343" -> if (p.size >= 2) e343 += unesc(p[1])
                    "label" -> if (p.size >= 3) labels[unesc(p[1])] = unesc(p[2])
                }
            }
            return r.copy(e343Devices = e343, labels = labels)
        }
    }
}

data class MidiResolution(val endpoint: MidiEndpoint?, val status: RouteStatus, val text: String)
data class AudioResolution(val deviceId: Int, val endpoint: AudioEndpoint?, val status: RouteStatus, val text: String)

object RoutingResolver {
    /** MIDI IN: AUTO = first device that sends MIDI (the previous auto-connect rule); a saved device = only that device. */
    fun midiIn(s: RoutingSettings, devices: List<MidiEndpoint>): MidiResolution {
        if (s.midiIn == RoutingSettings.AUTO) {
            val d = devices.firstOrNull { it.canIn }
            return if (d != null) MidiResolution(d, RouteStatus.CONNECTED, "AUTO → ${d.label}")
            else MidiResolution(null, RouteStatus.UNAVAILABLE, "AUTO: no MIDI keyboard connected (on-screen keyboard still plays)")
        }
        val d = devices.firstOrNull { it.key == s.midiIn && it.canIn }
        return if (d != null) MidiResolution(d, RouteStatus.CONNECTED, d.label)
        else MidiResolution(null, RouteStatus.DISCONNECTED, "${s.labels[s.midiIn] ?: "Saved device"} not connected (on-screen keyboard still plays)")
    }

    /**
     * MIDI OUT: OFF = nothing is sent anywhere. AUTO = the MIDI IN device when it takes MIDI (previous behaviour: the keyboard you play is the module),
     * else the first device that takes MIDI. A saved device = only that device; when it is missing nothing is sent (never another device).
     */
    fun midiOut(s: RoutingSettings, devices: List<MidiEndpoint>, midiIn: MidiEndpoint?): MidiResolution {
        if (s.midiOut == RoutingSettings.OFF) return MidiResolution(null, RouteStatus.OFF, "OFF: no MIDI is sent")
        if (s.midiOut == RoutingSettings.AUTO) {
            val d = midiIn?.takeIf { it.canOut && devices.any { x -> x.id == it.id } } ?: devices.firstOrNull { it.canOut }
            return if (d != null) MidiResolution(d, RouteStatus.CONNECTED, "AUTO → ${d.label}")
            else MidiResolution(null, RouteStatus.UNAVAILABLE, "AUTO: no MIDI OUT device connected")
        }
        val d = devices.firstOrNull { it.key == s.midiOut && it.canOut }
        return if (d != null) MidiResolution(d, RouteStatus.CONNECTED, d.label)
        else MidiResolution(null, RouteStatus.DISCONNECTED, "${s.labels[s.midiOut] ?: "Saved device"} not connected: nothing is sent")
    }

    /** Audio: DEFAULT = Android's routing (id 0). A saved device missing = System Default, reported as DISCONNECTED. */
    fun audio(s: RoutingSettings, devices: List<AudioEndpoint>): AudioResolution {
        if (s.audioOut == RoutingSettings.DEFAULT) return AudioResolution(0, null, RouteStatus.CONNECTED, "System Default")
        val d = devices.firstOrNull { it.key == s.audioOut }
        return if (d != null) AudioResolution(d.id, d, RouteStatus.CONNECTED, d.label)
        else AudioResolution(0, null, RouteStatus.DISCONNECTED, "${s.labels[s.audioOut] ?: "Saved device"} not connected: playing on System Default")
    }

    /** The E343 profile only for a MIDI OUT device the user confirmed as a PSR-E343, or one that names itself so. Never assumed otherwise. */
    fun isE343(out: MidiEndpoint?, s: RoutingSettings): Boolean {
        if (out == null) return false
        if (out.key in s.e343Devices) return true
        return listOf(out.name, out.product).any { Regex("\\bPSR[- ]?E343\\b|\\bE343\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    }

    /** Android AudioDeviceInfo type -> offered kind (null = not offered: phone earpiece, SCO call path, HDMI, telephony, ...). */
    fun audioKind(type: Int): AudioKind? = when (type) {
        2 -> AudioKind.SPEAKER                  // TYPE_BUILTIN_SPEAKER
        11, 22, 12 -> AudioKind.USB             // TYPE_USB_DEVICE, TYPE_USB_HEADSET, TYPE_USB_ACCESSORY
        8, 26, 27 -> AudioKind.BLUETOOTH        // TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER
        3, 4 -> AudioKind.WIRED                 // TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES
        else -> null
    }
}
