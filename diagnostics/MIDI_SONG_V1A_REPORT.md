# MIDI SONG V1A — MIDI Song Player (laporan tahap)

- **Branch:** `feature/midi-song-v1` (tidak di-merge ke `main`)
- **Baseline:** `0cdcb48` (checkpoint E343 SHORT_GATE 120 ms + UI DATA CONTROL)
- **Status:** dibangun dan dites di host/CI. **Belum diuji di Redmi Pad maupun PSR-E343 fisik.**
- **V1B (recorder) / V1C (full arranger recording):** belum dikerjakan; menunggu hasil tes Player oleh pengguna.

## 0. V1A.1 (perbaikan suara setelah tes perangkat)

Tes pengguna V1A: Song berbunyi, tetapi piano bervibra dan drum/bass lemah. Penyebabnya: SysEx setup XG dari file dibuang.
V1A.1 mengirim SysEx setup (whitelist XG/GS/GM) **hanya ke synth internal Song**, me-reset synth Song di awal lagu dan saat chase,
dan menyamakan SRC stream Song dengan stream utama. MIDI OUT tetap tanpa SysEx.
Detail: `diagnostics/MIDI_VOYAGER_SONG_AUDIT.md`.

## 1. Arsitektur

```
 OPEN (SAF OpenDocument, .mid/.midi, ≤ 8 MB)
        │
        ▼
 Smf.parse ─ Type 0/1, VLQ, running status, PPQ, meta, SysEx (setup XG/GS/GM → INTERNAL saja), End of Track,
        │     file rusak → SmfException(offset) ; Type 2 / SMPTE → ditolak dengan pesan jelas
        ▼
 SongTimeline ─ merge semua track (tick → NOTE OFF dulu → track → urutan file), tempo map → µs,
        │        time signature → BAR:BEAT, durasi, chase state (MSB → LSB → PC → CC → bend)
        ▼
 SongPlayer (thread sendiri, jadwal absolut dari jam monotonic, tempo override 25–300 %)
   ledger note/sustain/bend milik SONG → panic HANYA untuk note SONG pada STOP/PAUSE/SEEK/MUTE/ganti output
        │ satu event → satu salinan per output
        ├── INTERNAL → nativeSongEvents → BassMidiPlayer::songEvents → songStream_ (stream BASSMIDI KE-2,
        │               16 ch, di-mix di render(); stream keyboard/Style tidak disentuh). songEvents menolak byte ≥ 0xF0; songSysEx hanya setup XG/GS/GM utuh.
        └── MIDI OUT → MidiInputManager.sendSongBytes (raw, hanya 0x80..0xEF; SysEx tidak pernah dikirim)

 Kebijakan SONG vs STYLE (V1): PLAY SONG ditolak saat Style berjalan;
 START Style menghentikan Song dengan bersih (onStartStop → stopSongForStyle).
 Tidak ada All Notes Off / Reset Controllers global: reset native hanya di songStream_.
```

## 2. File

| Jenis | File |
|---|---|
| Inti (Kotlin murni) | `song/Smf.kt`, `song/SongTimeline.kt`, `song/SongPlayer.kt`, `song/SongOutputs.kt` |
| Native (tambahan saja) | `bassmidi_player.{h,cpp}` (songStream_, renderSong), `audio_engine.{h,cpp}`, `native_lib.cpp` (JNI `nativeSongEvents/Reset/SetActive`) |
| Integrasi (tambahan) | `NativeAudioBridge.kt`, `AudioEngineManager.kt`, `MidiInputManager.sendSongBytes`, `MainViewModel.kt` (blok Song + `onStartStop`) |
| UI | `ui/SongPanel.kt` (portable), `ui/SongPage.kt` (dialog + picker), `SxMainScreen.kt` (tab SONG membuka halaman) |
| Tes | `tests/cpv/SongPlayerTest.kt`, `tests/song_native_test.cpp`, `tools/test_song_native.py`, `tools/midi_fixture_gen.py` + `tests/fixtures/midi/*`, `tools/ui_preview/.../SongPanelTest.kt`, `tools/test_midi_song_guard.py` |
| CI | `.github/workflows/midi-song-quick-apk.yml` (baru), `style-voice-editor.yml` (branch ditambahkan) |

## 3. Tes (lokal, host)

| Tes | Hasil |
|---|---|
| Guard isolasi (`test_midi_song_guard.py`: file beku, hanya penambahan di file native/bridge, invarian SysEx/All Notes Off/kebijakan Style) | PASS |
| Fixture MIDI dibaca ulang oleh pembaca Python independen (`--check`, 7 file) | PASS |
| CPV JVM (termasuk 14 tes SongPlayerTest: parser vs manifest, 5 file rusak, Type 2 ditolak, tempo map, BAR:BEAT, seek+chase, pause/stop tanpa note gantung, mute, tempo override, tanpa drift 10 menit (telat maks ≈ 3 ms), routing tanpa duplikasi) | OK, 359 tes, 0 gagal |
| Kontrak native Song (`song_stream=2 reset_events=32 main_stream_touched=0`, SysEx ditolak) | PASS |
| Suite native yang ada (cpv_native, right_diag, rtdiag_native, r23_native, u01 native, latency A/B) | semua PASS |
| UI desktop Compose SongPanelTest (1333×800, 1280×800, 1000×600) | hanya di CI |
| APK (Kotlin + NDK, 2 ABI) | hanya di CI (Gradle Android tidak bisa dijalankan lokal) |

Status GitHub Actions dan link APK: lihat laporan chat untuk commit ini.

## 4. Yang benar-benar berfungsi (terverifikasi di host)

- Parsing SMF Type 0/1, error jelas untuk file rusak, Type 2 ditolak.
- Penjadwalan, PLAY/PAUSE/RESUME/STOP/SEEK, tempo %, mute per channel, chase bank/program/CC saat seek/resume.
- STOP/PAUSE/SEEK/MUTE hanya mematikan note milik Song; tidak ada NOTE OFF yatim.
- Output INTERNAL / MIDI OUT / BOTH: setiap event satu kali per output.
- Jalur Song tidak menyentuh stream keyboard/Style (mock native).

## 5. Risiko

1. **Timing di perangkat** (thread Java, GC): jadwal absolut menghindari drift, tetapi jitter di Android belum diukur.
2. **Beban CPU**: stream BASSMIDI kedua hanya di-decode saat Song aktif (+ ekor 4 s); polifoni penuh belum diukur di Redmi Pad.
3. **MIDI OUT ke PSR-E343**: Song memakai ch 1–16 yang sama dengan RIGHT/LEFT/Style di modul eksternal. Default output INTERNAL. Song berbunyi tunggal (Style dilarang bersamaan) di V1.
4. **Pemetaan SoundFont Song** sederhana (font drum lalu font melodi); voice GM bisa berbeda dari yang diharapkan file.
5. **SysEx**: sejak V1A.1 setup XG/GS/GM diterapkan di synth internal; SysEx lain dan semua SysEx ke MIDI OUT tetap dibuang.

## 6. Langkah tes pengguna (Redmi Pad, landscape)

1. Install APK, buka tab **SONG** → halaman SONG terbuka.
2. **OPEN MIDI** → pilih file .mid (Type 0 dan Type 1). Cek judul, durasi, tempo, time signature, nama channel.
3. **PLAY** → suara keluar lewat INTERNAL. Cek BAR:BEAT dan progress berjalan.
4. **PAUSE** lalu **PLAY** (resume) → tidak ada note gantung, voice tetap benar.
5. Geser **seek** dan tombol bar ◀/▶ saat play dan saat pause → voice/program benar setelah lompat.
6. Tempo **−5% / +5%**, lalu reset.
7. Tap channel di mixer 16 channel → mute/unmute; note channel itu berhenti bersih.
8. **STOP** → semua note Song berhenti; keyboard RIGHT/LEFT masih normal.
9. Output **MIDI OUT** dan **BOTH** dengan PSR-E343 → tidak ada note ganda/gantung; STOP membersihkan.
10. Saat Song play, tekan **START Style** → Song berhenti bersih, Style jalan. Saat Style jalan, **PLAY SONG** → muncul pesan penolakan.
11. Buka file rusak / Type 2 → pesan error, aplikasi tidak crash.
12. Cek regresi: Style EnglishWaltz2 (SHORT_GATE 120 ms), split point, transpose, registration, mixer, DATA CONTROL.
