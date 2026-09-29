# YamahaArranger

Aplikasi Android arranger keyboard bergaya Yamaha.

## Baseline resmi saat ini

- Branch utama: `main`
- Commit baseline engine/dokumentasi: `52e5b7ea6f8ee4b1166f999e198fea07c8573ab1`
- Repository: `pulicarpus/YamahaArranger`
- Audit engine dan blueprint tersedia di folder `docs/`.

**Catatan:** dokumentasi lama yang masih menyebut branch `fix/yamaha-panel-sustain-clean` atau commit audit lama tidak lagi menjadi acuan. Baseline resmi sekarang adalah `main`.

## Arsitektur

### Jalur style

Yamaha STY / PRS / SFF / SFF GE
→ parser SMF/Yamaha native
→ model section + part
→ kebijakan CASM
→ StyleSequencer
→ BASSMIDI
→ Oboe
→ audio Android

### Jalur MIDI/keyboard

Android MIDI / Yamaha E343
→ MidiInputManager
→ deteksi chord / routing keyboard
→ ArrangerBrain
→ StyleSequencer + AudioEngineManager
→ BASSMIDI / Oboe

## Kemampuan engine saat ini

### Style Yamaha

- Intro A/B/C
- Main A/B/C/D
- Fill AA/BB/CC/DD
- konsep Fill directional di ArrangerBrain; representasi native lengkap masih perlu diperbaiki
- Ending A/B/C
- master musical clock kontinu
- antrean perpindahan section seamless
- Auto Fill
- kebijakan CASM
- NTR / NTT / RTR
- High Key dan Note Limit
- Bass-On
- state mixer/controller style
- override channel style
- model meter 2/4, 3/4, 4/4 dan 6/8

Struktur arranger Yamaha yang menjadi acuan meliputi Intro I-III, Main A-D, Fill In A-D, Break, Ending I-III, serta part Rhythm 1-2, Bass, Chord 1-2, Pad dan Phrase 1-2.

## Audio engine

**BASSMIDI adalah jalur SoundFont aktif saat ini.**

Fitur utama:

- BASS + BASSMIDI
- BASS_MIDI_NOTEOFF1
- hingga 1000 MIDI voices yang dikonfigurasi
- PPQN 1920
- kualitas SRC yang dapat dikonfigurasi
- preservasi Bank MSB/LSB Yamaha
- normalisasi Yamaha variation bank
- role SoundFont melody dan drum yang terpisah
- mode satu SF2 untuk melody + drum
- BASSMIDI FONTEX2 mapping
- preload sample asynchronous
- enumerasi preset
- resolver preset berdasarkan nama voice
- volume/pan/expression/reverb/chorus per channel
- master gain
- panel sustain
- release time bergaya Yamaha untuk RIGHT voices

FluidSynth masih ada sebagai dependency legacy, tetapi bukan jalur SoundFont aktif yang menjadi acuan.

## Masalah prioritas yang sudah diketahui

Detail lengkap ada di `docs/ARRANGER_ENGINE_AUDIT.md`.

1. Urutan Voice Resolver masih dapat memilih program numerik yang sama sebelum kecocokan semantic String/category.
2. `render()` BASSMIDI dan operasi kontrol masih berbagi mutex sehingga ada risiko contention pada audio realtime.
3. Kepemilikan note style masih memakai source-channel/source-note, bukan identitas event yang unik.
4. Fill directional di ArrangerBrain lebih lengkap daripada model StyleSection native.
5. Intro/Ending yang dipilih ketika idle belum dijamin menjadi urutan one-shot yang benar.
6. Timing transisi masih menggabungkan quantization wall-clock dengan scheduler coroutine/master tick.
7. Regression test untuk transisi, repeated notes, meter dan voice resolution masih belum lengkap.

Ini adalah target stabilisasi. **Engine tidak perlu ditulis ulang dari nol.**

## Sustain dan Release

Desain saat ini memisahkan:

- sustain panel untuk keyboard voices;
- note ACMP/chord;
- lifecycle note style;
- release time.

Untuk Yamaha E343, acuan MIDI yang digunakan adalah:

- CC64 = Sustain
- CC72 = Release Time

Sustain keyboard tidak boleh ikut menahan note ACMP/style.

## Alat diagnostik

- SF2/style inspector
- String/CASM trace
- DebugLog export
- log Voice Resolver native BASSMIDI
- enumerasi preset SF2
- dump marker style/CASM

Catatan riset MIDI Voyager:

`docs/MIDI_VOYAGER_PRO_5.4.11_AUDIO_RESEARCH.md`

## Audit dan blueprint

Sebelum mengubah engine, baca:

- `docs/ARRANGER_ENGINE_AUDIT.md`
- `docs/ARRANGER_ENGINE_BLUEPRINT.md`
- `docs/MIDI_VOYAGER_PRO_5.4.11_AUDIO_RESEARCH.md`

Audit membandingkan implementasi dengan:

- GigLad
- vArranger
- One Man Band
- Android Arranger Keyboard
- MIDI Voyager Pro
- perilaku arranger/SFF Yamaha

## Regression yang wajib diperiksa

Sebelum perubahan engine dianggap aman:

- Main A → Main B
- Main B → Main D
- Main D → Main A
- Intro → Main
- Fill → Main
- Ending → Stop
- repeated identical notes
- drum + melody bersamaan
- Strings voice resolution
- Yamaha variation bank
- 2/4
- 3/4
- 4/4
- 6/8
- reload SoundFont
- sustain ON/OFF
- perubahan release time

## Aturan pengembangan

Jangan mengganti subsystem yang sudah bekerja secara membabi buta.

Jika ada bug, tentukan batas yang gagal:

1. parser
2. CASM
3. transition scheduler
4. note ownership
5. Voice Resolver
6. state BASSMIDI
7. realtime audio
8. UI/performance state

Kemudian ubah hanya batas yang bertanggung jawab dan tambahkan regression test.

## Build

Karena proyek memiliki native C++/NDK, build dilakukan melalui GitHub Actions dalam workflow Android-only.

Workflow build:

`.github/workflows/build.yml`

Workflow inspeksi SF2:

`.github/workflows/inspect-sf2.yml`
