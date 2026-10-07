# SFF1 reference implementation audit — YamahaArranger

Tanggal: 2026-10-07. Status: **AUDIT ONLY; tidak ada fix atau perubahan playback dalam deliverable ini.**

Baseline: branch `fix/mix-percussion-fidelity-784`, commit lengkap **`05cdb1f0998d082d701bc0d1979580270ae2eefc`**. HEAD checkout dan remote branch cocok pada saat audit. [Build #792](https://github.com/pulicarpus/YamahaArranger/actions/runs/37487781956) untuk SHA tersebut berstatus completed/success menurut metadata Actions yang dibaca pada audit ini. Ini bukan hasil build Android baru oleh auditor.

Audit ini melanjutkan `YamahaArranger_SFF1_CORPUS_AUDIT_2026-10-07.md` dan `PROMPT_SFF1_REFERENCE_PIPELINE_YamahaArranger.md`. Temuan handoff dipakai sebagai hipotesis yang diperiksa kembali, bukan sebagai pengganti source aktual. Corpus `sff1.zip` SHA-256: `a9b89b90e1dee25c714a076a125d6c7173bcca0e8d2029c57092b3295035d465`.

## Kesimpulan dan batas bukti

Parser produksi dapat membaca 503/503 SFF1. Pemisahan source/destination dan Cntt override sudah bekerja untuk policy yang terpasang. Akan tetapi, keberhasilan parse **belum berarti conformance end-to-end**: field pemilihan root hilang, timeline diurutkan ulang, satu key ownership tidak dapat mewakili overlap, dan beberapa MIDI event berhenti di sequencer.

Ada counterexample produksi terhadap asumsi source channel sebagai role: **source 8/9 yang policy-nya ditolak dapat dikirim ke rhythm 8/9**, walaupun CASM mendeklarasikan sumber itu sebagai melodic. Main D BaroqueAir1 menghasilkan 44 NOTE_ON rhythm tanpa deklarasi rhythm pada section itu. Unplugged2 menghasilkan 256 NOTE_ON rhythm tambahan dari source melodic 8/9. Ini lebih konkret daripada menyimpulkan semua style yang terdengar kosong pasti bermasalah pada SF2.

Label bukti:

- **PROVEN**: terlihat pada source baseline, atau direproduksi dengan parser/sequencer produksi yang tidak diubah. Selalu disebutkan batas lingkungan uji.
- **STRONG INFERENCE**: mekanisme dan corpus mendukung akibat yang masuk akal, tetapi akibat akustik atau semantics Yamaha belum diukur.
- **UNKNOWN / NEEDS RESEARCH**: tidak cukup bukti untuk mengklaim arti bit, Yamaha-exact transformation, atau perilaku hardware.

Severity: **P0** untuk counterexample lifecycle/routing yang dapat merusak ACMP; **P1** untuk loss of information, fidelity dan conformance penting; **P2** untuk kelengkapan metadata, hardening atau jangkauan pembuktian. Prioritas desain dialect pada handoff tidak dijadikan bukti bahwa absennya enum sendiri menyebabkan silence. P0/P1 di sini menyatakan prioritas engineering, bukan izin mengubah baseline.

## 1. Metode verifikasi yang benar-benar dijalankan

1. Checkout source tepat pada SHA di atas; baca parser, JNI, repository/model, arranger, sequencer, transformer, audio bridge, native BASSMIDI dan MIDI OUT. Tidak mengandalkan ringkasan audit lama untuk isi fungsi.
2. Compile `style_parser.cpp` dan `smf_reader.cpp` asli dengan g++ C++17. Shim host hanya logging Android (`tests/mocks`, `ANDROID_LOG_ERROR=6`); tidak mengganti parser. Driver sementara mengekspor raw SMF event, section, source part, policy dan event hasil parser untuk semua 503 file.
3. Walker SMF independen membandingkan **3.218.027 raw event** terhadap `SmfReader`, termasuk urutan, status, data, meta/SysEx payload. Walker CASM independen membaca chunk CSEG/Sdec/Ctab/Cntt dan membandingkan semua field model yang dipasang dengan output produksi. Menghitung policy raw dan salinan per section secara terpisah.
4. Overlap dihitung pada **urutan SMF asli**, FIFO multiplicity per channel/key; NOTE_ON velocity 0 dihitung sebagai OFF. Tidak menghitung artifact reorder sequencer sebagai overlap corpus.
5. Compile Kotlin 1.9.24/JVM17, coroutines 1.8.1 dengan `StyleSequencer`, `CasmNoteTransformer`, model, chord dan diagnostics asli. Hanya AudioEngineManager, MidiInputManager dan DebugLog diganti capture stub. Probe synthetic menggunakan PPQ besar agar cepat tetapi menjalankan fungsi produksi, comparator dan ledger asli. Probe ini membuktikan urutan panggilan output, **bukan real BASS PCM atau ketepatan waktu Android**.
6. Replay Main D ketujuh golden fixtures, masing-masing C major dan F major, melalui sequencer asli. Input berasal dari parser asli; tick-zero setup diekstrak dengan aturan repository. Ini memeriksa satu pass/section, bukan seluruh transisi, seluruh chord atau Yamaha hardware.
7. Menjalankan `python3 tools/test_voice_resolver.py`: exit 0. Suite host/guards yang dipanggilnya lulus. Tidak menjalankan Gradle JVM suite, build APK/ABI baru, real-BASS test, atau PSR-E343 pada audit ini.

Driver dan capture sementara berada di scratch di luar repository. Tidak ditambahkan sebagai harness produksi atau sebagai file deliverable. Angka utama dan trace counterexample dimasukkan di bawah agar kesimpulan tetap dapat ditinjau dari dokumen ini.

### Hasil corpus dan koreksi arti “32.913 terverifikasi”

| Pemeriksaan | Hasil ulang | Batas kesimpulan |
|---|---:|---|
| Parser produksi | 503/503 | Parse sukses; bukan certification playback |
| Dialect/container | 503 SFF1 + CASM; SMF0, satu track, PPQ1920 | Corpus tidak menguji SFF2 |
| CSEG / Sdec | 3.584 / 3.584 | Sdec mendahului tabel dalam corpus ini |
| Ctab / Cntt / Ctb2 | 32.913 / 32.913 / 0 | Jumlah record raw, bukan jumlah policy per section |
| Expected section-policy copies dari ekspansi Sdec | 73.229 | Termasuk descriptor untuk source tanpa part pada section |
| Policy copies yang benar-benar dipasang | **71.239** | Semua field yang direpresentasikan cocok dengan raw Ctab + Cntt pada source part yang tersedia |
| Salinan tidak dipasang | **1.990**, pada 1.084 section di 196 style | Source part tidak tersedia pada section tersebut; bukan bukti authored note hilang |
| Raw Ctab yang tidak terpasang pada satu pun section Sdec-nya | **157** | Model runtime bukan registry seluruh 32.913 descriptor |
| Overlapping NOTE_ON | **523 pada 47 style**, multiplicity maksimum 2 | Original SMF order; tidak otomatis 523 melodic lifecycle failures |
| Multi-source → destination groups | **9.373** | Maksimum 11 source ke Chord1 pada Unplugged2 |
| Ctab bytes 11–12 selain `0x0FFF` | **257 record pada 49 style** | Field hilang terbukti; semantics/orientasi bit root belum certified |
| Cntt Bass-On | 3.562 | Semua Ctab corpus memiliki pasangan Cntt source |
| NTT efektif | 0:15.068; 1:4.866; 2:10.600; 3:760; 4:36; 5:1.541; 6:28; 9:14 | 6/9 hanya pada BaroqueAir1; tidak dimapping ulang |
| Meter | 463×4/4; 39×3/4; 1×2/4; 0×6/8 | Nama `6-8*` tidak menjadi bukti meter |

**Koreksi penting:** handoff “32.913 Ctab/Cntt terverifikasi” benar sebagai inventory raw dan pemeriksaan mapping field yang direpresentasikan. Itu **tidak boleh ditulis sebagai 32.913 policy lengkap pasti hidup di normalized model**. Policy source tanpa part tidak dipasang; byte 10–12 dan trailing byte Ctab tidak direpresentasikan. Tidak ada mismatch field pada 71.239 salinan yang applicable, tetapi ada loss of information di luar field model itu.

## 2. Pipeline aktual file-by-file

Semua path dan nomor baris berikut mengacu pada baseline 05cdb1f, bukan commit dokumen audit.

| Tahap | File / function / class | Perilaku aktual dan boundary |
|---|---|---|
| SMF bytes | `app/src/main/cpp/smf_reader.h`, `smf_reader.cpp:28–130`, `SmfReader::parse` | Baca SMF0/1, PPQ, tempo pertama, raw channel/meta/SysEx event dalam urutan asli. Tidak membawa dialect arranger |
| Section slicing | `app/src/main/cpp/style_parser.cpp:7–23,177–199`, `StyleParser::parse`, `classifyMarkerText` | Marker/text ke enum; event dikelompokkan per source MIDI channel; setup sebelum section pertama disalin ke tick0; range section half-open; terminal end memakai last event tick+1 |
| CASM | `style_parser.cpp:24–175`, `parseCasm`; `style_parser.h:9–16` | Scan CASM/CSEG/Sdec; Ctab satu policy, Ctb2 ranges; attach hanya ke part yang source channel-nya cocok; Cntt override setelah seluruh CSEG dibaca |
| JNI transfer | `app/src/main/cpp/native_lib.cpp:147–180`; `app/src/main/java/com/yourapp/style/NativeStyleBridge.kt` | Global last parser; packed event `[tick,status,data1,data2,metaType,payloadLen,payload…]`; 15-field policy string. Field yang tidak ada di CasmPolicy tidak dapat mencapai Kotlin |
| Repository/model | `app/src/main/java/com/yourapp/yamahaarranger/style/StyleRepository.kt:9–187`; `app/src/main/java/com/yourapp/style/StyleModel.kt` | Decode non-note payload, policy list, tick0 bank/PC/controllers; legacy voiceMap terpisah; meter dengan pencarian byte FF58; tidak memiliki StyleDialect, raw ordinal atau note-instance owner |
| Section requests/chord input | `app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt`, `scheduleSectionChange`, `fillForTransition`; `app/src/main/java/com/yourapp/chord/{ChordDetector,AcmpChordAnalyzer}.kt` | UI/ACMP chord menuju sequencer. Brain menghitung wait next-bar memakai meter, lalu queue pada master clock |
| Voice setup/mix | `StyleSequencer.kt:556–677`, `applyVoicesFromCasm`, `applyStyleController`, `applyEffectiveMixer` | Pilih satu setup per destination dari first policy/first explicit part; mixer state/cache per destination; bank state dinamis per source; source/destination identity tidak sama |
| Schedule/policy | `StyleSequencer.kt:730–1007`, `policyScore`, `selectPolicy`, `playOnce` | Flatmap part events; sort tick lalu ON-first; pilih satu policy per note; source8/9 exemption pada policy miss; skip semua non-note pada relative tick0 |
| Note conversion | `app/src/main/java/com/yourapp/yamahaarranger/arranger/CasmNoteTransformer.kt`, `transform` | NTR 0/1/2/3, NTT explicit 0–5, default base untuk lainnya, HighKey, octave NoteLimit; bukan dialect-aware |
| Ownership/RTR | `StyleSequencer.kt:72–96,430–554,966–989`, `handleChordChange`, `retarget`, `releaseActive`, `endScheduledNote` | Satu owner per source:key; lock/identity guard menahan stale retarget; diagnostics onId tersedia tetapi bukan lookup identity. Drum/no-policy ON tidak masuk ledger |
| Native output | `app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt:213–252,329–339`; `native_lib.cpp`; `app/src/main/cpp/audio_engine.cpp` | Bridge style origin/diagnostics menuju native soundFont/BassMidiPlayer; fallback sample bila SF2 belum loaded adalah existing behavior |
| Voice resolution/synth | `app/src/main/cpp/bassmidi_player.cpp`, `setChannelPreset`, `noteOn`, `noteOff`, `percussionOn/Off`; `app/src/main/cpp/voice_resolver.h`, `percussion_fidelity_policy.h` | Destination native channel state, requested bank/program lalu resolver/font mapping, BASS events; native NOTEOFF1/percussion owners tidak dapat memulihkan owner yang sudah dipotong sequencer |
| PCM | `BassMidiPlayer::render/renderPercussion`, `AudioEngine::onAudioReady`; existing PCM diagnostic/meter/trim code | Parent BASS decode, existing trim/shared FX/percussion sum, existing clamp. Tidak disentuh audit; suara nyata tidak dibuktikan oleh capture stub |
| External MIDI | `app/src/main/java/com/yourapp/midi/MidiInputManager.kt:180–215` | ON/OFF dan bank0/bank32/PC dikirim ke port; sequencer tidak memanggil pengiriman style CC/pitch bend/SysEx. Output ini harus diuji terpisah dari BASSMIDI |

## 3. Ctab/Cntt: byte map yang diperiksa

Offset adalah zero-based di payload Ctab, bukan di header chunk.

| Offset | Informasi | Implementasi produksi | Status |
|---|---|---|---|
| 0 | source channel | `d[0]`, attach ke part.midiChannel | PROVEN preserved bila part tersedia |
| 1–8 | voice name | eight-byte string, trim ASCII | PROVEN preserved; bukan ID preset numerik |
| 9 | destination channel | `d[9]` | PROVEN separate dari source |
| 10 | flag/raw field | tidak dibaca/model tidak memiliki field | PROVEN loss; semantics tidak diasumsikan |
| 11–12 | raw 12-bit candidate root-selection field | tidak dibaca | PROVEN loss; ROOT mask designation STRONG INFERENCE |
| 13–17 | raw five-byte chord field | big-endian ke uint64/Long | PROVEN bytes preserved; arti/mapping semantic bit UNKNOWN |
| 18,19 | source chord root/type | `d[18]`, `d[19]` | PROVEN preserved; sourceChordType tidak digunakan oleh transform |
| 20 | NTR | `d[20]` | PROVEN preserved |
| 21 | NTT + high bit | `d[21]&0x7F`; Ctab Bass-On tidak diambil | NTT preserved; semua pasangan Cntt corpus mengoverride NTT dan Bass-On, sehingga omission Bass-On Ctab bukan effective mismatch corpus |
| 22 | High Key | `d[22]` | PROVEN preserved |
| 23,24 | Note Limit low/high | `d[23]`,`d[24]` | PROVEN preserved |
| 25 | RTR | `d[25]` | PROVEN preserved |
| 26 dan tail | raw trailing data | tidak disimpan | UNKNOWN semantic relevance; jangan mengklaim full byte fidelity |
| Cntt 0,1 | source + NTT/Bass-On | source-keyed map; bits 0..6 NTT / bit7 Bass-On; diterapkan setelah tabel CSEG | PROVEN authoritative pada corpus; jangan menebak general duplicate-Cntt semantics |

Parser memberi sourceNoteLow/High 0/127 untuk Ctab. Tidak ada range loss yang terbukti pada Ctab corpus dari keputusan ini. Ctb2 memiliki range berbeda tetapi tidak terdapat dalam dataset; correctness SFF2 tidak disimpulkan.

## 4. Temuan per first divergence

### F01 — Root-selection data hilang upstream — P1

**FACT:** 257 dari 32.913 Ctab memiliki bytes11–12 bukan `0x0FFF`, pada 49 style; 53 raw values (52 non-default + satu default) termasuk 0x0000, 0x0008, 0x0555 dan 0x0AAA.

**EVIDENCE:** raw chunk walker; `style_parser.cpp:91–113` melompat dari destination byte9 ke root byte18 dan membaca chord field13–17; `CasmPolicy`, JNI15-field, `CasmPolicyModel` tidak punya root mask.

**PROJECT BEHAVIOR:** informasi sudah hilang di `parseCasm` sebelum Kotlin `policyScore`; current selection hanya mempertimbangkan chord-type mask, source-note range dan specificity.

**GAP:** **PROVEN** data loss; **STRONG INFERENCE** bytes11–12 adalah pemilihan root/pitch-class; **UNKNOWN** orientasi bit, reserved flags dan perilaku mask0. Jangan mengubah root/type metadata menjadi current-root selector sebagai pengganti field yang hilang.

**RISK:** root-specific lanes dapat ikut dimainkan pada root yang salah, menambah density/collision atau salah voicing. Ini bukan bukti bahwa LoveSong3 kosong: seluruh root field LoveSong3 di corpus adalah 0x0FFF. Regression risk tinggi jika bit dibalik atau mask0 ditafsirkan tanpa rujukan.

**RECOMMENDED ACTION:** pertama simpan raw field dan provenance secara read-only di S3; penerapan semantics/gates hanya di S5 dengan primary Yamaha/reference evidence dan oracle root 0..11. First divergence: **raw Ctab → native CasmPolicy**.

### F02 — ON-first reorder dapat memotong note baru dan terlambat mengganti preset — P0

**FACT:** comparator `compareBy<Scheduled>{it.tick}.thenBy{it.event.isNoteOn.not()}` menempatkan true NOTE_ON sebelum semua OFF, CC dan PC pada tick sama.

**EVIDENCE:** `StyleSequencer.kt:825–835`; production Kotlin probe, input `ON0, OFF1, ON1, OFF2`, output sesudah header: `ON0 → OFF(replace) → ON1 → OFF(scheduled at1)`; OFF2 tidak punya owner. Input PC40 dan ON pada tick1 menghasilkan **ON → PC40 → OFF**, pada audio dan MIDI captures. Normalized corpus mempunyai 104.861 section/tick groups dengan ON+OFF dan 30 dengan ON+PC di tick>0; angka pertama mencakup key/channel berbeda, bukan jumlah truncation failures.

**PROJECT BEHAVIOR:** pembacaan SMF mempertahankan urutan, tetapi `StyleParser::parse` memisah event per source channel; global raw ordinal tidak dibawa. Flatmap part lalu comparator mengubah urutan intra-source, serta tidak dapat memulihkan urutan global antar-source.

**GAP:** **PROVEN** reorder dan counterexample cut-short. First loss of cross-lane ordering adalah **section projection tanpa raw ordinal**; first ON-before-OFF/PC behavior adalah **playOnce sorting**. Jangan mengklaim generic OFF-first pada seluruh MIDI selalu tepat: zero-duration notes yang sengaja ON→OFF harus tetap punya urutan sah.

**RISK:** sustained accompaniment terputus, adjacent repeated notes hilang, attack memakai preset/controller lama. Mengubah comparator sendiri tanpa ownership/boundary tests juga dapat memunculkan stuck note.

**RECOMMENDED ACTION:** simpan raw event ordinal di S3; desain explicit tie semantics dan note pairing bersama S4. Uji OFF→ON, ON→OFF, CC0/32→PC→ON, lintas source dan section seam, tanpa global allNotesOff.

### F03 — Single-owner ledger tidak mewakili overlap — P0

**FACT:** 523 ON overlap pada 47 style; max multiplicity2. Contoh pertama: `8BeatSoft.S686.bcs`, absolute raw tick17280, source14/key58; style itu mempunyai 7 overlap.

**EVIDENCE:** original-SMF walker; `activeTransposedNotes: MutableMap<String,ActiveTransposedNote>` (`StyleSequencer.kt:94`); key construction966; replacement968–975; insertion989; endScheduledNote549–554.

**PROJECT BEHAVIOR:** probe `ON0 #1, ON1 #2, OFF2 #1, OFF3 #2` menghasilkan `ON0 → OFF(replace at1) → ON1 → OFF2`; OFF3 tidak diteruskan. onId ada dalam descriptor diagnostic, namun lookup tetap source:key. Only policy-bearing melodic ON masuk ledger.

**GAP:** **PROVEN** satu slot tidak dapat menyimpan kedua instance; first divergence **NOTE_ON → ownership insertion/replacement**. Jumlah 523 tidak disamakan dengan melodic defect count karena overlap juga terjadi pada rhythm dan semantik Yamaha retrigger belum diukur.

**RISK:** durasi/tail/retarget salah; source berbeda yang berubah menjadi output pitch sama masih dapat berinteraksi di native/device. Native NOTEOFF1 tidak memperbaiki premature upstream OFF.

**RECOMMENDED ACTION:** S4 unique instance identity + deterministic ON/OFF pairing; simpan section generation, source-part/lane dan onId; pasangan OFF memakai captured destination/output dari ON. Jangan hanya mengganti map menjadi key destination:note atau melepas lock stale-owner.

Jalur wajib ikut migrasi: `activeNoteSnapshot/Count`, `handleChordChange`, `handleNoChord`, `retarget`, `releaseActive` reference identity guard, `endScheduledNote`, replacement ON, `chordOwnerEvidence`, forced-transition release, stop/clear dan diagnostics/tests reflective constructor. Physical-output collisions lintas source merupakan masalah lain: source instance tokens tetap harus dipertahankan, lalu kemampuan native/MIDI diukur.

### F04 — Policy miss pada source8/9 menjadi phantom rhythm — P0

**FACT:** source channel bukan destination role. Ada 9.373 multi-source groups dan source8/9 dapat diarahkan CASM ke destination11/12/15.

**EVIDENCE:** `isRhythmSource(channel)=channel==8||channel==9` (`StyleSequencer.kt:791`); policy-null drop guard927 mengecualikannya; destination fallback955 adalah source channel; `isDrumDestination` menganggap destination8/9 rhythm. Replay nyata:

- BaroqueAir1 MainD mendeklarasikan melodic destinations10/11/12/15. Dengan C major, src8 Strings yang ditolak chord mask mengirim36 ON ke rhythm8; src9 Oboe mengirim8 ON ke rhythm9. Total44 phantom rhythm ON; native audio/MIDI call arguments terkonfirmasi.
- Unplugged2 MainD: authorized rhythm source15→8 mengirim128 ON dan source14→9 mengirim105 ON. Rejected melodic src8→11 dan src9→11 masing-masing mengirim128 ON ke fallback8/9. Observed rhythm counts menjadi256 dan233: **256 additional ON**.

**PROJECT BEHAVIOR:** non-8/9 source dengan policy miss di-drop; source8/9 dibypass, dikirim raw/untransposed sebagai rhythm. Ini bertentangan dengan klaim general bahwa source/destination separation sudah benar di seluruh pipeline.

**GAP:** **PROVEN** dispatch ke destination yang tidak dideklarasikan oleh applicable CASM untuk event itu. First divergence **policy-null handling → source-based rhythm exemption/fallback**. Exact Yamaha sound belum diukur, tetapi salah destination sudah bisa dibuktikan sebelum resolver.

**RISK:** pitched String/Oboe/Guitar notes berubah menjadi trigger percussion; mask-rejected part dianggap hadir, intentional absence dirusak. Perubahan gate berisiko pada style legacy tanpa CASM, maka dialect/declaration absence harus dibedakan.

**RECOMMENDED ACTION:** masukkan counterexample ke S1; di S3 normalized plan bedakan “tidak ada CASM” dengan “CASM ada tetapi tidak cocok”. Rencana fix kecil gate baru hanya setelah transcript legacy/SFF1 dipisahkan; jangan force semua policy atau menghidupkan semua source.

### F05 — Drum/no-policy ON tidak memiliki scheduled OFF owner — P1

**FACT:** insertion ledger hanya jika `policy!=null && !isDrumPart`; setiap scheduled OFF memanggil `endScheduledNote`, tanpa jalur direct OFF alternatif.

**EVIDENCE:** `StyleSequencer.kt:949–952,987–989`; production probe source4→destination9: ON42 dikirim, OFF42 berikutnya tidak menghasilkan audio ataupun MIDI OFF.

**PROJECT BEHAVIOR:** native percussion memiliki ledger/generation dan `percussionOff`, tetapi scheduled style OFF tidak pernah memanggilnya untuk rhythm ini. Non-CASM fallback note juga tidak memiliki owner. STOP/global existing cleanup tetap ada.

**GAP:** **PROVEN** missing OFF dispatch; **UNKNOWN** dampak PCM setiap kit karena banyak drum one-shot dapat berakhir alami; tidak menyebut semua rhythm stuck. First divergence **ON tidak diregistrasikan → OFF lookup gagal**.

**RISK:** external MIDI/looping percussion atau legacy melodic fallback dapat bertahan; native ownership counterexample tidak tercakup oleh tests yang memanggil native noteOff langsung. Memperbaiki rhythm release dapat mengubah decay/choke, sehingga harus milestone tersendiri di S4/S6.

**RECOMMENDED ACTION:** cakup semua note instance yang dikirim; tetapkan explicit one-shot/release behavior per output; jangan global allNotesOff untuk menutup gap.

### F06 — MIDI data preserved dalam model, tetapi tidak dispatched penuh — P1

**FACT:** packed MIDI/JNI/model mempertahankan non-note event dan payload.

**EVIDENCE:** `nativeGetPartEvents`, `decodePackedEvents`; `playOnce` non-note branch880–925 hanya menangani CC0/32, CC7/10/11/91/93 dan PC. `applyStyleController` mengembalikan default untuk CC lain. Corpus normalized section copies pada tick>0 memuat **41.347 pitch-bend events** dan **23.244 CC selain subset tersebut**, termasuk CC1=6.185, CC74=15.970, CC71=527, CC73=370 dan RPN101/100/6 masing-masing30. Counts ini per section projection, bukan seluruhnya unique raw SMF events atau audible eligible events.

**PROJECT BEHAVIOR:** pitch bend/pressure/SysEx dan CC lain tidak punya output call dalam loop; relative tick0 seluruh non-note di-skip. Tick0 subset voice/mix diterapkan oleh header activation, tetapi tidak seluruh event. SysEx sebelum marker pertama tidak dimasukkan `setupByChannel` karena syarat status<0xF0; ini **lebih awal**, di section projection. Tick0 non-note count678.648 tidak berarti semuanya loss karena subset header diterapkan terpisah.

**GAP:** **PROVEN** dispatch coverage parsial; first divergence bergantung event: global pre-section SysEx pada **section slicing**, later pitch/CC pada **sequencer dispatch**. Probe bend+CC7: audio capture menerima mixerCC7 tetapi tidak bend; MIDI capture menerima neither bend norCC7.

**RISK:** filter/modulation/pitch/RPN/GS-XG setup kurang faithful; menambah semua SysEx secara buta dapat reset synth/keyboard dan merusak balance. Jangan klaim MIDI OUT setara BASSMIDI.

**RECOMMENDED ACTION:** S3 klasifikasi ordered events tanpa menjalankannya; S5/S6 tentukan output capability/allowlist dengan semantic fixtures. Emit trace “preserved but unsupported” sampai terbukti; pertahankan authored intentional zero.

### F07 — NTT6/9 tidak diinterpretasikan; sourceChordType tidak dikonsumsi transform — P1

**FACT:** 28 NTT6 +14 NTT9 raw policies, hanya BaroqueAir1. NTR corpus0/1, RTR corpus0–4.

**EVIDENCE:** `CasmNoteTransformer.transform` branches NTT0–5; `else -> base`; tidak membaca policy.sourceChordType. `StyleSequencer.retarget` memiliki RTR0–5, RTR5 deferred; tidak ada RTR5 dalam corpus ini.

**PROJECT BEHAVIOR:** NTT6/9 menghasilkan base + existing HighKey/NoteLimit, tanpa unknown semantic error. SourceChordType disimpan/scored sebagai metadata valid, bukan sebagai source-chord interpretation oleh transform.

**GAP:** **PROVEN** fallback dan omission konsumsi field; **UNKNOWN** exact Yamaha tables 6/9 dan pengaruh sourceChordType per NTT. Explicit branch0–5 berarti implemented, bukan Yamaha-exact SUPPORTED certification. Status: NTR/NTT0–5 **PARTIAL**; NTT6/9 **UNKNOWN**; RTR0–4 implemented/**PARTIAL**; RTR5 deferred/out of corpus.

**RISK:** BaroqueAir1 chord/bass voicing tidak sesuai reference. Jangan mengubah6/9 menjadi0/2/5 demi test hijau atau menganggap seluruh handwritten transformer tests sebagai Yamaha oracle.

**RECOMMENDED ACTION:** S5 reference evidence + known input/output per root/chord/source type; unknown status harus terlapor. First divergence **policy → transform interpretation**, bukan parser NTT extraction.

### F08 — Boundary dialect belum explicit — P1

**FACT:** native parser mengenali Ctab dan Ctb2, tetapi CasmPolicy/ParsedStyle tidak menyimpan SFF1/SFF2; transformer berkomentar “SFF2” namun menerima kedua jenis policy.

**EVIDENCE:** `style_parser.h`, `parseCasm`, `StyleModel.kt`, `StyleRepository.loadStyle`, `CasmNoteTransformer.kt:7`.

**PROJECT BEHAVIOR:** satu generic plan/sequencer, tanpa capability/version boundary atau unknown dialect diagnostic.

**GAP:** **PROVEN** absennya metadata/boundary; **UNKNOWN** apakah setiap shared branch valid untuk dua dialect. Comment SFF2 sendiri bukan bukti SFF1 rusak. First divergence **container identity tidak direpresentasikan sebelum normalized policy**.

**RISK:** pembetulan SFF1 bisa merusak SFF2 ranges/guitar semantics. Enum-only change yang mengubah dispatch tanpa oracle juga berbahaya.

**RECOMMENDED ACTION:** S2 additive StyleDialect.SFF1/SFF2/UNKNOWN; S3 version-aware interpretation → common normalized plan → **satu** scheduler/output engine. Tidak menghapus SFF2.

### F09 — Multi-source notes bertahan; destination setup merupakan arbitration — P1 untuk pembuktian fidelity

**FACT:** native source parts dan policy lists mempertahankan beberapa source menuju satu destination; tidak ada map[destination] yang membuang seluruh note lane di parser.

**EVIDENCE:** `byChannel`, `part.casmPolicies`, flatmap semua parts; Unplugged2 11 source→Chord1. Namun `applyVoicesFromCasm` memakai `policies.firstOrNull`, `explicitByDestination` first explicit, `applied` satu kali per destination; mixer/appliedChannelStates per destination; dynamicBankBySource per source.

**PROJECT BEHAVIOR:** **PROVEN** note-lane preservation dan first-winner setup; non-note memakai first policy, tidak policy yang dipilih untuk chord/note. Source setup/program/controller berikutnya dapat menulis shared destination.

**GAP:** **UNKNOWN** arbitration Yamaha-exact untuk alternative root/chord lanes dengan setup berbeda. Shared synth channel state memang bukan independent voice state per source. Jangan menganggap semua destination-keyed map otomatis bug atau menghapus cache yang sudah memperbaiki presence.

**RISK:** suara/mixer source alternatif tidak sesuai event yang akhirnya dipilih; controller state tergantung flatten/order. First potential divergence **source setups → applyVoicesFromCasm arbitration**, ditambah global event ordinal loss F02.

**RECOMMENDED ACTION:** S1 capture competing source setup identities; S3 explicit documented destination-state selection; S6 compare device/native transcripts sebelum perubahan arbitration.

### F10 — legacy voiceMap kosong untuk SFF1; bukan akar silence utama — P2

**FACT:** zero Ctb2 pada503 style; `nativeExtractVoiceMap` hanya heuristic scan Ctb2.

**EVIDENCE:** `native_lib.cpp:180`, `StyleRepository.kt:24–35`, `StyleSequencer.setVoiceMap:182`; sequence note/voice path memakai Ctab policies dan authored MIDI setup.

**PROJECT BEHAVIOR:** legacy map tidak memiliki hasil SFF1; Ctab.voiceName tetap ada. `setVoiceMap` menyimpan map/reset presence/cache name, tetapi tidak digunakan untuk memilih note policy/preset pada jalur utama yang diperiksa.

**GAP:** **PROVEN** bukan SFF1 source of truth; **tidak ada bukti** map kosong menyebabkan semua part diam. Ctb2 heuristic correctness bahkan untuk SFF2 belum certified.

**RISK:** diagnostics/UI yang berharap map global dapat misleading; membuat parser voice kedua akan menambah sumber kebenaran bersaing.

**RECOMMENDED ACTION:** S2 beri legacy/dialect label; gunakan scoped Ctab policy + MIDI numeric identity. First divergence **CASM → legacy voiceMap**, jalur auxiliary.

### F11 — Bank identity melodic dipertahankan; percussion canonicalization lebih awal — P1 verification

**FACT:** corpus memakai bank Yamaha selain GM. Native parser/model mempertahankan MSB+LSB+PC; voiceName bukan substitute bank identity.

**EVIDENCE:** authored MIDI setup + dynamicBankBySource; `applyVoicesFromCasm`/dynamic PC menghitung melodic bankMSB*128+LSB; `BassMidiPlayer::setChannelPreset` memisahkan controllers dan menyimpan requested identity sebelum resolver. Destination8/9 di Kotlin diubah menjadi audio bank128 / external127:0; native juga canonicalizes drums.

**PROJECT BEHAVIOR:** melodic source identity tidak dihapus untuk menjadi GM secara universal. Existing family resolver/fallback ada di native boundary. Rhythm rendered/requested bank dan authored bank tidak sepenuhnya sama; raw source tetap tersimpan di model.

**GAP:** **PROVEN** behavior; **UNKNOWN** exact output sufficiency untuk seluruh authored Yamaha kits/banks/device. Fallback numeric/name dapat dipakai bila source setup tidak lengkap; itu bukan full conformance.

**RISK:** prematur bank canonicalization atau broad piano fallback dapat menutupi parser/gate defects; menghapus resolver merusak baseline non-GM. First output identity translation **sequencer preset request**, lalu native resolver.

**RECOMMENDED ACTION:** S3 retain authored/requested/resolved identities secara terpisah; S6 test bank/controller readback per output. Jangan mengubah bank/percussion normalization dalam audit ini.

### F12 — FillBA preserved, tetapi terminal duration +1 tick — P1 timing / P2 grammar breadth

**FACT:** corpus memiliki MainA–D, FillAA/BB/CC/DD/BA, IntroA–C, EndingA–C; FillBA adalah terminal recognized section pada corpus ini. `fn:Fill&Hit` tidak ada di Sdec.

**EVIDENCE:** `classifyMarkerText` skip optional “in”, enum FillBA, `ArrangerBrain.fillForTransition`; production fixtures memberi FillBA length7681 (4/4),5761 (PopWaltz3/4),3841 (Forro2/4). `StyleParser::parse:187` terminal end = `track.events.back().tick+1`, sehingga EOT tepat pada bar grid menghasilkan+1.

**PROJECT BEHAVIOR:** BA tidak diruntuhkan menjadiBB; directional enums lain belum mendapat corpus proof. Current modulo `(raw-phase+length)%length` serta naturalEnd memakai section length; last-boundary+1 mencapai scheduler.

**GAP:** **PROVEN** terminal+1 convention/duration; **STRONG INFERENCE** one-tick seam/grid offset bila FillBA dimasukkan satu pass; bukan measured Android transition defect. No evidence untuk playable fn:Fill&Hit atau semua16 directional fill.

**RISK:** menghapus+1 tanpa mendefinisikan boundary events dapat kehilangan final OFF/EOT atau memindahkannya ke start karena modulo. First divergence **SMF boundary/EOT → section length**, sebelum scheduler.

**RECOMMENDED ACTION:** S1 terminal-EOT/final-OFF/meter seam fixture; S3 explicit duration versus inclusive terminal event rule; perubahan hanya setelah S4 boundary ownership proof. Jangan membuat synthetic section untuk fn marker.

### F13 — Meter metadata dipakai, tetapi transition exactness belum terbukti — P2

**FACT:** Forro2/4, PopWaltz2 3/4, no encoded6/8. `detectStyleMeter` membaca raw FF58 pattern, bukan nama file.

**EVIDENCE:** corpus reader; `StyleMeter.ticksPerBar`; `ArrangerBrain.scheduleSectionChange` memakai numerator/denominator pada `millisToNextBar`. At PPQ1920, grids2/4=3840,3/4=5760,4/4=7680.

**PROJECT BEHAVIOR:** Brain menunggu dengan millisecond delay lalu `queueSeamlessTransition` memakai currentMasterTick; parameter numerator/denominator di queue tidak dipakai. Comment next-bar/phase-aligned di sequencer bukan executable guarantee: caller sudah quantizes, queue mengambil current tick, dan active section dimainkan phase 0.

**GAP:** metadata/output math **PROVEN** untuk corpus; scanner FF58 tidak memvalidasi chunk/event boundary; meter changes, real6/8, exact bar handoff under delay/load/tempo change **UNKNOWN**. Golden quick replay bukan meter timing test.

**RISK:** late coroutine wakeup menghasilkan transition tick meleset; mengganti scheduler demi meter tanpa timing evidence dapat regresi clock.

**RECOMMENDED ACTION:** S1 virtual/controlled clock tests2/4,3/4 plus explicit synthetic6/8 terpisah corpus; primary future fix boundary di S3/S4, bukan nama file atau hardcode4/4.

### F14 — Intentional absence dan unattached metadata harus dibedakan dari loss — P2, terkait P0 F04

**FACT:** raw CASM style-level role presence: Rhythm1 483, Rhythm2 503, Bass503, Chord1 503, Chord2 474, Pad439, Phrase1 450, Phrase2 317. Angka ini tidak menjamin setiap section memiliki note untuk role itu.

**EVIDENCE:** corpus raw destinations; `attachPolicy` hanya pada existing source part. Semua1.990 missing copies adalah source yang tidak mempunyai part pada target section; tidak ditemukan field mismatch pada copies yang applicable. 157 descriptor tidak represented sama sekali. `8BeatPiano1` MainD benar-benar hanya destinations9–12; replay mengirim72 ON ke9–12, tidak membuat Pad/Phrase.

**PROJECT BEHAVIOR:** parser tidak membuat part synth untuk descriptor source yang tidak ada; diagnostics raw counts memakai parsed notes, tidak full registry raw Ctab. Runtime phantom dispatch F04 adalah exception nyata yang merusak absence setelah parsing.

**GAP:** **PROVEN** metadata projection lossy; **UNKNOWN** apakah descriptor-only source perlu retained untuk future activation/arbitration. Absence sendiri bukan proof missing audio.

**RISK:** universal “semua8 part harus berbunyi” memberi false failures dan mendorong unsafe fallback.

**RECOMMENDED ACTION:** S3 simpan section CASM descriptor registry terpisah dari event lane; jangan membuat authored note sintetis. Status per section/policy/event/output: `INTENTIONALLY_ABSENT`, `PRESENT_BUT_MUTED`, `PRESENT_BUT_DROPPED`, `PRESENT_BUT_UNRESOLVED_VOICE`, `PRESENT_AND_DISPATCHED`. Dispatch tidak sama dengan native acceptance/PCM.

### F15 — Chord mask semantics dan partial transformer membutuhkan oracle — P2/P1

**FACT:** parser membaca semua five-byte bits ke64-bit; `policyScore` hanya memakai recognized chord type0..33. Raw `0x7FFFFFFFF` spans35 bits; metadata corpus source chord types dan app chord qualities bukan set yang sama.

**EVIDENCE:** `StyleModel.kt:35`, `style_parser.cpp:107–109`, `yamahaChordType`/`policyScore`; handwritten transformer tests memakai expected app outputs, bukan hardware-derived golden table.

**PROJECT BEHAVIOR:** bit1 di recognized type berarti policy playable. hard mask rejection dapat menghasilkan genuine selective lane absence; tidak boleh dibalik untuk memaksa sound. Transformer Chord NTT memakai nearest target pitch; source type metadata tidak masuk transform.

**GAP:** raw width **PROVEN**; semantic34/35, reserved bit, chord enum mapping dan Yamaha-exact transform **UNKNOWN**. Implemented branches ditandai PARTIAL, bukan unsupported total atau certified exact.

**RISK:** kesalahan bit polarity/chord numbering menjadikan lane hilang atau semuanya aktif. First possible semantic divergence **raw mask → recognized chord index interpretation**.

**RECOMMENDED ACTION:** S5 authoritative reference table; tetap preserve raw full width. Jangan mengganti comments saja lalu mengklaim semantic issue selesai.

## 5. Golden fixtures dan hasil sequencer capture

Semua angka berikut berasal dari **MainD satu pass, output capture stubs, chord C major**. F major memberi ON counts/destination counts yang sama pada tujuh run ini, tetapi pitches dapat berubah. Dibandingkan dengan raw ON untuk mengobservasi gates, bukan menjadikan raw ON==forwarded ON sebagai invariant: chord/root-selected alternatives memang boleh mute.

| Fixture | Meter | MainD raw ON | Forwarded ON | Audio destination ON counts | Interpretasi |
|---|---|---:|---:|---|---|
| `Ballad/LoveSong3.S687.prs` | 4/4 | 503 | 483 | 8:117,9:80,10:19,11:70,12:192,13:3,14:2 | Seven roles mencapai output. Source6 alternate piano19 dan source4 string1 ditolak current major policy gates. Ini bukan bukti20 missing audible notes |
| `Latin/Forro.S729.prs` | 2/4 | 357 | 321 | 8:105,9:50,10:9,11:72,12:64,14:21 | True2/4; quick replay tidak membuktikan quantization |
| `Ballad/PopWaltz2.S662.bcs` | 3/4 | 236 | 231 | 8:69,9:47,10:9,11:14,12:56,13:3,14:33 | True3/4; Phrase2 tidak dipaksa |
| `Movie&Show/BaroqueAir1.S145.sst` | 4/4 | 280 | 236 | 8:36,9:8,10:64,11:72,12:48,15:8 | **44 phantom rhythm ON (F04)**, NTT6/9 semantics unresolved |
| `Ballad/8BeatSoft.S686.bcs` | 4/4 | 153 | 141 | 8:26,9:14,10:15,11:41,12:18,13:3,14:24 | Seven original-SMF overlaps in whole file; MainD count alone bukan ownership proof |
| `Pop&Rock/Unplugged2.T151.prs` | 4/4 | 1.737 | 1.353 | 8:256,9:233,10:48,11:672,14:144 | 11 sources→Chord1; root field varies; **256 extra rhythm ON (F04)** |
| `Ballad/8BeatPiano1.T107.pcs` | 4/4 | 72 | 72 | 9:32,10:8,11:24,12:8 | Intentional missing roles retained; only40 OFF because rhythm not ledgered (F05) |

LoveSong3 source density terkonfirmasi: src2=19,3=70,4=1,5=192,6=19,8=117,9=80,10=3,12=2, sum503. Ini menunjukkan pentingnya source lane5 guitar; tidak ditemukan parser yang menghapusnya. Source7 StrumFX yang disebut comment lama **tidak menjadi note-bearing source MainD pada file LoveSong3.S687 ini**. Existing StrumFX suppression tidak dihapus: codec/articulation behavior untuk fixture lain tetap outside audit fix scope.

Tidak ada assertion bahwa mix balance, clipping #792, SoundFont choice, attack/release Yamaha atau tingkat suara perangkat sudah benar berdasarkan tabel ini.

## 6. Yang sudah benar dan harus dipertahankan

- SMF raw order/data terbukti cocok pada3.218.027 events. Native CSEG/Sdec/Ctab parsing dan Cntt-after-table override cocok pada applicable corpus records.
- Source/destination separation, source part list dan multi-policy representation sudah ada. Dibutuhkan perbaikan gate F04 dan normalization metadata, bukan rewrite parser/channel model.
- Melodic bankMSB+LSB+PC tidak diubah menjadi GM-only secara universal. Current resolver readback/diagnostics mengisolasi authored/requested/resolved state.
- FillBA dikenali; marker fungsi tidak dipaksa menjadi playable section; true 2/4 dan 3/4 metadata ditemukan.
- Existing CC11 fix tidak menimpa effective CC7; trims mempertahankan intentional zero. Destination cache membantu restore mixer setelah automation; jangan mencabutnya tanpa competing-source tests.
- `noteLifecycleLock` dan stale-object identity check sudah menangani race retarget owner lama. Itu tetap penting walaupun unique-instance ledger belum ada.
- Stage3 drum tetap bypassed; native existing compatible percussion dan measured balance/headroom baseline tetap untouched.

## 7. Tes yang ada, yang dijalankan, dan yang belum ada

### Existing test inventory

| Test / suite | Yang diuji | Batas |
|---|---|---|
| `CasmNoteTransformerTest.kt` | Root transpose, bypass, chord tones, bassOn, HighKey, octave limits | Handcrafted semantics; tidak menguji NTT6/9/source-chord table/Yamaha oracle |
| `StylePartPresenceRegressionTest.kt` | Actual scheduler; declared roles, melodic voice prefixes, rhythm raw pitches, policy/range rejection, Phrase2 | Tidak assertion scheduled rhythm OFF; tidak mencakup policy-missed melodic source8/9 real corpus |
| `StyleChordDiagnosticRegressionTest.kt` | RTR replacement messages, diagnostic byte parity, masks, shared-output observation, stale/raced owner identity | Coalescing **source berbeda** bukan overlapping same-source/key instances; current map implementation seeded lewat reflection |
| `StyleExpressionRegressionTest.kt`, `StyleMixFidelityRegressionTest.kt` | CC7/11/mute/trim/cache restore/intentional zero | Controller subset; bukan full SMF dispatch atau seluruh 503 conformance |
| `GenericDrumGlobalReplayTest`, `ProductionDrum*`, `DrumEngineeringProofTest` | Shadow/raw replay, offline engineering ownership/gates | Offline shadow proofs tidak mengubah/membuktikan production StyleSequencer ledger |
| `tests/bank_translation_readback_test.cpp`, `accompaniment_presence_test.cpp` | Production BassMidiPlayer native bank/presence API | Mock synth; native direct noteOff bukan sequencer-origin OFF proof |
| `tests/percussion_fidelity_test.cpp`, `native_resolver_test.cpp`, `voice_resolver_test.cpp` | Native routing/resolver/repeated-note percussion ownership | Mock API; tidak menguji lost upstream event order |
| `tools/test_production_regression_bypass.py`, `test_mix_percussion_boundaries.py`, role/diagnostic guards | Hash/structural protection reviewed playback baseline, Stage3 exclusion | Fidelity gap yang sudah ada dapat lolos guard; guards bukan Yamaha oracle |
| `test_percussion_real_bass.py`, `test_measured_balance.py`, `test_pcm_headroom.py`, real PCM suites | Native PCM and observed baseline parity fixtures | Existing CI programs; **tidak dijalankan auditor**, tidak membuktikan full SFF1 semantics atau device sample content |

**Executed host suite:** `tools/test_voice_resolver.py` selesai exit0: 210 resolver,70 native routing,48 audio-path,30 SF2-zone,15 all-kit,30 compact-export,29 focused-comparison checks;12 managed audition scenarios;63 bank-readback,32 accompaniment-native,238 percussion-native,177 role-PCM-native,4 liveness,46 shadow host checks. Structural regression/Stage3/mix/role/diagnostic guards lulus. Semua mock/native-host scope; jangan mengubahnya menjadi claim acoustic pass.

**Executed audit probes:** seluruh 503 production parser + independent field/event comparison; original overlap/root counts; five production scheduler counterexamples; fourteen MainD captures (7 files × 2 roots). Compiler stubs tidak mengganti scheduler/transformer. Real-clock precision, all-section transitions, note-pair oracle dan real output belum diuji.

### Missing acceptance tests

1. Versioned seluruh 503 conformance manifest, source archive hash, raw record identities, source/event ordinals, empty descriptors, deterministic normalized plan digest.
2. Policy-null on melodic source8/9 → **tidak menjadi synthetic rhythm**; absence-aware assertions pada BaroqueAir1/Unplugged2 dan legacy no-CASM fixture.
3. Same tick OFF→ON, ON→OFF zero duration, banks/PC/controller→ON, cross-source original order; assert exact output transcript **dan owner lifetime**, bukan count saja.
4. All47 overlapping-note files with independent source-instance pairing, plus transforms that coalesce different source notes onto one output pitch; native vs external ownership separate.
5. Root-mask raw preservation257records/49styles; interpretation root 0..11, chord numbering/polarity and reserved bits setelah oracle tersedia. NTT6/9/sourceChordType known Yamaha reference output.
6. Rhythm/no-policy forwarded ON has defined release owner; native percussion generation/choke/FIFO + MIDI duration tests, existing one-shot decay unchanged unless intentionally revised.
7. Full non-note preservation/dispatch capability (pitch bend, mod/filter/RPN, global setup/SysEx); explicit unsupported status; tick0 replay/setup only-once ordering.
8. Real2/4,3/4, explicit synthetic6/8; terminal FillBA EOT+finalOFF; Main→Fill→Main and Intro/Ending under controlled clock, tempo/load/cancel/chord change.
9. Competing source bank/program/controller policy arbitration on common destination; retarget destination switch preset identity; authored/requested/resolved roles kept separate.
10. BASS PCM and external MIDI/PSR-E343 parity from same normalized plan; device readback and sustained duration measurement. Dispatch, acceptance, voice-resolved and PCM-present are different assertions.

## 8. Implementation plan bertahap — belum diimplementasikan

Setiap stage harus menjadi perubahan terisolasi, dibandingkan dengan frozen #792 baseline. Regression risks tidak diselesaikan dengan force-volume, piano fallback, universal 8 part sound atau global allNotesOff. S1 harus berisi baseline-known failures dengan expected statuses; jangan membikin test hijau dengan menganggap perilaku cacat sebagai target akhir.

### S1 — Corpus conformance harness (stage paling aman)

- **Files proposed:** `tools/audit_sff1_corpus.py`, `tests/sff1_parser_conformance_test.cpp`, `tests/fixtures/sff1_manifest.json`, Kotlin `Sff1ProductionPipelineRegressionTest.kt`; optional CI job read-only. Tidak menambahkan seluruh archive tanpa keputusan distribusi; corpus location/hash configurable.
- **Minimal change:** durable harness dari probe audit; raw+native+Kotlin plan captures, known-failure ledger F01–F15. Separate record counts, section copies, eligible events dan output roles. Collect seluruh 503 parse, seluruh 47 overlaps dan 49 style root-mask fixtures; golden seven fixtures mandatory.
- **Invariant:** production source/PCM/MIDI untouched; no stage3 activation, no synth calls dari corpus scanner, no fake part/fixture expected silence removal.
- **Test:** seluruh 503 native comparison; exact F02/F03/F04/F05/F06 counterexample transcripts; byte-identical baseline captures for unaffected paths.
- **Rollback:** remove harness/test-only commit; #792 production unchanged.
- **Risk:** low runtime, medium false-oracle risk; root/NTT semantics marked UNKNOWN until proven. Harness harus menguji actual production functions, tidak hanya copied predicates.

### S2 — Explicit SFF1/SFF2 dialect boundary

- **Files proposed:** `style_parser.h/.cpp`, `native_lib.cpp`, `NativeStyleBridge.kt`, `StyleModel.kt`, `StyleRepository.kt`; dialect unit fixtures.
- **Minimal change:** additive SFF1/SFF2/UNKNOWN detection/provenance/capability metadata, transfer through JNI. Initially select same legacy playback behavior; do not change transformer dispatch in this stage. Header/marker+Ctab/Ctb2 evidence with conflict/unknown handling.
- **Invariant:** one scheduler; all existing bank/mix/voice behavior unchanged; seluruh 503 detected SFF1; SFF2 not deleted.
- **Test:** SFF1 corpus, independently controlled SFF2/unknown/conflicting/malformed fixtures; plan metadata versus unchanged musical transcript.
- **Rollback:** separate metadata commit; legacy path recoverable.
- **Risk:** low/moderate JNI/model compatibility; dialect enum mislabeled sebagai semantic certification harus dilarang.

### S3 — Normalized CASM plan, initially read-only

- **Files proposed:** parser/models/JNI/repository above; new `NormalizedStylePlan.kt`/`CasmPolicyNormalizer.kt`; `StylePartPresence.kt` for explicit reason/provenance. Sequencer adapters only after plan parity established.
- **Minimal change:** store raw CASM fields including bytes10–12/tail, raw policy identity+CSEG/Sdec, descriptor-only lanes, event ordinal, authored setup and per-output capability. Distinguish absent declaration from incompatible policy. Build ordered common plan without switching playback initially; compare legacy captures. Destination setup arbitration specified rather than silently first-winner. Subsequent **separate small commit** addresses F04 gate under explicit declarations, tested on legacy no-CASM too; no broad CASM rewrite bundled with it.
- **Invariant:** do not create notes for empty descriptors; preserve source lane identity, seluruh 32.913 descriptor mentah inventory, Cntt precedence, full five-byte chord mask, source bank/program/controller, FillBA. Raw root mask is retained **without guessed musical gate**.
- **Test:** expected73229descriptor expansions versus attached71239event-lane policies; explanation for1990missing copies; raw ordinal recovery; conflicting destination setup; absence/mute/drop/unresolved/dispatch distinction; exact phantom-rhythm regression.
- **Rollback:** normalized plan observation off/legacy adapter remains until parity/counterexamples approved; retain pinned plan digests.
- **Risk:** moderate/high once dispatch switches; fixes exposing old fallback defects alter sound. Do not silently normalize unknown NTT.

### S4 — Unique note-instance ownership and ordered lifecycle

- **Files proposed:** `StyleSequencer.kt`, normalized event models; `StyleChordDiagnosticRegressionTest.kt` migrations; new instance-ownership/rhythm/transition tests. Native adapter only if output evidence requires it, as separate substage.
- **Minimal change:** token e.g. `(sectionGeneration,sourcePart/sourceChannel,onId)`; deterministic per-source/key OFF pairing; keep original event identity; output-owner registry for coalesced keys, no pitch deduplication. Ordered same-tick lifecycle verified before removing ON-first comparator. Cover every forwarded ON including rhythm/no-policy with explicit release behavior. Preserve lock/stale-reference protection. Diagnostics onId becomes joinable instance identity, not just incidental logging.
- **Invariant:** OFF follows recordedON destination/pitch despite later chord/preset; outgoing section releases only its owners; keyboard0–3/locks/mutes and existing CC11/mixer behavior intact. No global allNotesOff as ownership fix; no channel-wide bend substituted for polyphonic retarget.
- **Test:** seluruh 47 original overlaps; ONONOFFOFF, opposite same-tick orders, PC before attack; stale retarget race; simultaneous source instances coalescing output; section loop/Fill/EOT/seam and rhythm/choke. Native NOTEOFF1/device behavior measured separately.
- **Rollback:** isolated ownership commit and previous legacy transcript; staged capability/feature selection confined to tested path. Keep diagnostics to compare full lifetime, not note count only.
- **Risk:** **high**: changed decay/sustain/polyphony; plain MIDI lacks instance tokens, therefore internal identity alone does not certify hardware release semantics.

### S5 — Remaining SFF1 CASM semantics

- **Files proposed:** `CasmNoteTransformer.kt`, version-aware interpreter, `policyScore/selectPolicy` adapter; authoritative fixtures/documented semantics tables; parser Ctab Bass-On fallback separately if required outside corpus.
- **Minimal change:** one proven semantics group per commit: root-selection polarity/orientation, chord mask/type numbering, sourceChordType interpretation, NTT6/9 then needed RTR behavior. Close capability statuses only when expected outputs exist. Section duration/EOT correction is separate from note transform and uses S4 boundary oracle.
- **Invariant:** unknown remains explicit; never alias6/9 to anotherNTT; no force-activation rejected lanes; SFF2 ranges/guitar path kept behind own interpreter. Preserve HighKey/NoteLimit intent and authored notes.
- **Test:** BaroqueAir1 per-source/per-root/per-chord reference;49root-mask styles and all12 roots; known source-major/minor/extended chord tables; remainder503 regression; separate SFF2 tests.
- **Rollback:** each semantics group revertible, prior capability statuses/report available.
- **Risk:** **high** until primary Yamaha/hardware oracle; corpus distributions alone cannot supply algorithms.

### S6 — BASSMIDI versus external MIDI verification

- **Files proposed:** output transcript tests around AudioEngineManager/MidiInputManager, `tests/sff1_real_bass_pipeline_test.cpp`, device protocol capture fixtures; resolver diagnostics/readback adapter only after specific proven gap.
- **Minimal change:** render/capture same plan into two independent backends; compare bankMSB/LSB/PC/controllers, ON/OFF lifetime, preset readback, non-note capability and intentional absence. Record realBASS PCM and external device behavior separately. Address F06 events/early percussion identity translation in small supported-output commits, not all SysEx at once.
- **Invariant:** #792 percussion/melodic bank resolver, measured trims, PCM observers, native mapping/preload, clamp and existing Stage3 bypass remain untouched unless separately authorized evidence warrants a change. Real device SF2 samples cannot be substituted by same-name synthetic fixtures in conclusions.
- **Test:** tujuh golden fixtures plus dense Phrase2 chosen from manifest; seluruh 47 fixture ownership dan 49 style root-mask acceptance; LoveSong Main/Fill/Intro/Ending duration/part presence; true 2/4,3/4. Output MIDI OFF/PC/controller enable-state policy explicit. PCM presence≠loudness≠Yamaha timbre equivalence.
- **Rollback:** independently revert backend-specific changes; retain same normalized input digest and #792 output controls.
- **Risk:** moderate/high; hardware timing/SysEx/reset/bank behavior and actual SF2 layer/loop fidelity require measured evidence.

## 9. Rekomendasi akhir A–D

### A. Sepuluh temuan terpenting

1. All503 parse dan effective Ctab/Cntt mapping yang applicable benar, tetapi complete raw-policy normalization belum ada:157 descriptor mentah tidak hidup di targetsection mana pun.
2. Ctab11–12 hilang;257 record non-default pada 49 style; root-selection semantics belum certified.
3. ON-first comparator mengubah OFF/PC ordering, dengan production counterexample immediate truncation/preset-too-late.
4. Satu source:key owner tidak mewakili523 overlap pada 47 style; existing stale-owner lock membantu race tetapi tidak instance multiplicity.
5. Policy-missed melodic source8/9 menjadi phantom rhythm:44 ON BaroqueAir1 dan256 ON tambahan Unplugged2 MainD.
6. Rhythm/no-policy scheduledOFF tidak diteruskan; native direct-OFF tests tidak menutup gap sequencer ini.
7. Pitchbend/controller data preserved tetapi tidak fully dispatched; externalMIDI tidak menerima style mixerCC path.
8. NTT6/9 fallback silent dan sourceChordType tidak dipakai transformer; exact remaining semantics UNKNOWN.
9. Multi-source note lanes bertahan, tetapi destination setup first-winner/non-note firstpolicy arbitration belum Yamaha-proven; legacy voiceMap bukan SFF1 source of truth.
10. FillBA dipertahankan, terminal+1tick dan exacttransitionquantization perlu test; intentionalmissingroles tetap sah dan tidak boleh dipaksa sound.

### B. First divergence paling mungkin untuk style kosong/ACMP rusak

**Untuk short/missing sustained notes:** earliest reproducible runtime defect adalah event ordering F02 dan single-owner lifecycle F03. Kedua mechanism mengirim OFF terlalu awal sebelum audio/resolver; trace lifetime harus diperiksa sebelum memodifikasi voice/gain.

**Untuk wrong/percussive ACMP:** F04 terbukti langsung pada corpus, pada policy-null → source-based rhythm fallback. Root-mask omission F01 berada lebih upstream, tetapi akibat pasti perroot masih UNKNOWN; jangan menyimpulkan omission itu menjelaskan LoveSong3 silence.

**Untuk hilangnya lane tertentu:** periksa raw section/source → applicable CASM → selection rejection → transform → bridge → native acceptance → resolvedvoice → PCM. Current chordmask/unsupportedStrumFX/mute/lock dapat intentionallydrop. LoveSong3 MainD capture sudah mengirim guitar 192, bass 19, piano 70, pad 3 dan phrase 2 notes; bila perangkat masih terdengar kosong, lihat native/PCM evidence pada setting/style/chord yang sama. **Tidak ada bukti di audit ini bahwa parser membuang guitar lane5 LoveSong3, atau bahwa SF2/gain adalah akar universal.**

### C. Stage pertama paling aman

**S1 corpus conformance harness**, test/observation-only dengan exact SHA/corpus identity dan known failures. Jangan langsung melompat ke root mask gate, ownership rewrite atau voice fallback. Seluruh fix berikutnya harus menunjukkan first divergence yang diatasi dan transcript sebelum/sesudah.

### D. Bagian yang harus untouched

Production playback pada audit ini; Stage3 bypass; percussionbalance/choke/compatible mappings; measuredbalance #791 dan read-only #792 headroom observers; existing native bank/voice resolver/font mappings/preload; PCM render/gain/clamp; existing CC11 cache/trim/intentionalzeros; ACMP/chord detection; keyboard channel ownership; existing MIDI routing and Telegram build workflow. Jangan force all channels, replace Yamaha bank with GM, addpiano fallback, atau global allNotesOff untuk menutupi upstreamloss.

**Stop point:** dokumen ini adalah satu-satunya perubahan repository yang dimaksudkan. S1–S6 belum dilaksanakan. Tidak ada klaim Yamaha-exact acoustic equivalence, PSR-E343 pass, SFF2 certification atau 6/8 corpus proof.

## 10. Recipe untuk mengulang bukti utama tanpa mengubah produksi

Gunakan checkout exactbaseline dan scratch output. Compile existing production fixture extractor:

```bash
g++ -std=c++17 -O2 -DANDROID_LOG_ERROR=6 \
  -I tests/mocks -I app/src/main/cpp \
  tools/dump_style_shadow_fixture.cpp \
  app/src/main/cpp/style_parser.cpp app/src/main/cpp/smf_reader.cpp \
  -o /tmp/sff1-production-dump
/tmp/sff1-production-dump /path/to/sff1/Ballad/LoveSong3.S687.prs
python3 tools/test_voice_resolver.py
```

Extractor existing mencetak parsed section-policy copies, bukan raw CSEG counts, sehingga jangan menjumlahkan C-lines lalu mengharapkan32913. Untuk raw root field inventory: bounded-walkCASM→CSEG→Ctab, `int.from_bytes(payload[11:13], 'big')`; count values!=0x0FFF dan distinct files. Untuk Cntt apply `source=d[0]`, `ntt=d[1]&127`, `bassOn=(d[1]&128)!=0`, perCSEG, sebelum membandingkan Ctab projection.

Untuk original overlap: baca original SMF event order; increment multiplicity pada 0x9n velocity>0, count collision ketika pre-count>0; decrement pada 0x8n atau0x9n velocity0; jangan sort ON first. Expected: 523 collision events / 47 files / maximum 2. Jika menambahkan harness permanen pada S1, simpan complete source-instance/CSEG/raw ordinal provenance dan output reason untuk setiap missing descriptor/candidate.

Untuk production sequencer probe: compile exact `StyleSequencer.kt`, `CasmNoteTransformer.kt`, `StyleModel.kt`, chord sources dan diagnostics; gunakan coroutineScope, captured AudioEngineManager/MidiInputManager, Cmajor policy src4→dst11, PPQ1_000_000_000, single pass. Inputs dan expected **observed buggy baseline**, bukan targetfix:

```text
ON(0), OFF(1), ON(1), OFF(2)
  -> ON(0), replacement-OFF, ON(1), scheduled-OFF(1); OFF(2) unowned
PC40(1), ON(1), OFF(2)
  -> ON(1), PC40(1), OFF(2), after initial section header
ON(0), ON(1), OFF(2), OFF(3)
  -> ON(0), replacement-OFF(1), ON(1), OFF(2); OFF(3) unowned
policy src4->dst9, ON42(0), OFF42(1)
  -> ON42 only; no scheduled audio/MIDI OFF
bend(1), CC7=80(1), ON(2), OFF(3)
  -> native mixer CC7 call, no bend; MIDI has ON/OFF, no style CC7/bend
```

Golden Main D source8/9 policy miss counterexamples harus direplay dari output parser, bukan copied gate. Set chord C major / F major; compare declared policy destination dengan actual captured output destination. real BASS/Android/hardware proof tetap membutuhkan S6.
