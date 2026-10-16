package com.yourapp.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import com.yourapp.yamahaarranger.ui.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MidiInputManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val midiManager: MidiManager? = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private var openedDevice: MidiDevice? = null
    private var outputPort: MidiOutputPort? = null
    @Volatile private var inputPort: MidiInputPort? = null
    var onNoteOn: ((midiNote: Int, velocity: Int) -> Unit)? = null
    var onNoteOff: ((midiNote: Int) -> Unit)? = null
    var onSustainChange: ((enabled: Boolean) -> Unit)? = null
    var connectedDeviceName: String? = null
        private set
    private val mixerOutput = MidiMixerOutput { data ->
        val port = inputPort
        if (port == null) false else try { port.send(data, 0, data.size); true }
        catch (e: Exception) { DebugLog.traceError("MIDI OUT write failed: ${e.message}"); false }
    }
    @Volatile var midiOutEnabled = false
        set(value) { mixerOutput.setEnabled(value); field = value }
    var onOutputReady: (() -> Unit)? = null
    fun setMixerControllers(channel: Int, pairs: IntArray) = mixerOutput.update(channel, pairs)
    fun setMixerMasterVolume(value: Int) = mixerOutput.setMaster(value)
    fun setMixerStyleChannels(channels: Set<Int>) = mixerOutput.setChannels(channels)
    fun setMixerMutedChannels(channels: Set<Int>) = mixerOutput.setMutedChannels(channels)

    /** MIDI channel used by the E343 keyboard for chord/ACMP input (0 = MIDI channel 1). */
    var chordInputChannel: Int = 0

    private var runningStatus = -1
    private var pendingData1 = -1

    fun listAvailableDevices(): List<MidiDeviceInfo> = midiManager?.devices?.toList() ?: emptyList()

    fun connectFirstAvailableDevice(): Boolean {
        val info = listAvailableDevices().firstOrNull { it.outputPortCount > 0 }
        if (info == null) {
            DebugLog.add("❌ No MIDI device with OUTPUT port")
            return false
        }
        connect(info)
        return true
    }

    fun connect(info: MidiDeviceInfo) {
        val mgr = midiManager ?: return
        close()
        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI Device"
        DebugLog.add("🔌 Opening: $name")
        mgr.openDevice(info, { device ->
            if (device == null) {
                DebugLog.add("❌ openDevice returned null")
                return@openDevice
            }
            openedDevice = device
            runningStatus = -1
            pendingData1 = -1

            val recvPort = device.openOutputPort(0)
            if (recvPort != null) {
                outputPort = recvPort
                recvPort.connect(MidiNoteReceiver())
                DebugLog.add("✅ MIDI IN ready (port 0)")
            } else {
                DebugLog.add("⚠ MIDI IN port failed")
            }

            val sendPort = device.openInputPort(0)
            if (sendPort != null) {
                inputPort = sendPort
                mixerOutput.setConnected(true)
                onOutputReady?.invoke()
                DebugLog.add("✅ MIDI OUT ready (port 0)")
            } else {
                DebugLog.add("⚠ MIDI OUT port failed")
            }
            connectedDeviceName = name
            DebugLog.add("✅ Connected: $name")
        }, Handler(Looper.getMainLooper()))
    }

    private inner class MidiNoteReceiver : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            com.yourapp.yamahaarranger.audio.LatencyProbe.onSendEnter(timestamp)
            parseMessages(data, offset, count)
            com.yourapp.yamahaarranger.audio.LatencyProbe.onSendExit()
        }
    }

    private fun parseMessages(data: ByteArray, offset: Int, count: Int) {
        var i = offset
        val end = offset + count
        while (i < end) {
            val b = data[i].toInt() and 0xFF

            // Realtime bytes can appear between any MIDI bytes. Ignore them without
            // destroying running status; this also prevents an infinite parser loop.
            if (b in 0xF8..0xFF) {
                i++
                continue
            }

            if (b and 0x80 != 0) {
                runningStatus = b
                pendingData1 = -1
                i++
                if (b >= 0xF0) {
                    when (b) {
                        0xF0 -> {
                            while (i < end) {
                                val sb = data[i].toInt() and 0xFF
                                i++
                                if (sb == 0xF7) break
                            }
                        }
                        0xF1, 0xF3 -> if (i < end) i++
                        0xF2 -> i = (i + 2).coerceAtMost(end)
                    }
                    runningStatus = -1
                    pendingData1 = -1
                    continue
                }
            }

            val status = runningStatus
            if (status < 0 || status >= 0xF0) {
                i++
                continue
            }

            val type = status and 0xF0
            val channel = status and 0x0F
            if (type == 0xC0 || type == 0xD0) {
                if (i < end) i++
                continue
            }

            if (pendingData1 < 0) {
                if (i >= end) break
                pendingData1 = data[i].toInt() and 0x7F
                i++
            }
            if (i >= end) break
            val d1 = pendingData1
            val d2 = data[i].toInt() and 0x7F
            i++
            pendingData1 = -1

            // RAW diagnostic before channel filtering: captures every MIDI channel.
            // This lets us distinguish E343 RIGHT traffic from ACMP traffic without
            // changing routing behavior yet.
            when (type) {
                0x90 -> DebugLog.traceMidi("RAW NOTE_ON status=0x" + status.toString(16).uppercase().padStart(2, '0') + " ch=" + channel + " note=" + d1 + " vel=" + d2)
                0x80 -> DebugLog.traceMidi("RAW NOTE_OFF status=0x" + status.toString(16).uppercase().padStart(2, '0') + " ch=" + channel + " note=" + d1 + " vel=" + d2)
                0xB0 -> if (d1 == 64 && channel == chordInputChannel) {
                    val enabled = d2 >= 64
                    onSustainChange?.invoke(enabled)
                    DebugLog.add("🎹 IN ch$channel Sustain " + if (enabled) "ON" else "OFF")
                }
            }

            when (type) {
                0x90 -> if (channel == chordInputChannel) {
                    if (d2 > 0) {
                        com.yourapp.yamahaarranger.audio.LatencyProbe.noteOnParsed()
                        onNoteOn?.invoke(d1, d2)
                        DebugLog.add("🎹 IN ch$channel NoteOn $d1 vel$d2")
                    } else {
                        onNoteOff?.invoke(d1)
                        DebugLog.add("🎹 IN ch$channel NoteOff $d1")
                    }
                }
                0x80 -> if (channel == chordInputChannel) {
                    onNoteOff?.invoke(d1)
                    DebugLog.add("🎹 IN ch$channel NoteOff $d1")
                }
            }
        }
    }

    fun sendNoteOn(channel: Int, note: Int, velocity: Int) {
        if (channel !in 0..15) return
        mixerOutput.noteOn(channel, note, velocity)
    }

    /** CC72 Release Time for an external keyboard; BASSMIDI receives the knob separately. */
    fun sendReleaseTime(channel: Int, value: Int) {
        if (!midiOutEnabled) { DebugLog.traceMidi("CC72 suppressed (MIDI OUT OFF) ch=$channel value=$value"); return }
        val port = inputPort ?: run { DebugLog.traceMidi("CC72 dropped (no output port) ch=$channel"); return }
        val ch = channel.coerceIn(0, 15)
        val v = value.coerceIn(0, 127)
        try { port.send(byteArrayOf((0xB0 or ch).toByte(), 72, v.toByte()), 0, 3); DebugLog.traceMidi("CC72 ch=$ch value=$v") }
        catch (e: Exception) { DebugLog.traceError("MIDI CC72 failed ch=$ch: ${e.message}"); Timber.w(e, "sendReleaseTime failed") }
    }

    fun sendNoteOff(channel: Int, note: Int) {
        if (channel !in 0..15) return
        mixerOutput.noteOff(channel, note)
    }

    /** Explicit RIGHT OFF affects only the selected external MIDI part. */
    fun sendRightPartSoundOff(channel: Int) {
        if (channel !in 0..2 || !midiOutEnabled) return
        val port = inputPort ?: return
        try { port.send(byteArrayOf((0xB0 or channel).toByte(), 120, 0), 0, 3) }
        catch (e: Exception) { DebugLog.traceError("RIGHT PART OFF failed ch=$channel: ${e.message}") }
    }

    fun sendProgramChange(channel: Int, program: Int, bankMsb: Int = 0, bankLsb: Int = 0) {
        if (channel !in 0..15) return
        mixerOutput.program(channel, program, bankMsb, bankLsb)
    }

    fun allNotesOff() {
        val port = inputPort ?: return
        for (ch in 0 until 16) try {
            port.send(byteArrayOf((0xB0 or ch).toByte(), 123.toByte(), 0), 0, 3)
        } catch (_: Exception) {}
    }

    fun close() {
        mixerOutput.setConnected(false)
        try { outputPort?.close() } catch (e: Exception) { Timber.w(e, "close outputPort") }
        try { inputPort?.close() } catch (e: Exception) { Timber.w(e, "close inputPort") }
        try { openedDevice?.close() } catch (e: Exception) { Timber.w(e, "close device") }
        outputPort = null
        inputPort = null
        openedDevice = null
        connectedDeviceName = null
        runningStatus = -1
        pendingData1 = -1
    }
}