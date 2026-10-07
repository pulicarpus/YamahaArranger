# S3 — SFF1 CASM semantic fidelity and information preservation

Tanggal lokal pengguna: 2026-10-08 (Asia/Jakarta). Baseline S2: `253e1cf65e75aadf73c0a480e665bd91c9c98c76`, parent `dedeb58ac2ed5f9c2116e30b8cd696cfd16c47c4`, tree `04372af1d61ab62d7872094837b212c3156f81e9`. Branch target `fix/mix-percussion-fidelity-784`. Build #795/run37662505479 SUCCESS diverifikasi sebelum edit. HEAD lokal/remote cocok dan working tree bersih. Baseline 503 corpus, 55 JVM tests (S1+S2), 20 capture, tujuh fail-closed, S2 native dialect dan seluruh host regression/guards PASS sebelum edit.

S3 menyimpan raw information dan provenance melalui metadata terpisah. Tidak ada musical/playback fix atau S4. Kata **PRESERVED** menyatakan preservation data pada boundary yang disebutkan, bukan certification Yamaha musical semantics. Status ledger/reference S1 adalah frozen historical observations pada legacy playback model; metadata S3 dilaporkan terpisah.

## Evidence dan production path

Archive asli `sff1.zip` SHA-256: `a9b89b90e1dee25c714a076a125d6c7173bcca0e8d2029c57092b3295035d465`. Setiap file dicocokkan kembali dengan file SHA yang dikunci S1. SFF1 berasal dari boundary S2: format0/satu track, tick0 marker FF/meta06 dengan payload tepat SFF1 dan tanpa deklarasi SFF yang bertentangan. Seluruh503 native parse mengembalikan identity SFF1. SFF2 tetap `UNVERIFIED_NEEDS_FIXTURE`; tidak ada positive SFF2 fixture/detector/interpretation.

Jalur audit: raw SMF/CASM bytes -> `StyleParser::parseCasm` CSEG/Sdec/Ctab/Cntt/Ctb2 -> legacy `CasmPolicy` + registry `CasmRawDescriptor` -> section/source attach -> JNI15-field policy yang tetap sama + read-only metadata protocol -> `StyleRepository::parseCasmPolicies` yang tetap sama -> original `CasmPolicyModel` + typed semantic snapshot -> `StyleSectionModel.casmSemanticBindings` -> original `StyleSequencer` policyScore/selectPolicy/transform/ownership. Tidak ada inferensi dari nama, extension, folder, voice atau bank.

Native registry menangkap setiap bounded subchunk pada loop parser yang sudah ada: CSEG ordinal, tag, absolute payload offset dan seluruh payload bytes. Field metadata `semanticDescriptor` dan `semanticCntt` mengikat setiap salinan policy pada descriptor asli dan Cntt yang benar-benar mengoverride policy itu. Ctab yang tidak pernah attached tetap ada pada registry. Sdec raw memberi provenance applicability; actual bindings memberi section/part/policy projection yang benar-benar diproduksi. Getter const dan protocol read-only tidak mengubah field legacy. Tag non-printable di-encode hex sehingga protocol JNI tetap ASCII; raw tag/payload tetap dapat diamati. Ini metadata transport safety, tanpa mengubah penerimaan parser lama.

Kotlin: `CasmRawNtr`, `CasmRawNtt`, `CasmRawRtr`, `CasmRawCtab`, `CasmSemanticDescriptor`, `CasmSemanticBinding`, `CasmSemanticSnapshot`. Root candidate disimpan sebagai **seluruh raw word bytes11–12**, tanpa mengasumsikan 12-bit orientation, allowed-root bit, mask0, atau reserved bits. Raw flag byte10, five-byte chord field dan tail juga disimpan. `executionCode`/`hasExplicitExecutionBranch` hanya melaporkan literal masking/branch yang sudah ada, bukan pemetaan Yamaha yang baru.

`withCasmSemanticMetadata` mengambil satu snapshot JNI setelah legacy style/dialect terbentuk, lalu menempelkan binding ke style dan section. Setiap binding section membawa reference descriptor dan Cntt descriptor secara langsung (beserta index); raw word dapat dibaca dari section yang diterima sequencer tanpa akses kembali ke ParsedStyle. Collections metadata dibungkus unmodifiable, payload berupa immutable hex String. Original parts/events/policy lists dan effective policy object tetap referensi yang sama; meter/voiceMap/value lama juga dipertahankan. Section yang diterima sequencer memiliki metadata, namun selector/executor tidak membaca metadata baru. Legacy policy string, decoder dan comparator tidak diubah.

Unknown dialect, schema/header/row/provenance/index tidak valid menghasilkan snapshot UNKNOWN dengan rawProtocol tetap tersedia dan musical objects tetap utuh. Ctb2 opaque descriptor berstatus UNSUPPORTED untuk typed schema S3; dataset tidak memiliki Ctb2. Existing Ctb2 playback parser tidak diperbaiki atau certified. Null/failed load mengosongkan registry/provenance metadata; snapshot Kotlin lama tidak berubah. Tidak ada global mutable flag baru. Existing last-parser JNI cache tetap memiliki batasan concurrency historis.

## Semantic matrix wajib

Offset di bawah adalah zero-based pada payload Ctab, kecuali dinyatakan absolute. Columns legacy dan S3 dibedakan eksplisit.

| Semantic | Raw evidence | Parsed | Projected | Sequencer-visible | Status | First divergence |
| --- | --- | --- | --- | --- | --- | --- |
| Source/destination | Ctab0/9; semua32913 | Legacy sourceChannel/destinationChannel; S3 raw/index |71239 same-value copies dengan descriptor ID |Original effectivePolicy refs dan same source/destination | PRESERVED |Tidak ada pada applicable copies |
| Voice name bytes | Ctab1–8 |Legacy ASCII trim; S3 full payload hex |Trimmed name pada legacy, raw bytes pada registry |Legacy trimmed name; raw descriptor tersedia pada section binding | COLLAPSED |Legacy extraction trims padding; S3 raw bytes tetap disimpan |
| Flag byte10 |0:4959,1:27954 |Legacy tidak memiliki field; S3 flagByte10/rawHex |Sidecar lengkap |Tersedia di section metadata, belum dipakai selector | PRESERVED |Legacy extraction omission; meaning UNKNOWN |
| Raw root candidate word |Ctab11–12;53 values;257 non-default |Legacy tidak memiliki root-selection field; S3 rootSelectionWordRaw |700 non-default attached bindings; all32913 raw Ctab retained |Raw word tersedia di binding; execution tidak mengonsumsi | PRESERVED |Legacy raw->CasmPolicy kehilangan field; S3 carrier menyimpan tanpa semantics |
| Root-selection consumption |Contoh0x0000/0008/0555/0AAA |Legacy field absent; metadata mempunyai raw word |Legacy CasmPolicyModel tetap tidak memiliki selector field |policyScore tidak memakai raw word; correct interpretation UNKNOWN | PROJECTION_LOSS |Legacy raw->policy; interpretation/consumption sengaja dibekukan |
| Chord field bytes13–17 |Seluruh5 bytes, termasuk upper/reserved bits |Big-endian uint64/Long dan raw hex |Same40-bit value |policyScore memilih chord-type bit0..33 | PRESERVED |Tidak ada byte loss; bit meaning/upper bits UNKNOWN |
| Source chord root/type |Ctab18/19 |Same integers |Same original policy objects |Root dipakai/clamp dalam transformer; type specificity di score, tidak dipakai transform | PRESERVED |Raw values tetap ada; semantics relevance belum certified |
| NTR |Ctab20:0=18047,1=14866 |Full raw byte + CasmRawNtr |Same integer |Existing execution masks&127; explicit branches0..3 | PRESERVED |Execution dapat collapse high-bit states; bukan raw-field loss |
| Raw Ctab NTT/Bass bit |Ctab21; high bit0 di seluruh corpus |Legacy low7 NTT, Bass defaultfalse; S3 full byte |Original NTT saat extraction + full raw byte |Effective NTT/Bass mengikuti Cntt bila ada | PRESERVED |Authoritative override mengubah effective state; raw sebelumnya kini tetap disimpan |
| Effective Cntt |Cntt0 source/1 NTT+bit7 |Source-keyed map; provenance ID |Every attached policy menerima actual final override |Same NTT/Bass | PRESERVED |Tidak ada effective mismatch corpus |
| NTT6/9 execution |Raw/effective6:28,9:14 |Distinct values retained |Distinct policies/typed values |Existing transformer else->base untuk keduanya | COLLAPSED |Execution branch; F07 tidak diperbaiki |
| High key / note limits |Ctab22/23/24 |Same full bytes |Same values |Existing HighKey/octave limit behavior | PRESERVED |Tidak ada applicable value loss; correct Yamaha semantics belum certified |
| RTR |Ctab25:0..4 corpus |Full raw byte / CasmRawRtr |Same integer |Existing&127; branches0..4,5 deferred,else fallback | PRESERVED |No raw-field loss; branch correctness UNKNOWN |
| Tail bytes |Ctab26..end;00×32913 |Legacy ignored; S3 rawHex/tailHex |Registry retained |Metadata tersedia; no execution use | PRESERVED |Legacy omission; semantic meaning UNKNOWN |
| Section applicability |3584 Sdec raw strings |Legacy classifyMarkerText; S3 raw+group ordinal |Actual native section/source bindings |Same sections/policies, sidecar declaration available | PRESERVED |All corpus recognized sections match independent S1 projection |
| Missing expanded copy |1990/1084 sections/196 styles |Descriptor exists, no parsed source part for that section |No synthesized part/note; raw descriptor survives |No policy copy on absent part | SOURCE_ABSENT |attachPolicy finds no parsed part; authored intent UNKNOWN |
| Descriptor never attached |157 raw Ctab |Legacy temporary extraction, no runtime registry; S3 raw registry retained |No bindings for any declared section |Registry accessible on style, no invented section part | PARSED_BUT_UNATTACHED |attachPolicy; whole-descriptor and per-section-copy counts are different units |
| Chord/range rejection |Baroque MainD src8->dst12 policy present, C-major policyScore null |All policy values retained |Policy exists until selection |Rejected candidate; F04 fallback behavior remains frozen | PARSED_BUT_FILTERED |policyScore event/chord gate; no claim all missing is filtered |
| Ctb2 typed schema |Corpus count0 |Opaque raw descriptor only; no invented schema |No verified positive case |Existing legacy playback path unchanged | UNSUPPORTED |Verified fixture/spec absent |
| Unknown/reserved semantics |Flag/root orientation/chord layout outside proven bytes |Raw information preserved |Typed/raw carrier available |No newly guessed interpretation | UNKNOWN |Missing primary oracle; preservation is not semantic certification |

## Ctab/Cntt precedence evidence

All32913 Ctab have authoritative Cntt in the same raw CSEG. All32913 matching Cntt follow their Ctab in this corpus; Ctab-only corpus cases=0. Source/parser evidence: `cnttByChannel[d[0]] = ...` stores low7 NTT/Bass-On; after the entire CSEG loop, the parser writes override into `part.casmPolicies` and the legacy first `part.casm` for matching sources/sections. S3 records the ID of the actual overriding Cntt. Every71239 projected copy is checked against independent raw mapping and actual native provenance; no CSEG-cross override occurs in this corpus.

**Twelve raw descriptors** have Ctab low7 NTT different from effective Cntt (MovieBallad2/MovieBallad3). Example: `Movie&Show/MovieBallad2.S795.sst`, IntroB, source8/destination8: Ctab payload offset25240, raw `0854696d70616e692008000fff03ffffffff0002000303297f0100`, raw NTT3; Cntt offset25385, payload `0800`; effective/projected/sequencer-visible NTT0, Bassfalse. Both raw states survive in S3. Bass-On true comes from Cntt for3562 raw records, while Ctab high bit is0 for all32913.

Host mechanical probes on generic **UNKNOWN SMF** test Ctab-only, Cntt before/after table, duplicate last-Cntt and subsequent-CSEG override of previously attached policy. They reproduce current source behavior, without declaring synthetic SFF1/SFF2 or Yamaha correctness. Ctab-only NTT0x82 becomes low7=2/Bassfalse on legacy path, preserving the old omission; Cntt0x86 produces6/Basstrue. Duplicate source-map entries are last-write wins. The existing post-CSEG loop may also override policies previously attached to the same section/source from earlier CSEG; provenance records that actual later Cntt. No such cross-CSEG effective difference is observed in the503 corpus. Do not infer general authoring precedence from corpus order alone.

Same destination does not merge source policies:9373 multi-source destination groups, max11 distinct source->Chord1. Section applicability, per-source arrays and every policy ordinal remain unchanged. Existing one-selected-policy and destination setup arbitration are execution choices, not new S3 normalization rules.

## F01 raw-root counterexamples and first divergence

Information loss in legacy extraction is proven by raw bytes and absence of a field on native/Kotlin playback policy. Exact musical meaning, bit orientation, mask0 and correct gating remain UNKNOWN. This is not proof of a particular wrong note or silence.

| Style | Section | Source->destination | Ctab payload offset / absolute root offset | Raw word | Legacy parsed/projected/consumed root-selection | S3 carrier |
| --- | --- | --- | --- | --- | --- | --- |
| Country/CowboyBoogie1.S626.bcs |IntroC|3->11|18918 /18929|0000|Absent/absent/not consumed|0000 preserved |
| Ballrom/Rhumba.T006.bcs |FillBA|6->11|24015 /24026|0008|Absent/absent/not consumed|0008 preserved |
| Ballad/PopBallad4.S281.bcs |FillBB|7->9|26470 /26481|0555|Absent/absent/not consumed|0555 preserved |
| Ballad/PopBallad4.S281.bcs |FillBB|11->10|26540 /26551|0AAA|Absent/absent/not consumed|0AAA preserved |
| Pop&Rock/Unplugged2.T151.prs |MainA|5->11|85411 /85422|0003|Absent/absent/not consumed|0003 preserved |

Cowboy raw payload: `033235436c477472200b01000000000000000008000003004a0100`.
Rhumba: `0643686f72643120200b01000803ffffffff000e010207007f0100`.
PopBallad root0555: `0752687974686d32200900055507ffffffff0002010007007f0100`.
PopBallad root0AAA: `0b42617373202020200a010aaa03ffffffff00020001071c7f0200`.
Unplugged raw0003: `05677420632d6323200b01000303d0bfdf9f0002000201007f0100`.

All257 non-default raw descriptors have at least one attached copy:700 copies in209 style/section pairs,700 section/source lanes,49 styles; affected sources include0..15 and destinations9..15. This includes a rhythm destination, so melodic-only interpretations are not assumed. The new reference lists every affected style and representative counterexamples for every52 non-default raw words. Current policyScore for the exact Unplugged policy remains1010012 at C-major and F-major: raw root0003 is preserved as metadata, not applied as a selector. SourceChordRoot byte18 remains a different field. New S3 tests assert raw0 remains0, rather than replacing it with a default4095.

## NTR/NTT/RTR preservation and collapse

Raw NTR0:18047,1:14866; other raw NTR absent from this corpus. Raw Ctab NTT:0=15056,1=4872,2=10600,3=766,4=36,5=1541,6=28,9=14. Effective NTT:0=15068,1=4866,2=10600,3=760,4=36,5=1541,6=28,9=14. RTR:0=260,1=28474,2=3270,3=832,4=77. Complete source/destination/root/type/note-limit/high-key/chord-mask/flag/tail distributions are pinned in the semantic reference; no unknown values are mapped to guessed enum labels.

Literal execution source is `CasmNoteTransformer.transform` lines17–18 and existing when branches: NTR&127, NTT&127; unsupported NTR uses current root-transpose fallback; unsupported NTT uses base. Thus rawNTR0/128 share execution code0, while raw values remain distinct. NTT0/6/9 can yield identical current base output; tests lock that collapse, preserving all three raw codes. RTR execution is `StyleSequencer` lines484–491: low7 code0 stop,1/2 held update,3/4 retrigger variant,5 deferred,else existing fallback. These are implementation observations. F07 is not closed. Chord field is preserved at full five-byte width; policyScore uses types0..33, and unconsumed upper/reserved bits are not assigned invented semantics.

## Missing classification and ownership scope

1990 missing copies are **SOURCE_ABSENT at parsed section/source boundary**. Raw declarations do not prove authored intention; no assertion that they should synthesize notes is made.157 completely unbound raw descriptors are PARSED_BUT_UNATTACHED. Example `Ballad/16BeatBallad02.S790.bcs` has two such descriptors; payload offset32934 is source6/destination12, full bytes `06452e5069616e6f200c010fff000007ff000008000007007f0100`. S3 retains it, and tests assert no part/event/policy manufacture. For dynamic mask/range exclusion, PARSED_BUT_FILTERED is evidence at a specified event/chord only. A general corpus eligibility/filter count is UNKNOWN without an oracle. Root/flag/tail omission on the historical playback model is PROJECTION_LOSS; raw carrier preservation does not repair consumption. Unsupported Ctb2 and unknown semantics remain explicitly classified.

All523 original overlaps on47 styles have **both ON instances in the same section projection**. Raw events, per-source ordering and source/destination policy data are preserved to that boundary. Global raw event ordinal is still observer-side, absent from production event objects: that temporal identity is lost before ownership. Unique CASM descriptor provenance now survives in sidecar; it is not a note-instance token. At execution, `activeTransposedNotes` is still a single source:key map (`StyleSequencer:94`); the F03 baseline probe still replaces the first instance and drops the later unmatched OFF. This is ownership-layer collapse for that probe, not a claim523 audible failures. No F03 map, pairing, lifecycle, locks, scheduler comparator or backend change is made.

## Frozen behavioral gate and independent source identity

Before/after:503/503; raw events3218027; CSEG/Sdec3584/3584; Ctab/Cntt/Ctb2 32913/32913/0; expansions73229; attached71239; missing1990/1084sections/196styles; unattached157; overlap523/47,max2; multi-source9373,max11; non-default root257/49. All original aggregates, meters, FillBA lengths and per-style/golden/capture hashes remain exactly identical. Every 503 native raw registry and descriptor binding matches independent raw bytes; full503 JVM matrices verify71239 effective policies using the unchanged production decoder and exact original policy object references.

The S1 full manifest still describes the frozen legacy playback model. Its historical root-field label and F01–F15 ledger are retained; current carrier preservation is documented here and in the S3 semantic reference. The only changed full-manifest components are physical source identity and the full self-digest. Canonical behavioral content excluding those two components remains SHA-256 `21ac9cc72e12523d2d0f791be69da7626e143d44d0fd0f1c70a20af26e7ba308`. Original reference/golden/20 capture/known-failure ledger bytes are unchanged. S2 source fixture and every S2 insertion hash are unchanged.

S3 source guard pins full old/new hash and exact insertion hash for six files. Removing exact S3 blocks recovers every S2 byte. The S2 guard then removes its independently pinned metadata to recover every byte of05cdb1f for all125 original production paths. New tracked/untracked production source paths remain forbidden by S1 path-set gate. S3 defaults to the new profile; explicit S2_BASELINE is required to verify the old profile. Mutations/unlisted blocks/rollback/mixed profiles are rejected. Full S3 source identity is separate from musical identity; no expected musical digest is changed.

Physical production changes and reasons:

- `app/src/main/cpp/native_lib.cpp` — Read-only semantic JNI snapshot plus metadata-only null/failed-load reset; no new global state.
  - Old S2 SHA-256: `f5428a8066baae7536b15fc3a8e51646f7efa7edbd299ec8cc89be0a8e81d506`
  - New S3 SHA-256: `350d6f5c097a21a877b0815630686268fa4b535cea88063a5998f0800c1e5b3e`
- `app/src/main/cpp/style_parser.cpp` — Observe existing bounded chunk walk; retain full raw payload/offset/CSEG; attach descriptor/Cntt IDs and serialize read-only snapshot; original parsing/override fields untouched.
  - Old S2 SHA-256: `4d35cb694cd71d1c1d849e80a5892a58de4fc65a9a3a8648f4a723c5073b5807`
  - New S3 SHA-256: `bf95fbd19a841129dd0c5e5573e037f29541f4b021d8446fa71d8c621d826d20`
- `app/src/main/cpp/style_parser.h` — Raw descriptor registry and per-policy provenance IDs, const getters and metadata-only reset; original CasmPolicy fields intact.
  - Old S2 SHA-256: `e73e91387000e0af41a0c96d549d87140edbe4711f67ae7604c8b3a03b8eccd1`
  - New S3 SHA-256: `dfbd8ad856e6eb49f227e474da833248a44515bd0d73a50945fa9b2ba81a557d`
- `app/src/main/java/com/yourapp/style/NativeStyleBridge.kt` — Expose read-only semantic snapshot JNI method.
  - Old S2 SHA-256: `7b17ae5285dc0e5c2d5bf64a016b6cbb5949432ba5a6d4cef7b65e7575e3dfef`
  - New S3 SHA-256: `49990624f074b056525940a39cfc30a3d84de3e168e33ab091ff925a39b54ea7`
- `app/src/main/java/com/yourapp/style/StyleModel.kt` — Typed raw words/enums and immutable descriptor/binding carriers on style/section; CasmPolicyModel and musical fields intact.
  - Old S2 SHA-256: `bbfa15d06f4097a2b0364c2f0f5cb6ec666f21300bd2a36bcbf25ab7a692d375`
  - New S3 SHA-256: `dbffebdc953b6d45bd5f9ddbfa3cfeed12b538e95a1fbf817e0eef55535a71e6`
- `app/src/main/java/com/yourapp/yamahaarranger/style/StyleRepository.kt` — Decode and attach separate provenance snapshot, preserving original parts/events/policy references; unknown metadata cannot alter playback.
  - Old S2 SHA-256: `7243584bb78a77b36de1be5e42ff78d7d97b509f828ef592c626aed2d304d76b`
  - New S3 SHA-256: `a6ecd4d4c1978159e5045ccffee0d08d30813dee265bc47c7cc7ce71b1865079`

Raw production diff is six metadata files. After checked S3 removal, diff against S2 is EMPTY. After checked S3+S2 removal, diff against05cdb1f is EMPTY. Stage3 flags, scheduler/order, source8/9 fallback, ownership, ACMP, transform execution, voiceMap/bank/program, BASSMIDI/SF2/percussion/choke/PCM/headroom/MIDI OUT/transitions/allNotesOff are unchanged.

## Files, tests and reproduction

Production functions: `StyleParser::parseCasm` (`style_parser.cpp:24`), Ctab provenance104, override provenance175/181, `casmSemanticProtocol:221`; `nativeGetCasmSemanticMetadata` JNI; `StyleRepository::loadStyle:85` wrapper and `withCasmSemanticMetadata:97`; types in `StyleModel.kt:118–145`. Controller `ArrangerBrain::playSection:578` memilih object `style.sections[section.styleName]` pada584 dan mengirim model itu langsung ke playSeamless/play pada590/605; metadata section tidak diproyeksikan ulang. Historical playback: `StyleSequencer::policyScore:728`, selectPolicy, RTR485, ownership94; `CasmNoteTransformer::transform:17–18`. Playback files are unchanged.

New harness: `tools/audit_sff1_semantics.py`, `tests/sff_casm_semantic_test.cpp`, `tools/test_sff_casm_native.py`, `tools/sff_casm_source_guard.py`, `tools/test_sff_casm_source_guard.py`, source/reference fixtures, eight verified corpus semantic resources plus SHA index, `SffCasmSemanticPreservationTest.kt`, and this document. Existing S1 audit/JVM runner/stub/source guard and host umbrella gain only metadata/source-gate integration. No existing S1 test or musical resource is changed.

```sh
python3 tools/audit_sff1_corpus.py --corpus "$ORIGINAL_SFF1_ZIP" --output build/s3-conformance
python3 tools/audit_sff1_semantics.py --corpus "$ORIGINAL_SFF1_ZIP" --output build/s3-semantics
python3 tools/test_sff1_pipeline.py --existing-regressions --s2 --s3 --s3-protocols build/s3-semantics/protocols --output build/s3-jvm
python3 tools/test_sff1_harness.py
python3 tools/test_sff_dialect_boundary.py --corpus "$ORIGINAL_SFF1_ZIP"
python3 tools/test_sff_casm_source_guard.py
python3 tools/test_voice_resolver.py
```

Baseline55 JVM PASS; after63 PASS (existing55 + eight S3), unchanged20 capture rows. Original seven fail-closed tests PASS. Four S3 source negative/profile tests and all four S2 tests PASS. New native mechanical tests run in the host CI umbrella and use only generic UNKNOWN SMF. Corpus-specific positive native/all503 matrices run locally against the attached original archive. CI runs the eight S3 JUnit tests against eight SHA-indexed real corpus fixtures, along with all existing Android tests/guards; no test is skipped/disabled. After CI, actual commit/tree/run/ABI/APK/Telegram outcomes are reported in the final A–R report, with no assumed results here.

S3 semantic matrix SHA-256: `eaaaed3566c60e14e8a8910b1b646e87f52f1077d61a9b61cbef876b557be7ac`. `tests/fixtures/sff1_semantic_reference_s3.json` is a289147-byte deterministic summary/reference with503 protocol/projection digests, field distributions and raw counterexamples. Full corpus protocol matrices and full manifest remain generated artifacts and are not committed. Initial new semantic reference creation never overwrites existing expectations; ordinary verification compares exact reference. Eight committed semantic fixtures are individually checked against regenerated original-corpus native output and SHA index.

## Ledger and boundaries

F01: raw loss proven/preserved sidecar; root selection unchanged, bit interpretation UNKNOWN. F02: comparator/order frozen. F03: observe-only, source:key owner unchanged. F04–F06: all counterexample captures frozen. F07: inventory/preservation/fallback collapse recorded; no execution repair. F08: S2 identity remains closed and regression-tested. F09 setup arbitration/F10 legacy voiceMap/F11 output mapping/F12 terminal+1/F13 transitions remain frozen. F14: section/source absence, whole descriptor nonattachment and chord-dependent filtering are separated without assigning intent. F15: exact bytes preserved, Yamaha chord/root oracle absent. Historical F01–F15 ledger bytes stay unchanged.

Risks/unknowns: root/flag/chord mask semantics, unsupported NTT/RTR meaning and authored intent lack primary oracle; Ctb2/SFF2 have no verified fixture. Existing parser malformed-length behavior and JNI last-parser concurrency are not repaired. New raw/provenance storage adds load-time memory/allocation, not a realtime performance claim; no Android device stress/timing/PCM certification is asserted. Host Kotlin boundaries are mocked, and native host uses logging shim/actual parser; output transcript does not prove PCM. All523 overlaps are preserved before ownership, but only existing counterexample/golden execution coverage is claimed for musical lifecycle.

Recommended S4 scope only: separately authorized note-instance/ownership identity and deterministic ON/OFF pairing design using the preserved source/section/provenance evidence and frozen F02–F06 transcripts as reviewed baseline observations. Require explicit event-order/section-generation/overlap/retarget/forced-transition/stop tests plus independent desired-musical oracle before changing those expected behaviors. Root/NTT/Ctb2 interpretation needs verified specification/fixtures and a separate authorization. S4 is not started by S3.

## Final deterministic manifest/source envelopes

- Full generated manifest SHA-256: before `366835dbbe99e4bcd21e2eae833e0e89bf47bae089fff3d3df4d417dcb55a443`; after `7701f47cf7cf7e8166cd0ba6ffdc5a4792af7ba2743e8b386fa1237fb086f56b`.
- Canonical full manifest content SHA-256: before `dae4376e012e7c03fd614e17c885fad3a585ec3ab37972fafcd0e128bf3f7847`; after `080bc872b7269aef2cd9d5f3cac86eafef2c2e0e4a506054eccce57cb815e9c8`.
- Physical production source table SHA-256: before `bb52729bcd0dabeb66ab82490ba4dec69caacbc9f6be78dea55667ddd03d51f1`; after `fd3ec12803ecaebb6f220451d5ccc8fe1f96282ee6c64d4c18076e9958a74dff`.

Only source identity and its full-manifest self-digest changed; all other manifest components, per-style full results and byte transcripts are identical. Final reproduction proof is supplied with the A–R report.
