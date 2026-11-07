# P-B0.1: INSTRUMENTASI TERARAH (observer-only, dapat dimatikan)

- **Status:** diimplementasikan di working tree. **Belum di-commit, belum di-push, belum ada APK** (menunggu persetujuan).
- **Branch lokal:** `experiment/pb01-targeted-probes`, dibuat dari baseline playable `experiment/bassmidi-pa-mutex-ab` @ `910644153ca068cd8903c5257081ef65b96e3c00` (P-A + dokumen).
- **Dua file audit lama yang untracked tidak disentuh.**

**Pertanyaan yang ingin dijawab:**
1. Apakah ±36 ms voice apply di Fill/Main terutama berasal dari pengulangan `applyFonts()` (lewat `bindDrumAB` dan `SetChannelPreset`)?
2. Apakah retarget akor terhambat oleh akuisisi mutex synth yang berulang?

Dasar: `PB0_TRANSITION_CHORD_FORENSIC_AUDIT.md` (INFERRED #6, #8, #10).

## 1. Batasan yang dipatuhi

- Tidak ada perubahan pada: event, urutan event, pemilihan SF2/preset, CASM, StyleSequencer (hash P1 tetap), ArrangerBrain, router MIDI OUT, engine BASSMIDI, lock, atau P-A.
- Tidak ada optimasi dan tidak ada antrean baru.
- Probe hanya membaca jam monotonic dan menulis atomik relaxed:
  - tanpa alokasi;
  - tanpa log;
  - tanpa JNI di jalur audio/not;
  - tanpa lock baru di jalur not/render. (Satu spin-guard kecil hanya di tepi jendela, dari thread aplikasi, jarang.)
- **Dapat dimatikan:** `pb01::enabled` (default ON). Bila OFF, setiap probe hanya melakukan satu load relaxed. Tombol **`P-B0.1 PROBES: ON/OFF`** di SF2 / STYLE INSPECTOR, hanya saat Style berhenti.

## 2. Lokasi probe

| # | Lokasi (file:baris di working tree) | Yang diukur |
|---|---|---|
| N1 | `bassmidi_player.cpp:1237` `noteOn` | tunggu lock + hold per peran thread (`OpNoteOn`) |
| N2 | `bassmidi_player.cpp:1311` `noteOff` | idem (`OpNoteOff`) |
| N3 | `bassmidi_player.cpp:1362` `setChannelPreset` | idem (`OpPreset`) |
| N4 | `bassmidi_player.cpp:1524` `setChannelMixer` | idem (`OpMixer`) |
| N5 | `bassmidi_player.cpp:3185` `bindDrumAB` | idem (`OpBindDrumAB`). Lock ini `std::lock_guard` biasa, jadi **sebelumnya tidak terlihat** di histogram `synthMutexHold_*` |
| N6 | `bassmidi_player.cpp:2383` `render` | tunggu + hold render (peran audio) |
| N7 | `bassmidi_player.cpp:152` `applyFonts` | durasi, diatribusikan ke operasi pemanggil (thread-local `currentOp`) |
| N8 | `bassmidi_player.cpp:349/355` sekitar `BASS_MIDI_StreamSetFonts` | durasi SetFonts saja + ukuran tabel (entries last/max) |
| K1 | `AudioEngineManager.kt:328,342,359,367,408,668` (`pbRole()`) | memberi tahu native, **sekali per thread OS**, peran thread: `DefaultDispatcher-worker*` → scheduler, `main` → UI, lainnya → other (JNI hanya pada panggilan pertama per thread) |
| K2 | `PcmSessionControls.kt:232/240` (`voiceApplyStart/End`) | jendela voice apply: delta native per operasi peran scheduler + durasi Kotlin |
| K3 | `PcmSessionControls.kt:206/215` (`chordApplyStart/End`) | jendela retarget: delta native peran UI + durasi Kotlin + jumlah penggantian |
| J | `native_lib.cpp:301-305` | JNI: enable, role, window |
| R | `audio_engine.cpp:498/504` | `PB01_*` di SAVE REPORT; reset bersama RESET LATENCY |
| UI | `Sf2StyleInspectorScreen.kt:151-156`, `MainViewModel.kt:2196+` | toggle |

**Pola keselamatan probe lock:** `pb01::Scope` dideklarasikan **sebelum** lock (sehingga dihancurkan **setelah** lock dilepas), dan `locked()` dipanggil tepat setelah lock diambil. Urutan dan isi critical section tidak berubah. Guard memastikan tepat 6 situs Scope, 1 timer `applyFonts`, dan 1 stempel SetFonts.

## 3. Metrik yang tersedia di Inspector (SAVE REPORT)

| Baris | Arti |
|---|---|
| `PB01 enabled=…` | status probe |
| `LATENCY_NATIVE stat=PB01_mutex_<peran>_<operasi>_wait` / `_hold` | per peran (audio / scheduler / main / other) × operasi (noteOn, noteOff, render, setChannelPreset, setChannelMixer, bindDrumAB): count (= **jumlah akuisisi mutex**), mean, p50, p95, p99 (batas bucket), maks |
| `PB01_applyFonts_by_<operasi>` | berapa kali dan berapa lama `applyFonts` dipicu oleh bindDrumAB / setChannelPreset / lainnya |
| `PB01_streamSetFonts`, `PB01_fontTable entriesLast/entriesMax` | biaya `BASS_MIDI_StreamSetFonts` dan ukuran tabel font |
| `PB01_WINDOW voiceApply windows= applyFontsCallsPerWindowMean applyFontsCallsMax mutexAcquisitionsPerWindowMean …` | per transisi section |
| `PB01_voiceApply_totalAsSeenByKotlin` / `nativeWaitPlusHold` / `nonNativeRemainder` / `mutexWaitSum` / `applyFontsSum` / `streamSetFontsSum` / `bindDrumAB` / `setChannelPreset` / `setChannelMixer` | pembagian waktu satu voice apply |
| `PB01_WINDOW chordRetarget windows= mutexAcquisitionsPerWindowMean … replacements=` | per perubahan akor |
| `PB01_chordRetarget_totalAsSeenByKotlin` / `nativeWaitPlusHold` / `mutexWaitSum` / `noteOn` / `noteOff` / `nonNativeRemainder` / `perReplacement` | pembagian waktu satu retarget; `nonNativeRemainder` = Kotlin (CASM, DebugLog/traceAudio, MIDI OUT) |

## 4. Cara membaca hasil (keputusan)

**Q1: voice apply ±36 ms berasal dari `applyFonts` berulang?**
- **TERBUKTI** jika `PB01_voiceApply_applyFontsSum` mean ≥ 50 % dari `PB01_voiceApply_totalAsSeenByKotlin` mean **dan** `applyFontsCallsPerWindowMean` ≥ 2.
  - Pembeda: `PB01_applyFonts_by_bindDrumAB` dibanding `…_by_setChannelPreset` menunjukkan pemicunya.
- **SEBAGIAN:** `bindDrumAB` / `setChannelPreset` dominan, tetapi `applyFontsSum` < 50 %. Biayanya ada di bagian lain fungsi itu (resolver, log, `FontLoadEx`) atau di tunggu lock (`mutexWaitSum`).
- **GUGUR:** `nonNativeRemainder` ≥ 50 %. Biaya ada di sisi Kotlin: `resolveAB`, DebugLog, alokasi.

**Q2: retarget terhambat akuisisi mutex berulang?**
- **TERBUKTI** jika:
  - `mutexAcquisitionsPerWindowMean` ≈ 2 × penggantian per jendela; **dan**
  - `PB01_chordRetarget_mutexWaitSum` mean ≥ 50 % dari `totalAsSeenByKotlin` mean; **dan**
  - `PB01_mutex_main_noteOn_wait` / `_noteOff_wait` mean ≥ 1 ms.
- **GUGUR / arah lain:** `nonNativeRemainder` dominan. Penyebabnya kerja Kotlin per not (traceAudio, dlog, MIDI OUT), bukan lock.

## 5. Risiko overhead

| Risiko | Besaran / mitigasi |
|---|---|
| Biaya per panggilan di jalur not | 3 × `steady_clock::now()` + 2 histogram atomik + 3 penjumlah per lock site. **HOST:** +0,28 µs per pasangan NOTE ON/OFF (643 → 927 ns, mock synth). Di perangkat kemungkinan 0,5–1 µs per pasangan, dibanding tunggu lock 2–3 ms (rasio < 0,1 %). Kontrol: run ON vs OFF |
| Render (190 callback/s) | 1 Scope per callback ≈ < 1 µs per 5,3 ms |
| Thread-local role pada pool Default | Didaftarkan sekali per thread OS. Pekerjaan lain di pool Default (misalnya `lifecycleScope` AudioEngineManager) bisa tercatat sebagai "scheduler"; terlihat sebagai operasi `other`/preset di luar jendela |
| Jendela | Satu snapshot ±170 atomik di tepi jendela (dari thread aplikasi, bukan audio). Spin-guard hanya di antara dua tepi. Diuji TSan |
| Atribusi `applyFonts` | Lewat `currentOp` thread-local yang di-set Scope. Pemanggil yang tidak di-probe → `other` |
| SetFonts gagal | Tidak dicatat (return awal); terlihat sebagai `applyFonts` tanpa `streamSetFonts` |
| Sisi MIDI OUT | Tidak diukur native. Masuk `nonNativeRemainder` |
| Interaksi P-A | Tidak ada. Tes host dijalankan dengan P-A OPTIMIZED aktif |

## 6. Hasil tes (HOST)

| Tes | Hasil |
|---|---|
| `tools/test_pb01_probe.py` (baru): plain, ASan+UBSan, TSan | **PASS** (18 cek setiap build; TSan 0 peringatan). Overhead host per pasangan NOTE ON/OFF: plain +291 ns (687 → 978 ns), ASan +359 ns, TSan +1,8 µs (instrumentasi sanitizer) |
| Guard isolasi pada salinan bersih (`MIDI SONG GUARD` + `P-A GUARD` + `P-B0.1 GUARD`) | **PASS** |
| `tools/test_pa_mutex_ab.py` (P-A, plain/ASan/TSan) | **PASS**: event LEGACY = OPTIMIZED, `logsUnderNoteLock` OPTIMIZED = 0 |
| `test_voice_resolver.py` (semua tes native yang menautkan `bassmidi_player.cpp`) | **PASS (tes native, 9 baris PASS)**. Langkah terakhir `test_drum_shadow_guards.py` FAIL. Sudah ada di baseline `b66ad9f`: guard hash F12 vs StyleSequencer P1, tidak terkait P-B0.1 |
| Device Routing native, Song native, CPV JVM | **PASS** |
| R1–R5 `FillNoteReleaseP1RtTest` (ROSITA, EnglishWaltz2) | **PASS** (5 + 5) |
| R6: Recorder V1B (1), V1C (2), parity (1), E343 SHORT_GATE (13), E343 AUTO (9), Device Routing (10), Transpose/ACMP/LEFT (18), MIDI IN split (7) | **PASS** |
| APK / CI | **NOT RUN** (menunggu persetujuan) |
| Perangkat | **NOT TESTED** |

`tests/pb01_probe_test.cpp` membuktikan (18 cek):
- ON vs OFF: riwayat event BASSMIDI identik (urutan, channel, nilai);
- OFF tidak mencatat apa pun;
- voice apply: 5 preset + 7 mixer + 2 bindDrumAB = **14 akuisisi** per jendela, terhitung tepat;
- `applyFonts` diatribusikan ke `setChannelPreset`; SetFonts dan ukuran tabel tercatat;
- retarget 10 penggantian = **20 akuisisi** pada peran main;
- peran tidak bocor antar thread;
- 4 thread (render, scheduler, UI, toggle) selesai: tanpa deadlock.

## 7. Prosedur uji di Redmi Pad (setelah APK disetujui)

**Konfigurasi:**
- FunkPopRock, Main D, ACMP ON, OUTPUT INTERNAL;
- P-A **OPTIMIZED**, P-B0.1 PROBES ON;
- RESET LATENCY sebelum setiap run, SAVE REPORT sesudahnya.

| Run | Isi | Untuk |
|---|---|---|
| T1 | Main C ↔ Main D (Fill CC/DD) 10× | Q1 |
| T2 | Fill DD → Main D 10× (via Main D dari Main D/C) | Q1 (jalur antrean) |
| T3 | Akor C → F → G → Am setiap 2 detik selama 60 s, semua part aktif | Q2 |
| T4 | Ulang T3 dengan PROBES OFF | Kontrol overhead (bandingkan `styleEventWakeLateness`, `chordApplyDuration`) |

## 8. GO / NO-GO setelah data

- **Q1 TERBUKTI:** GO untuk eksperimen P-B1a (`bindDrumAB` / `applyFonts` hanya bila berubah, toggle).
- **Q2 TERBUKTI:** GO untuk P-B1b (batch retarget dalam satu akuisisi).
- **Q1/Q2 GUGUR:** revisi target ke komponen yang dominan (`nonNativeRemainder` / tunggu render).
- **Overhead T4 vs T3** > 1 ms p50 pada `styleEventWakeLateness`: data P-B0.1 tidak valid; kurangi probe.
