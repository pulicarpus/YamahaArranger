# MIDI RECORDER — V1B (KEYBOARD ONLY) + status V1C

- **Branch:** `feature/midi-song-v1` (tidak di-merge ke `main`)
- **Baseline:** `d45567a` (SONG MIXER V1A.2; Quick Build #7 hijau, APK https://github.com/pulicarpus/YamahaArranger/actions/runs/41462278031/artifacts/13150091655)
- **Commit V1B:** lihat `git log d45567a..HEAD` (inti recorder, tap engine + integrasi, UI, tes/CI/laporan)
- **Status perangkat:** dites di host/CI saja. **Belum diuji di Redmi Pad maupun dengan PSR-E343 USB fisik.**
- **V1C (FULL ARRANGER):** **belum diimplementasikan.** Gerbang: V1B harus terbukti di perangkat dulu (lihat §8).

## 1. Tahap 0 — audit

| Bagian | Temuan |
|---|---|
| HEAD / CI | `d45567a`; Quick #7 hijau (APK + tes + UI); working tree bersih; `origin` = lokal (tidak ada pekerjaan paralel) |
| Keyboard virtual | `VirtualKeyboardController` → `ArrangerBrain.virtualKeyboardSink` → `VirtualRightVoices` → **engine saja** (tidak ke MIDI OUT). Punya OCTAVE sendiri, **tidak** ikut Master Transpose |
| USB MIDI PSR-E343 | `MidiInputManager` → `brain.onKeyboardNoteOn/Off` (split, ACMP/LEFT, transpose, oktaf RIGHT, touch response) → engine **dan** MIDI OUT; pedal → `brain.setKeyboardSustain` |
| Sustain keyboard | ditahan di aplikasi (`sustainHeldNotes`) + `AudioEngineManager.setKeyboardSustain` |
| Pitch bend / CC1 / pressure dari USB | diparsing tetapi **tidak dibunyikan** oleh aplikasi (lihat `RIGHT_PITCH_STABILITY_AUDIT.md`) |
| Style Sequencer | event final setelah CASM/NTT dan retarget akor: `stageIo` → `audioEngine.noteOnStyleChannel/noteOnChannel` (dst ch 9–16) + MIDI OUT |
| Satu titik bersama | **semua nada yang berbunyi** (keyboard dan Style) lewat `AudioEngineManager.noteOn/noteOnChannel/noteOff/noteOffChannel` (+ varian Style) |

## 2. Arsitektur V1B

```
 USB PSR-E343 ─► MidiInputManager ─► ArrangerBrain (split, transpose, RIGHT octave, LEFT, touch, sustain hold) ─┬─► MIDI OUT (TIDAK direkam)
 Keyboard virtual ─► VirtualRightVoices ─────────────────────────────────────────────────────────────────────┤
                                                                                                             ▼
                                        AudioEngineManager.noteOn / noteOnChannel / noteOff / noteOffChannel (ch 1–4)
                                        setKeyboardSustain, selectKeyboardPreset / setChannelProgram (voice), stopRightPart
                                             │ native call dulu (latensi RIGHT tidak berubah) …
                                             └─► noteTap?.…  (null = tidak merekam: hanya satu baca volatile)
                                                     ▼
                                        MidiRecorder (Kotlin murni, terkunci)
                                          tick = peta tempo (segmen BPM pada jam monotonic) × PPQ 960
                                          ledger note: NOTE OFF tanpa NOTE ON = dibuang; STOP / part OFF / snapshot menutup note terbuka
                                                     ▼
                                        SmfWriter → SMF Type 1: track 0 (judul, 4/4, peta tempo), satu track per part yang bernada
```

- **Tanpa rekaman ganda:** satu tap di engine. MIDI OUT (`MidiInputManager.sendNoteOn`) tidak pernah direkam (guard memeriksa `noteTap` tidak ada di MidiInputManager / MidiMixerOutput / StyleSequencer).
- **Yang direkam = yang terdengar:** nada sudah ditranspose, dioktafkan, dipisah RIGHT/LEFT, velocity sesudah touch response, durasi sesudah sustain.
- **Timeline musikal:** tick dari tempo Style (`ArrangerBrain.state.tempoBpm`). Perubahan tempo masuk ke peta tempo (meta FF 51) pada tick saat itu.
- **File selalu valid:** STOP menutup semua note dan pedal pada tick STOP; setiap track diakhiri End of Track. Saat merekam, snapshot valid ditulis tiap 10 s ke `files/recordings/recording_in_progress.mid` (aman bila aplikasi ditutup paksa). STOP menyimpan otomatis `files/recordings/last_take.mid`. ViewModel `onCleared` menghentikan rekaman dan menyimpannya.

## 3. Pemetaan channel / track (V1B)

| Track | Isi | Channel |
|---|---|---|
| 0 | judul take, teks "YamahaArranger MIDI Recorder V1B", 4/4, tempo | — |
| RIGHT1 | voice (Bank MSB = bank SF2, LSB 0, Program) + teks nama preset SF2, NOTE, CC64 | 1 |
| RIGHT2 | idem | 2 |
| RIGHT3 | idem | 3 |
| LEFT | idem | 4 |

Track hanya dibuat untuk part yang benar-benar memainkan nada. Rencana V1C: Style tetap di ch 9–16 (Rhythm1 9, Rhythm2 10, Bass 11, Chord1 12, Chord2 13, Pad 14, Phrase1 15, Phrase2 16), sehingga **tidak ada konflik** dengan ch 1–4 keyboard.

## 4. UI (halaman SONG)

- **● RECORD / ■ STOP REC** (waktu dan jumlah event saat merekam), **SAVE MIDI** (SAF, `.mid`, aktif bila ada take dan tidak sedang merekam).
- **RECORD MODE:** KEYBOARD ONLY (aktif), FULL ARRANGER (nonaktif, "V1C"), **LOAD TAKE** (memuat take ke Song Player untuk diputar ulang).
- **Lencana mengambang "● REC m:ss ■ STOP"** di layar utama saat merekam (Popup; tata letak layar utama tidak berubah). Halaman SONG boleh ditutup selama merekam.

## 5. Hasil tes

| Tes | Hasil |
|---|---|
| CPV JVM (`MidiRecorderTest` 7 tes: struktur Type 1, tick musikal, track/voice/sustain, STOP di tengah not panjang, tanpa orphan, part OFF, perubahan tempo, perubahan voice, snapshot, take kosong; + Song/Mixer 369 sebelumnya) | **OK, 376 tes** |
| Pembaca Python **independen** (`tools/check_recorded_midi.py` di atas `midi_fixture_gen.read`): Type 1, setiap track diakhiri EOT, note berpasangan, tempo/durasi/jumlah note sesuai | **PASS, 3 file** |
| Rantai produksi rtdiag (`RecorderRtTest`): USB + keyboard virtual → ArrangerBrain → engine → tap, dengan MIDI OUT ON, transpose +2, RIGHT2 oktaf +1, LEFT, pedal USB, note ditahan saat STOP | **OK** |
| Guard isolasi (tap sesudah panggilan native; jalur Style tidak di-tap; MIDI OUT/Style router/ArrangerBrain tidak berubah) | **PASS** |
| Regresi (rtdiag unit, MIDI IN/split, transpose/ACMP/LEFT, RIGHT USB velocity, E343 EW2, latency A/B, r22 live, registration, Song native) | lihat laporan chat |
| UI desktop (RECORD/STOP/SAVE/LOAD TAKE di 3 ukuran, FULL ARRANGER nonaktif) dan build APK | CI (Quick Build) |

## 6. Keterbatasan V1B

1. **Pitch bend, modulation, dan aftertouch tidak direkam**, karena aplikasi tidak membunyikannya dari USB (merekamnya akan membuat file berbeda dari yang terdengar). Bisa ditambah bila nanti dibunyikan.
2. **Voice ditulis sebagai bank/program SF2** ditambah teks nama preset dan SF2. Player lain memakai voice GM/XG-nya sendiri. Di YA SONG, voice bisa dipilih ulang lewat MIXER V1A.2.
3. Keyboard virtual tidak ikut Master Transpose (perilaku aplikasi yang sudah ada). File merekam nada yang terdengar.
4. Sustain dari tombol SUSTAIN atau pedal USB ditulis sebagai CC64. Sustain khusus keyboard virtual ditulis lewat waktu NOTE OFF yang tertunda.
5. Time signature selalu 4/4 di V1B. Tempo mengikuti tempo Style yang aktif.
6. Global All Notes Off dari STOP Style tidak di-tap. Note keyboard yang terpotong saat itu tetap tertutup oleh NOTE OFF tombolnya atau oleh STOP rekaman.

## 7. Langkah tes pengguna V1B (Redmi Pad + PSR-E343)

1. SONG → **● RECORD**. Mainkan **keyboard virtual** beberapa bar → **■ STOP REC** → **LOAD TAKE** → PLAY: harus sama.
2. Ulangi dengan **PSR-E343 USB**, dengan RIGHT1+RIGHT2, LEFT ON, dan Transpose ≠ 0: nada yang diputar ulang harus sama dengan yang dimainkan.
3. Pedal sustain dan RELEASE: durasi nada di playback harus sama.
4. Ganti voice RIGHT1 saat merekam: playback berganti voice di titik yang sama (voice terdekat dari SF2).
5. Tahan satu not panjang, tekan STOP: tidak ada not gantung saat diputar ulang.
6. Ubah tempo saat merekam: playback mengikuti, BAR:BEAT benar.
7. **SAVE MIDI**, lalu buka file di **MIDI Voyager** atau player lain.
8. Regresi: Style EnglishWaltz2 (SHORT_GATE), latensi RIGHT, split, MIDI OUT, Song Player dan Mixer.

## 8. Kesiapan V1C (FULL ARRANGER) — belum dimulai

- **Titik tap sudah ada:** varian Style di `AudioEngineManager` (`noteOnStyleChannel/noteOffStyleChannel/noteOnChannel` dst 8..15, dan `setChannelProgram` dst 8..15). Isinya event final sesudah CASM/NTT dan retarget akor, jadi Intro/Main/Fill/Break/Ending, ACMP, dan perubahan akor sudah tercakup.
- **Perlu dikerjakan:**
  - timeline bersama dengan master tick Style (`ArrangerBrain.clockTickNow`) supaya keyboard dan Style sinkron sampai level tick;
  - voice Style sebagai bank Yamaha 14-bit (MSB/LSB/PC) dan kit (MSB 127);
  - mixer Style (CC7/10/11/91/93);
  - pilihan ekspor **GM-compatible** terpisah (drum di ch10, voice GM terdekat) dari representasi internal;
  - SysEx PSR-S710 **tidak** disalin.
- **Gerbang:** V1B diverifikasi Bos di Redmi Pad (langkah §7).
