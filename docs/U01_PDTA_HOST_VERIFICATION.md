# U0/U1 original PDTA verification and diagnostic build

Starting commit: 975e2a6599b14f49e7e4229f615737d30c5144ab, branch experiment/universal-yamaha-u0-u1. This supersedes the missing-byte/name blockers in the preceding checkpoint. No production source is changed from that commit.

The owner supplied YamahaArranger_SF2_PDTA_Export.zip, SHA-256 `15c7c1f1d4786fd549076f6b62339810514fbd8a613bc2348d8db0461cac2875`. It is retained as a metadata-only CI fixture: four PDTA articulation/name/header tables and derived TSVs, with no original SF2 or sdta/PCM. All payloads contain exactly the nine permitted PDTA tables. The earlier choice to keep raw PDTA outside the repository is superseded for this metadata-only reproducible CI fixture; the prohibition on SF2/audio remains intact.

## Original byte proof

The production native parser and Python SHA-256 independently match all four audit goldens. The independent bounded Python inventory validates table order/extents, terminals, preset/instrument/sample links and inherited generators. Every name TSV row and every attenuation TSV row exactly matches the original bytes, including full 20-byte names and padding, raw PG48+IG48 and clamped model values. Manifest table offsets/sizes/counts are checked, not trusted.

- Presets: 1,148; instruments: 1,211; samples: 10,424. Twelve terminal records are additionally verified.
- All 12,783 nonterminal P/I/S names have exact 20-byte nameHex. No unknown names are filled by inference.
- The capped earlier audit omitted six Colombo preset corrections. Total recovery against the legacy owner export is 14 preset, 1 instrument and 15 sample text corrections. See U01_RAW_NAME_RECONCILIATION.json.
- All 66,944 original zones match the legacy export's relation IDs, effective key/velocity ranges and raw attenuation exactly. No coverage/attenuation fixture discrepancy was found.
- Negative census remains Colombo 761 / Tyros 567 / Prime Melodi 2230 / Prime Drum 4393, with minima -175 / -367 / -1440 / -1440 cB.
- Colombo127=XG, Colombo128=GS, Tyros128=GS; Prime Drum=CUSTOM with separate keyMapStandard. Prime sample-name evidence remains zero. Policy C conflicts and model-A LEVEL_OUTLIER are preserved, without selection/gain changes.

The catalog is generated deterministically from verified PDTA through a catalog-input projection of actual names, bags, sample frame headers, ranges and inherited generators. This projection is labeled derived, not a device export. All seven outputs are tested byte-identically. Host identity status is HOST_PDTA_VERIFIED_DEVICE_PENDING, while runtimeEligible remains false and the runtime catalog is disconnected. Whole-file SHA-256 stays provenance, not a replacement for pdtaSha256.

## Reproduce

```
python3 tools/sf2_catalog/verify_golden_pdta.py --pdta-dir tests/fixtures/sf2_pdta/export.zip
python3 tools/test_u01_host.py --metadata tests/fixtures/sf2_pdta/export.zip --jvm-deps PINNED_JVM_DEPS --out HOST_RESULTS
python3 tools/test_u01_regressions.py --suite native --out NATIVE_RESULTS
python3 tools/test_u01_regressions.py --suite jvm --out JVM_RESULTS
```

The aggregate runs original golden verification, corruption rejection, sanitizer RIFF tests, deterministic catalog/identity/family properties, U0 Kotlin and raw-name scanner tests, native recorder comparison, P1–P8/N1–N14/R1 (including genuine N10), and all four full-pool A/B suites. Existing native/JVM contract bodies are unchanged; the separate regression runner invokes their original native block/JVM function. Historical full-CI guards are neither changed nor claimed to pass.

Policy C expects main1280 opened204/swaps8; nonzero7680 no changes; named39936 opened102/swaps20; MSB081920 opened25900/swaps8. Each has zero ACCEPT→REJECT/unexpected changes and checks the original per-case golden. Playback/mapping traces must remain identical (63 rows). Ten scanner tests, including exact apostrophe round-trip, and 78 existing JVM tests must pass.

## Build scope

The new Universal Yamaha U0 U1 Diagnostic APK workflow is restricted to this experimental branch. Host gates are a prerequisite for Android. It verifies parent975e2a6, the protected f4f2c613 tree, zero production delta, unchanged existing workflows/historical guards, exact PDTA fixture and byte-pinned BASS SDK. It runs Gradle assembleDebug for the existing two configured ABIs, verifies debug signing, APK native libraries, SDK identities and source provenance, then uploads the APK and evidence. Existing Build APK and all other branches are untouched.

Full historical regression CI remains BLOCKED by baseline-profile compatibility. This workflow is an isolated diagnostic build, not production certification, a release or a merge request. No resolver/drum/mixer/CASM/transport/LIVE/REG/RELEASE/RIGHT OFF/MIDI OUT code changes or automatic Bank/PC sends are introduced.

Host gates authorize a diagnostic build only. Actual Redmi Pad fingerprints/timing, ROSITA listening, E343 behavior and device regressions remain unverified until the owner tests. Calibration stream P1–P8 is still separate; no engine path is added. See U01_PDTA_DEVICE_TEST.md for export instructions; STYLE FIDELITY is in SAVE REPORT, not SAVE DRUM (SMALL).
