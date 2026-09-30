# Voice Resolver V2 — Checkpoint 2026-09-30

## Scope

This checkpoint records the next engineering decision after the 569-style / seven-SF2 coverage audit and the user-provided mute-per-channel audio test. It does not change playback code by itself.

## Corpus coverage baseline

The existing SX920 coverage audit reports:

- 569 styles
- 7,612 voice setup entries
- 578 unique Yamaha bank MSB:LSB + Program Change requests
- 1,624 inspected SF2 presets across seven managed SF2 files
- 250/578 unique requests have same requested MSB + Program coverage
- 328/578 have only same-PC coverage under a different bank
- 0/578 have no matching PC anywhere
- MSB 104 accounts for 282 unique requests and 2,342 entries, with no same-MSB+PC coverage in the inspected SF2 set

The audit explicitly states that same-PC-only is not safe as a general automatic match and that final selection must consider role, Yamaha bank family, variation, semantic voice family, and fallback order.

## Current native runtime evidence

The latest exported runtime log confirms that the resolver is producing auditable source identities.

### Bass

CH10 resolves exactly:

- requested Yamaha bank: 1028 (`8:4`)
- program: 17
- source name: `BASS`
- source bank: 8
- source program: 17
- destination: bank `8:4`, program 17

Therefore the UI label `B3 Org` is not evidence that the native source is Organ. The native resolver says the source is `BASS`.

### Piano

CH11 uses:

- source: `Yamaha ConcertGrand`
- destination: bank `104:21`, program 0

### A.Guitar

CH12 uses:

- source: `Steel Guitar`
- destination normally `8:16`, program 1
- other Yamaha variation requests can resolve to a different destination LSB while retaining the same source family.

### Strings

CH13 (`Strings1`) and CH14 (`Strings2`) both currently resolve semantically to the same source preset:

- source: `String Yamaha`
- source bank 0
- source program 49
- destination bank `8:5`, program 49

This is a confirmed coverage/fidelity limitation, not a scheduler failure.

## Native preload correction already applied

The native preload path was corrected so melodic preload uses the resolver-selected `melodySourceProgram` rather than the destination `state.program`. Source voice name is also retained in channel state and included in diagnostics.

Relevant diagnostics now include:

- `VOICE RESOLVE`
- `VOICE MAP`
- `SET PRESET`
- `BASSMIDI preload`

These logs must be treated as the authoritative evidence for source selection during the next regression run.

## Important finding

The current engine is no longer best described as having a generic "missing voice routing" problem. For the tested style, drum, bass, piano, guitar, and strings paths are producing concrete native mappings. The remaining emptiness is more consistent with incomplete Yamaha voice-family/variation coverage and source-fidelity differences across the available SF2 set.

## Next engineering phase — Candidate Matrix

Do **not** change the resolver again merely from one audible example.

The intended candidate order is:

1. role separation: DRUM vs MELODY/SFX;
2. exact Yamaha bank + program;
3. Yamaha MSB/bank-family + program;
4. known Yamaha variation/LSB family;
5. semantic voice-name/category family;
6. same-program cross-bank fallback;
7. Piano/final safe fallback.

For each of the 578 unique requests, the eventual matrix must record:

- requested MSB
- requested LSB
- requested program
- requested Yamaha voice name
- role
- exact SF2 candidates
- same-MSB candidates
- variation candidates
- semantic candidates
- fallback candidates
- selected source SF2
- selected source bank/program
- reason for selection

The 328 same-PC-only requests must not be silently treated as exact matches.

## MSB 104 priority

MSB 104 is the largest unresolved Yamaha family in the current seven-SF2 inventory. It contains 282 unique requests and 2,342 voice entries without same-MSB+PC coverage. These requests require Yamaha/Tyros semantic and variation mapping rather than blind same-PC substitution.

## Drum rule

Yamaha style rhythm requests using MSB 126/127 must remain in the drum-role resolver. The current inspector represents Yamaha drum banks through SF2 bank 128, so 126/127 → drum-bank-128 handling must remain explicit. Drum presets must never compete with melodic candidates.

## Do not touch yet

Until the candidate matrix is generated and reviewed, do not:

- alter CASM;
- alter StyleSequencer timing;
- alter Main/Fill transition logic;
- force Bass → Bass or Strings → Strings based only on UI labels;
- remove the secondary SF2 fallback;
- replace BASSMIDI with another engine.

## Regression requirement

After resolver changes, rerun at least:

- the current tested style with mute-per-channel;
- drum + melody simultaneously;
- Bass;
- Piano;
- A.Guitar variation;
- Strings1/Strings2;
- several styles containing MSB 104 requests;
- several styles containing Yamaha variation banks;
- at least one style with 126/127 rhythm requests.

The exported log must contain source identity for every resolved melodic channel.
