# Single-SF2 master — isolated migration plan (2026-10-12)

Status: **PLAN ONLY / NO-GO FOR PRODUCTION CONVERSION**.

## References
- Frozen playable style source: `c75122daf5d1077db55d50d1c261144fde59af4a`.
- Protected READY baseline: `c13fa3e1aec416b8378010e13b1c86914c246e4a`.
- Voice & RELEASE experiment `7121a35` is NOT eligible for promotion: Android device report found ineffective RELEASE and broken R2/R3 selection.
- Four-SF2 read-only analysis was supplied as `SF2_MASTER_MAPPING_PLAN.md` in the project conversation; source inventory CSVs remain user-owned, not checked into this branch.

## Inventory
| Source | Presets | Banks | Role |
|---|---:|---:|---|
| ColomboGMGS2_BM.sf2 | 892 | 52 | GM/GS fallback |
| MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2 | 93 | 5 | Melody primary |
| DRUMKIT YAMAHA PSR-SX700 & SX900 PRIME.sf2 | 21 | 1 (128) | Drum primary |
| yamaha tyros 4_just_t4_fixed.sf2 | 142 | 3 | Alternate melody/drum |
Total 1148 preset entries; 911 unique bank/program addresses; 158 cross-file collision groups.

## Why naive merging is forbidden
- Exact same raw bank:PC can have different sounds (e.g. 0:0, 0:49, 8:1, 128:0).
- Resolver and priority use source identity, Yamaha semantic family, original bank/PC, normalized virtual bank, and MIDI destination MSB/LSB/PC.
- Drum fallback for request PC73 can change if a formerly absent kit becomes present in merged bank 128.
- Drum donor evidence and balance trims are keyed to source provenance/hash; merged SF2 changes hash.
- Current single-file API loads melody normalized copy and original drum separately; lower memory usage is unproven.

## Manifest design
Columns: source_sha256, source_filename, original_bank, original_program, original_name, role, master_bank, master_program, MIDI_MSB, MIDI_LSB, MIDI_PC, destination_part, resolver_priority, fallback_policy, gain_trim_db, drum_key_velocity_rules, evidence_ref, validation_status.

Use unique master addresses for each source preset; never resolve conflicts by first/last entry. Preserve original source identity as metadata in the manifest and adapt runtime resolver explicitly only after review. Drum channel destinations 9/10 (MIDI one-based), melody/style channels 11–16, keyboard channels 1–4.

## Gates before any SF2 export or app change
1. Compare SHA256 of four laptop binaries to repo metadata; archive originals.
2. Derive collision-free address allocation including bank 126/127/128 and variation LSB handling; verify SF2 format and normalizer constraints.
3. Capture reference style channel-to-source routing, including actual note/key/velocity and CASM context; preserve PC73 legacy fallback.
4. Capture reference PCM and perceived balance for LoveSong and representative Main/Fill/Intro/Ending; record measured gain trims.
5. Export *experimental* master to a new filename using a reproducible manifest and full instrument/sample/modulator preservation. No overwrite.
6. Compare rendered PCM, preset identity, drum articulation, style transitions, import time, BASSMIDI handle count and Android RAM/PSS. Gate release on device listening.
7. Only then consider production loader changes and a separate review PR.

## Branch rules
No style, ACMP, RIGHT voice, RELEASE, native loader, or READY edits during manifest phase. This branch is documentation only; no APK claimed.
