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
class MidiInputManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val midiManager: MidiManager? =
        context.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    private var openedDevice: MidiDevice? = null
    private var outputPort: MidiOutputPort? = null   // TERIMA dari device
    private var inputPort: MidiInputPort? = null     // KIRIM ke device

    var onNoteOn: ((midiNote: Int, velocity: Int) -> Unit)? = null
    var onNoteOff: ((midiNote: Int) -> Unit)? = null

    var connectedDeviceName: String? = null
        private set

    /** Toggle MIDI OUT — kalau ON, style notes dikirim ke E343. */
    var midiOutEnabled = false

    fun listAvailableDevices(): List<MidiDeviceInfo> {
        val mgr = midiManager ?: return emptyList()
        return mgr.devices.toList()
    }

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

            // 1) RECEIVE — dari device (chord detection)
            val recvPort = device.openOutputPort(0)
            if (recvPort != null) {
                outputPort = recvPort
                recvPort.connect(MidiNoteReceiver())
                DebugLog.add("✅ MIDI IN ready")
            } else {
                DebugLog.add("⚠ MIDI IN port failed")
            }

            // 2) SEND — ke device (style playback)
            val sendPort = device.openInputPort(0)
            if (sendPort != null) {
                inputPort = sendPort
                DebugLog.add("✅ MIDI OUT ready")
            } else {
                DebugLog.add("⚠ MIDI OUT port failed")
            }

            connectedDeviceName = name
            DebugLog.add("✅ Connected: $name")
        }, Handler(Looper.getMainLooper()))
    }

    private inner class MidiNoteReceiver : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            parseMessages(data, offset, count)
        }
    }

    private fun parseMessages(data: ByteArray, offset: Int, count: Int) {
        var i = offset
        val end = offset + count
        var runningStatus = -1

        while (i < end) {
            val byte = data[i].toInt() and 0xFF
            if (byte and 0x80 != 0) {
                runningStatus = byte
                i++
            }
            val status = runningStatus
            if (status < 0) { i++; continue }

            when (status and 0xF0) {
                0x90 -> {
                    if (i + 1 >= end) break
                    val note = data[i].toInt() and 0x7F
                    val velocity = data[i + 1].toInt() and 0x7F
                    i += 2
                    if (velocity > 0) onNoteOn?.invoke(note, velocity)
                    else onNoteOff?.invoke(note)
                }
                0x80 -> {
                    if (i + 1 >= end) break
                    val note = data[i].toInt() and 0x7F
                    i += 2
                    onNoteOff?.invoke(note)
                }
                else -> i += bytesForStatus(status)
            }
        }
    }

    private fun bytesForStatus(status: Int): Int = when (status and 0xF0) {
        0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 2
        0xC0, 0xD0 -> 1
        else -> 0
    }

    // ═════════════════════════════════════════════════════
    // MIDI OUT
    // ═════════════════════════════════════════════════════
    fun sendNoteOn(channel: Int, note: Int, velocity: Int) {
        if (!midiOutEnabled) return
        val port = inputPort ?: return
        val ch = channel.coerceIn(0, 15)
        val n = note.coerceIn(0, 127)
        val v = velocity.coerceIn(1, 127)
        val msg = byteArrayOf((0x90 or ch).toByte(), n.toByte(), v.toByte())
        try {
            port.send(msg, 0, 3)
        } catch (e: Exception) {
            Timber.w(e, "sendNoteOn failed")
        }
    }

    fun sendNoteOff(channel: Int, note: Int) {
        if (!midiOutEnabled) return
        val port = inputPort ?: return
        val ch = channel.coerceIn(0, 15)
        val n = note.coerceIn(0, 127)
        val msg = byteArrayOf((0x80 or ch).toByte(), n.toByte(), 0.toByte())
        try {
            port.send(msg, 0, 3)
        } catch (e: Exception) {
            Timber.w(e, "sendNoteOff failed")
        }
    }

    fun sendProgramChange(channel: Int, program: Int, bank: Int = 0) {
        val port = inputPort ?: return
        val ch = channel.coerceIn(0, 15)
        try {
            // Bank Select MSB (CC 0)
            port.send(byteArrayOf((0xB0 or ch).toByte(), 0.toByte(), bank.toByte()), 0, 3)
            // Program Change
            port.send(byteArrayOf((0xC0 or ch).toByte(), program.toByte()), 0, 2)
        } catch (e: Exception) {
            Timber.w(e, "sendProgramChange failed")
        }
    }

    fun allNotesOff() {
        val port = inputPort ?: return
        for (ch in 0 until 16) {
            try {
                // CC 123 = All Notes Off
                port.send(byteArrayOf((0xB0 or ch).toByte(), 123.toByte(), 0.toByte()), 0, 3)
            } catch (_: Exception) {}
        }
    }

    fun close() {
        try { outputPort?.close() } catch (e: Exception) { Timber.w(e, "close outputPort") }
        try { inputPort?.close() } catch (e: Exception) { Timber.w(e, "close inputPort") }
        try { openedDevice?.close() } catch (e: Exception) { Timber.w(e, "close device") }
        outputPort = null
        inputPort = null
        openedDevice = null
        connectedDeviceName = null
    }
}