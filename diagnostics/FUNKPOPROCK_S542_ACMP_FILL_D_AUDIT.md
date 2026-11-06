# FunkPopRock S542: audit ACMP dan Fill DD → Main D (PSR-E343 MIDI OUT)

- **Mode:** AUDIT SAJA. Tidak ada patch produksi. Kode produksi, tes, fixture, dan workflow tidak diubah. Tidak ada APK.
- **Label bukti:**
  - **SOURCE**: isi file Style atau kode sumber.
  - **HOST**: simulasi rantai produksi di host (RtRig, port USB palsu, synth tiruan).
  - **PHYSICAL**: pengamatan pengguna di Redmi Pad + PSR-E343. Belum diverifikasi di sini.
  - **HYPOTHESIS**: dugaan yang belum dibuktikan.
- Hasil HOST hijau **bukan** bukti kualitas musikal di E343.

## 1. Executive Summary

1. **Fill DD → Main D bersih di HOST.**
   - Pada semua skenario (ACMP OFF/ON, akor tetap, ganti akor, memori akor, tanpa akor), tidak ada not Fill yang masih berbunyi saat Main D mulai.
   - Tidak ada NOTE ON ganda di batas section, tidak ada Bank/PC atau ledakan CC di batas.
   - Paritas MIDI OUT = BASSMIDI identik.
   - **Baseline `b81cb76` dan P1 identik** untuk Style ini, jadi **P1 tidak berpengaruh** pada FunkPopRock.
2. **ACMP OFF lebih rapi memang karena desain.**
   - ACMP OFF hanya mengirim drum (ch9/ch10); semua part harmonik dibuang (`DROP_ACMP_OFF`).
   - ACMP ON menambah sekitar 115–130 NOTE ON per birama dari 6 channel melodik.
   - Main D dan Fill DD adalah section **paling padat** di Style ini: drum 70/79 NOTE ON per birama (Main A: 28).
   - Total sekitar 45–60 NOTE ON per ketukan pada 120 BPM.
3. **Cacat terverifikasi (HOST + SOURCE), memicu "berantakan walau tanpa menekan tombol":**
   - Kondisi: ACMP ON sejak aplikasi dibuka (default) dan belum pernah ada akor.
   - Akibatnya part harmonik dimainkan **tanpa filter chord-mute**.
   - **Bs a dan Bs b berbunyi bersamaan** di ch11: 124 NOTE ON alih-alih 62, dengan 57 NOTE ON pada tuts yang masih aktif di channel yang sama.
   - **Ogn Rt ikut berbunyi**, dan semua part memainkan akor sumber CM7 apa adanya.
4. **Drum: masalah terpisah, terverifikasi (SOURCE + dokumen E343).**
   - Part Pc (Rhythm 1, ch9) meminta kit 127/0/PC28, yang **tidak ada di E343**.
   - Not 17/18/26 di kit E343 menjadi **Scratch Push / Scratch Pull / Brush Swirl**.
   - Drum tidak pernah mendapat NOTE OFF di MIDI OUT (perilaku LEGACY).
   - Perbaikan SHORT_GATE untuk Brush Swirl hanya berlaku untuk EnglishWaltz2 ch10 not 26, jadi tidak mencakup FunkPopRock ch9 not 26.
   - Main D memainkan not 26 dua kali lebih sering daripada Main C.
5. **Penyebab "paling parah di Fill DD → Main D" (HYPOTHESIS terkuat):**
   - kepadatan tertinggi di Style ini;
   - ditambah drum tanpa NOTE OFF dan suara kit yang salah;
   - ditambah Bank LSB asli yang tidak dikenal E343 (mode ORIGINAL);
   - kemungkinan voice stealing di E343.
   - Bukan not menggantung dari Fill.

**GO/NO-GO:** NO-GO untuk patch P1 sebagai solusi FunkPopRock. GO untuk menyiapkan dua kandidat patch kecil terpisah (bagian 12), yang masing-masing butuh eksperimen dan uji fisik sendiri.

## 2. Baseline SHA

| Item | Nilai |
|---|---|
| Branch referensi (P1) | `experiment/e343-fill-note-release-p1` @ `c63dec9f81f5264d1c5d3f3cc45884d328ed214a` |
| Baseline pembanding | `feature/midi-song-v1` Device Routing V1 @ `b81cb760f79a00983dee80dd9435a2afb0d9c861` (worktree terpisah di luar repo) |
| Audit fill sebelumnya | `4e4871cda3ba5369737c429f6a8cce6b644e3071` (`diagnostics/PSR_E343_MIDI_OUT_FILL_AUDIT.md`) |
| Harness | Tes scratch di luar repo (`FunkAuditScratchTest`), dijalankan lewat `tools/test_rtdiag.py` yang disalin; tidak di-commit |

## 3. Identitas Style

| Item | Nilai (SOURCE) |
|---|---|
| File | `FunkPopRock.S542.prs` (upload pengguna) |
| SHA-256 | `9eee8d1d218f38296330fa967e222286dd249f5e55f6460510a23857212dde3e` |
| Ukuran | 85998 byte |
| Format | SFF1 (MThd 1, CASM 1, Ctab 9, Ctb2 0, Cntt 6, CSEG 3, OTSc 1) |
| PPQ / tempo / birama | 1920 / 120 BPM / 4/4 (1 birama = 2000 ms) |
| Section (tick) | IntroA 7680, IntroB 61440, IntroC 30720, MainA–D 30720 (4 birama), FillAA/BB/CC/DD 7680, FillBA 7681, EndingA 15360, EndingB 30720, EndingC 38400 |
| Fill untuk C→D | Tidak ada `FillCD`, jadi `fillForTransition` memakai `FillDD` (SOURCE: `ArrangerBrain.kt:968-974`) |

## 4. Struktur Main D / Fill DD (SOURCE, parser produksi)

CASM sama di Main C, Main D, Fill CC, dan Fill DD. Akor sumber semuanya 0/2 (C M7). Channel ditulis 1-based.

| src | Part | → ch | Peran | NTR / NTT | High key | RTR | Mask chord-mute | Bank/PC (tick 0) | ON Main D | ON Fill DD |
|---|---|---|---|---|---|---|---|---|---|---|
| 9 | Dr | 10 | Rhythm 2 | 1/0 bypass | 6 | 1 | semua | 127/0/PC1 | 211 | 59 (1 tanpa OFF dalam section) |
| 6 | CCCRASH | 10 | Rhythm 2 | 1/0 | 6 | 3 | hanya bit 34 ("cancel") | 127/0/PC1 | 0 | 0 |
| 8 | Pc | 9 | Rhythm 1 | 1/0 | 6 | 1 | semua | **127/0/PC28** | 68 | 20 |
| 10 | Bs a | 11 | Bass | 0/1 + bass | 3 (lim 28-127) | 2 | Maj, 6, M7, add9, m6, m7, m7b5, dim, dim7, 7, 7sus4, 7b5, 1+5, 1+8, 1+2+5, … | 0/114/PC34 | 38 | 9 |
| 1 | Bs b | 11 | Bass | 0/1 + bass | 3 (lim 28-127) | 2 | aug, m, m(9), mM7, mM7(9), M7aug, 7aug, sus4 | 0/114/PC34 | 38 | 9 |
| 11 | 60Cl | 12 | Chord 1 | 1/2 chord | 7 | 1 | semua | 0/117/PC29 → 0/110/PC28 | 216 | 54 |
| 3 | Pf Bs | 13 | Chord 2 | 0/1 + bass | 11 | 2 | semua | 0/113/PC1 | 36 (sumber 24..36) | 12 |
| 12 | Pf | 13 | Chord 2 | 1/2 | 7 | 1 | semua | 0/113/PC1 | 60 | 15 |
| 4 | Ogn Rt | 14 | Pad | 0/2 | 6 | 1 | add9, 6(9), m7, m7b5, 7, 7(9), … (tipe ber-7) | 0/117/PC19 → 0/121/PC17 | 1 | 1 |
| 13 | Ogn | 14 | Pad | 1/2 | 7 | 1 | semua | sama | 3 | 3 |
| 14 | ClGt | 15 | Phrase 1 | 1/2 | 7 | 1 | semua | 0/117/PC29 → 0/110/PC28 | 112 | 28 |

**Hasil pemeriksaan sumber:**
- Untuk part melodik, jumlah ON = OFF.
- Tidak ada overlap not dalam satu sumber.
- Tidak ada ON/OFF di tick yang sama.
- Satu-satunya not tanpa OFF di dalam section adalah 1 not Dr di Fill DD (drum, tidak masuk ledger).

**Kepadatan per birama (NOTE ON, satu bass):**

| Section | Melodik | Drum |
|---|---|---|
| Main A | 61 | 28 |
| Main B | 95 | 40 |
| Main C | 128 | 53 |
| **Main D** | 116 | **70** |
| Fill CC | 129 | 61 |
| **Fill DD** | 122 | **79** |

**Not Pc (Rhythm 1) per section:**
- Main C: 26×4, 17×2, 18×2.
- **Main D: 26×8**, 17×2, 18×2.
- Fill DD: 26×2, 17×2, 18×2.

## 5. ACMP ON vs OFF (HOST, mode ORIGINAL, rantai produksi)

Skenario per run: start → 3 birama (Main C atau Main D) → `selectMainVariation(MainD)` → Fill DD → Main D 4 birama → STOP. Akor C = tuts 36/40/43 (≤ split 54).

| Skenario | Kondisi | NOTE ON ch9 / ch10 | NOTE ON ch11 / 12 / 13 / 14 / 15 | Puncak not melodik bersamaan | Not Fill terbawa ke Main D |
|---|---|---|---|---|---|
| A | ACMP OFF, Main D saja | 70 / 215 | — (dibuang) | 0 | — |
| B | ACMP OFF, Main C → Fill DD → Main D | 119 / 344 | — | 0 | 0 |
| C | ACMP ON, Main D, akor C ditahan | 70 / 215 | 39 / 217 / 100 / 6 / 112 | 17 | — |
| D | ACMP ON, C → Fill DD → Main D, akor C ditahan | 119 / 344 | 62 / 401 / 168 / 21 / 224 | 17 | **0** |
| E | Seperti D, akor F saat Fill, G setelah Main D | 111 / 319 | 58 / 379 / 163 / 26 / 211 | 17 | **0** |
| F | Akor C ditekan lalu dilepas sebelum start (memori akor) | sama dengan D | sama dengan D | 17 | **0** |
| G | **ACMP ON, tidak pernah ada akor** (default saat aplikasi dibuka) | 119 / 344 | **124** / 401 / 168 / **28** / 224 | 19 | 0 |

**Pembacaan:**
- **Memori akor (F):** identik dengan menahan akor (D). "Tanpa menekan tombol E343" setelah akor pernah diberikan sama saja dengan akor C yang terus ditahan (HOST).
- **Tanpa akor sama sekali (G):**
  - `StyleSequencer.acmpHarmonyEnabled = true` dan `awaitChord = false` saat start. `setAcmpHarmony(true)` keluar lebih awal karena nilainya sudah `true`, sehingga `awaitChord` tidak pernah di-set (SOURCE `StyleSequencer.kt:188-196`).
  - Akibatnya part harmonik dimainkan dengan `chord == null`. `policyScore` melewati mask chord-mute (`StyleSequencer.kt:1216`), jadi **Bs a + Bs b dan Ogn Rt + Ogn berbunyi bersamaan**, tanpa transposisi (akor sumber CM7: not 59/71 = B terdengar).
  - Di ch11 ada 57 NOTE ON pada tuts yang masih berbunyi (dua pola bass memukul tuts yang sama).
  - Ini identik di baseline dan P1.
- **ACMP OFF:** hanya drum yang keluar. Ini sesuai keluhan "jauh lebih rapi" (PHYSICAL), dan konsisten dengan desain (`DROP_ACMP_OFF`, `StyleSequencer.kt:1511`).

## 6. CASM / NTT / RTR

| Pemeriksaan | Hasil | Label |
|---|---|---|
| Bs a vs Bs b (ch11) | Mask **saling eksklusif** dan gabungannya = semua 34 tipe. Dengan akor terdeteksi, tepat satu bass berbunyi (ch11 = 39 ON per 4 birama, sama dengan 38 di sumber + 1) | SOURCE + HOST |
| `yamahaChordType` | 19 kualitas akor dipetakan ke 0..33. Semuanya tercakup oleh salah satu mask bass | SOURCE |
| Pf Bs + Pf (ch13) | Keduanya mask "semua", jadi selalu berbunyi bersamaan (sesuai desain Style: tangan kiri bass + tangan kanan akor). **0 tuts ganda** di HOST. Pf Bs (NTT1 + bass) menghasilkan **C1 (24) pada piano**: register sangat rendah, menumpuk dengan Bass | SOURCE + HOST |
| Ogn Rt + Ogn (ch14) | Ogn Rt hanya untuk tipe akor ber-7. Dengan akor C: hanya Ogn. Tanpa akor: keduanya (kasus G) | SOURCE + HOST |
| CCCRASH | Mask hanya bit 34 (cancel), dan tidak punya not di Main D / Fill DD. Tidak berpengaruh | SOURCE |
| Perubahan pitch CASM (E: C→F→G) | ch11 4→9 pitch, ch13 5→13, ch15 4→9. Akor diterapkan saat Fill dan Main D. Tidak ada NOTE ON ganda dan tidak ada not lama yang tertahan | HOST |
| RTR | Bass/Pf Bs RTR 2 (pitch shift ke root), lainnya RTR 1 (pitch shift). Ledger tidak meninggalkan not | HOST |
| 60Cl + ClGt | Dua part gitar terpisah (ch12, ch15) memainkan pola 16-an. Di AUTO E343 keduanya menjadi **Clean Guitar 0/112/28 yang sama** | HOST (peta) / HYPOTHESIS (efek dengar: keruh) |

## 7. Siklus hidup not di Fill DD → Main D (HOST, skenario D, ORIGINAL)

- **Timeline (NOTE ON per ketukan 500 ms, drum | melodik):**
  - 1 birama sebelum Fill: 14|35, 14|25, 10|39, 14|28
  - **Fill DD:** 21|26, 17|29, 19|33, 22|33
  - **Main D birama 1–2:** 17|27, 19|26, 17|35, 17|30, 17|24, 16|26, 21|34, 13|32
- **Batas (Main D mulai di 7996 ms, NOTE ON pertama di 8001 ms):**
  - not melodik dari Fill yang masih berbunyi: **0**;
  - not Fill yang terbawa > 20 ms: **0**;
  - pesan dalam ±30 ms: 19 ON, 14 OFF, **0 CC, 0 PC**.
- **Downbeat Main D:** 15 NOTE ON dalam 2 ms di 6 channel (ch13: 4, ch10: 4, ch14: 3, ch9: 2, ch11: 1, ch12: 1). Ini juga jumlah maksimum NOTE ON dalam 2 ms selama run.
- **Ledakan pesan:** satu-satunya ledakan besar adalah inisialisasi saat start (103–113 pesan dalam 5 ms: Bank/PC/CC7/CC10/CC11/CC91/CC93 per channel). Di batas Fill → Main tidak ada.
- **Kepemilikan / NOTE OFF hilang:**
  - Di ch11–16, semua NOTE ON ditutup NOTE OFF, kecuali not yang masih ditahan saat STOP (ditutup CC123 saat STOP: ch11 1, ch12 1, ch13 5, ch14 3).
  - Drum (ch9/ch10): **0 NOTE OFF** (LEGACY: drum tidak di ledger, router tidak melepas drum).
- **Tuts ganda di channel melodik:** 0 di skenario C–F, **57** di skenario G (ch11).
- **Paritas:** NOTE ON/OFF MIDI OUT = BASSMIDI per channel di semua run.
- **Voice stealing:** tidak bisa diukur di host (synth tiruan). UNKNOWN untuk E343.

## 8. Puncak not aktif

| Ukuran | Nilai (HOST) |
|---|---|
| Puncak not melodik bersamaan (ch11–16), skenario C–F | 17 |
| Puncak, skenario G (tanpa akor) | 19 |
| Per channel (D) | ch11 1, ch12 4, ch13 5, ch14 3, ch15 4 |
| Drum | Tanpa NOTE OFF: setiap pukulan menempati voice E343 sampai suaranya habis. Ride, Crash, Hi-Hat Open, Tambourine, dan Brush Swirl punya ekor panjang |
| Laju not | Sekitar 90–100 NOTE ON per detik di Main D / Fill DD dengan ACMP ON. Dengan ACMP OFF sekitar 35 per detik |

- **UNKNOWN:** polifoni maksimum PSR-E343 tidak ada di dokumen repo dan tidak diverifikasi di sini.
- **HYPOTHESIS:** 17–19 not melodik ditambah ekor drum tanpa NOTE OFF bisa mendekati batas polifoni E343 di section terpadat. Voice stealing akan terdengar sebagai not terpotong atau "berantakan".

## 9. Mapping drum E343

| Item | Nilai | Label |
|---|---|---|
| Rhythm 1 (ch9) 'Pc' | Request **127/0/PC28** | SOURCE |
| Kit E343 yang ada (manual hal. 53–55, `docs/yamaha/E343_DRUMKITS.tsv`) | PC 1, 2, 9, 17, 25, 26, 33, 40, 41, 49, 113, 115, 128, SFX 1/2. **PC28 tidak ada** | SOURCE |
| Mode ORIGINAL | Aplikasi mengirim 127/0/PC28 ke E343. Reaksi E343 terhadap kit yang tidak ada belum diketahui: bisa mengabaikan PC (tetap memakai voice lama di ch9) atau memakai kit lain | HOST (wire) / UNKNOWN (E343) |
| Mode AUTO E343 / + BALANCE | 127/0/PC28 → **Standard Kit 1** (127/0/1), "100 % sound". Hanya berarti not-nya *ada suaranya*, bukan suaranya *cocok* | HOST |
| Not Pc yang dikirim | 17, 18, 26, 32, 42, 46 dikirim apa adanya. Di Standard Kit 1: **Scratch Push, Scratch Pull, Brush Swirl**, Sticks, HH Closed, HH Open | SOURCE + dokumen E343 |
| Rhythm 2 (ch10) 'Dr' + CCCRASH | 127/0/PC1 = Standard Kit 1, ORIGINAL kompatibel. Not 36, 40, 42, 43, 44, 45, 46, 47, 49, 53, 54, 57, 59 semuanya berbunyi | HOST |
| NOTE OFF drum | 0 NOTE OFF di ch9/ch10. Eksperimen SHORT_GATE (`E343DrumNoteOffExperiment`) dibatasi ke EnglishWaltz2, channel 10 (index 9), not 26. FunkPopRock ch9 not 26 (Brush Swirl, gejala "tres-tres-tres" di EnglishWaltz2) **tidak tercakup** | SOURCE |
| Kesimpulan | Drum adalah **masalah terpisah**, tidak bergantung pada ACMP, sesuai keluhan "timing drum OK, instrumennya tidak cocok" (PHYSICAL). Mapping tidak diubah dalam audit ini | — |

## 10. Baseline `b81cb76` vs P1 (HOST, ORIGINAL)

| Skenario | Baseline | P1 |
|---|---|---|
| B (ACMP OFF, Fill DD → Main D) | terbawa 0; batas 7 ON | terbawa 0; batas 7 ON |
| D | ON ch11–15 62/402/168/21/224; terbawa 0; puncak 17 | 62/401/168/21/224; terbawa 0; puncak 17 |
| E | terbawa 0; puncak 17 | terbawa 0; puncak 17 |
| F | sama dengan D | sama dengan D |
| G | ch11 124 ON, 57 tuts ganda; puncak 19 | **identik** |

Selisih ±1–2 not di ch12 berasal dari jitter waktu STOP, bukan perilaku.

**Kesimpulan:** Fill DD tidak punya not melodik tanpa NOTE OFF, jadi P1 tidak punya apa pun untuk dilepas di Style ini. **P1 tidak relevan untuk keluhan FunkPopRock** (tetapi juga tidak merugikan).

## 11. Temuan

### VERIFIED

- **V1 (HOST):** Fill DD → Main D tidak meninggalkan not, tidak menggandakan NOTE ON, dan tidak mengirim Bank/PC atau CC di batas. Berlaku di baseline dan P1, untuk ACMP ON dan OFF, ORIGINAL dan AUTO E343 + BALANCE.
- **V2 (SOURCE + HOST):** ACMP ON tanpa akor pernah diterima (default `acmpHarmonyEnabled = true`, `awaitChord = false`) membuat mask chord-mute dilewati:
  - Bs a + Bs b berbunyi bersamaan (ch11 2× NOTE ON, 57 tuts ganda);
  - Ogn Rt ikut berbunyi;
  - harmoni = akor sumber CM7 tanpa transposisi.
- **V3 (SOURCE):** Pc meminta kit 127/0/PC28 yang tidak ada di E343. Not 17/18/26 menjadi Scratch/Brush Swirl di Standard Kit 1 (target AUTO).
- **V4 (SOURCE + HOST):** Drum tidak mendapat NOTE OFF di MIDI OUT. Pengecualian SHORT_GATE tidak mencakup Style ini. Main D memainkan Brush Swirl (26) 8× per 4 birama (Main C: 4×).
- **V5 (SOURCE + HOST):** Main D / Fill DD adalah section terpadat (drum 70/79 per birama, sekitar 190 NOTE ON per birama dengan ACMP ON). ACMP OFF menghilangkan sekitar 60 % not.
- **V6 (HOST):** Mode ORIGINAL mengirim Bank LSB 114/110/113/121 yang tidak ada di daftar voice E343. AUTO memetakan semuanya ke LSB 112 dan menurunkan CC7 Pad 64 → 53.

### SUSPECTED (HYPOTHESIS)

- **S1:** Gejala "paling parah di Fill DD → Main D" berasal dari kepadatan tertinggi, ditambah ekor drum tanpa NOTE OFF dan suara kit yang salah, sehingga polifoni E343 penuh dan terjadi voice stealing.
- **S2:** Di mode ORIGINAL, E343 menolak atau mengganti voice dengan LSB yang tidak dikenal, sehingga part terdengar dengan voice yang tidak terduga.
- **S3:** Pf Bs memainkan C1 di piano, menumpuk dengan Bass. 60Cl dan ClGt sama-sama Clean Guitar di AUTO. Keduanya membuat bunyi keruh.
- **S4:** Kalau pengguna menyalakan aplikasi dengan ACMP ON tanpa pernah memainkan akor, keluhan "berantakan walau tanpa menekan tombol" persis kasus V2.

### UNKNOWN

- Polifoni maksimum PSR-E343 dan algoritma voice stealing-nya.
- Reaksi E343 terhadap 127/0/PC28 dan Bank LSB yang tidak dikenal.
- Mode OUTPUT (ORIGINAL / AUTO / BALANCE) dan OUTPUT MODE (MIDI OUT / BOTH) yang dipakai pengguna saat gejala muncul.
- Apakah akor pernah dimainkan sebelum gejala.
- Status Local Control pengguna.

## 12. Kandidat akar masalah dan rekomendasi patch minimal (TIDAK diimplementasikan)

| # | Kandidat | Bukti | Patch minimal yang diusulkan | Prioritas |
|---|---|---|---|---|
| K1 | ACMP ON tanpa akor memainkan semua sumber CASM tanpa mask | V2 | **P2:** saat Start dengan ACMP ON dan `currentChord == null`, perlakukan seperti `awaitChord = true` (harmoni diam sampai akor pertama, seperti setelah OFF→ON). Alternatif yang lebih sempit: ketika `chord == null`, pilih satu policy per destinasi memakai tipe akor sumber CASM (0/2 = M7) sebagai pengganti mask. Lingkup: `StyleSequencer` (`setAcmpHarmony` / start), beberapa baris | **1** |
| K2 | Kit Pc 127/0/PC28 tidak ada di E343, not 17/18/26 tidak cocok | V3 | **P3 (adapter E343, bukan sequencer):** di AUTO, untuk Rhythm 1 yang kit-nya bukan kit E343, mute/skip not di luar daftar "aman" (17/18/26) atau petakan ke suara perkusi yang setara. Butuh tabel per not yang ditinjau pengguna; jangan menebak | 2 |
| K3 | Drum tanpa NOTE OFF (Brush Swirl berkelanjutan) | V4 | **P4:** perluas SHORT_GATE dari "EnglishWaltz2 + ch10 + not 26" ke "not 26 di ch9/ch10 pada Style apa pun" (sudah diverifikasi fisik 120 ms di EnglishWaltz2). Hanya MIDI OUT | 3 |
| K4 | Kepadatan atau polifoni E343 | V5, S1 | Belum ada patch. Pertama ukur di perangkat: matikan per part (Mute) di Main D, mulai dari 60Cl dan ClGt, lalu bandingkan | Uji fisik |
| K5 | Bank LSB asli tidak dikenal E343 | V6, S2 | Tidak perlu patch. Uji dengan AUTO E343 + BALANCE vs ORIGINAL | Uji fisik |

Patch yang **tidak** disarankan:
- CC123 global;
- delay atau throttle;
- perubahan parser atau CASM transformer;
- menghapus pengaman P1.

## 13. Risiko regresi (bila K1–K3 nanti dikerjakan)

- **K1:** Style yang memang diharapkan berbunyi sebelum akor pertama (preview tanpa akor) akan diam sampai ada akor. Ini mengubah perilaku start default. Butuh R-tes:
  - start tanpa akor;
  - start dengan akor;
  - OFF→ON;
  - Recorder FULL ARRANGER;
  - paritas BASSMIDI.

  Varian "satu policy per destinasi" lebih kecil dampaknya, tetapi menyentuh `selectPolicy` (dipakai di semua Style).
- **K2:** Hanya mode AUTO di adapter E343. Risikonya kehilangan suara perkusi yang memang diinginkan. Harus opsional dan terlihat di UI.
- **K3:** Brush Swirl yang sengaja panjang di Style lain akan terpotong 120 ms. Hanya untuk MIDI OUT; BASSMIDI tidak berubah.
- Guard isolasi `tools/test_midi_song_guard.py` harus diberi pengecualian sempit dan ter-hash seperti P1. Jangan dilonggarkan.

## 14. GO / NO-GO

- **NO-GO:** P1 sebagai perbaikan FunkPopRock. Tidak ada not Fill yang tertinggal (baseline = P1).
- **NO-GO:** patch apa pun di jalur transisi Fill → Main untuk Style ini.
- **GO (eksperimen terpisah, satu per satu):**
  - K1 (P2, awaitChord saat Start tanpa akor);
  - lalu K3 (P4, SHORT_GATE not 26 umum);
  - K2 setelah tabel perkusi ditinjau pengguna.
- **Uji fisik yang dibutuhkan sebelum patch** (Redmi Pad + E343, Local Control OFF, catat mode OUTPUT):
  1. Buka aplikasi, ACMP ON, **mainkan satu akor dulu**, lalu Main C → Main D. Bandingkan dengan start tanpa akor.
  2. Ulangi di ORIGINAL vs AUTO E343 + BALANCE.
  3. Di Main D, mute bergantian: Rhythm 1 (Pc), 60Cl, ClGt, Pf. Catat part mana yang menghilangkan kesan "berantakan".
  4. ACMP OFF: dengarkan apakah suara scratch/swirl muncul di Rhythm 1.

Masalah fisik **belum dinyatakan selesai**.
