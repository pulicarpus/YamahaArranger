# SF2 Master — Provenance manifest gate (2026-10-12)

Status: **SOURCE IDENTITIES VERIFIED; ROUTING NOT CERTIFIED**.

Four original source hashes match `tests/fixtures/percussion_inventory_fingerprints.json` (see `docs/SF2_SOURCE_VERIFICATION_GATE.md`). The user-provided 1,148-row provisional CSV was transformed into `SF2_MASTER_PROVENANCE_VERIFIED.csv` (conversation artifact; not stored in repository). Each row now has original source SHA-256, original bank and PC, immutable `source_sha256:bank:PC` provenance ID, role, review markers for bank 126/127/128, and separate **staging** bank/program. All 1,148 staging addresses were checked unique. This is **not** a validated BASSMIDI routing manifest.

Source preset totals: Colombo 892, Tyros4 142, SX Melody 93, SX Drum 21. There are 158 groups of cross-source raw bank/PC collisions. The mapping study describes 911 distinct raw bank/PC addresses and 237 extra colliding entries.

**Must preserve:** source-specific resolver semantics, source-family matching, MIDI destination MSB/LSB/PC vs original SF2 bank/PC vs native normalized bank/PC; drum PC73 legacy fallback; per-key/velocity percussion donor decisions; gain profiles Melody 0:0 = -6dB, Melody 8:1 = -6dB, Tyros 0:49 = +12dB. The other gain values are **UNKNOWN, not 0 dB**. Bank 126 includes unusual percussion/SFX source presets and must not be indiscriminately treated as standard melody. Drum bank 127/128 source PC collisions need explicit resolution.

## Next executable, non-production gate

1. Freeze and export reference **routing decisions** per style section, destination part and MIDI MSB/LSB/PC. Capture source SHA256 + original bank/PC + native bank/PC, fallback reason, and percussion donor key/velocity when applicable.
2. Capture audible PCM comparisons on real BASSMIDI and Android for at least LoveSong Main/Fill/Intro/Ending, PC73 drum, bank 126 and Right1/2/3 voice isolation.
3. Derive deterministic, collision-free master address allocation with exact SF2 generator/modulator/sample/loop/stereo preservation and bank 127/128 handling; validate against BASSMIDI mapping limits.
4. Test a **new experimental master**, never overwrite originals. Compare audible PCM, source selection, note ownership, load time, and RAM/PSS. No production loader/style modifications until pass.

This branch must not merge unverified Voice & RELEASE changes or edit `main`, READY baseline, or protected style checkpoint.
