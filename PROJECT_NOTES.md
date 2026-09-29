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
