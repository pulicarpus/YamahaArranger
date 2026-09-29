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

### P0.1 Voice Resolver

File: `app/src/main/cpp/bassmidi_player.cpp`

Urutan sekarang masih dapat mengutamakan program numerik yang sama sebelum semantic category.

Gejala:
Strings dapat menjadi Piano/E.Piano, bukan preset String.

Target:
exact bank/program → explicit mapping → semantic category → family match → compatible numeric fallback → Piano sebagai fallback terakhir.

Jangan mengubah CASM untuk memperbaiki ini.

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
