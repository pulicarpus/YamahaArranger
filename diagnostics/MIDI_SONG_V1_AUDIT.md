# MIDI SONG V1 — Audit (TAHAP 0)

Branch `feature/midi-song-v1`, baseline `0cdcb48` (`experiment/e343-drum-note-off` HEAD: checkpoint E343 SHORT_GATE 120 ms + UI DATA CONTROL). Tidak di-merge.

## 1. Yang sudah ada

| Bagian | Temuan (file) | Bisa dipakai ulang? |
|---|---|---|
| Menu SONG | `SxMainScreen.kt` `SxNavBar`: tab "SONG" ada di daftar, tetapi **tanpa handler** (MULTI PAD dan REGIST juga sama). Tidak ada player, recorder, atau state SONG. | Hanya slot tab. |
| Parser SMF | `app/src/main/cpp/smf_reader.{h,cpp}`: SMF Type 0/1 di C++, dipakai `style_parser.cpp` untuk `.sty`. Event menyimpan tick, status, channel, dan data; tempo yang disimpan hanya tempo pertama (`defaultTempoBpm`). Tidak ada tempo map, tidak ada time signature, dan tidak ada jalur JNI untuk file `.mid` biasa. | Tidak untuk player: dibutuhkan tempo map, time signature, penanganan error per byte, dan tes JVM. Parser **Kotlin murni baru** (`yamahaarranger/song/Smf.kt`) mudah diuji di CPV dan tidak menyentuh parser Style. |
| Writer SMF | Tidak ada. | Baru (dibutuhkan untuk V1B). Untuk V1A, fixture tes dibuat oleh writer Python **independen** (`tools/midi_fixture_gen.py`) sekaligus pembaca independen. |
| Engine BASSMIDI | `bassmidi_player.cpp`: **satu** stream `BASS_MIDI_StreamCreate(16 ch, DECODE \| FLOAT \| NOTEOFF1)`. Ch 0–3 untuk keyboard (RIGHT1/2/3, LEFT); ch 4–15 untuk Style (Rhythm 8/9 lewat lane perkusi `renderPercussion`). Font dipetakan dengan `FONTEX2` per range channel (0–7, 10–15). `render()` men-decode `stream_`, menambah lane perkusi, lalu meter. API dari Kotlin hanya note on/off, preset, mixer, sustain keyboard, dan release. **Tidak ada** event MIDI umum (pitch bend, CC64 per channel, CC bebas, pressure). | Stream yang ada **tidak boleh** dipakai SONG (lihat §3). |
| MIDI IN | `MidiInputManager` (parser USB, split, channel filter) → `ArrangerBrain` → keyboard part 0–3. | Tidak dipakai di V1A (dipakai V1B). |
| MIDI OUT | `MidiMixerOutput` (router dengan lock): ch Style 4–15, cache voice (`sentVoice`), ledger note, mute/solo, octave correction, epoch. Jalur kirim: `MidiInputManager.sendNoteOn/Off/ProgramChange` → router → `MidiInputPort.send`. `allNotesOff()` mengirim **CC123 ke 16 channel**. | Router tidak cocok untuk Song (dibangun untuk channel Style, ada cache dan mute/solo). V1A: **jalur raw baru** `sendSongBytes` langsung ke port, dengan ledger milik Song sendiri, ditambah `forgetAllSentMidiState()` setelah Song berhenti agar Style mengirim ulang voice-nya. |
| Keyboard virtual | `VirtualKeyboardPanel` → `brain.onKeyboardNoteOn` (sama dengan USB). | V1B. |
| StyleSequencer | Clock master (`TempoClock`, PPQ Style 1920, `tickDeadlineNanos`), `stageIo` (urutan I/O per key), CASM/NTT, map drum, eksperimen E343. Event final per channel tujuan dikirim di `playOnce` (lewat `stageIo`: engine, lalu MIDI OUT). | V1C: titik tap kanonis (§5). V1A tidak menyentuh file ini. |
| Penyimpanan Android | Storage Access Framework (`ActivityResultContracts.OpenDocument/CreateDocument`) sudah dipakai untuk Style, SF2, WAV, dan log. | Dipakai untuk OPEN MIDI (V1A) dan SAVE MIDI (V1B). |

## 2. Alur data V1A

```
 .mid/.midi (SAF OpenDocument)
        │ bytes (≤ 8 MB)
        ▼
 Smf.parse ──► SmfFile (format, PPQ, track, event + byte asli, error jelas, Type 2 ditolak)
        │
        ▼
 SongTimeline: semua track digabung (urut tick, lalu urutan file), tempo map → µs per event,
               time signature → BAR:BEAT, durasi, state "chase" per channel (bank/program/CC/bend)
        │
        ▼
 SongPlayer (satu thread, jam monotonic System.nanoTime)
   PLAY/PAUSE/RESUME/STOP/SEEK, tempo override (%), mute per channel,
   ledger note & sustain milik SONG sendiri (per channel × note)
        │  setiap event dikirim SEKALI ke dispatcher
        ▼
 SongOutputs (fan-out; satu event → satu salinan per output yang aktif, tidak pernah ganda di satu output)
   ├── INTERNAL: NativeAudioBridge.nativeSongEvents(raw bytes) → stream BASSMIDI KEDUA milik SONG
   │                (16 ch, font sama, di-mix di render(); tidak menyentuh stream keyboard/Style)
   └── EXTERNAL: MidiInputManager.sendSongBytes → MidiInputPort (PSR-E343)
                 SysEx TIDAK pernah dikirim ke luar (diparsing dan dihitung saja)
```

## 3. Keputusan arsitektur dan alasannya

1. **Stream BASSMIDI terpisah untuk SONG.** Kalau Song dikirim ke stream yang ada, Program Change Song di ch 1–4 akan menimpa voice RIGHT/LEFT pilihan pengguna, ch 9–16 bentrok dengan state Style (pin, tiered source, lane drum), dan `nativeAllNotesOff` saat STOP Song akan memutus keyboard. Stream kedua:
   - punya channel sendiri (16), jadi kepemilikan note jelas: **STOP Song hanya membersihkan stream Song**;
   - menerima event raw (`BASS_MIDI_StreamEvents(..., BASS_MIDI_EVENTS_RAW)`), sehingga pitch bend, pressure, CC64, dan running status langsung didukung;
   - memakai SoundFont yang sama (pemetaan sederhana: font drum lalu font melodi; ch 10 = drum sesuai GM);
   - hanya di-decode saat Song aktif atau masih ada voice yang berbunyi. Saat Song diam, `render()` identik dengan baseline.
2. **Parser Kotlin baru**, bukan memperluas `smf_reader.cpp`: parser Style tetap byte-identik, dan parser baru diuji di JVM terhadap fixture dari writer Python independen.
3. **MIDI OUT Song lewat jalur raw sendiri**, bukan router Style. Setelah Song STOP/PAUSE/SEEK, ledger Song mengirim NOTE OFF + CC64=0 hanya untuk note atau channel milik Song. **Tidak ada CC123 global.** Setelah STOP, cache voice router di-invalidasi agar Style mengirim ulang Bank/Program ke E343.
4. **Kebijakan SONG vs STYLE (V1):** PLAY SONG ditolak dengan pesan jelas saat Style berjalan; Style tidak pernah dihentikan oleh Song. START Style saat Song berjalan menghentikan Song dengan bersih (hanya note Song), juga dengan pesan. Keyboard tetap bisa dimainkan di atas Song (stream internal terpisah). Lihat risiko R3.

## 4. Risiko

| # | Risiko | Mitigasi V1A |
|---|---|---|
| R1 timing | Jitter thread Java dan GC. | Jadwal absolut dari jam monotonic (`startNs + µs(tick)`), bukan sleep kumulatif, jadi tidak ada drift. Event pada waktu yang sama dikirim berurutan dalam satu batch. Tempo override menskalakan waktu; perubahannya dihitung ulang dari posisi saat ini. |
| R2 duplikasi | Dua output aktif bisa terlihat seperti event ganda. | Satu dispatcher, satu salinan per output. Tes: jumlah event per output = jumlah event file (tanpa SysEx), dan tidak ada output yang menerima event dua kali. |
| R3 bentrok channel di E343 | Song ch 1–4 sama dengan channel RIGHT/LEFT di MIDI OUT, dan ch 9–16 sama dengan Style. | Style tidak boleh berjalan bersamaan (kebijakan §3.4). Keyboard + Song lewat MIDI OUT bisa bertumpuk di channel yang sama: didokumentasikan. Output default = INTERNAL. |
| R4 hanging note | STOP/PAUSE/SEEK/MUTE di tengah note. | Ledger milik Song mengirim NOTE OFF untuk setiap note aktif dan CC64=0 untuk channel yang sedang sustain. Internal: `nativeSongReset` (NOTESOFF + SUSTAIN 0 per channel, hanya stream Song). |
| R5 SEEK | Instrumen/controller salah setelah lompat posisi. | "Chase": sebelum lanjut dari posisi baru, kirim bank, program, CC terakhir, dan pitch bend terakhir per channel sampai posisi itu (tanpa NOTE ON). |
| R6 file rusak | Panjang chunk salah, VLQ tak berujung, running status tanpa status, file terpotong. | Parser membatasi panjang, melempar `SmfException` dengan offset, dan tidak pernah crash. UI menampilkan pesan. |
| R7 native | Perubahan `bassmidi_player.cpp` harus tidak mengubah perilaku stream lama. | Kode **hanya tambahan**; guard memeriksa bahwa tidak ada baris yang dihapus. Mock BASS ditambah fungsi yang dipakai. Full Validation (kontrak native, golden A/B, ROSITA byte replay) tetap berjalan di branch ini. |
| R8 V1C | Event Style final dikirim di dua tempat (engine dan MIDI OUT) dengan representasi berbeda (drum map MIDI OUT, octave correction MIDI OUT, E343 NOTE OFF). | Ditangani di audit V1C: tap kanonis di `stageIo` sebelum fan-out, dengan nilai **sebelum** koreksi khusus MIDI OUT. Tidak dikerjakan di V1A. |

## 5. Catatan untuk V1B/V1C (belum dikerjakan)

* Konflik pemetaan channel: kontrak yang diminta adalah RIGHT1–3 = ch 1–3, LEFT = 4, Rhythm1/2 = 9/10, Bass = 11, … Phrase2 = 16. Aplikasi saat ini: keyboard part 0–3 (= ch 1–4), Style tujuan 8–15 (= ch 9–16). Ini **cocok**. Ch 5–8 dipakai Style hanya untuk sumber yang tidak dirutekan CASM. Ini harus diaudit ulang sebelum V1C.
* Tap kanonis V1C: `StyleSequencer.playOnce` → `stageIo{ engine; midiOut }`. Recorder akan dipasang sebagai observer **sebelum** fan-out. Implementasinya akan dirancang di audit V1C.

## 6. Rencana V1A (commit kecil)
1. Audit ini.
2. Inti Kotlin murni: `Smf` (parser), `SongTimeline`, `SongPlayer` (jam dan output abstrak), tes CPV, serta fixture dari writer Python independen dan verifikasi silang dengan pembaca Python.
3. Native: stream Song + JNI (tambahan saja) dan mock BASS.
4. Integrasi: `AudioEngineManager`/`MidiInputManager` (tambahan), ViewModel, halaman SONG (Compose portabel + tes UI desktop), kebijakan Style, guard, dan workflow Quick APK untuk branch ini.
