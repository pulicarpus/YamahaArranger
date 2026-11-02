# SONG MIXER V1A.2 — Voice / Mixer per channel untuk MIDI Song Player

- **Branch:** `feature/midi-song-v1` (tidak di-merge ke `main`)
- **Baseline:** `3152712` (V1A.1: SysEx XG/GS/GM ke synth Song, peta font `dbank 0`, SRC sinc; Quick run #5 hijau)
- **Commit implementasi:** lihat `git log 3152712..HEAD` (empat commit: inti, native+integrasi, UI, CI+laporan)
- **Status perangkat:** dites di host/CI saja. **Belum diuji di Redmi Pad atau PSR-E343.**
- **File uji pengguna:** `HANYA-DIDALAM-NAMA-YESUS.MID` (rekaman Yamaha PSR-S710, XG). File tidak di-commit; tes memakai file sintetis
  dengan pola yang sama (string ch3 0/117/49 + CC64, drum ch10 bank 127, piano ch1, Program Change di tengah lagu, XG System On).

## 1. Tahap 0 — audit

| Bagian | Temuan | Keputusan |
|---|---|---|
| Song stream | `BassMidiPlayer::songStream_`, peta FONTEX2 dibuat ulang di `ensureSongStreamLocked` | Pin voice per channel ditaruh **pertama** di peta stream Song saja |
| Scheduler | `SongPlayer.dispatchLocked` / `chaseLocked` / `resetSynthLocked` | Satu titik transformasi per output: `SongMixerRules.transform` |
| SoundFont | Inventaris `browserPresets_` (fontId, bank, program, nama, handle) sudah ada untuk Voice Selector keyboard | Dipakai ulang read-only (`keyboardPreset()` lookup); tidak ada I/O SF2 baru |
| Voice resolver Style | `findMelodicPreset` / `applyFonts` (stream utama) | **Tidak disentuh.** Guard memastikan `applyFonts` tidak pernah melihat pin Song |
| UI | `SongPanel` (portable) + `SongDialog` (polling 100 ms) | Tab CHANNELS / MIXER + Voice Selector overlay, file portable baru `SongMixerPanel.kt` |
| Pekerjaan paralel | `origin/feature/midi-song-v1` = HEAD lokal `3152712`, tidak ada commit lain | aman |

## 2. Arsitektur

```
 file event ──► SongPlayer.dispatchLocked
                 │  noteFileStateLocked: simpan nilai ASLI file (bank/PC/CC7/CC10/CC64) → tampilan + RESET CHANNEL
                 ▼
            SongMixerRules.transform(msg, override[ch], internal?)      (per output, murni, dites JVM)
                 │  voice (INTERNAL saja): PC file → Bank 0 / LSB 0 / PC 0 (melodi) atau PC 0 (kit; bank file dibiarkan = mode drum XG)
                 │                         Bank file → dibuang (melodi)
                 │  volume / pan (semua output): CC7 / CC10 file → nilai user
                 │  sustain (semua output): FOLLOW = CC64 apa adanya, IGNORE = CC64 dibuang, FORCE OFF = CC64 → 0
                 ▼
        ┌── INTERNAL: songStream_ ◄── pin FONTEX2 per channel (songSetChannelVoice): preset SF2 pilihan → (bank 0|128, PC 0), 1 channel
        └── MIDI OUT: voice file tetap (SF2 tidak ada di modul luar); volume/pan/sustain user berlaku

 Setelah GM reset (awal lagu), setiap SysEx setup (mis. XG System On) dan setiap chase (seek / resume / output baru):
   applyOverridesLocked → kirim ulang seluruh override, jadi reset/chase tidak pernah menghapus pilihan user.
 MUTE / SOLO: channel "silent" = mute atau ada channel lain yang solo → note Song channel itu diakhiri (NOTE OFF per note, tanpa
   All Notes Off), note baru tidak dikirim; controller tetap jalan.
 Per lagu: override disimpan per judul selama aplikasi berjalan; lagu lain mulai bersih, membuka lagi lagu yang sama memulihkannya.
 Saat STOP: perubahan mixer hanya ke stream Song internal (modul eksternal tidak disentuh sampai PLAY).
```

## 3. Fitur yang benar-benar bekerja (terverifikasi tes)

| Fitur | Bukti |
|---|---|
| Ganti voice per channel dari SF2 yang dimuat (melodi) | JVM `voiceOverrideReachesOnlyTheInternalSynth…`; native mock `SONG MIXER NATIVE PASS`; BASSMIDI asli: pin CH3 → preset 58, CH1 tetap |
| Ganti drum kit di channel drum | JVM `drumKitOverride…`; BASSMIDI asli: CH9 (XG drum part) → kit 25 |
| PC/Bank file tidak menimpa voice user (termasuk PC di tengah lagu) | JVM |
| MIDI OUT tetap voice file | JVM |
| Volume / Pan benar-benar mengubah audio | JVM (CC7/CC10 diganti di semua output); BASSMIDI asli: CC7 30 vs 127 dan CC10 0 kiri/kanan (angka di CI) |
| Sustain FOLLOW / IGNORE / FORCE OFF, NOTE OFF tetap diproses, tanpa All Notes/Sound Off | JVM `sustainModes…`; BASSMIDI asli: FOLLOW menahan nada setelah NOTE OFF, IGNORE/FORCE OFF berhenti di NOTE OFF |
| Mute / Solo | JVM `soloSilencesOtherChannels…` |
| Bertahan saat seek maju/mundur, pause/resume, ulang lagu, XG System On di file | JVM `overridesSurvive…` |
| RESET CHANNEL kembali ke nilai file | JVM `resetChannelRestores…` |
| Ganti file MIDI: lagu lain bersih, buka lagi lagu sama = override kembali | JVM |
| Preset tidak ditemukan → voice file dipakai, status `NOT_FOUND` tampil | JVM + native |
| UI MIXER di 1333×800, 1280×800, 1000×600 (tombol ≥ 48 dp) dan menggerakkan player asli | desktop Compose `SongPanelTest` (CI) |

## 4. Hasil tes

Lihat laporan chat dan run CI untuk commit terakhir. Lokal: CPV JVM, native mock Song, guard, dan regresi native/rtdiag (dicantumkan di chat).

## 5. Risiko / keterbatasan

1. **Belum dites di Redmi Pad.** Kualitas dan keras suara preset pilihan tergantung SF2 Bos.
2. Override voice hanya untuk synth INTERNAL. Untuk MIDI OUT, PSR-E343 tetap memakai voice dari file.
3. Kit hanya bisa dipilih di channel yang dimainkan sebagai drum oleh file (ch10, bank 126/127, atau XG PART MODE). Channel melodi
   tidak bisa diubah menjadi drum (dan sebaliknya) di V1A.2.
4. Override diingat per judul lagu **selama aplikasi berjalan** (belum disimpan ke penyimpanan).
5. Volume/pan user mengganti CC7/CC10. Ekspresi (CC11) dan volume/pan dari SysEx XG part ditimpa ulang setelah setiap SysEx setup;
   CC11 file tetap berlaku.
6. Preset baru dimuat sampelnya secara asinkron (`FONTLOAD_NOWAIT`); note pertama setelah ganti voice bisa sedikit terlambat.

## 6. Langkah tes pengguna (Redmi Pad)

1. Buka SONG → OPEN `HANYA-DIDALAM-NAMA-YESUS.MID` → tab **MIXER**.
2. Pilih **CH3** (Strings) → SUSTAIN **IGNORE CC64** → PLAY: string harus berhenti saat not dilepas. Coba juga **FORCE OFF**, lalu **FOLLOW MIDI** (kembali seperti file).
3. CH3 → **CHANGE VOICE** → pilih SF2 lalu preset string lain → dengar perbedaannya. Seek, pause/resume: voice tetap.
4. CH10 → **CHANGE KIT** → pilih kit lain.
5. Geser **VOLUME** dan **PAN** satu channel selama play: harus terdengar langsung. Tombol **MIDI** = kembali ke nilai file.
6. **SOLO** satu channel, lalu **MUTE**; **RESET CHANNEL** → kembali seperti file.
7. Buka file lain lalu buka lagi file ini → pengaturan CH3 kembali.
8. Regresi: Style EnglishWaltz2 (SHORT_GATE), RIGHT1/2/3, split, MIDI OUT.
