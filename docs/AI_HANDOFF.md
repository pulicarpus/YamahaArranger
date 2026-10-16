# AI HANDOFF — YamahaArranger (experimen Colombo Primary)

Branch kerja: `experiment/colombo-primary-voice-override`. Baseline stabil: R2-3 `c75732f28fec58fbfbc7e626bc380b10a00722b8`
(R2-4 Drum Punch **tidak** dipakai). Jangan merge ke `main`, jangan buat PR tanpa persetujuan pemilik.

Label bukti: **[T]** terbukti (kode, `git diff`, laporan perangkat), **[K]** indikasi kuat, **[?]** belum diketahui.

## Aturan yang harus dipertahankan
- CASM, algoritma chord, dan aturan transisi Main/Fill **tidak boleh diubah** sebelum penyebab latensi terbukti dengan pengukuran.
- LIVE CONTROL, REG MEMORY (schema 2), RELEASE, RIGHT OFF, MIDI OUT PSR-E343 (tanpa Bank/PC otomatis), Voice Inspector, dan
  override Mixer per style harus tetap berfungsi.
- Tidak ada limiter, normalisasi, atau perubahan gain tanpa persetujuan.
- Prime Melodi/Drum tidak dimuat otomatis. Colombo = SF2 utama, Tyros 4 = fallback.

## Status fitur CPV (kode lokal lulus tes host; belum diuji di perangkat)
- P0–P5 selesai: Style Voice Inspector, Colombo utama/Tyros fallback, override manual per style (disimpan per hash isi style,
  schema 1, siap per-section), drum lewat resolver R2-3 (RESOLVED selalu aktif).
- Build APK dijalankan oleh CI `cpv-colombo-primary.yml`. Kode Compose belum pernah dikompilasi di lingkungan lokal (tanpa Android SDK).

## Investigasi latensi (gejala dari Redmi Pad + E343)
Gejala nyata: RIGHT1/2/3 lewat audio internal terlambat (termasuk Piano); suara langsung E343 normal; style terasa tertahan saat
pindah Main; respons chord ACMP terlambat. SF2 terbaca benar.

### Fakta terverifikasi
1. **[T] Callback audio = 256 frame (5,33 ms @48 kHz).** Dari laporan perangkat `Inspector_230525`/`230632`:
   `frames/callbacks` = 3.646.464/14.244 = 6.858.240/26.790 = 256. Oboe diminta LowLatency+Exclusive, buffer = 2×burst
   (`audio_engine.cpp:49-94`), dengan fallback Shared/None bila ditolak. **Mode yang benar-benar dipakai perangkat belum
   diketahui** (log "Stream opened" tidak ada di laporan) → diukur oleh `LATENCY_OUTPUT`.
2. **[T] Satu mutex (`BassMidiPlayer::mutex_`) dipakai bersama** oleh `render()` (ditahan selama seluruh `BASS_ChannelGetData`),
   `noteOn/noteOff`, `setChannelPreset` (+`findMelodicPreset`, `applyFonts`/`BASS_MIDI_StreamSetFonts`, preload, log),
   `setChannelMixer`, `bindDrumAB`, `pinChannelPreset`, `channelBinding`, `presetList`. Callback audio memakai `lock_guard`
   biasa; panggilan berat di thread lain bisa memblok callback (underrun) dan NOTE_ON.
3. **[T] Jalur RIGHT tidak berubah antara R2-3 dan CPV.** `git diff c75732f HEAD` tidak punya hunk di `MidiInputManager`,
   `ArrangerBrain.onKeyboardNoteOn/startRightNote`, `AudioEngineManager.noteOn/noteOnChannel`, maupun `BassMidiPlayer::noteOn/render`
   (sebelum probe ditambahkan). CPV tidak menambah pekerjaan per-nada.
4. **[T] Jalur RIGHT berjalan di thread MIDI Android** (bukan main), tanpa hop dispatcher. Per nada dengan 3 layer: 3 kali
   ambil `mutex_` native berurutan, ±6 panggilan `DebugLog.add` (format tanggal + monitor), alokasi kecil
   (`mutableMapOf`, `Pair`, `String.format`). Tidak ada `runBlocking`. Pembacaan `BASS_MIDI_StreamGetPreset` hanya sekali per preset.
5. **[T] ACMP**: `chordSettleMs = 15` (`ArrangerBrain.kt:89`) lalu `applyChordNow` di **thread Main** (`viewModelScope`).
   `handleChordChange` me-retarget not yang sedang berbunyi secara sinkron (monitor `lifecycle` + `mutex_` per not).
   Tidak berubah di CPV.
6. **[T] Section**: perpindahan dikuantisasi ke **bar berikutnya** (bukan beat) — ini dirancang. Setelah boundary, sebelum note
   pertama section baru, thread sequencer menjalankan berurutan: release note lama, `applyVoicesFromCasm` (JNI+log per kanal),
   lalu merge/sort event section. `applyVoicesFromCasm` hanya jalan bila **nama** section berubah.
7. **[T] Perbedaan CPV vs R2-3 yang menyentuh transisi:** drum RESOLVED sekarang selalu aktif. Di R2-3 default LEGACY, jalur drum
   tidak memanggil `applyFonts` (`if (!state.drum && !applyFonts())`). Di CPV, `applyDrumAB` → `bindDrumAB` → `applyFonts`
   (+ ±K+4 baris `uiLog` lewat JNI → `DebugLog`) berjalan untuk ch8 dan ch9 pada setiap pergantian nama section, di dalam
   `mutex_`. Ini satu-satunya penambahan CPV di jalur section-start.
8. **[T] Bukti lama (F12, Build #811/#813, LoveSong, sebelum CPV):** keterlambatan event style 77–100 ms pada transisi Main,
   dispatch maksimum 61,7 ms, tunggu `lifecycle` 24,3 ms, jeda callback 40,3 ms; **akar masalah saat itu "UNKNOWN"**
   (`docs/F12_LATENCY_DIAGNOSTIC.md`). Jadi gejala section/chord sudah ada sebelum CPV dan **bukan semata regresi CPV**.
9. **[T] `uiLog` native** (`vsnprintf` + logcat + `NewStringUTF` + `DebugLog.add`) dipanggil dalam `mutex_` pada
   `setChannelPreset`/`applyFonts` (±25–35 baris per kanal berubah). Biaya per baris tidak terukur di kode.

### Hipotesis (belum terbukti — diukur oleh probe)
| # | Hipotesis | Diukur oleh |
|---|---|---|
| H1 | Stream Oboe jatuh ke Shared/None atau latensi output Android besar | `LATENCY_OUTPUT`: `fallbackToShared`, `perf`, `estimatedOutputLatencyMs`, `xruns` |
| H2 | Render mendekati anggaran 5,33 ms (Colombo, 1000 voice, SRC 8-point, meter PCM debug, native debug) | `callbackTotal` p99, `overBudget`, `over80pct`, `renderDecode` |
| H3 | `mutex_` ditahan lama saat section-start (apply voice, drum RESOLVED) | `renderLockWait` max, `keyboardNoteLockWait` max pada waktu transisi |
| H4 | Biaya `DebugLog` per nada/section di thread MIDI/sequencer | `midiOnSendTotal`, `rightNoteOnSend…`, `sectionVoiceApplyDuration` |
| H5 | Sampel Colombo belum termuat saat nada pertama (preload NOWAIT) | outlier `keyboardNoteQueuedToBufferReady` pada nada pertama |
| H6 | ACMP: 15 ms + hop Main + retarget; detektor chord tidak membersihkan `heldNotes` saat release sehingga chord baru bisa tertunda | `chordLastNoteToApplyChordNow`, `chordApplyDuration…` |
| H7 | APK debug membangun native tanpa optimasi dan dengan meter PCM | log build CI (`CMAKE_BUILD_TYPE`), perbandingan `renderDecode` |

## Instrumentasi yang ditambahkan (observer saja, perilaku tidak berubah)
- Native `latency_probe.h`: atomik relaxed + `steady_clock`, tanpa log/alokasi/lock/JNI di jalur realtime.
  Hook di `BassMidiPlayer::noteOn` (tunggu lock, dispatch), `render` (tunggu lock, decode, pasangan nada→render), dan callback Oboe
  (durasi total, anggaran, over-budget). `AudioEngine::latencyReport()` menambah deskripsi stream (sharing, perf, API, burst, buffer),
  `getXRunCount`, dan `calculateLatencyMillis` (estimasi) — dipanggil di luar callback.
- Kotlin `LatencyProbe` (di `PcmSessionControls.kt`): ingress USB, thread MIDI, lock keyboard, JNI, section (permintaan→boundary→apply
  →note pertama), scheduler, ACMP. Jam `System.nanoTime()` = `CLOCK_MONOTONIC` = jam native.
- Inspector: tombol **RESET LATENCY**; blok `=== LATENCY PROBE ===` di SAVE REPORT.
- Uji: `tests/cpv/CpvLatencyProbeTest.kt` (JVM) dan bagian 7 di `tests/cpv_native_test.cpp` (pasangan nada→render, parity MIDI
  probe ON/OFF, kontrak anggaran). Protokol perangkat: `docs/cpv/LATENCY_TEST.md`.
- Overhead di host x86: ±29 ns per `now()`, ±16 ns per `record`, ±0,19 µs per siklus note+render+callback. **Belum diukur di ARM.**

## Rencana perbaikan (belum dikerjakan; urutan menurut bukti yang akan datang)
Jangan menerapkan butir apa pun sebelum data perangkat mendukungnya.
1. **Bila H1:** konfigurasi stream — mencoba LowLatency tanpa Exclusive, tidak memaksa 48 kHz bila perangkat asli berbeda
   (buat BASS di laju perangkat), `Usage::Game`, penyetelan buffer berbasis `xruns`.
2. **Bila H2:** kurangi beban render — turunkan voice maksimum, kualitas SRC untuk keyboard live, matikan meter PCM dan
   metering per-sampel di build uji latensi, build native dengan optimasi.
3. **Bila H3:** satu `applyFonts` per section (bukan per kanal), lewati `applyDrumAB` bila binding drum tidak berubah,
   hapus `uiLog` dari dalam `mutex_` (antrian log asinkron), siapkan voice section berikutnya sebelum boundary bila targetnya diketahui.
4. **Bila H4:** ganti `DebugLog.add` per nada di thread MIDI dengan ring tanpa lock; salin buffer ekspor di luar monitor.
5. **Bila H6:** ukur dulu; baru pertimbangkan memindahkan `applyChordNow` dari Main dan mengevaluasi settle 15 ms. Algoritma chord tidak diubah.
6. **Perbandingan dengan R2-3:** probe yang sama perlu dipasang di cabang berbasis R2-3 (hanya instrumentasi) agar A/B di perangkat
   memakai alat ukur yang sama. Belum dibuat karena butuh branch baru.

## Hasil uji lokal (host)
Lihat pesan commit terakhir. Native, JVM (97 tes), Policy C, ROSITA, LIVE/REG lulus lokal; build Android dan semua klaim audio/latensi
**belum diverifikasi** sampai CI dan perangkat.
