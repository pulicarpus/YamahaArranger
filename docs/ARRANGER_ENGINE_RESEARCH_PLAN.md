# YamahaArranger — Arranger Engine Research & Work Plan

Date: 2026-09-29
Branch: `research/giglad-arranger-engine-adaptation`

> **Status: RESEARCH ONLY.**
> Dokumen ini adalah blueprint sebelum perubahan kode. Jangan implementasi hanya karena ada poin di sini; setiap fase harus diverifikasi dengan log/build/APK dan regresi audio.

---

## 1. Tujuan

Membuat YamahaArranger berperilaku seperti arranger hardware Yamaha dalam hal:

- continuous musical timing;
- Main A/B/C/D;
- Fill AA/BB/CC/DD dan Break;
- Intro/Ending;
- Auto Fill dan successor;
- chord recognition dan chord changes;
- CASM/NTR/NTT/RTR;
- per-note ownership dan NOTE_OFF yang aman;
- drum/percussion yang tidak ikut transposisi chord;
- Yamaha bank MSB/LSB/Program;
- SF2/BASSMIDI tanpa reload/blocking pada pergantian section;
- sustain/hold tanpa hanging note;
- 2/4, 3/4, 4/4, 6/8 dan time signature lain;
- transisi tanpa gap/pause yang terdengar.

**Prinsip utama:** jangan mengejar "suara keluar" dengan menambal NOTE_OFF/scheduler secara lokal. Arranger harus dipandang sebagai pipeline musikal lengkap.

---

## 2. Hasil riset lintas aplikasi

### 2.1 GigLad — referensi utama untuk perilaku arranger

Dokumentasi dan release history GigLad menunjukkan pemisahan yang kuat antara:

1. master musical timeline;
2. section/successor;
3. chord-switch policy;
4. style-switch policy;
5. CTA/CASM;
6. MIDI engine;
7. audio engine.

Perilaku penting:

- section mempunyai successor;
- section normal dapat berpindah pada beat;
- Fill dapat berpindah dengan presisi lebih kecil dari satu beat;
- chord switch mempunyai mode Soft Retrigger, Hard Retrigger, dan Stop;
- style switch mempunyai Retrigger atau Stop;
- Retrigger pada section baru menghitung ulang note yang seharusnya sedang aktif pada posisi tersebut;
- percussion dapat memakai bypass sehingga tidak ikut chord transposition;
- cross-boundary NOTE_ON/NOTE_OFF perlu diamankan;
- MIDI dan audio engine tidak boleh saling memblokir;
- release history menunjukkan bug nyata yang sama dengan masalah kita: audio pause, note hanging, sustain hanging, section-switch overload, chord terlambat, instrument berhenti, wrong Yamaha bass mapping, crackle, multiple time signatures, dan Auto Fill/successor.

**Kesimpulan:** arsitektur GigLad sangat berguna sebagai model perilaku, tetapi bukan kode untuk disalin.

### 2.2 vArranger — referensi routing dan kontrol live

vArranger mendukung Yamaha SFF1/SFF2 dan banyak format arranger lain.

Hal yang relevan:

- real-time chord recognition;
- Auto Fill;
- Sync Start/Stop;
- Hold;
- Manual Bass;
- Bass to Lowest;
- Transpose/Octave;
- routing setiap track ke beberapa output;
- SoundFont/SFZ;
- kontrol fungsi arranger dari note/button/fader/knob;
- mixer dan instrument definition.

**Kesimpulan:** track routing harus dianggap sebagai layer tersendiri dari style/CASM, dan fungsi arranger sebaiknya bisa dipetakan ke knob/controller.

### 2.3 One Man Band — referensi seamless loading dan controller

OMB mendokumentasikan:

- accompaniment tetap berjalan ketika style baru dimuat;
- style switch tanpa glitch;
- empat melody instruments;
- variasi/fill/tempo/volume/style dapat dikontrol dari key, slider, knob, wheel atau pedal;
- tempo dapat dikontrol controller;
- chord recognition luas.

**Kesimpulan:** resource loading tidak boleh menghentikan musical transport. UI multifunction knob yang kita rencanakan juga sejalan dengan pola arranger/controller nyata.

### 2.4 Android Arranger Keyboard — referensi paling dekat dengan target Android

Fitur yang relevan:

- Yamaha STY/SFF2;
- SF2/SF3;
- Main A-D;
- Fill;
- Intro/Ending;
- Bass/Chord/Pad/Phrase;
- USB/Bluetooth MIDI;
- Sync Start;
- perkembangan khusus untuk Main A-D transition.

**Kesimpulan:** masalah transisi Main A-D memang merupakan area nyata pada arranger Android, bukan sekadar masalah YamahaArranger.

### 2.5 Android MIDI Arranger — baseline Android

Mendukung:

- Yamaha Style Format 1;
- chord recognition;
- internal sound bank/SoundFont;
- MIDI CC untuk switch/knob/slider;
- registrations;
- patch MSB/LSB/Program.

Namun dokumentasinya juga mengakui:

- beberapa Yamaha Style features tidak terimpor;
- Format 2 perlu konversi;
- Pitch Bend pada style belum didukung;
- perangkat Android lama dapat mempunyai latency tinggi.

**Kesimpulan:** kompatibilitas Yamaha penuh membutuhkan parser + CASM + arranger engine, bukan hanya MIDI playback.

### 2.6 Korg Pa arranger — validasi lintas vendor

Korg menggunakan:

- Style Element;
- Chord Variation;
- NTT;
- transposition rules;
- track-specific behavior.

Drum/Percussion tidak menggunakan NTT seperti track melodis.

**Kesimpulan:** pemisahan percussion bypass dan melodic transposition adalah pola arranger umum, bukan kekhususan Yamaha.

---

## 3. Yamaha SFF/CASM — model yang harus kita pertahankan

Dokumentasi Yamaha menjelaskan alur:

**Source Root + Source Chord**
→ **NTR**
→ **NTT**
→ **High Key**
→ **Note Limit**
→ **RTR**
→ **actual sounded notes**

Artinya CASM bukan sekadar metadata. CASM menentukan bagaimana pola sumber berubah ketika chord berubah.

### Channel role standar

Kita harus mempertahankan konsep:

- Rhythm Sub
- Rhythm Main
- Bass
- Chord 1
- Chord 2
- Pad
- Phrase 1
- Phrase 2

### Parameter utama

- NTR: Root Trans / Root Fixed / Guitar / Bypass
- NTT: Bypass / Melody / Chord / Bass / minor variants
- High Key
- Note Limit Low/High
- RTR: Stop / Pitch Shift / Pitch Shift to Root / Retrigger / Retrigger to Root / Note Generator

### Aturan keras

**Drum/Percussion:**
- NTR = Bypass
- NTT = Bypass
- jangan ditranspose mengikuti chord.

**Bass/Chord/Pad/Phrase:**
- transform berdasarkan CASM track tersebut;
- jangan gunakan satu algoritma transposisi global untuk semua track.

---

## 4. Section model

Jangan menganggap Fill sebagai Main yang diputar sekali.

Gunakan konsep:

```
PLAYING(Main A)
   |
   | user requests Fill B
   v
REQUESTED
   |
   | wait until legal switch point
   v
FILL B
   |
   | successor = Main B
   v
MAIN B
```

Intro/Ending juga harus menjadi state/section dengan successor yang jelas.

### Candidate section graph

- Main A -> Main A
- Main B -> Main B
- Main C -> Main C
- Main D -> Main D
- Fill AA -> Main A
- Fill BB -> Main B
- Fill CC -> Main C
- Fill DD -> Main D
- Break -> configured successor
- Intro -> configured Main
- Ending -> STOP

Jangan menganggap semua style selalu mengikuti graph di atas. Successor harus berasal dari style/user action bila tersedia.

---

## 5. Timing model

Harus ada satu **master musical clock**.

Tidak boleh:

- reset clock ketika Main berubah;
- menghentikan scheduler lalu membuat scheduler baru;
- menunggu audio resource loading sebelum melanjutkan clock;
- menghitung posisi section baru dari wall-clock yang berbeda.

### Konsep

```
masterTimelineTick
        |
        +-- section position
        +-- beat
        +-- bar
        +-- MIDI event time
        +-- transition decision
        +-- chord change
```

Semua event harus dapat direkonstruksi dari timeline yang sama.

### Precision policy

Minimal:

- BAR
- BEAT
- ANY_BEAT

Normal Main:
- default ke beat/bar yang sesuai perilaku style.

Fill/Break:
- dapat memerlukan precision lebih halus.

---

## 6. Chord switch ≠ Section switch

Ini salah satu keputusan arsitektur terpenting.

### Chord switch

Mode yang perlu dipahami:

- SOFT_RETRIGGER
- HARD_RETRIGGER
- STOP
- BYPASS

Soft:
- note yang masih valid dipertahankan;
- hanya note yang berubah diretrigger.

Hard:
- note lama dimatikan;
- pattern chord baru dimainkan.

Stop:
- note yang tidak lagi valid dihentikan;
- common note dapat dipertahankan.

### Section switch

Mode berbeda:

- RETRIGGER
- STOP

Retrigger:
- note dari section lama dilepas;
- engine menghitung note yang seharusnya sedang aktif pada posisi target;
- hanya note yang memang seharusnya aktif dikirim.

Stop:
- section lama berhenti;
- tidak memaksa replay sampai NOTE_ON berikutnya.

**Jangan gabungkan dua keputusan ini menjadi satu global allNotesOff.**

---

## 7. Note ownership

Setiap note style harus dapat dilacak minimal berdasarkan:

- section;
- source track/channel;
- source note;
- transformed/destination note;
- destination MIDI channel;
- generation/instance;
- optional chord context.

Contoh konsep:

```
StyleNoteOwner {
    sectionId
    sourceTrack
    sourceChannel
    sourceNote
    destinationChannel
    destinationNote
    generation
}
```

Ketika Fill -> Main terjadi:

- release hanya ownership milik Fill;
- jangan mematikan Right Voice;
- jangan mematikan Left;
- jangan mematikan drum yang berasal dari channel lain;
- jangan mematikan note dari target section yang baru saja aktif.

---

## 8. Cross-boundary event safety

Kasus berbahaya:

```
Section A:
  NOTE_ON(t=3.75)

Section B:
  NOTE_OFF(t=4.25)
```

Jika section A dihentikan tepat di boundary, NOTE_OFF dari A dapat hilang atau salah diarahkan.

Kita perlu invariant:

> Setiap NOTE_ON yang dikeluarkan oleh arranger memiliki owner dan jalur release yang deterministik.

Pilihan:

1. NOTE_OFF normal sebelum boundary;
2. safety NOTE_OFF di boundary;
3. ownership dipindahkan secara eksplisit jika note memang harus survive.

Tidak boleh ada note yang "kebetulan" hidup karena NOTE_OFF berasal dari clip lama.

---

## 9. Drum path

Dari riset dan log YamahaArranger:

- CASM/style sudah mengirim NOTE_ON drum berulang;
- channel 9 aktif;
- contoh note: 36, 62, 64, 61, 40;
- Rhythm2 menggunakan program 88, MSB 127, LSB 0.

Maka debugging drum harus dipisahkan menjadi:

```
STYLE EVENT
   ↓
CASM
   ↓
DRUM BYPASS
   ↓
BANK/PROGRAM
   ↓
BASSMIDI CHANNEL
   ↓
SF2 PRESET
   ↓
AUDIO
```

Jangan kembali memperbaiki CASM scheduler jika log sudah membuktikan NOTE_ON drum keluar.

---

## 10. Voice / bank pipeline

Pipeline yang diinginkan:

```
Style Program Change
       ↓
Yamaha MSB/LSB/PGM
       ↓
Bank normalization
       ↓
SF2 preset lookup
       ↓
BASSMIDI channel state
       ↓
Audio
```

Tidak boleh mengasumsikan:

```
Program 88 = GM instrument 88
```

karena Yamaha menggunakan bank extension dan drum mapping.

User mixer override juga harus dipisahkan dari event style supaya style CC/program tidak diam-diam mengembalikan setting user.

---

## 11. Audio engine boundary

Arranger scheduler boleh menghasilkan MIDI events, tetapi tidak boleh melakukan operasi berat/blocking di audio callback.

Hindari saat transition:

- load SF2;
- reopen audio stream;
- instantiate instrument;
- filesystem I/O;
- blocking mutex;
- parser besar;
- style reload penuh.

Target:

```
PRELOAD / CACHE
       ↓
transition decision
       ↓
send MIDI
       ↓
audio engine tetap berjalan
```

BASSMIDI tetap dipertahankan. Kita tidak mengganti engine hanya karena referensi lain menggunakan FluidSynth/native sampler.

---

## 12. Multifunction knob

Riset OMB/vArranger/GigLad menunjukkan arranger umum memang memetakan fungsi ke knob/controller.

Knob besar yang sedang direncanakan dapat memakai konsep:

- current target = Tempo → encoder naik/turun tempo;
- current target = Transpose → transpose;
- current target = Release → release;
- current target = Volume;
- current target = Voice/Bank;
- current target = Reverb/Chorus;
- current target = Style parameter lain.

Prinsip UI:

**satu knob fisik/virtual + target selector**, bukan banyak knob kecil yang memenuhi layar.

Nilai perubahan harus dikontrol dengan:

- min/max;
- step;
- acceleration bila diperlukan;
- display nilai besar;
- feedback langsung.

---

## 13. Masalah yang sengaja TIDAK disentuh dulu

Sampai blueprint arranger selesai, jangan mengubah:

- UI yang sudah stabil;
- sustain yang sudah diperbaiki;
- BASSMIDI routing yang sudah bekerja;
- native audio tanpa bukti dari log;
- voice selector tanpa reproducer;
- drum CASM jika log membuktikan event sudah keluar.

Setiap perubahan harus punya:

1. reproducer;
2. hypothesis;
3. log yang membuktikan hypothesis;
4. patch kecil;
5. build;
6. test APK;
7. regression check.

---

## 14. Tahapan kerja yang disepakati

### Phase 0 — Baseline / freeze
- Jangan ubah behavior.
- Capture build/APK baseline.
- Catat style test:
  - LoveSong;
  - style 3/4;
  - 2/4;
  - 4/4;
  - 6/8.
- Capture MIDI/event log.

### Phase 1 — Model section
- dokumentasikan section ID;
- successor;
- transition request;
- transition precision;
- Fill/Break semantics.

**Belum mengubah audio engine.**

### Phase 2 — Master timeline
- pastikan satu monotonic musical clock;
- section transition tidak reset clock;
- chord event memakai timeline yang sama.

### Phase 3 — Note ownership
- ownership per note;
- targeted NOTE_OFF;
- safety boundary release;
- regression untuk hanging notes.

### Phase 4 — CASM
- source root/chord;
- NTR;
- NTT;
- High Key;
- Note Limit;
- RTR;
- drum bypass.

### Phase 5 — Chord/section transition policy
- Soft/Hard/Stop chord behavior;
- Retrigger/Stop section behavior;
- Fill -> successor;
- Intro -> Main;
- Main -> Fill -> Main;
- Main D -> Fill -> Main A.

### Phase 6 — Bank/voice/audio boundary
- verify Yamaha MSB/LSB/PGM;
- verify drum/melody separation;
- verify SF2 preset lookup;
- verify no reload/blocking on transition.

### Phase 7 — Time signature
Minimum regression:

| Meter | Main | Fill | Chord change | Sustain |
|---|---|---|---|---|
| 2/4 | test | test | test | test |
| 3/4 | test | test | test | test |
| 4/4 | test | test | test | test |
| 6/8 | test | test | test | test |

### Phase 8 — UI/controller
- multifunction knob;
- target selector;
- tempo/transpose/release/etc;
- MIDI controller mapping;
- no regression to arranger timing.

### Phase 9 — APK validation
Minimum manual scenarios:

1. Main A -> Main B
2. Main A -> Fill -> Main A
3. Main A -> Fill B -> Main B
4. Main D -> Fill -> Main A
5. Intro -> Main A
6. Main -> Ending
7. chord change during Fill
8. chord change at boundary
9. sustain during Main change
10. sustain during Fill
11. drum + melody simultaneously
12. SF2 bank switch
13. style switch while playing
14. 2/4
15. 3/4
16. 4/4
17. 6/8

---

## 15. Acceptance criteria

Sebuah perubahan arranger dianggap berhasil hanya jika:

- tidak ada audible gap/pause pada transition;
- tidak ada hanging note;
- tidak ada accidental global NOTE_OFF;
- drum tetap berbunyi ketika melody berubah;
- melody tidak mematikan drum;
- bass mengikuti chord sesuai CASM;
- phrase/chord/pad mengikuti aturan CASM masing-masing;
- Main/Fill tidak kehilangan beat;
- Fill mempunyai successor yang benar;
- Intro/Ending tidak masuk loop salah;
- tempo/timeline tidak reset secara tidak semestinya;
- sustain tetap benar;
- SF2 tidak reload pada setiap transition;
- tidak ada regression pada voice yang sudah bekerja.

---

## 16. Referensi utama

- GigLad documentation: https://deltarray.com/documentation/giglad/
- GigLad release history: https://deltarray.com/releases_history
- vArranger features: https://www.varranger.com/features/
- One Man Band features: https://www.1manband.nl/features.htm
- One Man Band live arranger: https://www.1manband.nl/omb/live.htm
- Android MIDI Arranger: https://android-midi-arranger.com/
- Android MIDI Arranger FAQ: https://android-midi-arranger.com/faq
- Android MIDI Arranger known issues: https://android-midi-arranger.com/known-issues
- Yamaha Style File Format reference: Yamaha PSR reference manuals
- Yamaha Style Studio / SFF2 research: https://github.com/jozphe/Yamaha-Style-Studio
- Korg Pa documentation: official Korg Pa manuals

---

## 17. Golden rule

> **Jangan memperbaiki arranger dengan menambal gejala audio.**
>
> Pertama buktikan event timeline → section → CASM → ownership → MIDI routing → instrument → audio.
>
> Kalau satu layer terbukti benar dari log, jangan mengulang perubahan di layer itu. Cari layer berikutnya.

This document is a research/work plan only. Code changes require a separate implementation step and explicit verification.
