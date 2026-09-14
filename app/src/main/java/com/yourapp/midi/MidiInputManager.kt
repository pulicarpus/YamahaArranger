package com.yourapp.yamahaarranger.midi

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

    /** Callback dari MainViewModel — set sebelum connect. */
    var onNoteOn: ((midiNote: Int, velocity: Int) -> Unit)? = null
    var onNoteOff: ((midiNote: Int) -> Unit)? = null

    /** Device yang sedang terhubung (untuk info di UI). */
    var connectedDeviceName: String? = null
        private set

    // ─────────────────────────────────────────────────────────────
    // 1. SCAN DEVICES
    // ─────────────────────────────────────────────────────────────

    fun listAvailableDevices(): List<MidiDeviceInfo> {
        val mgr = midiManager ?: return emptyList()
        val devices = mgr.devices.toList()
        Timber.d("MIDI devices found: ${devices.size}")
        devices.forEach { info ->
            Timber.d("  → ${info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)}")
        }
        return devices
    }

    // ─────────────────────────────────────────────────────────────
    // 2. CONNECT (auto pilih device pertama yang punya input port)
    // ─────────────────────────────────────────────────────────────

    fun connectFirstAvailableDevice() {
        val info = listAvailableDevices().firstOrNull { it.inputPortCount > 0 }
        if (info == null) {
            Timber.w("No MIDI device with input port found")
            return
        }
        connect(info)
    }

    fun connect(info: MidiDeviceInfo) {
        val mgr = midiManager ?: run {
            Timber.e("MidiManager not available")
            return
        }

        // Tutup koneksi lama kalau ada
        close()

        val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI Device"
        Timber.i("Opening MIDI device: $name")

        mgr.openDevice(info, { device ->
            if (device == null) {
                Timber.e("Failed to open MIDI device")
                return@openDevice
            }
            openedDevice = device

            // Buka input port #0 (PSR-E343 hanya punya 1)
            val port = device.openInputPort(0)
            if (port == null) {
                Timber.e("Failed to open input port")
                device.close()
                openedDevice = null
                return@openDevice
            }
            inputPort = port

            // Sambungkan receiver — MIDI data masuk ke sini
            val receiver = MidiNoteReceiver()
            port.connect(receiver)
            receiver.port = port

            connectedDeviceName = name
            Timber.i("✅ Connected to: $name")
        }, Handler(Looper.getMainLooper()))
    }

    // ─────────────────────────────────────────────────────────────
    // 3. RECEIVER — parse MIDI message
    // ─────────────────────────────────────────────────────────────

    private inner class MidiNoteReceiver : MidiReceiver() {
        var port: MidiInputPort? = null

        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            parseMessages(data, offset, count)
        }
    }

    /**
     * Parse MIDI bytes. Format standar:
     *   Note On : 0x90 | channel, note, velocity   (velocity > 0)
     *   Note Off: 0x80 | channel, note, velocity
     *   Note On dengan velocity 0 = Note Off
     *
     * Bisa juga ada "running status" — status byte dihilangkan
     * kalau sama dengan pesan sebelumnya. Kita handle keduanya.
     */
    private fun parseMessages(data: ByteArray, offset: Int, count: Int) {
        var i = offset
        val end = offset + count
        var runningStatus: Int = -1

        while (i < end) {
            val byte = data[i].toInt() and 0xFF

            // Status byte?
            if (byte and 0x80 != 0) {
                runningStatus = byte
                i++
            }
            // else: data byte — pakai runningStatus

            val status = runningStatus
            val command = status and 0xF0

            when (command) {
                0x90 -> { // Note On
                    if (i + 1 >= end) break
                    val note = data[i].toInt() and 0x7F
                    val velocity = data[i + 1].toInt() and 0x7F
                    i += 2

                    if (velocity > 0) {
                        onNoteOn?.invoke(note, velocity)
                    } else {
                        onNoteOff?.invoke(note)
                    }
                }
                0x80 -> { // Note Off
                    if (i + 1 >= end) break
                    val note = data[i].toInt() and 0x7F
                    i += 2  // skip note + velocity
                    onNoteOff?.invoke(note)
                }
                else -> {
                    // Bukan note on/off — skip sesuai panjang message
                    i += bytesForStatus(status)
                }
            }
        }
    }

    /** Berapa data byte setelah status byte, tergantung command. */
    private fun bytesForStatus(status: Int): Int = when (status and 0xF0) {
        0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 2  // Note Off/On, Aftertouch, CC, PitchBend
        0xC0, 0xD0 -> 1                     // Program Change, Channel Pressure
        0xF0 -> 0                           // SysEx — skip (kompleks, tidak kita butuhkan)
        else -> 0
    }

    // ─────────────────────────────────────────────────────────────
    // 4. CLOSE
    // ─────────────────────────────────────────────────────────────

    fun close() {
        try {
            inputPort?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing input port")
        }
        try {
            openedDevice?.close()
        } catch (e: Exception) {
            Timber.w(e, "Error closing device")
        }
        inputPort = null
        openedDevice = null
        connectedDeviceName = null
    }
}