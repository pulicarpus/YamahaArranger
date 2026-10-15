# SF2 Binary Audit — Koreksi Katalog & Rekomendasi U0–U1

**Sumber:** `SF2.zip` dari Google Drive (id `17sScWkzOfGjV8wWxQFB0HtSn1AaQbp3b`).
- 921.652.682 byte; SHA-256 ZIP `6e9d4e1eaaa548187444bf409bd283e2c22a00e07cae57cadddf0fd3277897dd`.
- Diunduh lengkap (HTTP 200/206; ukuran sama dengan `content-range`).
- Isi: 4 SF2 + `PUT_SF2_FILES_HERE.txt`.

**Sifat:** read-only. Parser host (Python, mmap) membaca RIFF/pdta/sdta. Tidak ada perubahan repository, commit, push, atau APK. File SF2 dan sampel **tidak** disalin ke repo.

**Label:** **[T]** terbukti dari byte file / eksekusi parser; **[K]** indikasi kuat; **[?]** belum diketahui (butuh tes engine/perangkat).

---

## 1. Identitas file [T]

| File | Ukuran (byte) | SHA-256 file | = laporan perangkat | **pdtaSha256** (usulan U1) | Byte pdta |
|---|---:|---|---|---|---:|
| ColomboGMGS2_BM.sf2 | 273.067.684 | `9e4686e4…d47eb` | **cocok** | `ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506` | 446.466 |
| DRUMKIT YAMAHA PSR-SX700 & SX900 PRIME.sf2 | 152.669.056 | `a72158c0…8f8a` | **cocok** | `338385762217a9ecd9ecb8707fa71202a115da4eaad81441c27d714acbf61283` | 197.194 |
| MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2 | 342.996.838 | `e8c73561…4a82` | **cocok** | `cfb096e3c197570554313aaca227f7e207ac64867e9871a025c1b4b2c6e9c136` | 208.430 |
| yamaha tyros 4_just_t4_fixed.sf2 | 526.011.878 | `de5b1404…36ce` | **cocok** | `16bb60deafdf426cdc7e269b6c5f434cd5b7db6e14a83f86d17f54720176217a` | 260.470 |

**Definisi `pdtaSha256` (normatif untuk U1):** SHA-256 atas payload chunk `LIST` pdta, yaitu mulai dari fourcc `pdta` sampai akhir chunk. Panjangnya sama dengan field size `LIST`, tanpa header 8 byte `LIST`+size. Nilai di tabel di atas adalah golden test.

Struktur [T]:
- Keempat file: `RIFF sfbk` dengan `INFO`, `sdta`, `pdta`; trailing 0 byte; tanpa `sm24`; terminal `EOP`/`EOI`/`EOS` ada.
- Jumlah tabel **sama persis** dengan ekspor perangkat:

  | Font | Preset | Instrumen | Sampel |
  |---|---:|---:|---:|
  | Colombo | 892 | 765 | 3.288 |
  | Prime Drum | 21 | 37 | 2.257 |
  | Prime Melodi | 93 | 124 | 2.322 |
  | Tyros | 142 | 285 | 2.557 |

- Modulator kustom:

  | Font | pmod | imod |
  |---|---:|---:|
  | Colombo | 16 | 415 |
  | Prime Drum | 0 | 165 |
  | Prime Melodi | 8 | 243 |
  | Tyros | 0 | 0 |

INFO yang relevan untuk kebijakan:
- **Colombo:** "CC-BY-SA 4.0 … **Please be kind NOT TO Redistribute anything from this sample list**". Juga: "Make sure that you have ENABLED … **USE BANK 127 FOR DRUM KITS IN XG MODE**". Ada catatan changelog: "Change of GM Honky Tonk (Now **Tack Piano**, Following Roland Suit)".
- **Prime Drum / Melodi:** "… BUAT PENGGUNA **MIDI VOYAGER** (BASSMIDI). JANGAN REMAPPING ULANG".
- **Tyros:** "Yamaha tyros 4 gm soundfont" (Polyphone, 2018).

---

## 2. Validasi ekspor metadata perangkat [T]

| Temuan | Detail | Dampak |
|---|---|---|
| Tabel P (bank/PC/nama) Prime Melodi dan Tyros | identik dengan `full-presets.tsv` | – |
| **Apostrof ASCII (0x27) diekspor sebagai `?`** | Colombo: `60's Synth Strings`, `Shigi's Piano`, `80's Sci-Fi`, `90's Jazz Guitar`, `Beatles' Mellotron`, dst. Drum: `CLOW D'ACADEMY`, `CLOW D'ACADEMY II`, `D'ROSTA SX700-SX900` | Pencocokan nama persis (allowlist/katalog) dari ekspor bisa salah. **Exporter harus menulis nama mentah (hex) di samping teks.** |

---

## 3. Identitas sampel [T]

Data sampel di-hash (SHA-1 dari frame `start..end`).

| Font | Nama sampel unik | Nama dengan >1 data berbeda | Nama `SATRIOMUSIK SF2-xx` | …dengan >1 data berbeda | Data identik di bawah nama berbeda |
|---|---:|---:|---:|---:|---:|
| Colombo | 3.264 | 20 | 0 | – | 2 |
| Prime Drum | 1.206 | **396** | 100 | **95** | 424 |
| Prime Melodi | 1.188 | **300** | 100 | **100** | 164 |
| Tyros | 2.555 | 2 | 0 | – | 179 |

**Kesimpulan:** nama sampel Prime **tidak bisa dipakai sebagai bukti identitas atau family**. Bobot bukti dari nama sampel Prime harus 0. Colombo dan Tyros cukup konsisten.

---

## 4. Atenuasi (generator 48) — temuan level paling penting [T]

| Font | Zona | Zona dengan total atenuasi **negatif** | Preset terdampak | Minimum |
|---|---:|---:|---:|---:|
| Colombo | 50.701 | 761 | 81 | −175 cB |
| Tyros | 3.689 | 567 | 31 (termasuk FINGER BASS, tyros 4 grand) | −367 cB |
| Prime Melodi | 5.949 | **2.230** | 28 | **−1440 cB** |
| Prime Drum | 6.605 | **4.393 (66%)** | 16/21 kit (termasuk STANDAR PSR-SX, Kendang Alex) | **−1440 cB** |

- Spesifikasi SF2 menetapkan atenuasi awal 0…1440 cB. Nilai negatif di luar spesifikasi.
- Bila diterapkan mentah, nilai ini menghasilkan penguatan tidak masuk akal (+45…+132 dB pada kit Prime). Kit ini dipakai normal oleh pengguna MIDI Voyager (BASSMIDI). Jadi **[K] BASSMIDI meng-clamp total negatif ke 0 atau ke batas tertentu**.
- Besar clamp dan faktor skala atenuasi positif (spesifikasi 1,0 vs EMU 0,4) **[?]**, harus ditutup lewat probe U0 (§7).
- Relevan dengan P2: parent peak 4,64 dengan role melodik ≤ 0,457. Bila BASSMIDI hanya membatasi sebagian (misalnya sampai +x dB), kit Prime menjadi sumber puncak yang belum diukur.

---

## 5. Level dan karakter sampel untuk ROSITA [T untuk data sampel; model level di bawah]

Metode:
- RMS sampel (jendela 1 s; drum 0,5 s) dikurangi atenuasi zona, lalu dijumlahkan secara power atas zona yang aktif pada key/velocity ROSITA.
- **Tidak** termasuk velocity curve, CC7/11, envelope, filter, dan modulator. Model A = atenuasi diclamp 0–1440 sesuai spesifikasi. Model B = clamp × 0,4 (EMU).
- Modulator kustom pada preset target hanya: Colombo Clean Guitar (vel→filter) dan Prime Honky Tonk (CC23→atenuasi, CC23 tidak dikirim ROSITA). Tidak ada yang mengubah kurva velocity standar.

### 5.1 Melodik (dB relatif, makin tinggi makin keras)

| Peran (key, vel ROSITA) | Prime Melodi | Tyros | Colombo | Catatan karakter (centroid serangan) |
|---|---|---|---|---|
| Clean Gtr 0/27 (55–67, v91) | **Clean yamaha: A −36,6 / B −18,6** (3 zona, att 30 dB) | t4 clean: −16,7 / −16,7 | Clean Guitar: −28,1 / −19,1 | Colombo paling terang (2,4 kHz) vs Prime 1,3 kHz, Tyros 1,1 kHz |
| Honky Tonk 0/3 (67–79, v89) | **Yamaha Honky Tonk: A −43,4 / B −26,6** (att 28 dB) | Honky Tonk: −16,0 / −11,8 (4 zona) | Tack Piano: −20,7 / −17,1 (layer Bright Piano + **Harpsichord**) | **Prime Honky Tonk adalah preset paling pelan dari semua kandidat** |
| Bass 0/33 (31–43, v125) | BASS PSR: −9,5 / −9,5 | FINGER BASS: −14,3 / −14,3 | Finger Bass: −19,7 / −12,8 | Prime/Tyros gelap (≈0,23–0,29 kHz) |
| Strings 0/48 (60–67, v67) | String Yamaha: −9,1 / −9,1 | t4 strings marcato: −15,8 / −15,8 | String Ensemble: −23,2 / −15,1 | – |
| Synth Voice 0/54 | – | −21,1 / −13,6 | −18,9 / −12,9 | – |
| Choir 0/52 | (Suling = Flute, −15,5) | tyros 4 choir: −20,3 / −20,3 | Choir Aahs: −26,6 / −17,6 | – |
| Pad yang dipilih label, 0/89 | – | woarm pad: −22,7 / −22,7 | Pad 2 Warm: −17,2 / −11,2 | – |

Kesimpulan [T untuk data; K untuk dampak dengar]:
1. **Gitar tidak lebih keras secara intrinsik.** Prime Clean yamaha justru salah satu yang terpelan (model A) dan setara dengan yang lain (model B).
2. **Prime "Yamaha Honky Tonk", yaitu entri allowlist Policy C, terpelan dari semua kandidat** (−43,4 / −26,6 dB). Nilai ini 27 dB (A) atau 15 dB (B) di bawah Tyros Honky Tonk. Di Main B–D, satu-satunya instrumen non-gitar di lapisan chord nyaris hilang. Ini sangat mungkin bagian dari kesan "semuanya gitar".
3. Prime BASS PSR dan String Yamaha relatif keras (−9 dB). Model level MIDI dari audit ROSITA (bass −0,3 dB, gitar −10…−14) menambah selisih ini.
4. Mana yang benar antara model A dan B mengubah peringkat Prime vs Tyros/Colombo hingga 18 dB. **Ini harus diukur di BASSMIDI** (probe §7), bukan ditebak.

### 5.2 Drum (vel 110, model A; level dB | centroid Hz | durasi s)

| Key ROSITA (makna XG) | Prime STANDAR PSR-SX 128/0 | Kendang Alex 128/8 | ALEX KEPRI 128/16 | **Colombo XG Room 127/8** | Colombo XG Std 127/0 | Tyros room kit 128/8 |
|---|---|---|---|---|---|---|
| 33 (XG **Bass Drum L**) | −12 \| 769 \| 0.4 (kick) | **tidak ada zone** | **tidak ada zone** | −19 \| 1757 \| 0.6 (kick) | −27 (SubKick) | −28 \| 5172 \| 0.6 (**bukan kick**, sampel "097") |
| 34 (XG Open Rim Shot) | −10 \| 4122 | **tidak ada** | **tidak ada** | −19 \| 7615 (snare) | −19 | −22 \| 7723 |
| 36 (Bass Drum H) | −9 \| 1900 | −15 \| 661 | −18 | −20 | −30 | −11 |
| 37 Side Stick | −22 \| 6175 | −10 \| **397 (rendah, bukan side stick)** | −13 \| 192 | −27 \| 7202 | −27 | −23 |
| 42 HH Closed | −19 \| 10110 | −30 \| 9759 | −12 \| 4565 | −38 \| 12252 | −38 | −28 |
| 53 Ride Bell | −23 \| 6516 \| 9.1 | −16 \| **1427** | −11 \| 3051 | −33 \| 5509 | −33 | −33 |
| 60/62/64 Bongo/Conga | −20/−20/−9 | −19/−19/−24 | −29/−25/−8 | −31/−30/−29 | sama | −29/−27/−17 |

Kesimpulan [T]:
1. **Di peta key XG, key 33 adalah Bass Drum L (kick).** Ini dikonfirmasi oleh Colombo XG kit (`DK-SubKick`). Rhythm2 ROSITA Main A memainkan key 33 sebanyak 8 kali, yaitu **kick utama groove**. Di Kendang Alex (yang dipilih untuk PC8), kick ini **tidak ada**. Key 34 (rim shot Rhythm1 Main B) juga tidak ada di ALEX KEPRI. Ini mengoreksi audit ROSITA, yang belum menyebut makna key 33.
2. Karakter Kendang Alex di key XG berbeda jauh: key 37 (side stick) berisi suara rendah 397 Hz, dan key 53 (ride bell) 1,4 kHz. Ini **kit kendang**, bukan Room kit. [T untuk data; label "kendang" [K]]
3. **Tyros 128/x memakai peta GS**, bukan XG: key 33/34 berisi sampel bernomor dengan centroid 5–7 kHz (klik). Colombo memisahkan peta dengan benar: 127/x = XG (33 = SubKick), 128/x = GS (33/34 = "Met Click 1/2").
4. Prime STANDAR/DRUM 2/DRUM 3 mengikuti peta mirip XG/PSR di key 33–36 (kick di 33/35/36).

---

## 6. Koreksi terhadap `UNIVERSAL_YAMAHA_VOICE_DRUM_ENGINE_DESIGN.md`

| § | Isi rancangan | Koreksi |
|---|---|---|
| §5.2 | Tyros kit "YAMAHA (Tyros) / {XG,pc} setelah ditinjau" | **Tyros 128/x = peta key GS** → `KitStandard::GS`, `emulates {GS,pc}`. Tidak eligible untuk request XG MSB127 di T1/T2. Hanya untuk request GS/bank 128. |
| §5.2 | Colombo 127/x XG, 128/x GS | **Terkonfirmasi** dari sampel key 33–36; juga sesuai INFO Colombo |
| §5.2 | `keySem` "bila diketahui" | Tambahkan **`keyMapStandard`** (XG / GS / PSR_CUSTOM) per kit sebagai field wajib. Gate coverage memeriksa keberadaan zone **dan** kesesuaian peta (key 33 XG = kick). |
| §5.3 (contoh ROSITA) | Rhythm2 PC8 → XG Room | Tetap, dengan alasan tambahan: kick key 33 hanya tersedia di kit berpeta XG. Tyros room kit **tidak** jadi alternatif. |
| §4.2 | Bukti dari nama sampel "bobot rendah" | Prime: **bobot 0** (95–100% nama SATRIOMUSIK ambigu). Colombo/Tyros: boleh dipakai sebagai bukti pendukung. |
| §4.2 | `VoiceCatalogEntry` | Tambah: `levelEstimateA_dB`, `levelEstimateB_dB`, `negAttenZones`, `attenRange_cB`, flag `LEVEL_OUTLIER` (> 12 dB di bawah median peran). Level **tidak** dipakai untuk memilih tier; hanya untuk review dan U8. |
| §4.5 | Rencana ROSITA PC3 = Prime Yamaha Honky Tonk (T1, Policy C) | Tetap secara identitas, tetapi **ditandai `LEVEL_OUTLIER`**. Rekomendasi review: pemilik memilih antara Prime (identitas PSR) dan Tyros Honky Tonk (identitas Yamaha + level normal) setelah probe §7. Colombo "Tack Piano" valid sebagai sinonim GM Honky-tonk (sesuai changelog Colombo), tetapi berisi layer **Harpsichord**, jadi subfamily `piano.honkytonk` perlu review dengar. |
| §6 | `pdtaSha256` definisi umum | Definisi persis + nilai golden ada di §1 dokumen ini |
| §7 | Input tool = ZIP metadata | Ekspor metadata **lossy** untuk apostrof. Tool harus memakai: (a) SF2 asli secara lokal/offline (tidak disimpan di repo), atau (b) ekspor yang menambahkan `nameHex`. Katalog yang dihasilkan menyimpan nama sebagai **byte mentah**. |
| §8.3 / O6 | Fixture | Colombo meminta tidak didistribusikan ulang. **Jangan commit SF2, sampel, atau potongan audio.** Fixture repo cukup berupa tabel turunan: fingerprint, P/Z ringkas, level, peta key. |
| §11 O4 | Level relatif preset belum diketahui | Sebagian tertutup: level sampel dan atenuasi terukur (§5). Tersisa semantik engine (clamp negatif, skala 1,0 vs 0,4), yang ditutup lewat probe U0. |

---

## 7. Rekomendasi U0–U1 untuk Codex

### U0 — Diagnostik (tanpa perubahan perilaku audio)

1. **Perbaikan exporter metadata (log-only):** tambahkan `nameHex=` untuk P/I/S di samping `name='…'`, dan escape `'`. Tes: nama `CLOW D'ACADEMY` diekspor dan di-parse kembali identik dengan byte phdr.
2. **Probe kalibrasi BASSMIDI (debug-only, offline):**
   - **Mekanisme:** aksi debug merender satu nada ke stream *decode* BASSMIDI terpisah, tanpa output audio dan tanpa menyentuh stream utama atau `mutex_` render, lalu melaporkan peak/RMS (dBFS) per probe.
   - **Daftar probe minimal** (font, bank/PC, key, vel 100, durasi 1 s):

     | # | Probe | Tujuan |
     |---|---|---|
     | P1 | Prime Drum STANDAR 128/0 key 36 (total att −1440) | Cara clamp nilai negatif |
     | P2 | Colombo XG Room 127/8 key 36 (att positif normal) | Referensi P1 |
     | P3 | Prime Melodi Clean yamaha 0/27 key 60 (att +30 dB, 3 zona) | Skala atenuasi positif |
     | P4 | Tyros t4 clean 0/27 key 60 (att 0) | Referensi P3 |
     | P5 | Prime Yamaha Honky Tonk 0/3 key 72 (att +28 dB) | Level outlier |
     | P6 | Tyros Honky Tonk 0/3 key 72 (att +7 dB) | Referensi P5 |
     | P7 | Tyros FINGER BASS 0/33 key 36 (att −2,6 dB) | Clamp negatif kecil |
     | P8 | Prime Kendang Alex 128/8 key 33 | Harus sunyi (tidak ada zone) |

   - **Interpretasi yang sudah ditetapkan:**

     | Ukuran | Bila hasilnya ≈ | Berarti |
     |---|---|---|
     | P3 − P4 | −20 dB | skala 1,0 (model A) |
     | P3 − P4 | −2 dB | skala 0,4 (model B) |
     | P1 dibanding prediksi clamp (−9 dB) | ≈ prediksi | negatif diclamp ke 0 |
     | P1 dibanding prediksi clamp | jauh lebih keras | negatif diterapkan sebagian |

   - **Output:** `SF2_CALIBRATION_<timestamp>.txt` (fingerprint font, probe, peak, RMS, versi BASSMIDI).
3. **STYLE FIDELITY export** (dari rancangan U0): tambahkan per channel → `keyMapStandard` kit aktif, key yang dimainkan tetapi tidak punya zone, total atenuasi mentah dan clamped dari zona aktif, serta `pdtaSha256` font sumber.

**Gate U0:**
- Tes exporter round-trip.
- Probe P1–P8 berjalan di Redmi Pad tanpa glitch di stream utama (cek dengan callback age/PCM existing).
- Tidak ada perubahan byte MIDI OUT.
- Tes REG/LIVE/RELEASE/RIGHT OFF hijau.

### U1 — Fingerprint dan katalog

1. **`pdtaSha256` di semua build:**
   - Telusuri RIFF dengan seek, jangan membaca `sdta`. Hash hanya payload pdta.
   - Golden: 4 nilai di §1.
   - Gate performa: < 300 ms total untuk 4 font di perangkat. Byte yang dibaca sekitar 1,1 MB.
   - Fingerprint penuh file yang sudah ada (flag debug) tidak dihapus.
2. **Tool katalog** (`tools/sf2_catalog/`, host):
   - **Input:** SF2 lokal (path argumen), kamus nama GM/GS/XG, dan `catalog_review.yaml`.
   - **Output:**
     - `voice_catalog_generated.h`, `drum_catalog_generated.h`
     - `catalog_report.md`
     - `catalog_manifest.json` (pdtaSha256, versi kamus, hash review)
   - **Deterministik.** Tidak mengeluarkan sampel atau audio.
   - **Aturan yang wajib diimplementasikan dari audit ini:**
     - Bobot bukti nama sampel = 0 untuk font yang punya > 10% nama sampel ambigu (Prime Drum/Melodi). Dihitung otomatis dari hash data sampel, bukan hardcode nama file.
     - `keyMapStandard` kit diturunkan dari bank (127 → XG, 128 → GS untuk font berlabel GM/GS/XG). Untuk font lain, dari bukti key 33–36: kick terdeteksi di 33 → XG/PSR; klik/metronom di 33/34 → GS. Hasil heuristik ini wajib `review_required`.
     - `negAttenZones`, `attenRange_cB`, `levelEstimateA/B` per preset; `LEVEL_OUTLIER` otomatis.
     - Nama disimpan sebagai byte mentah (hex) + teks.
   - **`catalog_review.yaml` awal:**
     - Policy C: Prime `Yamaha Honky Tonk` 0/3 (EXACT, `LEVEL_OUTLIER`, perlu review pemilik) dan `Clean yamaha` 0/27 (EXACT).
     - CONFLICT: Suling S770 0/52 (Flute), Shakuhachi 0/53, Melodic Tom1 0/47, SARUNE 0/84.
     - Drum: Colombo 127/x = XG; Colombo 128/x = GS; Tyros 128/x = GS; 21 kit Prime Drum = CUSTOM. Kendang Alex dan ALEX KEPRI dengan catatan "tidak ada zone 33–35".
3. **Tes U1:**
   - Golden fingerprint.
   - Determinisme tool (dua kali jalan = byte identik).
   - Katalog yang dihasilkan untuk keempat font: jumlah preset/kit sesuai §1; flag sesuai §4–5.
   - Tidak ada entri family yang berasal dari PC kandidat (uji properti: permutasi PC kandidat netral → family tetap).

**Gate U1:**
- Laporan `catalog_report.md` ditinjau pemilik, terutama keputusan PC3 (Prime vs Tyros Honky Tonk) dan semua `LEVEL_OUTLIER`.
- Probe U0 sudah menetapkan model level (A/B) sebelum angka level dipakai di review.

---

## 8. Hal terbuka

| # | Item | Penutup |
|---|---|---|
| S1 | Clamp atenuasi negatif dan skala atenuasi positif di BASSMIDI | Probe U0 P1–P7 |
| S2 | Apakah kit Prime memberi puncak di atas 0 dBFS saat dimainkan bersama (sumber parent peak 4,64 di P2) | Probe U0 + HEADROOM dengan kit XG Colombo vs Prime |
| S3 | Pilihan PC3: Prime (identitas PSR, sangat pelan) vs Tyros (normal) | Review pemilik setelah S1 |
| S4 | Isi Tyros 33/34 (sampel "094"/"145") | Dengar / probe; untuk keputusan GS sudah cukup |
| S5 | Lisensi fixture | Hanya tabel turunan; tidak ada SF2/sampel di repo |

## Lampiran — Artefak (scratch, tidak di repo)

- `structure.json`: RIFF, INFO, jumlah tabel, pdtaSha256
- `crosscheck.json`, `samplehash_*.json`: validasi ekspor dan identitas sampel
- `negative_attenuation.txt`: sensus atenuasi negatif
- `levels_clamp.txt|json`, `levels_emu04.txt|json`, `levels_raw.txt|json`: level dan karakter
- `modulators.txt`: modulator pada preset target
- Tool: `sf2parse.py`, `structure.py`, `crosscheck.py`, `levels.py`, `levels04.py`, `atten.py`, `mods.py`
