# Blueprint Engine Arranger YamahaArranger

## Baseline resmi
Branch: main
Commit: 52e5b7ea6f8ee4b1166f999e198fea07c8573ab1

## 1. Jalur utama
Yamaha STY / SFF / SFF GE
↓
Parser MIDI + Yamaha Style
↓
Section + Part
↓
CASM
↓
StyleSequencer
↓
Master Musical Clock
↓
Note Ownership + CASM Transformer
↓
Voice Resolver
↓
BASSMIDI
↓
Oboe
↓
Android Audio

## 2. Aturan section
- Main → Main dapat menjadi Main → Fill → Main.
- Fill hanya satu kali.
- Intro hanya satu kali.
- Ending hanya satu kali.
- Pergantian section tidak boleh menghentikan engine.
- Pergantian section tidak boleh memanggil global all-notes-off.
- Hanya note style yang dimiliki section lama yang boleh direlease.
- Master musical clock tidak di-reset saat seamless transition.

## 3. Directional Fill
Main A → Main B berbeda dari Main B → Main A.
Jika style menyediakan FillAB dan FillBA, parser wajib mempertahankan keduanya. Jangan mengubah semua Fill menjadi FillAA/BB/CC/DD jika informasi arah tersedia.

## 4. Note Ownership
Setiap note style harus memiliki ID instance unik, misalnya sectionGeneration + track + channel + eventIndex.
Dengan begitu C5 ON, C5 ON, C5 OFF, C5 OFF tetap dapat diperlakukan sebagai dua instance note berbeda.

## 5. CASM
CASM bertanggung jawab atas source channel, destination channel, NTR, NTT, High Key, Note Limit, RTR, Bass-On dan chord mute.
CASM tidak memilih preset SF2.

## 6. Voice Resolver
Voice identity terdiri dari Bank MSB, Bank LSB, Program, Voice Name dan Category.
Urutan resolver:
1. Exact bank/program.
2. Explicit mapping.
3. Semantic name/category.
4. Family/category.
5. Numeric fallback yang masih semantically compatible.
6. Piano sebagai fallback melodic terakhir.
Drum tidak boleh jatuh ke preset melodic.

## 7. Audio realtime
Callback Oboe/BASSMIDI tidak boleh membaca file, scan SF2, melakukan pekerjaan berat, menunggu mutex UI/file, preload sample synchronous, atau melakukan preset resolution mahal.
Semua pekerjaan berat harus selesai sebelum note membutuhkan suara tersebut.

## 8. Sustain dan Release
Untuk Yamaha E343: CC64 = Sustain dan CC72 = Release Time.
Panel sustain tetap dipisahkan dari ACMP. ACMP/chord tidak boleh ikut ditahan hanya karena sustain keyboard aktif.
Release Time diterapkan pada RIGHT voices sesuai desain saat ini.

## 9. Multifunction Knob
Konsep:
Knob → Selected Parameter → Performance State → Subsystem

Contoh:
- Tempo → StyleSequencer
- Transpose → keyboard output
- Release → BASSMIDI RIGHT voices
- Volume → master/channel mixer
- Pan → mixer
- Reverb → mixer
- Chorus → mixer

Knob tidak memiliki logika audio sendiri.

## 10. Regression Matrix
| Skenario | Harapan |
|---|---|
| Main A → Main B | transisi mulus |
| Main B → Main D | Fill directional jika tersedia |
| Main D → Main A | tidak ada note mati prematur |
| Intro → Main | Intro satu kali |
| Fill → Main | tidak ada pause |
| Ending → Stop | Ending satu kali |
| repeated C5 | kedua instance punya NOTE_OFF sendiri |
| 2/4 | quantize benar |
| 3/4 | quantize benar |
| 4/4 | quantize benar |
| 6/8 | quantize benar |
| Drum + melody | keduanya berbunyi |
| Strings | tidak berubah menjadi Piano |
| Variation bank | MSB/LSB tetap benar |
| SF2 reload | routing lama tidak bocor |
| Sustain | hanya keyboard voice |
| Release | RIGHT voice berubah sesuai parameter |

## 11. Definisi selesai
- Tidak ada stop/restart untuk section transition.
- Tidak ada global all-notes-off saat transition.
- Repeated notes tidak saling menimpa.
- Intro/Ending deterministic.
- Directional Fill tidak hilang.
- CASM deterministic.
- Voice Resolver mencatat alasan fallback.
- Render callback tidak menunggu mutex operasi lambat.
- 2/4, 3/4, 4/4 dan 6/8 lolos test.
- Perilaku MIDI E343 tetap kompatibel.

## Prinsip utama
Jangan rewrite engine yang sudah bekerja.
Jika ada bug, tentukan batas yang gagal: Parser → CASM → Transition → Note Ownership → Voice Resolver → BASSMIDI State → Realtime Audio → UI State.
Baru ubah bagian yang memang bertanggung jawab atas bug tersebut.