# PSR-E343 MIDI OUT — FILL-IN & CHORD TRANSITION ROOT CAUSE ANALYSIS

Mode: **READ-ONLY SOURCE AUDIT + REPORT-ONLY COMMIT.** Tidak ada perubahan kode produksi, tes repo, fixture, atau workflow, dan tidak ada APK. Simulasi dijalankan dengan harness sementara di luar working tree.

Label bukti:
- **SOURCE** = fakta yang dibaca dari kode atau data parser;
- **HOST** = hasil simulasi di host (rantai produksi + synth tiruan + port USB palsu);
- **PHYSICAL** = observasi pengguna di perangkat;
- **HYPOTHESIS** = belum terverifikasi.

---

## 1. Executive Summary

- **Kandidat akar masalah terkuat (VERIFIED di SOURCE + HOST): not melodi Fill yang kehilangan NOTE OFF terbawa ke Main berikutnya.**
  - Parser Style hanya menyimpan event dengan `startTick <= tick < endTick` per section (`style_parser.cpp:205`). NOTE OFF dari not yang berbunyi sampai atau melewati akhir section **dibuang** dari section itu.
  - Hasilnya, NOTE ON tanpa NOTE OFF: EnglishWaltz2 32 not (FillCC 7, FillDD 3, EndingC 11, MainC/D masing-masing 3, …), HardRock2 10, ROSITA 3 per Main/Fill (ch14, plus ch12 di FillCC/DD). Love Song: 0.
  - Saat Fill **berakhir alami** dan Main dari antrean dimulai, sequencer **tidak melepas** not yang masih aktif (`StyleSequencer.kt:760-772`). Pelepasan hanya ada pada transisi yang **memutus** section (`:726-735`) dan pada STOP (`:801`).
  - Di HOST (EnglishWaltz2, Main B → FillCC → Main C), empat not Chord 2 dari Fill masih berbunyi saat Main dimulai: selama 248/499/749 ms, dan **3044 ms** sampai ganti akor. Ganti akor me-*retarget* not itu (NOTE OFF + NOTE ON di pitch baru), jadi not tetap menggantung di pitch akor baru. Hanya STOP (CC123) yang membersihkan semuanya.
  - Ini **cocok** dengan observasi pengguna: suara akor lama tetap ada, hilang sendiri, langsung berhenti saat STOP, dan paling sering pada Style dengan Fill.
- **Tidak terbukti di HOST:**
  - (H1) aplikasi terlambat melepas not saat ganti akor: retarget terjadi 15–34 ms setelah tombol ditekan (termasuk settle 15 ms), jumlah NOTE OFF = NOTE ON, dan 0 not akor lama tersisa lebih dari 150 ms;
  - (H2) backlog di aplikasi: tidak ada antrean; setiap pesan dikirim seketika.
- **MIDI OUT = BASSMIDI** untuk event not. Jumlah ON/OFF per channel identik di semua skenario. Jadi kalau H3 terjadi di E343, **BASSMIDI menerima not menggantung yang sama**. Pengguna belum membandingkannya (fakta fisik 8), dan laporan ini **tidak** menyatakan BASSMIDI bebas masalah.
- **GO** untuk eksperimen patch pertama (P1, §14), dengan uji fisik pembanding lebih dulu atau bersamaan (§17).

## 2. Repository Baseline

| Item | Nilai |
|---|---|
| Branch aktif | `feature/midi-song-v1` |
| HEAD awal audit | `f82061750f41d35487bd73dfa6522dca2e8f488e` (= `origin/feature/midi-song-v1`) |
| Working tree | bersih dari perubahan tracked; dua file untracked dari audit sebelumnya (`diagnostics/PSR_E343_MIDI_OUT_DEEP_AUDIT.md`, `tests/rtdiag/E343MidiOutDeepAuditRtTest.kt`) tidak disentuh dan tidak di-commit |
| Commit kode Device Routing V1 yang diuji | `b81cb76` (APK Quick run `42010987323`, Full run `42010987581`). `58e716f` dan `f820617` sesudahnya hanya dokumen, jadi kode HEAD = kode APK |
| Baseline audit Codex `18081d2` | = HEAD `origin/main`, **bukan leluhur** branch ini. Main belum punya router `MidiMixerOutput` (+204 baris di branch), adapter E343, Device Routing, maupun perubahan `StyleSequencer` (+1214 baris). Temuan audit lama **tidak** dipakai tanpa verifikasi ulang |
| Audit sebelumnya di branch ini | `diagnostics/PSR_E343_MIDI_OUT_DEEP_AUDIT.md` (untracked). **Koreksi:** di audit itu, akor uji C/F/G (48–62) sebagian berada **di atas split point 54** (`ArrangerBrain.kt:69`), sehingga tidak sampai ke ACMP. Kesimpulannya tentang ganti akor tidak berlaku. Temuan lainnya (NOTE ON ulang dari CASM, ledger drum, RIGHT/LEFT ikut ke MIDI OUT) tetap berlaku karena tidak bergantung pada ganti akor |

## 3. Physical Test Evidence (PHYSICAL, dari pengguna)

Perangkat: Redmi Pad (Helio G99) + PSR-E343 lewat USB MIDI, BASSMIDI internal, Device Routing V1.

1. Pemilihan MIDI IN, MIDI OUT, dan AUDIO OUT berfungsi.
2. Style lewat MIDI OUT E343: sebagian suara bertumpuk dan terlambat.
3. Setelah akor diganti, sebagian suara akor lama masih berbunyi.
4. Suara akor baru terlambat mengikuti.
5. Nada yang menggantung akhirnya hilang sendiri.
6. STOP langsung menghentikan suara.
7. Paling sering pada Style dengan Fill In ramai.
8. **Belum ada** perbandingan langsung dengan BASSMIDI internal pada Style dan Fill yang sama.

Semua ini observasi, bukan bukti bahwa NOTE OFF hilang. Fakta 6 tidak membuktikan NOTE OFF normal; STOP mengirim CC123 (All Notes Off) ke 16 channel (`MidiInputManager.kt:297`), dan itu mematikan not menggantung juga.

## 4. MIDI OUT Architecture (SOURCE)

| Tahap | File : fungsi : baris | Thread / waktu | NOTE ON / NOTE OFF |
|---|---|---|---|
| Parser Style | `style_parser.cpp:205` (pembagian section) | saat Style dimuat | event `tick ∈ [start, end)` per section; **NOTE OFF di `tick >= end` dibuang** dari section itu |
| CASM/NTT | `CasmNoteTransformer.transform`, `selectPolicy` | thread sequencer | nada sumber → nada keluaran per akor |
| Penjadwal section | `StyleSequencer.playOnce` `:1288-1372` | coroutine `Dispatchers.Default`; waktu = `tickDeadlineNanos(tick)` dari master clock; menunggu per event (`waitForNextBarOrEvent`) | event disortir per tick, ON sebelum OFF (`thenBy{isNoteOn.not()}` → **ON dulu** pada tick yang sama); tick dibungkus `% sectionLength` (`:1305`) |
| NOTE ON | `:1494-1529` | thread sequencer, setelah monitor dilepas | `stageIo { engine.noteOnStyleChannel; midiInputManager.sendNoteOn }` (satu perintah berurutan per (ch, pitch)); masuk ledger `activeTransposedNotes["src:note"]` hanya untuk part melodi dengan CASM (`:1511-1513`) |
| NOTE OFF | `:1462-1473` → `endScheduledNote` `:983` → `chordOff` `:956` | thread sequencer | hanya kalau kunci sumber ada di ledger; drum tidak pernah (by design) |
| Re-strike tombol sumber yang sama | `:1487-1491` `SCHEDULED_REPLACE_OFF` | thread sequencer | OFF lama, lalu ON baru |
| Ganti akor | `ArrangerBrain.onChordChanged` `:556` → settle **15 ms** (`:110`, `:568-584`) → `applyChordNow` `:587` → `sequencer.currentChord` setter `:91-106` → `handleChordChange` `:863` → `retargetActive` → `updateHeldPitch` `:918` | MIDI IN / Main → coroutine settle → setter | RTR 1/2/3/4: OFF nada lama + ON nada baru; RTR 0: lepas; nada sama: tidak ada event |
| Transisi yang memutus | `startPlayback` loop `:717-753` | thread sequencer | **melepas semua not ledger** (`:726-735`) |
| Section dari antrean (berakhir alami) | `:760-772` | thread sequencer | **tidak melepas apa pun** |
| STOP | `stop()` → `io.stopSession` `:801` | pemanggil | ledger dikosongkan, `audioEngine.allNotesOff`, `midiInputManager.allNotesOff` (CC123 ×16) |
| Router | `MidiMixerOutput.noteOn/noteOff/program/update` `:105-170` | `@Synchronized`, thread pemanggil | 1 pesan = 1 `MidiInputPort.send()` **tanpa timestamp** (langsung); Bank/PC/CC yang sudah dipegang modul dilewati |
| E343 AUTO/BALANCE | `MidiInputManager.kt:54`, `:269` → `E343OutAdapter.voice/controllers` | sama | hanya Bank/PC dan CC7 ch9–16; tidak mengubah not |
| Device Routing | `MidiInputManager.connectRouting` | main thread | port input 0 perangkat MIDI OUT yang dipilih |

Waktu musikal vs waktu kirim: tidak ada antrean MIDI terpisah. Pesan dikirim saat thread sequencer mencapai `targetNanos` event itu, dan BASSMIDI menerima event yang sama dalam perintah yang sama. Keterlambatan di aplikasi sama dengan jitter thread sequencer, dan **sama** untuk internal maupun MIDI OUT.

## 5. Main → Fill → Main Timeline (SOURCE + HOST)

Fill dijadwalkan oleh `ArrangerBrain.selectMainVariation` (`:643`) → `scheduleSectionChange(fill, thenPlay = target)` → `sequencer.queueScheduledTransition([fill, main])` (`:578`):
1. **Main → Fill:** transisi memutus Main di batas bar, lalu **semua not Main dilepas** (`:726-735`, "Release only notes owned by the outgoing section").
2. **Fill → Main:** Main ada di `pendingSectionQueue`, dan Fill berakhir alami (`playOnce` selesai), lalu `continue` (`:762-772`) **tanpa pelepasan**.

Pemilihan Fill: `Fill<asal><tujuan>` kalau ada, kalau tidak `fillFor(tujuan)` (FillAA/BB/CC/DD) (`:959-974`).

| Skenario | HOST (Love Song, HardRock2, EnglishWaltz2, ROSITA) |
|---|---|
| A Main normal + ganti akor | retarget 15–21 ms setelah tombol; OFF = ON; 0 not lama > 150 ms |
| B Main → Fill (FillBB / FillBA / FillAA) | 0 not terbawa (dilepas oleh transisi) |
| C Main → Fill + ganti akor di Fill | retarget 17–34 ms; 0 not lama (kecuali nada bersama, mis. EnglishWaltz2 C→F: nada C dipertahankan, benar) |
| D Fill → Main (FillBB, FillBA, FillAA) | 0 not terbawa (Fill itu tidak punya not tanpa OFF yang masih berbunyi di akhir) |
| **D' Fill → Main (EnglishWaltz2 FillCC → Main C)** | **4 not Fill ch13 terbawa**: 55, 60, 64 berakhir 248/499/749 ms ke dalam Main; **67 bertahan 3044 ms sampai ganti akor** |
| E Fill → Main + ganti akor | not terbawa ikut di-retarget (OFF + ON di pitch baru) dan tetap berbunyi |
| F Fill padat | puncak 1 ms 24–100 pesan, 10 ms 60–109, 100 ms 86–110 (setup voice di batas section + akor + drum) |
| G Fill berulang | HardRock2 Main B → FillBA: 2 not dilepas 159/727 ms; lainnya 0 |
| H STOP saat Fill | semua dibersihkan oleh CC123 |

## 6. NOTE ON/OFF Lifecycle (SOURCE + HOST)

- Pasangan NOTE ON/OFF per (channel, pitch) memakai ledger berkunci **nada sumber** (`"src:note"`), bukan nada keluaran. NOTE OFF selalu dikirim di nada keluaran yang tersimpan (`active.outputNote`), jadi pitch NOTE OFF **sesuai** dengan NOTE ON. Ini juga berlaku setelah retarget, karena `outputNote` diperbarui (`:944`).
- **Velocity 0** tidak dipakai di MIDI OUT; NOTE OFF dikirim sebagai `0x80` (`MidiMixerOutput.off`).
- **CC64:** Style tidak mengirim CC64 (0 pesan dalam semua tur). Sustain keyboard tidak menahan not Style.
- **All Notes Off / All Sound Off:** hanya saat STOP (CC123 ×16), ganti/putus perangkat (CC64=0 + CC123 ×16, Device Routing), dan RIGHT OFF (CC120 di ch keyboard).
- **Drum:** tanpa NOTE OFF (by design LEGACY, `:1525`). Ledger router drum tidak pernah turun (lihat §7).
- **Mengapa STOP bisa menghentikan not menggantung, tetapi ganti akor tidak:** STOP memakai CC123 global. Ganti akor hanya memproses not yang **ada di ledger**, dan memindahkannya ke pitch baru (RTR 1–4) — bukan mematikannya. Not Fill yang kehilangan OFF **ada di ledger**, jadi ganti akor memindahkannya, tidak mengakhirinya.

## 7. Active-Note Ledger Analysis (SOURCE + HOST)

| Ledger | Diisi | Dikosongkan | Temuan |
|---|---|---|---|
| `StyleSequencer.activeTransposedNotes` (`:228`) | NOTE ON melodi dengan CASM (`:1513`) | NOTE OFF dari pola, re-strike nada sumber sama, RTR 0 / no chord, transisi memutus, STOP | **Tidak dikosongkan pada akhir section alami.** Not tanpa NOTE OFF (dari parser) tetap di ledger sampai salah satu peristiwa di kiri. HOST: ukuran ledger 3–14 di batas section; FillBA → Main A: 12 → 0 (transisi memutus) |
| `MidiMixerOutput.notes[ch][note]` (`:115`) | setiap NOTE ON yang ditulis | NOTE OFF yang ditulis; `releaseNotes` saat mute / disable / putus | Drum tidak pernah turun (Love Song: 270 setelah sekitar 1 menit). `allNotesOff()` saat STOP **tidak** mengosongkannya; mute/solo/MIDI OUT OFF/ganti perangkat lalu mengirim ratusan NOTE OFF sekaligus (audit sebelumnya, V3/V4) |

Kunci per nada sumber berarti dua not sumber berbeda yang dipetakan ke nada keluaran yang sama memiliki dua entri. Keduanya mengirim NOTE ON di tombol yang sama (Love Song Chord1/Chord2: 32/44 kali per menit; audit sebelumnya, V2 — tidak bergantung pada ganti akor).

## 8. MIDI Burst & Backlog Analysis

| Ukuran | Love Song | HardRock2 | EnglishWaltz2 | ROSITA | Jenis bukti |
|---|---|---|---|---|---|
| Pesan per 1 ms (puncak) | 36–88 | 34–78 | 24–88 | 28–100 | HOST |
| Pesan per 10 ms | 60–109 | 62–86 | 60–94 | 80–108 | HOST |
| Pesan per 100 ms | 101–110 | 86 | 94 | 108 | HOST |
| ±50 ms di sekitar batas section | 22–42 (NOTE ON/OFF + CC0/32/PC + CC7/10/11/91/93) | 9–13 | — | — | HOST |
| Ganti akor (dalam 40 ms) | 7–12 OFF + 7–12 ON | 4–8 + 4–10 | 4–10 + 4–10 | 1–4 + 1–7 | HOST |
| Antrean / timestamp | tidak ada antrean; `send()` tanpa timestamp | | | | SOURCE |
| Selisih jadwal vs kirim di perangkat | **NOT TESTED**: host tidak mengukur jitter Redmi Pad maupun USB | | | | — |

Puncak burst ada di **awal play dan batas section** (setup voice: CC0/CC32/PC dan 5 CC per channel, dengan CC dipaksa ulang setelah PC), bukan di Fill sendiri. Belum ada bukti bahwa Android MIDI API atau E343 kewalahan (laju proses USB E343 = UNKNOWN). Delay/throttle **tidak** direkomendasikan tanpa bukti fisik.

## 9. E343 Voice Mapping Analysis (SOURCE + HOST)

- Channel Style 9–16 (Rhythm1 ch9, Rhythm2 ch10, Bass ch11, Chord1 ch12, Chord2 ch13, Pad ch14, Phrase ch15–16). RIGHT/LEFT ch1–4 juga dikirim ke MIDI OUT (`ArrangerBrain.kt:283`, `:400`).
- Urutan voice: CC0 → CC32 → PC lalu CC7/10/11/91/93 dipaksa ulang. Bank/PC yang sama **tidak** dikirim ulang (router).
- Program Change per tur: Love Song ORIGINAL 11, AUTO 6–7; HardRock2 5; EnglishWaltz2 6; ROSITA 7–10. Semuanya di batas section, **tidak** per chord atau Fill berulang.
- AUTO E343 + BALANCE: jumlah not identik dengan ORIGINAL; jumlah PC ≤ ORIGINAL; CC7 diskalakan konstan. **Tidak** ada reprogramming berlebihan dan tidak ada controller reset berulang.
- Kit drum Rhythm1 di ch9 dengan MSB 127: apakah E343 menerimanya = **UNKNOWN** (catatan audit sebelumnya).
- Polifoni 32 (dibagi dengan keyboard E343): not menggantung dari H3, drum tanpa NOTE OFF, dan NOTE ON ganda bisa menghabiskannya. Ini **tidak terbukti** sebagai akar masalah (UNKNOWN).

## 10. Fixture / Simulation Results (HOST)

Harness sementara di luar repository (scratchpad). Isinya: runner salinan `tools/test_rtdiag.py` dengan sumber tambahan, plus dua kelas tes JUnit pada RtRig produksi. Jenis analisis:
- **Statis:** NOTE ON melodi tanpa NOTE OFF di section-nya (dump parser produksi):

  | Style | Section → jumlah not |
  |---|---|
  | EnglishWaltz2 (32) | IntroC 2, MainC 3, MainD 3, **FillCC 7**, **FillDD 3**, EndingA 3, EndingC 11 |
  | HardRock2 (10) | IntroB 3, MainC 1, MainD 2, **FillAA 1**, **FillCC 1**, EndingA 1, EndingC 1 |
  | ROSITA | MainA–D 3 masing-masing (ch14), **FillAA/BB 3**, **FillCC/DD 6** |
  | Love Song | 0 |
- **Dinamis:** skenario A–H (§5) dengan tombol akor ≤ split 54 (C 36/40/43, F 41/45/48, G 43/47/50, Am 45/48/52), ORIGINAL dan AUTO E343 + BALANCE; ditambah skenario Fill → Main dengan Fill yang punya not tanpa OFF (EnglishWaltz2 Main B → FillCC → Main C; HardRock2 Main D → FillAA → Main A).
- **Paritas:** jumlah NOTE ON/OFF MIDI OUT = BASSMIDI di setiap channel melodi, di semua skenario.
- Semua tes harness lulus (7 skenario × 4 Style + 2 skenario Fill).
- **Tidak diuji:** perangkat, USB, polifoni E343, bunyi.

## 11. Verified Findings

| ID | Status | Severity | File : baris | Bukti | Pemicu | Dampak MIDI OUT | Dampak BASSMIDI | Verifikasi | Perbaikan minimal | Risiko |
|---|---|---|---|---|---|---|---|---|---|---|
| **F1** NOTE OFF dibuang parser di batas section | VERIFIED (SOURCE + dump) | Tinggi | `style_parser.cpp:205` (`if(ev.tick<startTick‖ev.tick>=endTick)continue`) | 32/10/≥27 NOTE ON tanpa OFF (EnglishWaltz2/HardRock2/ROSITA) | not yang berbunyi sampai atau melewati akhir section | not tanpa NOTE OFF di data | sama (event sama) | dump `style.tsv` (§10) | parser (dibekukan) **atau** sequencer (P1) | parser dipakai semua Style → hanya lewat P1 |
| **F2** Section dari antrean tidak melepas not aktif | VERIFIED (SOURCE + HOST) | Tinggi | `StyleSequencer.kt:760-772` vs `:726-735` | EnglishWaltz2 FillCC → Main C: 4 not terbawa, 248–3044 ms | Fill/Intro yang berakhir alami lalu ke Main | not Fill menggantung, ikut di-retarget saat ganti akor | sama | `SameMainFill` (§10) | P1 | lihat §15 |
| **F3** Ganti akor me-retarget, bukan mengakhiri, not ledger | VERIFIED (SOURCE + HOST) | Sedang | `:863-873`, `:918-945` | not 67 → OFF + ON di pitch baru setelah 3044 ms | F2 + ganti akor | "akor lama masih ada" berubah menjadi nada yang tetap menggantung di akor baru | sama | sama | tidak perlu diubah sendiri (perilaku RTR benar) | — |
| **F4** Ganti akor diterapkan 15–34 ms setelah tombol | VERIFIED (HOST) | Rendah | `ArrangerBrain.kt:110`, `:568-599` | 4 Style | setiap akor | +15 ms settle sengaja (penggabungan jari E343) | sama | harness | tidak ada | — |
| **F5** NOTE ON ganda dari CASM | VERIFIED (HOST, audit sebelumnya) | Sedang | `:1511-1529`, kunci ledger `src:note` | Love Song Chord1/2 32/44 per menit | dua not sumber → nada keluaran sama | dua voice di tombol yang sama di E343 | sama | `E343MidiOutDeepAuditRtTest` (untracked) | P3 | sedang |
| **F6** Ledger router drum tidak pernah turun | VERIFIED (SOURCE + HOST) | Sedang | `MidiMixerOutput.kt:115`, `:164-170`; `MidiInputManager.kt:297` | 270 setelah sekitar 1 menit; mute → 270 NOTE OFF | mute/solo/MIDI OUT OFF/ganti perangkat | ledakan pesan → jeda | tidak ada | audit sebelumnya | P2 | rendah |
| **F7** RIGHT/LEFT dikirim ke MIDI OUT | VERIFIED (SOURCE) | Sedang (fisik) | `ArrangerBrain.kt:283`, `:400` | kode | E343 = MIDI IN + Local Control ON | bunyi lokal + bunyi dari aplikasi | tidak ada | T1 (§17) | P4 | rendah |
| **F8** Pada tick yang sama, NOTE ON disortir sebelum NOTE OFF | VERIFIED (SOURCE) | Rendah | `StyleSequencer.kt:1309` (`thenBy{it.event.isNoteOn.not()}`) | kode | NOTE ON dan NOTE OFF bernada sama di tick sama (sumber: 1–3 per section di HardRock2/EnglishWaltz2/ROSITA) | Dari kode: NOTE ON diproses lebih dulu → REPLACE (OFF not lama + ON not baru); lalu NOTE OFF pola pada tick yang sama mengakhiri **not baru** (`endScheduledNote` menghapus kunci `src:note`) → not baru terpotong seketika (bisa terdengar sebagai jeda). Dampak dengar belum diukur | sama | tes terisolasi (§16 R4) | evaluasi; jangan diubah tanpa tes | sedang |

## 12. Unverified Hypotheses

| ID | Status | Isi | Cara memverifikasi |
|---|---|---|---|
| U1 | UNKNOWN | Love Song (Style yang paling sering diuji pengguna?) tidak punya not tanpa OFF (F1 = 0); gejalanya di Love Song bisa berasal dari F5/F7/polifoni, bukan F2 | Tanyakan Style yang dipakai; T2/T3 di §17 |
| U2 | SUSPECTED | Release voice E343 (Strings/Guitar) lebih panjang dari SF2, sehingga NOTE OFF yang tepat waktu tetap terdengar sebagai "akor lama masih ada" | Bandingkan dengan internal di Style/Fill yang sama (T6) |
| U3 | SUSPECTED | Polifoni 32 terlampaui (not menggantung + drum + voice ganda + lokal) → pencurian voice → not baru "terlambat" atau hilang | T3 (kurangi drum) |
| U4 | UNKNOWN | Laju proses USB-MIDI E343 terhadap burst 40–110 pesan per 10–100 ms di batas section | T4 |
| U5 | UNKNOWN | Jitter thread sequencer di Helio G99 di bawah beban | probe `LatencyProbe` di perangkat |

## 13. Root Cause Ranking

1. **F1 + F2 (+F3):** not tanpa NOTE OFF dari Fill/Intro terbawa ke Main dan menggantung sampai ganti akor (dipindah, bukan diakhiri), re-strike, atau STOP. **Keyakinan sedang–tinggi** untuk Style yang punya not seperti itu (EnglishWaltz2, HardRock2, ROSITA, dan kemungkinan banyak Style Yamaha). Cocok dengan fakta fisik 3, 5, 6, 7.
2. **F5 + F7 + U3:** voice ganda dan pantulan RIGHT/LEFT memenuhi polifoni E343 → bertumpuk/terlambat. Keyakinan sedang; perlu T1–T3.
3. **U2:** release voice E343. Keyakinan rendah–sedang.
4. **F6:** ledakan NOTE OFF, hanya setelah aksi pengguna. Keyakinan sedang untuk "jeda" yang muncul setelah mute/toggle.
5. **H2 backlog:** tidak didukung oleh kode aplikasi (tanpa antrean). Kontribusi USB/E343 UNKNOWN.

## 14. Minimal Patch Candidates (TANPA IMPLEMENTASI)

| # | Perubahan minimal | Alasan dan bukti | Risiko Style normal | Risiko Recorder | Risiko BASSMIDI | Tes regresi | Keputusan |
|---|---|---|---|---|---|---|---|
| **P1** | Saat section dari antrean dimulai (`StyleSequencer.kt:762-772`), lepas not ledger yang **dimiliki section sebelumnya** (`active.sourceSection != section baru`) dengan `releaseActive`, sama seperti cabang transisi `:726-735`. Hanya not milik section lama, bukan CC123 global | F1/F2: satu-satunya jalur tanpa pelepasan; HOST membuktikan not terbawa 248–3044 ms | Not yang **sengaja** disambung melintasi section (tie Fill → Main) akan berhenti di batas. Perilaku ini sama dengan transisi yang memutus, yang sudah berjalan | Recorder merekam tap engine: take akan berisi NOTE OFF di batas (lebih benar) | Internal juga berhenti di batas (sama dengan perilaku saat ini untuk transisi memutus) | R1–R6 (§16) | **GO** (eksperimen pertama) |
| P2 | Kosongkan ledger router saat `allNotesOff()` STOP, **atau** jangan menghitung drum ch9/10 | F6 | tidak ada (CC123 sudah terkirim) | tidak ada | tidak ada | ledger setelah STOP = 0; SHORT_GATE EnglishWaltz2 tetap lulus | GO (terpisah, rendah) |
| P3 | Router MIDI OUT: kalau (ch, nada) masih dipegang, kirim NOTE OFF sebelum NOTE ON ulang dan tahan NOTE OFF sampai hitungan 0 | F5 | artikulasi not kembar berubah di E343 | tidak ada | tidak ada (internal tidak berubah) | Love Song: NOTE ON ulang = 0; ON = OFF | **NO-GO** sampai T2 membuktikan dampaknya. Juga melanggar arahan "jangan menambahkan NOTE OFF sebelum setiap NOTE ON" tanpa bukti |
| P4 | Petunjuk Local Control, atau opsi agar RIGHT/LEFT tidak dikirim ke MIDI OUT | F7 | tidak ada | tidak ada | tidak ada | UI | NO-GO sampai T1 |
| P5 | All Notes Off di setiap Fill | — | memotong not yang sah, juga drum/RIGHT | — | — | — | **NO-GO** (dilarang, tanpa bukti) |
| P6 | Delay/throttle MIDI | — | menambah latensi | — | — | — | **NO-GO** (tidak ada bukti backlog) |
| P7 | Ubah parser agar NOTE OFF di `tick == endTick` disimpan sebagai OFF di akhir section | F1 | menyentuh parser (dibekukan) yang dipakai semua Style | ada | ada | seluruh suite parser/CASM | NO-GO (cakupan terlalu besar; P1 menangani gejala di sequencer) |

## 15. Regression Risks (P1)

- **Style:** tie musikal yang disengaja melintasi Fill → Main akan berhenti di batas. Perilaku ini sudah sama untuk transisi memutus (`:726-735`), jadi konsisten. Not drum tidak terpengaruh (tidak ada di ledger).
- **Loop section yang sama** (Main → Main tanpa Fill): P1 tidak boleh melepas not di batas loop. Syaratnya `sourceSection` berbeda, karena loop yang sama memakai re-strike (`SCHEDULED_REPLACE_OFF`).
- **Recorder V1B/V1C:** tap engine mencatat NOTE OFF tambahan di batas; paritas live = file = playback harus diuji ulang (`RecorderParityRtTest`).
- **BASSMIDI:** menerima NOTE OFF yang sama (event sama). Tidak ada perubahan resolver, SoundFont, atau DSP.
- **E343 AUTO / Device Routing:** tidak terkait.
- **Ordering (P1 dan F8):** pelepasan memakai `releaseActive` → `chordOff` → `stageIo` yang sudah berurutan per tombol.

## 16. GO / NO-GO Decision

- **GO** untuk eksperimen patch **P1**, di branch eksperimen, dengan tes regresi sebelum dan sesudah:
  - R1: EnglishWaltz2 Main B → FillCC → Main C: tidak ada not Fill yang masih berbunyi lebih dari 20 ms setelah Main dimulai (saat ini 4 not, 248–3044 ms);
  - R2: tidak ada not terbawa pada FillBB / FillBA / FillAA di 4 Style (saat ini 0, harus tetap 0);
  - R3: loop Main yang sama (MainC ×3): not Pad/Chord tetap disambung oleh re-strike, bukan dipotong di batas loop;
  - R4: tick sama ON/OFF (F8): tes terisolasi untuk not sumber yang berakhir dan dimulai ulang di tick yang sama;
  - R5: paritas ON/OFF MIDI OUT = BASSMIDI; ganti akor tetap 15–34 ms dengan 0 not lama;
  - R6: Recorder V1C parity, E343 SHORT_GATE, E343 AUTO, Device Routing, CPV JVM.
- **NO-GO** untuk P3–P7 sampai tes fisik §17 memberi bukti.

## 17. Remaining Physical Tests (Redmi Pad + PSR-E343)

- **T0 — Identifikasi Style dan Fill:** catat Style dan transisi persis yang menimbulkan gejala. Bandingkan EnglishWaltz2 Main B → Main C (FillCC) dengan Main A → Main B (FillBB). Prediksi H3: gejala muncul setelah FillCC, tidak setelah FillBB.
- **T1 — Local Control E343 OFF** (F7).
- **T2 — Mute Chord 1/2 sebelum START** pada Love Song (F5).
- **T3 — Mute Rhythm 1/2 sebelum START** (polifoni U3).
- **T4 — Main ↔ Fill berulang tanpa ganti akor;** catat jeda di batas section (U4).
- **T5 — OUTPUT MODE BOTH vs INTERNAL** pada Style/Fill yang sama. Prediksi H3: not menggantung yang sama juga terdengar di BASSMIDI internal, kemungkinan lebih pendek karena release SF2 (fakta fisik 8, U2).
- **T6 — ORIGINAL vs AUTO E343** (voice/kit ch9).

Kalau T0 dan T5 cocok dengan prediksi, F1/F2 menjadi penyebab yang terbukti di perangkat dan P1 dilanjutkan.

---

*Laporan ini hanya dokumen. Tidak ada kode produksi, tes repository, fixture, atau workflow yang diubah. Harness simulasi ada di luar repository dan tidak di-commit.*
