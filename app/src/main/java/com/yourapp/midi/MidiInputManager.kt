package com.yourapp.yamahaarranger.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real USB-MIDI and Bluetooth-MIDI input using Android's built-in
 * android.media.midi framework (API 23+, well within this app's minSdk 26)
 * — no vendor SDK or native USB-host code needed for class-compliant
 * devices. RTP-MIDI (MIDI over WiFi) is NOT covered by this framework and
 * needs its own UDP/AppleMIDI-protocol implementation — left as a Phase 3
 * TODO, tracked separately from USB/BLE which this class does handle.
 *
 * Every class-compliant USB MIDI keyboard and every paired BLE-MIDI
 * device shows up here as a [MidiDeviceInfo] the same way, since Android
 * exposes Bluetooth LE MIDI peripherals as regular MidiDevices once paired
 * via Settings > Bluetooth (no separate BLE GATT handling required here).
 */
@Singleton
class MidiInputManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val midiManager: MidiManager? =
        context.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    private var openDevice: MidiDevice? = null
    private var outputPort: MidiOutputPort? = null

    var onNoteOn: ((note: Int, velocity: Int) -> Unit)? = null
    var onNoteOff: ((note: Int) -> Unit)? = null

    fun listAvailableDevices(): List<MidiDeviceInfo> {
        val manager = midiManager ?: run {
            Timber.w("MidiManager unavailable — device has no MIDI feature")
            return emptyList()
        }
        return manager.devices.toList()
    }

    /** Opens the first input port of [info] and starts routing note on/off
     * into [onNoteOn]/[onNoteOff]. Call [close] before opening another. */
    fun connect(info: MidiDeviceInfo) {
        val manager = midiManager ?: return
        manager.openDevice(info, { device ->
            if (device == null) {
                Timber.e("Failed to open MIDI device ${info.id}")
                return@openDevice
            }
            openDevice = device
            val port = device.openOutputPort(0) // device's OUTPUT is our INPUT
            if (port == null) {
                Timber.e("MIDI device ${info.id} has no output port 0")
                return@openDevice
            }
            outputPort = port
            port.connect(receiver)
        }, /* handler = */ null)
    }

    fun close() {
        outputPort?.disconnect(receiver)
        outputPort = null
        openDevice?.close()
        openDevice = null
    }

    // MidiReceiver.onSend gives us raw MIDI bytes exactly as the USB/BLE
    // device sent them — parse the minimal subset (note on/off) we need.
    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            var i = offset
            val end = offset + count
            while (i < end) {
                val status = msg[i].toInt() and 0xFF
                val hi = status and 0xF0
                if ((hi == 0x90 || hi == 0x80) && i + 2 < end) {
                    val note = msg[i + 1].toInt() and 0x7F
                    val velocity = msg[i + 2].toInt() and 0x7F
                    if (hi == 0x90 && velocity > 0) {
                        onNoteOn?.invoke(note, velocity)
                    } else {
                        onNoteOff?.invoke(note)
                    }
                    i += 3
                } else {
                    i += 1 // skip unrecognized/other status bytes conservatively
                }
            }
        }
    }
}
