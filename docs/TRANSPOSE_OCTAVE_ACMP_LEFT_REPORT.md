# MASTER TRANSPOSE, RIGHT OCTAVE, ACMP/LEFT INDEPENDENCE

Branch `feature/transpose-octave-acmp-left`, baseline `034855a` (Realtime audit + Metronome; CI SUCCESS, APK arm64-v8a + armeabi-v7a built).
Tidak ada merge ke `main`. Bukti di dokumen ini dibedakan: **UNIT** (JVM), **HOST-SIM** (rig produksi MIDI parser → ArrangerBrain → ChordDetector → StyleSequencer → AudioEngineManager dengan JNI palsu), **DEVICE** (Redmi Pad + PSR-E343 — *belum ada*). Tidak ada klaim hasil suara/musikal/realtime sebelum uji DEVICE.

## 0. Baseline
- Working tree bersih, HEAD = `origin/feature/realtime-audit-metronome` = `034855a`; workflow `style-voice-editor.yml` run 39631015737 SUCCESS (host + android) dengan APK. Metronome/realtime selesai → implementasi diizinkan.
- Branch kerja terpisah dari baseline; tidak ada branch agen lain yang disentuh.
- Tidak ada refactor besar: `CasmNoteTransformer`, `ChordDetector`, `AcmpChordAnalyzer`, MIDI parser/`MidiInputManager`, `MidiMixerOutput`, BASSMIDI/Oboe/C++, Metronome dan semua store Style Voice **byte-identik** dengan baseline (`tools/test_transpose_guard.py`).

## 1. Audit perilaku sebelum patch (kode baseline `034855a`)
| # | Temuan | Lokasi |
|---|---|---|
| A1 | Master Transpose = penjumlahan semitone **setelah** CASM: `note=(transformed+override+master)` dan `updateHeldPitch` (`target=base+master`). Register CASM (NTT/High Key/Note Limit) tetap register chord asli, jadi "C +5" ≠ chord F. | `StyleSequencer.kt` dispatch, `updateHeldPitch`, `rootPitchForHeld` (`masterApplied`) |
| A2 | Tidak ada requested/active/pending: setiap perubahan transpose langsung menggeser nada Style yang sedang berbunyi (harmoni berubah di tengah lagu). | `ArrangerBrain.setKeyboardTranspose` → `sequencer.setMasterTranspose` |
| A3 | ACMP dan LEFT saling terkait: ACMP ON+LEFT ON = hanya chord (LEFT **tidak** bunyi); ACMP ON+LEFT OFF = tidak ada deteksi chord, area bawah malah jadi RIGHT; ACMP ON mematikan nada LEFT (`releaseLeftVoiceNotes("ACMP ON")`). | `ArrangerBrain.onKeyboardNoteOn/Off`, `setAcmpEnabled` |
| A4 | `StyleSequencer` tidak tahu ACMP: ACMP OFF tidak menghasilkan drum-only; chord lama (`currentChord`) tidak dibersihkan. | `StyleSequencer` dispatch |
| A5 | Tidak ada Octave RIGHT. Pitch di luar 0..127 di-*clamp* (`coerceIn`) sehingga nada berubah diam-diam. | `ArrangerBrain` |
| A6 | Registration tidak menyimpan ACMP maupun octave; split point (54) tidak punya UI dan tidak disimpan (hanya konstanta/`setSplitPoint` tanpa pemanggil UI). | `Registration*`, `SxMainScreen` ("SPLIT" display-only) |
| A7 | MIDI OUT sudah memakai satu pitch final (`outputNote`) per NOTE_ON dan NOTE_OFF dipasangkan lewat owner map; drum tidak pernah ditranspos. Tidak ada jalur MIDI OUT→IN di app. | `startRightNote`, `midiInputManager.sendNoteOn` |
| A8 | Perilaku baseline yang **tidak diubah** (dicatat sebagai risiko): `StyleSequencer.stop()` memanggil `allNotesOff` global → STOP/START mematikan nada keyboard yang sedang ditahan (NOTE_OFF berikutnya menjadi orphan, tidak berbahaya tetapi nyata). | `StyleSequencer.stop()` |

## 2. Desain & akar masalah terbukti
**Chord-target transposition.** Chord yang terdeteksi ditranspos (root & bass mod 12, kualitas tetap, `chord/ChordTranspose.kt`) **sebelum** masuk `StyleSequencer.currentChord`. CASM/NTR/NTT/RTR/Note Limit/High Key lalu menurunkan semua part dari chord target persis seperti bila chord itu dimainkan. Tidak ada penambahan semitone setelah CASM (A1 hilang); drum (`isDrumDestination`) tidak pernah tersentuh.

**Tiga nilai transpose** di `ArrangerBrain`: `requestedTranspose` (nilai knob/Registration), `activeTranspose` (dipakai RIGHT/LEFT NOTE_ON dan target chord), `pendingTranspose` (permintaan yang menunggu).
- Style berjalan + ACMP ON + sudah ada chord terapan → permintaan menjadi **pending**; harmoni tidak berubah.
- Pending dikomit **hanya** pada *chord onset*: NOTE_ON area ACMP yang menghasilkan pengenalan chord valid (termasuk menekan ulang chord yang sama setelah dilepas). NOTE_OFF/callback deteksi berulang **bukan** onset. Komit, penurunan chord target, dan `appliedChord` terjadi dalam satu transaksi di bawah `keyboardLock`; tidak ada restart, pergeseran beat, atau perubahan tempo; tidak ada mass NOTE_OFF/ON saat knob ditekan.
- Permintaan terakhir menang; meminta nilai sama dengan *active* membatalkan pending.
- Style berhenti → pending dikomit saat STOP dan konteks chord diturunkan ulang (restart memakai transpose baru). Tidak ada Style berjalan / ACMP OFF / belum ada chord → aktif segera. ACMP dimatikan → pending dikomit. Ganti split point → konteks chord dibuang, pending dikomit. Tidak ada pending yang bisa menggantung tanpa indikator (indikator di UI, lihat §5).
- RIGHT/LEFT memakai `activeTranspose` saat NOTE_ON (selama pending tetap nilai lama); nada yang sudah berbunyi tidak berubah pitch; NOTE_OFF memakai pitch yang tercatat pada NOTE_ON.

**ACMP/LEFT 4-state** (area bawah = key ≤ split 54): ACMP ON → chord detector; LEFT ON → suara LEFT (ch3, `activeTranspose`); keduanya ON → keduanya untuk key yang sama; keduanya OFF → seluruh keyboard RIGHT. Kepemilikan per-key (`chordHeld`, `leftOwners`, `rightNoteOwners`) menjamin toggle saat key ditahan tidak membuat NOTE_OFF yatim atau memotong RIGHT. ACMP tidak pernah mengubah LEFT dan sebaliknya.

**ACMP OFF = drum-only.** `StyleSequencer.setAcmpHarmony(false)` membersihkan konteks chord (nada harmonik dilepas lewat ledger `activeTransposedNotes` miliknya sendiri) dan menjatuhkan NOTE_ON non-drum (`DROP_ACMP_OFF`); Rhythm dipilih lewat `isDrumDestination`, bukan asumsi kanal. OFF→ON: `awaitChord` menahan harmoni sampai chord valid baru diterapkan (chord lama tidak dipakai ulang). Transport/tempo/section tidak disentuh.

**RIGHT Octave** −1/0/+1 per R1/R2/R3: `output = input + activeTranspose + 12×octave(layer)`; di luar 0..127 → **dibuang untuk layer itu** (tanpa clamp/wrap/nada ilegal). Tidak memengaruhi chord, Style, drum, atau LEFT. Mengubah octave saat key ditahan aman (owner map menyimpan pitch). MIDI OUT menerima pitch final satu kali per layer.

**Registration**: slot menyimpan `acmp` dan `rightOctaves` sebagai field **opsional** (slot lama tetap byte-identik dan terbaca; recall slot lama tidak mengubah nilai live). Urutan recall: ACMP sebelum Transpose, octave mengikuti freeze VOICES, ACMP mengikuti freeze PART ON/OFF. Recall saat Style berjalan mengikuti kontrak pending (lewat `setKeyboardTranspose`). LEFT ON/OFF dan voice sudah tersimpan sebelumnya. Split point **tidak** disimpan (tidak ada konfigurasi pengguna; fixed 54) — dicatat.

## 3. File produksi yang berubah
| File | Alasan |
|---|---|
| `arranger/ArrangerBrain.kt` | state machine requested/active/pending, routing 4-state, octave, kepemilikan per-key, transaksi komit |
| `yamahaarranger/arranger/StyleSequencer.kt` | hapus penambahan semitone pasca-CASM; gate ACMP + `awaitChord`; `masterTranspose` jadi cermin diagnostik |
| `chord/ChordTranspose.kt` (baru) | `DetectedChord.transposedBy` |
| `registration/{RegistrationModel,RegistrationCodec,RecallPlanner,RegistrationLibraryController}.kt` | field opsional ACMP/octave, langkah recall |
| `ui/MainViewModel.kt` | snapshot/apply Registration, state UI |
| `ui/SxMainScreen.kt` | indikator pending di nilai TRANSPOSE, kontrol segmented octave R1/R2/R3 (−1/0/+1) di panel suara, badge `OCT` di kartu suara |

## 4. Tes
| Tes | Jenis | Cakupan |
|---|---|---|
| `tests/rtdiag/TransposeAcmpLeftTest` (18) | HOST-SIM | A=B (F vs C+5), C=D (G vs C−5) untuk Bass/Chord1/Chord2/Pad/Phrase, drum tidak berubah; pending tanpa onset, komit di onset, re-press chord sama, release bukan onset, last-request-wins, RIGHT/LEFT memakai active, nada ditahan tetap pitch, STOP/ACMP OFF/ACMP-OFF-immediate, octave independen + MIDI OUT pitch final, out-of-range dibuang, 4 kombinasi ACMP/LEFT, drum-only + LEFT bass, ACMP ON/OFF saat playback (Rhythm tetap, harmoni senyap, tanpa burst, re-entry hanya setelah chord valid), toggle saat key ditahan, MIDI OUT ON/OFF, rapid chord/Main/STOP-START tanpa stuck/orphan |
| `tests/cpv/MasterTransposeTest` (direvisi) | UNIT (StyleSequencer asli) | semantik lama (+semitone pasca-CASM) diganti: chord target = chord langsung untuk −12..12, cermin tidak mengubah pitch, retarget root-only, NOTE_OFF berpasangan, batas MIDI, source guard |
| `app/src/test/.../TransposeOctaveAcmpRecallTest` | UNIT | planner/codec/controller: ACMP + octave, slot lama, freeze, urutan, clamp |
| `VoiceReleaseContractTest.lowerKeyRoutedToRight…` (direvisi) | UNIT | asumsi lama "ACMP ON + LEFT OFF ⇒ RIGHT" dihapus; area bawah menjadi RIGHT hanya bila ACMP dan LEFT keduanya OFF |
| Regresi: rtdiag stress/metronome, CPV JVM 235, native, LIVE/REG, Registration recall, guard | lihat §6 |

Alasan revisi tes lama: kontrak berubah secara sengaja (A1/A3). Tes guard isolasi tidak dilemahkan; guard baru `tools/test_transpose_guard.py` membatasi file/baris yang boleh berubah.

## 5. UI
- Nilai TRANSPOSE: bila pending, label berubah menjadi `NOW +x ▸ NEXT CHORD` dan nilai (permintaan) berwarna oranye; tanpa pending tampilan sama seperti sebelumnya.
- Panel suara: baris `RIGHT OCTAVE` berisi tiga grup segmented `−1 | 0 | +1` untuk R1/R2/R3; kartu suara menampilkan `· OCT ±1` hanya bila ≠ 0.
- Layar LIVE tidak diubah (dijaga guard LIVE V3.2): stepper TRANSPOSE di LIVE menampilkan nilai *requested*; indikator pending dan kontrol octave hanya ada di layar utama.

## 6. Hasil lokal (HOST/UNIT, bukan perangkat)
- rtdiag `TransposeAcmpLeftTest` 18/18 OK; rtdiag stress 17/17 OK (sebelum tes baru ditambahkan).
- CPV JVM 236/236 OK (termasuk `MasterTransposeTest` revisi dan `TransposeRositaTest`); Registration recall 106/106 OK; LIVE/REG 86/86 OK; `test_transpose_guard.py` PASS.
- **Perbandingan C→F / C→G pada ROSITA.STY Main A (CASM nyata, MIDI OUT host):** A (akor F) = B (C +5) dan C (akor G) = D (C −5) identik per channel. Rentang pitch (min..max, jumlah NOTE_ON):

| ch | C asli | A: F | B: C+5 | C: G | D: C−5 |
|---|---|---|---|---|---|
| 8 drum | 34..53 | 34..53 | 34..53 | 34..53 | 34..53 |
| 9 drum | 33..62 | 33..62 | 33..62 | 33..62 | 33..62 |
| 10 Bass | 31..43 | 24..36 | 24..36 | 26..38 | 26..38 |
| 11 Chord1 | 55..64 | 60..69 | 60..69 | 62..71 | 62..71 |
| 12 Chord2 | 55..64 | 60..69 | 60..69 | 62..71 | 62..71 |
| 13 Pad | 60..67 | 65..72 | 65..72 | 67..74 | 67..74 |
| 14 Phrase | 60..67 | 65..72 | 65..72 | 67..74 | 67..74 |

Bass F berada satu oktaf lebih rendah daripada C+5 semitone: itu keputusan register CASM untuk akor F, bukan penambahan semitone (A1). Kemiripan musikal di telinga belum diverifikasi.
- GitHub Actions + tautan APK: dilaporkan di pesan akhir.

## 7. Risiko tersisa
1. Tidak ada uji DEVICE: nada, register musikal C→F/C→G pada style nyata, dan kestabilan realtime belum diverifikasi di Redmi Pad/PSR-E343.
2. Fixture host memakai CASM sintetis (NTR=Root Trans, NTT bass/chord/…); belum dibandingkan terhadap style SFF nyata (ROSITA) untuk A/B/C/D — perlu uji perangkat dengan style nyata.
3. Style START/STOP memanggil `allNotesOff` global (baseline): key RIGHT/LEFT yang sedang ditahan saat START/STOP ikut mati; NOTE_OFF berikutnya orphan.
4. Chord onset = NOTE_ON yang menghasilkan pengenalan chord valid. Pada MultiFinger (≥3 jari) dua jari pertama LEFT (jika LEFT ON) berbunyi dengan transpose lama dan jari ketiga memicu komit.
5. Pending hanya terlihat di layar utama, bukan di layar LIVE.
6. Nada di luar 0..127 dibuang (bukan dibunyikan di oktaf lain); perubahan dari perilaku clamp lama.
7. Split point tetap 54, tidak disimpan di Registration.
8. Tidak ada pengaman IN→OUT→IN (app tidak memasukkan ulang output sendiri; loop fisik kabel tidak diuji).

## 8. Skenario uji Redmi Pad
1. Style nyata, ACMP ON: tekan C, set TRANSPOSE +5 → label `NOW +0 ▸ NEXT CHORD`, harmoni tetap C; lepas dan tekan C lagi → Style pindah ke F. Ulangi dengan −5 (G).
2. Saat pending, mainkan RIGHT C4 → tetap C4; setelah komit RIGHT C4 → F4; tahan satu nada RIGHT melewati komit lalu lepas → tidak ada nada nyangkut.
3. ACMP OFF + LEFT ON: Style drum-only, LEFT (Electric Bass) bisa dimainkan manual. ACMP ON + LEFT ON: chord terdeteksi dan LEFT tetap bunyi.
4. ACMP ON→OFF saat Style jalan: harmoni berhenti bersih, drum jalan, RIGHT tidak terpotong; OFF→ON: harmoni baru kembali setelah chord ditekan.
5. RIGHT OCTAVE R1 +1, R2 −1 (layer aktif bersamaan); ubah octave saat key ditahan; nada di ujung keyboard tidak dibunyikan di oktaf lain.
6. Simpan Registration 1–8 (ACMP, octave, voice), recall saat STOP dan saat PLAY.
7. MIDI OUT ke PSR-E343: pitch final sekali, drum tidak bergeser, tidak ada Bank/Program Change otomatis.
8. Catat: nada nyangkut, jitter tempo, lonjakan NOTE_ON saat toggle ACMP.
