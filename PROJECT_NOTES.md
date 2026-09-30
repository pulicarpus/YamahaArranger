# YamahaArranger — Catatan Proyek

## Baseline resmi

- Branch utama: `main`
- Commit baseline: `52e5b7ea6f8ee4b1166f999e198fea07c8573ab1`
- Repository: `pulicarpus/YamahaArranger`
- Branch `fix/yamaha-panel-sustain-clean` sudah dipromosikan menjadi baseline `main`.
- Audit ini tidak mengubah source code aplikasi; perubahan dokumentasi dipisahkan dari perbaikan engine.

## Arsitektur saat ini

### Jalur style

Yamaha STY / PRS / SFF / SFF GE
→ native SMF parser
→ StyleSectionModel / StylePartModel
→ CASM policy
→ StyleSequencer
→ AudioEngineManager
→ BASSMIDI
→ Oboe

### Jalur MIDI/keyboard

Android MIDI
→ MidiInputManager
→ routing chord/keyboard
→ ArrangerBrain
→ StyleSequencer + AudioEngineManager

## File yang menjadi acuan

| File | Fungsi |
|---|---|
| `app/src/main/cpp/style_parser.cpp` | parser SMF/style marker + CASM |
| `app/src/main/cpp/smf_reader.cpp` | parser MIDI standar |
| `app/src/main/java/com/yourapp/yamahaarranger/style/StyleRepository.kt` | pembentuk model style Kotlin |
| `app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt` | timeline musik, transisi dan kepemilikan note |
| `app/src/main/java/com/yourapp/yamahaarranger/arranger/ArrangerBrain.kt` | state arranger live, Auto Fill dan routing keyboard |
| `app/src/main/java/com/yourapp/yamahaarranger/arranger/CasmNoteTransformer.kt` | transformasi note NTR/NTT/RTR |
| `app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt` | facade audio Kotlin |
| `app/src/main/cpp/bassmidi_player.cpp` | BASSMIDI, mapping SF2 dan Voice Resolver |
| `app/src/main/cpp/audio_engine.cpp` | output Oboe |
| `app/src/main/cpp/native_lib.cpp` | JNI bridge |

## Blueprint dan riset

Baca dokumen berikut sebelum menyentuh engine:

- `docs/ARRANGER_ENGINE_AUDIT.md`
- `docs/ARRANGER_ENGINE_BLUEPRINT.md`
- `docs/MIDI_VOYAGER_PRO_5.4.11_AUDIO_RESEARCH.md`

Referensi perilaku eksternal yang digunakan dalam audit:

- GigLad
- vArranger
- One Man Band
- Android Arranger Keyboard
- MIDI Voyager Pro
- Yamaha arranger/SFF

## Yang sudah benar

### Arsitektur transisi

- master clock kontinu
- pending transition queue
- queued successor sections
- tidak ada global `allNotesOff` saat seamless section change
- release note style keluar dilakukan secara targeted
- Auto Fill
- Main A-D
- logika Intro/Ending/Fill successor ketika perubahan section dimulai saat playback

### CASM

- CSEG
- Ctab/Ctb2
- Cntt override
- NTR
- NTT
- RTR 0-5
- High Key
- Note Limits
- Bass-On
- chord mute policy
- diagnostics CASM

### Audio

- BASSMIDI sebagai jalur aktif
- BASSMIDI NOTEOFF1
- Yamaha MSB/LSB bank
- Yamaha variation-bank normalization
- role SF2 melody/drum
- async preload
- routing drum
- resolver berbasis nama voice
- mixer channel
- master gain

### Keyboard

- split point
- RIGHT 1/2/3
- LEFT
- transpose
- sustain ledger
- release time
- MIDI OUT
- input MIDI bergaya E343

## Sumber bug yang sudah diketahui

### P0.1 Voice Resolver — SELESAI TAHAP 1

File: `app/src/main/cpp/bassmidi_player.cpp`

Commit perbaikan: `bb203f604b96c04ad43cbe42b1933b3b73a3c972`

Perubahan:
- exact bank/program tetap menjadi prioritas pertama;
- MSB-only bank + program tetap diterima;
- semantic name/category sekarang dicari **sebelum** fallback same-program lintas bank;
- kandidat beda-family diberi penalti agar tidak menang hanya karena bank numeriknya dekat;
- fallback numeric baru dipakai setelah pencarian semantic gagal;
- log `VOICE RESOLVE semantic/numeric fallback/final fallback` ditambahkan.

Target urutan:
exact bank/program → MSB-only bank/program → semantic category/name → same-program lintas bank → Piano/fallback terakhir.

Jangan mengubah CASM untuk memperbaiki voice resolution.

### P0.2 Realtime mutex

File: `app/src/main/cpp/bassmidi_player.cpp`

`render()` dan operasi control/preload masih berbagi `mutex_`.

Risiko:
callback audio menunggu saat voice/program change atau operasi lain sedang berjalan.

Target:
render realtime tidak bergantung pada mutex operasi UI/file yang lambat.

### P0.3 Identitas instance note

File: `app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt`

Kunci sekarang masih:
`sourceChannel:sourceNote`

Risiko:
dua note identik yang overlap dapat dianggap satu record aktif.

Target:
section generation + source track + source channel + event index, atau token instance unik lain.

### P0.4 Directional Fill

File: `app/src/main/cpp/style_parser.cpp`

ArrangerBrain sudah memiliki konsep FillAB/BA dan arah lain, tetapi model native StyleSection terutama hanya mempertahankan FillAA/BB/CC/DD.

Target:
pertahankan arah Fill dari file style, bukan mereduksinya menjadi Fill generik.

### P1.1 Intro/Ending saat idle

File: `ArrangerBrain.kt`

Target:
Intro ketika idle → Intro satu kali → Main.
Ending ketika idle → Ending satu kali → Stop.

### P1.2 Model timing

Sekarang masih ada:
wall-clock next-bar quantization + coroutine scheduler + master tick.

Target:
satu musical tick yang menjadi sumber kebenaran untuk quantization dan section transition.

## Sustain

Sustain panel diimplementasikan sebagai keyboard note ledger di ArrangerBrain dengan kontrol release native.

Note ACMP/chord sengaja tidak dimasukkan ke keyboard sustain.

Acuan Yamaha E343:

- CC64 = Sustain
- CC72 = Release Time

## Regression test yang wajib

1. Main A → Main B
2. Main B → Main D
3. Main D → Main A
4. Intro → Main
5. Fill → Main
6. Ending → Stop
7. repeated same-note events
8. 2/4
9. 3/4
10. 4/4
11. 6/8
12. drum + melody
13. Strings voice resolution
14. Yamaha variation bank resolution
15. SF2 reload
16. sustain ON/OFF
17. release time changes

## Peringatan dokumentasi lama

Dokumentasi lama pernah menyebut:

- FluidSynth sebagai engine aktif;
- sine-wave placeholder sebagai jalur SoundFont;
- CASM belum lengkap;
- StyleSequencer versi lama.

Pernyataan tersebut **bukan lagi acuan** untuk branch `main`.

## Aturan pengembangan

Jangan menyentuh subsystem hanya karena dokumentasi lama mengatakan subsystem tersebut belum selesai.

Selalu periksa branch `main` saat ini.

Jika ada bug, klasifikasikan:

parser → CASM → transition → note ownership → Voice Resolver → state BASSMIDI → realtime audio → UI state.

Kemudian ubah batas terkecil yang memang bertanggung jawab dan tambahkan regression test.

## Hasil audit saat ini

Baseline `main` sudah menjadi acuan proyek.

Engine belum dianggap bebas bug. Empat target P0 utama adalah:

1. Voice Resolver.
2. realtime BASSMIDI mutex.
3. unique note-instance ownership.
4. directional Fill parser/model.


## Dataset audit Yamaha SX920 Factory Styles — 2026-09-29

User menyediakan `SX920_Factory_Styles_EN.zip` sebagai dataset referensi factory style Yamaha SX920. Dataset ini diperlakukan sebagai **fixture/regression corpus**, bukan sebagai source code aplikasi.

Inventaris awal:
- 520 file `.prs`
- 49 file `.sst`
- total 569 style
- 6 file `.fps` ikut terdeteksi dalam paket, tetapi bukan bagian dari hitungan 569 style utama.
- Audit awal menunjukkan seluruh 569 style yang diaudit menggunakan PPQ 1920.

Distribusi FF58 pada audit awal:
- 4/4: 523
- 3/4: 33
- 6/4: 6
- 6/8: 3
- 2/4: 3
- 5/4: 1

Audit awal juga menemukan 42 style yang indikasi meter pada nama style berbeda dari nilai FF58. Ini harus diperlakukan sebagai sinyal bahwa FF58 tidak boleh menjadi satu-satunya sumber kebenaran untuk menentukan groove/meter style. Panjang section dan struktur event MIDI harus ikut dianalisis.

### Tujuan audit otomatis 569 style

Audit lanjutan harus menggunakan parser native YamahaArranger bila memungkinkan, sehingga hasil audit mencerminkan jalur parser yang benar-benar dipakai aplikasi.

Data minimum yang perlu diekstrak per style:
- nama/file/extension
- PPQ
- tempo
- FF58
- marker section
- panjang Main A/B/C/D dalam tick
- Intro A/B/C
- Fill/Break dan arah Fill bila tersedia
- Ending A/B/C
- channel/track yang aktif
- bank MSB/LSB + Program Change
- CASM CSEG
- NTR
- NTT
- RTR
- High Key
- Note Limit
- Bass-On
- source/destination channel
- kepadatan note/event dan karakter subdivision

Output audit:
- CSV untuk analisis/filter
- JSON untuk data terstruktur
- Markdown untuk ringkasan temuan
- daftar regression styles terpilih

### Prinsip penting dari dataset

1. Jangan menganggap nama style sebagai bukti meter MIDI.
2. Jangan menganggap FF58 sendirian sebagai panjang groove/section.
3. Gunakan tick/event/marker aktual untuk menentukan panjang section.
4. Gunakan CASM aktual untuk memahami revoicing dan routing.
5. Gunakan bank/program aktual untuk menguji Voice Resolver.
6. Jangan mengubah engine hanya berdasarkan satu style; gunakan corpus 569 style.
7. Setiap perubahan parser/CASM/transition/Voice Resolver harus diuji ulang terhadap regression corpus.

### Regression corpus yang akan dipilih

Setelah audit native parser selesai, pilih sekitar 20–30 style yang mewakili:
- 2/4, 3/4, 4/4, 6/8, 12/8 dan meter tidak lazim
- perbedaan nama meter vs FF58
- Main A-D dengan panjang berbeda
- Intro → Main
- Fill → Main
- Main D → Main A
- directional Fill
- CASM NTR/NTT/RTR kompleks
- Guitar/NTR khusus
- Yamaha variation bank
- banyak channel accompaniment
- kombinasi drum + melody

Corpus ini menjadi regression suite sebelum perubahan besar pada StyleSequencer, CASM, parser, atau Voice Resolver.

### Status

Audit statis awal 569 style: **SELESAI**.

Tahap berikutnya:
1. jalankan audit menggunakan parser native `style_parser.cpp`;
2. bandingkan hasil parser native dengan audit SMF statis;
3. identifikasi mismatch parser;
4. pilih 20–30 regression styles;
5. baru gunakan hasil tersebut sebagai dasar perubahan engine.

**Catatan:** audit dataset tidak boleh dianggap sebagai perubahan engine. Source code engine tetap tidak diubah hanya karena hasil audit menunjukkan kasus yang belum didukung.


## Tahap 2 — Audit parser native terhadap corpus SX920 — 2026-09-29

Tahap 2 selesai secara audit terhadap 569 style menggunakan perilaku parser yang sama dengan `style_parser.cpp` pada baseline `main`. Audit dilakukan tanpa mengubah source engine.

Hasil:
- 569/569 style berhasil diparse.
- 569/569 menggunakan PPQ 1920.
- 569/569 memiliki Main A, Main B, Main C, dan Main D.
- Panjang Main A/B/C/D hasil parser native 100% cocok dengan audit SMF statis untuk seluruh 569 style.
- CASM ditemukan pada seluruh corpus.
- 2.218 CSEG terdeteksi.
- 22.940 Ctb2 terdeteksi.
- 68.820 policy range Ctb2 terbentuk dari model parser.
- Pemeriksaan source-channel CASM terhadap voice setup tidak menemukan mismatch jumlah source channel pada corpus.

### Temuan P0 yang sekarang terbukti oleh corpus

**Directional Fill bukan kasus langka — seluruh 569 style memiliki marker `Fill In BA`.**

Audit SMF statis menemukan marker `Fill In BA`.

Namun `StyleParser::classifyMarkerText()` saat ini:
- mengenali Fill AA/BB/CC/DD;
- untuk pola `ba` mengembalikan `StyleSection::BreakDown`;
- tidak mempertahankan identitas directional Fill BA sebagai section tersendiri.

Akibatnya, pada seluruh 569 style:
`Fill In BA` → `BreakDown`

Ini mengonfirmasi P0.4 sebelumnya secara jauh lebih kuat: informasi directional Fill memang hilang pada batas parser/model, dan masalahnya bukan edge case satu style.

### Temuan penting lain

Panjang Main A/B/C/D tidak bermasalah pada corpus ini. Jadi untuk masalah transisi Main dan panjang section, **jangan mengubah perhitungan lengthTicks parser secara membabi buta**. Parser saat ini berhasil mempertahankan panjang Main terhadap audit event/marker statis.

### CASM

Corpus menunjukkan dominasi `Ctb2`, bukan `Ctab`:
- CSEG: 2.218
- Ctb2: 22.940
- Ctab: 0 pada corpus audit
- Cntt: 0 pada corpus audit

Ini menjadikan Ctb2/range-policy sebagai jalur CASM yang paling penting untuk regression test factory SX920.

### Keputusan engineering

Belum ada perubahan source code pada tahap ini.

Sebelum memperbaiki parser:
1. pertahankan hasil audit sebagai baseline;
2. tambahkan regression fixture untuk `Fill In BA`;
3. desain representasi directional Fill yang tidak merusak section AA/BB/CC/DD yang sudah berjalan;
4. audit ulang seluruh 569 style setelah perubahan parser;
5. baru lanjut ke StyleSequencer untuk memastikan arah Fill benar-benar dipakai saat transition.

Jangan memperbaiki masalah directional Fill di CASM atau Voice Resolver; sumber masalah yang terbukti berada pada klasifikasi/model section parser.

### Artefak audit

- `SX920_Factory_Styles_Audit/native_parser_audit.csv`
- `SX920_Factory_Styles_Audit/native_parser_audit_summary.json`

Artefak ini adalah snapshot audit, bukan source engine.


## Tahap 3 — Implementasi parser directional Fill — 2026-09-29

Tahap 3A/3B selesai. Source engine sekarang mempertahankan directional Fill di batas parser native.

Perubahan yang dilakukan:
- StyleSection native diperluas dari Fill AA/BB/CC/DD menjadi seluruh 16 kombinasi AA..DD: FillAA, FillAB, FillAC, FillAD, FillBA, FillBB, FillBC, FillBD, FillCA, FillCB, FillCC, FillCD, FillDA, FillDB, FillDC, FillDD.
- StyleParser::classifyMarkerText() sekarang membaca kode dua huruf setelah marker Fill dan mempertahankan arahnya.
- Fill In BA tidak lagi dipetakan ke BreakDown; hasilnya FillBA.
- styleSectionToString() dan styleSectionFromString() diperluas agar JNI dapat membawa semua directional Fill ke Kotlin.
- ArrangerBrain tidak perlu diubah karena enum dan fillForTransition() sudah mendukung directional Fill.
- CASM, Voice Resolver, StyleSequencer, BASSMIDI, sustain, dan keyboard routing tidak disentuh.

Commit source:
- 0ae5fcb680100310fced4d1fd62a77a9c84be9c0 — style_parser.h
- 43820fed77ed92b1e6cb401b0eb5bf3a4f93c0a3 — style_parser.cpp

Verifikasi corpus sebelum build:
- 569/569 style tetap menjadi corpus utama.
- Fill In BA terbukti ada pada 569/569 style pada audit sebelumnya.
- Dengan klasifikasi dua-huruf baru, marker Fill In BA diarahkan ke FillBA, bukan BreakDown.

Status:
- Implementasi parser selesai.
- Build APK belum diverifikasi dari commit ini karena workflow .github/workflows/build.yml saat ini hanya trigger otomatis pada beberapa branch fitur/fix dan tidak mencantumkan main.
- Tahap berikutnya: verifikasi build native/Gradle, lalu audit ulang 569 style terhadap section names/length/CASM dan regression transisi Main→Fill→Main.


## Tahap 4 — Audit 569 style setelah parser directional Fill — 2026-09-29

Audit terhadap corpus SX920_Factory_Styles_EN.zip dilakukan setelah Stage 3 dan menemukan satu bug nyata pada implementasi classifier:

- Marker Yamaha ditulis sebagai Fill In BA.
- Implementasi awal Stage 3 melakukan compaction menjadi fillinba, tetapi langsung membaca dua karakter setelah fill, sehingga yang terbaca adalah in, bukan ba.
- Akibatnya directional Fill belum benar-benar lolos ke FillBA.

Perbaikan dibuat pada app/src/main/cpp/style_parser.cpp:
- setelah fill, classifier sekarang melewati token opsional in;
- kemudian membaca dua huruf arah AA..DD;
- Fill In BA sekarang dipetakan ke FillBA.

Commit perbaikan:
- 4b682f4c7380a9f72b2d1cab196bf7159faad9902 — fix parser handling of Yamaha Fill In directional markers.

Verifikasi corpus setelah perbaikan:
- 569/569 style terdeteksi.
- 0 parse failure pada audit corpus.
- 569/569 PPQ 1920.
- 569/569 style yang memiliki Fill In BA diklasifikasikan sebagai FillBA.
- 0 Fill In BA yang jatuh ke BreakDown.
- Panjang Main A/B/C/D: 0 mismatch dibanding baseline Stage 2.
- Baseline Stage 2 sebelumnya mencatat Fill In BA sebagai BreakDown pada 569/569; hasil ini sekarang berubah sesuai target parser directional.

### Status build
Build APK belum dapat dijalankan dari environment saat checkpoint ini karena workflow .github/workflows/build.yml hanya auto-trigger pada branch fitur/fix tertentu dan tidak memiliki main pada push trigger. Workflow memang memiliki workflow_dispatch, tetapi tool GitHub yang tersedia di sesi ini tidak menyediakan aksi dispatch workflow baru.

Jangan menyatakan APK/build hijau sampai ada hasil build nyata.

Tahap berikutnya:
1. jalankan build native/Gradle melalui CI atau environment build yang tersedia;
2. audit 569 style terhadap hasil parser yang benar-benar terkompilasi;
3. lanjut regression transition Main → Fill → Main setelah build valid.


## Tahap 5 — Multi-SF2 Inspector aman untuk SF2 besar — 2026-09-29

Tujuan tahap ini adalah memisahkan **inspeksi seluruh SF2 yang tersimpan** dari daftar preset yang sedang dimuat engine. Ini penting karena folder managed dapat berisi banyak SF2, sedangkan engine saat ini sengaja hanya memuat kombinasi font yang dipilih untuk playback.

### Masalah yang ditemukan

Sebelumnya:
- `ContentResolverProvider.listSoundFonts()` sudah menemukan seluruh file `.sf2`.
- `refreshSoundFontList()` hanya mengisi `availableSoundFonts`, lalu mengambil preset dari `audioEngine.loadedSoundFontPresets()`.
- Akibatnya UI dapat mengatakan "managed files = 6" tetapi hanya menampilkan preset dari SF2 yang sedang dimuat engine.
- Implementasi `SoundFontInspector.inspect()` lama menggunakan `InputStream.readBytes()`, sehingga inspeksi file Yamaha/Tyros berukuran ratusan MB berpotensi menggunakan RAM sangat besar.

### Implementasi

Perubahan:
- `SoundFontInspector.inspect()` sekarang menggunakan parser RIFF streaming dengan buffer 64 KiB.
- Parser hanya membaca metadata SF2 yang diperlukan:
  - RIFF/SFBK validity;
  - INFO: INAM, ISFT, ICMT;
  - pdta/phdr: nama, program, bank;
  - inst: jumlah instrument;
  - shdr: jumlah sample.
- Chunk sample/audio besar dilewati dengan `skipFully()`; sample audio tidak dimuat ke RAM.
- `MainViewModel` menambahkan:
  - `sf2Reports`;
  - `sf2ScanInProgress`.
- `refreshSoundFontList()` sekarang melakukan scan berurutan terhadap **semua managed SF2**, tanpa mengubah konfigurasi playback.
- Inspector UI sekarang memiliki tombol **SCAN ALL SF2** dan menampilkan hasil per file: validitas, ukuran, jumlah preset/instrument/sample, metadata, dan preset.
- Report inspector sekarang menyimpan inventory semua SF2 yang berhasil diinspeksi, terpisah dari daftar preset engine yang sedang loaded.
- Resolver/playback/CASM/parser/style sequencer tidak diubah pada tahap ini.

### File/commit

- `65563607b10d0a13781623c4d66f546298f52d09` — streaming SF2 metadata parser.
- `8c1cb1f5c0676fc2670165360bdfee0a7078604d` — multi-SF2 inspector state and sequential scan.
- `b83ae955799e08f94b4d71139d77084743562274` — Inspector UI dan report inventory semua SF2.

### Keputusan engineering

Tahap ini **belum mengubah Voice Resolver atau BASSMIDI font mapping**. Tujuannya adalah memperoleh inventory nyata dari semua SF2 yang tersedia terlebih dahulu.

Setelah inventory tersedia, langkah berikutnya:
1. bandingkan request Yamaha style (MSB:LSB + PC) terhadap seluruh preset dari semua SF2;
2. pisahkan role melodic vs drum secara eksplisit;
3. ukur coverage exact-match dan near-match;
4. baru desain fallback resolver yang tidak menukar Bass → Organ, Guitar → Piano, atau Drum → Flute.


## Tahap 6 — Audit coverage Voice Resolver Multi-SF2 — 2026-09-30

Setelah Inspector berhasil menginventarisasi seluruh managed SF2, dilakukan coverage audit terhadap corpus **569 factory style SX920**.

### Data yang dibandingkan

- 569 style.
- 7.612 voice-setup entries.
- 578 kombinasi unik **bank MSB:LSB + Program Change**.
- 7 SF2 managed.
- 1.624 preset terinspeksi.

SF2 yang terinventarisasi:
- ColomboGMGS2_BM.sf2 — 892 preset.
- DRUMKIT YAMAHA PSR-SX700 & SX900 PRIME.sf2 — 21 preset.
- MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2 — 93 preset.
- merlin_GMpro(v3.15).sf2 — 136 preset.
- Timbres Of Heaven GM_GS_XG_SFX V 3.4 Final.sf2 — 339 preset.
- yamaha tyros 4_just_t4_fixed.sf2 — 142 preset.
- Yamaha_PSR-SX700_CP80.sf2 — 1 preset.

### Hasil coverage

Aturan audit:
- exact family = MSB request + PC tersedia pada SF2;
- untuk rhythm MSB 126/127, diuji juga mapping eksplisit ke drum bank SF2 128;
- same-PC lintas bank hanya dianggap kandidat, **bukan** match aman.

Hasil dari 578 request unik:
- 250/578 memiliki kandidat same-bank/MSB + PC.
- 328/578 hanya memiliki kandidat same-PC di bank lain.
- 0/578 benar-benar tanpa PC yang sama di seluruh inventory.

Pada level semua 7.612 entry style:
- 4.512 entry memiliki kandidat same-bank/MSB + PC menurut aturan audit.
- 7.612/7.612 memiliki PC yang sama di suatu SF2, sehingga same-PC fallback secara statistik terlalu mudah lolos dan tidak cukup untuk menentukan suara.

### Temuan penting

**MSB 104 adalah gap terbesar.**
- 2.342 entry style menggunakan MSB 104.
- 282 request unik MSB 104.
- Tidak ada kandidat same-MSB + PC pada tujuh SF2 yang diinspeksi.

Artinya MSB 104 tidak boleh diselesaikan dengan fallback same-PC biasa. Resolver membutuhkan mapping variasi Yamaha/Tyros/semantic voice family.

**MSB 126/127 harus dipisahkan sebagai role rhythm/SFX.**
- Yamaha rhythm request tidak boleh bersaing dengan preset melodic yang kebetulan memiliki Program Change sama.
- Inventory drum Yamaha berisi 21 preset pada SF2 bank 128.
- Mapping 126/127 → drum bank 128 harus menjadi langkah resolver yang eksplisit, bukan efek samping fallback.

### Keputusan engineering

Jangan mengubah Voice Resolver hanya untuk mengejar angka coverage.

Tahap berikutnya adalah membuat **candidate matrix 578 request unik** dengan urutan:

1. role (DRUM vs MELODY/SFX);
2. Yamaha bank family MSB;
3. exact/variation candidate;
4. semantic voice-name family;
5. GM/XG fallback;
6. same-PC lintas bank hanya sebagai kandidat terakhir dan harus tunduk pada role.

Tujuan utamanya adalah menghilangkan kasus seperti:
- Bass → Organ;
- A.Guitar → Drum;
- Bright Piano → Strings;
- Drum → Flute.

Artefak:
- docs/SX920_SF2_VOICE_COVERAGE.md
- commit: 25337e745e399dc629c4187c2c02784b381331b3

**Status:** coverage audit selesai. Voice Resolver V2 belum diubah berdasarkan audit ini.


## Tahap 7 — Candidate Matrix + default SF2 selection guard — 2026-09-30

Tahap berikutnya dimulai pada branch `feat/voice-resolver-v2-multisf2` agar perubahan runtime Voice Resolver tidak mengganggu `main` sebelum build dan uji perangkat.

### Candidate matrix

Inventory 7 SF2 dipetakan terhadap 578 request unik dari 569 factory styles.

- 250/578 request memiliki kandidat exact-family berdasarkan bank SF2 yang dipetakan ke destination Yamaha.
- 328/578 hanya memiliki kandidat same-PC lintas bank.
- 578/578 memiliki minimal satu PC yang sama di inventory, sehingga same-PC tidak boleh dianggap sebagai bukti kecocokan suara.
- Candidate matrix lengkap: `docs/SX920_SF2_VOICE_CANDIDATE_MATRIX.md`.

Matrix menggunakan nama preset yang benar-benar ada di inspector. Karena export corpus 569 style tidak membawa nama voice untuk setiap request, matrix tidak mengarang semantic voice-name match untuk request yang tidak memiliki nama sumber.

### Temuan yang mengubah implementasi

- MSB 104: 282 request unik, 0 exact-family candidate dari 7 SF2. Ini tidak boleh diselesaikan dengan numeric same-PC fallback.
- MSB 126/127: role rhythm/SFX harus dipisahkan dari melodic.
- BASSMIDI 2.4.16 mendukung `BASS_MIDI_FONTEX2` dengan mapping per-channel. Dokumentasi resmi menyatakan konfigurasi dapat menentukan source preset/bank, destination program/bank/LSB, serta channel range. Ini memungkinkan resolver memilih SF2 berbeda per channel tanpa membuat bank global yang saling bertabrakan. 

### Perubahan awal yang aman

Sebelum runtime multi-SF2 penuh, auto-loader tidak lagi memilih "file non-drum pertama" dari folder.

Sekarang pemilihan default memberi prioritas:
1. MELODI Yamaha PSR-SX700/SX900 PRIME untuk melody;
2. DRUMKIT Yamaha PSR-SX700/SX900 PRIME untuk rhythm;
3. Tyros/Colombo/Timbres/Merlin/CP80 sebagai prioritas berikutnya.

Tujuannya menghindari folder-order memilih SF2 yang salah sebagai melody source.

Commit branch:
- `ec1ab42b88c1b091c5ef1fd913b59c10ef2036a5` — preferred Yamaha melody/drum SF2 selection.
- `81d8f5af8096445120d2f2779bbfc93a4e851e4f` — CI trigger untuk branch resolver v2.

### Rencana runtime Voice Resolver V2

Jangan langsung memuat semua 7 SF2. BASSMIDI memang mendukung stacking multiple soundfonts, tetapi total sample corpus cukup besar. Implementasi berikutnya harus memakai stack terkontrol dan mapping EX2/EX2 per-channel:

1. Yamaha PRIME sebagai sumber utama.
2. Drumkit Yamaha khusus rhythm.
3. Fallback melody terpilih (Tyros/Colombo) hanya sebagai lapisan tambahan yang terkontrol.
4. Exact bank/program mendapat prioritas.
5. Jika exact tidak ada, resolver memilih candidate berdasarkan role + semantic voice name bila nama tersedia.
6. Same-PC lintas bank hanya fallback paling akhir dan harus ditolak bila role/category bertentangan.

**Status:** candidate matrix selesai; default SF2 selection guard sudah di branch; runtime multi-SF2 resolver belum diubah.

## Tahap 8 — Voice Resolver V2: channel-specific FONTEX2 routing — 2026-09-30

Riset terhadap aplikasi arranger lain dan dokumentasi BASSMIDI mengubah desain runtime resolver.

### Acuan riset

- vArranger mendokumentasikan bahwa preset SF2 ditentukan oleh kombinasi BANK + PROGRAM dan duplicate BANK/PROGRAM harus dikendalikan agar tidak ambigu.
- vArranger juga memiliki soundbank khusus untuk format Yamaha, sehingga kompatibilitas style dan soundbank diperlakukan sebagai pasangan yang sengaja dirancang, bukan sekadar same-PC fallback.
- Dokumentasi resmi BASSMIDI BASS_MIDI_FONTEX2 mendukung source preset/bank (spreset, sbank), destination program/bank/LSB (dpreset, dbank, dbanklsb), dan pembatasan per channel (minchan, numchan).
- BASSMIDI juga menerapkan priority berdasarkan urutan mapping ketika beberapa soundfont menyediakan tujuan yang sama.

### Masalah runtime yang terbukti

Multi-SF2 sudah dapat dimuat, tetapi resolver lama hanya menyimpan pilihan source secara internal lalu tetap mengirim destination Yamaha. Tanpa mapping EX2 per-channel, SF2 primary dapat memenangkan tujuan yang sama walaupun resolver telah memilih fallback SF2.

Selain itu, same-PC lintas bank terbukti tidak aman:
- Bass -> Organ;
- A.Guitar -> Drum;
- Piano -> preset drum/kit;
- Piano -> Strings.

### Implementasi

Branch feat/voice-resolver-v2-multisf2 sekarang:

1. Menyimpan melodySourceBank dan melodySourceProgram pada state tiap channel.
2. Tidak lagi mengganti state.program dengan program source. Program destination Yamaha tetap dipertahankan.
3. Membuat mapping BASS_MIDI_FONTEX2 khusus untuk setiap channel yang sudah berhasil di-resolve: source SF2 + source bank/program; destination Yamaha MSB/LSB + program; minchan=channel, numchan=1.
4. Mapping channel-specific ditempatkan sebelum mapping generic sehingga pilihan resolver benar-benar menjadi source preset yang dipakai BASSMIDI.
5. Preload sekarang menggunakan source bank/program yang sudah dipilih resolver; tidak lagi mencari ulang berdasarkan same-PC yang bisa memilih preset berbeda.
6. Same-PC fallback dibatasi hanya jika kandidat masih berada dalam semantic instrument family yang sama.
7. Arbitrary first-preset fallback dihapus; final fallback hanya piano-family Program 0 atau gagal secara aman.

### Commit

- 0921e113fb48bac57ac7c9e8b1ceae4777bde212 — simpan source bank/program resolver per channel.
- eeda75fe7503adaa808276d3e28472672e99db95 — channel-specific FONTEX2 routing + semantic-safe fallback.
- 3a6c8d3af4a4789a503620a480b7c0c831da4686 — compile fix untuk handle BASS.

### Build

GitHub Actions Build APK run #729 untuk commit 3a6c8d3af4a4789a503620a480b7c0c831da4686 sudah terpicu pada branch ini. Pada checkpoint penulisan, setup sampai NDK/CMake/BASS/BASSMIDI/Gradle selesai dan step Build debug APK masih berjalan.

Jangan menganggap APK hijau sebelum run selesai dengan conclusion success.

### Target verifikasi perangkat

Inspector berikutnya harus memperlihatkan pola seperti:

- Bass -> source preset kategori Bass, bukan Organ;
- A.Guitar -> source preset kategori Guitar, bukan Drum;
- Bright Piano/E.Grand Piano -> source preset kategori Piano/EP, bukan Strings;
- Drum channel -> tetap hanya menggunakan drum font/role.

Regression utama:
- Love Song;
- Yamaha variation MSB 8/104;
- drum + melody bersamaan;
- reload SF2;
- switching Main/Fill tanpa suara hilang.


## Checkpoint audio Build 730 → Voice Resolver V3 — 2026-09-30

Build 730 (971c155e3eb3975a99bbb11130b36aac2836fc6e) sudah menjadi baseline uji audio yang menghasilkan suara drum, bass, piano, dan string pada style Love Song menurut pengujian perangkat pengguna.

Temuan penting:
- 7 SF2 tersedia di folder managed: Yamaha melody, Yamaha drumkit, ColomboGMGS2, Merlin, Timbres Of Heaven, Tyros 4, dan CP80.
- Inspector masih menampilkan bagian diagnostik Kotlin lama. Bagian tersebut bukan bukti bahwa native Voice Resolver V2 gagal.
- Native C++ sudah memiliki pemetaan channel-specific BASS_MIDI_FONTEX2 dan log VOICE MAP; jalur ini dipertahankan.
- Resolver native sudah memprioritaskan exact bank/program, MSB-only, semantic category/name, lalu fallback yang category-safe.
- Karena Build 730 sudah menghasilkan beberapa instrumen dengan benar, perubahan berikutnya tidak boleh mengganti routing yang sudah bekerja hanya untuk memperbaiki satu style.

### Target V3

Tujuan V3 adalah meningkatkan kelengkapan/karakter suara secara global, bukan membuat mapping khusus Love Song.

Urutan prinsip:
1. exact Yamaha bank/program;
2. normalized Yamaha bank;
3. semantic voice category + voice-name evidence;
4. program proximity sebagai tie-breaker;
5. category-safe fallback;
6. conservative fallback terakhir.

Kategori harus mencegah kesalahan seperti Bass→Organ, Guitar→Drum, Piano→Strings.

### Validasi perangkat berikutnya

Regression minimum:
- Love Song Main A/B/C/D;
- Intro → Main;
- Fill → Main;
- beberapa style dengan karakter berbeda;
- 2/4, 3/4, 4/4, 6/8;
- drum + melody bersamaan;
- Yamaha variation bank, terutama MSB 104;
- SF2 reload.

Build 730 diperlakukan sebagai audio baseline; jangan menyentuh CASM, scheduler, transition, atau drum routing tanpa bukti regresi.

## Checkpoint Video Mute-Per-Channel + Source Preload Fix — 2026-09-30

Pengujian video perangkat pengguna berdurasi sekitar 83 detik dilakukan dengan memute channel satu per satu pada Style Mixer. Video menunjukkan setiap part tetap dapat memengaruhi audio ketika channel lain diuji; tidak ada bukti dari video bahwa seluruh jalur channel mati. Label voice yang tampil juga tidak boleh dianggap sebagai bukti identitas sample yang benar.

Urutan channel yang diuji:
- CH8 Perc
- CH9 Drums
- CH10 Bass (UI saat ini menampilkan "B3 Org")
- CH11 Piano ("Yamaha ConcertGrand")
- CH12 A.Guitar ("Yamaha Bright Piano")
- CH13 Bright Piano ("String Yamaha")
- CH14 E.Grand Piano ("String Yamaha")

Temuan penting:
- CH10 memang secara UI diberi label "B3 Org", tetapi CH10 adalah destination Bass track. Audio video menunjukkan energi low/mid yang konsisten dengan adanya bagian bass; video saja belum cukup untuk membuktikan bahwa source SF2 yang dipilih native adalah Organ.
- Karena diagnostic Kotlin lama pernah menunjukkan mapping same-PC yang salah, bukti yang harus dipercaya untuk resolver adalah log native VOICE RESOLVE, VOICE MAP, SET PRESET, dan BASSMIDI preload.
- Dengan demikian, langkah berikutnya adalah membuat source voice native dapat diaudit langsung: source bank, source program, source role, dan source preset name harus tercatat pada channel state/log.

### Fix native setelah video

1. Menambahkan melodySourceName pada ChannelState agar nama preset source hasil resolver tidak hilang setelah findMelodicPreset.
2. Log VOICE MAP sekarang menyertakan srcName.
3. Log SET PRESET sekarang menyertakan sourceName.
4. Log preload sekarang menyertakan role, source program, source name, dan destination program.
5. Perbaikan penting: preloadCurrentPreset() sebelumnya memanggil BASS_MIDI_FontLoadEx() melodic menggunakan state.program (destination program), padahal resolver sudah memilih state.melodySourceProgram. Sekarang preload menggunakan sourceProgram yang benar-benar dipilih resolver. Ini menghindari preload preset berbeda dari source yang dipakai mapping.
6. Variabel presetCache yang tidak digunakan pada preload dihapus.

Commit:
- 0230a7708d15c12355ab28df9550c987269c746b — retain resolved source voice name in channel state.
- 1db6cc50486a6442d6bdad58928411cd38869a1d — preload resolved source preset and log source voice.

### Aturan verifikasi

Jangan mengubah mapping Bass→Bass secara paksa hanya berdasarkan label UI. APK berikutnya harus diuji dengan exported log dan dicari pasangan:
- VOICE RESOLVE ch=10 ... sourceRole=... sourceBank=... sourceProg=...
- VOICE MAP ch=10 ... srcBank=... srcProg=... srcName=... -> dstBank=8:4 dstProg=17
- BASSMIDI preload ch=10 ... srcProg=... srcName=... dstProg=17

Targetnya adalah membuktikan source preset aktual terlebih dahulu, baru melakukan perubahan resolver jika source category memang terbukti salah.
