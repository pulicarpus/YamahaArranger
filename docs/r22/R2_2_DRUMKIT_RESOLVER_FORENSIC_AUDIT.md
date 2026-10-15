# R2-2 — Forensic Audit DrumKitResolver (ROSITA, Redmi Pad)

| Item | Nilai |
|---|---|
| Bukti runtime | **S1** `YamahaArranger_Inspector_20261015_230525.txt` (drum saja), **S2** `…_230632.txt` (tanpa drum) |
| Baseline kode | `experiment/bassmidi-r2-0-pcm-session` @ `dc746b7` (logika drum identik dengan `163f769`) |
| Bukti statis | ROSITA.STY (SFF2/CASM) + 4 SF2 asli terverifikasi SHA (Colombo, Tyros 4, Prime Melodi, Prime Drum) |
| Sifat | Read-only. Tanpa commit, push, build, perubahan kode, limiter, atau normalisasi. Baseline dan `main` tidak disentuh |

Label: **[T]** terbukti dari counter, log, kode, atau byte SF2; **[K]** indikasi kuat; **[?]** belum diketahui.
"peakUB" = batas atas statis untuk satu not (lihat §4.1), **bukan hasil audio**.

---

## 1. Ringkasan

1. **Akar masalah [T]: kit dipilih hanya dari nomor PC, di satu SF2 saja (Prime Drum).**
   - Bank 127 dari style dibuang dan diganti 128.
   - LSB diabaikan.
   - Preset pertama dengan PC yang sama di Prime Drum yang dipakai.
   - Akibatnya, kit Colombo XG (127/x) dan Tyros GS (128/x) tidak pernah bisa dipilih untuk ch8/ch9.
2. **Sesi drum-only (S1) clipping berat [T].**
   - Peak **4,439** (+12,9 dBFS), **43.689 sampel clipped (1,28 %)**.
   - Clamp output memotong RMS ≈ **2,2 dB** (0,2585 → 0,2006).
   - Sesi tanpa drum (S2): peak 1,386 (+2,8 dBFS), 0,21 % clipped.
   - Kedua sesi valid: mute stabil, NOTE_ON keyboard 0, delta NOTE_ON terisolasi.
3. **Yang membuat Prime clipping, secara struktural [T dari byte SF2 + metadata Inspector]:**
   - **Stacking koheren.** Satu NOTE_ON memicu banyak zona dengan sampel identik. Contoh STANDAR key 36 vel 83: **14 zona, 8 salinan SF2-94**.
   - **Atenuasi negatif** sampai −1440 cB (di luar spesifikasi SF2).
   - Zona velocity tumpang-tindih.
   - Send reverb/chorus per-zona (rev 70–220, cho 300).
   - Kendang Alex: atenuasi −750 cB di semua zona.
4. **Hipotesis bahwa Colombo clipping lebih rendah: BELUM TERBUKTI.**
   - Data statis mendukung: stack ≤ 1 sampel identik, atenuasi positif, peakUB jauh lebih kecil.
   - Belum ada pengukuran runtime. Colombo **tidak** dianggap lebih baik sampai A/B di perangkat (§7).
5. **Masalah identitas musikal [T]:**
   - ROSITA meminta kit Yamaha standar 127:0 PC0/8/16 (Standard / Room / Rock).
   - Yang terpakai adalah kit custom Indonesia (STANDAR PSR-SX, **Kendang Alex**, **ALEX KEPRI**).
   - Dua di antaranya kehilangan key yang dimainkan:
     - Kendang Alex: key 33 tidak ada → **8/42 hit Main A ch9 diam**.
     - ALEX KEPRI: key 34 tidak ada → **2/10 hit Main B ch8 diam**.
6. **Keputusan:**
   - **R2-2 SHADOW: GO.**
   - **Aktivasi resolver: NO-GO** sampai A/B runtime dan persetujuan owner (terutama soal Kendang, §5.4).
   - **Limiter/normalisasi: NO-GO** (di luar scope dan dilarang).

---

## 2. Bukti runtime S1/S2 (yang terbukti)

| Metrik | S1 (drum saja) | S2 (tanpa drum) |
|---|---|---|
| Durasi | 35,504 s | 34,224 s |
| `peakSinceMark` | **4,43885** | 1,38574 |
| `clippedSinceMark` | **43.689 / 3.408.384 (1,28 %)** | 6.772 / 3.285.504 (0,21 %) |
| RMS (stage 0) → RMS clamp-model (stage 4) | 0,258468 → 0,200645 (**−2,2 dB**) | 0,249572 → 0,249065 (−0,02 dB) |
| NOTE_ON delta | ch8 = 36, ch9 = 215; lainnya 0; keyboard 0 | ch10 = 35, ch11 = 66, ch12 = 66, ch13 = 18, ch14 = 60; drum 0; keyboard 0 |
| Mute | mask 64512 (ch10–15), `muteChangesSinceMark=0` | mask 768 (ch8/9), `muteChangesSinceMark=0` |
| mappingGeneration | 4 → 4 | 4 → 13 |
| Kit aktif saat ekspor | ch8 dan ch9: `128:0 'STANDAR PSR-SX'`, `resolverReason=legacy_same_pc_first_matching_cache_entry` | — |
| Worst frame | `HEADROOM_WORST frame=2809856` → +30,85 s dari mark; `lane8Peak=0 lane9Peak=0` (lane per-drum tidak aktif) | — |

Interpretasi:

- **[T]** Drum Prime saja sudah melewati full-scale sekitar 4,4×. Clipping S1 tidak bisa dijelaskan oleh bagian melodik, karena NOTE_ON melodik dan keyboard = 0.
- **[T]** S2 juga clipping (0,21 %), jadi drum bukan satu-satunya sumber clipping. Bass sendiri hanya 12 sampel pre-clip (`PCM_SESSION_ROLE ch=10`).
- **[K]** Worst frame (+30,8 s) kemungkinan besar jatuh di Main D, tempat ch9 = STANDAR key 36 (stack 14 zona). Ini belum terbukti, karena log tidak menyimpan timeline section per frame.
- **[?]** Kontribusi per-kit (STANDAR vs Kendang Alex) ke peak tidak bisa dipisahkan dari S1:
  - Kedua kit aktif bergantian dalam satu sesi.
  - `lane8Peak` dan `lane9Peak` = 0.
  - Tap ROLE sengaja tidak memakai `GetChannel` untuk drum.
- **[?]** Mengganti kit pasti menurunkan clipping: **belum terbukti** (§7).

---

## 3. Akar masalah: jalur kit dari SFF2/CASM ke BASSMIDI [T]

```
ROSITA.STY  (SFF2, SInt: CC0=127 CC32=0 PC=n per part)
 └─ style_parser.cpp:196–216
      SInt channel events (0x80–0xEF) diinjeksi ke awal setiap section; sysex dibuang
 └─ StyleRepository.kt:164–182  extractVoiceSetup
      CC0/CC32/PC tick-0 → part.bank / part.program   (MSB 127 masih benar di sini)
 └─ StyleSequencer.kt
      :748  drum = isDrumDestination(destination, voiceName)      // dest 8/9 → drum (:857)
      :752  prog = override ?: explicit PC ?: guessProgramFromVoiceName
      :766  audioBank = if (drum) 128 else styleBank              // ← 127 DIBUANG, LSB hilang
      :767  midiMsb  = if (drum) 127 ...                          // hanya untuk MIDI OUT
      :787  audioEngine.setChannelProgram(dest, prog, audioBank, voiceName)
      :1103–1113  jalur PC dinamis: drum juga dipaksa ke bank 128
 └─ bassmidi_player.cpp
      findDrumPreset (~835–866):
        cari preset.program == requestedProgram di drumDrumPresetCache_ (HANYA Prime Drum),
        lalu melodyDrumPresetCache_; jika tidak ada → kit pertama
      setChannelPreset, cabang drum (~1341–1350):
        bankMsb = 128; program = sourceProgram
        reason = legacy_same_pc_first_matching_cache_entry | legacy_first_cached_kit_fallback
      applyFonts: addDrumMapping(drumFont_, 127/128) untuk minchan 8, numchan 2
        → yang terpetakan ke channel drum hanya Prime Drum
      send(MIDI_EVENT_DRUMS / BANK / PROGRAM) (~1399–1403)
      Drum tidak memanggil applyFonts → mappingGeneration tidak berubah saat kit berganti
```

Mengapa 127:0 berakhir sebagai 128:0 STANDAR PSR-SX:

1. MSB 127 diganti 128 di `StyleSequencer.kt:766`, dan LSB tidak pernah diteruskan.
2. Pencarian native hanya mencocokkan PC, di cache Prime Drum.
3. Di Prime Drum, PC0 = STANDAR PSR-SX, PC8 = Kendang Alex, PC16 = ALEX KEPRI. Ketiganya adalah kit custom.
4. Kit Colombo XG berada di bank 127 di SF2 Colombo. Bank itu tidak pernah dipetakan sebagai drum untuk ch8/9, sehingga tidak bisa dijangkau. Hal yang sama berlaku untuk Tyros 128/x.

Ini adalah "first PC found", bukan identitas musikal.

Catatan untuk diagnosis: `mappingGeneration` tidak mencerminkan pergantian kit drum. Shadow log membutuhkan counter generasi drum sendiri (§6).

---

## 4. Tabel kandidat kit (key dan velocity yang benar-benar dimainkan ROSITA)

### 4.1 Metode

- Hit drum per (section, channel) diambil dari ROSITA.STY: key, velocity, dan jumlah hit per loop.
- Per kandidat, dihitung zona yang cocok dengan key/velocity tersebut di SF2 asli:
  - `sameStack`: jumlah zona dengan sampel identik (penjumlahan koheren).
  - Atenuasi preset + instrument (gen 48).
  - Send rev/cho (gen 16/15).
  - Durasi sampel.
- `peakUB` = Σ zona (puncak sampel × gain atenuasi), dengan atenuasi di-clamp ke 0–1440 cB:
  - (k = 1,0): spesifikasi.
  - (k = 0,4): mirip EMU, model yang cocok dengan PCM perangkat [K].
- `peakUB` **tidak** memperhitungkan kurva velocity, filter, pan, envelope, maupun tumpang-tindih antar-not. Pasangan stereo L/R ikut dijumlahkan. Ini batas atas kasar per-not untuk perbandingan relatif saja, **bukan prediksi peak audio**.
- Kolom:
  - PRM = yang dipakai sekarang (Prime Drum 128/PC).
  - COL-XG = Colombo 127/PC.
  - TYR-GS = Tyros 4 128/PC.
- Data lengkap: `scratchpad/r22/kits.txt` dan `kits.json`.

### 4.2 Permintaan yang diaudit

| Section / ch | Request ROSITA | PRM (aktual) | COL-XG | TYR-GS |
|---|---|---|---|---|
| Main A ch8 (Rhythm1) | 127:0 PC0 | STANDAR PSR-SX | XG Standard 1 | tyros 4 standard |
| Main A ch9 (Rhythm2) | 127:0 PC8 | **Kendang Alex** | XG Room | room kit |
| Main B ch8 | 127:0 PC16 | **ALEX KEPRI** | XG Rock | power kit |
| Main B ch9 | 127:0 PC0 | STANDAR PSR-SX | XG Standard 1 | tyros 4 standard |
| Main C/D ch8 + ch9 | 127:0 PC0 | STANDAR PSR-SX | XG Standard 1 | tyros 4 standard |

### 4.3 Coverage (hit per loop yang punya zona di key dan velocity itu)

| Request | PRM | COL-XG | TYR-GS |
|---|---|---|---|
| Main A ch8 PC0 | 6/6 | 6/6 | 6/6 |
| **Main A ch9 PC8** | **34/42** (key 33 ×8 diam) | 42/42 | 42/42 |
| **Main B ch8 PC16** | **8/10** (key 34 ×2 diam) | 10/10 | 10/10 |
| Main B ch9 PC0 | 40/40 | 40/40 | 40/40 |
| Main C ch8 / ch9 | 10/10, 40/40 | 10/10, 40/40 | 10/10, 40/40 |
| Main D ch8 / ch9 | 10/10, 47/47 | 10/10, 47/47 | 10/10, 47/47 |

Inspector S1 (`KIT RANK`, cakupan Main D, 57 hit) mengonfirmasi bahwa STANDAR menutup 57/57, sedangkan Kendang Alex dan ALEX KEPRI tidak punya key 34 [T].

### 4.4 Struktur per key (yang paling relevan)

| Request / key (vel) | PRM: zona, stack, att cB, peakUB k1/k0,4 | COL-XG | TYR-GS |
|---|---|---|---|
| **D ch9 key36 kick (83–94)** | **14 zona, stack 8, −1440…−1140, 14,0 / 14,0** | 5 zona, stack 1, 225–243, 0,36 / 1,75 (Deadly Kick10) | 2 zona, stack 1, 0, 2,0 |
| B/C ch9 key33 (72–127) | 2, stack **2**, **−1440**, 2,0 | 4, stack 1, 150–225, 0,51 / 1,71 (SubKick) | 2, stack 1, 0, 1,25 (sampel "094/097", kemungkinan klik GS [K]) |
| A ch9 key37 (127) | Kendang 4, stack 2, **−750**, **4,0** | 1, 100, 0,32 / 0,63 (Rimshot) | 2, 0, 0,98 |
| A ch9 key60 (127) | Kendang 4, stack 2, −750, **4,0** | Bongo Rim 0,24 / 0,56 | 0,6 |
| A ch9 key62 (127) | Kendang 4, stack 2, −750, 2,48 | Closed Slap 0,24 / 0,56 | 1,46 |
| A/C/D ch8 key53 ride (127) | 2, stack **2**, 50, 1,12 / 1,59, **rev 70, dur 9,08 s** | BellRide 0,31 / 0,94, dur 3,29 s | 0,56, dur 2,23 s |
| B ch8 key53 (127) | ALEX KEPRI 4, stack 2, 0, **3,86**, rev 50 | 0,31 / 0,94 | 0,91 |
| B ch9 key54 tamb. (90–94) | 3, stack **3**, −100, 3,0, rev 200–220 | 0,4 / 0,66 | 1,24 |
| ch9 key42 HH (50–127) | 2, −30, 1,93, rev 70, cho 300 | 0,17 / 0,74 | 0,28–0,9 |

Temuan struktural [T, byte SF2]:

- **PRM STANDAR:**
  - 314/385 zona beratenuasi negatif.
  - Key 36 memicu 14 zona sekaligus pada satu velocity: sampel SF2-94 ×8, SF2-18/19/20 ×4, SF2-42 ×2.
  - Rentang velocity zona tumpang-tindih (77–84 vs 82–88 vs 81–88).
  - Inspector perangkat melaporkan `matchingZones=14` untuk vel 83/84/86/94, cocok dengan parser host.
- **Kendang Alex:** 220/220 zona −750 cB, tidak ada zona 33–35.
- **ALEX KEPRI:** tidak ada zona 33–35.
- **Prime** memakai send reverb/chorus per-zona (gen 16/15). Colombo dan Tyros pada key-key ini = 0.
- **COL-XG:**
  - Stack sampel identik ≤ 1 pada semua key ROSITA.
  - Atenuasi 0–243 cB.
  - 0 zona negatif di kit XG (XG Rock: 3/236 negatif secara keseluruhan, tidak pada key ROSITA).
- **TYR-GS:**
  - Atenuasi 0, stack 1.
  - Peta kunci GS: key 33/34 = klik metronom, bukan kick/rim. Ini mismatch semantik untuk style Yamaha yang memakai key 33 sebagai kick [K].

### 4.5 Semantik key-map

| Namespace | Key 33 | Key 34 | Arti untuk ROSITA |
|---|---|---|---|
| Yamaha/PSR-XG (127:x) | Kick soft | Open rim shot | Cocok dengan request style (MSB 127) |
| Colombo 127/x | SubKick / Deadly Kick | StanSnare1 | Sesuai |
| Colombo 128/x, Tyros 128/x | Met Click 1/2 (GS) | | Tidak cocok untuk request 127 |
| Prime (custom) | Bervariasi per kit | | Kendang Alex menaruh **kendang** di 37/60/62, tempat XG Room punya side stick / bongo / conga |

Kesimpulan: berdasarkan identitas request (MSB 127 = kit Yamaha standar), target alami adalah **COL-XG 127/PC**. Ini konsisten dengan prioritas owner (Colombo → Tyros → Prime untuk kit custom yang teridentifikasi). Tyros GS hanya cadangan bila request-nya GS (MSB 120/GS 128), bukan untuk 127.

---

## 5. Bukti vs hipotesis

### 5.1 Terbukti [T]
- Pemilihan kit = PC-only di Prime Drum (kode, `resolverReason`).
- S1 drum-only: peak 4,44 dan 1,28 % clipped dengan kit Prime STANDAR + Kendang Alex (+ ALEX KEPRI bila Main B sempat dimainkan; dari delta NOTE_ON, Main B kemungkinan tidak dimainkan [K]).
- Prime memiliki stacking koheren, atenuasi negatif, dan zona velocity tumpang-tindih pada key yang dimainkan ROSITA.
- Kendang Alex dan ALEX KEPRI kehilangan key yang dimainkan, jadi ada hit yang diam.

### 5.2 Indikasi kuat [K]
- Stack 14 zona di key 36 (Main D) adalah kandidat terbesar worst frame.
- BASSMIDI meng-clamp atenuasi negatif ke 0. Model k = 0,4 cocok dengan PCM perangkat, tetapi perilaku persisnya belum diukur langsung.

### 5.3 Hipotesis, belum terbukti [?]
- "Colombo XG akan clipping lebih rendah." Hanya didukung peakUB statis. Perlu A/B runtime.
- "Colombo terdengar lebih benar untuk ROSITA." Perlu penilaian dengar owner.
- Pengaruh reverb/chorus kit ke peak: belum diketahui apakah PCM_SESSION stage 0 sudah termasuk return FX.
- Loudness: Colombo kemungkinan **jauh lebih pelan** (atenuasi positif). Tanpa perubahan gain (dilarang di R2-2), keseimbangan drum vs melodik bisa berubah. Ini risiko, bukan perbaikan otomatis.

### 5.4 Keputusan owner yang diperlukan
- Main A ch9 meminta 127:0 PC8 (XG Room Kit). Prime memberi **Kendang Alex**.
  - Secara identitas Yamaha, ini salah.
  - Secara selera dangdut Indonesia, mungkin justru disukai.
- Desain resolver: **tidak** memilih Kendang secara otomatis untuk request 127:0 PC8. Kendang hanya dipakai lewat **override per-style** atau identitas custom eksplisit, sesuai keputusan owner sebelumnya. Owner perlu mengonfirmasi ini untuk ROSITA.

---

## 6. Desain R2-2 SHADOW resolver (universal)

### 6.1 Prinsip keras
- Playback tetap **Policy C / legacy** tanpa perubahan.
- Shadow hanya menghitung dan mencatat log. Yang **dilarang**:
  - Tidak ada NOTE_ON tambahan.
  - Tidak ada load/swap SF2.
  - Tidak ada `BASS_MIDI_StreamSetFonts`, `MIDI_EVENT_*`, atau `GetChannel`.
  - Tidak ada perubahan mixer, gain, velocity, CC, atau FX.
  - Tidak ada sentuhan ke LIVE CONTROL, REG MEMORY, RELEASE, RIGHT OFF, maupun MIDI OUT E343.
- Berjalan di thread sequencer/non-audio, dengan batas waktu (target < 2 ms per keputusan). Hasilnya di-cache per (styleHash, section, ch, request).

### 6.2 Data model

```kotlin
data class DrumKitRequest(
  val styleHash: String, val format: StyleFormat /*SFF1|SFF2*/,
  val section: String, val channel: Int /*0-based 8..15*/,
  val msb: Int?, val lsb: Int?, val pc: Int?,          // RAW dari SInt/CASM, sebelum :766
  val source: RequestSource /*SINT_SETUP|DYNAMIC_PC|OVERRIDE|NAME_GUESS*/,
  val casmVoiceName: String?)

data class DrumSectionProfile(            // dari data style, bukan dari audio
  val hits: Map<Int /*key*/, IntArray /*velocities*/>)

data class KitCandidate(
  val font: FontId, val bank: Int, val preset: Int, val name: String,
  val namespace: KitNamespace /*YAMAHA_XG|GS|GM|CUSTOM|UNKNOWN*/,
  val coverage: Coverage /*coveredHits, missingKeys, missingVel*/,
  val maxSameSampleStack: Int, val maxZonesPerHit: Int,
  val negativeAttZones: Int, val velOverlap: Boolean,
  val fxSendZones: Int, val exclusiveClassOk: Boolean /*HH choke 42/44/46*/)
```

### 6.3 Namespace dan identitas (data-driven, bukan hardcode ROSITA)

| Request | Namespace | Urutan kandidat |
|---|---|---|
| MSB 127, ch Rhythm/drum | YAMAHA_XG | Colombo 127/PC (exact) → Colombo 127/0 (family fallback, flag `pc_fallback`) → Tyros (hanya jika peta key cocok, flag `gs_map_risk`) → Prime (hanya override/identitas) |
| MSB 126 (SFX/custom Yamaha) | CUSTOM/SFX | Katalog identitas custom → Colombo 126/x jika ada → `unresolved` |
| MSB 120 / GS 128 / GM ch10 | GS/GM | Tyros 128/PC → Colombo 128/PC → `unresolved` |
| MSB/LSB tidak dikenal, atau hanya nama CASM | UNKNOWN | **Tidak menebak**. `shadow=unresolved`, pakai legacy |
| Override per-style (owner) | CUSTOM | Kit override, misalnya Prime Kendang Alex, dengan coverage gate tetap dicatat |

Tabel `KitIdentity` (file data, versi + hash) memetakan (font SHA, bank, preset, name hash) ke namespace dan family (standard, room, rock, kendang, …). Kit Prime yang belum teridentifikasi = CUSTOM tanpa family, sehingga tidak pernah dipilih otomatis.

### 6.4 Pemilihan (deterministik)
1. Filter berdasarkan namespace dan identitas: exact (MSB, LSB, PC) lebih tinggi daripada family.
2. **Coverage gate:** semua (key, velocity) di `DrumSectionProfile` punya ≥ 1 zona. Jika gagal → kandidat ditolak (`missingKeys` dicatat).
3. **Flag struktural** hanya sebagai informasi dan tie-break, bukan pengubah gain:
   - `maxSameSampleStack > 2`
   - `negativeAttZones > 0`
   - `velOverlap`
   - `fxSendZones > 0`
   - `!exclusiveClassOk`
4. Prioritas owner: Colombo → Tyros → Prime.
5. Hasil: `ideal`, plus `alternatives[0..2]` dan alasan masing-masing.

### 6.5 Titik integrasi (hanya observasi)
- `StyleSequencer.kt`: tangkap `DrumKitRequest` **sebelum** `:766`, baik di jalur setup section (`:748–787`) maupun PC dinamis (`:1103–1113`). Panggil `shadowResolver.evaluate(...)` dan simpan hasilnya. Nilai yang dikirim ke `setChannelProgram` **tidak diubah**.
- Native: tambahkan getter read-only `getDrumChannelSelection(ch)` → (font, bank, preset, name, reason) untuk sisi "actual". Tambahkan juga `drumSelectionGeneration`, karena `mappingGeneration` tidak berubah untuk drum.
- Metadata zona kandidat diambil dari katalog U1 yang sudah ada (`catalogUse=OFF` tetap OFF untuk playback). Katalog hanya dibaca oleh shadow.

### 6.6 Format log

```
DRUM_KIT_SHADOW gen=<drumSelGen> style=<hash> fmt=SFF2 section='Main A' ch=9 src=SINT_SETUP
  req=127:0:8 ns=YAMAHA_XG
  actual=PRM:128:8:'Kendang Alex' actualReason=legacy_same_pc_first_matching_cache_entry
         cov=34/42 missingKeys=[33] stackMax=2 negAtt=220 fx=0
  shadow=COL:127:8:'XG Room' shadowReason=xg_exact cov=42/42 stackMax=1 negAtt=0 fx=0
  alt1=TYR:128:8:'room kit' reason=gs_map_risk cov=42/42
  agree=0 override=none evalUs=<n>
```

- Satu baris per perubahan (section, ch, request).
- Ringkasan di Inspector: bagian `=== DRUM KIT SHADOW ===` berisi agreement rate dan daftar disagreement.

### 6.7 Ekspektasi shadow untuk ROSITA (untuk verifikasi G5, bukan hasil audio)

| Request | Actual | Shadow ideal |
|---|---|---|
| A ch8 127:0:0 | PRM STANDAR | COL XG Standard 1 |
| A ch9 127:0:8 | PRM Kendang Alex | COL XG Room (atau override owner: Kendang Alex, dengan flag `cov 34/42`) |
| B ch8 127:0:16 | PRM ALEX KEPRI | COL XG Rock |
| B/C/D ch9, C/D ch8 127:0:0 | PRM STANDAR | COL XG Standard 1 |

---

## 7. Uji perangkat terkontrol (untuk tahap R2-3, setelah shadow lulus)

Shadow sendiri tidak menghasilkan bukti audio. A/B membutuhkan toggle debug terpisah (R2-3):

- `drumKitPolicy = LEGACY | RESOLVED`
- Hanya boleh diganti saat **STOP** (tidak saat playback).
- Butuh mapping FONTEX: Colombo `sbank=127, spreset=n → dbank=128` untuk ch8/9.
- Risiko [?]: preset bank-melodik Colombo yang dipakai di channel drum harus dibuktikan berbunyi benar (root key/pitch per zona).

Protokol:

1. **Persiapan.**
   - Proses app yang sama.
   - Tempo default ROSITA.
   - Mute ch10–15 (drum-only), mute stabil (`muteChangesSinceMark=0`).
   - Keyboard tidak disentuh (`keyboardNoteOnDelta=0`).
2. **Urutan tetap:**
   - START Main A, 8 bar → Main B, 8 bar → Main C, 8 bar → Main D, 8 bar → STOP.
   - Satu `PCM_SESSION` mark per run.
   - Tambahkan log section dengan `frame` agar worst frame bisa dipetakan ke section. Ini data baru yang diperlukan.
3. **Run:** LEGACY ×3, RESOLVED ×3, diselang-seling (L, R, L, R, L, R).
4. **Kesetaraan input (wajib):**
   - Delta NOTE_ON ch8/ch9 identik antar-run.
   - Jika berbeda, run tidak valid.
5. **Metrik per run:**
   - `peakSinceMark`, `clippedSinceMark`, RMS stage 0 dan stage 4.
   - `HEADROOM_WORST` frame dan section.
   - Hit tanpa zona (perlu counter `noZoneNoteOn` per channel).
   - Agreement shadow vs aktual.
6. **Ulangi full-mix** (ch8–15 aktif) LEGACY vs RESOLVED: keseimbangan drum vs melodik tanpa perubahan gain.
7. **Rubrik dengar** (owner, A/B buta bila memungkinkan; skor 1–5):
   - Kick (berat dan bersih)
   - Snare/rim (identitas)
   - Hi-hat (choke dan kejelasan)
   - Perkusi Main A (bongo/conga vs kendang: mana yang diinginkan)
   - Ride tail
   - Groove secara umum
   - Distorsi yang terdengar
   - Keseimbangan dengan bass/melodi
8. **Tidak ada** limiter, normalisasi, atau penyesuaian gain selama pengujian.

---

## 8. Risiko

| # | Risiko | Mitigasi |
|---|---|---|
| R1 | Colombo lebih pelan → drum terdengar "kecil" di full-mix | Ukur di §7 langkah 6. Keputusan gain adalah tahap terpisah, bukan R2-2 |
| R2 | Kit bank-127 Colombo di channel drum berperilaku beda (pitch/root, exclusive class) | Uji perangkat R2-3; flag `exclusiveClassOk` |
| R3 | Owner lebih suka Kendang untuk dangdut | Override per-style; shadow tetap mencatat coverage yang hilang |
| R4 | Style lain memakai MSB/LSB yang tidak dikenal (Mega/Revo/custom kit) | `unresolved`, tidak menebak; perluas tabel identitas berbasis data |
| R5 | Shadow memengaruhi timing/audio (GC, alokasi) | Evaluasi di luar audio thread, hasil di-cache; gate G1 |
| R6 | `mappingGeneration` tidak menangkap pergantian drum | `drumSelectionGeneration` baru |
| R7 | Peta GS (Tyros) dipakai untuk request XG → key 33/34 jadi klik | Namespace gate; Tyros hanya `alt` dengan flag `gs_map_risk` |
| R8 | peakUB disalahartikan sebagai hasil audio | Label eksplisit di log/laporan; keputusan hanya dari §7 |
| R9 | Perubahan menyentuh MIDI OUT E343 (`midiMsb=127` di `:767`) | Shadow tidak mengubah `:767`; nilai MIDI OUT tetap |

---

## 9. Acceptance gate

### R2-2 SHADOW
| Gate | Kriteria |
|---|---|
| G1 Audio identik | Dibandingkan shadow OFF: NOTE_ON delta identik, `mappingGeneration` identik, tidak ada panggilan BASS/FONTEX baru (counter), peak/clipped dalam variasi run-ke-run. Tidak ada perubahan pada LIVE/REG/RELEASE/RIGHT OFF/MIDI OUT |
| G2 Kelengkapan | Setiap setup drum (section start dan PC dinamis) menghasilkan tepat satu `DRUM_KIT_SHADOW`; sisi actual sama dengan `NATIVE … resolverReason` |
| G3 Determinisme | Input sama → keputusan sama pada 3 run dan setelah restart |
| G4 Universal | Unit test JVM pada korpus: SFF1, SFF2, request GS (MSB 120/128), MSB 126, request tanpa CASM, kit custom. Tidak ada crash; request yang tidak dikenal → `unresolved` |
| G5 ROSITA | Hasil shadow sama dengan tabel §6.7. Coverage dan `missingKeys` cocok dengan §4.3 (34/42 key 33; 8/10 key 34) |
| G6 Performa | p99 evaluasi < 2 ms, di luar audio thread; tidak ada underrun baru (`fallbackCallbacks` tidak naik dibanding OFF) |

### R2-3 aktivasi (nanti, bukan sekarang)
- Input setara (§7.4) di semua run.
- RESOLVED: clipped dan peak lebih rendah dari LEGACY secara konsisten di 3/3 pasangan, tanpa hit yang diam (`noZoneNoteOn=0`).
- Persetujuan dengar owner (rubrik §7.7), termasuk keputusan Kendang.
- Bisa di-rollback dengan satu toggle.

---

## 10. GO / NO-GO

| Item | Keputusan |
|---|---|
| R2-2 Shadow resolver + log | **GO** |
| Mengganti kit saat playback | **NO-GO** |
| Aktivasi resolver (R2-3) | **NO-GO** sampai gate R2-3 lulus |
| Mengklaim "Colombo mengurangi clipping" | **NO-GO**: belum ada bukti runtime |
| Limiter/normalisasi/gain drum | **NO-GO** (dilarang dan di luar scope) |

---

## 11. Urutan implementasi untuk Codex

1. **Tangkap request mentah.** Bawa MSB/LSB/PC asli dan sumbernya (SInt/dinamis/override/tebakan nama) ke objek `DrumKitRequest` sebelum `StyleSequencer.kt:766`, di kedua jalur. Tanpa perubahan perilaku. Unit test bahwa nilai `setChannelProgram`/MIDI OUT tidak berubah.
2. **`DrumSectionProfile`.** Hitung key→velocity per (section, ch) dari data style yang sudah diparse. Pakai ulang logika Inspector "DRUM KIT AUDIT".
3. **Metadata kit read-only.** Perluas katalog U1 dengan:
   - Zona per preset (key/vel range)
   - Sampel id, `sameSampleStack`
   - Atenuasi preset+inst
   - Overlap velocity
   - Gen 15/16/57
   - Font SHA
   Tetap `catalogUse=OFF`.
4. **`KitNamespace` + `KitIdentity`** sebagai file data berversi:
   - Colombo 127/x = YAMAHA_XG, 128/x = GS
   - Tyros 128/x = GS
   - Prime = CUSTOM, family hanya bila teridentifikasi
5. **`DrumKitShadowResolver`** sebagai fungsi murni Kotlin (§6.4), plus unit test:
   - Fixture ROSITA (§6.7, coverage §4.3)
   - Fixture sintetis GS/SFF1/unknown
6. **Getter native read-only** `getDrumChannelSelection` dan `drumSelectionGeneration`. Tanpa efek samping.
7. **Logging** `DRUM_KIT_SHADOW` dan bagian Inspector "DRUM KIT SHADOW" (agreement dan disagreement).
8. **Validasi G1–G6** di perangkat (shadow ON vs OFF, ROSITA dan 2–3 style lain). Laporkan Inspector.
9. **(R2-3, terpisah, setelah persetujuan)**:
   - Toggle `drumKitPolicy` yang hanya diganti saat STOP
   - Mapping FONTEX Colombo 127→drum
   - Counter `noZoneNoteOn`
   - Log section+frame
   - Protokol §7

---

## 12. Data tambahan yang diperlukan

1. **Timeline section per frame** (section, frame saat ganti) agar `worstFrame` bisa dipetakan ke Main A/D.
2. **Peak per-kit / per-lane drum:** `lane8Peak` dan `lane9Peak` = 0 di S1. Alternatifnya, sesi drum-only per section (Main A saja, Main D saja) dengan kit legacy. Ini bisa dilakukan sekarang tanpa perubahan kode: mainkan satu section terkunci per `PCM_SESSION`.
3. **Perilaku BASSMIDI terhadap atenuasi negatif** (clamp atau tidak): uji satu not STANDAR key 36 vs satu not key 36 pada kit tanpa atenuasi negatif, dengan sesi PCM.
4. **Apakah PCM_SESSION stage 0 sudah termasuk return reverb/chorus.**
5. **Request kit untuk Intro/Fill/Break/Ending ROSITA** (audit ini hanya mencakup Main A–D).
6. **Keputusan owner untuk Main A ch9:** XG Room (identitas Yamaha) atau Kendang Alex (override).
7. **Bukti runtime bahwa preset Colombo bank 127 berbunyi benar di channel drum BASSMIDI** (R2-3).

---

*Lampiran: `scratchpad/r22/kits.py` (skrip analisis, dijalankan dengan `python3 -I`), `kits.json` dan `kits.txt` (hasil lengkap per key/velocity/kandidat).*
