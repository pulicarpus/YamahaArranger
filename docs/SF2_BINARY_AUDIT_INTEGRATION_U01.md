# U0/U1 binary-audit integration — available evidence checkpoint

Historical checkpoint at 975e2a6. Original-byte/name blockers are superseded by [PDTA verification](U01_PDTA_HOST_VERIFICATION.md).

Branch: `experiment/universal-yamaha-u0-u1`. Starting commit: `2b4bd2c64400a2f9451cf4ec18c6065f8159fddf`.

The owner supplied the universal design, SF2_BINARY_AUDIT_CATALOG_CORRECTIONS.md, and SF2_audit_evidence.zip. All were read. The later owner instruction explicitly defers final U1 validation until Claude supplies four original PDTA chunks; only verifiable work continues now. The audit's future resolver, drum and gain recommendations do not authorize activating U2–U8.

## Integrated facts

The audit's normative hash domain includes the four-byte `pdta` LIST type. Commit 2b4bd2c excluded it. The native diagnostic parser and generator domain are corrected to `sf2-list-pdta-payload-v2`: SHA-256 of the LIST payload including `pdta`, child headers/data/padding; excluding the 8-byte LIST header and outer pad. Old device manifests must be regenerated. This changes diagnostic hashes, never preset choice.

| Role | Presets | Golden reference pdtaSha256 | Payload bytes |
|---|---:|---|---:|
| Colombo | 892 | `ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506` | 446466 |
| Prime Drum | 21 | `338385762217a9ecd9ecb8707fa71202a115da4eaad81441c27d714acbf61283` | 197194 |
| Prime Melodi | 93 | `cfb096e3c197570554313aaca227f7e207ac64867e9871a025c1b4b2c6e9c136` | 208430 |
| Tyros 4 | 142 | `16bb60deafdf426cdc7e269b6c5f434cd5b7db6e14a83f86d17f54720176217a` | 260470 |

These are Claude references, not hashes recomputed from original bytes in this workspace. Whole-file hashes remain separate metadata provenance. No filename determines font identity. The device/golden gates remain open; runtimeEligible=false for every entry and generated headers remain disconnected from production.

The catalog has all 1,148 presets (989 voice, 159 drum). Colombo bank 127=XG, bank 128=GS; Tyros bank 128=GS. All Prime Drum entries remain CUSTOM. Separate keyMapStandard is XG_PSR_LIKE for STANDAR/DRUM2/DRUM3 and PSR_CUSTOM for the other kits, with review required. Kendang Alex and ALEX KEPRI record no zones at keys 33–35; existing playback still chooses exactly as before.

The sample-name ambiguity ratio is derived from Claude's sample-data hash census: Colombo 20/3264, Prime Drum 396/1206, Prime Melodi 300/1188, Tyros 2/2555. A ratio >10% gives sample-name family evidence weight zero. Thus all Prime sample names, not merely SATRIOMUSIK names, are excluded from family inference. They remain in the data. No new sample hashing was performed here.

Inherited PG48+IG48 is computed from every original metadata Z row. The census exactly matches the binary audit:

| Role | Zones | Negative zones | Affected presets | Minimum cB |
|---|---:|---:|---:|---:|
| Colombo | 50701 | 761 | 81 | -175 |
| Tyros | 3689 | 567 | 31 | -367 |
| Prime Melodi | 5949 | 2230 | 28 | -1440 |
| Prime Drum | 6605 | 4393 | 16 | -1440 |

Per-preset negative counts/ranges retain all exported zones. Levels A/B are imported only for named targets actually modeled in the supplied JSON; other values stay null. Models omit engine envelope, velocity curve, controllers, filter and modulator effects. LEVEL_OUTLIER uses model A >12 dB below the median of available candidates in the same role. Prime Yamaha Honky Tonk is flagged (-43.4/-26.6 dB); this does not prove either engine attenuation model. Existing Policy C conflicts and allowlist remain unchanged. Rules carry source references; fixture provenance pins each imported evidence file by SHA-256.

## Names: no invented reconstruction

The known five Colombo and three Prime Drum preset corrections preserve apostrophes from audit JSON. The crosscheck source caps its mismatch list at five. Colombo reaches this cap, so the 887 unlisted Colombo preset names cannot be declared byte-accurate. Their nameHex stays null. Tyros/Prime Melodi have full preset-name equality; the Prime Drum list has only three mismatches and is not capped. Their decoded name-content bytes are recorded with an explicit scope excluding NUL/padding, not presented as complete raw 20-byte fields.

Original instrument/sample bytes are absent from the audit ZIP. Legacy text is retained and clearly labeled lossy, with null nameHex. `catalog_names.json.gz` inventories P/I/S identity and this status. No global '?'→apostrophe substitution is used.

The explicit Android metadata exporter now writes escaped text plus the exact 20-byte nameHex field for P/I/S, including NUL/padding. Its scanner/classification/control logic is unchanged. The generator accepts these fields as authoritative bytes. Ten scanner tests include apostrophe round-trip for preset, instrument and sample.

## Host validation and limits

Available-evidence host gates exercise:

- Native RIFF/PDTA parsing under ASan/UBSan: bounds, truncations, mutations, duplicate/order/size checks, sample exclusion and the corrected fourcc-inclusive domain.
- Seven deterministic catalog outputs (final JSON 4,587,467 bytes; host parse/load median 24.341 ms over 31 iterations, not Android), 1,148 presets and four-font table counts; exact negative-attenuation census; map corrections; conflicts; LEVEL_OUTLIER; all four reference-match and deliberate mismatch manifest cases.
- Golden verifier tooling tests: exact synthetic payload hashing, malformed payload rejection, missing originals fail, and valid synthetic substitutes cannot satisfy an original golden. These do not recompute the missing original hashes.
- Renamed files/display names preserve font identity. Replacing every Prime sample name leaves family/evidence identical. Permuting a neutral candidate's PC cannot add a family.
- Kotlin U0 export and ten metadata scanner tests, including raw-name round-trip.
- Native before/after comparison: 63 MIDI event/mapping rows identical; explicit diagnostic export creates no MIDI/controller writes.
- Policy C P1–P8, N1–N14 (real N10), R1 and complete main/nonzero/named/MSB0 A/B. Counts must remain 204/8, 0/0, 102/20, 25900/8 opened/swaps respectively, with zero closed/unexpected changes and original canonical golden checks.
- Existing native regression suites (the C++ block from tools/test_voice_resolver.py, run separately from the incompatible historical source guards) and 78 keyboard/MIDI/release/readiness/mixer JVM tests. These are mock/host evidence, not device audio.

The historical full-regression CI incompatibility is unchanged and is not represented as passing. The only production delta from 2b4bd2c is the diagnostic fingerprint header and metadata export formatting. Policy C, NOTE_ON guard, selection, main audio, MIDI OUT, CASM, transport, mixer/gain, LIVE/REG/RELEASE/RIGHT OFF remain protected.

## Original PDTA handover / next gate

Ask Claude to export LIST payload bytes, not text and not just a digest. Required content begins `pdta`, excludes LIST+size and excludes outer padding. Preferred names: COLOMBO.pdta, PRIME_DRUM.pdta, PRIME_MELODY.pdta, TYROS4.pdta. Supply an export manifest with original whole-file hash, payload offset/size and payload SHA-256. No sdta, sample PCM or audio is needed.

```
python3 tools/sf2_catalog/verify_golden_pdta.py --pdta-dir OWNER_PDTA_DIRECTORY
```

This tool requires all four original payloads and compares size/hash through the same production native parser. Missing or mismatching input fails; no replacement bytes are generated. Raw files stay outside the repository. The current missing-input gate correctly fails and is PENDING, not PASS. Final raw-name reconciliation, full golden recomputation and any seek-based device hashing optimization follow when the original PDTA is supplied.

## Device / APK stage (not performed)

No workflow was dispatched and no APK built at this checkpoint, following the owner's instruction to wait for final U1 evidence. No device hashing/peak/RMS measurements were taken. Existing font-cache hashing still operates on bytes already loaded by the existing cache; a <300 ms PDTA-only seek-based Android measurement has not been demonstrated.

After the original-byte host gate passes, build a debug APK containing both arm64-v8a and armeabi-v7a. Use Inspector → FULL METADATA (ZIP) to export raw names. Load each original font and save the explicit drum audit to collect all four SF2_FINGERPRINT rows. For ROSITA, stop playback and save the drum audit for each active section; STYLE FIDELITY only attributes the active binding at export, with other sections UNOBSERVED. Retain reports and build SHA. No bank/program messages may be sent to E343 by these exports.

P1–P8 calibration remains a separate unimplemented device task: decode-only stream, no audio output, independent handles/resources, no main-stream attribute or shared-font mutation, safe cleanup and bounded duration. The requested probes are Prime Drum128/0 key36; Colombo127/8 key36; Prime Melodi0/27 key60; Tyros0/27 key60; Prime Melodi0/3 key72; Tyros0/3 key72; Tyros0/33 key36; Prime Drum128/8 key33. Velocity100, duration1s. Peak/RMS, engine version, golden font identity and hashing time must be recorded; no values may be inferred from audit models. It is deliberately deferred rather than adding a new engine path before identity validation.

Decision: GO for integration supported by available evidence only. Final U1 / diagnostic APK / catalog activation remain NO-GO pending original PDTA and device gates. No claim about sound on Redmi Pad or E343 is made.
