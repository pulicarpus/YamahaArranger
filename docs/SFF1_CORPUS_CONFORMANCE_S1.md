# S1 — SFF1 corpus conformance harness

Tanggal: 2026-10-07. Scope: **S1 saja, observation/test-only**. Tidak ada perbaikan F01–F15, perubahan playback, S2, build APK, atau pengiriman Telegram.

## A. Branch dan baseline

Branch: `fix/mix-percussion-fidelity-784`. Frozen production baseline: Build #792 / `05cdb1f0998d082d701bc0d1979580270ae2eefc`.

Dokumen ini masuk satu commit S1 bersama harness dan audit prasyarat yang sebelumnya belum tracked. Identitas commit S1 diperoleh dengan `git log -1 --format='%H %s'`; SHA tidak ditulis di dalam commit yang mereferensikan dirinya sendiri. Commit dibuat lokal. Push branch ini memicu workflow `build.yml`, termasuk assembleDebug dan pengiriman APK Telegram, sehingga tidak dilakukan pada S1.

Audit penuh [SFF1_REFERENCE_IMPLEMENTATION_AUDIT.md](SFF1_REFERENCE_IMPLEMENTATION_AUDIT.md) tetap menjadi laporan audit-only pada baseline tersebut. Kalimat stop point di sana menjelaskan scope audit sebelumnya; laporan ini mencatat pelaksanaan S1 sesudahnya.

## B. File dan mekanisme

| File | Tujuan |
|---|---|
| `tools/audit_sff1_corpus.py` | Walker SMF/CASM independen, memanggil parser produksi untuk seluruh corpus; strict manifest/fixture comparison |
| `tests/sff1_parser_conformance_test.cpp` | Observer langsung pada `SmfReader` dan `StyleParser`, tanpa copied parser/routing predicates |
| `tests/fixtures/sff1_reference.json` | Compact pin: 503 file identities and full per-style provenance digests, aggregate/golden counts, production identity, ledger and generated full-manifest SHA |
| `tests/fixtures/sff1_known_failures.json` | Ledger F01–F15 dengan status/reason/evidence |
| `app/src/test/resources/sff1_main_d_native.tsv` | Output parser produksi tujuh MainD fixtures; raw origin ordinal per projected event |
| `app/src/test/resources/sff1_pipeline_digests.tsv` | 20 capture digests: enam synthetic + tujuh golden × dua roots |
| `app/src/test/java/com/yourapp/yamahaarranger/arranger/Sff1ProductionPipelineRegressionTest.kt` | Actual repository decoder/setup functions, sequencer, transformer dan diagnostics |
| `tools/test_sff1_pipeline.py` | Runner host JVM17 untuk tes S1 dan lima existing regression classes |
| `tests/fixtures/sff1_jvm_dependencies.json` | Version, URL Maven Central dan SHA-256 dependency jars |
| `tests/sff1_jvm_stubs/{AudioEngineManager,MidiInputManager,DebugLog,NativeStyleBridge,Timber}.kt` | Boundary facades untuk compile host saja; tidak masuk Android source sets |
| `tools/test_sff1_harness.py` | Lima fail-closed checks: input identity/count, no overwrite, bounded SMF/CASM reads |
| `docs/SFF1_REFERENCE_IMPLEMENTATION_AUDIT.md` | Audit prasyarat dibawa masuk tanpa perubahan hasil/semantics |
| `docs/SFF1_CORPUS_CONFORMANCE_S1.md` | Hasil, batas bukti dan recipe S1 ini |

Tidak ada archive 503 style, jars, executable, APK, observed build output atau CI modification dalam commit. Corpus location configurable (`--corpus ZIP_OR_DIRECTORY`). ZIP harus cocok dengan audited SHA; extracted directory harus cocok dengan exact set 503 relative paths/file hashes. Output sementara berada di `build/`.

Native observer tidak menambahkan ordinal ke model produksi. Python menghitung observer-side join `(relative path, section, part/source, part-event ordinal, raw-event ordinal, copy-kind)` dan digest lengkapnya. Setiap policy mempunyai CSEG/subchunk ordinal, payload byte offset, source/destination, raw/mapped hashes, root raw value, effective NTT/Bass-On, attached/missing sections. Missing copies mempunyai alasan `NO_PARSED_SOURCE_PART_IN_SECTION`. Seluruh 523 overlap menyimpan ON ordinal dan prior outstanding ON ordinals; itu collision candidate, bukan jumlah acoustic failures.

Kotlin mengemas native observer rows menurut bentuk existing JNI, lalu menjalankan private `decodePackedEvents`, `parseCasmPolicies`, dan `extractVoiceSetup` repository asli melalui reflection. `Unsafe.allocateInstance` hanya membuat repository shell agar constructor tidak memuat JNI; `loadStyle`/JNI tidak dipanggil. Ini bukan full Android/JNI integration test. Host `NativeStyleBridge` melempar error bila dipanggil. Audio/MIDI mocks mengamati output API calls; comparator, selection, ownership dan transformer semuanya source produksi.

F02/F03 memakai existing production diagnostic observer dengan observer-clock konstan agar capture window tidak flaky. Scheduler clock tidak diganti. Lifecycle decisions ikut diasert/digest, karena ON/OFF counts saja tidak membedakan replacement-OFF dari scheduled-OFF yang benar. Mockito agent dipasang eksplisit pada JVM host; tidak ada agent di app. Native compile mempertahankan warning existing parser `misleading-indentation` sebagai warning, dengan warning lain fatal; source produksi tidak diformat ulang.

## C. Conformance seluruh 503

Semua acceptance counts cocok dengan audit; tidak ada expected count yang disesuaikan agar hijau.

| Observasi | Hasil strict |
|---|---:|
| Production parse / SFF1+CASM | 503/503 |
| SMF0, one track, PPQ1920 | 503/503 |
| Raw SMF events dibandingkan seluruh fields/payload/order | 3.218.027 |
| CSEG / Sdec | 3.584 / 3.584 |
| Raw Ctab / Cntt / Ctb2 | 32.913 / 32.913 / 0 |
| Expected section-policy copies | 73.229 |
| Attached/applicable copies | 71.239 |
| Missing copies / sections / styles | 1.990 / 1.084 / 196 |
| Raw descriptors never attached | 157 |
| Original-order overlapping ON / styles / max multiplicity | 523 / 47 / 2 |
| Multi-source→destination groups / max sources | 9.373 / 11 (Chord1) |
| Ctab bytes11–12 != 0x0FFF / styles | 257 / 49 |
| Effective NTT | 0=15.068; 1=4.866; 2=10.600; 3=760; 4=36; 5=1.541; 6=28; 9=14 |
| Actual meter | 463×4/4; 39×3/4; 1×2/4; 0×encoded6/8 |
| Terminal FillBA baseline lengths | 7681×463; 5761×39; 3841×1 |

**Count vocabulary:** raw record adalah satu subchunk asli (Ctab dan Cntt dihitung terpisah); section-policy copy adalah satu effective Ctab policy setelah Cntt, diperluas melalui Sdec. Parsed raw ON MainD mencakup alternative source lanes. Eligible event Yamaha-exact **NOT_EVALUATED**, karena selection oracle belum ada. Dispatched event adalah satu observed output call pada boundary. Native acceptance dan PCM presence **NOT_MEASURED**. Dengan demikian raw ON==dispatched ON tidak menjadi invariant universal.

Corpus SHA-256: `a9b89b90e1dee25c714a076a125d6c7173bcca0e8d2029c57092b3295035d465`.

Manifest content digest: `4e2fd4a9719b826cd810a6e1eac62fc3e40dbb3eb2996ff9374e04b87da2d543`. Ini SHA atas canonical JSON tanpa field digest itu sendiri, bukan hash formatting file. Golden native resource SHA-256: `5136410612a6a721b2fc2b8e06cdb20f1fa38129870e7a5b4943f176c9c3b1a4`.

## D. Ledger F01–F15

Green S1 berarti observasi baseline dapat direproduksi. `BASELINE_KNOWN_FAILURE` tidak berarti defect telah diperbaiki atau menjadi target musikal yang benar.

| ID | Status | Reason / batas |
|---|---|---|
| F01 | `BASELINE_KNOWN_FAILURE` | Ctab bytes11-12 not represented in native/Kotlin policy; root bit semantics UNKNOWN |
| F02 | `BASELINE_KNOWN_FAILURE` | ON-first same-tick reorder and preset too late |
| F03 | `BASELINE_KNOWN_FAILURE` | Single source:key owner replaces overlapping instance |
| F04 | `BASELINE_KNOWN_FAILURE` | Policy-rejected melodic source8/9 falls back to rhythm8/9 |
| F05 | `BASELINE_KNOWN_FAILURE` | Rhythm and no-policy fallback ON do not receive scheduled OFF |
| F06 | `BASELINE_KNOWN_FAILURE` | Partial non-note dispatch, external MIDI lacks style CC7/bend |
| F07 | `UNKNOWN_SEMANTICS` | NTT6/9 base fallback observed, exact Yamaha meaning not certified |
| F08 | `BASELINE_KNOWN_FAILURE` | No explicit dialect identity in production model; this alone does not prove silence |
| F09 | `UNKNOWN_SEMANTICS` | First-destination setup arbitration observed; Yamaha correctness unresolved |
| F10 | `NOT_APPLICABLE` | Ctb2 legacy voiceMap is not an SFF1 source of truth; Ctab voice preserved |
| F11 | `UNKNOWN_SEMANTICS` | Melodic source banks preserved; output percussion translation needs backend/device evidence |
| F12 | `BASELINE_KNOWN_FAILURE` | FillBA preserved but terminal section includes last tick+1 |
| F13 | `UNKNOWN_SEMANTICS` | True meter metadata preserved; exact real-clock transitions unmeasured |
| F14 | `PROVEN_PRESERVED` | No synthetic notes for absent parsed source; 1990 descriptor copies still omitted from model |
| F15 | `UNKNOWN_SEMANTICS` | Full five-byte chord field preserved; bit/type/root semantic oracle missing |

F14 `PROVEN_PRESERVED` hanya menyatakan tidak dibuat synthetic notes untuk source yang absen dan golden absence tetap bertahan. Hilangnya 1.990 descriptor copies dari model tetap dicatat, dan F04 masih melanggar absence pada runtime. NTT6/9, root/chord masks dan Yamaha output semantics tidak diberi guessed mapping.

## E. Exact counterexample results

Audio dan MIDI call order diasert, termasuk initial section header PC. ON/OFF below menggunakan output pitch60 kecuali rhythm42. Lifecycle provenance berasal dari diagnostics produksi.

| Temuan / input | Observed frozen baseline |
|---|---|
| F02 `ON0, OFF1, ON1, OFF2` | Header PC11=0; ON0 → replacement-OFF(owner origin0) → ON1 → scheduled-OFF(owner origin1 pada tick1); tick2 `OFF_NO_ACTIVE_LEDGER` |
| F02 `PC40(1), ON(1), OFF(2)` | Header PC11=0; ON11:60 → PC11=40 → OFF11:60, pada audio dan MIDI |
| F03 `ON0 #1, ON1 #2, OFF2 #1, OFF3 #2` | Header PC11=0; ON0 → replacement-OFF1(owner origin0) → ON1 → scheduled-OFF2(owner origin1); tick3 `OFF_NO_ACTIVE_LEDGER` |
| F04 BaroqueAir1 MainD | Declared melodic destinations10/11/12/15; src8→fallback8=36 dan src9→fallback9=8: **44 phantom rhythm ON** |
| F04 Unplugged2 MainD | Authorized rhythm src15→8=128 dan src14→9=105; rejected melodic src8→fallback8=128 dan src9→fallback9=128: **256 additional rhythm ON**; final destinations8=256,9=233 |
| F05 src4→dst9 `ON42(0), OFF42(1)` | Header PC9=0 lalu ON9:42; tidak ada scheduled audio/MIDI OFF; ledger kosong |
| F05 no-policy/no-chord fallback `ON60(0), OFF60(1)` | ON4:60 di audio/MIDI; tidak ada scheduled OFF |
| F06 `bend(1), CC7=80(1), ON(2), OFF(3)` | Native mixer CC7 header127 lalu80; tidak ada bend call. MIDI header PC11=0 lalu ON/OFF; tidak ada style CC7/bend |

F04 hasil sama pada C major/F major. Capture tidak membuktikan synth menghasilkan drum PCM tertentu. Canonical capture menyimpan urutan dan functional arguments (termasuk source, destination, pitches, velocity, banks/mixer dan event tick bila API memilikinya); diagnostic IDs, sample flags, global Mockito sequence number dan wall clock dikecualikan. F02/F03 menambahkan lifecycle reason/provenance yang stabil.

| Synthetic capture | SHA-256 |
|---|---|
| `F02_same_tick_off` | `c1ad180eec0ff15de462f29db32ea39e65d6760ceb7e7ab9739b0eeb8209c4a8` |
| `F02_same_tick_program` | `a3ff2998c5967c0f99028e0068c9559584b5ad68ef3ce5a14e260a6e5abe1999` |
| `F03_overlap` | `6d1f2ee1a784586941bb58944c8807350f5e29292ecf83c5d245970e99f76e46` |
| `F05_no_policy_off` | `ac4f102801f79bd9cc36707173b96c8d3f005ac291edbd333260dd51936a64f3` |
| `F05_rhythm_off` | `f56587f03a997df2151b2ea1432e859a19d1a3da2e0679cc97e4ca0b54ff9461` |
| `F06_non_note` | `c2a0be7d88c579a767a0aa2a22b251369add070b4a3f396d57318a9f3d1fa180` |

## F. Golden MainD counts dan digests

| Fixture | Raw ON | Dispatched ON | Destination ON counts |
|---|---:|---:|---|
| `Ballad/LoveSong3.S687.prs` | 503 | 483 | 8:117, 9:80, 10:19, 11:70, 12:192, 13:3, 14:2 |
| `Latin/Forro.S729.prs` | 357 | 321 | 8:105, 9:50, 10:9, 11:72, 12:64, 14:21 |
| `Ballad/PopWaltz2.S662.bcs` | 236 | 231 | 8:69, 9:47, 10:9, 11:14, 12:56, 13:3, 14:33 |
| `Movie&Show/BaroqueAir1.S145.sst` | 280 | 236 | 8:36, 9:8, 10:64, 11:72, 12:48, 15:8 |
| `Ballad/8BeatSoft.S686.bcs` | 153 | 141 | 8:26, 9:14, 10:15, 11:41, 12:18, 13:3, 14:24 |
| `Pop&Rock/Unplugged2.T151.prs` | 1737 | 1353 | 8:256, 9:233, 10:48, 11:672, 14:144 |
| `Ballad/8BeatPiano1.T107.pcs` | 72 | 72 | 9:32, 10:8, 11:24, 12:8 |

Counts sama untuk F major; pitches/digests tidak harus sama. LoveSong3 MainD source density: 2=19,3=70,4=1,5=192,6=19,8=117,9=80,10=3,12=2 (total503). Tes mengasert lane5→destination12 sebanyak192 ON. Selisih20 tidak disebut20 missing audible notes. 8BeatPiano1 mempertahankan absence dengan hanya destinations9–12.

| Fixture | C major capture SHA-256 | F major capture SHA-256 |
|---|---|---|
| `Ballad/LoveSong3.S687.prs` | `94a9cb5b2190f1e52f57d39e0387132152cc09a445abd067c5f37bfeb924b831` | `06970117f570674c3eb28e168553052d99d2b754cc7a1ae9bd9d3b37669893d0` |
| `Latin/Forro.S729.prs` | `e507da42ece1f3801cc106a2829d5f2124efafec2a653b17ba556ed82339f20f` | `994ed61a3551999c909f47b8d7de929dc16a7f49882ac768c7d042e07609b8dc` |
| `Ballad/PopWaltz2.S662.bcs` | `eae1428588b4aa5ca85e072b4be8788ce6d3396c138917c8abe6cc4232b19f1a` | `bbb40083dbb011fe12d041e1baea9ef397c34ecffab46dbd554af0b981247fff` |
| `Movie&Show/BaroqueAir1.S145.sst` | `c3426a785e319611e1e5fc7050b229223a423d8b59cf9666fcaf370dfd3b6fa4` | `04aff8993c3a494077d8100072a7e18985dba31211bb329bce9a897fd303c0fb` |
| `Ballad/8BeatSoft.S686.bcs` | `1a31a6ef45ecbda237bfa5d67552f4fced1562ad1abc6e503e67f4b656ec038c` | `8c6b4fd4e1cd8116a1da57679b2b6977d3ad8ab1d81a5aa5e4487eaa7fa23ceb` |
| `Pop&Rock/Unplugged2.T151.prs` | `3e6320b3511428a87e0882a43247aa2b721a79d94b62e92220b1db7e67c91bb9` | `c3af62a234f5b5e6ac066e2dfd6b47d58ae20cb110714adbfa3fb61ddfe36619` |
| `Ballad/8BeatPiano1.T107.pcs` | `836e8abea7f66dc9cb62b759e5c0df6bbc719677cea4ff1a9687029b55c2f4ef` | `1e5017307ff1b7e5c304eed2a8e4d6683fb48d110f6f3c81805b3a5726799ae9` |

Digests ini observed functional transcript, bukan waveform digest atau hardware Yamaha oracle. Full 20-row index disimpan di `app/src/test/resources/sff1_pipeline_digests.tsv`.

## G. Validasi dan recipe

| Suite | Hasil / scope |
|---|---|
| Corpus strict, extracted directory | PASS503/503; seluruh per-style manifest/provenance, aggregate dan native golden resource cocok |
| Corpus strict, original ZIP | PASS503/503; archive identity dan extraction path ikut divalidasi |
| Production Kotlin S1 + lima existing classes | OK50 tests: 7 S1 +43 existing; strict20-row capture set/digests cocok |
| Harness failure/bounds | OK5 tests; wrong archive, incomplete directory dan initial-record overwrite gagal tanpa mengubah expected |
| `tools/test_voice_resolver.py` | Exit0; existing host/native mock suites dan structural playback guards lulus |

Lima existing Kotlin classes: `CasmNoteTransformerTest`, `StylePartPresenceRegressionTest`, `StyleExpressionRegressionTest`, `StyleMixFidelityRegressionTest`, `StyleChordDiagnosticRegressionTest`. Mereka dikompilasi dari source asli bersama S1 pada plain JVM; bukan Gradle Android test run.

Host umbrella mencakup 210 resolver,70 native-routing,48 audio-path,30 SF2-zone,15 all-kit,30 compact-export,29 focused checks,12 managed audition scenarios,63 bank-readback,32 accompaniment-native,238 percussion-native,177 role-PCM-native,4 diagnostic-liveness dan46 shadow checks. Regression bypass, mix/percussion, role-PCM dan diagnostic guards PASS; **Stage3 absent dari production source/JNI**. Nama role-PCM suite tidak mengubah batas mock API menjadi original/device PCM proof. Existing real-BASS/Gradle/Android/device suites tidak dijalankan.

Reproduce dari root repository, Python3.11+, C++17 compiler, JVM17:

```bash
python3 tools/audit_sff1_corpus.py --corpus /path/to/sff1.zip
# Atau exact extracted directory:
python3 tools/audit_sff1_corpus.py --corpus /path/to/sff1
python3 tools/test_sff1_pipeline.py --dependencies /path/to/pinned-jars --existing-regressions
python3 tools/test_sff1_harness.py
python3 tools/test_voice_resolver.py
```

Bila dependencies belum tersedia, gunakan `python3 tools/test_sff1_pipeline.py --fetch-dependencies --existing-regressions`; runner mengambil hanya pinned official Maven Central jars dan memverifikasi SHA. Downloads hanya terjadi dengan flag eksplisit. Corpus scan/strict JVM harus dijalankan berurutan dengan output directories terpisah dari run concurrent lain. Default reports: `build/sff1/observed_manifest.json`, `build/sff1/sff1_main_d_native.tsv`, `build/sff1-jvm/{compile.log,junit.log,sff1_pipeline_observed.tsv}`.

`--manifest` menunjuk compact reference, bukan generated full manifest. `--record` dan `--record-digests` hanya bootstrapping awal; keduanya menolak overwrite fixture yang sudah ada. Initial recording bukan regression pass; strict run berikutnya wajib cocok. Counts tetap hard-coded dari audit; error tidak menulis expected baru. Perubahan baseline pada stage mendatang membutuhkan review source pins/expected transcript yang disengaja dan dilaporkan.

## H. Bukti produksi tidak berubah

Seluruh **125 tracked files** dalam `app/src/main` dibandingkan byte SHA-256 dengan `git show 05cdb1f:<path>`; indexed file set harus sama dan extra untracked main files ditolak. Main-tree Git OID baseline: `856c7ae49c267c10d966676124820c33935581ab`. Canonical full file-hash-table SHA-256: `bd572a56bfc35959468e79a687ea6a7603aa5cf6f240857c20dbb3d9b60ebe6a`. Tabel hash lengkap disimpan di compact reference dan generated full manifest.

`git diff 05cdb1f -- app/src/main app/build.gradle.kts build.gradle.kts .github/workflows` kosong. Commit hanya tools/tests/test resources/docs. Existing scheduler comparator, source8/9 fallback, root-mask omission, single-owner ledger, transformer NTT6/9, bank/resolver/percussion, choke/Stage3 bypass, #791 balance/#792 headroom, ACMP/chord detection, global cleanup dan output routing semuanya unchanged. Tidak diperlukan APK untuk host harness.

## I. Perbedaan terhadap audit

Tidak ada perbedaan acceptance counts, golden destination counts atau counterexample behavior. S1 menambahkan durable identity/provenance, production repository decoder execution, no-policy OFF counterexample, fixed observer-window lifecycle assertions, F-major digests dan fail-closed tests. Audit sebelumnya menamai counts raw Ctab/Cntt sebagai verified inventory; S1 tetap memisahkannya dari71.239 applicable copies dan1.990 missing copies.

S1 tidak menutup UNKNOWN semantics. Corpus-wide native plan/projection diuji semua503; sequencer end-to-end replay dibatasi tujuh MainD sections/dua roots dan synthetic lifecycle cases. Semua47 overlap files dan49 non-default-root files dicatat corpus-wide, tetapi tidak direplay seluruh sections/chords ke real backend. Exact real-clock transitions, native acceptance, synth/device sustained duration dan PCM presence tetap belum diukur.

## I. Compact fixture packaging revision

Full event-level manifest tidak disimpan di Git. Harness meregenerasi
`build/sff1/observed_manifest.json` dari corpus dan parser produksi yang sama.
`tests/fixtures/sff1_reference.json` berasal dari manifest export S1 asli, bukan
dari perubahan expected berdasarkan run baru. Format full manifest schema 1 dan
serialization tetap identik. Generated file berukuran **32.192.551 byte**;
SHA-256 byte `77a95fafee170fbd72366946751b72deda1c72facbc3ef2b7b9346c0ffd36809`;
canonical content digest
`4e2fd4a9719b826cd810a6e1eac62fc3e40dbb3eb2996ff9374e04b87da2d543`.

Compact reference mempertahankan seluruh non-style metadata, production file
identity table, aggregate acceptance, corpus archive SHA, ledger SHA, native
golden-resource SHA dan pipeline capture-resource SHA. Untuk setiap 503 style,
reference menyimpan relative path, file SHA/size, SHA atas **seluruh** original
style observation, serta golden expectations/protocol digest bila applicable.
Policy rows, missing-copy provenance, overlaps, sections dan raw-event/projection
digests tetap tercakup hash full style dan full manifest; tidak ada sampling atau
field exclusion dari hash. Harness memeriksa per-style hash, seluruh compact
reference, canonical content digest, exact generated-file SHA/size dan exact
golden resource bytes. Directory mode tetap wajib exact path/file identity set;
ZIP mode juga wajib original archive SHA. Expected tidak otomatis diperbarui.

Tambahan fail-closed tests memastikan policy/missing/overlap/section mutations
ditolak walaupun aggregate tidak berubah, inconsistent self-digest ditolak,
dan perubahan whitespace generated bytes ditolak. Lima negative/bounds tests
awal tetap ada. Test Kotlin, native driver, known-failure ledger F01–F15,
dependencies dan kedua golden TSV tidak berubah byte dari export asli.

Regeneration output tetap di ignored build directory. Corpus, jars, APK dan full
manifest tidak masuk commit. Revisi ini hanya packaging S1; production baseline
dan acceptance assertions tetap sama, dan S2 tidak dimulai.

## J. Kesiapan S2 dan stop point

**Aman memulai desain/perubahan metadata S2 setelah stage itu diminta terpisah**, dengan common scheduler dan musical behavior tetap sama. S1 source pin sengaja memblokir perubahan produksi; S2 perlu review pin/provenance yang additive, lalu membuktikan transcript tetap cocok. Jangan mengubah comparator, ownership, F04 gate, root selection, NTT6/9 atau output/resolver dalam S2. SFF2/UNKNOWN/conflicting-dialect fixtures masih diperlukan untuk S2.

S1 selesai pada satu commit terisolasi. Tidak mulai S2, tidak memperbaiki F01–F15, tidak build APK dan tidak push branch.

Revalidasi revisi packaging: strict ZIP/directory 503/503; original generated
manifest byte-identical; 32.913 Ctab dan 32.913 Cntt; 523 overlap pada 47 style;
50 JVM tests dan 20 strict captures (F02–F06/golden) PASS; 7 fail-closed/bounds
tests PASS; host umbrella dan regression/bypass/mix/role/diagnostic guards PASS.
Production diff terhadap `05cdb1f` EMPTY. Log dan daftar ukuran/hashes tersedia
di export verification. Tidak menjalankan Android/real-device/PCM acceptance baru.
