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
    private val midiManager: MidiManager? =
        context.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    private var openedDevice: MidiDevice? = null
    private val outputPorts = mutableListOf<MidiOutputPort>() // device -> app
    private var inputPort: MidiInputPort? = null              // app -> device

    var onNoteOn: ((midiNote: Int, velocity: Int) -> Unit)? = null
    var onNoteOff: ((midiNote: Int) -> Unit)? = null

    var connectedDeviceName: String? = null
        private set

    var midiOutEnabled = false

    fun listAvailableDevices(): List<MidiDeviceInfo> =
        midiManager?.devices?.toList() ?: emptyList()

    fun connectFirstAvailableDevice(): Boolean {
        val devices = listAvailableDevices()
        DebugLog.add("🔎 MIDI devices=${devices.size}")
        devices.forEachIndexed { index, info ->
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "?"
            DebugLog.add("MIDI[$index] $name in=${info.inputPortCount} out=${info.outputPortCount}")
        }

        // MIDI IN for the app comes from a device OUTPUT port.
        // Prefer a Yamaha/E343-named device when several MIDI devices exist.
        val info = devices
            .filter { it.outputPortCount > 0 }
            .sortedByDescending {
                val name = it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: ""
                if (name.contains("E343", ignoreCase = true) ||
                    name.contains("Yamaha", ignoreCase = true)) 1 else 0
            }
            .firstOrNull()

        if (info == null) {
            DebugLog.add("❌ No MIDI device with OUTPUT port")
            return false
        }
        connect(info)
        return true
    }

    fun connect(info: MidiDeviceInfo) {
        val mgr = midiManager ?: run {
            DebugLog.add("❌ MidiManager null")
            return
        }
        close()

        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI Device"
        DebugLog.add("🔌 Opening: $name in=${info.inputPortCount} out=${info.outputPortCount}")

        mgr.openDevice(info, { device ->
            if (device == null) {
                DebugLog.add("❌ openDevice returned null")
                return@openDevice
            }
            openedDevice = device

            // Open EVERY device output port. Some USB MIDI devices expose more
            // than one output port/interface; assuming port 0 can silently miss notes.
            var connectedInputs = 0
            for (portIndex in 0 until info.outputPortCount) {
                try {
                    val recvPort = device.openOutputPort(portIndex)
                    if (recvPort == null) {
                        DebugLog.add("⚠ MIDI IN port $portIndex = null")
                        continue
                    }
                    val receiver = MidiNoteReceiver(portIndex)
                    recvPort.connect(receiver)
                    outputPorts += recvPort
                    connectedInputs++
                    DebugLog.add("✅ MIDI IN port $portIndex connected")
                } catch (e: Exception) {
                    DebugLog.add("❌ MIDI IN port $portIndex: ${e.javaClass.simpleName}")
                    Timber.w(e, "open/connect MIDI output port $portIndex")
                }
            }

            // App -> keyboard/target device.
            if (info.inputPortCount > 0) {
                try {
                    val sendPort = device.openInputPort(0)
                    if (sendPort != null) {
                        inputPort = sendPort
                        DebugLog.add("✅ MIDI OUT port 0 ready")
                    } else {
                        DebugLog.add("⚠ MIDI OUT port 0 = null")
                    }
                } catch (e: Exception) {
                    DebugLog.add("❌ MIDI OUT open: ${e.javaClass.simpleName}")
                }
            } else {
                DebugLog.add("ℹ Device has no INPUT port")
            }

            connectedDeviceName = name
            DebugLog.add("✅ Connected: $name; IN ports=$connectedInputs/${info.outputPortCount}")
        }, Handler(Looper.getMainLooper()))
    }

    private inner class MidiNoteReceiver(private val portIndex: Int) : MidiReceiver() {
        // Parser state belongs to this physical MIDI output port, not the manager.
        private var runningStatus = -1
        private var pendingData1 = -1
        private var rawLogged = false

        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            if (!rawLogged) {
                val bytes = (offset until (offset + count))
                    .joinToString(" ") { "%02X".format(data[it].toInt() and 0xFF) }
                DebugLog.add("📨 RAW MIDI port=$portIndex [$bytes]")
                rawLogged = true
            }
            parseMessages(data, offset, count)
        }

        private fun parseMessages(data: ByteArray, offset: Int, count: Int) {
            var i = offset
            val end = offset + count

            while (i < end) {
                val b = data[i].toInt() and 0xFF

                // MIDI realtime messages may occur between any data bytes.
                if (b in 0xF8..0xFF) {
                    i++
                    continue
                }

                if (b and 0x80 != 0) {
                    runningStatus = b
                    pendingData1 = -1
                    i++

                    // System Common / SysEx are not chord Note messages.
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

                when (type) {
                    0x90 -> if (d2 > 0) {
                        onNoteOn?.invoke(d1, d2)
                        DebugLog.add("🎹 IN NoteOn port=$portIndex n=$d1 vel=$d2")
                    } else {
                        onNoteOff?.invoke(d1)
                        DebugLog.add("🎹 IN NoteOff port=$portIndex n=$d1")
                    }
                    0x80 -> {
                        onNoteOff?.invoke(d1)
                        DebugLog.add("🎹 IN NoteOff port=$portIndex n=$d1")
                    }
                }
            }
        }
    }

    fun sendNoteOn(channel: Int, note: Int, velocity: Int) {
        if (!midiOutEnabled) return
        val port = inputPort ?: return
        val ch = channel.coerceIn(0, 15)
        val n = note.coerceIn(0, 127)
        val v = velocity.coerceIn(1, 127)
        try {
            port.send(byteArrayOf((0x90 or ch).toByte(), n.toByte(), v.toByte()), 0, 3)
        } catch (e: Exception) {
            Timber.w(e, "sendNoteOn failed")
        }
    }

    fun sendNoteOff(channel: Int, note: Int) {
        if (!midiOutEnabled) return
        val port = inputPort ?: return
        val ch = channel.coerceIn(0, 15)
        val n = note.coerceIn(0, 127)
        try {
            port.send(byteArrayOf((0x80 or ch).toByte(), n.toByte(), 0), 0, 3)
        } catch (e: Exception) {
            Timber.w(e, "sendNoteOff failed")
        }
    }

    fun sendProgramChange(channel: Int, program: Int, bank: Int = 0) {
        val port = inputPort ?: return
        val ch = channel.coerceIn(0, 15)
        try {
            port.send(byteArrayOf((0xB0 or ch).toByte(), 0, bank.coerceIn(0, 127).toByte()), 0, 3)
            port.send(byteArrayOf((0xC0 or ch).toByte(), program.coerceIn(0, 127).toByte()), 0, 2)
        } catch (e: Exception) {
            Timber.w(e, "sendProgramChange failed")
        }
    }

    fun allNotesOff() {
        val port = inputPort ?: return
        for (ch in 0 until 16) try {
            port.send(byteArrayOf((0xB0 or ch).toByte(), 123.toByte(), 0), 0, 3)
        } catch (_: Exception) {}
    }

    fun close() {
        outputPorts.forEach {
            try { it.close() } catch (e: Exception) { Timber.w(e, "close outputPort") }
        }
        outputPorts.clear()
        try { inputPort?.close() } catch (e: Exception) { Timber.w(e, "close inputPort") }
        try { openedDevice?.close() } catch (e: Exception) { Timber.w(e, "close device") }
        inputPort = null
        openedDevice = null
        connectedDeviceName = null
    }
}
