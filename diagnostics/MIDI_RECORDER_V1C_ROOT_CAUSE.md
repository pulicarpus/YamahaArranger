# V1C FULL ARRANGER — investigasi "rekaman tidak musikal, perubahan akor tidak terdengar"

- **Laporan perangkat (Bos, Redmi Pad, commit `68cbac4`):** keyboard virtual, FULL ARRANGER, 2.845 event / 28 bar, LOAD TAKE bisa diputar, tetapi hasilnya tidak musikal, terutama perubahan akor tidak terdengar. PSR-E343 belum dites.
- **Investigasi:** commit `345a969` (tes dan probe saja, sebelum patch). **Patch:** `4a358e0` dan seterusnya.
- **Catatan:** semua bukti dari host/CI (rantai produksi dengan synth tiruan, plus BASSMIDI asli Linux dengan SF2 sintetis). **Bukan** SoundFont Bos dan bukan Redmi Pad.

## 1. Pertanyaan dan bukti

### 1.1 Apakah akor virtual mengubah chord state ArrangerBrain saat main langsung? — **Ya**
`RecorderParityRtTest` (Style **ROSITA asli**, rantai produksi, akor dari keyboard virtual lewat `VirtualKeyRouter`):
`C->'C/G', F->'F', G->'G'`.

### 1.2 Event final engine vs file vs Song Player — **sama**
Nada NOTE ON berurutan per segmen akor dan per channel Style (urutan, bukan hanya jumlah):

| Part | C | F | G | live = file = playback |
|---|---|---|---|---|
| Bass (ch11) | C G | C F | D G | SAME (8/8/8) |
| Chord1 (ch12) | C E G | A C F | B D G | SAME (18/18/18) |
| Chord2 (ch13) | C E G | A C F | B D G | SAME |
| Pad (ch14) | C E G | A C F | B D G | SAME |
| Phrase1 (ch15) | C E G | A C F | B D G | SAME |
| Rhythm1/2 | — | — | — | SAME |

372 note, **0 selisih**. Timing (tes V1C sebelumnya): selisih terburuk 0,94 ms dari panggilan engine.
**Bank/program di file = permintaan ke engine:** Bass `0/112/33`, Chord1 `0/112/27`, Chord2 `0/112/27`, Pad `0/112/48`, Phrase1 `0/112/27`, Rhythm1/2 MSB 127 PC 0/8.
**Kesimpulan:** perekaman (nada, akor, retarget, timing, bank/program) **tidak** menjadi penyebab.

### 1.3 Apakah Song Player menerapkan XG, bank, program, dan drum dengan benar? — **sebagian: di sinilah perbedaannya**
BASSMIDI asli (CI run 41604908061, `tests/song_xg_real_bass_test.cpp`):

| Skenario (CH11, stream seperti stream Song) | Hasil |
|---|---|
| File rekaman NATIVE dibuka langsung oleh BASSMIDI | ch9 drums=1, ch10 drums=1, berbunyi → **XG System On dan drum part benar** |
| Peta generik, LSB 0, PC 0 | preset 0 bank 0, −18,8 dBFS |
| Peta generik, **LSB 112**, PC 0 (= LOAD TAKE V1C) | preset 0 bank 0, −18,9 dBFS → **LSB diabaikan** (jatuh ke LSB 0, tidak diam) |
| Rute per channel 0/112/33 → preset 58, XG On (= patch) | **preset 58**, −18,9 dBFS |
| Rute yang sama tanpa XG | diam (dicatat: rute LSB ≠ 0 butuh XG On; take NATIVE selalu memilikinya) |

**Perbedaan yang terbukti dari kode produksi (bukan perkiraan):**
- Stream keyboard/Style **tidak** memutar bank/program mentah. `findMelodicPreset` (resolver keluarga voice Yamaha, banyak SF2: utama + sekunder seperti Tyros/SX) memilih preset, lalu `applyFonts` memasang **rute satu-channel** `sbank/spreset → dbank/dbanklsb/dpreset`. Font yang dimuat adalah salinan ternormalisasi: bank asli 8 menjadi bank fisik 1 (`docs/BANK_READBACK_783.md`).
- Stream Song (V1A–V1C) hanya memasang peta generik [font drum, font melodi utama] dengan `dbank 0 / dbanklsb 0`.

**Jadi saat LOAD TAKE, setiap part Style berbunyi dengan font utama bank 0 / program X, bukan preset yang terdengar saat main live.** Font sekunder tidak pernah dipakai, dan voice variasi Yamaha (MSB ≠ 0) jatuh ke bank fisik lain di salinan ternormalisasi.

### 1.4 Penyebab lain yang ditemukan (belum diperbaiki)
- **Trim balance terukur** stream utama (`MEASURED_BALANCE_791.md`): untuk preset tertentu (SHA SF2 + bank + PC), misalnya Pad/Phrase +12 dB dan Chord −6 dB, berupa DSP per role. **Tidak ada di stream Song.** Pada SF2 yang terkena, Pad/Phrase di playback bisa sekitar 12 dB lebih pelan daripada live.
- **Percussion lane kompatibel** (drum Yamaha) dan peta kit R23 hanya ada di stream utama.

## 2. Akar masalah (sejauh yang bisa dibuktikan tanpa perangkat)

**LOAD TAKE tidak memakai voice yang dipakai engine live.** Nada dan akor di file benar, tetapi stream Song memilih preset dengan cara yang berbeda (lookup generik bank/program di font utama, LSB diabaikan) dari resolver multi-SF2 engine live. Akibatnya Bass/Chord/Pad/Phrase berbunyi dengan instrumen lain (dan untuk preset yang punya trim, dengan balance lain) sehingga harmoni bisa tertutup drum.

**Belum terbukti:** seberapa besar efeknya pada SF2 Bos. Untuk itu perlu readback di perangkat: baris SYNTH di tab MIXER (preset tiap channel Song) dibandingkan dengan voice Style live.

## 3. Perbaikan minimal (V1C.1)

- Saat merekam: sesudah setiap voice di-resolve, `channelResolvedSource(ch)` membaca balik **preset SF2 yang benar-benar dimainkan stream utama** (font + bank + program), lalu menulisnya sebagai teks meta **`YAMAP ch=C dst=MSB/LSB/PC src=BANK/PROGRAM sf2=FILE`** di track part, satu per perubahan voice (termasuk perubahan voice antar section).
- Saat LOAD TAKE / OPEN MIDI: `YAMAP` dibaca, preset dicari di SF2 yang dimuat (nama file + bank + program), lalu stream Song memasang **rute satu-channel yang sama seperti `applyFonts`** (`songSetVoiceRoute`). Bank/PC milik file sendiri lalu memilih preset live, sehingga perubahan voice antar section otomatis ikut. Pin Mixer V1A.2 tetap prioritas pertama.
- Ekspor GM tidak membawa `YAMAP` (khusus YA). File lain tanpa `YAMAP` tidak berubah perilakunya.
- **Tidak diubah:** Style Sequencer, CASM/NTT, RIGHT, SHORT_GATE, MIDI OUT, peta stream utama.

## 4. Yang belum diatasi
1. Trim balance terukur (DSP per role) dan percussion lane kompatibel tidak direplikasi di stream Song.
2. Take yang direkam **sebelum** patch ini tidak punya `YAMAP`, jadi perlu direkam ulang.
3. Harus dites Bos di Redmi Pad: rekam FULL ARRANGER → LOAD TAKE → tab MIXER (baris SYNTH) dibandingkan dengan bunyi live.
