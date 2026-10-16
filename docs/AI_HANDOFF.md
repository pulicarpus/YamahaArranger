# AI HANDOFF — YamahaArranger (experimen Colombo Primary)

Branch kerja: `experiment/colombo-primary-voice-override`. Baseline stabil: R2-3 `c75732f28fec58fbfbc7e626bc380b10a00722b8`
(R2-4 Drum Punch **tidak** dipakai). Jangan merge ke `main`, jangan buat PR tanpa persetujuan pemilik.

Label bukti: **[T]** terbukti (kode, `git diff`, laporan perangkat, tes), **[K]** indikasi kuat, **[?]** belum diketahui.

## Aturan yang harus dipertahankan
- CASM, algoritma chord, aturan transisi Main/Fill, dan jeda chord 15 ms **tidak diubah** sebelum dampaknya terbukti.
- LIVE CONTROL, REG MEMORY (schema 2), RELEASE, RIGHT OFF, MIDI OUT PSR-E343 (tanpa Bank/PC otomatis), Voice Inspector, dan
  override Mixer per style harus tetap berfungsi.
- Tidak ada limiter, normalisasi, atau perubahan gain tanpa persetujuan.
- Prime Melodi/Drum tidak dimuat otomatis. Colombo = SF2 utama, Tyros 4 = fallback.
- Oboe sudah memakai backend AAudio; jangan diganti.
- **Jangan menyatakan latensi membaik sebelum pengujian perangkat A/B membuktikannya.**

## Riwayat commit (branch ini)
| Commit | Isi |
|---|---|
| `690e578` | P0–P1: workflow CI, Style Voice Inspector |
| `6d48ae4` | P2–P5: Colombo utama/Tyros fallback, override Mixer per style |
| `88c3dd2` | probe latensi (observer saja) + investigasi awal |
| `2e844e3` | patch latensi bertahap + probe lebih rinci + saklar A/B (dijelaskan di bawah) |

## Hasil perangkat Redmi Pad + E343, build `88c3dd2` (baseline A/B)
File asli: `YamahaArranger_Inspector_20261016_194230.txt` (SHA-256 `8906f0cc…16efb1d5`). Angka di-parse ke
`docs/cpv/baseline/latency_88c3dd2_redmi_pad_20261016_194230.json`; fixture ringkas `tests/fixtures/cpv/latency_device_88c3dd2.txt`;
perbandingan: `python3 tools/compare_latency.py compare A B`.

| Area | Hasil | Arti |
|---|---|---|
| Stream Oboe | AAudio, **Shared + LowLatency**, burst 256, buffer 512 (10,67 ms), estimasi output **18,82 ms**, XRUN 13 | [T] lantai latensi output ±19 ms (estimasi Oboe, bukan ukur akustik); tidak jatuh ke mode None |
| Callback | 25.894 callback, `renderDecode` rata-rata 1,31 ms (maks 7,76 ms), **116 over-budget** (anggaran 5,33 ms), `renderLockWait` maks **16,99 ms**, `callbackTotal` maks 17,61 ms | [T] decode sendiri hampir selalu dalam anggaran; callback yang lewat anggaran bertepatan dengan menunggu `mutex_` [K] |
| RIGHT | USB→`onSend` rata-rata 1,03 ms (maks 11,9); onSend→semua layer ke native rata-rata **4,46 ms** (maks 16,0); lock native 0,40 ms; NOTE_ON→render 2,40 ms; →buffer siap 4,26 ms (maks 6,63) | [T] ±1 ms JNI per tekan; **±3,4 ms per tekan hilang di Kotlin/penjadwalan thread MIDI** (penyebab [?]) |
| Section | boundary→apply 16 ms (maks 45,7); **apply voice 35,9 ms (maks 68,9)**; apply→nada pertama 6,2 ms; boundary→nada pertama **50,1 ms (maks 82,4)**; keterlambatan nada pertama 60,4 ms | [T] ±58 ms antara bar-boundary dan nada pertama section baru |
| Kuantisasi | permintaan tombol→boundary rata-rata 1,63 s (maks 2,75 s) | [T] **disengaja** (bar berikutnya); tidak dihitung sebagai keterlambatan |
| ACMP | nada terakhir→apply 25,8 ms (maks 44,2); retarget 32,0 ms (maks 65,4); total **57,8 ms (maks 92,8)** | [T] 15 ms settle + ±11 ms hop Main + ±32 ms retarget |
| Scheduler | wake lateness: 41 % kejadian ≥16 ms, **24,5 % ≥64 ms**, maks **316 ms**; setelah lifecycle 8 % ≥64 ms | [T] thread sequencer sering tertinggal jadwal; penyebab per-fase [?] (lihat probe baru) |

### Arti `keyNotesOverwrittenBeforeRender = 212` [T]
Bukan NOTE_ON hilang. Probe lama hanya memasangkan **satu** NOTE_ON per render pass; NOTE_ON lain yang masuk antrian BASSMIDI sebelum
render berikutnya (layer ke-2/3 dari satu tekan, nada chord bersamaan) tidak dipasangkan dan dihitung sebagai "overwritten".
Bukti tidak ada yang hilang: `JNI Kotlin = lock native = dispatch native = 426`, dan `214 dipasangkan + 212 = 426`. Dispatch hanya dihitung
setelah `BASS_MIDI_StreamEvent` sukses. Counter sudah diganti (`keyNotesQueued`, `renderWindowsWithKeyNotes`,
`maxKeyNotesInOneRenderWindow`, `pairingOverflow`) dan **setiap** NOTE_ON dipasangkan.

## Akar masalah yang terbukti vs dugaan
1. **[T] Section tertahan ±58 ms** berasal dari kerja sinkron di thread sequencer antara boundary dan nada pertama: release nada lama
   (16 ms), `applyVoicesFromCasm` (36 ms), merge/sort event (6 ms). Kuantisasi 1,6 s adalah desain.
2. **[T dari kode] Satu penyumbang apply voice yang berulang:** drum RESOLVED selalu aktif, sehingga setiap pergantian nama section
   memanggil `bindDrumAB` → `applyFonts` untuk ch8 dan ch9 meskipun kit tidak berubah. Di R2-3 (LEGACY) jalur drum tidak memanggil
   `applyFonts`. Pembagian biaya apply (drum vs melodik vs log) **belum terukur** → probe baru.
3. **[K] Callback melewati anggaran karena rebutan `mutex_`**, bukan karena decode: `renderLockWait` maks 17,0 ms ≈ `callbackTotal` maks 17,6 ms.
4. **[T] Latensi output ±19 ms (estimasi)** + antrian BASSMIDI 2–4 ms ⇒ RIGHT terdengar ±23–29 ms setelah USB tiba pada kondisi rata-rata
   [estimasi, bukan ukur akustik]. Ini batas bawah arsitektur saat ini; tidak ada bukti stream salah konfigurasi.
5. **[?] RIGHT 4,46 ms di Kotlin:** belum diketahui apakah CPU (kode) atau menunggu/dijadwalkan ulang → probe CPU thread vs waktu dinding.
6. **[?] Penyebab 24,5 % kejadian style ≥64 ms:** burst nada pada tick yang sama (tiap nada ±0,6 ms tunggu lock + JNI + log), pasca-transisi,
   atau stall lain → probe `wake lateness` dipisah menjadi `steadyState` vs `within500msOfSectionStart`, plus `styleLifecycleMonitorWait/Hold`.
7. **[T dari kode] ACMP:** `handleChordChange` me-retarget not satu per satu: tiap not = noteOff + noteOn JNI, masing-masing menunggu
   `mutex_` (rata-rata lock style 0,63 ms). Itu cocok dengan 32 ms retarget [K]. Penyelesaian penuh (batch di bawah satu lock atau antrian
   perintah tanpa blok) adalah perubahan besar → **belum dikerjakan**, butuh data A/B dulu.

## Perubahan kode pada iterasi ini (semua dapat dimatikan saat runtime untuk A/B)
Saklar di Inspector (baris **LATENCY TUNING**; `A: ALL OFF` = perilaku `88c3dd2`, `B: ALL ON` = semua patch). Default aplikasi: ON.
Native default 0 (perilaku lama) agar kontrak historis tetap valid; aplikasi menyalakannya saat memasang stack Colombo.

| Bit | Patch | Alasan berbasis bukti | Risiko / batas |
|---|---|---|---|
| 1 `DRUM_REBIND_SKIP` | `bindDrumAB` untuk kit yang sama dan sudah terverifikasi hanya diverifikasi ulang dengan readback independen; tanpa `applyFonts`, 4 event MIDI, preload | apply voice 36 ms; bind ch8+ch9 berulang tiap section (kode) | readback tidak cocok ⇒ jalur penuh seperti semula; kit berbeda ⇒ jalur penuh |
| 2 `FONTS_NOOP_SKIP` | `applyFonts` tidak mengirim ulang tabel FONTEX2 yang identik dan menunda ±15 baris log (kirim hanya bila tabel berubah) | rebutan `mutex_` saat apply; log `uiLog` JNI di dalam mutex | tabel selalu dikirim setelah font dimuat/dibebaskan/gagal (`fontTableDirty_`). **Perbedaan perilaku:** pada tabel identik, kegagalan pengiriman tidak lagi mungkin, jadi channel tidak ditandai fail-closed (kontrak `accompaniment_presence_test` berlaku untuk saklar OFF) |
| 4 `CHORD_OFF_MAIN` | tunggu 15 ms + penerapan chord berjalan di `Dispatchers.Default` bukan thread Main (settle 15 ms **tidak diubah**) | `chordLastNoteToApplyChordNow` 25,8 ms ≫ 15 ms | state chord kini `@Volatile`; chord yang datang di dalam settle tetap membatalkan yang lama (diuji) |
| 8 `MIDI_THREAD_PRIORITY` | thread dispatch MIDI dinaikkan ke `URGENT_AUDIO` sekali (OFF mengembalikan ke default) | handoff RIGHT 3,4 ms tak terjelaskan CPU | percobaan; bila ditolak Android, input MIDI tetap jalan |

Probe baru (observer saja): pemasangan semua NOTE_ON keyboard ke render; waktu `setChannelPreset`, `findMelodicPreset`, `applyFonts` (saat dikirim),
`BASS_MIDI_StreamSetFonts`, `bindDrumAB` penuh, panggilan `uiLog`; hitungan `applyFontsSkipped`, `drumBindSkipped/Full`; CPU thread vs waktu dinding
untuk handoff RIGHT, apply voice, retarget chord; biaya satu `DebugLog.add`; tunggu dan tahan monitor `lifecycle` sequencer; pemisahan wake lateness.
`tools/compare_latency.py` membandingkan dua laporan.

## Belum dikerjakan (butuh data A/B atau keputusan)
- Satu `applyFonts` untuk seluruh channel per section (batch) dan memoisasi `findMelodicPreset` (900 kandidat disalin per panggilan).
- Antrian perintah MIDI native tanpa blok (NOTE_ON tidak menunggu `render()`); batch noteOff/noteOn untuk retarget chord.
- Memindahkan apply voice sebelum boundary; mengurangi release nada lama; `DebugLog` lazy-format.
- Build native debug memakai `YAMAHA_ROLE_PCM_METERS` dan `YAMAHA_PCM_HEADROOM`; belum diukur seberapa besar menambah `renderDecode`.
- Evaluasi jeda chord 15 ms (jangan dihapus tanpa pengujian deteksi chord dan stabilitas ACMP).

## Hasil tes untuk `2e844e3`
- **Lokal (host):** U0/U1 native 210 resolver + 70 routing + audit path; R2-3 native; Policy C (A/B 39.936 kasus nama); ROSITA; LIVE/REG;
  JVM dan integrasi R2-3; CPV native (pairing probe, parity NOTE ON/OFF, tuning on/off untuk tabel font dan bind drum, aturan tabel kotor);
  CPV JVM **104 tes** termasuk thread chord dan settle 15 ms; `tools/test_compare_latency.py`. Semua lulus.
  Satu kegagalan sempat muncul dan diperbaiki sebelum commit: `accompaniment_presence_test` ("failing destination remains fail-closed")
  gagal saat saklar tabel-font aktif sebagai default native; native kini default 0 dan aplikasi yang menyalakannya.
- **GitHub Actions run #3:** https://github.com/pulicarpus/YamahaArranger/actions/runs/38907793389 — job `host` dan `android` (dua ABI) **sukses**.
  Artifact APK: `YamahaArranger-CPV-2e844e3fedd81a599f4f4e16ed75854709a018c4` (13,7 MB, SHA-256 arsip
  `d8251d5f8954992c4ae3aa771d1d9355cb5a85de7db6b614382a544216f83502`); unduh butuh login GitHub.
- **Perangkat:** belum diuji. Tidak ada klaim perbaikan latensi sampai A/B (`docs/cpv/LATENCY_TEST.md`) dijalankan.
