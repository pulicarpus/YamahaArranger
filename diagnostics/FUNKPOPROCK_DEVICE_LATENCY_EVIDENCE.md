# FunkPopRock S542: bukti latensi PERANGKAT (Redmi Pad, 3 laporan Inspector 2026-11-06)

**Status: akar masalah sebagian besar terlokalisasi, tetapi belum dibuktikan A/B. Belum ada patch.**
- Lanjutan dari `diagnostics/FUNKPOPROCK_CROSS_OUTPUT_TIMING_ROOT_CAUSE.md`. Data perangkat menggugurkan H2 (penumpukan voice tanpa batas) dan mengukur H1 (kopling lewat mutex synth).
- Data juga menemukan penyebab kedua: kerja diagnostik/log di dalam mutex synth pada NOTE ON.

**Label bukti:**
- **DEVICE**: angka `LatencyProbe` dari perangkat pengguna.
- **CODE**: sumber, dengan path:baris.
- **ARITH**: hitungan dari angka DEVICE.
- **HYPOTHESIS**: dugaan.

## 1. Sumber data

| Laporan | SHA-256 file asli | Lama sejak RESET | Isi run (dari selisih penghitung part presence, kumulatif sejak Style dimuat) |
|---|---|---|---|
| `Inspector_20261106_180406` | `diagnostics/funkpoprock_timing/device_20261106/SOURCE_SHA256.txt` | 65,3 s | **Semua part aktif** (tidak ada not ter-mute) |
| `Inspector_20261106_180448` | idem | 39,1 s | Drum + Bass penuh. Chord1/Chord2/Pad/Phrase ter-mute ±64 % waktu |
| `Inspector_20261106_180527` | idem | 34,1 s | Campuran. Drum ter-mute ±12 %, pengiring ±55 % |

**Catatan:**
- Mute berubah di tengah run 2 dan 3, jadi run tidak bersih per kombinasi.
- Mode OUTPUT (INTERNAL / MIDI OUT / BOTH) **tidak tercatat** di laporan: UNKNOWN.
- Tempo 94 BPM (metronom `bpmX100=9400`).
- SF2: `ColomboGMGS2_BM.sf2`. Rhythm 1 PC27 → `XG CR-78` (jalur DRUM_R23).
- Potongan laporan (bagian latensi dan part presence) ada di `diagnostics/funkpoprock_timing/device_20261106/`.

## 2. Angka kunci (DEVICE)

| Ukuran | Run 1 (semua) | Run 2 | Run 3 |
|---|---|---|---|
| Event Style yang dibangunkan | 9065 | 6774 | 5654 |
| **Lateness bangun scheduler** (`styleEventWakeLatenessBeforeLifecycle`): rata-rata / maks | **37,4 / 196,0 ms** | 28,0 / 160,1 ms | 18,6 / 121,2 ms |
| Event telat > 32 ms / > 64 ms | 3653 (40 %) / 1654 (18 %) | 2181 / 1053 (16 %) | 1039 / 438 (8 %) |
| Lateness pada not pertama section (`sectionFirstNoteLatenessVsSchedule`) | rata-rata 63 ms, maks 88 ms | 58 / 82 ms | 55 / 61 ms |
| Budget callback audio | 5333 µs (256 frame @ 48 kHz, AAudio Shared LowLatency) | idem | idem |
| `renderDecode` rata-rata / maks | 2,65 / 4,85 ms | 3,72 / 5,07 ms | 3,12 / 5,82 ms |
| **Mutex synth dipegang render**, rata-rata per callback | 2,68 ms (**50 %** periode) | 3,76 ms (**70 %**) | 3,15 ms (**59 %**) |
| `styleNoteLockWait` (NOTE ON Style menunggu mutex): rata-rata / p99 / maks | 2,0 / 16 / 14,8 ms | 2,9 / 16 / 15,6 ms | 1,8 / 16 / 22,7 ms |
| `styleNoteDispatch` (event BASSMIDI sebenarnya): rata-rata | **34 µs** | 39 µs | 35 µs |
| **`synthMutexHold_styleNoteOn`** (NOTE ON memegang mutex): rata-rata / maks | **1004 µs / 58,0 ms** | 1272 µs / 10,4 ms | 1090 µs / 10,3 ms |
| NOTE ON yang memegang mutex ≥ 1 ms | 1000 dari 3837 (26 %) = 15,3/s | 493 dari 1710 = 12,6/s | 442 dari 1625 = 13,0/s |
| `renderLockWait` (callback audio menunggu mutex): maks | **54,7 ms** | 9,9 ms | 10,3 ms |
| `callbackTotal` maks | 55,2 ms | 14,0 ms | 14,8 ms |
| `overBudget` / `callbackIntervalOver150pctOfBudget` | 548 / 203 | 370 / 171 | 230 / 96 |
| Oboe `xruns` (kumulatif per stream) | 69 | 175 (+106) | 231 (+56) |
| `activeBassVoices` rata-rata / maks | 94 / 160 | 140 / 173 | 120 / 208 |

## 3. Temuan

### F-D1 (DEVICE + CODE): scheduler sangat terlambat di perangkat

- Rata-rata 19–37 ms, maksimum 121–196 ms. Sampai 18 % event telat lebih dari 64 ms (sekitar 1/10 ketukan pada 94 BPM).
- Di host tanpa biaya output, angkanya ≤ 3 ms.
- Ini **terdengar**: not jatuh berkelompok dan terlambat, sesuai keluhan "tersendat, nyangkut, tidak ikut tempo".
- Jam tetap absolut (CODE). Keterlambatan muncul dalam lonjakan, lalu pulih; bukan drift permanen.

### F-D2 (DEVICE + CODE): kopling lewat mutex synth

- Render memegang mutex 50–70 % dari setiap periode 5,3 ms (`bassmidi_player.cpp:2356-2387`).
- Setiap NOTE ON Style menunggu rata-rata 2–3 ms, p99 16 ms. Tunggu ini terjadi **di thread scheduler** (`StyleSequencer.kt:1533-1540`), satu per satu (serial).
- Satu cluster 15–20 event di downbeat × ±3 ms = 45–60 ms telat (ARITH). Ini cocok dengan p50 ≤ 32 ms dan maksimum ±200 ms saat beberapa cluster beruntun.
- MIDI OUT dikirim **setelah** panggilan native di thread yang sama (CODE), jadi MIDI OUT ikut telat.
- Render tetap berjalan di mode OUTPUT MIDI OUT (CODE `audio_engine.cpp:239-247`).

### F-D3 (DEVICE + CODE + ARITH): NOTE ON memegang mutex terlalu lama karena kerja diagnostik di dalam mutex

- Event BASSMIDI sendiri hanya 34–39 µs. Rata-rata hold 1,0–1,3 ms, jadi **> 95 % waktu hold bukan untuk menghasilkan suara**.
- Di dalam mutex, `BassMidiPlayer::noteOn` (`bassmidi_player.cpp:1222-1293`) menjalankan `logAudioPath` (`:1118-1220`). Untuk not yang "disampel" (maks 4 not biasa + 4 snare per channel per 2 s, `audio_path_diagnostic.h:65-70`), fungsi ini:
  - menulis 2–3 baris `LOGI`. `LOGI` = `uiLog` = `vsnprintf` + `__android_log_print` + **JNI `NewStringUTF` + `DebugLog.add` Kotlin** (SimpleDateFormat, alokasi, `synchronized(logLock)`) (`bassmidi_player.cpp:42-63`, `MainViewModel.kt:84-102`);
  - memanggil `BASS_MIDI_FontGetPreset` / `FontGetInfo`;
  - membangun beberapa `std::string`.
- Setiap 2 s per channel ada `AUDIO SUMMARY`.
- Setiap NOTE ON drum menjalankan `findDrumPreset` + `sf2_zones::match` + membangun string signature (`:1135-1160`).
- **Kecocokan jumlah (ARITH):**
  - Jumlah not yang disampel per detik, dengan sekitar 7 channel aktif dan snare di ch10: ±14/s.
  - Hold ≥ 1 ms terukur 12,6–15,3/s di ketiga run, **hampir konstan** walaupun jumlah event berbeda jauh. Ini sesuai kuota sampling per channel, bukan beban musik.
- **Akibatnya pada audio:** hold terpanjang (58 ms) cocok dengan `renderLockWait` 54,7 ms dan `callbackTotal` 55,2 ms. Callback audio terblokir oleh NOTE ON, sehingga terjadi xrun (+106 dalam 39 s, +56 dalam 34 s). Ini menjelaskan gejala di **audio internal BASSMIDI**.

### F-D4 (DEVICE): H2 lama (voice menumpuk tanpa batas) GUGUR

- Voice aktif tetap 94–140 rata-rata dan maksimum 160–208, jauh di bawah batas 1000. Voice tidak terus naik.
- Render tetap berat (2,7–3,7 ms per 5,3 ms), tetapi itu biaya sintesis voice nyata, bukan voice yang tertahan.
- Drum tanpa NOTE OFF ke BASSMIDI tetap fakta CODE, tetapi bukan penyebab utama di sini.

### F-D5 (DEVICE, terbatas): lateness ikut jumlah event

- Run 1 (semua part) paling buruk, run 3 (paling banyak mute) paling ringan.
- Run tidak bersih per kombinasi, jadi pola "drum + satu pengiring" **belum bisa dipisahkan** dari data ini.
- Secara mekanisme (CODE), not drum adalah yang paling sering dan paling mahal di dalam mutex (jalur drum `logAudioPath` + `percussionOn` + lane perkusi di render). Menambah satu channel lagi menambah cluster dan kuota sampling. Ini konsisten, tetapi **HYPOTHESIS** untuk detail per kombinasi.

## 4. Status hipotesis (diperbarui)

| ID | Hipotesis | Status |
|---|---|---|
| G1–G6 | Drift, transisi section, P1, parser, banjir CC, biaya Kotlin | Tetap **GUGUR** (host). Dispatch Kotlin setelah lifecycle ≈ lateness bangun, jadi kerja Kotlin per event bukan sumber utama (DEVICE) |
| H1 | Kopling mutex synth: scheduler serial menunggu render, MIDI OUT ikut telat | **TERBUKTI ADA dan BESAR** (DEVICE F-D1/F-D2) |
| H2 | Voice drum menumpuk tanpa batas | **GUGUR** (DEVICE F-D4) |
| H6 (baru) | Kerja diagnostik/log (logcat + JNI DebugLog) di dalam mutex pada NOTE ON memperpanjang hold dan memblokir callback (xrun) | **SANGAT DIDUKUNG** (DEVICE + CODE + ARITH F-D3). Belum dibuktikan A/B |
| H4 | Prioritas thread `Dispatchers.Default` | Belum diuji. Tidak diperlukan untuk menjelaskan angka |
| H5 | Batas polifoni E343 | Belum diuji. Hanya untuk MIDI OUT |

## 5. Root cause

**Akar masalah yang paling didukung bukti (belum dibuktikan A/B):**
- Not Style dikirim ke BASSMIDI secara serial dari thread scheduler, lewat **satu mutex yang juga dipegang render audio selama sintesis**.
- Pada sebagian NOTE ON, mutex itu juga ditahan untuk **log diagnostik (logcat + JNI ke `DebugLog`)**.
- Akibatnya ada dua arah:
  1. scheduler menumpuk keterlambatan 20–200 ms, dan MIDI OUT ikut telat karena dikirim setelahnya;
  2. callback audio terblokir sampai 55 ms, sehingga xrun di BASSMIDI.

**Tidak terbukti:** scheduler drift, CASM, parser, Fill/P1, penumpukan voice.

## 6. Rencana perbaikan paling kecil (BELUM dikerjakan)

Urutan yang disarankan, masing-masing dengan A/B yang terukur:

| # | Perubahan | Kenapa paling aman | Lingkup |
|---|---|---|---|
| **P-A** (eksperimen pertama) | Pindahkan kerja diagnostik `logAudioPath` / `captureChordState` / `auditNoteZone` / `LOGI` **keluar dari mutex synth**, atau matikan saat playback. Bentuk A/B: toggle runtime, default = perilaku lama. Simpan data mentah kecil di bawah lock, lalu format/log setelah lock dilepas | Tidak mengubah event MIDI, CASM, tempo, routing, atau suara. Hanya waktu hold | `bassmidi_player.cpp` (`noteOn`, `logAudioPath`) |
| P-B | Kirim MIDI OUT **sebelum** panggilan native (atau lewat thread writer berurutan), supaya MIDI OUT tidak menunggu render | Memisahkan MIDI OUT dari beban BASSMIDI. Urutan per kunci (OrderedNoteIo P1) harus dijaga | `StyleSequencer.kt:1533-1540` |
| P-C | Antrean event lock-free ke render, alih-alih mutex bersama | Menghilangkan kopling sepenuhnya, tetapi perubahan besar. Hanya kalau P-A dan P-B tidak cukup | native |

Yang tidak disarankan:
- delay atau throttle;
- menurunkan kepadatan Style;
- CC123;
- mengubah tempo, CASM, atau routing.

## 7. Risiko regresi

- **P-A:**
  - laporan diagnostik (AUDIO PATH / DRUM ZONE / ringkasan) kehilangan detail atau berubah urutan;
  - tes yang membaca baris log tersebut (`tests/*diagnostic*`, `audio_path_diagnostic_test.cpp`) harus diperiksa.

  Suara tidak berubah (event BASSMIDI identik).
- **P-B:**
  - urutan relatif MIDI OUT vs BASSMIDI berubah (perbedaan ≤ beberapa ms);
  - risiko pada urutan per kunci, panic STOP, Recorder (tap engine), dan E343 SHORT_GATE.
- Semua patch: guard isolasi diberi pengecualian sempit yang ter-hash. Uji ulang R1–R6 dan Recorder parity.

## 8. Eksperimen berikutnya yang paling menentukan

**A/B P-A di perangkat.** Butuh APK eksperimen: toggle "Diagnostik audio di NOTE ON" ON (lama) / OFF.
1. FunkPopRock, Main D, semua part, OUTPUT MODE INTERNAL.
2. 60 s dengan toggle ON, lalu SAVE REPORT. RESET LATENCY.
3. 60 s dengan toggle OFF, lalu SAVE REPORT.
4. Kalau H6 benar, dengan OFF:
   - `synthMutexHold_styleNoteOn` turun ke ≈ 50 µs;
   - `renderLockWait` maks < 1 ms;
   - xrun ≈ 0;
   - `styleNoteLockWait` dan lateness scheduler turun, walaupun yang tersisa masih dibatasi render 50–70 %;
   - **dan terdengar lebih rapi**.

Tanpa APK baru, catat juga mode OUTPUT setiap run, dan jangan mengubah mute di tengah run.

## 9. GO / NO-GO

- **GO:** membuat APK eksperimen untuk A/B P-A saja (toggle, default perilaku lama). Ini perlu persetujuan karena butuh build APK dan perubahan native.
- **NO-GO:**
  - patch produksi permanen sebelum A/B;
  - P-B/P-C sebelum hasil P-A;
  - P2/P3/P4 dari audit sebelumnya.
