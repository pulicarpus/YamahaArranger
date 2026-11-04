# AUDIT — Style terasa kosong / voice balance / MIDI OUT PSR-E343 (Love Song.T547.prs)

- **Branch:** `feature/midi-song-v1`. **Audit saja:** tidak ada perubahan kode produksi, tidak ada APK.
- **Style:** `Love Song.T547.prs` (58.112 byte, sha256 `dea4ce95…aa499`), **SFF2** (35 `Ctb2`, 0 `Ctab`, 4 `CSEG`, `OTSc` ada).
- **Sumber bukti:** rantai produksi di host (ArrangerBrain + StyleSequencer + MidiMixerOutput dengan wiring `MainViewModel`), synth tiruan, plus dokumen ukur sebelumnya (#781/#786/#790/#791). **Belum** ada readback dari Redmi Pad atau SoundFont Bos, dan **PSR-E343 tidak diuji fisik**.
- **Batas bukti:** aktivitas MIDI dihitung, tidak pernah dianggap sebagai level audio. Synth tiruan tidak menghasilkan suara.

## 0. Ringkasan

| | Kesimpulan | Kekuatan bukti |
|---|---|---|
| CASM/NTT SFF2 | **Benar.** Retarget akor C→F→G identik live = file = playback, 209 note, 0 selisih | Tes (terbukti) |
| INTERNAL: Piano dominan | Data Style sendiri sudah menempatkan Piano paling keras: CC7 81 + velocity P50 65, sedangkan Guitar CC7 79 tapi velocity **20–30**, Pad velocity **22**, Bass CC7 **52**. Estimasi kurva BASSMIDI: Piano ≈ −19 dB, Bass ≈ −29, Guitar ≈ −40, Pad ≈ −43 dB. Ini cocok dengan ukuran #790 (Piano −28, Guitar −31, Bass −40, Strings −58/−65 dBFS) | Data Style: terbukti. Level akhir di SF2 Bos: **belum** |
| INTERNAL: kompensasi | Trim measured balance #791 hanya aktif untuk SHA256 SF2 tertentu (MELODI/Tyros). Resolver memilih Colombo (Piano/Strings) dan SX700 (Bass/Guitar) → trim kemungkinan **tidak aktif** | Kode: terbukti. Font terpilih di perangkat: perlu readback |
| MIDI OUT: Bass/Guitar diam | **Tidak satu pun** bank/program yang diminta Style ada di daftar resmi PSR-E343 (MSB 8 dan 104 = voice Mega/Live Yamaha arranger). Perilaku E343 untuk permintaan di luar daftar **tidak didokumentasikan** | Daftar vs permintaan: terbukti. Bunyi di E343: **belum diuji** |
| Polifoni E343 | Puncak note melodi bersamaan 12 (semua Style ≤ 38 termasuk drum yang tidak di-OFF dalam jendela); E343 32 | Bukan penyebab utama |

## 1. Tabel per part (MainA, akor C, 120 BPM, MIDI OUT ON)

`bank14` = MSB·128 + LSB. PC ditulis 0-based (angka tampilan = PC+1). Nilai MIDI OUT **sama** dengan nilai internal (CC7/10/11/91/93 dari wiring produksi).

| Part (ch) | Voice diminta (MSB/LSB/PC, nama CASM) | Voice terpilih INTERNAL (resolver, pool sintetis) | CC7 / CC11 / pan / rev | NOTE ON, velocity P10/P50/P90, range | Status MIDI OUT vs daftar E343 | Dugaan masalah |
|---|---|---|---|---|---|---|
| Rhythm1 (9) | 127/0/73 'Perc.' | kit drum | 54 / 127 / 64 / 60 | 20, 20/40/60, 54..69 | 127/0/PC74: **bukan kit E343** (kit E343 MSB127 PC 1,2,9,17,25,26,33,41,49,113) | E343 kemungkinan pakai kit lain/standar; masih terdengar (sesuai laporan) |
| Rhythm2 (10) | 127/0/73 'Drums' | kit drum | 62 / 127 / 64 / 20 | 7, 39/39/82, **16..33** | sama; nada 13–22 di bawah peta drum GM | sebagian nada di bawah rentang drum, bisa diam di kit mana pun |
| **Bass (11)** | **8/4/17 'Bass'** | keluarga BASS → SX700 Melody 'BASS' | **52** / 127 / 64 / 0 | 4, 55/59/59, 36 | **MSB 8 tidak ada di E343.** Bila E343 jatuh ke 0/0/PC18 = **Percussive Organ** | INTERNAL: CC7 paling rendah. MIDI OUT: voice di luar daftar |
| **Chord1 Piano (12)** | **104/21/0 'Piano rt'** | keluarga PIANO → Colombo 'Wide Piano 2' | **81** / 127 / – / 24 | 31, 49/**65**/76, 60..64 | MSB 104 tidak ada. Bila jatuh ke PC1 = **Grand Piano** | INTERNAL: CC7 + velocity tertinggi + preset piano SF2 ber-attenuation negatif (#791) → dominan |
| **Chord2 Guitar (13)** | **8/10/1** (MIDI OUT juga 8/16/1) | keluarga GUITAR → SX700 'Steel Guitar' | 79 / 127 / 54 / 26 | 9, **20/21/30**, 48..60 | MSB 8 tidak ada. Bila jatuh ke 0/0/PC2 = **Bright Piano** | INTERNAL: velocity sangat rendah, untuk Mega Voice (zona velocity) → di SF2 jadi sangat pelan. MIDI OUT: bisa berbunyi sebagai piano |
| **Pad Strings (14)** | **8/5/49 'Strings1'** | keluarga STRINGS → Colombo Strings | 62 / 127→100 (fade) / 56 / 60 | 3, **22**, 52..64 | MSB 8 tidak ada. Bila jatuh ke PC50 = Strings 2 | INTERNAL: velocity 22 + attack lambat (~300 ms, #791) → tenggelam |
| Phrase1/2 (15/16) | Phrase2 8/5/49 (MainC) | Strings | – | tidak ada note di MainA | sama dengan Pad | – |

**Catatan "voice terpilih":** diambil dari `voice_resolver_test` (pool SF2 sintetis dengan nama file seperti milik Bos). Preset sebenarnya di Redmi Pad harus dibaca di tab MIXER / PART PRESENCE.

## 2. INTERNAL — mengapa Piano dominan

1. **Data Style (terbukti, BALAUDIT):** Piano mendapat CC7 81 dan velocity median 65 serta 31 note per 2 bar; Guitar velocity median 21, Pad 22, Bass CC7 52.
2. **Velocity Mega/Live Voice:** MSB 8/104 adalah voice Yamaha arranger. Mega Voice guitar memakai zona velocity (velocity rendah = layer normal yang tetap keras di Yamaha). Di SF2 pengganti, velocity 20–30 berarti ≈ −30…−36 dB hanya dari kurva velocity. *(Hipotesis kuat; didukung angka, belum diukur di SF2 Bos.)*
3. **Preset SF2:** dari #791, preset Piano punya attenuation negatif (−200 cB, lebih keras), Guitar layer velocity tetap, Strings attack lambat.
4. **Kompensasi tidak berlaku:** `measured_balance_profiles.h` dikunci SHA256 SF2 penuh + bank + PC (MELODI e8c7…, Tyros de5b…). Dengan font Colombo/SX700 yang dipilih resolver, tidak ada baris yang cocok.
5. **Bukan fallback voice yang salah:** setiap part memilih keluarga yang benar (BASS/PIANO/GUITAR/STRINGS), tidak ada yang jatuh ke Piano default.
6. **Bukan decoding SFF2:** lihat §4.

## 3. MIDI OUT PSR-E343 — mengapa hanya Drum dan Piano

**Bukti:** urutan per channel CC0 → CC32 → PC → CC7/10/11/91/93 → NOTE ON (`E343MidiOutAuditTest`, `StyleBalanceAuditRtTest`). NOTE ON/OFF dikirim di channel yang benar (ch11 Bass 4 note, ch13 Guitar 9 note). Jadi indikator aktivitas MIDI Bos memang benar menunjukkan note dikirim.

**Yang tidak cocok:** bank/program. Dicocokkan dengan `docs/yamaha/E343_VOICES.tsv` (523 baris resmi dari manual): **0 kecocokan** untuk kelima permintaan melodi dan kit drum.

Bila E343 memperlakukan bank yang tidak dikenal dengan memakai program XGlite bank 0 *(tidak terdokumentasi, belum diuji)*:

| Part | Diminta | Kemungkinan di E343 |
|---|---|---|
| Bass | 8/4/PC18 | Percussive Organ |
| Guitar | 8/10/PC2 | **Bright Piano** |
| Piano | 104/21/PC1 | **Grand Piano** |
| Strings | 8/5/PC50 | Strings 2 |

Skenario ini menjelaskan "yang terdengar hanya piano": Guitar ikut menjadi piano, Bass menjadi organ perkusif 1 note di range rendah, Strings velocity 22 nyaris tidak terdengar. Kemungkinan lain: E343 mengabaikan bank yang tidak dikenal dan menyimpan voice sebelumnya. **Keduanya belum terbukti** sampai tes isolasi di perangkat (§6).

Volume tidak menjelaskan diamnya Bass di E343: CC7 52 / CC11 127 / velocity 59 masih terdengar pada voice bass normal.

## 4. SFF1 vs SFF2

- Parser membaca `Ctb2` (range tengah byte 20/21, tiga blok 6 byte di 22/28/34: NTR, NTT|bassOn, highKey, limit, RTR).
- `RecorderParityRtTest` dengan Love Song: chord state C/F/G benar; Bass C G → C F → D G; Chord1 C E → A C F → B D G; Chord2/Pad C E G → A C F → B D G; **live = file = playback, 209 note, 0 selisih**.
- Jadi decoding SFF2 benar; masalah ada di **resolver/balance** (INTERNAL) dan **kompatibilitas voice** (MIDI OUT), bukan di CASM/NTT.
- `dialectIdentity` = UNKNOWN (metadata saja, tidak memengaruhi pemutaran).
- Tidak ada fixture SFF2 SX920 di repo; tidak dibuat konversi ke SFF1.

## 5. Rekomendasi patch minimal (belum dikerjakan)

| # | Patch | Bagian | Risiko regresi |
|---|---|---|---|
| A | **Tanpa kode:** pakai Style Voice Editor → **EXTERNAL MIDI OUT** (sudah ada) untuk mengganti voice MIDI OUT per part ke voice resmi E343, simpan dengan SAVE PROFILE | MIDI OUT | Nol (fitur yang ada, per Style) |
| B | Peta kompatibilitas E343 opsional per part: bila MSB/LSB/PC tidak ada di daftar resmi, kirim keluarga yang setara dari daftar (Bass → 0/112/34 Finger Bass, Guitar → 0/112/26 Folk Guitar, Piano → 0/112/1 Grand Piano, Strings → 0/112/49 String Ensemble, kit → 127/0/1). Default OFF, terlihat di UI | MIDI OUT router (perlu izin karena router dibekukan) | Sedang: memengaruhi semua Style untuk perangkat E343; tidak menyentuh INTERNAL. Harus diuji fisik |
| C | Trim role-balance berbasis **keluarga preset terpilih** (bukan SHA SF2) untuk keluarga yang terukur bermasalah, atau perluas registry #791 dengan font Colombo/SX700 setelah ukur di perangkat | INTERNAL (balance DSP) | Sedang: mengubah balance semua Style; harus diukur dengan PART PRESENCE, bukan dengan telinga saja |
| D | Kompensasi velocity untuk voice Mega/Live (MSB 8/104) yang diganti SF2 (mis. lantai velocity untuk Guitar/Pad) | INTERNAL | Lebih tinggi: mengubah dinamika Style; kerjakan hanya bila C tidak cukup |

Urutan disarankan: **A (tes isolasi) → C → B**. D hanya bila perlu. Tidak ada perubahan pada CASM, NTT, Style Sequencer, RIGHT, SHORT_GATE, atau Recorder V1C.1.

## 6. Langkah tes di perangkat (sebelum patch)

**INTERNAL (Redmi Pad):**
1. Muat Love Song, ACMP ON, akor C, MainA.
2. Tab MIXER: catat baris SYNTH (SF2 / bank / preset) untuk ch11–14.
3. Ekspor PART PRESENCE / MEASURED_BALANCE (build debug) → kirim log. Dari situ terlihat dBFS nyata tiap part dan apakah trim #791 aktif.

**MIDI OUT (PSR-E343), isolasi per part:**
1. Style Voice Editor → Bass → EXTERNAL MIDI OUT → MSB 0 LSB 112 PC 34 (Finger Bass). Mainkan. Bass terdengar? → penyebab = voice di luar daftar.
2. Guitar (Chord2) → 0/112/26 (Folk Guitar); Pad → 0/112/49 (String Ensemble); Piano biarkan.
3. Drum → 127/0/1 (Standard Kit 1).
4. Kontrol: kembalikan Bass ke permintaan asli (8/4/18) → catat apa yang terdengar di E343 (diam / organ / voice sebelumnya). Hasil ini memastikan perilaku E343 yang tidak terdokumentasi.

## 7. Bukti / tes

- `tests/rtdiag/StyleBalanceAuditRtTest.kt` (baru, tes saja): baris `BALAUDIT` per channel (permintaan voice, mixer, NOTE ON, velocity P10/P50/P90, range, polifoni; MIDI OUT bank/PC/CC/velocity/range).
  `python3 tools/test_rtdiag.py --rosita --style "Love Song.T547.prs" --style-name "Love Song" --only StyleBalanceAuditRtTest`
- `tests/rtdiag/E343MidiOutAuditTest.kt`: urutan dan isi permintaan MIDI OUT.
- `tests/rtdiag/RecorderParityRtTest.kt`: retarget SFF2 dan paritas.
- `docs/yamaha/E343_VOICES.tsv`: daftar resmi untuk pencocokan.
- `app/src/main/cpp/measured_balance_profiles.h`, `docs/MEASURED_BALANCE_791.md`, `docs/ROLE_PCM_BALANCE_786.md`.
