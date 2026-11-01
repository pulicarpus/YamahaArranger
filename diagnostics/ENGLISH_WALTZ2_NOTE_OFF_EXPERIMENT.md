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
Objek `E343DrumNoteOff` (class `E343DrumNoteOffExperiment`, di `StyleSequencer.kt`). Kontrol (sejak perbaikan UI §8): **RIGHT DIAG → tombol besar `E343 EXPERIMENT ▸ <mode>`** membuka halaman layar penuh:
* tiga tombol mode **LEGACY / AUTHORED / SHORT_GATE** (yang aktif berwarna hijau);
* lima tombol gate **40/80/120/200/300 ms**;
* baris "Active mode", status scope, dan statistik NOTE ON/OFF yang diperbarui 2× per detik;
* RESET STATS, serta CLOSE atau tombol Back Android.

(Chip lama "E343 26 OFF / GATE" di versi `95f6600` ada di kode tetapi tidak terlihat; lihat §8.)

Mode dan gate disimpan sejak checkpoint §11 (tanpa file atau file rusak → LEGACY + 120 ms). Sebelum checkpoint itu (APK `f6b732f` dan lebih lama), mode kembali ke LEGACY setiap aplikasi dibuka.

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
| `ui/MainViewModel.kt` | Tambahan saja: wiring probe, fungsi chip, `e343ExperimentUi/e343SetMode/e343SetGate/e343ResetStats`. |
| `ui/RightVoiceDiagOverlay.kt` | Layout diperbaiki (§8): CLOSE dan tombol E343 di depan, semua baris bisa digeser, panel bisa di-scroll; dialog E343 layar penuh. Semua aksi lama tetap ada (guard). |
| `ui/E343ExperimentPanel.kt` (baru) | Halaman eksperimen Compose murni, juga di-compile oleh `tools/ui_preview` untuk tes UI desktop. |
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

* Run **#45** [`41297070679`](https://github.com/pulicarpus/YamahaArranger/actions/runs/41297070679) (`8d9a9e8`): job host **dibatalkan pada batas 60 menit** setelah 16 langkah hijau, di menit ke-3 langkah eksperimen; job android tidak berjalan.
* Run **#46** [`41302348294`](https://github.com/pulicarpus/YamahaArranger/actions/runs/41302348294) (`95f6600`, timeout host 100 m): **host success (59 m 58 s), android success (3 m 27 s)**. APK [`YamahaArranger-STYLEVOICE-95f6600…`](https://github.com/pulicarpus/YamahaArranger/actions/runs/41302348294/artifacts/13082949489). APK inilah yang diuji pengguna di Redmi Pad; kontrol E343-nya tidak bisa dijangkau (§8).

## 8. Perbaikan UI RIGHT DIAG (laporan Redmi Pad, landscape)
**Gejala (pengguna, APK run #46 / `95f6600`):** hanya RESET STATS, LOG PROBE, SF2 PITCH/FX, dan MIDI OUT STATE yang terlihat. Tombol lain terpotong atau bertumpuk, panel tidak bisa digeser, dan kontrol E343 tidak ditemukan.

**Kontrol E343 memang ada di APK itu.** Job android run #46 berhasil, dan `95f6600` berisi `E343DrumNoteOff` beserta chip-nya (commit `8d9a9e8`). Masalahnya murni di layout, dan bisa dibuktikan dari kode `RightVoiceDiagOverlay.kt` @ `95f6600`:
1. Panel memakai `width(560.dp)` + `heightIn(max = 330.dp)`, dan **Column luar tidak bisa di-scroll**. Hanya blok teks paling bawah yang punya `verticalScroll`.
2. Setiap baris kontrol adalah `Row` biasa **tanpa scroll atau wrap**. Baris 1 berisi judul (~240 dp) ditambah 6 chip, lebih lebar dari 560 dp. Anak-anak terakhir (CLEAR OUT NOTES, **CLOSE**) tidak kebagian lebar. Ini persis empat tombol yang terlihat di laporan.
3. Baris E343 diawali `Text` panjang ("E343 EXPERIMENT (EnglishWaltz2, …)"). Teks itu mengambil seluruh 560 dp dan membungkus ke beberapa baris, sehingga chip mode/GATE/STATS sesudahnya mendapat lebar 0 dan **tidak terlihat**.

Ini bukan masalah orientasi atau state: lebar panel tetap 560 dp di layar mana pun.

**Perbaikan:**
* `RightVoiceDiagOverlay.kt`:
  - **CLOSE** dan tombol besar **`E343 EXPERIMENT ▸ <mode>`** dipindah ke depan.
  - Setiap baris chip diberi `horizontalScroll`, dan seluruh panel diberi `verticalScroll`. Scroll dalam di bagian bawah dihapus, karena scroll bersarang searah akan crash.
  - Semua aksi lama tetap ada (diperiksa guard). Chip E343 OFF STATS dipindah ke baris kedua.
* `E343ExperimentPanel.kt` (baru, Compose murni tanpa ViewModel/Android): halaman layar penuh.
  - Header tetap dengan **CLOSE** yang selalu terlihat; isi di-scroll vertikal.
  - Tombol mode dan gate berukuran **≥ 52 dp**, dengan pilihan aktif ditandai.
  - Baris "Active mode", statistik, dan RESET STATS.
  - **Status scope**: jika Style bukan EnglishWaltz2, tertulis "NOT APPLICABLE to the current Style '…' … plays exactly as LEGACY".
* Halaman ini dibuka sebagai `Dialog` layar penuh (`E343ExperimentDialog`), jadi tidak tertutup HOME, VOICE, transport, atau keyboard. Tombol Back Android menutupnya.
* Tombol memanggil `E343DrumNoteOff.setModeByName` dan `setGate`, yaitu objek yang sama yang dipakai sequencer. State halaman dibaca dari `E343DrumNoteOff.snapshot()` (state asli mesin, bukan salinan UI), diperbarui 2× per detik.

**Tes:**
* `E343DrumNoteOffUnitTest` (+2): nama tombol → mode mesin; nama tak dikenal tidak mengubah apa-apa; pindah ke LEGACY menutup not 26 yang terbuka; snapshot = state mesin (mode, gate, scope, router, statistik). CPV JVM **332 OK** (lokal).
* `tools/ui_preview/src/test/kotlin/E343ExperimentPanelTest.kt` (desktop Compose, meng-compile file panel yang sama dengan aplikasi). Ukuran 1333×800 dp (Redmi Pad 10.61" landscape), 1280×800 dp (Redmi Pad SE/Pro), dan 800×360 dp (uji beban). Yang diperiksa:
  - CLOSE terlihat tanpa scroll;
  - 3 mode + 5 gate + RESET + statistik dapat dijangkau dan terlihat;
  - tinggi tombol ≥ 48 dp;
  - klik tombol mengubah "Active mode";
  - Style lain berlabel NOT APPLICABLE;
  - PNG dirender sebagai bukti.

  Tes ini **hanya bisa jalan di CI** (Maven/Google tidak bisa diakses dari sesi ini). Hasilnya di §9.
* Overlay RIGHT DIAG sendiri bergantung pada MainViewModel, jadi tidak dirender di desktop. Perbaikannya dibuktikan lewat kode dan guard, **belum dilihat di Redmi Pad**.

## 9. Pipeline build: kenapa lambat, dan jalur Quick APK
**Bukti waktu (run #46 `41302348294`, `95f6600`, dari timestamp API job dan log):**
| Tahap | Waktu terukur | Isi |
|---|---|---|
| host: native resolver/Policy C/drum/ROSITA | 8 m 30 s | harness C++ (U01, R23, policy, CPV native, …) |
| host: JVM CPV + LIVE/REG | 2 m 47 s | CPV 330 tests (74 s), LIVE/REG 4× (~43 s tiap compile) |
| host: Realtime diag + metronome | **24 m 08 s** | rtdiag 52 tests 302 s; **`ChordChangeStutterTest` 1120 s (18,7 m)**; rtdiag native 24 s |
| host: MIDI OUT voice cache | **16 m 40 s** | unit 42 s; `SectionTransitionGapTest`+P1+ordered I/O HR2 378 s; ROSITA 356 s; burst HR2 111 s; burst ROSITA 113 s |
| host: velocity / octave / mixer / split | 42 / 44 / 44 / 43 s | masing-masing satu run rtdiag kecil (7–10 tests) |
| host: E343 audit | 1 m 36 s | 2 run × 48 s (2 tests) |
| host: NOTE OFF experiment + audit EW2 | 3 m 11 s | 59 + 50 + 82 s |
| **host total** | **59 m 58 s** | |
| android (setelah `needs: host`) | **3 m 27 s** | NDK 25 s, SDK 7 s, **compile APK 2 m 35 s**, verify 1 s |

**Akar penyebab:**
1. **Dependensi job:** `android` memakai `needs: host`, jadi APK baru keluar setelah semua tes host selesai (~60 m). Padahal build APK-nya sendiri hanya 3,5 m.
2. **Tes waktu-nyata:** `ChordChangeStutterTest` dan `SectionTransitionGapTest` memutar Style secara real time (puluhan skenario × 6 percobaan). Waktunya dibatasi jam dinding, bukan CPU. Tes ini diwarisi branch e343 dari kondisi `if:` eksperimen-eksperimen sebelumnya (P1, voice cache).
3. **Kompilasi ulang:** setiap pemanggilan `test_rtdiag.py`/`test_cpv.py` meng-compile ulang rantai Kotlin produksi dari nol dengan `kotlin-compiler.jar`. Run 2 tes saja butuh 42–48 s. Ada 21 pemanggilan, kira-kira **14 menit kompilasi berulang**.
4. Tidak ditemukan proses menggantung. Semua tahap selesai OK, dan run #45 dibatalkan murni karena batas 60 m.

**Jalur Quick APK** (`.github/workflows/e343-quick-apk.yml`, "Quick Experimental APK (E343)"). Ada tiga job **paralel dan independen** plus satu job laporan:
* **apk**: checkout tepat `GITHUB_SHA`, JDK 17, NDK r26d, Gradle 8.7, SDK yang di-pin.
  - Pemeriksaan keselamatan esensial: guard isolasi dan ancestry baseline.
  - `assembleDebug`, verifikasi tanda tangan, kedua ABI, dan provenance `source_sha == GITHUB_SHA`.
  - Pemeriksaan **isi dex**: kelas `E343DrumNoteOffExperiment`, `E343DrumNoteOffSnapshot`, `E343ExperimentContent`, `E343ExperimentDialog`, tag `e343.mode.`, dan label `E343 EXPERIMENT` harus ada.
  - Artifact **`E343-QUICK-APK-<branch>-<sha>`** berisi APK bernama dengan SHA, BUILD-INFO (branch, commit, nomor run, sha256 APK), dan laporan ini.
* **quick-tests**: guard, CPV JVM (termasuk unit test eksperimen), `E343DrumNoteOffRtTest` pada EnglishWaltz2, ROSITA, dan HardRock2.
* **ui-test**: tes UI desktop + PNG.
* **report**: tabel hasil, dan **merah jika salah satu job gagal**. APK tetap ter-upload walau tes merah, tetapi kegagalan terlihat jelas.

Cache yang dipakai hanya dependensi Gradle (berdasarkan file Gradle) dan JAR uji (berdasarkan JSON, tetap diverifikasi sha256). Output build tidak pernah di-cache, jadi tidak ada artefak yang tercampur antar-commit.

**Full Validation tidak diubah isinya** (`style-voice-editor.yml`, semua tes tetap). Perubahannya hanya: timeout host 100 m (`95f6600`) dan tambahan run HardRock2 di langkah eksperimen. Workflow ini berjalan terpisah pada push yang sama. Belum diparalelkan, karena memecah job host lama butuh restrukturisasi yang lebih besar; itu usulan terpisah.

**Hasil Quick APK:**
* Run #1 [`41316193228`](https://github.com/pulicarpus/YamahaArranger/actions/runs/41316193228) (`e606786`): **gagal** karena dua penyebab dari log.
  - Upload artifact ditolak karena nama artifact berisi `/` dari nama branch. APK-nya sendiri sudah terbangun dan lolos verifikasi.
  - `:compileKotlin` di `tools/ui_preview` gagal sebelum tes jalan, karena `E343Compat` dan `StyleEditorNavigation` belum ada di daftar sumber renderer.
  - Diperbaiki di `f6b732f`.
* Run #2 [`41316937048`](https://github.com/pulicarpus/YamahaArranger/actions/runs/41316937048) (`f6b732f`, workflow_dispatch): **semua hijau**.
  - Total **3 m 42 s**: apk 3 m 30 s (compile 2 m 31 s), quick-tests 2 m 42 s, ui-test 1 m 32 s (tes UI desktop lulus), report hijau.
  - Artifact `E343-QUICK-APK-experiment-e343-drum-note-off-f6b732f3e1718fbfa5748c41c4449aa6388cd1fd`. **APK inilah yang diuji pengguna di Redmi Pad + PSR-E343 (§11).**
* Pembanding: Full Validation untuk menghasilkan APK membutuhkan ~63 menit (run #46).


## 10. Tes di PSR-E343 (yang dibutuhkan dari pengguna)
1. Pasang APK dan hubungkan E343 lewat USB MIDI OUT. Muat **EnglishWaltz2.S519**, mainkan **MainA**.
2. **LEGACY** (default): dengarkan "tres-tres-tres" sebagai referensi.
3. RIGHT DIAG → **`E343 EXPERIMENT ▸`** → tombol **AUTHORED**. Baris "Active mode" harus berubah, dan status scope harus hijau ("ACTIVE on EnglishWaltz2"). Dengarkan MainA/MainB: apakah "tres" berlebihan hilang dan swirl terdengar satu ketukan dengan aksen 1-2-3?
4. Ganti ke **SHORT_GATE**, coba GATE 40/80/120/200/300 ms. Catat nilai yang paling enak dan yang merusak irama.
5. Uji juga MainC (swirl panjang yang memang ditulis Style), Fill, STOP di tengah, dan ganti section.
6. Kirim angka statistik di halaman E343 (atau RIGHT DIAG → **E343 OFF STATS** / **MIDI OUT STATE**), beserta penilaian Anda per mode.
6b. Kontrol: muat ROSITA, buka halaman E343. Harus tertulis **"NOT APPLICABLE to the current Style 'ROSITA…'"**, dan ROSITA harus terdengar sama di semua mode.
7. Mode dan gate disimpan (sejak checkpoint §11). Untuk kembali ke perilaku lama, pilih **LEGACY** di halaman E343.

Keputusan setelah itu: jika AUTHORED menghilangkan masalah tanpa merusak irama, langkah berikutnya adalah memperluas dengan hati-hati, juga di branch eksperimen terpisah. Kandidatnya: not 28 Brush Tap Swirl (juga ditahan oleh Style), lalu opsi per Style. **Tidak ada perluasan ke seluruh drum atau seluruh Style tanpa bukti.**

## 11. Checkpoint: SHORT_GATE 120 ms untuk EnglishWaltz2 (hasil perangkat nyata)
**Baseline:** `9ae2089` (audit E343 + laporan EnglishWaltz2). Eksperimen dimulai di `8d9a9e8`; APK yang diuji: `f6b732f` (Quick run #2). Checkpoint ini: lihat commit sesudah `f6b732f` di branch ini.

**Hasil uji fisik pengguna** (Redmi Pad + PSR-E343 sebagai tone generator lewat USB MIDI, EnglishWaltz2.S519):
| Mode | Hasil di PSR-E343 |
|---|---|
| LEGACY | "tres-tres-tres" masih mengganggu |
| AUTHORED | terdengar sama seperti LEGACY |
| **SHORT_GATE 120 ms** | **drum normal** |

Halaman E343 EXPERIMENT bisa dipakai di Redmi Pad. PSR-E343 merespons NOTE OFF pada Brush Swirl (not 26). Pengguna memutuskan **tidak** memperluas ke EnglishWaltz3, dan keputusan itu dihormati.

**Kenapa 120 ms, dan kenapa AUTHORED tidak membantu** (dihitung dari file Style; tempo meta 666666 µs/ketukan = **90 BPM**, birama 3/4):
* AUTHORED mematikan swirl setelah 1900/1920 ketukan = **660 ms**, lalu swirl berikutnya mulai **6,9 ms** kemudian. Bagi telinga, ini sama dengan LEGACY: swirl tetap berbunyi hampir terus-menerus.
* SHORT_GATE 120 ms = **18 % dari satu ketukan**. Swirl menjadi aksen pendek per ketukan (velocity 49/39/29 tetap seperti ditulis Style), dengan jeda jelas sebelum ketukan berikutnya. Irama waltz tetap utuh.
* 120 ms adalah nilai yang diuji dan dinilai normal oleh pengguna. Nilai gate lain belum dinilai di perangkat.

**Cakupan tepat** (dikunci di kode dan diperiksa guard):
* nama Style mengandung `EnglishWaltz2`;
* Rhythm 2 (channel 10), not Style 26 (Brush Swirl);
* output MIDI OUT saja.

Yang **tidak** berubah: Rhythm1, not drum lain, drum note map (OFF memakai nomor yang benar-benar dikirim), Bank Select/Program Change, velocity, tempo, CASM, BASSMIDI internal, file Style, dan Style lain termasuk **EnglishWaltz3**.

**Pengamanan pilihan (perubahan checkpoint ini):**
* Pilihan mode dan gate **disimpan** di `e343_drum_noteoff.txt`, satu folder dengan `keyboard_input.txt`. Penulisan atomik (file tmp lalu rename).
* Isinya hanya `mode=` dan `gate=`. Scope **tidak pernah** disimpan di file.
* Pemulihan ketat: file tidak ada, tak terbaca, lebih dari 4 KB, atau isinya tidak valid → **LEGACY** + 120 ms. Gate di luar 40/80/120/200/300 → 120 ms.
* **Default global tetap LEGACY.** Instalasi baru, atau tanpa file, tidak berubah sama sekali.
* Setelah pengguna memilih SHORT_GATE (gate 120 ms adalah default), pilihan itu tetap aktif setelah aplikasi dibuka ulang. Pilihan ini **hanya** berlaku untuk EnglishWaltz2 (scope di kode). Untuk Style lain, halaman E343 menulis "NOT APPLICABLE".
* Ketiga mode tetap bisa dipilih untuk perbandingan.
* Halaman E343 kini menampilkan catatan hasil uji ("Verified on PSR-E343 (user test): SHORT_GATE 120 ms sounds normal …").

**Rollback ke LEGACY:** RIGHT DIAG → **E343 EXPERIMENT ▸** → **LEGACY**. Pilihan itu langsung berlaku: not 26 yang sedang berbunyi dimatikan sekali, lalu perilaku kembali ke baseline. Pilihan LEGACY juga ikut tersimpan. Rollback total: hapus `e343_drum_noteoff.txt`, atau pasang APK baseline.

**Audit implementasi (bukti otomatis, host, rantai produksi, tempo uji 280 BPM):**
| Kontrak | Bukti |
|---|---|
| SHORT_GATE 120 ms memotong not 26 di MIDI OUT | `shortGate120…`: gate **124, 120, 120, 120, 120, 120, 120 ms**; `offGate=7`; lebih awal dari AUTHORED (~212 ms pada tempo uji) |
| Pairing ON/OFF, tidak ada stuck note | `assertPaired` pada setiap tes (ON tidak pernah tumpang tindih, setiap ON ditutup, ledger kosong setelah STOP); retrigger ditutup lebih dulu (unit test) |
| Transisi section / STOP / disconnect / ganti mode | OFF saat transisi (MainC→MainA), OFF saat STOP sebelum CC123 lama, `dropped=1` dan 0 OFF yatim setelah MIDI OUT dimatikan lalu dinyalakan, OFF dalam 0 ms saat ganti mode |
| LEGACY identik dengan baseline | `legacyIsIdenticalToTheUnwiredBaselinePath…`: byte MIDI OUT ch5–16 sama dengan jalur tanpa eksperimen; 0 NOTE OFF drum |
| BASSMIDI internal tidak terpengaruh | event engine drum (ON/OFF, not, urutan) identik dengan LEGACY; engine tidak pernah menerima NOTE OFF drum |
| ROSITA / HardRock2 tidak terpengaruh | AUTHORED, SHORT_GATE 40 ms, dan **SHORT_GATE 120 ms**: 0 NOTE OFF drum, `tracked=0`, aliran NOTE ON sama |
| EnglishWaltz3 tidak terpengaruh | file EW3 tidak ada di repo; data EW2 dengan nama `EnglishWaltz3.S520.prs` + SHORT_GATE 120: not 26 dimainkan 8×, **0 NOTE OFF**, `tracked=0`; unit test nama lain juga di luar scope |
| MIDI IN / split / ACMP / LEFT tidak berubah | input sama (akor LEFT C lalu Am, not RIGHT): event engine ch1–4 dan byte MIDI OUT ch1–4 identik, nada ACMP sama; `MidiInSplitRtTest` OK (7), `TransposeAcmpLeftTest` OK (18) |
| Penyimpanan aman | unit test: round-trip, enam input rusak → LEGACY, gate 999 → 120, file tanpa scope |

Hasil lokal checkpoint ini:
* CPV JVM **334 OK**;
* `E343DrumNoteOffRtTest` **OK 13** pada EnglishWaltz2, HardRock2, dan ROSITA (tes khusus Style tertentu melewati diri di Style lain);
* `MidiInSplitRtTest` OK 7, `TransposeAcmpLeftTest` OK 18;
* guard PASS.

Hasil CI: lihat laporan akhir di percakapan, atau ringkasan run Quick APK untuk commit ini.

**Risiko yang belum diuji:**
* Hanya Redmi Pad + PSR-E343 + EnglishWaltz2. Modul MIDI lain belum diuji. Gate selain 120 ms belum dinilai di perangkat.
* Pengujian manusia dilakukan pada section yang dimainkan pengguna. Belum ada konfirmasi di perangkat untuk semua section (MainC dan FillCC berisi swirl panjang; Intro/Ending tidak memakai not 26).
* Timing gate memakai timer terpisah (presisi ms di host). Jitter pada perangkat Android belum diukur.
* Mute/solo channel 10 saat swirl berbunyi dilepas oleh router lama (`releaseOwnedNotes`). Kasus ini belum diuji dengan telinga.
* Penyimpanan baru diuji lewat unit test codec. Siklus tutup/buka aplikasi di perangkat belum diuji.
* Full Validation (~60 m) berjalan terpisah. Statusnya dilaporkan tersendiri dan **bukan** syarat APK eksperimen.

