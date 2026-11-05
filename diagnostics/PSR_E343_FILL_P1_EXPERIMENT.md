# PSR-E343 — P1 FILL → MAIN NOTE RELEASE EXPERIMENT

- **Dasar:** `diagnostics/PSR_E343_MIDI_OUT_FILL_AUDIT.md`, commit audit `4e4871cda3ba5369737c429f6a8cce6b644e3071`, temuan F1/F2/F3, kandidat P1, dan rencana tes R1–R6.
- **Branch eksperimen:** `experiment/e343-fill-note-release-p1`, dibuat dari `feature/midi-song-v1` @ `4e4871c` (HEAD terverifikasi = remote).
- **Commit:**
  - `ae72bd0`: patch P1 + tes R1–R5;
  - `9dfd9d6`: jendela tes R3 dihitung dari panjang Main C.
- **Status:** eksperimen host saja. Tidak ada merge, PR, APK, atau perubahan workflow. Tidak ada workflow yang terpicu oleh branch ini. **Belum diuji di Redmi Pad + PSR-E343.**

## 1. Perubahan kode

`StyleSequencer.kt`, di cabang `pendingSectionQueue` loop playback (section berikutnya mulai setelah section sebelumnya berakhir alami). **+11 baris, 0 baris dihapus:**
1. Simpan nama section sebelumnya.
2. Kalau section baru **berbeda** (loop section yang sama dilewati), tunggu sampai tick batas section (`awaitMusicalTick(masterTimelineTick, …)`, fungsi yang sudah ada). Ini supaya not dilepas **di batas section**, bukan di event terakhir Fill: `playOnce` kembali segera setelah event terakhirnya, dan Fill bisa saja masih berbunyi.
3. Lepas hanya not ledger dengan `sourceSection == section sebelumnya`, lewat `releaseActive` → `chordOff` → `stageIo` (mekanisme yang sama dengan jalur transisi `:726-735`). NOTE OFF dikirim di pitch keluaran yang tersimpan, ke BASSMIDI dan MIDI OUT dalam satu perintah.

**Tidak diubah:**
- tanpa CC123 global;
- tanpa delay atau throttle;
- drum (tidak ada di ledger);
- pengenalan akor;
- CASM/NTT;
- parser;
- BASSMIDI/Oboe;
- router MIDI OUT;
- adapter E343.

Penerapan voice section baru tetap di posisi lama, jadi waktu Program Change tidak berubah.

## 2. File yang berubah (diff vs `4e4871c`)

| File | Perubahan |
|---|---|
| `app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt` | +11 / −0 |
| `tests/rtdiag/FillNoteReleaseP1RtTest.kt` (baru) | tes R1–R5 |
| `diagnostics/PSR_E343_FILL_P1_EXPERIMENT.md` (baru) | laporan ini |

Dua file untracked dari audit lama tidak disentuh dan tidak di-commit.

## 3. Bukti sebelum/sesudah (HOST: rantai produksi via RtRig, port USB palsu, synth tiruan)

Baseline dijalankan dari worktree terpisah di `4e4871c` dengan file tes yang sama.

| Tes | Style | Sebelum (baseline) | Sesudah (P1) |
|---|---|---|---|
| R1 Main B → FillCC → Main C: not Fill > 20 ms setelah NOTE ON pertama Main | EnglishWaltz2 | **4 not** (ch13: 55/60/64/67, sampai **6499 ms**) | **0** |
| | HardRock2 | **1** (ch12:60, 5746 ms) | **0** |
| | ROSITA | **3** (ch12: 60/64/67, 499 ms) | **0** |
| | Love Song | 0 | 0 |
| R2 FillBB → Main B, FillBA/FillAA → Main A | 4 Style | 0 | 0 |
| R3 Main C ×≥3 loop: not yang berakhir di batas loop tanpa re-strike | EnglishWaltz2 (5 loop) / HardRock2 (4) / ROSITA (4) / Love Song (4) | 32 / 15 / 3 / 42 | 32 / 15 / 3 / 42 (identik). Re-strike EnglishWaltz2 8 → 6: selisih jitter jendela 3 ms, bukan pemotongan |
| R4 not panjang < 2 ms (urutan ON/OFF tick sama, F8) / NOTE ON tumpang-tindih | EnglishWaltz2 / HardRock2 / ROSITA / Love Song | {} / ch12:1 / ch14:9 / overlap ch12:34 ch13:15 ch14:2 | **identik** (F8 tidak diubah, sesuai lingkup) |
| R5 paritas MIDI OUT = BASSMIDI (ON dan OFF per channel 11–16) | 4 Style | sama | sama (asersi lulus) |
| R5 akor diterapkan setelah tombol | 4 Style | 16–30 ms | 16–30 ms; not lama > 150 ms hanya nada bersama (EnglishWaltz2 C→F: pc0), sama dengan baseline |

## 4. Hasil R1–R6

| ID | Hasil | Catatan |
|---|---|---|
| R1 | **PASS** | 0 not Fill terbawa di 4 Style. Baseline gagal di EnglishWaltz2, HardRock2, ROSITA |
| R2 | **PASS** | tetap 0 |
| R3 | **PASS** | loop Main yang sama tidak terpotong; angka identik |
| R4 | **PASS (tidak berubah)** | F8 tetap ada (sengaja tidak diperbaiki) |
| R5 | **PASS** | paritas ON/OFF identik; respons akor tidak memburuk |
| R6 Recorder V1B/V1C | **PASS** | `RecorderRtTest` + `FullArrangerRecorderRtTest` OK (3) |
| R6 Recorder parity | **PASS** | `RecorderParityRtTest` OK di ROSITA, EnglishWaltz2, Love Song |
| R6 E343 SHORT_GATE | **PASS** | `E343DrumNoteOffRtTest` (EnglishWaltz2) OK |
| R6 E343 AUTO | **PASS** | `E343AutoMapRtTest` OK di ROSITA, EnglishWaltz2, Love Song |
| R6 Device Routing | **PASS** | `DeviceRoutingRtTest` OK |
| R6 Transpose/ACMP/LEFT | **PASS** | `TransposeAcmpLeftTest` OK |
| R6 CPV JVM | **PASS** | 385 tes OK |
| Guard isolasi `tools/test_midi_song_guard.py` | **FAIL (sesuai dugaan)** | Guard membekukan folder `arranger/` (FROZEN) dan daftar file yang diizinkan. P1 sengaja mengubah `StyleSequencer.kt`. Guard **tidak** diubah dalam eksperimen ini; kalau P1 diadopsi, guard harus diberi izin eksplisit untuk 11 baris ini. Guard juga menolak dua file untracked audit lama yang ada di working tree |
| CI / APK | NOT TESTED | sengaja tidak dibuat (lingkup eksperimen); tidak ada workflow untuk branch ini |

## 5. Risiko

- **Musikal:** not yang **sengaja** disambung dari Fill/Intro ke Main sekarang berhenti di batas section, sama seperti perilaku transisi yang memutus selama ini. Not yang NOTE OFF-nya ada tepat di akhir section (dibuang parser) kini berakhir di akhir section, sesuai data aslinya. Loop section yang sama tidak terpengaruh.
- **Recorder:** tap engine mencatat NOTE OFF di batas section. Paritas live = file = playback tetap lulus.
- **BASSMIDI:** menerima NOTE OFF yang sama (satu `stageIo`). Resolver, SoundFont, dan DSP tidak berubah. Not menggantung yang sama sebelumnya juga terjadi di internal (lihat audit F2).
- **Waktu:** `awaitMusicalTick` hanya menunggu sampai tick awal section baru, yang memang akan ditunggu `playOnce` berikutnya. Event section baru tidak tertunda. Kalau transisi datang selama penungguan, `playOnce` berikutnya memutus seperti biasa dan jalur transisi melepas sisanya.

## 6. Pemeriksaan diff

- Produksi: hanya `StyleSequencer.kt`, +11 / −0, semuanya di cabang antrean section.
- Tidak ada perubahan di parser, CASM, router, adapter E343, native, workflow, atau tes lain.
- Tidak ada merge, rebase, atau force push.

## 7. Keputusan

**GO** untuk uji fisik eksperimental:
- R1 lulus (cacat baseline terbukti dan hilang);
- R2–R6 lulus di cakupan yang tersedia;
- tidak ada regresi terdeteksi;
- perubahan hanya P1 dan tes.

**Belum layak di-merge:**
- guard isolasi harus diperbarui secara eksplisit;
- APK eksperimen harus dibuat (di luar lingkup ini);
- uji fisik di bawah ini harus lulus.

## 8. Rekomendasi uji fisik (Redmi Pad + PSR-E343)

1. **EnglishWaltz2:** Main B → Main C (memutar FillCC), lalu ganti akor 2–3 detik kemudian.
   - Sebelum patch: guitar ch13 dari Fill terus berbunyi lalu ikut pindah ke akor baru.
   - Sesudah patch: berhenti di awal Main C.
   - Bandingkan juga Main A → Main B (FillBB): harus sama di kedua versi.
2. **ROSITA:** Main B → Main C (FillCC, ch12) dengan pemeriksaan yang sama.
3. **OUTPUT MODE BOTH vs INTERNAL** pada Style dan Fill yang sama: pastikan tidak ada nada Fill yang tertinggal di keduanya.
4. **Regresi dengar:**
   - Main yang sama berputar ≥ 3 kali: pad/akor tidak terputus;
   - Intro → Main;
   - Ending;
   - Recorder FULL ARRANGER (rekam → LOAD TAKE).
5. Kalau gejala "bertumpuk/terlambat" di **Love Song** tetap ada: Love Song tidak punya not yang kehilangan OFF (R1 = 0 di baseline), jadi penyebabnya di luar P1. Lanjutkan T1–T3 dari laporan audit: Local Control, NOTE ON ganda, polifoni.
