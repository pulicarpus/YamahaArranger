# P-B1a: EKSPERIMEN CACHE BIND DRUM (A/B toggle, default OFF)

- **Status:** diimplementasikan dan diuji di host. **Belum di-commit, belum ada APK** (menunggu persetujuan). **Belum diuji di Redmi Pad.**
- **Branch lokal:** `experiment/pb1a-drum-bind-cache`, dari `experiment/pb01-targeted-probes` @ `930cba6`.
  - Branch dasar itu = P-A + probe P-B0.1, sehingga efek P-B1a langsung terukur dengan baris `PB01_*`.
- **Dasar:** `diagnostics/PB01_DEVICE_RESULTS.md`. Dengan shadow OFF, `bindDrumAB` ×2 = ±17 ms (63 %) dari voice apply ±27 ms, karena:
  - setiap transisi membangun ulang tabel font;
  - setiap transisi mengirim ulang DRUMS/BANK/PROGRAM;
  - padahal kit drum tidak berubah.

## 1. Perubahan

| Bagian | Isi |
|---|---|
| `pb1a_drum_bind_cache.h` (baru) | Flag `pb1a::mode` (default **0 = OFF = perilaku lama**), penghitung (`calls`, `skippedIdentical`, `rebound`), dan `tableStale` |
| `BassMidiPlayer::bindDrumAB` (`bassmidi_player.cpp:3191-3202`) | Bila ON **dan** semua syarat terpenuhi, binding dipertahankan (hanya label section diperbarui) dan fungsi kembali `true` tanpa `applyFonts`, tanpa kirim ulang, tanpa preload. Syarat skip: (1) tabel font tidak basi; (2) binding sebelumnya `verified`; (3) fingerprint, bank mentah, dan program **identik**; (4) channel masih drum dengan program yang sama; (5) **readback live BASSMIDI** = font/bank/program binding |
| `applyFonts` (`:357`) | Setelah `StreamSetFonts` sukses: `tableStale = false` (tabel = binding) |
| Reset binding (`:3202`, `bindDrumManual :3241`) | `tableStale = true`: tidak boleh skip sampai tabel dibangun ulang |
| JNI / Kotlin / UI | `nativePb1aSetDrumBindCache` / `nativePb1aDrumBindCache`. Tombol **`P-B1a DRUM BIND CACHE: OFF/ON`** di SF2 / STYLE INSPECTOR (hanya saat berhenti) |
| Laporan | Baris `PB1A drumBindCache=… bindDrumABCalls= skippedIdentical= rebound=` di SAVE REPORT, direset oleh RESET LATENCY |
| Guard | Pengecualian sempit + pin: flag hanya dibaca di `bindDrumAB`, syarat skip dipin persis, default OFF |

**Tidak diubah:**
- StyleSequencer (hash P1 tetap), ArrangerBrain, CASM, parser;
- pemilihan kit (`resolveAB` tetap jalan setiap transisi);
- jalur kit berubah / tidak ter-resolve / readback gagal / ganti mode / manual bind;
- not, NOTE OFF, MIDI OUT, P-A, probe P-B0.1.

## 2. Temuan selama pengujian (sudah ditangani)

**Kasus:** request tanpa resolusi di ch8 menginvalidasi binding ch8 tanpa membangun ulang tabel font.
- **LEGACY:** rebind ch9 berikutnya membangun ulang tabel, sehingga mapping basi ch8 hilang. Readback ch8 jatuh ke `prime_drum`.
- **Versi pertama cache:** rebind ch9 dilewati, sehingga mapping basi tertinggal. Readback berbeda, walau bunyinya sama (not ch8 tetap ditolak di kedua mode).
- **Perbaikan:** flag `tableStale`. Setelah binding apa pun di-reset, tidak ada skip sampai `applyFonts` berikutnya sukses.
- Tes kini membuktikan readback identik di setiap langkah.

## 3. Tes host

`tools/test_pb1a_native.py` (baru) memakai fixture emas dan mock R2-3 (fingerprint Colombo asli), plain dan ASan/UBSan.

**Sesi FunkPopRock-like:**
- 6× voice apply Main C ↔ Main D dengan kit sama;
- ganti preset melodik di tengah;
- ganti kit ch8 (PC 27 → 8);
- request tanpa resolusi;
- readback gagal (`mismatchPreset`);
- mode OFF → ON.

**Hasil:**

| Cek | Hasil |
|---|---|
| Readback kit ch8/ch9 dan jumlah not drum yang diterima, **setiap langkah**, OFF = ON | PASS |
| Semua event NOTE (stream, channel, key, velocity), urutan sama | PASS (108 event) |
| State akhir controller/program setiap channel | PASS |
| OFF tidak pernah skip | PASS (28 / 28 rebind) |
| ON skip hanya bind identik terverifikasi; perubahan, tanpa resolusi, readback gagal, dan ganti mode tetap rebind | PASS (16 skip, 12 rebind) |

Suite lain: lihat §6.

## 4. Dampak yang diharapkan (INFERRED, harus diukur di perangkat)

- Fill/Main dengan kit yang sama (FunkPopRock Main C/D, Fill CC/DD: semua 127/0/PC27 + PC0):
  - `PB01_voiceApply_bindDrumAB` ±17 ms → < 1 ms (2 skip, masing-masing satu readback di bawah lock + tunggu render);
  - voice apply ±27 ms → **±10 ms**;
  - not pertama setelah transisi lebih awal ±15 ms.
- Transisi ke section dengan kit lain: tidak berubah (rebind penuh).

## 5. Risiko regresi

| Risiko | Mitigasi |
|---|---|
| Binding dianggap valid padahal BASSMIDI sudah berubah | Readback live wajib cocok; noteOn drum tetap memverifikasi readback di setiap not (jalur lama) |
| Tabel font basi | `tableStale`; tes kasus tanpa resolusi |
| Program/bank ch8/9 tidak dikirim ulang | Hanya bila readback membuktikan nilai yang sama sudah aktif |
| Preload (`FontLoadEx`) tidak diulang | Preset sudah dimuat saat bind pertama |
| `tableStale` global per proses | Aplikasi memakai satu BassMidiPlayer utama |
| Label section binding (Inspector `DRUM_AB_BOUND section=`) | Tetap diperbarui |

## 6. Hasil suite (HOST)

| Tes | Hasil |
|---|---|
| `tools/test_pb1a_native.py` (plain + ASan/UBSan) | **PASS** (16 cek). Run pertama ASan gagal *link*: tes tidak mendefinisikan global JNI. Diperbaiki seperti tes native lain, lalu PASS |
| `tools/test_r23_native.py` (binding drum R2-3, default OFF) | **PASS** |
| `tools/test_pb01_probe.py`, `tools/test_pa_mutex_ab.py` (plain/ASan/TSan) | **PASS** |
| Guard isolasi pada salinan bersih (+ P-A / P-B0.1 / P-B1a GUARD) | **PASS** |
| `test_voice_resolver.py` | tes native **PASS** (9 baris). Langkah terakhir `test_drum_shadow_guards.py` FAIL, sama seperti di baseline `b66ad9f` (guard hash F12 vs StyleSequencer P1) |
| Device Routing native, Song native, CPV JVM | **PASS** |
| R1–R5 (ROSITA, EnglishWaltz2), Recorder V1B/V1C/parity, E343 SHORT_GATE/AUTO, Device Routing, Transpose/ACMP/LEFT, MIDI IN split | **PASS** |
| APK / CI | NOT RUN (menunggu persetujuan) |
| Perangkat | NOT TESTED |

## 7. Uji Redmi Pad (setelah APK)

1. Inspector:
   - P-A AUDIO DIAG = OPTIMIZED;
   - P-B0.1 PROBES = ON;
   - R2-2 SHADOW = OFF;
   - LEAN = ON (seperti B1).
2. **Run 1:** P-B1a = **OFF**. RESET LATENCY, Main C ↔ Main D 10×, STOP, SAVE REPORT.
3. **Run 2:** P-B1a = **ON**. Sama persis.
4. Dengarkan apakah drum/kit terdengar sama di kedua run.

**GO** bila Run 2 dibanding Run 1:
- `PB01_voiceApply_totalAsSeenByKotlin` turun ≥ 50 %;
- `sectionFirstNoteLatenessVsSchedule` turun;
- `PB1A skippedIdentical` ≈ 2 per transisi;
- `DRUM_AB_BOUND … bindingVerified=1` untuk ch8/9;
- kit terdengar sama.

**NO-GO** bila kit terdengar berbeda, ada drum yang hilang, atau `bindingVerified=0`.
