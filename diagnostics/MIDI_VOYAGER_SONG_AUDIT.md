# MIDI Voyager vs YA SONG — audit suara + perbaikan V1A.1

Branch `feature/midi-song-v1`. Audit perilaku (help, string API, aset) dari APK MIDI Voyager Pro 5.4.11 yang diberikan pengguna
(SHA-256 `3c5582a0…464c0`, sama dengan `docs/MIDI_VOYAGER_PRO_5.4.11_AUDIO_RESEARCH.md`). **Kode dan aset Voyager tidak disalin**
(termasuk `8Rock11e.sf2`); YA hanya meniru perilaku yang terdokumentasi, dengan kode dan tes sendiri.

## 1. Laporan pengguna (Redmi Pad, V1A `81c7092`)

Song bisa diputar, tetapi: **piano bervibra**, **drum dan bass kurang terdengar**. Di MIDI Voyager file yang sama terdengar bagus,
hanya **string terdengar seperti di-sustain**.

## 2. Isi file uji (statistik saja; file milik pengguna, tidak di-commit)

SMF Type 0, PPQ 1920, rekaman keyboard Yamaha (XG):

| Ch | Isi | Penting |
|---|---|---|
| 1 | Piano, PC 0, bank 0/113 | **CC1 = 82**, CC64 |
| 2 | Bass, PC 33, bank 0/114 | CC7 95 |
| 3 | Strings, PC 49, bank 0/117 | CC1 = 82, **CC64 0/127**, CC5 |
| 9 | **Rhythm, bank MSB 127, PC 58**, 457 note | hanya drum lewat SysEx XG |
| 10 | Rhythm, bank MSB 127, PC 89, 1590 note | |

SysEx: 175 buah. 130 setup XG/GM: GM On, **XG System On**, **XG PART MODE** (part 9 = drum S2, part 10 = drum S1, part 7/8 = drum),
**XG MW LFO PMOD DEPTH = 0** untuk part 1 dan 3, efek, EQ part, drum setup. 45 non-synth: 44× `43 7E` (kontrol section style Yamaha),
1× `43 73` (privat instrumen).

## 3. Akar masalah (V1A)

V1A membuang **semua** SysEx (keputusan konservatif di audit TAHAP 0). Untuk file XG ini akibatnya:

1. **Piano bervibra:** CC1 = 82 masuk BASSMIDI, yang memakai modulator bawaan SF2 (CC1 → vibrato, sekitar 50 cent penuh; terukur di
   `RIGHT_PITCH_STABILITY_AUDIT.md`: CC1 127 → 32,6 c @ 8 Hz). File itu sendiri mematikan efek ini dengan XG *MW LFO PMOD DEPTH = 0*,
   tetapi pesan itu tidak pernah sampai ke synth.
2. **Drum hilang/lemah:** ch 9 hanya menjadi drum karena *XG System On* dan *XG PART MODE*. Tanpa itu, BASSMIDI memperlakukan ch 9
   sebagai melodi (bank 127 / PC 58 → bukan kit drum), sehingga 457 note Rhythm berbunyi sebagai nada melodi pendek, bukan drum.
3. **Bass terasa lemah:** sebagian besar persepsi (Rhythm ch 9 hilang). Kualitas resampling stream Song juga masih default
   (bukan sinc seperti stream utama).

**Kenapa Voyager bagus:** Voyager memutar file dengan `BASS_MIDI_StreamCreateFile`, sehingga BASSMIDI sendiri menjalankan SysEx
GS/XG. Help Voyager: *"If a midi file … contain[s] a SYSEX message 'XG System on', then the app will handle certain XG specifics
(e.g. fallback on bank 128 for drumkits …)"*.

**String "seperti di-sustain" di Voyager:** file mengirim CC64 (pedal) juga ke ch 3. Voice string SF2 menahan nada selama pedal
ditekan, sedangkan voice string Yamaha punya envelope sendiri. Ini perbedaan voice/SoundFont, bukan bug timing. Belum diubah
(lihat §6).

## 4. Perbaikan V1A.1 (yang diadaptasi dari perilaku Voyager)

| Perubahan | Detail | Batas keamanan |
|---|---|---|
| SysEx setup ke synth **internal** Song | Whitelist: GM On/Off `7E dd 09`, device control `7F dd 04`, Roland GS DT1 `41 dd 42 12`, Yamaha XG parameter `43 1n 4C`. Hanya pesan utuh `F0 … F7`, ≤ 256 byte. | **MIDI OUT tidak pernah menerima SysEx** (player tidak menawarkan ke output eksternal, dan output eksternal juga menolak). `43 7E`, `43 73`, pabrikan lain, paket terpisah, dan escape F7 dibuang. Dicek ulang di native (`songSysEx`). |
| Reset synth Song | GM System On ke stream Song sebelum lagu diputar dari awal dan sebelum chase (seek/resume), lalu SysEx setup sebelum posisi itu dikirim ulang. | Hanya `songStream_`; stream keyboard/Style tidak disentuh. Lagu baru tidak mewarisi setup XG lagu sebelumnya. |
| SRC stream Song | `BASS_ATTRIB_MIDI_SRC` = nilai stream utama (sinc). | Tidak mengubah stream utama. |

## 5. Bukti

| Tes | Hasil |
|---|---|
| CPV JVM (whitelist, urutan, hanya internal, replay saat seek, reset saat lagu baru) | OK, 361 tes |
| Native mock (`songSysEx` → stream Song saja, yang ditolak tidak terkirim, `songEvents` tetap menolak SysEx) | PASS |
| **BASSMIDI asli** `tests/song_xg_real_bass_test.cpp` (CI: SF2 sintetis, stream sama seperti app) | lihat laporan CI. Klaimnya: tanpa SysEx CC1=82 bervibra dan ch 9 bukan drum; dengan SysEx XG dari file, vibrato hilang dan ch 9 menjadi drum. |

Belum terbukti: suara dengan SoundFont asli Bos di Redmi Pad, dan kit Yamaha PC 58/89 (diganti kit drum yang ada di SF2).

## 6. Usulan berikutnya (belum dikerjakan, menunggu keputusan)

1. **Pedal/sustain per channel** di mixer SONG (misalnya matikan CC64 di string), untuk kasus "string di-sustain".
2. **Voice resolver Yamaha untuk Song**: bank 0/113, 0/114, 0/117 dan kit 127/58, 127/89 di-resolve seperti voice Style
   (multi-SF2, Yamaha SX/Tyros/Colombo) dan bukan raw GM. Ini perubahan besar; perlu tes perangkat V1A.1 dulu.
3. Pilihan "default drum kit" seperti Voyager.
