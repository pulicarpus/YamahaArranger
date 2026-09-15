package com.yourapp.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
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
    private var outputPort: MidiOutputPort? = null

    var onNoteOn: ((midiNote: Int, velocity: Int) -> Unit)? = null
    var onNoteOff: ((midiNote: Int) -> Unit)? = null

    var connectedDeviceName: String? = null
        private set

    private var noteOnCount = 0
    private var byteCount = 0

    fun listAvailableDevices(): List<MidiDeviceInfo> {
        val mgr = midiManager ?: return emptyList()
        return mgr.devices.toList()
    }

    fun connectFirstAvailableDevice(): Boolean {
        val devices = listAvailableDevices()
        DebugLog.add("🔍 Found ${devices.size} MIDI device(s)")
        devices.forEach { info ->
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "?"
            DebugLog.add("  · $name: in=${info.inputPortCount} out=${info.outputPortCount}")
        }
        val info = devices.firstOrNull { it.outputPortCount > 0 }
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
        DebugLog.add("🔌 Opening: $name")

        mgr.openDevice(info, { device ->
            if (device == null) {
                DebugLog.add("❌ openDevice returned null")
                return@openDevice
            }
            openedDevice = device

            val port = device.openOutputPort(0)
            if (port == null) {
                DebugLog.add("❌ openOutputPort(0) null")
                device.close()
                openedDevice = null
                return@openDevice
            }
            outputPort = port

            // Connect receiver - MIDI data dari device masuk ke sini
            val receiver = MidiNoteReceiver()
            port.connect(receiver)

            connectedDeviceName = name
            byteCount = 0
            noteOnCount = 0
            DebugLog.add("✅ Connected: $name (waiting for MIDI data...)")
            DebugLog.add("   Note: tekan tuts E343 sekarang")
        }, Handler(Looper.getMainLooper()))
    }

    private inner class MidiNoteReceiver : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            byteCount += count
            // Log pertama kali terima data
            if (byteCount == count) {
                DebugLog.add("📨 First MIDI bytes received! count=$count")
            }
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
                0x90 -> {  // Note On
                    if (i + 1 >= end) break
                    val note = data[i].toInt() and 0x7F
                    val velocity = data[i + 1].toInt() and 0x7F
                    i += 2
                    if (velocity > 0) {
                        noteOnCount++
                        if (noteOnCount <= 8) {
                            DebugLog.add("🎹 NoteOn n=$note v=$velocity (#$noteOnCount)")
                        }
                        onNoteOn?.invoke(note, velocity)
                    } else {
                        if (noteOnCount <= 8) {
                            DebugLog.add("🎹 NoteOff(0vel) n=$note")
                        }
                        onNoteOff?.invoke(note)
                    }
                }
                0x80 -> {  // Note Off
                    if (i + 1 >= end) break
                    val note = data[i].toInt() and 0x7F
                    i += 2
                    if (noteOnCount <= 8) {
                        DebugLog.add("🎹 NoteOff n=$note")
                    }
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

    fun close() {
        try { outputPort?.close() } catch (e: Exception) { Timber.w(e, "close port") }
        try { openedDevice?.close() } catch (e: Exception) { Timber.w(e, "close device") }
        outputPort = null
        openedDevice = null
        connectedDeviceName = null
    }
}