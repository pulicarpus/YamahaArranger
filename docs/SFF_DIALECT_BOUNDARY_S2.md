# S2 explicit dialect metadata boundary

Baseline: `dedeb58ac2ed5f9c2116e30b8cd696cfd16c47c4`, Build #794. Target branch: `fix/mix-percussion-fidelity-784`. S2 changes identity and metadata only. The user explicitly authorized separating exact source identity from the frozen S1 behavioral reference; no musical expected digest was changed.

## Detection and propagation

Production path is file bytes -> existing `SmfReader` -> `StyleParser` -> existing CASM/section/part projection -> existing JNI decoder -> `StyleRepository` -> `ParsedStyle`/`StyleSectionModel` -> existing sequencer and dispatch. Identity is observed after successful SMF parsing, before CASM projection. It is read from decoded events, never inferred from filenames, directories, CASM presence, voice or bank.

All 503 original corpus files contain format-0, one-track SMF with a tick-0 marker event (status FF, meta type 06) whose payload is exactly the four bytes `53 46 46 31` (`SFF1`). Corpus archive SHA-256 is `a9b89b90e1dee25c714a076a125d6c7173bcca0e8d2029c57092b3295035d465`. This observed declaration is the supported SFF1 evidence. The detector requires format 0 and one parsed track, an exact tick-0 SFF1 declaration, and no conflicting tick-0 marker beginning `SFF`. No raw-buffer substring search is used. An empty section result or failed parse clears dialect metadata. This certifies the declaration, not independent correctness of every legacy parser validation rule.

Native `StyleDialect` has stable codes UNKNOWN=0, SFF1=1, SFF2=2. Each parser owns its identity, and each parsed native section receives it. The read-only JNI getter returns the current parser's identity; null/failed loads reset metadata. No new global mutable flag is introduced. The existing native last-parser cache remains unchanged.

Kotlin uses `StyleDialect`, `StyleDialectEvidence`, and immutable `StyleDialectIdentity`. Only native code 1 maps to SFF1 with `SMF_TICK0_MARKER_SFF1`; all other codes, including reserved 2, map to UNKNOWN/NONE. The repository takes one identity snapshot and copies that same value into the style and all section models. Musical part/event lists, meter and voiceMap are preserved, with reference identity asserted. Downstream consumers receive the metadata without re-inference; no sequencer or dispatch code changes.

UNKNOWN means insufficient or unsupported classification evidence. It does not reject a file that the existing parser accepts and does not change its legacy playback path. This is a fail-closed identity boundary, not a new playback admission policy.

SFF2 positive detection is **UNVERIFIED_NEEDS_FIXTURE**. SFF2 is representable in the type, but there is no verified SFF2 corpus, invented marker, positive detector, or SFF2 interpretation. Generic synthetic SMF tests contain no dialect declaration and are used only for UNKNOWN. Positive SFF1 tests use the original checked corpus.

## Independent source and behavioral gates

`tests/fixtures/sff_dialect_source_identity_s2.json` pins the full old/new SHA-256 and every authorized insertion hash for exactly six production files. `tools/sff_dialect_source_guard.py` verifies physical source hashes first. Removing only those exact pinned metadata insertions must recover every old byte. The normal profile requires all six S2 hashes; an explicit `SFF_DIALECT_SOURCE_PROFILE=S1_BASELINE` is required to run the old profile. Mixed profiles, arbitrary source edits, mutated insertions and unlisted metadata blocks fail.

The S1 production gate still checks all 125 paths against `05cdb1f0998d082d701bc0d1979580270ae2eefc`, including path-set equality and no added production files. Six files have authorized physical source changes; their checked metadata removal recovers the original baseline. All remaining source bytes must match the baseline directly. Historical regression guards use the same checked removal before their unchanged musical source pins. The host CI umbrella additionally executes the independent new-source guard and its negative tests.

The S1 compact reference, ledger, golden resources and capture TSV remain unchanged. Per-style full result hashes, aggregates, golden/capture hashes and all other reference components are compared exactly. Only the source-containing manifest envelope may differ. The full manifest self-digest and deterministic byte serialization are still validated, including rejection of whitespace tampering.

**SOURCE IDENTITY:** expected changed only for these authorized files:

- `app/src/main/cpp/native_lib.cpp`: Read-only dialect JNI accessor plus metadata-only invalid/null-load reset; no new global state.
  - Old SHA-256: `4b26fbd8d1af84c04f0c3686f2a31c2ce57f071ff2d423bcc763366e17b31dde`
  - New SHA-256: `f5428a8066baae7536b15fc3a8e51646f7efa7edbd299ec8cc89be0a8e81d506`
- `app/src/main/cpp/style_parser.cpp`: Reset/read/copy dialect metadata only; retain exact original parse, event order, policy projection and return logic.
  - Old SHA-256: `351a3c2bccc9d0148ece90801e6d230f564eef2a47eae05903e19e3641b75f2a`
  - New SHA-256: `4d35cb694cd71d1c1d849e80a5892a58de4fc65a9a3a8648f4a723c5073b5807`
- `app/src/main/cpp/style_parser.h`: Per-parser typed dialect and section metadata; read existing decoded SMF declaration only, no CASM interpretation.
  - Old SHA-256: `4e8c701c15cede6dc130726625b82bffd2e24b88b8ec447f0fbee4540c0db4f9`
  - New SHA-256: `e73e91387000e0af41a0c96d549d87140edbe4711f67ae7604c8b3a03b8eccd1`
- `app/src/main/java/com/yourapp/style/NativeStyleBridge.kt`: Expose parser-owned typed numeric dialect snapshot via JNI.
  - Old SHA-256: `8899fda96a679671103cc9436207db5ec4a1c58f37a83708252ead365843237b`
  - New SHA-256: `7b17ae5285dc0e5c2d5bf64a016b6cbb5949432ba5a6d4cef7b65e7575e3dfef`
- `app/src/main/java/com/yourapp/style/StyleModel.kt`: Immutable style/section metadata, explicit UNKNOWN and reserved unverified SFF2; no musical fields changed.
  - Old SHA-256: `875a573f4200b72f6d0d1366a6efe2ed8048b76576be2be5b69744ca67f257a9`
  - New SHA-256: `bbfa15d06f4097a2b0364c2f0f5cb6ec666f21300bd2a36bcbf25ab7a692d375`
- `app/src/main/java/com/yourapp/yamahaarranger/style/StyleRepository.kt`: Attach one native identity snapshot to result and sections, preserving all original musical values/references.
  - Old SHA-256: `f024ed422f8e8c3af1cb3e3cef0d2e2f6a8e7d148dfe6b7f6340ed9a848ca5cd`
  - New SHA-256: `7243584bb78a77b36de1be5e42ff78d7d97b509f828ef592c626aed2d304d76b`

**BEHAVIORAL/MUSICAL IDENTITY:** must remain identical. Complete before/after generated manifests differ only in `production_source_identity` and `deterministic_manifest_content_sha256`. Every other top-level component, all 503 per-style results, native MainD protocol bytes, golden fixture bytes and all 20 JVM capture rows match exactly. Canonical SHA-256 after excluding those two source-containing components is identical:
`21ac9cc72e12523d2d0f791be69da7626e143d44d0fd0f1c70a20af26e7ba308`.

| Manifest/source digest | Before | After |
| --- | --- | --- |
| Full generated file SHA-256 | `77a95fafee170fbd72366946751b72deda1c72facbc3ef2b7b9346c0ffd36809` | `366835dbbe99e4bcd21e2eae833e0e89bf47bae089fff3d3df4d417dcb55a443` |
| Canonical full content SHA-256 | `4e2fd4a9719b826cd810a6e1eac62fc3e40dbb3eb2996ff9374e04b87da2d543` | `dae4376e012e7c03fd614e17c885fad3a585ec3ab37972fafcd0e128bf3f7847` |
| Physical production source table SHA-256 | `bd572a56bfc35959468e79a687ea6a7603aa5cf6f240857c20dbb3d9b60ebe6a` | `bb52729bcd0dabeb66ab82490ba4dec69caacbc9f6be78dea55667ddd03d51f1` |

The full manifest remains a locally generated artifact and is not committed.

## S1 before/after evidence

| Observation | Before | After |
| --- | ---: | ---: |
| Corpus parse success | 503/503 | 503/503 |
| Raw events | 3,218,027 | 3,218,027 |
| CSEG / Sdec | 3,584 / 3,584 | 3,584 / 3,584 |
| Ctab / Cntt / Ctb2 | 32,913 / 32,913 / 0 | 32,913 / 32,913 / 0 |
| Section-policy expansions / attached copies | 73,229 / 71,239 | 73,229 / 71,239 |
| Missing copies / sections / styles | 1,990 / 1,084 / 196 | 1,990 / 1,084 / 196 |
| Unattached raw descriptors | 157 | 157 |
| Overlapping NOTE_ON / styles / max multiplicity | 523 / 47 / 2 | 523 / 47 / 2 |
| Multi-source destination groups / max sources | 9,373 / 11 | 9,373 / 11 |
| Nondefault-root records / styles | 257 / 49 | 257 / 49 |
| S1 capture digests | 20 identical expected rows | 20 identical expected rows |
| S1/relevant JVM tests | 50 PASS | 50 PASS + 5 S2 PASS |
| Original fail-closed harness tests | 7 PASS | 7 PASS |

NTT counts remain {0:15068,1:4866,2:10600,3:760,4:36,5:1541,6:28,9:14}. Meter counts remain 4/4:463, 3/4:39, 2/4:1. FillBA terminal lengths remain 7681:463, 5761:39, 3841:1. Seven golden styles, fourteen golden C/F-major captures and six synthetic captures are unchanged.

F02 same-tick OFF/program ordering, F03 single-owner overlap lifecycle, F04 source8/9 phantom rhythm, F05 missing OFF and F06 partial non-note dispatch remain asserted as exact baseline counterexamples. No counterexample is removed or converted into a corrected expectation. F01–F15 ledger bytes remain unchanged. F08 receives infrastructure for explicit identity only; it does not gain musical dialect interpretation or verified SFF2 support.

## Tests and reproducible commands

Before production edits, original S1 corpus/JVM/harness and full host regression all passed. After source-gate separation, a second complete before/after run uses exact baseline production bytes followed by the six pinned metadata files. The baseline phase explicitly selects `S1_BASELINE`, while the after phase uses the normal S2 profile.

```sh
# Run from repository root; ORIGINAL_SFF1_ZIP names the verified attached archive.
python3 tools/audit_sff1_corpus.py --corpus "$ORIGINAL_SFF1_ZIP" --output build/s2-conformance
python3 tools/test_sff1_pipeline.py --existing-regressions --s2 --output build/s2-jvm
python3 tools/test_sff1_harness.py
python3 tools/test_sff_dialect_boundary.py --corpus "$ORIGINAL_SFF1_ZIP"
python3 tools/sff_dialect_source_guard.py
python3 tools/test_sff_dialect_source_guard.py
python3 tools/test_voice_resolver.py
```

The native boundary test checks positive declaration and all section identities for every original style, UNKNOWN without evidence, SFF1 -> UNKNOWN -> SFF1 isolation, failed-load reset, naked text rejection, and unchanged bytes under unrelated extensions. The five JVM tests check mapping/propagation, unknown/reserved codes, extension independence, immutable load snapshots and explicitly unverified SFF2. The four source guard tests reject arbitrary byte/comment edits and unlisted metadata blocks, validate old/new recovery pins, and reject the old profile unless explicitly selected. Existing assertions/tests are retained.

Changed non-production files (the original S1 regression test and expected musical fixtures are unchanged):

- `app/src/test/java/com/yourapp/yamahaarranger/arranger/SffDialectBoundaryTest.kt`
- `tests/sff_dialect_boundary_test.cpp`
- `tools/test_sff_dialect_boundary.py`
- `tests/fixtures/sff_dialect_source_identity_s2.json`
- `tools/sff_dialect_source_guard.py`
- `tools/test_sff_dialect_source_guard.py`
- `tools/audit_sff1_corpus.py`
- `tools/test_sff1_pipeline.py`
- `tests/sff1_jvm_stubs/NativeStyleBridge.kt`
- `tools/pcm_headroom_guard.py`
- `tools/test_drum_shadow_guards.py`
- `tools/test_voice_resolver.py`
- `docs/SFF_DIALECT_BOUNDARY_S2.md`

CI runs the existing complete Android unit suite, native regression/guards and APK checks, plus the five new JVM tests and source guard. The full 503-file corpus and positive native boundary gate run locally against the attached archive; CI has no copy of that private corpus. CI outcomes, commit/tree, both ABI results, APK and Telegram must be reported from the actual resulting run, not inferred from local checks.

## Scope and remaining risks

No playback correction, musical CASM semantics, ownership, routing, NTR/NTT/RTR, voice mapping, percussion, BASSMIDI, MIDI OUT, scheduler or transition change is included. No Stage3 setting is changed; the existing Stage3 bypass/absence APK guards remain required. Musical source after exact metadata removal has EMPTY diff against 05cdb1f; raw physical production diff is intentionally six metadata files, as authorized.

Existing last-parser JNI state is not a new flag and still has its historical concurrency limitations; only sequential load isolation is demonstrated. Malformed SMF acceptance rules are not repaired. S1 transcripts are observations of current behavior, not proof of Yamaha musical correctness or PCM/native acceptance at the Kotlin mocked boundary. Native parser preservation, host regression and APK checks provide their own separately scoped evidence. SFF2 remains unverified. F01–F15 remain known issues.

A later S3 could safely begin with read-only normalized planning/oracle design after independent approval, retaining these source and behavioral gates. Verified SFF2 bytes and specifications are prerequisites for positive SFF2 detection or interpretation. Musical fixes require separate scope and evidence. S3 is not started by this work.
