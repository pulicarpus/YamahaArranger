# SF2 master — Golden routing capture contract (2026-10-12)

Status: **CAPTURE CONTRACT READY / CAPTURE NOT YET EXECUTED**.

## Objective
Preserve *audible and semantic* four-SF2 resolver behavior from frozen playable style checkpoint `c75122daf5d1077db55d50d1c261144fde59af4a`. Do not use the broken Voice & RELEASE experiment as a golden source. This is read-only diagnostic specification, not a production code change.

## Required row schema (one decision per event/context)
`fixture_id,style_sha256,section,beat_tick,chord,acmp_enabled,destination_part,midi_channel_1based,request_msb,request_lsb,request_pc_0based,source_file_sha256,source_bank,source_program_0based,native_handle_id,native_bank,native_program,fallback_reason,semantic_family,drum_key,velocity,donor_source_sha256,donor_bank,donor_program,donor_key,volume_trim_db,decision_trace,pcm_reference_sha256,status`

Unknown must be recorded as `UNKNOWN`, not inferred. Each routing decision must have a stable fixture identifier and traceable input and output. Drum-key donor rows can be separate if per-key selection occurs after program resolution. Distinguish 0-based SF2 PC and 1-based MIDI displayed PC. Do not conflate MIDI bank MSB/LSB with SF2 bank or BASSMIDI normalized bank.

## Coverage
- LoveSong Main A/B/C/D, Fill A/B/C/D, Intro and Ending (where supported). Record each section's actual availability and skip with explicit reason, never fabricate.
- Rhythm1/Rhythm2, Bass, Chord1/Chord2, Pad, Phrase1/Phrase2; include silence and absent part explicitly.
- MIDI request PC73 (raw 0-based) and observed fallback, bank 126/127/128, velocity-sensitive percussion donor keys.
- RIGHT1/RIGHT2/RIGHT3/LEFT independently with style stopped. Record voice selection before/after for isolation, but treat current experimental branch as non-golden.
- At least one 2/4, 3/4, 4/4, 6/8 fixture when the actual file and source can be identified; do not fabricate fixtures.
- Envelope/pitch bend/controllers, note-on/off ownership, transition note lifecycle and source generation where present.

## Capture and acceptance
1. Pin commit SHA, source SF2 hashes, fixture style hash, Android/BASSMIDI build identity, and trace schema version.
2. Extract deterministic routing decisions from frozen reference without altering production or build workflow. If instrumentation is unavoidable, isolate it in a dedicated diagnostic branch and prove output parity.
3. Include raw traces and a canonical normalized CSV. Deterministic replay must produce the same row set and event ordering; any nondeterminism is a failure to investigate.
4. Produce an executable comparator: original four-source routing vs planned master routing, matching request context, source provenance, native mapping, fallback, donor selection, and gain trim. Compare actual rendered PCM separately, not just routing CSV.
5. For missing evidence mark `UNVERIFIED`; do not claim equivalence or start master export.
6. Before production: prove master bank allocation constraints, BASSMIDI FONTEX2 mapping, drum bank behavior, sample/modulator/stereo/loop preservation, and RAM/PSS on device.

## Guardrails
- Never edit or merge `main`, protected READY baseline, or frozen style checkpoint.
- No master binary or APK build in this phase.
- Do not assume staging banks are valid runtime banks.
- Do not assume missing gain trims equal 0 dB.
- Do not mix ongoing RIGHT2/RIGHT3/RELEASE fixes into style reference.
