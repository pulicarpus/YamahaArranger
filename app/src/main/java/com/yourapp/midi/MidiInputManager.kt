package com.yourapp.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
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
    private var inputPort: MidiInputPort? = null

    var onNoteOn: ((midiNote: Int, velocity: Int) -> Unit)? = null
    var onNoteOff: ((midiNote: Int) -> Unit)? = null

    var connectedDeviceName: String? = null
        private set

    fun listAvailableDevices(): List<MidiDeviceInfo> {
        val mgr = midiManager ?: return emptyList()
        return mgr.devices.toList()
    }

    fun connectFirstAvailableDevice(): Boolean {
        val info = listAvailableDevices().firstOrNull { it.inputPortCount > 0 }
        if (info == null) {
            Timber.w("No MIDI device with input port found")
            return false
        }
        connect(info)
        return true
    }

    fun connect(info: MidiDeviceInfo) {
        val mgr = midiManager ?: run {
            Timber.e("MidiManager not available")
            return
        }
        close()
        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI Device"
        Timber.i("Opening MIDI device: $name")

        mgr.openDevice(info, { device ->
            if (device == null) {
                Timber.e("Failed to open MIDI device")
                return@openDevice
            }
            openedDevice = device
            val port = device.openInputPort(0)
            if (port == null) {
                Timber.e("Failed to open input port")
                device.close()
                openedDevice = null
                return@openDevice
            }
            inputPort = port
            port.connect(MidiNoteReceiver())
            connectedDeviceName = name
            Timber.i("Connected to: $name")
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

    fun close() {
        try { inputPort?.close() } catch (e: Exception) { Timber.w(e, "close port") }
        try { openedDevice?.close() } catch (e: Exception) { Timber.w(e, "close device") }
        inputPort = null
        openedDevice = null
        connectedDeviceName = null
    }
}