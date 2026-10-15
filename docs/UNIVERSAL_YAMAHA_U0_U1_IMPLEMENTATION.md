# U0/U1 diagnostic implementation

Branch: `experiment/universal-yamaha-u0-u1`.
Parent: `c3f3b0a67f4d4acc2310f687abb248be1cae6643`.
Reference: owner's UNIVERSAL_YAMAHA_VOICE_DRUM_ENGINE_DESIGN.md and explicit two-stage provisional catalog request.
Scope: U0/U1 only. Catalog use OFF; U2–U8 are not activated.

## Implemented behavior

The existing explicit drum audit export now includes STYLE FIDELITY per parsed section/channel, raw bank/program/controller declarations, active resolver reason, selected native preset/kit, effective controller readback, and metadata key coverage. It does not send MIDI or modify native attributes. Active binding attribution is a snapshot at export, available while stopped; other sections are explicitly UNOBSERVED. This is not a continuous playback trace or a reconstruction of effective SInt/CASM setup. Missing declarations remain unknown. Key presence does not prove all-velocity or audible PCM coverage.

PDTA fingerprinting runs when the existing font metadata cache reads original SF2 bytes. It validates RIFF bounds, padding, duplicate PDTA lists, ordered mandatory tables and record extents. PDTA allocation is limited to 32 MiB. The domain `sf2-list-pdta-subchunks-v1` hashes exact bytes following the four-byte LIST type `pdta`, including child headers/padding, excluding the LIST header/type/outer pad. Samples and text exports are not hashed as PDTA. Nanoseconds measured around validation and hashing are exported; this adds work at font load, never in noteOn/render.

The deterministic generator preserves all 1,148 actual preset identities and source metadata. Output partitions 989 voice / 159 drum presets (banks 127 and 128 across all fonts). This differs deliberately from the old resolver pool of 1,127, which excluded only the 21 Prime Drum presets. It does not alter that resolver fixture. Names/instrument/sample evidence remains provisional, with explicit UNKNOWN/CONFLICT states; GM canonical claims remain false. Generated headers are not included by production.

## Fingerprint provenance

These whole-file hashes come from the original metadata export END records. They identify provenance only; original SF2 bytes were not available on this host. Every actual pdtaSha256 remains UNVERIFIED/null.

| Font | Presets | Whole-file SHA-256 (NOT PDTA) |
|---|---:|---|
| Colombo | 892 | `9e4686e4aa7173ffd1baf7ec6f80fcb3fe90880e824cf8c4e1defb5d153d47eb` |
| Prime Drum | 21 | `a72158c09677c585eec348762a33da49509bf338c5b36d0e787af58cca0b9f8a` |
| Prime Melodi | 93 | `e8c7356159c200d945f13e113595ff06e7955779b0e9361709d9c7cdba164a82` |
| Tyros 4 | 142 | `de5b1404630840a2a897e77c6083e2231c5d1523e6571a8afd972a4f684d36ce` |

Renaming exported font names/files leaves provenance roles and preset data identical. No PDTA hash is invented. An observed device hash alone cannot promote a catalog: it must match a separately reviewed expected hash for the same whole-file provenance. Mismatch is a hard generator error. All entries remain runtimeEligible=false through U1.

## Reproduce host validation

```
python3 tools/test_u01_host.py --metadata OWNER_METADATA_DIRECTORY_OR_ZIP --jvm-deps PINNED_JVM_JARS_DIRECTORY --out HOST_RESULTS_DIRECTORY
python3 tools/sf2_catalog/build_catalog.py --help
```

The JVM dependency files and SHA-256 pins are in `tests/fixtures/sff1_jvm_dependencies.json`. Metadata must be the actual four-font owner export; missing/incomplete input fails. The aggregate validates source boundaries, sanitizer RIFF tests, deterministic catalog output, mismatch/rename tests, U0 Kotlin export, baseline/candidate native event and mapping equality, and all Policy C suites including real N10 assertions and named golden data.

Host results:

- PDTA parser: ASan/UBSan PASS, exact domain, sample exclusion, all truncations/byte mutations, invalid sizes/order/duplicates/limit.
- Catalog: 1,148 presets; six deterministic output files byte-identical. JSON 2,980,980 bytes; median parse/load 23.096 ms over 31 host iterations. This is not Android timing. Runtime loads no catalog yet.
- U0 Kotlin export PASS; native differential: 63 event/mapping rows byte-identical and explicit export creates no MIDI/controller writes.
- Policy C main: 1,280 cases, 204 opened, 8 swaps. Nonzero MSB: 7,680, no changes. Named: 39,936, 102 opened, 20 swaps (reconciled audit golden). MSB0: 81,920, 25,900 opened, 8 swaps. All have zero closed/unexpected changes; the final aggregate completed with exit code 0.
- Native regression suites PASS: resolver 210; routing 70; audio path 48; zone 30; kits 15; compact 30; comparison 29; managed audition 12; bank 63; accompaniment 32; percussion 238; role PCM 211; liveness 4; shadow 46. Mock BASS evidence does not prove real PCM sound.
- JVM keyboard/MIDI/release/readiness/mixer regression: 78 tests PASS.
- Production boundary verification: Policy C header, StyleSequencer, ArrangerBrain and MIDI mixer exact bytes; selection, drum, render, keyboard, release, mixer and transport functions unchanged. Only diagnostic export/cache/reason bookkeeping is added.

Historical full-regression CI guards remain BLOCKED by the previously diagnosed baseline compatibility issue. They were neither changed nor represented as passing. No production workflow was modified.

## Device stage and activation gate

Load the four original SF2 fonts in a diagnostic Android build. Existing cache loading records PDTA hashes and elapsed hashing time. Export the existing Inspector drum audit while stopped after loading each font. Collect all four `SF2_FINGERPRINT` rows from the reports (only currently loaded fonts are exported). Retain the raw reports, original file identity and device/build details. Debug builds include the existing whole-file provenance cache; UNAVAILABLE or missing provenance must not be guessed.

Compare all four whole-file provenance hashes with the table, inspect PDTA validity and independently review the hash bindings. Record the reviewed expected values in catalog_review.yaml only after evidence exists; regenerate using `--fingerprints DEVICE_REPORT`. Hash mismatch must fail. Record total hashing time for all four fonts and compare with the design's <300 ms device gate. Host timing cannot satisfy that gate. Catalog remains disconnected from active resolution throughout U1, even after identity validation.

Decision: host implementation GO (aggregate PASS); device identity/performance and catalog activation NO-GO pending actual SF2/device evidence. No sound improvement, device compatibility or full historical CI pass is claimed. No APK was built as part of this host implementation.
