# EnglishWaltz2.S519: audit drum MIDI OUT ke PSR-E343 ("tres-tres-tres")

Branch `claude/e343-audit-continue-q40lxg` (eksperimen; tidak di-merge, tanpa PR). Tidak ada perubahan pada kode produksi, BASSMIDI, CASM, Style asli, atau mapping MIDI OUT. Yang ditambahkan hanya test host, skrip analisis, dan laporan. Baseline yang playable tetap sama.
Katalog acuan: `docs/yamaha/E343_VOICES.tsv` dan `docs/yamaha/E343_DRUMKITS.tsv`, hasil ekstraksi Owner's Manual resmi PSR-E343 (lihat `PSR_E343_MIDI_OUT_COMPATIBILITY.md`).

## 1. File yang diaudit
* File unggahan pengguna `EnglishWaltz2.S519.prs`: 36254 byte, SHA-256 `90c9efb49ad1a7c3db4ae99b2f15cf4f4da9e2789f7e75efd6a3ab23df9e94a3`. Header SFF1 menyebut nama internal `EnglishWaltz2.S519.STY`. Repository tidak memuat Style lain dengan nama serupa, dan tidak ada Style lain yang dipakai.
* File Style **tidak di-commit**: `tests/fixtures` dibekukan oleh guard, dan file ini milik pengguna. Test akan otomatis dilewati (skip) jika file tidak diberikan. Cara menjalankannya ada di §9.
* File diparse dengan parser C++ produksi (`tools/dump_style_shadow_fixture.cpp` + `style_parser.cpp` + `smf_reader.cpp`). Hasil: PPQ 1920, birama 3/4, 15 section: IntroA/B/C, MainA/B/C/D, FillAA/BA/BB/CC/DD, EndingA/B/C.

## 2. Ringkasan (terbukti vs dugaan)
**Terbukti** (data Style, katalog resmi, simulasi rantai produksi):
1. **Kit kompatibel.** Rhythm2 'Drums' (ch 10) mengirim **127/0/PC1 = list 2 = #189 Standard Kit 2** di semua section. Rhythm1 'Perc' (ch 9) mengirim **127/0/PC0 = #188 Standard Kit 1**. Keduanya **EXACT** di E343.
2. **Semua not punya sampel.** Setiap not drum yang ditulis Style ada di kit E343 yang dipilih (lihat §5). Tidak ada not tanpa suara, dan tidak ada kit yang tidak didukung pada nilai yang benar-benar dikirim.
3. **Rhythm1 hanya berbunyi di IntroC.** Isinya satu not, 49 Crash Cymbal 1, ×47, velocity 4–54 (crash roll crescendo). Di 14 section lain tidak ada satu pun NOTE ON di ch 9, baik di data Style maupun di wire.
4. **MIDI OUT = data Style.** Not dan velocity yang ditulis ke port USB sama dengan yang ditulis Style dan sama dengan yang diterima engine internal. Tidak ada transpose, remap, atau duplikasi di jalur default. Urutan CC0 → CC32 → PC benar.
5. **NOTE OFF drum tidak pernah dikirim.** Ini perilaku baseline: sequencer memperlakukan not Rhythm sebagai one-shot, jadi NOTE OFF dari Style tidak diteruskan, baik ke engine maupun ke MIDI OUT. Diagnostiknya `OFF_NO_ACTIVE_LEDGER ... oneShotDrumMayBeNormal=1` di `StyleSequencer`. Simulasi mencatat `noteOff=0` di ch 9/10 untuk semua section.
6. **Ciri khas EnglishWaltz2:** Style ini memakai keluarga **Brush, not 25–28**, yang tidak dipakai ROSITA maupun HardRock2. Di E343, not 25–28 adalah Brush Tap, **Brush Swirl**, Brush Slap, dan Brush Tap Swirl. Not 26 (Brush Swirl) dibunyikan **di setiap ketukan pada MainA dan MainB** (12 kali per 4 birama 3/4 = 3 kali per birama) dan **ditahan 1900 tick**, hampir satu ketukan penuh. Semua not drum lain ditahan 20 tick. Pola 3 kali per birama ini cocok dengan bunyi "tres-tres-tres".
7. **Mengganti kit tidak mengubah not 25–27.** Menurut katalog resmi, di semua kit E343 MSB 127 (Standard 1/2, Room, Rock, Electronic, Analog, Dance, Jazz, Brush, Symphony), not 25–27 berbunyi sama dengan Standard Kit 1 (Brush Tap/Swirl/Slap). Hanya Electronic Kit dan Analog Kit yang mengganti not 28 menjadi Reverse Cymbal.

**Dugaan** (belum terbukti di perangkat):
* **H-A (utama):** "tres" adalah sampel Brush Swirl (26), mungkin bersama Brush Tap (25) dan Brush Slap (27), di E343. Not-nya benar sesuai Style, tetapi sampel E343 terdengar kasar.
* **H-B:** jika Brush Swirl di E343 adalah sampel ber-loop yang menunggu NOTE OFF, maka karena aplikasi tidak pernah mengirim NOTE OFF untuk drum, suara swirl bisa menumpuk dan terus berbunyi. Panjang 1900 tick yang ditulis Style untuk not 26 dan 28 menunjukkan penulis Style sengaja mengandalkan NOTE OFF. Dokumen E343 tidak menjelaskan apakah kit drum-nya merespons NOTE OFF.
* **H-C:** laporan "masalah ada di KEDUA Rhythm" tidak cocok dengan data, karena di luar IntroC tidak ada not apa pun di ch 9. Kemungkinannya: pengujian dilakukan di IntroC, swirl yang belum berhenti terdengar saat channel di-mute/solo, atau ada sumber lain. Ini perlu dicek dengan log di perangkat (§8, T2).

## 3. Rhythm, CASM, dan permintaan bank/program (semua section)
| Part (CASM) | src → dst (1-based) | Section | Tick 0 di file (urutan) | Yang dikirim aplikasi | E343 |
|---|---|---|---|---|---|
| Drums | ch10 → ch10 | 15 section | 127/0/PC85 (list 86), lalu 127/0/PC1 | 127/0/PC1 (list 2) | #189 Standard Kit 2 **EXACT** |
| CC_Crash | ch8 → ch10 | 12 section (bukan IntroB, IntroC, EndingC) | 127/0/PC83 (list 84), lalu 127/0/PC1 | sama dengan Drums (PC1) | **EXACT**; part ini tidak berisi NOTE ON |
| Perc | ch9 → ch9 | hanya IntroC | 127/0/PC85, lalu 127/0/PC0 | 127/0/PC0 (list 1) | #188 Standard Kit 1 **EXACT** |

* Set pertama (list 84/86) tidak ada di E343, tetapi **tidak dikirim**: aplikasi hanya mengirim set terakhir di tick 0. Ini sudah dibuktikan di wire untuk semua section. Kalau E343 mengikuti urutan pesan, ia juga akan berakhir di set terakhir.
* Policy CASM drum: NTR/NTT bypass untuk drum (aplikasi tidak mentranspos part drum), rentang not 0–127, tidak ada mute mask, jadi tidak ada not yang dibuang.
* Kedua Rhythm memakai kit dan channel yang **berbeda** (Std Kit 1 di ch 9, Std Kit 2 di ch 10). Tidak ada konflik kit.

## 4. Simulasi MIDI OUT aktual (rantai produksi, test host `EnglishWaltz2E343DrumTest`: OK 2 tests)
Setiap section dimainkan dari STOP pada tempo 280 BPM (agar cepat), dengan MIDI OUT aktif. Byte di port USB ch 9/10 direkam. Hasil per section (pass pertama):

| Section | ch 9 (Rhythm1) | ch 10 (Rhythm2): NOTE ON per not (pass pertama) |
|---|---|---|
| IntroA | – | 25×2, 27×1, 35×1, 44×3 (+ crash 49 di batas akhir) |
| IntroB | – | 35×7, 40×10, 44×8, 45×2, 49×1, 59×23 |
| IntroC | 127/0/PC0; 49×47 | 35×2, 44×4, 49×1, 57×47, 59×4 |
| MainA | – | 25×8, **26×12**, 35×4, 44×8 |
| MainB | – | 25×16, **26×12**, 27×8, 35×6, 44×9 |
| MainC | – | 25×4, 26×1 (ditahan 11500 tick), 27×4, 35×4, 44×4, 51×6 |
| MainD | – | 35×4, 40×4, 44×4, 59×12 |
| FillAA | – | 25×4, 26×1, 27×1, 28×2, 35×2, 40×1, 44×2, 46×1, 49×1 |
| FillBA | – | 35×2, 40×3, 44×1, 47×2, 49×1, 59×2 |
| FillBB | – | 25×5, 26×2, 27×1, 35×2, 44×2, 45×2, 46×1, 49×1 |
| FillCC | – | 25×3, 26×1, 27×3, 35×2, 40×1, 44×1, 45×3, 46×1, 49×1, 51×1 |
| FillDD | – | 35×2, 40×3, 45×2, 46×2, 47×3, 49×1, 59×2 |
| EndingA | – | 35×1 |
| EndingB | – | 35×2, 40×3, 44×1, 45×1, 46×1, 47×3, 59×3 |
| EndingC | – | 35×5, 40×5, 44×5, 57×11, 59×14 |

Yang diperiksa (assert):
* Program Change selalu didahului CC0 lalu CC32.
* Not di MIDI OUT sama dengan not di engine internal.
* Setiap pasangan (not, velocity) yang dikirim memang ditulis oleh Style.
* Tidak ada NOTE OFF di ch 9/10 selama playback.

Selisih ±1 di beberapa section (misalnya MainA 26×13) berasal dari jendela rekaman 250 ms yang sudah masuk ke awal loop berikutnya. Celah retrigger 0–17 ms di IntroC/EndingC adalah jitter scheduler host. Data Style sendiri berjarak minimal 160–240 tick dan **tidak berisi NOTE ON ganda pada tick yang sama**. Waktu di host bukan bukti waktu di perangkat.

## 5. Drum note vs katalog resmi PSR-E343 (seluruh section)
Tabel lengkap ada di `diagnostics/e343/EnglishWaltz2_E343_drum_notes.md`. Kolom "keyboard" = not MIDI + 12, sesuai catatan kaki manual.

| Not MIDI | Suara di kit E343 yang dipilih | NOTE ON | Velocity | Panjang not di Style (tick) | Section |
|---|---|---|---|---|---|
| 25 | Brush Tap | 42 | 15–116 | 20 | IntroA, MainA/B/C, FillAA/BB/CC |
| **26** | **Brush Swirl** | 29 | 25–51 | **1900–11500** | **MainA ×12, MainB ×12**, MainC, FillAA/BB/CC |
| 27 | Brush Slap | 18 | **114–125** | 20 | IntroA, MainB ×8, MainC ×4, Fill |
| 28 | Brush Tap Swirl | 2 | 54–63 | 940–1900 | FillAA |
| 35 | Bass Drum Hard | 46 | 25–65 | 20 | semua section |
| 40 | Snare H Hard 2 (khusus Std Kit 2) | 30 | 16–103 | 20 | IntroB, MainD, Fill, Ending |
| 44 | Hi-Hat Pedal | 52 | 1–71 | 20 | hampir semua section |
| 45 / 46 / 47 | Low Tom / Hi-Hat Open / Mid Tom L | 10 / 6 / 8 | 18–80 | 20 | Fill, IntroB, EndingB |
| 49 | Crash Cymbal 1 (Rhythm1 ×47 di IntroC; Rhythm2 ×8) | 55 | 4–54 | 20 | Intro, Fill |
| 51 | Ride Cymbal 1 | 7 | 56 | 20 | MainC, FillCC |
| 57 | Crash Cymbal 2 | 58 | 5–34 | 20 | IntroC ×47, EndingC ×11 (cymbal roll) |
| 59 | Ride Cymbal 2 | 60 | 25–72 | 20 | IntroB, IntroC, MainD, FillBA/DD, EndingB/C |

## 6. Kandidat awal 25, 26, 27, 35, 44, 59
| Not | Ada di kit E343? | Dipakai di kontrol? | Penilaian |
|---|---|---|---|
| 25 Brush Tap | ya (sama dengan Std Kit 1) | tidak (ROSITA dan HR2 tidak memakai not < 33) | Kandidat. Pendek, ada di MainA/B; MainB 16 kali dengan jarak minimal 240 tick. |
| 26 Brush Swirl | ya | tidak | **Kandidat utama.** Dibunyikan tiap ketukan di MainA/B dan ditahan 1900 tick (tidak ada NOTE OFF di wire). Polanya cocok dengan "tres-tres-tres". |
| 27 Brush Slap | ya | tidak | Kandidat kedua. Velocity 114–125, sekali per birama di MainB/MainC. Tidak ada di MainA. |
| 35 Bass Drum Hard | ya | **ya, ROSITA (×7, terdengar normal)** | Kecil kemungkinan. |
| 44 Hi-Hat Pedal | ya | tidak | Kecil–sedang. Pendek, vel 57, dua kali per birama. |
| 59 Ride Cymbal 2 | ya | tidak | Kecil untuk MainA/B karena **tidak dipakai di MainA/MainB**. Jika "tres" juga terdengar di MainA, not 59 bukan penyebabnya di sana. |

Tidak ada kandidat yang dianggap "salah" secara otomatis: semua not ini ada di kit E343 dan memang ditulis seperti itu oleh Style.

## 7. Kontrol: ROSITA (normal) dan HardRock2 (agak bermasalah)
| | ROSITA | HardRock2 | EnglishWaltz2 |
|---|---|---|---|
| Kit yang dikirim | 127/0/1, /9, /17: semua EXACT | R1 127/0/1 EXACT; **R2 127/0/87 tidak ada di E343** | R1 127/0/1, R2 127/0/2: EXACT |
| Rentang not drum | 33–64 | 36–57 | **25–59** |
| Keluarga Brush 25–28 | tidak | tidak | **ya (91 NOTE ON)** |
| Not panjang | panjang (sampai 5400 tick), tetapi semuanya suara one-shot (kick, rim, conga ...) | 4–484 tick | 20 tick, **kecuali Brush Swirl dan Tap Swirl (940–11500)** |
| Velocity | sebagian besar 90–127 | 21–127 | rendah (1–72) kecuali Brush Slap 114–125 |

Ketiga Style sama-sama tidak mendapat NOTE OFF drum. ROSITA tetap normal karena semua suaranya one-shot. Jadi ketiadaan NOTE OFF baru berarti jika suara drum-nya **berkelanjutan**, yaitu keluarga swirl/roll, dan di antara ketiga Style hanya EnglishWaltz2 yang memakainya. Peringkat keparahan dari pengguna (ROSITA normal < HardRock2 agak bermasalah < EnglishWaltz2 parah) konsisten dengan ini. Masalah HardRock2 punya penyebab lain yang sudah terbukti di tingkat katalog: kit R2 nomor 87 tidak ada di E343.

## 8. Usulan pengujian dan perbaikan (masing-masing bisa diuji terpisah, urutan dari yang paling kecil)
**T0, langsung di E343 tanpa aplikasi (membuktikan H-A/H-B):** pilih voice **#189 Standard Kit 2** di panel E343, lalu tekan tuts keyboard **C#1, D1, D#1, E1** (= MIDI 25–28: Brush Tap, Swirl, Slap, Tap Swirl). Tekan juga G#2 (44) dan B3 (71 = MIDI 59).
* Jika D1 terdengar "tres", H-A terbukti di perangkat.
* **Tahan D1 lama, lalu lepas.** Jika suara berhenti saat tuts dilepas (bergantung NOTE OFF) atau terus menyala/ber-loop, berarti NOTE OFF penting, dan H-B jadi relevan.

**T1, di aplikasi dengan fitur yang sudah ada, tanpa ubah kode:** Style Editor → Rhythm 2 → *drum note map*, output **MIDI OUT**, semua section: petakan **26 → 85**. Menurut daftar resmi, not 85 tidak punya suara di Standard Kit 2 (kit hanya terisi di not 13–84). Test host sudah membuktikan di wire: di MainA, 14 NOTE ON not 26 menjadi not 85 dengan velocity sama, engine internal tetap memainkan not 26, dan not lain tidak berubah.
* Jika "tres" hilang, penyebabnya not 26.
* Ulangi terpisah untuk 25 → 85 dan 27 → 85.
* Peta ini hanya untuk diagnosis: membungkam not sama dengan mengubah musiknya. Hapus peta setelah selesai menguji.

**T2, cek klaim "kedua Rhythm":** mainkan MainA, lalu buka RIGHT DIAG → **MIDI OUT STATE**. Menurut data, `ch9 [Rhythm 1] NOTE_ON written` harus 0 di MainA. Jika tidak 0, kirim teks laporannya. Sertakan juga section yang sedang dimainkan saat menguji Rhythm1.

**Perbaikan yang mungkin, setelah ada bukti dari T0–T2. Tidak ada yang diterapkan sekarang:**
* **F1 (tanpa kode):** jika T1 membuktikan not tertentu, simpan drum map MIDI OUT khusus EnglishWaltz2. Pilih targetnya dengan telinga dari daftar E343, atau gunakan not tanpa suara. Hanya berlaku untuk Style ini; engine internal tidak berubah. Ini perbaikan paling kecil dan aman, tetapi baru boleh dibuat setelah ada bukti, bukan secara spekulatif.
* **F2 (perubahan kode kecil, opt-in, branch terpisah):** teruskan NOTE OFF Style untuk not Rhythm **ke MIDI OUT saja**, berpasangan lewat ledger router yang sudah ada. Default OFF supaya baseline tetap sama. Hanya layak jika T0 menunjukkan Brush Swirl di E343 bergantung pada NOTE OFF. Ini memperbaiki akar masalah (swirl berhenti sesuai panjang yang ditulis Style) tanpa membungkam not apa pun. Membutuhkan test baru: pasangan NOTE ON/OFF per not, mute/solo, STOP, dan perpindahan section.
* **F3 (kit override MIDI OUT):** **tidak disarankan untuk masalah ini.** Menurut katalog resmi, not 25–27 sama di semua kit MSB 127 (pada not 28, hanya Electronic dan Analog Kit yang menggantinya dengan Reverse Cymbal), jadi mengganti kit tidak mengubah Brush Tap/Swirl/Slap. Selain itu fitur override eksternal sengaja tidak menyentuh part drum, sehingga perlu perubahan kode.
* **Per-note mapping E343 global (untuk semua Style): jangan dibuat.** Buktinya baru dari satu Style, dan kedua Style kontrol tidak memakai not 25–28 sama sekali, jadi data belum mendukung mapping global.

## 9. Bukti dan cara mengulang
* `tests/rtdiag/EnglishWaltz2E343DrumTest.kt` (test baru, hanya membaca):
  `python3 tools/test_rtdiag.py --out evidence/ew2-e343 --rosita --style <path>/EnglishWaltz2.S519.prs --style-name EnglishWaltz2.S519.prs --only com.yourapp.rtdiag.EnglishWaltz2E343DrumTest` menghasilkan **OK (2 tests)**. Tanpa file Style, test di-skip.
* `diagnostics/e343/e343_drum_notes.py`: tabel per-not terhadap katalog resmi. Hasilnya ada di `diagnostics/e343/EnglishWaltz2_E343_drum_notes.md`, `ROSITA_E343_drum_notes.md`, `HardRock2_E343_drum_notes.md`.
* `diagnostics/e343/EnglishWaltz2_E343_compat.md`: semua permintaan bank/PC, termasuk part melodis. Catatan di luar lingkup drum: Pno 0/113/1, Str 0/116/50, dan FrHn 0/115/61 adalah FALLBACK_CANDIDATE (LSB tersebut tidak ada di E343). Tidak ada yang diubah.
* Guard `tools/test_e343_audit_guard.py` lulus (tidak ada perubahan di `app/`).
* Ini bukti logika software dan katalog, **bukan** bukti bagaimana E343 benar-benar berbunyi. T0–T2 diperlukan untuk itu.
