# P-A: A/B DIAGNOSTIK MUTEX BASSMIDI (EKSPERIMEN, bukan patch produksi)

- **Status:** implementasi + tes host PASS, CI run `42215796639` **success**, APK tersedia (§7). **Belum diuji di Redmi Pad.** Akar masalah **belum** dinyatakan diperbaiki.
- **Branch eksperimen:** `experiment/bassmidi-pa-mutex-ab`.
  - Dibuat dari `claude/e343-audit-continue-q40lxg` @ `b66ad9f87fb9a1907188950ddd6e40864c7bf7a0`, yang berisi P1 + laporan audit.
  - Tidak ada merge dan tidak ada PR. Branch produksi tidak diubah.
- **Commit implementasi:** `79d36a9bc68173740da596d5231779bc0b8664a0`.
- **Dasar:** `FUNKPOPROCK_DEVICE_LATENCY_EVIDENCE.md` (H6), `FUNKPOPROCK_CROSS_OUTPUT_TIMING_ROOT_CAUSE.md`, `FUNKPOPROCK_S542_ACMP_FILL_D_AUDIT.md`.

## 1. Hipotesis yang diuji

**H6:**
- Kerja diagnostik `logAudioPath` dijalankan di dalam mutex synth pada NOTE ON/OFF Style. Kerja itu meliputi:
  - `LOGI` = `uiLog` = logcat + JNI `DebugLog.add` Kotlin;
  - signature drum-zone dan `sf2_zones::match`;
  - audit NOTE ZONE;
  - `FontGetPreset` / `FontGetInfo` untuk not yang disampel.
- Akibatnya mutex dipegang terlalu lama (rata-rata 1,0–1,3 ms, maks 58 ms, sedangkan event BASSMIDI-nya hanya 34 µs).
- Callback audio terblokir (renderLockWait maks 55 ms → xrun), dan scheduler tertahan serial (lateness rata-rata 19–37 ms, maks 121–196 ms).

**Prediksi bila H6 benar:** mode OPTIMIZED menurunkan nilai-nilai berikut tanpa mengubah satu pun event MIDI:
- `synthMutexHold_styleNoteOn`;
- `renderLockWait`;
- `overBudget`;
- xrun;
- lateness scheduler.

## 2. Ringkasan perubahan

| Bagian | Isi |
|---|---|
| Mode | `pa_diag_mode.h`: `std::atomic<int>` global, **LEGACY (default)** / OPTIMIZED. Dibaca dengan *relaxed load* sekali per pemanggilan diagnostik. Hanya menggerbang diagnostik, tidak pernah menggerbang event |
| OPTIMIZED: NOTE ON (`logAudioPath`) | Penghitung murah tetap jalan: `captureChordState` (hanya saat capture di-arm), CC7/CC11 getter, `d.on`, `observeRolePcmNote`, part presence. **Dilewati:** signature drum-zone + `sf2_zones::match` + `LOGI`, `auditNoteZone` (string signature + ostringstream + `LOGI`), blok AUDIO PATH / AUDIO LIVE / DRUM PATH yang disampel (`FontGetPreset` / `FontGetInfo` + 2–3 `LOGI`), dan `LOGI` AUDIO SUMMARY (jendela ringkasan tetap di-reset) |
| OPTIMIZED: NOTE OFF | `LOGI` AUDIO OFF yang disampel dilewati (`d.off` tetap jalan) |
| Tidak ada penundaan ke thread lain | Tidak ada handle BASSMIDI, pointer font, atau string yang dibawa keluar dari lock. Tidak ada risiko *use-after-free* dari pemindahan kerja |
| Penghitung | `PA_MODE mode=… noteCallsUnderLock logsUnderNoteLock skippedDiagnostics switchesSinceStart`. Menghitung `uiLog` yang terjadi saat thread berada di NOTE ON/OFF dengan mutex terkunci (`thread_local` depth + relaxed atomics, tanpa alokasi) |
| Laporan | `LATENCY_OUTPUT_LIVE … xrunsSinceReset=…` (delta xrun sejak RESET) dan `LATENCY_KOTLIN_LATE stat=… count meanUs over32ms over64ms maxUs` untuk lateness bangun dan dispatch scheduler (jumlah bucket; tidak ada pencatatan baru) |
| UI | SF2 / STYLE INSPECTOR: tombol **`P-A AUDIO DIAG: LEGACY/OPTIMIZED`**, hanya aktif saat Style berhenti (ViewModel juga menolak saat playing). RESET LATENCY sekarang juga mereset penghitung P-A dan baseline xrun. Tidak ada kontrol baru di Live Control |
| Guard | `tools/test_midi_song_guard.py`: pengecualian sempit untuk 4 file P-A, ditambah pin. `pa_mode::optimized()` hanya boleh di 2 tempat. Penghitung tetap sebelum return OPTIMIZED. Dispatch NOTE ON tidak bergantung mode. `pa_mode` tidak boleh muncul di `arranger/`, `com/yourapp/arranger`, atau `midi/`. File native tetap aturan ADDITIVE (tanpa baris dihapus) |

## 3. File yang berubah (vs `b66ad9f`)

| File | Perubahan |
|---|---|
| `app/src/main/cpp/pa_diag_mode.h` | baru |
| `app/src/main/cpp/bassmidi_player.cpp` | +13 / −0 |
| `app/src/main/cpp/audio_engine.h` / `.cpp` | +5 / +15 (`paResetCounters`, `setPaDiagnosticMode`, `xrunsSinceReset`, baris `PA_MODE`) |
| `app/src/main/cpp/native_lib.cpp` | +5 (JNI set/get/reset) |
| `NativeAudioBridge.kt` / `AudioEngineManager.kt` | +3 / +5 |
| `MainViewModel.kt` | +9 (toggle ditolak saat playing, `latencyResetAll`) |
| `Sf2StyleInspectorScreen.kt` | +10 / −1 (tombol P-A; RESET LATENCY → `latencyResetAll`) |
| `PcmSessionControls.kt` | +6 (`meanUs`, `atLeast`, baris `LATENCY_KOTLIN_LATE`) |
| `tests/pa_mutex_ab_test.cpp`, `tools/test_pa_mutex_ab.py` | baru |
| `tools/test_midi_song_guard.py` | +15 / −1 (pengecualian + pin P-A) |

**Tidak diubah:**
- CASM/NTT/RTR, tempo/PPQ, parser;
- `StyleSequencer` (hash P1 tetap);
- drum map, SF2/preset, voice allocation (1000), routing, NOTE OFF, STOP/Fill/Main/ACMP;
- workflow CI.

## 4. Analisis keselamatan mutex dan thread

- **Event identik:** `send(channel, MIDI_EVENT_NOTE, …)` di `noteOn`/`noteOff` tidak bergantung mode (dipin guard). Semua yang dilewati OPTIMIZED terjadi **setelah** event dikirim dan hanya membaca state atau menulis struktur diagnostik (`drumZoneSignatures_`, `noteZoneRows_`, jendela sampel).
- **Lifetime:** tidak ada kerja yang dipindah keluar lock. OPTIMIZED hanya *tidak mengerjakan* blok diagnostik, jadi tidak ada akses BASSMIDI setelah lock dilepas.
- **Ganti mode:** hanya saat berhenti (UI + ViewModel). Toggle di tengah not pun aman secara memori: atomic relaxed, dan diagnostik yang dilewati tidak punya invariant lintas-not. Ini diuji dengan TSan (§5).
- **Deadlock:** OPTIMIZED tidak menambah lock. Justru `DebugLog.logLock` (yang di LEGACY diambil dari dalam mutex synth lewat JNI) tidak disentuh lagi dari note path.
- **Diagnostik yang hilang di OPTIMIZED:** baris DebugLog `AUDIO PATH / AUDIO LIVE / DRUM PATH / DRUM ZONE / AUDIO OFF / AUDIO SUMMARY / NOTE ZONE OBSERVED`, dan isi bagian NOTE ZONES di SAVE REPORT. Penghitung part presence, latency probe, dan PCM meter tetap ada.

## 5. Tes otomatis (HOST, `79d36a9`)

| Tes | Hasil | Isi |
|---|---|---|
| `tools/test_pa_mutex_ab.py` (baru): plain -O2 | **PASS** | 5145 event BASSMIDI (3000 NOTE ON, 2120 NOTE OFF, 25 controller/program/panic). Pola mirip FunkPopRock: drum + snare, Bass, Chord, Pad, Phrase, ganti preset di tengah, `allNotesOff`. **LEGACY = OPTIMIZED byte-per-byte** (channel, event, nilai = note/velocity/program/bank/CC, urutan). LEGACY = LEGACY (kontrol). Penghitung presence identik. Critical section sama (5120). `logsUnderNoteLock` LEGACY = 77, **OPTIMIZED = 0**. `skipped` OPTIMIZED = 5120, LEGACY = 0. Default = LEGACY |
| Sama, dengan ASan + UBSan | **PASS** | Tanpa use-after-free / UB |
| Sama, dengan TSan | **PASS** | 0 peringatan. 3 thread (render 45k×, not, toggle 6k×) selesai: tanpa deadlock |
| Guard isolasi (worktree bersih) | **PASS** | + `P-A GUARD PASS` |
| `test_voice_resolver.py` (semua tes native yang menautkan `bassmidi_player.cpp`: f03 lifecycle, resolver, audio path, zone, drum kit, bank translation, accompaniment presence, percussion fidelity, role PCM, snapshot liveness, drum shadow) | **PASS (tes native)** | Langkah terakhir (`test_drum_shadow_guards.py`, guard hash lama) **FAIL juga di baseline `b66ad9f`**, karena StyleSequencer P1. Tidak berkaitan dengan P-A (9 baris PASS identik dengan baseline) |
| Device Routing native, Song native | PASS | |
| CPV JVM | PASS | |
| R1–R5 `FillNoteReleaseP1RtTest` (ROSITA, EnglishWaltz2) | PASS (5 + 5) | |
| R6: Recorder V1B (1), V1C (2), Recorder parity (1), E343 SHORT_GATE (13), E343 AUTO (9), Device Routing (10), Transpose/ACMP/LEFT (18), MIDI IN split (7) | PASS | |
| Tes A/B P-A di CI | NOT TESTED | Workflow sengaja tidak diubah. Tes dijalankan lokal |
| BASSMIDI asli di host | NOT TESTED | Unduhan library diblok proxy |

## 6. Perbandingan perilaku

| Aspek | LEGACY | OPTIMIZED |
|---|---|---|
| Event BASSMIDI (urutan, channel, nilai) | baseline | identik (tes host) |
| MIDI OUT | tidak tersentuh | tidak tersentuh |
| Kerja di dalam mutex per NOTE ON Style | event + penghitung + diagnostik lengkap (sampel: 2–3 log JNI + metadata) | event + penghitung |
| Log DebugLog audio-path | ada | tidak ada (dicatat oleh `skippedDiagnostics`) |
| Metrik `LatencyProbe` / PCM / presence | ada | ada |

## 7. Build APK (CI)

- Workflow: `Quick Experimental APK (MIDI Song V1A)` (`.github/workflows/midi-song-quick-apk.yml`), `workflow_dispatch` pada `experiment/bassmidi-pa-mutex-ab`. Workflow tidak diubah.
- Run: `42215796639`, https://github.com/pulicarpus/YamahaArranger/actions/runs/42215796639
- **Status: completed / success** untuk semua job:
  - Quick APK (guard + compile kedua ABI ARM + verifikasi APK = commit ini);
  - Quick tests (guard, CPV JVM, Recorder V1B/V1C, Song native, Real BASSMIDI Song XG, E343 SHORT_GATE, MIDI IN split, Transpose/ACMP/LEFT, E343 AUTO, Device Routing);
  - UI tests;
  - Quick report.
- **APK artifact:** `SONG-QUICK-APK-experiment-bassmidi-pa-mutex-ab-79d36a9bc68173740da596d5231779bc0b8664a0`
  - id `13502175156`, 14,7 MB, digest zip `sha256:7b80b7faa3c3599c21157f366e42f53db9f1d6ee5f3acb3a929ba3257edc9eb5`, berlaku sampai 2027-02-04;
  - link: https://github.com/pulicarpus/YamahaArranger/actions/runs/42215796639/artifacts/13502175156
- **Bukti APK:** `SONG-QUICK-apk-evidence-42215796639` (id `13501471106`). APK debug, ARM64-v8a + armeabi-v7a.

## 8. Prosedur uji Redmi Pad

**Konfigurasi (sama untuk semua run):**
- Style `FunkPopRock.S542.prs`, SF2 `ColomboGMGS2_BM.sf2`;
- Main D, ACMP ON, semua part aktif (tanpa mute), 94 BPM;
- **OUTPUT = INTERNAL ONLY** (MIDI OUT off);
- akor sama setiap run (misalnya C, tahan; atau pola tetap C → F → G per 2 bar).

**Urutan A-B-B-A** (setiap run 60 s):
1. STOP. Buka SF2 / STYLE INSPECTOR. Tekan **P-A AUDIO DIAG** sampai **LEGACY**. **RESET LATENCY**.
2. Mainkan 60 s. **SAVE REPORT**. STOP.
3. P-A → **OPTIMIZED**. RESET LATENCY. Mainkan 60 s dengan pola akor sama. SAVE REPORT. STOP.
4. Ulangi OPTIMIZED sekali lagi, lalu LEGACY sekali lagi (B, A).
5. Jangan mute atau mengubah setting di tengah run. Catat juga kesan dengar setiap run.

**Baris yang dibandingkan:**
- `PA_MODE`: mode dan `logsUnderNoteLock` (OPTIMIZED harus 0);
- `LATENCY_KOTLIN_LATE stat=styleEventWakeLatenessBeforeLifecycle`;
- `styleNoteLockWait`, `styleNoteDispatch`, `synthMutexHold_styleNoteOn`, `renderLockWait`, `callbackTotal`;
- header `overBudget=`, `callbackIntervalOver150pctOfBudget`;
- `xrunsSinceReset`;
- `activeBassVoices`.

**Tabel hasil (diisi setelah uji):**

| Ukuran | A1 LEGACY | B1 OPT | B2 OPT | A2 LEGACY |
|---|---|---|---|---|
| Kesan dengar: rapi? tempo nyangkut? | | | | |
| `logsUnderNoteLock` | | | | |
| Lateness scheduler: rata-rata / maks (ms) | | | | |
| Event > 32 ms / > 64 ms | | | | |
| `styleNoteLockWait` rata-rata / p99 / maks | | | | |
| `synthMutexHold_styleNoteOn` rata-rata / maks | | | | |
| `renderLockWait` maks | | | | |
| `callbackTotal` maks | | | | |
| `overBudget` / `>150 %` | | | | |
| `xrunsSinceReset` | | | | |
| `activeBassVoices` rata-rata / maks | | | | |

## 9. Kriteria PASS/FAIL eksperimen

- **H6 TERBUKTI** jika, di kedua run OPTIMIZED dibanding kedua run LEGACY:
  - `logsUnderNoteLock` = 0;
  - `synthMutexHold_styleNoteOn` rata-rata turun ≥ 5× (dari ≈ 1 ms ke ≤ 200 µs) dan maks < 5 ms;
  - `renderLockWait` maks < 5 ms;
  - `xrunsSinceReset` turun ≥ 50 %;
  - lateness scheduler rata-rata **dan** jumlah > 64 ms turun ≥ 50 %;
  - perbedaannya konsisten di urutan A-B-B-A.
- **H6 SEBAGIAN:** mutex hold dan xrun turun, tetapi lateness scheduler tetap tinggi. Artinya sisa kopling berasal dari render yang memegang mutex 50–70 % waktu (H1). Kandidat berikutnya P-B/P-C (tidak dikerjakan di sini).
- **H6 GUGUR:** tidak ada perbedaan berarti, atau perbedaannya tidak konsisten A-B-B-A.
- **Eksperimen INVALID:**
  - OPTIMIZED terdengar berbeda secara musikal (tes host menyatakan event identik; kalau terdengar beda, laporkan);
  - mode atau setting berubah di tengah run.

## 10. Risiko regresi

- **LEGACY (default):** perilaku lama. Satu-satunya tambahan di jalur not: penghitung relaxed atomic + `thread_local` depth (≈ ns). Tombol RESET LATENCY sekarang juga mereset penghitung P-A dan baseline xrun.
- **OPTIMIZED:** baris diagnostik audio-path tidak ada. Alat yang membaca baris tersebut dari DebugLog (diagnostik lama) tidak berguna selama mode ini aktif.
- Mode tidak disimpan (kembali ke LEGACY saat aplikasi dibuka ulang).
- APK debug, bukan rilis.

## 11. GO / NO-GO

- **GO:** uji fisik A-B-B-A (§8) dengan APK ini.
- **NO-GO:**
  - patch produksi (misalnya memindahkan diagnostik keluar mutex secara permanen) sebelum hasil A/B memenuhi kriteria §9;
  - P-B/P-C sebelum hasil P-A;
  - merge.
