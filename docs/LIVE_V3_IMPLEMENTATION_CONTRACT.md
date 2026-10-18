# LIVE V3 LED UI — IMPLEMENTATION CONTRACT

Base: perf/right-latency at 02ee6bcae3e1 (branch feature/live-control-v3-current-baseline).
User-confirmed playable configuration: PSR-E343 MIDI OUT. Preserve all working MIDI OUT, audio, Style and RIGHT voice paths unchanged.

## Layout for Redmi Pad landscape
Compact full-width status header showing loaded STYLE, current section, voice names R1/R2/R3 (and LEFT where available), TEMPO and TRANSPOSE with small increment/decrement affordances. No oversized tempo/transpose cards.
Transport area: INTRO 1/2/3, MAIN A/B/C/D, FILL A/B/C/D, ENDING 1/2/3, START/STOP, ACMP, AUTO FILL. Buttons visually emulate dark physical arranger keycaps with thin LED top bar and generous touch targets. Hide or disable unsupported functions; no fake working controls.
RIGHT-side independent vertical faders R1/R2/R3/STYLE with numeric 0..127; drag bottom to top increases. Connect only to existing real volume state/actions, never introduce new audio ownership.
Bottom REG BANK navigation and eight registration slots, preserving existing recall/freeze and external MIDI semantics.

## Section state
Available idle: blue LED; currently active: amber LED; truly absent: gray disabled; unknown parsing: neutral distinct from absent. Pending amber only when a real pending state exists. Derive section availability from loaded style's parsed SFF1/SFF2 metadata; no hardcoded Dangdut example. Avoid claiming that parser errors mean section absent.

## Implementation guard
Change only UI and necessary read-only state adapters. No modifications to StyleSequencer, CASM routing, BASSMIDI, Oboe, MIDI OUT or note lifecycle. Existing LIVE V2 reference branch is reference only; port cautiously, don't merge wholesale.
Before calling any button functional, inspect actual current ViewModel signatures and state. If no callback exists, disable and document. Compile and run targeted tests; do not build or distribute APK without verifying UI compiles. Do not merge into main without device test.

## Acceptance
Check target landscape layout and font scale, independent faders, section metadata available/absent/unknown, no ghost controls, real transport callback wiring, and no protected audio changes.
