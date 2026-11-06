# PSR-E343 — P1 FILL → MAIN NOTE RELEASE: BUILD APK EKSPERIMEN

- **Status: BUILD SUCCESS** (APK eksperimen; **belum diuji fisik**).
- **Branch:** `experiment/e343-fill-note-release-p1` (tidak di-merge, tanpa PR, tanpa force push).
- **Commit APK:** `299a7f8ba8a9bf783868304297ab1e3c4b60408b`.
- **Isi branch:**
  - `ae72bd0`: patch P1 + tes;
  - `9dfd9d6`: jendela tes R3;
  - `fc71baa`: laporan eksperimen;
  - `299a7f8`: guard.
- **Dasar:** audit `diagnostics/PSR_E343_MIDI_OUT_FILL_AUDIT.md` (`4e4871c`) dan eksperimen `diagnostics/PSR_E343_FILL_P1_EXPERIMENT.md`.

## 1. Identitas patch P1

| Item | Nilai |
|---|---|
| File produksi | `app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt` saja (+11 / −0, cabang `pendingSectionQueue`) |
| SHA-256 file | `240c4145bfaf315f2b0f5875cd47f7d8cea9d0d640dd5163d0eb2be8358614a2` |
| SHA-256 blok tambahan (11 baris, sudah di-trim) | `420be0459a22253116976a22dc6f040ad2e0b5bf09575aff043fdd8fb91d5f10` |
| Perilaku | Fill/Intro berakhir alami, lalu section berikutnya dari antrean: lepas hanya not ledger milik section sebelumnya, di tick batas section (`releaseActive`). Loop section yang sama tidak disentuh |
| Tidak diubah | parser, CASM/NTT, pengenalan akor, drum, BASSMIDI/Oboe, router MIDI OUT, adapter E343, workflow; tanpa CC123 global, tanpa delay/throttle; F5–F8 tidak diperbaiki |

## 2. Perubahan guard (`tools/test_midi_song_guard.py`, commit `299a7f8`)

**Masalah:** guard membekukan folder `arranger/` (FROZEN) dan membatasi file yang boleh berubah (ALLOWED), sehingga P1 gagal di guard.

**Solusi, pengecualian sempit yang dikunci:**
- `StyleSequencer.kt` dan `tests/rtdiag/FillNoteReleaseP1RtTest.kt` ditambahkan ke ALLOWED.
- Pemeriksaan FROZEN tetap berjalan untuk **seluruh** folder lain. `StyleSequencer.kt` dikecualikan dari diff FROZEN, **tetapi** wajib lolos tiga syarat sekaligus:
  1. tidak ada baris yang dihapus dibandingkan base guard;
  2. tepat 11 baris tambahan dengan hash blok di atas;
  3. hash seluruh file sama dengan hash di atas.
- Perubahan lain sekecil apa pun di `StyleSequencer.kt` atau di file `arranger/` lainnya tetap membuat guard gagal.

**Uji guard** (worktree bersih, tanpa file untracked audit lama):

| Kasus | Hasil |
|---|---|
| Branch P1 apa adanya | PASS |
| Satu baris `StyleSequencer.kt` di luar blok P1 diubah | FAIL ("a line was removed") |
| Isi blok P1 diubah (filter `sourceSection`) | FAIL ("delta is not the reviewed P1 block") |
| File `arranger/` lain (`CasmNoteTransformer.kt`) diubah | FAIL ("Unapproved file changed") |
| Kembali ke kondisi asli | PASS |

Catatan: di working tree lokal guard juga menolak dua file untracked dari audit lama. File itu sengaja tidak di-commit dan tidak dihapus, dan tidak ada di checkout CI.

## 3. Hasil regresi

| Tes | Hasil | Sumber |
|---|---|---|
| R1–R5 (`FillNoteReleaseP1RtTest`, 4 Style, sebelum/sesudah) | PASS: R1 baseline 4/1/3 not tertahan (EnglishWaltz2/HardRock2/ROSITA) → 0; R2–R5 identik | lokal, lihat laporan eksperimen |
| R6 lokal: Recorder V1B/V1C + parity, SHORT_GATE, E343 AUTO, Device Routing, Transpose/ACMP, CPV JVM 385 | PASS | lokal, lihat laporan eksperimen |
| CI: Isolation guard | **PASS** | run `42132998670` |
| CI: CPV JVM, Recorder V1B/V1C + Python re-read, Song native, Real BASSMIDI Song XG | **PASS** | run `42132998670` |
| CI: E343 SHORT_GATE (EnglishWaltz2), MIDI IN split, Transpose/ACMP/LEFT | **PASS** | run `42132998670` |
| CI: E343 AUTO (HardRock2, EnglishWaltz2, ROSITA), Device Routing V1 | **PASS** | run `42132998670` |
| CI: UI tests (SONG, E343, rotary knob) | **PASS** | run `42132998670` |
| `FillNoteReleaseP1RtTest` di CI | NOT TESTED (workflow tidak memuatnya; workflow sengaja tidak diubah) | — |

## 4. Build

| Item | Nilai |
|---|---|
| Workflow | `Quick Experimental APK (MIDI Song V1A)` (`.github/workflows/midi-song-quick-apk.yml`), dijalankan manual (`workflow_dispatch`) pada branch eksperimen. Workflow tidak diubah |
| Run | `42132998670`: https://github.com/pulicarpus/YamahaArranger/actions/runs/42132998670 |
| Status | **success** (Quick tests, UI tests, Quick APK, Quick report) |
| Langkah verifikasi APK | "Verify APK = this commit, signature, ABIs and SONG player + native Song stream inside": success. Debug APK, ARM64-v8a + armeabi-v7a; Redmi Pad = arm64 |
| Artifact | `SONG-QUICK-APK-experiment-e343-fill-note-release-p1-299a7f8ba8a9bf783868304297ab1e3c4b60408b` (id `13464751887`, 14,7 MB) |
| Link | https://github.com/pulicarpus/YamahaArranger/actions/runs/42132998670/artifacts/13464751887 |
| Digest zip artifact | `sha256:5465cea16f1628bbe539207fed11c078c223c186ce7490c0893fb569229a247c` (SHA-256 file APK di dalam zip ada di artifact `SONG-QUICK-apk-evidence-42132998670`; tidak diekstrak di sini) |
| Masa berlaku | sampai 2027-02-03 |

## 5. Risiko yang tersisa

- Belum ada uji fisik di Redmi Pad + PSR-E343.
- Love Song tidak memiliki not yang kehilangan NOTE OFF (R1 = 0 di baseline). Kalau gejalanya ada di Love Song, penyebabnya di luar P1: F5 (NOTE ON ganda dari CASM), F7 (RIGHT/LEFT dipantulkan ke E343 / Local Control), atau polifoni.
- Not yang sengaja disambung dari Fill/Intro ke Main kini berhenti di batas section, sama seperti transisi yang memutus.
- F6 (ledakan NOTE OFF ledger drum) dan F8 (urutan ON/OFF di tick yang sama) belum diperbaiki.
- APK debug, bukan rilis.

## 6. Instruksi uji fisik (Redmi Pad + PSR-E343, USB MIDI)

1. Pasang APK dari artifact di atas.
2. **Siapkan E343:**
   - Local Control **OFF** (menu Function E343);
   - di aplikasi: SETTINGS → MIDI & AUDIO → MIDI OUTPUT = PSR-E343;
   - OUTPUT MODE = MIDI OUT (lalu ulangi dengan BOTH).
3. **EnglishWaltz2:** Main B, tahan akor C, lalu tekan Main C (memutar FillCC).
   - Ganti akor saat Fill dan 2–3 detik setelah Main C mulai.
   - Yang diharapkan: tidak ada suara guitar dari Fill yang menggantung atau ikut pindah ke akor baru.
4. Ulangi pada **ROSITA**, Main B → Main C (FillCC, ch12).
5. **Kontrol:** Main A → Main B (FillBB) di kedua Style. Hasilnya harus sama seperti sebelumnya.
6. **Bandingkan dengan APK baseline** `feature/midi-song-v1` (Device Routing V1, `b81cb76`): https://github.com/pulicarpus/YamahaArranger/actions/runs/42010987323/artifacts/13406763664. Di APK baseline, nada Fill diperkirakan tertinggal pada langkah 3–4.
7. **Regresi dengar:**
   - Main yang sama berputar ≥ 3 kali: pad/akor tidak terputus;
   - Intro → Main;
   - Ending;
   - STOP saat Fill;
   - Recorder FULL ARRANGER (rekam → LOAD TAKE).
8. Catat Style dan transisi persis di mana gejala masih terdengar, lalu kirim DebugLog. Baris yang dicari: `🎼 SECTION END: released`.

Masalah fisik **belum dinyatakan selesai**. Ini menunggu hasil uji di perangkat.
