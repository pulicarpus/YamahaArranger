# YamahaArranger — Phase 1 (Fondasi) + Phase 2 (Style Engine/Arranger Brain)

Ini adalah fondasi Phase 1 sesuai roadmap: struktur project, audio engine
native (Oboe) dengan polyphony 64 + ADSR, parser SMF + pembaca section
style Yamaha dasar, chord detection engine, dan UI Compose (keyboard +
tombol section + LCD display).

## Yang sudah nyata jalan (bukan cuma kerangka)
- **AudioEngine (C++/Oboe)**: benar-benar membuka stream low-latency,
  punya 64 voice dengan ADSR dan resampling pitch — bisa menghasilkan
  suara sungguhan begitu di-build & dijalankan di device.
- **SilentPlaceholderSampleProvider**: generate satu sample sine wave
  (C4) di Kotlin, dikirim ke native lewat direct ByteBuffer, supaya jalur
  JNI → Oboe → speaker bisa langsung dites tanpa nunggu SoundFont player.
- **SmfReader**: parser Standard MIDI File (Format 0/1) yang sungguhan —
  baca header, variable-length delta time, running status, meta & sysex
  event.
- **StyleParser**: memakai SmfReader untuk memisahkan section (Main
  A-D, Intro, Fill, dst) berdasarkan marker/text meta-event per track.
  Ini menangani style yang section-nya ditandai via marker SMF biasa.
- **ChordDetector**: pattern-matching akor sungguhan (bukan placeholder)
  untuk Major/Minor/7th/9th/dim/aug/sus/6th/add9/dst, mode Single Finger
  & Fingered.
- **UI Compose**: keyboard on-screen yang benar-benar menghasilkan MIDI
  note dari koordinat tap, LCD display, tombol section, transport row.

## Tambahan Phase 2 (sungguhan, bukan stub)
- **StyleRepository + JNI section/event bridge**: seluruh section & note
  event dari StyleParser native sekarang benar-benar bisa ditarik ke
  Kotlin (`nativeGetSectionNames`, `nativeGetPartEvents`, dst) — bukan
  cuma tervalidasi bisa di-parse seperti Phase 1.
- **StyleSequencer**: pemutar section sungguhan — jalan tick-by-tick,
  loop di akhir bar, kirim note on/off ke AudioEngineManager.
  **Keterbatasan yang diakui jujur**: ini pakai `delay()` di coroutine,
  jadi TIDAK sample-accurate (bisa drift beberapa ms). Untuk bar-accurate
  sequencing sungguhan, penjadwalan perlu dipindah ke audio callback
  native — dicatat sebagai TODO Phase 2b, bukan diklaim selesai.
- **NoteTransposer**: transposisi note mengikuti akor yang dimainkan
  (root shift + snap ke interval chord quality) — pendekatan yang jauh
  lebih sederhana dari NTT/NTR asli Yamaha (yang butuh reverse-engineer
  chunk CASM proprietary), tapi cukup untuk kasus umum major/minor/7th.
- **ArrangerBrain**: benar-benar menyatukan ChordDetector + StyleSequencer
  + section switching, termasuk logika "ganti Main A→B saat playing =
  mainkan Fill dulu" (auto-fill). Chaining fill→main yang presisi di
  bar boundary masih TODO (lihat komentar di kode).
- **MidiInputManager**: USB-MIDI & Bluetooth-MIDI INPUT yang sungguhan
  jalan, pakai `android.media.midi` bawaan Android (bukan native/vendor
  SDK) — device MIDI USB/BLE yang sudah dipasangkan otomatis masuk lewat
  jalur chord+audio yang sama dengan keyboard on-screen.
- **Registration Memory (Room)**: skema tabel 8 bank x 4 tombol sudah
  ada (entity+DAO), tapi UI untuk save/recall belum dibuat — ini
  fondasi datanya saja.
- **Import style dari storage**: tombol "Import .sty..." pakai Storage
  Access Framework (`OpenDocument`), bisa baca dari file lokal, SD card,
  atau cloud docs provider manapun yang terdaftar di sistem.

## Yang MASIH kerangka/TODO
- **CASM/NTT/NTR byte-exact** (lihat NoteTransposer) — pendekatan Phase 2
  adalah aproksimasi, bukan implementasi lengkap format proprietary.
- **SoundFont player (SF2/SFZ)** — SampleProvider masih placeholder sine
  wave; TinySoundFont/FluidSynth belum di-embed.
- **DSP effects** (Reverb/Chorus/Delay/EQ/Compressor), **voice
  layering** (Right1/Right2/Left, split point) — kolom untuk voice
  layering sudah dicadangkan di RegistrationMemoryEntity, tapi belum ada
  implementasi audio-nya.
- **RTP-MIDI (MIDI over WiFi)** — TIDAK dicakup oleh `android.media.midi`
  (itu hanya USB & BLE); perlu implementasi protokol AppleMIDI/UDP
  terpisah.
- **Recording (MIDI + Audio)**, **Style browser UI berkategori**,
  **Style converter**, **OTS per style**, **Song player (SMF/MP3/WAV)**,
  **Performance mode**, **UI final ala ORG24**, **rilis Play Store** —
  ini semua Phase 3-4 di roadmap Anda, belum digarap.

## ⚠️ Penting soal cara kerja Anda (Acode di tablet, tanpa desktop)
Proyek ini punya bagian **C++/NDK** yang butuh toolchain compile native
(CMake + NDK). Ini **tidak bisa dites langsung dari Acode** seperti kode
Kotlin biasa — Anda perlu build lewat CI, sama seperti alur
`gereja_mobile` (GitHub Actions → APK).

Sudah saya siapkan `.github/workflows/build.yml` yang:
1. Install JDK 17, Android SDK, NDK 26, CMake
2. Build `assembleDebug` (termasuk compile native code)
3. Upload hasil APK sebagai artifact (bisa juga diarahkan ke Telegram
   seperti proyek gereja Anda — tinggal uncomment bagian di file yml)

**Catatan**: repo ini belum menyertakan `gradle-wrapper.jar` (file
binary tidak ikut dalam zip dari chat ini) — workflow CI memakai
`gradle/actions/setup-gradle` sehingga tetap bisa build tanpa wrapper.
Kalau nanti Anda generate wrapper sendiri, bisa ganti ke `./gradlew`.

Juga belum ada file icon (`mipmap/ic_launcher`) — tambahkan launcher
icon sebelum build pertama, atau build akan gagal di resource linking.

## Saran langkah berikutnya
Bagian yang paling akan mengubah kualitas suara secara drastis adalah
**SoundFont player (TinySoundFont)** — begitu itu ada, semua instrumen
lain (Bass, Chord, Pad di style) langsung terdengar seperti alat musik
sungguhan, bukan sine wave yang di-pitch-shift. Saran saya itu prioritas
berikutnya, baru menyusul DSP effects & voice layering.

Build & jalankan dulu lewat CI untuk pastikan jalur audio + MIDI native
ini beneran bunyi di device Anda, baru kita lanjut modul berikutnya.
