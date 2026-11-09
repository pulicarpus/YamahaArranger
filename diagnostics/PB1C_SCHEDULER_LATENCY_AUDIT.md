# P-B1c: AUDIT READ-ONLY KETERLAMBATAN SCHEDULER (±21 ms)

- **Mode:** read-only. Tidak ada queue diimplementasikan. Tidak ada perubahan kode, merge, atau perubahan baseline playable.
- **Kode yang diaudit:** `experiment/pb1a-drum-bind-cache` @ `373b692` (= produksi `b66ad9f` + P1 + P-A + P-B0.1 + P-B1a).
- **Bukti perangkat:**
  - `diagnostics/pb1a_device_20261109/` (FunkPopRock, P-A OPTIMIZED, 2026-11-09);
  - `diagnostics/pb01_device_20261108/`.
- **Label:**
  - **CODE**: path:baris;
  - **DEVICE**: dari laporan;
  - **INFERRED**: tafsiran;
  - **UNKNOWN**: belum terukur.

## 1. Ringkasan

1. **Yang terukur (DEVICE, run 2026-11-09):**
   - lateness bangun scheduler rata-rata **21 ms**, maks 112–132 ms; **8–9 % event > 64 ms**;
   - setiap NOTE ON Style dari scheduler menunggu mutex synth rata-rata **0,9–1,1 ms** (p95 ≤ 4 ms, maks 20 ms), NOTE OFF 0,8–1,0 ms;
   - hold oleh NOTE ON hanya 44–50 µs;
   - render memegang mutex yang sama **2,3–2,6 ms per callback 5,33 ms (42–48 %)**.
2. **Kopling (CODE):** scheduler memanggil BASSMIDI secara serial (`StyleSequencer.kt:1533-1540` → JNI → `BassMidiPlayer::noteOn`, `SynthLock mutex_`). Setiap panggilan menunggu bila render sedang decode.
   - Waktu tunggu per detik ≈ (4557 × 0,88 + 2979 × 0,76) ms / 76 s ≈ **83 ms per detik (8 %)** pada thread scheduler (ARITH).
   - Dalam satu cluster 15–25 event per tick, tunggu itu menumpuk: ±8–12 ms rata-rata, puluhan ms saat cluster beruntun (INFERRED).
3. **Kopling ini menjelaskan sebagian, bukan seluruh 21 ms (INFERRED).** Sisanya belum terpisah:
   - wake jitter `Dispatchers.Default`;
   - kerja Kotlin per event (CASM, ledger, OrderedNoteIo);
   - antre di belakang voice apply atau retarget.

   **UNKNOWN** porsinya. Metrik `styleEventWakeLateness` (semua event, 21 ms) dan `styleEventDispatchLateness` (event yang sampai titik dispatch, 11–12 ms) mengukur populasi berbeda, sehingga tidak bisa langsung dikurangkan.
4. **Bounded queue + single BASSMIDI owner** (prinsip producer → ring → consumer) **menghapus tunggu mutex di jalur not**, tanpa mengubah kapan not berbunyi. Event sekarang pun baru diterapkan BASSMIDI pada decode berikutnya: `styleNoteQueuedToRenderStart` rata-rata 1,7–2,0 ms (DEVICE).
   - Manfaat maksimal ≈ porsi tunggu-mutex dari lateness. Itu belum pasti ≥ 50 %.

**GO/NO-GO (§8):**
- GO untuk satu langkah ukur kecil (**P-B0.2**) yang memisahkan lateness menjadi idle-jitter / backlog-JNI / backlog-Kotlin;
- **P-B1c GO bersyarat** pada hasil itu;
- NO-GO untuk implementasi queue sekarang.

## 2. Thread ownership (jalur not)

| Thread | Peran | Menyentuh synth | Bukti |
|---|---|---|---|
| T-SCHED (`Dispatchers.Default`, prioritas normal, bisa berganti worker) | Event Style, release, voice apply | `noteOn/Off` (± 100 panggilan per detik), `bindDrumAB`, preset/mixer | CODE `StyleSequencer.kt:712`, DEVICE role `scheduler` |
| T-MAIN (UI) | Retarget akor (per not), mixer UI, preset saat load | `noteOn/Off` (2 per not diganti), preset | CODE `ArrangerBrain.kt:575-600`, `StyleSequencer.kt:874-949` |
| T-MIDI | Not keyboard RIGHT/LEFT | `noteOn/Off` ch0–3 (role `other`) | CODE `ArrangerBrain.kt:364` |
| T-AUDIO (AAudio, real-time) | Render BASSMIDI + lane perkusi | `render` (mutex ±45 % waktu) | CODE `bassmidi_player.cpp:2383-2450` |
| Song stream | Song player | `songStream_` terpisah, mutex sama | CODE `songEvents` |

## 3. Mutex contention (DEVICE, 2026-11-09)

| Lock | Pemegang | Hold | Tunggu yang ditimbulkan |
|---|---|---|---|
| `BassMidiPlayer::mutex_` | render | 2,26–2,55 ms / callback | scheduler NOTE ON 0,9–1,1 ms rata-rata, maks 20 ms; NOTE OFF 0,8–1,0 ms |
| | scheduler NOTE ON / OFF | 44–50 / 25–29 µs | render menunggu ≤ 1–11 µs rata-rata (maks 0,4–8,5 ms) |
| | `bindDrumAB` (P-B1a OFF) | 3,95 ms | — |
| | Preset (load / voice apply) | 7–10 ms | jarang |
| `noteLifecycleLock` (Kotlin) | T-SCHED, T-MAIN | I/O di luar lock sejak P1 | UNKNOWN (tidak di-probe) |
| OrderedNoteIo (tiket per kunci) | thread pemutus | — | UNKNOWN (`maxTurnWaitNanos` tidak diekspor) |
| Router MIDI OUT | pengirim | `MidiInputPort.send` | 0 di OUTPUT INTERNAL |

**Pembacaan:** pada OPTIMIZED, sisi not hampir tidak memegang lock (µs). Yang mahal adalah **producer menunggu render**: ±45 % dari setiap 5,33 ms render memegang lock, sehingga sekitar separuh panggilan not jatuh ke dalam decode dan menunggu rata-rata ±1–2 ms.

## 4. Event ordering yang harus dipertahankan oleh queue

| Invarian | Sekarang (CODE) | Syarat untuk queue |
|---|---|---|
| Urutan ON/OFF per (channel, key) | OrderedNoteIo: tiket per kunci, eksekusi berurutan (`StyleSequencer.kt:1563-1603`) | FIFO tunggal (MPSC). Push terjadi di dalam `run()` yang sudah berurutan per kunci, jadi urutan per kunci terjaga |
| NOTE OFF tidak boleh hilang | panggilan langsung | **tanpa drop**: bila penuh, producer mengambil mutex, menguras queue, lalu menjalankan event langsung (fallback = perilaku lama) |
| Not vs Program/Bank/Mixer/`bindDrumAB` pada channel sama (voice apply di T-SCHED setelah not section lama) | langsung, berurutan di thread yang sama | Setiap operasi kontrol harus **menguras queue lebih dulu** (di bawah mutex) sebelum menerapkan dirinya. Kalau tidak, not lama bisa terdengar dengan program baru |
| STOP / panic (`allNotesOff`, `stopSession` epoch) | gate RW + epoch (`StyleSequencer.kt:1605-1608`) | `allNotesOff` membuang isi queue lalu NOTESOFF. Event epoch lama tetap dilewati di Kotlin |
| P1 release di batas section | `releaseActive` → OFF lewat io | FIFO menjaga OFF sebelum ON section baru |
| Retarget OFF lama → ON baru | dua perintah berurutan per kunci | sama (FIFO) |
| Semantik `NOTEOFF1` (instans tertua) | BASSMIDI | tidak berubah (event identik, urutan identik) |
| Readback / verifikasi drum AB di `noteOn` | dijalankan di thread pemanggil | dijalankan di consumer (thread audio atau owner); hasilnya tetap per not |
| Diagnostik di `noteOn` | LEGACY: `uiLog` + JNI di bawah lock | **wajib OPTIMIZED** (tidak boleh JNI/log di thread audio). Kalau LEGACY, jalur queue harus mati |
| Tap Recorder, MIDI OUT | di Kotlin, setelah JNI | tidak berubah (dicatat saat dispatch) |
| Keyboard ch0–3 | langsung dari T-MIDI | Tahap 1: tetap langsung (latensi live, diagnostik keyboard). Hanya ch4–15 lewat queue |

## 5. Opsi bounded queue + single BASSMIDI owner

| Opsi | Consumer | Untung | Risiko |
|---|---|---|---|
| **D1: drain di callback audio**, sebelum `SynthLock` render | T-AUDIO | Tanpa thread baru. Event masuk decode yang sama seperti sekarang. Producer tidak pernah menunggu | Callback memikul biaya `noteOn` (±45 µs × burst 20–35 ≈ 1–1,6 ms per 5,33 ms, INFERRED). Perlu batas jumlah per callback (sisa ke callback berikutnya = +5,3 ms untuk sisa itu) |
| **D2: thread "synth owner" khusus** (prioritas tinggi) yang menguras queue | thread baru | Callback audio tetap ringan | Owner masih menunggu render (±1 ms), tetapi scheduler tidak. Tambahan wake-up thread (±0,1–1 ms). Satu thread lagi untuk dikelola (STOP, lifecycle) |
| D3: batch per tick dari Kotlin (satu JNI per tick) | — | Tunggu per tick, bukan per event | Mengubah `StyleSequencer` (dibekukan, hash P1). Ditolak |

**Rancangan ring (prinsip, bukan implementasi):**
- MPSC terbatas, misalnya 1024 slot (burst terukur ≤ ±40 event per 5,33 ms);
- slot berisi `op`, `channel`, `key`, `velocity`, `AudioPathOrigin`;
- indeks atomik;
- tanpa alokasi;
- fallback penuh = jalur lama (lock + jalankan);
- satu "drain lock" agar hanya satu consumer menguras sekaligus (render / owner / operasi kontrol).

Implementasi native bisa berada sepenuhnya di `BassMidiPlayer`. Kotlin dan `StyleSequencer` tidak berubah.

## 6. Dampak yang diharapkan (INFERRED) dan batasnya

| Komponen lateness | Dihapus queue? |
|---|---|
| Tunggu mutex per not (±0,9–1,1 ms × jumlah event dalam cluster) | **ya** |
| Wake jitter `Dispatchers.Default` | tidak |
| Kerja Kotlin per event (CASM, ledger, tiket) | tidak |
| Antre di belakang voice apply (±12 ms, P-B1a ON) / retarget (±26 ms per akor, di T-MAIN; tidak memblok T-SCHED kecuali lewat lock) | sebagian (tunggu mutex) |
| Kuantisasi blok render (≤ 5,33 ms) | tidak (sama seperti sekarang) |

**Perkiraan kasar (INFERRED):** lateness rata-rata 21 ms → ±9–13 ms bila tunggu mutex adalah komponen backlog terbesar. Belum terbukti: porsi wake jitter dan Kotlin belum diukur.

## 7. Risiko (untuk implementasi nanti)

- Inversi urutan not ↔ program bila operasi kontrol tidak menguras queue lebih dulu.
- Kehilangan NOTE OFF bila overflow tidak ditangani dengan fallback.
- Callback audio lebih berat (D1). Ukur `callbackTotal` / `overBudget`.
- `allNotesOff` / STOP harus membuang queue secara atomik terhadap consumer.
- Diagnostik LEGACY tidak boleh berjalan di thread audio. Kunci P-B1c ON hanya bila P-A OPTIMIZED.
- Lane perkusi (`percussionOn`) berpindah ke thread consumer. Biaya dan keamanannya perlu tes host + TSan.
- Statistik `LatencyProbe` dan PB01 berubah makna (tunggu berpindah ke consumer). Perlu baris baru: kedalaman queue, umur event di queue, overflow.

## 8. GO / NO-GO

| Langkah | Keputusan | Syarat |
|---|---|---|
| **P-B0.2: dekomposisi lateness** (observer-only). Di `LatencyProbe` / `AudioEngineManager`, tanpa menyentuh `StyleSequencer`: catat akhir JNI terakhir per thread scheduler; saat event bangun, klasifikasikan **idle** (thread sudah bebas sebelum due → lateness = wake jitter) vs **backlog** (masih sibuk melewati due); pisahkan porsi backlog di JNI (tunggu + hold) vs Kotlin | **GO** (menunggu persetujuan implementasi) | toggle + tes host event identik, seperti P-B0.1 |
| **P-B1c (queue + owner)** | **GO bersyarat**: hanya bila P-B0.2 di FunkPopRock (semua part, OPTIMIZED) menunjukkan backlog-JNI ≥ 50 % dari lateness p95 | toggle default OFF, tes host event identik + overflow + STOP + TSan, A/B perangkat A-B-B-A; target lateness p95 turun ≥ 40 % dan event > 64 ms < 2 % tanpa xrun baru |
| P-B1c bila wake jitter dominan | **NO-GO**; arah lain: thread scheduler khusus berprioritas tinggi | — |
| Implementasi queue sekarang | **NO-GO** | sesuai instruksi |
