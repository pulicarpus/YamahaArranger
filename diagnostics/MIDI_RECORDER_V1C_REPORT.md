# MIDI RECORDER V1C — FULL ARRANGER + keyboard virtual ACMP/LEFT

- **Branch:** `feature/midi-song-v1` (tidak di-merge ke `main`)
- **Baseline:** `43e4657` (V1B; Quick Build #8 hijau). V1B sudah dites Bos di Redmi Pad: take 221 event / 25 bar, LOAD TAKE berhasil.
  PSR-E343 USB **belum** dites di perangkat.
- **Commit V1C:** lihat `git log 43e4657..HEAD`
- **Status perangkat V1C:** dites di host/CI saja. **Belum diuji di Redmi Pad.** Belum dinyatakan stabil.

## 1. Tahap 0 — audit

| Bagian | Temuan |
|---|---|
| Jalur final Style | `StyleSequencer.playOnce` → transform (CASM/NTT, transpose, oktaf part, velocity) → peta drum internal → `stageIo` (ledger berurutan) → `AudioEngineManager.noteOnStyleChannel` (+ MIDI OUT terpisah). Retarget akor: `noteOnStyleChannel/noteOnChannel` dan `noteOffStyleChannel/noteOffChannel`. Gate = waktu NOTE OFF dari ledger |
| Voice / controller Style | `setChannelProgram` (bank Yamaha 14-bit), `pinStyleChannel` (pin SF2 dari Style Voice Editor), `setChannelMixer` (CC7/10/11/91/93), `setChannelExpression` |
| STOP Style | `allNotesOff` → native NOTESOFF pada **16 channel** (termasuk keyboard) |
| Master timing | sequencer: `TempoClock` (PPQ Style, deadline nanodetik). Recorder: jam monotonic yang sama (`System.nanoTime`) + peta tempo |
| Channel internal | keyboard 0..3, Style dst 8..15 (Rhythm1 8, Rhythm2 9, Bass 10, Chord1 11, Chord2 12, Pad 13, Phrase1 14, Phrase2 15). **Tidak ada bentrokan.** Ch 4..7 tidak dipakai Style |
| Keyboard virtual | semua tombol → jalur RIGHT on-screen (`VirtualRightVoices`). Area kiri tidak pernah mencapai deteksi akor/LEFT. **Inilah penyebab ACMP/LEFT tidak bisa dimainkan** |

## 2. Keyboard virtual ACMP/LEFT (Tahap 1)

`VirtualKeyRouter` (baru, Kotlin murni, terisolasi). ArrangerBrain **tidak diubah**:
- tombol ≤ split dan (ACMP ON atau LEFT ON) → `ArrangerBrain.onKeyboardNoteOn/Off`, **jalur USB yang sama**, jadi aturannya sama persis dengan PSR-E343: ACMP = akor, LEFT = voice LEFT, keduanya = keduanya;
- selain itu (area kanan, atau ACMP OFF + LEFT OFF = seluruh keyboard RIGHT) → jalur RIGHT on-screen lama, tidak berubah;
- jalur dipilih saat NOTE ON dan diingat per tombol, sehingga NOTE OFF selalu kembali ke jalur yang sama walau split/ACMP/LEFT berubah saat tombol ditahan.
- Catatan: karena memakai jalur USB, LEFT dari keyboard virtual juga dikirim ke MIDI OUT (seperti LEFT dari USB). RIGHT on-screen tetap tidak ke MIDI OUT (perilaku lama).

## 3. Arsitektur tap final (Tahap 2–3)

```
 keyboard (USB / on-screen) ─► ArrangerBrain ─┐
 Style ─► StyleSequencer (CASM/NTT, retarget, drum map) ─► stageIo ─┤
                                                                     ▼
            AudioEngineManager: noteOn* / noteOff* / *StyleChannel   ─► native (BASSMIDI) DULU
                                setChannelProgram / pinStyleChannel / setChannelMixer / setChannelExpression / allNotesOff
                                                                     └─► noteTap?.… (null = tidak merekam)
                                                                              ▼
                              MidiRecorder: SATU jam + SATU peta tempo untuk semua channel → tick PPQ 960
```

- **Yang direkam = yang dibunyikan engine internal:** nada sesudah CASM/retarget/oktaf/peta drum internal, velocity final, gate dari NOTE OFF ledger.
- **MIDI OUT tidak di-tap**, jadi tidak ada event ganda. Guard memeriksa `noteTap` tidak ada di MidiInputManager / MidiMixerOutput / StyleSequencer.
- **Satu timeline:** setiap event (keyboard maupun Style) dicap waktu oleh jam recorder yang sama, saat engine menerimanya. Tidak ada dua timeline yang digabung. Tempo berubah = segmen baru dari tick saat itu; event sebelumnya tidak bergeser.
- **STOP Style** (`allNotesOff`) menutup semua note terekam pada tick itu. NOTE OFF yang datang terlambat dibuang (tidak ada orphan).
- **Voice dan mixer awal** Style (sudah diset sebelum RECORD) diambil dari snapshot di AudioEngineManager dan ditulis di tick 0. Mixer hanya merekam perubahan.
- Tap dipanggil **sesudah** panggilan native. Biaya saat tidak merekam: satu baca volatile per event.

## 4. Format dan channel (Tahap 4)

| Track | App ch → file ch (NATIVE) | GM COMPATIBLE |
|---|---|---|
| 0 conductor | judul, teks, **XG System On** (pesan XG standar, bukan SysEx PSR-S710), 4/4, peta tempo | GM System On |
| RIGHT1/2/3, LEFT | 1–4 | 1–4, bank 0 + program |
| Rhythm1 | **9**, bank MSB 127 (drum part di XG) | **10** ("Rhythm1 (GM ch10)"), tanpa PC sendiri |
| Rhythm2 | 10, bank MSB 127 + kit | 10, kit = program Rhythm2 |
| Bass / Chord1 / Chord2 / Pad / Phrase1 / Phrase2 | 11 / 12 / 13 / 14 / 15 / 16, bank Yamaha MSB/LSB + program | sama, bank 0 + program |

- Satu track per part yang memainkan nada. Teks per track menyebut voice: nama, sumber SF2/Style, dan bank/program.
- **SAVE MIDI** memakai jenis ekspor yang dipilih (YA NATIVE / GM). **LOAD TAKE** memutar jenis yang dipilih. Simpan otomatis: `last_take.mid` (NATIVE) dan `last_take_GM.mid`.
- **Klaim yang belum dibuat:** kompatibilitas GM/XG di player lain dan di modul eksternal belum dites. Yang sudah dites: BASSMIDI asli membuka dan memutar kedua file (§6).

## 5. UI (Tahap 5)

- Halaman SONG, baris RECORD MODE: **KEYBOARD ONLY / FULL ARRANGER** (terkunci saat merekam), **EXPORT: YA NATIVE / GM**, **LOAD TAKE**.
- Tombol RECORD menampilkan mode; STOP REC menampilkan waktu dan jumlah event; SAVE MIDI.
- **Lencana "● REC m:ss ■ STOP" mengambang** di layar utama, jadi halaman SONG bisa ditutup dan Style dimainkan lewat Live Control (START, section, akor) selama merekam.

## 6. Hasil tes

| Tes | Hasil |
|---|---|
| CPV JVM (`MidiRecorderTest` 9 tes, termasuk FULL NATIVE dan GM) | **OK, 378 tes** |
| **Rantai produksi V1C** `FullArrangerRecorderRtTest` (ArrangerBrain + StyleSequencer asli, MIDI OUT ON): akor dari keyboard virtual, Intro → akor C→F → Fill → tempo 120→100 → Ending → STOP, melodi USB, note ditahan saat STOP | **OK**. Per channel (engine = file): RIGHT1 2, Rhythm1 90, Rhythm2 10, Bass 22, Chord1 19, Chord2 18, Pad 24, Phrase1 40 = **225 NOTE ON, tidak ada yang ganda atau hilang**. **Satu timeline: selisih terburuk 0,94 ms** antara waktu di file dan panggilan engine. Retarget akor: bass {C,G} → {F,C}. Semua note tertutup. Take diputar ulang di SongPlayer: 225 NOTE ON |
| Keyboard virtual ACMP/LEFT (rtdiag): ACMP ON = akor tanpa voice, LEFT ON = LEFT ch4, keduanya OFF = RIGHT, area kanan = RIGHT, split dipindah saat tombol ditahan = tanpa not gantung | **OK** |
| V1B `RecorderRtTest` (USB + on-screen, transpose, oktaf, LEFT, pedal) | lihat chat |
| Pembaca Python **independen**: 7 file (V1B + V1C NATIVE/GM, termasuk 2 file dari rantai produksi): Type 1, EOT tiap track, note berpasangan, satu SysEx setup, GM tanpa ch9 | **PASS** |
| **BASSMIDI asli** (CI) memutar file rekaman V1C lewat `BASS_MIDI_StreamCreateFile`: berbunyi; ch10 drum; NATIVE ch9 drum lewat XG System On + MSB 127 | CI (Quick Build) |
| Regresi: rtdiag unit, stress realtime + P1 chain, split, ACMP/LEFT, RIGHT USB velocity, E343 SHORT_GATE EW2, E343 MIDI OUT audit, transisi section, mixer keys, latency A/B, cpv native, r22 live, registration, Song native, guard | lihat chat |

Tes mock **bukan** bukti kualitas audio. Kualitas suara drum/voice di Redmi Pad belum diukur.

## 7. Keterbatasan dan risiko

1. **Belum dites di Redmi Pad**, termasuk beban CPU saat Style penuh direkam dan latensi RIGHT di perangkat.
2. Voice Style dari pin SF2 (Style Voice Editor) ditulis sebagai bank/program SF2. Di player lain voice-nya bisa berbeda. Kit drum memakai nomor kit Style; SF2 lain bisa jatuh ke kit default.
3. **Peta drum internal** (Drum Note Map) direkam sebagai nada **internal** (yang terdengar di YA), bukan nada MIDI OUT.
4. Bar file dimulai saat RECORD ditekan. Tekan RECORD sebelum START supaya bar file sejajar dengan bar Style.
5. Time signature selalu 4/4 (time signature Style belum dibaca).
6. Tempo berubah dicatat saat state tempo berubah (sub-milidetik), bukan di tick sequencer. Waktu absolut setiap event tetap tepat.
7. Pitch bend/CC1/aftertouch dari USB tetap tidak direkam (tidak dibunyikan aplikasi). Controller per-event dari data Style (mis. pitch bend di Phrase), bila ada, tidak lewat API engine yang di-tap, jadi belum direkam (perlu dicek dengan Style yang memakainya).
8. LEFT dari keyboard virtual ikut ke MIDI OUT (kebijakan USB).

## 8. Langkah tes pengguna (Redmi Pad)

1. Muat Style. SONG → **FULL ARRANGER** → EXPORT **YA NATIVE** → **● RECORD**. Tutup halaman SONG (lencana REC terlihat).
2. ACMP ON. Mainkan akor di **area kiri keyboard virtual**. START (Intro) → Main → ganti akor beberapa kali → Fill → Main → Ending. Mainkan melodi.
3. Lencana **■ STOP** → buka SONG → **LOAD TAKE** → PLAY: drum, bass, chord, pad, phrase, dan melodi harus sinkron dan sama dengan yang dimainkan.
4. EXPORT **GM** → LOAD TAKE / SAVE MIDI → coba di MIDI Voyager atau player lain.
5. Keyboard virtual: ACMP ON/LEFT OFF (akor saja), ACMP OFF/LEFT ON (voice LEFT), keduanya OFF (RIGHT di seluruh keyboard).
6. PSR-E343 USB: ACMP/LEFT/RIGHT seperti biasa (regresi), lalu rekam FULL.
7. Regresi: EnglishWaltz2 (SHORT_GATE), latensi RIGHT, MIDI OUT, Song Player dan Mixer.
