# EnglishWaltz2: eksperimen NOTE OFF drum untuk MIDI OUT PSR-E343

Branch **`experiment/e343-drum-note-off`** (cabang dari `claude/e343-audit-continue-q40lxg` @ `9ae2089`). Eksperimen, **tidak di-merge**, tanpa PR. Default **LEGACY**: tanpa diaktifkan, perilaku identik dengan baseline.
Lanjutan dari `diagnostics/ENGLISH_WALTZ2_E343_DRUM_AUDIT.md`.

## 1. Bukti fisik baru (T0, pengguna, tanpa aplikasi)
Voice #189 Standard Kit 2 di PSR-E343, tuts D1 (MIDI 26, Brush Swirl) ditekan dan ditahan: **bunyi "tres" berhenti saat tuts dilepas.** Artinya Brush Swirl E343 merespons NOTE OFF dan berbunyi selama not ditahan. Ini belum membuktikan bahwa NOTE OFF yang hilang adalah satu-satunya penyebab.

## 2. Audit ulang jalur NOTE ON/OFF drum (kode baseline `9ae2089`)
| Langkah | Kode | NOTE ON drum | NOTE OFF drum |
|---|---|---|---|
| Event Style dijadwalkan | `StyleSequencer.playOnce` | diteruskan | diteruskan ke cabang `if(!s.event.isNoteOn)` |
| Ledger | `endScheduledNote()` | – (drum tidak masuk `activeTransposedNotes`) | **return false → `return@lifecycle`** (diagnostik `OFF_NO_ACTIVE_LEDGER ... oneShotDrumMayBeNormal=1`) |
| Engine internal | `audioEngine.noteOnStyleChannel` | ya | **tidak pernah** |
| MIDI OUT | `stageIo{ ... midiInputManager.sendNoteOn }` → `MidiMixerOutput.noteOn` | ya (drum map MIDI OUT diterapkan) | **tidak pernah** |
| Router | `MidiMixerOutput.noteOff` | – | seharusnya mengirim apa pun yang diterima, tetapi tidak menerima apa-apa |

**Kesimpulan:** NOTE OFF drum dibuang **di sequencer, SEBELUM MIDI OUT** (dan sebelum engine). Router sendiri tidak membuang NOTE OFF. Router menghitung NOTE ON drum di ledger `notes[ch][note]` yang tidak pernah turun. Karena itu router hanya mengirim NOTE OFF drum secara massal saat MIDI OUT dimatikan atau terputus (`releaseOwnedNotes`), sedangkan STOP mengirim CC123 di 16 channel. Keduanya perilaku lama dan tidak diubah.

## 3. Durasi asli note 26 di EnglishWaltz2 (parser produksi, PPQ 1920, 3/4)
| Section | NOTE ON | Gate (tick) | Jarak ON→ON | Jeda sebelum ON berikutnya | Overlap | Akhir terakhir / panjang section | Velocity |
|---|---|---|---|---|---|---|---|
| MainA | 12 (tiap ketukan) | 1900 | 1920 | 20 | **0** | 23020 / 23040 | 49, 39, 29 (aksen waltz) |
| MainB | 12 | 1900 | 1920 | 20 | 0 | 23020 / 23040 | 51, 41, 31 |
| MainC | 1 | 11500 | – | – | 0 | 11500 / 11520 | 25 |
| FillAA | 1 (tick 3840) | 1900 | – | – | 0 | 5740 / 5760 | 39 |
| FillBB | 2 | 1900 | 1920 | 20 | 0 | 3820 / 5760 | 49, 29 |
| FillCC | 1 | 5740 | – | – | 0 | 5740 / 5760 | 25 |

* Penulis Style membuat setiap swirl **berhenti 20 tick sebelum ketukan berikutnya**, dan tidak ada yang melewati akhir section. Artinya setiap swirl memang dirancang sepanjang satu ketukan.
* Tanpa NOTE OFF (LEGACY), di E343 setiap swirl terus berbunyi selama "tuts" dianggap ditahan (sesuai T0). Swirl baru datang tiap ketukan, sementara yang lama tidak pernah dilepas. Ini mekanisme yang masuk akal untuk "tres-tres-tres" berlebihan, tetapi **efek bunyinya di perangkat belum terbukti**. Itulah tujuan APK ini.
* Di MainC dan FillCC, Style sendiri menahan satu swirl hampir sepanjang section. Mode AUTHORED akan mempertahankan itu sesuai keinginan penulis; mode SHORT_GATE memotongnya.

## 4. Eksperimen (terisolasi, default OFF)
Objek `E343DrumNoteOff` (class `E343DrumNoteOffExperiment`, di `StyleSequencer.kt`). Kontrol: **RIGHT DIAG → baris "E343 EXPERIMENT"**.
* Chip **`E343 26 OFF: <mode>`** berganti mode LEGACY → AUTHORED → SHORT_GATE → LEGACY.
* Chip **GATE** berganti 40/80/120/200/300 ms (default 120).
* Chip **E343 OFF STATS** menampilkan statistik (juga dicatat ke debug log).

Mode tidak disimpan: setiap aplikasi dibuka, mode kembali ke LEGACY.

| Mode | Perilaku |
|---|---|
| **A. LEGACY** (default) | Perilaku sekarang. Pemeriksaan scope bernilai false, jadi NOTE ON dikirim dengan pemanggilan yang sama persis. Tidak ada NOTE OFF drum. |
| **B. AUTHORED** | NOTE OFF dikirim ke MIDI OUT pada tick NOTE OFF asli Style (1900 tick di MainA/B). |
| **C. SHORT_GATE** | NOTE OFF dikirim *gate* ms setelah NOTE ON, atau pada NOTE OFF asli jika itu datang lebih dulu. |

**Scope dikunci di kode, dan guard memeriksanya:**
* nama file Style mengandung `EnglishWaltz2`;
* channel tujuan 9 (Rhythm 2, ch 10);
* not Style 26;
* hanya output MIDI OUT;
* probe router harus ter-wire (di aplikasi: MainViewModel).

Di luar scope itu, semua mode sama dengan LEGACY: Rhythm1, not lain, Style lain (ROSITA, HardRock2), dan engine internal.

**Yang tidak diubah:**
* engine internal / BASSMIDI (eksperimen tidak pernah memanggil engine);
* velocity, Bank/Program, controller, tempo, CASM, dan file Style;
* drum note map MIDI OUT. NOTE OFF memakai nomor yang benar-benar dikirim: jika 26→85 dipetakan, yang di-OFF adalah 85.

**Pairing:**
* Satu instance terbuka per not.
* **Retrigger:** jika ON baru datang saat yang lama masih terbuka, yang lama di-OFF dulu, baru ON baru dikirim.
* **Timer gate yang basi** (generasi lama) tidak pernah menutup not yang lebih baru.
* **STOP:** OFF 26 dikirim di dalam sesi stop sebelum CC123 lama.
* **Akhir section, loop, dan transisi:** yang masih terbuka di-OFF. Di EnglishWaltz2 ini tidak pernah memotong not asli (§3).
* **Ganti mode atau ganti Style:** yang terbuka di-OFF.
* **MIDI OUT dimatikan atau terputus:** epoch router naik, router sudah melepas not-nya sendiri, jadi eksperimen tidak mengirim apa-apa (tidak ada OFF yatim).
* **NOTE ON yang tidak tertulis** (channel di-mute, MIDI OUT mati) tidak dilacak.
* **Tidak pernah All Notes Off**, baik global maupun per channel.

Batasan yang diketahui: jika channel di-mute/solo saat swirl berbunyi, router lama melepas semua not channel itu (`releaseOwnedNotes`). Eksperimen lalu melihat jumlahnya 0 dan tidak mengirim OFF tambahan.

## 5. Perubahan kode (diff terhadap `9ae2089`)
| File | Perubahan |
|---|---|
| `yamahaarranger/arranger/StyleSequencer.kt` | Objek eksperimen (baru, di akhir file) + 4 hook: (1) NOTE ON drum ch 10 dibungkus `E343DrumNoteOff.noteOn`, di luar scope = pemanggilan lama; (2) NOTE OFF Style yang selama ini dibuang diteruskan ke `authoredOff`, hanya jika ada not terbuka; (3) `releaseAll` setelah setiap `playOnce`; (4) `releaseAll` di dalam `stopSession` sebelum CC123. 2 baris lama diganti. |
| `midi/MidiMixerOutput.kt` | Tambahan saja: `epoch` (naik saat enable/connect berubah) + `noteProbe()` baca-saja. |
| `midi/MidiInputManager.kt` | Tambahan saja: `midiOutNoteProbe()`. |
| `arranger/ArrangerBrain.kt` | Tambahan saja: saat `loadStyle`, nama Style dicatat dan not yang terbuka ditutup. |
| `ui/MainViewModel.kt` | Tambahan saja: wiring probe + 4 fungsi chip. |
| `ui/RightVoiceDiagOverlay.kt` | Tambahan saja: satu baris chip. |
| `tests/fixtures/englishwaltz2/EnglishWaltz2.S519.prs` | Salinan byte-identik file pengguna (SHA-256 `90c9efb4…94a3`), untuk test CI. Style asli tidak diubah. |

Guard `tools/test_e343_drum_noteoff_guard.py` memeriksa:
* hanya file di atas yang berubah, dan selain StyleSequencer hanya ada tambahan;
* StyleSequencer kehilangan tepat 2 baris yang diganti;
* native, BASSMIDI, CASM, audio, Style, dan fixture lain byte-identik;
* default LEGACY dan scope EnglishWaltz2/ch10/26 ada di kode;
* objek eksperimen tidak memakai engine maupun All Notes Off;
* fixture identik dengan file pengguna.

## 6. Hasil test (lokal, host)
| Suite | Hasil |
|---|---|
| CPV JVM (`tools/test_cpv.py --suite jvm`), termasuk **`E343DrumNoteOffUnitTest`** (9 test: LEGACY tidak tersentuh, scope, OFF memakai nomor setelah drum map, Rhythm1 dan not lain tidak pernah di-OFF, retrigger, releaseAll/ganti mode, disconnect/disable tanpa OFF yatim, gate + timer basi, batas gate) | **OK 330** (321 sebelumnya + 9) |
| **`E343DrumNoteOffRtTest`** pada EnglishWaltz2 (rantai produksi, tempo 280) | **OK (9 tests)**; test ROSITA di-skip di run ini |
| **`E343DrumNoteOffRtTest`** pada ROSITA | **OK**: test ROSITA lulus, test khusus EW2 di-skip |
| `EnglishWaltz2E343DrumTest` (audit sebelumnya, LEGACY) | OK (2) |
| `E343MidiOutAuditTest` ROSITA / HardRock2 | OK (2) / OK (2) |
| rtdiag `--unit` / `MidiOutOctaveTest` / `MixerKeysRtTest` / `MidiInSplitRtTest` | OK 52 / 8 / 8 / 7 |
| `StyleEditorV2Test` ROSITA | OK (6) |
| JVM `test_r22_regressions --suite live` / `test_registration_recall` | OK 86 / PASS |
| Guard `test_e343_drum_noteoff_guard.py` | PASS |

Pengukuran utama dari `E343DrumNoteOffRtTest` (host, tempo 280 BPM; 1900 tick ≈ 212 ms):
* **LEGACY** MainA: 21 NOTE ON di ch10 (8 kali not 26), **0 NOTE OFF**.
* **AUTHORED** MainA: gate ON→OFF not 26 = **206, 212, 213, 213, 211, 212, 212 ms** (target 212). OFF hanya untuk 26.
  - Aliran NOTE ON (not, velocity, urutan) sama dengan LEGACY.
  - Bank/Program/controller sama.
  - Event engine ch10 sama.
  - Rhythm1 0 OFF.
* **SHORT_GATE 40 ms** MainA: gate = **44, 40, 40, 40, 40, 40, 40 ms**; `offGate=7`.
* **STOP** saat MainC menahan 26: OFF 26 terkirim **sebelum** CC123 lama; `offRelease=1`; ledger kosong.
* **Transisi** MainC→MainA: swirl MainC ditutup saat ganti section (857 ms), lalu not MainA berpasangan normal (203–213 ms).
* **MIDI OUT dimatikan lalu dinyalakan** saat 26 ditahan: `dropped=1`, `offRelease=0`, 0 OFF dari eksperimen setelah dinyalakan kembali.
* **Ganti mode** AUTHORED→LEGACY saat ditahan: OFF 26 terkirim dalam 0 ms.
* **IntroC** Rhythm1: 43 NOTE ON, 0 NOTE OFF (tidak tersentuh).
* **ROSITA** dalam AUTHORED dan SHORT_GATE: 0 NOTE OFF drum, `tracked=0`, aliran NOTE ON ch9/ch10 sama dengan LEGACY.

Ini bukti logika software di host, **bukan** bukti bunyi E343.

## 7. CI / APK
Workflow `.github/workflows/style-voice-editor.yml` diperluas untuk `experiment/e343-drum-note-off`: guard sendiri, langkah test eksperimen (EW2 + ROSITA + audit EW2), dan laporan ini sebagai `DEVICE-TEST.txt` di artifact APK.

CI_RESULT

## 8. Tes di PSR-E343 (yang dibutuhkan dari pengguna)
1. Pasang APK dan hubungkan E343 lewat USB MIDI OUT. Muat **EnglishWaltz2.S519**, mainkan **MainA**.
2. **LEGACY** (default): dengarkan "tres-tres-tres" sebagai referensi.
3. RIGHT DIAG → **`E343 26 OFF`** → **AUTHORED**. Dengarkan MainA/MainB: apakah "tres" berlebihan hilang dan swirl terdengar satu ketukan dengan aksen 1-2-3?
4. Ganti ke **SHORT_GATE**, coba GATE 40/80/120/200/300 ms. Catat nilai yang paling enak dan yang merusak irama.
5. Uji juga MainC (swirl panjang yang memang ditulis Style), Fill, STOP di tengah, dan ganti section.
6. Tekan **E343 OFF STATS** dan **MIDI OUT STATE**, lalu kirim teksnya beserta penilaian Anda per mode.
7. Kembalikan ke LEGACY setelah selesai (atau tutup aplikasi; mode tidak disimpan).

Keputusan setelah itu: jika AUTHORED menghilangkan masalah tanpa merusak irama, langkah berikutnya adalah memperluas dengan hati-hati, juga di branch eksperimen terpisah. Kandidatnya: not 28 Brush Tap Swirl (juga ditahan oleh Style), lalu opsi per Style. **Tidak ada perluasan ke seluruh drum atau seluruh Style tanpa bukti.**
