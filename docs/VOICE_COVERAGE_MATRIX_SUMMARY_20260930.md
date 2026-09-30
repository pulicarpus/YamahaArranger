# Voice Coverage Matrix — Refreshed Snapshot — 2026-09-30

## Input snapshots

- Style corpus: `styles.csv` from the 569-style SX920 audit.
- Styles: 569.
- Voice setup entries: 7,612.
- Unique Yamaha MSB:LSB+Program requests: 578.
- SF2 inventory: the 2026-09-30 07:28 Inspector snapshot with 7 managed SF2 files and 1,624 inspected presets.

## Refreshed coverage result

Using the current inspector snapshot and treating each unique request as:

1. exact packed Yamaha bank + Program;
2. same MSB + Program (MSB-only SF2 representation);
3. same Program anywhere;
4. none;

the refreshed 7-SF2 inventory gives:

- 25/578 exact packed bank + Program requests;
- 204/578 additional same-MSB + Program requests;
- 349/578 same-PC-only requests;
- 0/578 with no Program anywhere.

Entry-weighted across 7,612 voice setup entries:

- 116 exact;
- 3,737 same-MSB;
- 3,759 same-PC-only.

### Important snapshot discrepancy

The earlier audit note recorded 250/578 same requested MSB+Program and 328/578 same-PC-only. The refreshed calculation above uses the current 2026-09-30 Inspector inventory (1,624 presets) and `styles.csv` directly, so the discrepancy must be preserved and investigated rather than silently overwritten.

This does **not** mean either snapshot is wrong. Possible causes include different SF2 inventory snapshots or different counting/normalization rules. The resolver must not be changed until the counting rule is reconciled.

## Current production pair

The latest application log identifies the automatically selected primary/fallback pair as:

- primary: `MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2`
- fallback: `yamaha tyros 4_just_t4_fixed.sf2`

For this pair alone, the same matrix calculation gives:

- 18/578 exact packed bank + Program;
- 169/578 additional same-MSB + Program;
- 391/578 same-PC-only;
- 0/578 with no Program anywhere.

Therefore loading all seven inspected SF2s does increase numeric bank coverage, but the application currently does not use all seven as an ordered runtime fallback chain. The runtime design remains primary + controlled secondary + separate drum role.

## MSB 104

MSB 104 remains the dominant unresolved Yamaha family:

- 282 unique requests;
- 2,342 voice setup entries;
- no same-MSB+Program candidate in the refreshed seven-SF2 snapshot;
- all 282 have a Program match somewhere in the inspected inventory.

This means MSB 104 cannot be solved by simply adding another bank-normalization rule. It requires voice-family/variation mapping and, where available, a better Yamaha-specific source preset.

## Runtime confirmation

The latest exported runtime log proves the resolver can correctly handle some variation banks:

- `1028 = 8:4 / PC17` resolves to `BASS`;
- `1040 = 8:16 / PC1` resolves to `Steel Guitar`;
- `1029 = 8:5 / PC49` resolves semantically to `String Yamaha`.

The same log also shows that Strings1 and Strings2 can converge to the same source preset, which remains a fidelity limitation rather than a scheduler failure.

## Engineering decision

Do not replace the resolver with blind seven-SF2 search.

Next resolver work must add a deterministic candidate layer that can distinguish:

- exact Yamaha bank/program;
- MSB-only representation;
- Yamaha variation family;
- semantic family/name;
- same-PC family-safe fallback;
- source SF2 priority.

The new `tools/voice_candidate_matrix.py` provides a repeatable audit mechanism for this layer. It accepts any number of SF2 files and produces CSV/JSON/Markdown candidate reports without modifying the playback engine.

## Source-of-truth rule

The refreshed matrix is an audit snapshot. It is not a reason to change CASM, StyleSequencer, transition timing, or drum routing.
