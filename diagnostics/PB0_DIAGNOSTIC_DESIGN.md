# P-B0: DESAIN DIAGNOSTIK (READ-ONLY) — sumber lateness scheduler saat drum + accompaniment

- **Status:** DESAIN SAJA. Belum ada patch, build, atau perubahan perilaku. Implementasi menunggu persetujuan pengguna.
- **Kode yang diaudit:** `experiment/bassmidi-pa-mutex-ab` @ `a43af9f` = produksi `b66ad9f` (+P1) + eksperimen P-A (`79d36a9`).
- **Baseline playable:** APK P-A (run `42215796639`) dengan mode LEGACY = perilaku produksi.
- **Dasar:**
  - `FUNKPOPROCK_DEVICE_LATENCY_EVIDENCE.md`: data Redmi Pad;
  - `FUNKPOPROCK_CROSS_OUTPUT_TIMING_ROOT_CAUSE.md`;
  - `BASSMIDI_PA_MUTEX_AB_EXPERIMENT.md`.
- **Referensi PadLab StageLab Tahap 1–3:** dokumen ini **tidak tersedia** di repo atau di repo lain yang bisa diakses sesi ini (dicari lewat `search/code` untuk "PadLab" dan "StageLab": 0 hasil). Perbandingan di §7 memakai prinsip umum producer → queue → render-consumer, bukan implementasi PadLab. Tidak ada kode yang disalin.

**Label:**
- **CODE**: dibaca di sumber, dengan path:baris di branch di atas.
- **DEVICE**: angka dari laporan Inspector Redmi Pad 2026-11-06.
- **DESIGN**: usulan.

## 1. Call graph terverifikasi (CODE)

```
STYLE (per event)                                                                                  thread
StyleSequencer.playbackJob = scope.launch(Dispatchers.Default)            StyleSequencer.kt:712      T-SCHED (pool Default; bisa pindah thread setiap resume)
 └ playOnce(section)                                                       :1299
    ├ gabung semua part, sortir per tick (ON sebelum OFF di tick sama)     :1320
    └ untuk setiap event:
       ├ waitForNextBarOrEvent → tenggat absolut tickDeadlineNanos(tick)   :615-640, :75-92   (tunggu ms, sisa <1 ms langsung kirim)
       ├ LatencyProbe.schedulerWake(now, targetNanos)                      :1377   ← TITIK UKUR yang sudah ada
       ├ lifecycle { synchronized(noteLifecycleLock) {                     :213-224
       │     CASM selectPolicy/transform, ledger, chordOff lama, stageIo(...) }  :1380-1540
       │  } → setelah monitor dilepas: OrderedNoteIo.flush                 :1563-1603 (tiket per (ch,pitch), spin/park; gate RW untuk STOP)
       │     └ run(): audioEngine.noteOnStyleChannel → JNI                 :1533-1535
       │            └ BassMidiPlayer::noteOn: SynthLock(mutex_)            bassmidi_player.cpp:1231-1302
       │                 send(MIDI_EVENT_NOTE) → BASS_MIDI_StreamEvent     :618, :1284
       │                 logAudioPath (LEGACY: diagnostik + uiLog/JNI)     :1120
       │            midiInputManager.sendNoteOn → MidiMixerOutput.noteOn (@Synchronized) → MidiInputPort.send   MidiMixerOutput.kt:105, MidiInputManager.kt:39-42, :237-241
       └ LatencyProbe.styleEventDispatched(now, targetNanos)              :1449   ← TITIK UKUR yang sudah ada
 NOTE OFF drum: tidak di ledger → dibuang (tidak ke BASSMIDI / MIDI OUT)    :1474-1483
 Batas section: applyVoicesProbed → applyVoicesFromCasm → setChannelPreset/Mixer (mutex_, Preset hold 7–10 ms DEVICE)   :1002-1008, bassmidi_player.cpp:1353-1354, :1511-1514

MIDI IN (keyboard)
MidiNoteReceiver.onSend (LatencyProbe.onSendEnter/Exit)                     MidiInputManager.kt:120-123   T-MIDI (dispatch thread Android MIDI)
 └ ArrangerBrain.onKeyboardNoteOn: synchronized(keyboardLock)               ArrangerBrain.kt:364-366
    ├ RIGHT/LEFT: engine JNI (mutex_) + MIDI OUT (router monitor) di dalam keyboardLock
    └ ACMP: ChordDetector → settle: scope.launch{ delay(15 ms) }            :570-590              T-MAIN (viewModelScope)
          → applyChordNow → sequencer.currentChord = eff                    :335-344
          → handleChordChange → retargetActive (lifecycle{} per not aktif)  StyleSequencer.kt:874, :886   T-MAIN (JNI + USB per not)

AUDIO
Oboe/AAudio onAudioReady (LowLatency, Shared di Redmi Pad, burst 256)      audio_engine.cpp:209-260      T-AUDIO (prioritas tinggi)
 └ BassMidiPlayer::render: SynthLock(mutex_) SELAMA BASS_ChannelGetData     bassmidi_player.cpp:2369-2436 (+ renderPercussion :2814)
    → event yang sudah di-StreamEvent diterapkan di awal decode berikutnya (kuantisasi ke blok 256 frame = 5,33 ms)
 └ internal mute (OUTPUT MIDI OUT) = memset SETELAH render                  audio_engine.cpp:239-247
```

## 2. Thread ownership map (CODE)

| Thread | Isi | Prioritas / sifat | Menyentuh |
|---|---|---|---|
| **T-SCHED** | Coroutine `Dispatchers.Default`: tunggu tick, CASM, ledger, dispatch not Style, voice apply di batas section | Normal. **Tidak tetap**: setelah setiap suspend bisa lanjut di worker lain | `musicalClockLock`, `noteLifecycleLock`, OrderedNoteIo, `mutex_` (JNI), router monitor + socket USB, `DebugLog.logLock` |
| **T-MIDI** | `MidiReceiver.onSend` Android: parse, RIGHT/LEFT, deteksi akor | Thread dispatch MIDI service | `keyboardLock`, `mutex_`, router monitor, `logLock` |
| **T-MAIN** | Settle akor (15 ms), `applyChordNow`, retarget semua not aktif, UI, Inspector | Main looper (UI) | `keyboardLock` (singkat), `noteLifecycleLock`, `mutex_`, router, `logLock` |
| **T-AUDIO** | Callback Oboe → render BASSMIDI → gain/clamp/mute → metronom | Real-time (AAudio) | `mutex_` (selama decode) |
| T-METRO | Planner metronom FOLLOW (membaca jam Style) | Normal | `musicalClockLock` (baca) |
| T-UI-log | Panel log: `DebugLog.getAll()` setiap 250 ms | UI | `logLock` |
| E343DrumNoteOffGate | Timer SHORT_GATE (EnglishWaltz2 saja) | Daemon | router |

## 3. Mutex / critical-section ownership map (CODE + DEVICE)

| Lock | Pemegang | Isi critical section | Durasi terukur (DEVICE, P-A LEGACY) | Siapa yang menunggu |
|---|---|---|---|---|
| **`BassMidiPlayer::mutex_`** (`f12::SynthLock`) | T-AUDIO (render), T-SCHED/T-MAIN (not Style), T-MIDI (not keyboard), T-SCHED (preset/mixer di batas section) | Render: seluruh `BASS_ChannelGetData` + lane perkusi. NOTE ON: event + penghitung + (LEGACY) diagnostik/log JNI. Preset: map font | Render 2,7–3,8 ms per callback 5,3 ms (50–70 %). NOTE ON rata-rata 1,0–1,3 ms, maks 58 ms. Preset 7,7–10,3 ms | NOTE ON menunggu: rata-rata 2–3 ms, p99 16 ms. Render menunggu: maks 55 ms → xrun |
| `noteLifecycleLock` | T-SCHED, T-MAIN | Ledger + keputusan CASM (sejak P1 tanpa I/O di dalamnya) | Belum diukur per event (`f12Timing` hanya saat di-arm) | T-SCHED ↔ T-MAIN saat retarget |
| OrderedNoteIo (tiket per (ch,pitch) + gate RW) | Thread yang memutuskan | Menjamin urutan ON/OFF per kunci. STOP mengambil write-gate | `maxTurnWaitNanos` (maks saja, tidak dilaporkan) | Thread lain pada kunci yang sama |
| **Router `MidiMixerOutput` (@Synchronized)** | T-SCHED, T-MIDI, T-MAIN | Ledger MIDI OUT + **`MidiInputPort.send` (tulis socket, bisa blok)** | Belum diukur | Semua pengirim MIDI OUT |
| `keyboardLock` | T-MIDI, T-MAIN | Not keyboard (JNI + USB di dalamnya), status akor | DEVICE: wait rata-rata 3 µs (sedikit input) | T-MAIN (settle akor) |
| `DebugLog.logLock` | Semua, termasuk native `uiLog` lewat JNI (LEGACY: dari dalam `mutex_`) | Deque 30 + 30000 baris | Belum diukur | T-UI-log 4×/s |
| `musicalClockLock` | T-SCHED, T-METRO | Aritmetika jam | Pendek | — |
| `voiceMutex_` (fallback tanpa SF2) | T-AUDIO | Synth cadangan | Tidak aktif dengan SF2 | — |

**Rantai kopling yang relevan:**
- T-SCHED → `mutex_` ← T-AUDIO (render 50–70 %): scheduler menunggu render.
- T-SCHED → router → socket USB: di mode BOTH, MIDI OUT menambah biaya serial.
- (LEGACY) `mutex_` → JNI → `logLock`: callback audio bisa ikut menunggu.

## 4. Titik pengukuran

### 4.1 Yang sudah ada (CODE) dan cukup tidaknya

| Titik | Nama yang ada | Granularitas | Cukup? |
|---|---|---|---|
| Event due | `targetNanos` (dihitung, tidak disimpan) | per event | ✗ tidak diekspor per event |
| Scheduler wake | `styleEventWakeLatenessBeforeLifecycle` | histogram | ~ agregat saja |
| Dispatch end | `styleEventDispatchLatenessAfterLifecycle` | histogram | ~ agregat saja |
| Synth lock wait | `styleNoteLockWait` (native) | histogram | ~ tidak dipisah per peran |
| BASSMIDI apply (event diterima) | `styleNoteDispatch` | histogram | ~ |
| Event diterapkan oleh render | `styleNoteQueuedToRenderStart`, `…ToBufferReady` | histogram, hanya not pertama per periode render | ~ |
| Hold per jenis | `synthMutexHold_*` | histogram | ✓ |
| Callback start/end | `callbackTotal`, `callbackStartToStartInterval`, `CALLBACK_TIMELINE` (48 terakhir) | histogram + ring kecil | ✓ untuk audio |
| xrun | `xrunsSinceReset` (P-A) | counter | ✓ |
| Log di bawah lock | `PA_MODE logsUnderNoteLock` (P-A) | counter | ✓ |

**Kesimpulan audit:** timestamp yang ada **cukup untuk menunjukkan adanya** keterlambatan, tetapi **tidak cukup untuk menguraikannya per event end-to-end**. Celahnya:
- **G1:** tidak ada korelasi per event. Semua angka adalah histogram terpisah. `AudioPathOrigin.id` sebenarnya sudah dibawa sampai native, jadi korelasi bisa dibuat tanpa mengubah event.
- **G2:** tidak ada pemisahan per peran (drum ch8/9 vs accompaniment ch10–15). Padahal pertanyaannya justru "kenapa gabungan".
- **G3:** tidak bisa membedakan *wake jitter* (thread bangun terlambat oleh OS/pool) dari *queueing* (antre di belakang event sebelumnya dalam burst yang sama) dan *biaya sendiri* (tunggu lock + hold + USB).
- **G4:** waktu `MidiInputPort.send` dan tunggu router monitor tidak diukur.
- **G5:** tunggu `noteLifecycleLock` dan giliran OrderedNoteIo per event tidak diekspor.
- **G6:** identitas dan prioritas thread T-SCHED (pindah worker) tidak tercatat.

### 4.2 Titik instrumentasi yang diusulkan (DESIGN; observer saja)

Satu **ring buffer berukuran tetap, dialokasikan sebelum capture**, satu baris per event Style. Kotlin: `LongArray` per field; native: `uint64_t[]`. Penulisan memakai indeks atomik, tanpa lock, tanpa alokasi, tanpa log. Hanya aktif saat **capture di-arm** (misalnya 60 s, seperti CAPTURE CHORD), dan diekspor saat SAVE REPORT ketika playback berhenti.

| # | Field | Tempat (CODE) | Clock |
|---|---|---|---|
| T0 | `id`, `ch`, `role` (R1/R2/Bass/Chord1/Chord2/Pad/Phrase), ON/OFF, `tick` | `playOnce` sebelum lifecycle | — |
| T1 | `dueNs` = `targetNanos` | :1374 | monotonic |
| T2 | `wakeNs` (saat `waitForNextBarOrEvent` kembali) | :1358 | monotonic |
| T3 | `prevDispatchEndNs`: akhir dispatch event sebelumnya di loop yang sama | lokal di loop | monotonic |
| T4 | `lifecycleEnterNs` / `monitorAcquiredNs` / `monitorReleasedNs` | `lifecycle()` :213-224 | monotonic |
| T5 | `ioTurnWaitNs` (OrderedNoteIo) | `execute` :1593-1597 | monotonic |
| T6 | `jniEnterNs` / `jniExitNs` | sekitar `noteOnStyleChannel` :1534 | monotonic |
| N1 | `nativeEnter` / `locked` / `eventSent` / `released` (per `origin.id`) | `noteOn` :1231-1302 (memperluas probe yang ada) | steady_clock = monotonic |
| N2 | `consumedByRenderStart` (render pertama setelah `eventSent`) | `render` :2369 (stempel per blok + id terakhir yang dikirim) | monotonic |
| T7 | `usbEnterNs` / `routerAcquiredNs` / `usbExitNs` | `MidiMixerOutput.noteOn` :105 + `write` | monotonic |
| T8 | `threadId`, `threadPriority` (sekali per perubahan thread) | loop T-SCHED | — |

**Turunan per event (dihitung di ekspor, bukan saat real-time):**
- `wakeJitter` = T2 − max(T1, T3);
- `queueing` = max(0, T3 − T1);
- `lockWait` = N1.locked − N1.enter;
- `hold` = N1.released − N1.locked;
- `usb` = T7.exit − T7.enter;
- `ownCost` = T6.exit − T4.enter (+ usb);
- `endToEndDispatch` = N1.eventSent − T1;
- `endToEndAudible≈` = N2 − T1 (batas bawah; tanpa latensi output Oboe);
- `lateness` = T6.exit − T1 = wakeJitter + queueing + ownCost (cek identitas).

## 5. Risiko instrumentasi

| Risiko | Mitigasi |
|---|---|
| Efek pengamat (nanoTime ±25–50 ns × ±12 stempel per event; atomik) | Diaktifkan hanya saat capture. Run kontrol "capture OFF" vs "capture ON" wajib, dengan selisih p50 lateness < 1 ms |
| Alokasi / GC | Array dialokasikan saat arm, ukuran tetap (misalnya 16384 event × 16 long ≈ 2 MB Kotlin + ≈ 0,5 MB native). Overflow dihitung, tidak dialokasikan ulang |
| Korelasi id Kotlin ↔ native salah | `origin.id` sudah dibawa. Pakai ring native per id, join saat ekspor. Hitung yang tidak cocok |
| T-SCHED pindah thread | Jangan memakai `ThreadLocal` untuk data ring. Simpan `threadId` sebagai data |
| Stempel di dalam `mutex_` menambah hold | Hanya 2 stempel (locked, eventSent) di dalam lock. Sisanya di luar |
| Ekspor mengganggu playback | Ekspor hanya saat STOP (seperti tombol yang sudah ada) |
| Mengubah event | Tes host wajib seperti P-A: riwayat BASSMIDI dan MIDI OUT identik, capture ON vs OFF |
| Guard isolasi | Pengecualian sempit + pin, seperti P1/P-A |
| Interaksi dengan P-A | Semua run diulang di LEGACY dan OPTIMIZED |
| Clock domain | Kotlin `System.nanoTime` dan native `steady_clock` = CLOCK_MONOTONIC (CODE: `latency_probe.h:8-9`) |

## 6. Rencana pengujian A/B (DESIGN)

**Baseline tetap:**
- APK P-A `79d36a9` mode LEGACY (= produksi) dan OPTIMIZED.
- Build P-B0 = P-A + instrumentasi, yang harus lolos tes "event identik".

**Konfigurasi perangkat:**
- Redmi Pad, FunkPopRock S542, `ColomboGMGS2_BM.sf2`, 94 BPM;
- OUTPUT INTERNAL (dan subset BOTH);
- ACMP ON, akor sama;
- setiap run 60 s, urutan **A-B-B-A**;
- tanpa mengubah setting di tengah run.

| ID | Skenario | Cara | Pertanyaan |
|---|---|---|---|
| S1 | Drum saja | Mute ch11–16 | Lateness dasar dari drum |
| S2 | Accompaniment saja | Mute ch9–10 | Lateness dasar dari accompaniment |
| S3 | Gabungan | Semua aktif | Apakah S3 > S1 + S2 (non-linear)? |
| S4 | Akor cepat | C→F→G→C setiap 500 ms, lalu 250 ms | Kontribusi retarget T-MAIN |
| S5 | Fill → Main | Main C → Main D setiap 4 bar (Fill DD), 10× | Biaya preset/voice apply di batas (Preset hold 7–10 ms) |
| S6 | STOP saat Fill + stuck notes | STOP di tengah Fill DD, 5× | Pemeriksaan stuck |
| S7 (subset) | S3 dengan OUTPUT BOTH | MIDI OUT ke E343 | Biaya `usb` / router |

**Metrik** (p50 / p95 / p99 / maks + hitungan > 32 ms dan > 64 ms, total dan per peran):
- lateness;
- `wakeJitter`, `queueing`, `lockWait`, `hold`, `usb`, `ownCost`;
- `endToEndDispatch`, `endToEndAudible≈`;
- `renderLockWait`, `callbackTotal`, `overBudget`;
- `xrunsSinceReset`, `activeBassVoices`.

**Pemeriksaan stuck notes:**
- Setelah STOP + 2 s:
  - ledger Kotlin (`activeTransposedNotes`) = 0;
  - router MIDI OUT `notes` = 0 (`midiOutReport`);
  - native `audioDiagnostics_.pending()` per channel ≤ ekor drum;
  - `activeBassVoices` turun ke ≈ 0 (sisa release tail < 1 s).
- Selama run: NOTE ON tanpa OFF > 8 s pada ch11–16 (dari ring) = 0.

**Analisis:**
- Untuk setiap skenario, uraikan lateness p95/p99 menjadi persentase `wakeJitter` / `queueing` / `lockWait+hold` / `usb`.
- Bandingkan S3 dengan S1 + S2.
- Konsistensi A-B-B-A: selisih antar-ulangan < 25 %.

## 7. Prinsip producer → queue → render-consumer (umum)

**Model sekarang (CODE):** producer (T-SCHED) **langsung** memanggil synth di bawah mutex yang dipegang consumer (render) selama sintesis. Waktu kirim = waktu bunyi, dibulatkan ke blok render berikutnya. Akibatnya kedua sisi saling menunggu, dan timing ikut terganggu jitter thread.

**Prinsip umum alternatif (DESIGN untuk P-B1, belum diimplementasikan):**
- Producer menulis event **bertimestamp** (sampel/ns tujuan) ke antrean **lock-free SPSC** berukuran tetap.
- Consumer (callback audio) mengosongkan antrean di awal blok dan menerapkan event pada offset sampel yang benar.
- Mutex tidak lagi dipakai bersama di jalur not.
- Dua sifat penting:
  1. **tidak ada lagi tunggu lock** di T-SCHED (menghilangkan `lockWait` dan *queueing* akibat synth);
  2. dengan **lookahead** (event dikirim X ms sebelum due), timing menjadi sample-accurate dan **tidak bergantung pada wake jitter**.
- Lookahead menambah latensi respons perubahan akor (retarget not yang sudah dijadwalkan). Ini perlu kebijakan pembatalan, dan itu desain terpisah.
- BASSMIDI punya pengiriman event berkelompok/bertimestamp (`BASS_MIDI_StreamEvents`). Flag dan satuan waktunya **belum diverifikasi** untuk penggunaan ini dan harus dicek di dokumentasi BASSMIDI sebelum P-B1.
- P-B0 **tidak** membuat antrean. P-B0 hanya mengukur apakah prinsip itu menyasar komponen lateness yang dominan.

## 8. Kriteria GO / NO-GO untuk P-B1

**Prasyarat valid** (semua wajib):
- tes host "event identik, capture ON vs OFF" PASS;
- overhead capture: selisih p50 lateness < 1 ms dan p99 < 3 ms (run kontrol);
- korelasi id Kotlin ↔ native ≥ 99 %;
- A-B-B-A konsisten (< 25 %).

**GO P-B1 (antrean + render-consumer)** jika, pada S3 dengan mode **OPTIMIZED**:
1. lateness p95 > 10 ms **atau** event > 64 ms > 1 %; **dan**
2. `lockWait + hold + queueing akibat synth` ≥ 50 % dari lateness p95/p99; **dan**
3. S3 menunjukkan efek non-linear (p95 S3 > 1,5 × max(S1, S2)) yang dijelaskan oleh komponen synth; **dan**
4. tidak ada stuck notes di S6.

**NO-GO P-B1, arah lain:**

| Hasil | Kesimpulan |
|---|---|
| `wakeJitter` ≥ 50 % | Penyebabnya penjadwalan thread (`Dispatchers.Default`, prioritas). Kandidat: thread scheduler khusus berprioritas tinggi, atau lookahead + timestamp. Desain terpisah |
| `usb` ≥ 30 % di S7 | Kandidat P-B (writer MIDI OUT terpisah) |
| Lateness hanya di batas section (S5) | Kandidat: preset/voice apply di luar jalur kritis |
| OPTIMIZED saja: p95 < 10 ms, > 64 ms ≈ 0, xrun ≈ 0, dan terdengar rapi | **P-A cukup.** P-B1 tidak diperlukan |
| Data tidak valid (prasyarat gagal) | Ulangi P-B0. Jangan memutuskan |

## 9. Langkah berikut (menunggu persetujuan)

1. **Hasil uji fisik P-A (A-B-B-A) sebaiknya didapat dulu.** Kalau OPTIMIZED sudah memenuhi baris "P-A cukup", P-B0 boleh disederhanakan.
2. Setelah disetujui, implementasi P-B0 di branch terpisah (misalnya `experiment/pb0-latency-trace`) dari `79d36a9`:
   - ring buffer + ekspor;
   - tes host event identik + overhead;
   - guard;
   - APK lewat workflow yang ada.
3. Tanpa antrean baru, tanpa mengganti BASSMIDI, tanpa merge.
