# P-B0.2: PEMISAHAN LATENESS SCHEDULER (observer-only, toggle default OFF)

- **Status:** di-commit (`bd5e7ebd351dce4be506d9d9a29c113f0126d904`) dan di-push dengan persetujuan pengguna. CI run `42579186444` **success**, APK tersedia. **Belum diuji di perangkat.**
- **Branch lokal:** `experiment/pb02-sched-split`, dari `experiment/pb1a-drum-bind-cache` @ `bbbb4e7` (P-A + P-B0.1 + P-B1a).
- **Tidak diubah:**
  - StyleSequencer (hash P1 tetap), ArrangerBrain;
  - BASSMIDI/native (tidak ada perubahan C++);
  - P-A OPTIMIZED;
  - isi, urutan, dan waktu target event;
  - tidak ada queue atau thread baru.

## 1. Apa yang diukur

Untuk setiap event Style: **lateness = waktu bangun scheduler − waktu jatuh tempo** (titik yang sama dengan `styleEventWakeLatenessBeforeLifecycle`). Lateness dipecah menjadi:

| Komponen | Arti | Sifat |
|---|---|---|
| **WAKE** | Pada saat jatuh tempo scheduler tidak punya pekerjaan teramati, lalu bangun terlambat (timer coroutine, penjadwalan thread OS) | **Batas atas**: ikut memuat "ekor" tak teramati dari event sebelumnya (Kotlin sesudah JNI/MIDI OUT terakhir). Ekor ini diukur terpisah (`tail`) |
| **JNI** | Scheduler berada di dalam panggilan JNI BASSMIDI (tunggu mutex + kerja native) | terukur langsung |
| **MIDIOUT** | Scheduler berada di dalam kirim MIDI OUT (router + tulis USB) | terukur langsung |
| **VOICE** | Scheduler berada di dalam voice apply section (JNI di dalamnya tidak dihitung lagi) | terukur langsung |
| **KOTLIN** | Scheduler sibuk, tetapi tidak di salah satu di atas | sisa. Mencakup CASM, ledger, tunggu lock Kotlin (`noteLifecycleLock`, OrderedNoteIo), GC, dan preemption saat berjalan. **Sub-pembagiannya UNKNOWN** |
| **UNKNOWN** | Bagian interval yang lebih tua dari cakupan ring (atau sebelum wake pertama) | tidak pernah ditebak |
| **BOUNDARY** (kelas event) | Event pertama setelah voice apply, dan event yang intervalnya menjangkau ke perubahan section | **Hanya total**: di sana ada tunggu yang tidak bisa dilihat (tunggu batas bar dan `awaitMusicalTick` P1 berada di StyleSequencer yang tidak diinstrumentasi) |

## 2. Titik probe

| # | Lokasi | Isi |
|---|---|---|
| W | `PcmSessionControls.kt:246` `LatencyProbe.schedulerWake(now, target)` (dipanggil StyleSequencer per event setelah tunggu; sudah ada) | `Pb02.wake(now, target)`: klasifikasi |
| V | `PcmSessionControls.kt:234/243` `voiceApplyStart/End` (sudah ada) | interval VOICE |
| J | `AudioEngineManager.kt:334-336` (`noteOnChannel`), `:345-352` (`noteOnStyleChannel`), `:364-368` (`noteOffStyleChannel`), `:375-378` (`noteOffChannel`, sesudah baris log NOTE) | interval JNI di sekitar panggilan bridge |
| M | `MidiInputManager.kt:240-242` / `:260-262` (`sendNoteOn/Off`) | interval MIDIOUT |
| R | `PcmSessionControls.kt:133` `reset()` (RESET LATENCY), `:262` `report()` (SAVE REPORT) | reset dan laporan |
| Core | `PcmSessionControls.kt:322-` `class Pb02Core`, `:434-` `object Pb02` | classifier + toggle |
| UI | `Sf2StyleInspectorScreen.kt` tombol **`P-B0.2 SCHED SPLIT: OFF/ON`** (hanya saat berhenti); `MainViewModel.kt` | toggle |

**Filter thread:** hanya thread `DefaultDispatcher-worker*` (coroutine scheduler) yang dicatat. Retarget akor di thread UI dan keyboard di thread MIDI tidak dicatat; efeknya pada scheduler muncul sebagai tunggu JNI atau Kotlin yang lebih lama.

**Classifier ditaruh di `PcmSessionControls.kt`** (bukan file baru), karena beberapa tool tes memakai daftar sumber eksplisit yang memuat file itu. Tool tersebut tidak perlu diubah.

## 3. Validitas klasifikasi (tidak ada waktu yang dihitung dua kali)

**Konstruksi:**
1. Interval bertipe saling lepas:
   - JNI dan MIDI OUT berurutan di satu coroutine;
   - JNI di dalam voice apply tidak dicatat terpisah;
   - panggilan bersarang dipotong ke bagian barunya;
   - WAKE adalah waktu tidur.
2. **Per event:** `wake + jni + midiOut + voice + kotlin + unknown == lateness`, persis (`mismatches` harus 0).
   - Waktu yang sama memang boleh ada di lateness beberapa event dari satu cluster (mereka menunggu backlog yang sama), tetapi di setiap event setiap ns hanya punya satu tipe.
3. **Per waktu thread:** setiap ns di antara dua wake masuk tepat satu bucket: idle tepat waktu (termasuk tail), wake overrun, jni, midiOut, voice, kotlin, atau boundary. Jumlah bucket = span. Oversleep yang diwariskan ke beberapa event dihitung **sekali**.
4. **Klasifikasi idle vs backlog:**
   - **idle:** jatuh tempo > aktivitas teramati terakhir;
   - **backlog:** jatuh tempo ≤ aktivitas teramati terakhir. Interval [due, wake] sibuk penuh tanpa tidur, karena tunggu di StyleSequencer langsung kembali bila due sudah lewat (`StyleSequencer.kt:627-638`).

**Tes host** `tests/rtdiag/Pb02ClassifierTest.kt` (timeline sintetis, angka pasti), 5/5 PASS:

| Tes | Membuktikan |
|---|---|
| `idleBacklogAndInheritedOversleep` | idle overrun 3 ms; event backlog berikutnya mewarisi 2 ms oversleep sebagai **WAKE** (bukan Kotlin); JNI/MIDI OUT/Kotlin persis; timeline: oversleep **sekali** (3 ms) walau diwarisi 2 event; buckets = span |
| `ringEvictionIsUnknownNotGuessed` | bagian di luar cakupan ring masuk **UNKNOWN**; hanya interval yang tersisa yang diatribusikan |
| `nestedCallsStayDisjoint` | JNI bersarang (jalur fallback) tidak dihitung dua kali |
| `sectionChangeIsBoundaryTotalOnly` | perubahan section menjadi BOUNDARY (total saja); JNI di dalam voice apply tidak dihitung lagi |
| `overheadMicrobench` | 611 ns per event (host JVM: klasifikasi + 3 stempel) |

**Tes rantai produksi** `tests/rtdiag/Pb02EventIdentityRtTest.kt` (RtRig, ROSITA Main A → fill → Main B, akor C): PASS.
- Probe **OFF vs ON**: 316 event NOTE engine dan 457 pesan MIDI OUT **identik, urutan sama**.
- Run ON:
  - `mismatches = 0`;
  - setiap event tepat satu kelas (106 tepat waktu, 102 idle, 220 backlog, 71 boundary);
  - buckets timeline = span.

**Batas yang dilaporkan apa adanya (UNKNOWN, bukan estimasi):**
- WAKE adalah batas atas. Bila `tail` p95 sebanding dengan WAKE, pemisahan WAKE vs ekor Kotlin **UNKNOWN**.
- KOTLIN tidak memisahkan CPU Kotlin vs tunggu lock Kotlin vs GC vs preemption.
- Event BOUNDARY tidak dipecah.
- Cluster pertama setelah START (jatuh tempo sebelum wake pertama) menjadi UNKNOWN. Di host terlihat 27 % karena run hanya 8 s; di run perangkat 60 s porsinya kecil, dan tetap dilaporkan.

## 4. Overhead

| Kondisi | Biaya |
|---|---|
| OFF (default) | satu baca volatile per hook (`Pb02.enabled`) |
| ON | per panggilan JNI / MIDI OUT: 2 `System.nanoTime()` + catat ke ring tetap (512 slot, tanpa alokasi). Per event: klasifikasi ±0,6 µs (host). Perangkat diperkirakan beberapa µs per event (±150 event/s → < 0,1 % waktu thread) |
| Jalur audio | **tidak disentuh** (tidak ada kode native baru, tidak ada log) |

Kontrol overhead di perangkat: run OFF vs ON dengan `styleEventWakeLatenessBeforeLifecycle` (§6).

## 5. Hasil tes host lain

| Tes | Hasil |
|---|---|
| `Pb02ClassifierTest` (5) | **PASS** |
| `Pb02EventIdentityRtTest` (ROSITA, probe OFF vs ON) | **PASS**: event identik |
| Guard isolasi pada salinan bersih (+ P-A / P-B0.1 / P-B1a / **P-B0.2 GUARD**: default OFF, tidak masuk direktori Style engine) | **PASS** |
| P-B1a (`test_pb1a_native.py`), R23 native, P-B0.1, P-A (plain/ASan/TSan) | **PASS** |
| Device Routing native, Song native, CPV JVM | **PASS** |
| R1–R5 (ROSITA, EnglishWaltz2), Recorder V1B/V1C/parity, E343 SHORT_GATE/AUTO, Device Routing, Transpose/ACMP/LEFT, MIDI IN split | **PASS** |
| `test_voice_resolver.py` | tes native **PASS** (9). Langkah terakhir `test_drum_shadow_guards.py` FAIL, sama seperti di baseline |
| `test_realtime_guard.py` | **FAIL juga di baseline `bbbb4e7`** (guard file lama: "Unapproved file changed", workflow/E343/dst. dari fitur sebelumnya). Tidak terkait P-B0.2 |
| `test_r20_jvm.py` | **FAIL juga di baseline `bbbb4e7`** (daftar sumber lama tanpa `DrumKitShadowRuntime`). Tidak terkait P-B0.2 |
| CI `Quick Experimental APK (MIDI Song V1A)`, run `42579186444` (#23) | **success**: guard, compile APK kedua ABI + verifikasi, Quick tests, UI tests. https://github.com/pulicarpus/YamahaArranger/actions/runs/42579186444 |
| APK artifact | `SONG-QUICK-APK-experiment-pb02-sched-split-bd5e7ebd351dce4be506d9d9a29c113f0126d904` (id `13657170279`, 14,8 MB, digest zip `sha256:6734b72e0201a7001d7e2ec46cf2119440118de3884b784470b8927bc2a1f118`, berlaku sampai 2027-02-07): https://github.com/pulicarpus/YamahaArranger/actions/runs/42579186444/artifacts/13657170279 |
| Perangkat | NOT TESTED |

## 6. Rencana A/B perangkat (3 run × 60 s)

**Konfigurasi:**
- FunkPopRock, Main D, ACMP ON, semua part, OUTPUT INTERNAL, akor tetap;
- P-A OPTIMIZED, R2-2 SHADOW OFF, P-B1a OFF.

| Run | P-B0.2 | Untuk |
|---|---|---|
| 1 | **ON** | pemisahan |
| 2 | **OFF** | kontrol overhead (bandingkan `styleEventWakeLateness` rata-rata / p95 / > 64 ms dengan Run 1 dan 3) |
| 3 | **ON** | ulangan (konsistensi) |

Untuk setiap run: RESET LATENCY → mainkan 60 s → STOP → SAVE REPORT.

**Baris yang dibaca:** `PB02`, `PB02_SHARE_all`, `PB02_SHARE_late32ms`, `PB02_SHARE_late64ms`, `PB02_TIMELINE_ms`, `PB02_part_*`, `PB02_tail_*`.

**Keputusan:**

| Hasil (Run 1 dan 3 konsisten, overhead Run 2 vs 1/3 < 1 ms rata-rata) | Langkah |
|---|---|
| `jni` ≥ 50 % pada `PB02_SHARE_late32ms` | **P-B1c (queue + single owner): GO bersyarat** |
| `wake` ≥ 50 % dan `tail` p95 jauh lebih kecil | Penjadwalan thread. Kandidat: thread scheduler khusus berprioritas tinggi |
| `kotlin` ≥ 50 % | Kerja / lock Kotlin per event. Perlu probe CPU-thread berikutnya |
| `unknown` + BOUNDARY besar, atau `mismatches` > 0 | Data tidak valid; ulangi / perbaiki probe |
