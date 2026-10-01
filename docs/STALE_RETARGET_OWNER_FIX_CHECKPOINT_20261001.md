# Stale RTR owner lifecycle fix — 2026-10-01

## Baseline and proven cause

Branch `diag/audio-path-presence`, remote parent `0637cb6e4211fa3a7a6049464b557962d2eaf639`; Build #763 app source `a8da9819a89411923e33fa57de5d138df2dcf2b1`. Continue the existing chord investigation, CC11/resolver/family and note-zone checkpoints without rollback or sound tuning.

Evidence: `YamahaArranger_ChordCapture_20261001_204523.txt`, 47,025 bytes / 172 lines, SHA256 `7fa7da2a5452aec6493d435e20b148346f012d3951856328ef69992227e1f073`. The user identifies this as #763, but the uploaded report has v1 rather than the published #763 v2 format; binary provenance cannot be established from its header. The relevant playback lifecycle was unchanged between #762 and #763.

First G→C / chord2011 starts at1790862312158. Owner1977 (source11/key65, output67) ends through scheduled OFF at1790862312159, accepted by BASSMIDI (native3438). An earlier chord snapshot still processes this owner as replacement2025: another OFF67 is accepted at1790862312266 (3457), then stale ON64/velocity73 at1790862312267 (3459). Normal source65 event2028 sends ON64/velocity81 at1790862312271 (3460), **4ms later**. Normal source63 event2026 also sends64 at1790862312266 (3458); distinct source notes converging on a pitch are legitimate style behavior, not a deduplication target.

All exported87 native rows are ch11 with requested13333:0 and actual/expected font1:bank0:PC0 Yamaha ConcertGrand, CC7/11=81/127, match/init1. The stale onset is accepted; it is not a demonstrated preset fallback or same-event duplicate. Export omissions limit other transitions. The acoustic contribution of valid RTR onsets versus Yamaha hardware remains unmeasured; this patch fixes only the proven ended-owner resurrection.

## Minimal implementation

`StyleSequencer` checks referential identity `activeTransposedNotes[sourceKey] === active` before any per-owner retarget. A shared monitor holds this check and the full existing OFF→ON replacement together with scheduled owner creation/replacement/end, snapshot release and STOP ledger clear. An ended/replaced owner produces capture-only `STALE_OWNER_SKIP` and sends nothing. Snapshot collection/count also use the monitor; it is released between owners. Release independently validates identity so a stale snapshot cannot end a replacement owner.

No pitch/event-ID deduplication or new musical policy. Existing valid-owner transformation, RTR dispatch, velocity, destination handling and send order remain. NTR/NTT/CASM, program/voice/controller/resolver/native code, gain/drum/family gate, sustain/release parameters and external MIDI remain unchanged. Scheduler sorting, deadlines and waits are byte-identical to #763. Lifecycle sends are deliberately serialized; lock overhead is not claimed to be zero. No logging outside existing explicit capture is added.

## Regression and scope verification

Three new tests in the existing StyleChordDiagnosticRegressionTest:

1. Retain owner1977 as the old snapshot; execute the real scheduled OFF, then stale G→C worker at deterministic event-clock109ms and the real normal scheduler NOTE_ON at113ms. Only normal ON64/81 remains, with no stale ON64/73 or redundant OFF. The4ms interval is synthetic event metadata, not a wall-clock performance assertion.
2. Pause valid replacement during its old-note OFF and race scheduled OFF on another thread. End cannot interleave before replacement ON; output remains OFF67→ON64→OFF64.
3. Old and new owner objects with identical fields, pitch and eventID: stale retarget/release send nothing; the current object still receives its valid replacement. This proves identity, not value/pitch/event-ID deduplication.

Host suite **363 checks PASS** locally:210 resolver,60 native mock,48 capture observer,30 SF2,15 drum. Protected-function comparison confirms unchanged updateHeldPitch, policyScore/selectPolicy, rootPitchForHeld, applyStyleController/applyVoicesFromCasm and clock helpers; scheduler prelude unchanged. Targeted JVM suite now26 cases (5 expression,6 drum,15 chord). CI Build #765 passed all26 targeted JVM cases and real-SDK Android compilation for both ABIs; no broad toolchain search or workflow modification.

Only production StyleSequencer.kt, its existing test file, PROJECT_NOTES.md and this new checkpoint are published against the verified remote tree. Other local snapshot differences are already-published history, not part of this patch.

## Android test

Use the same fonts/style, warm MainD/full mix with C playing. CAPTURE CHORD once; C→F→G→C,2–3s each; hold final C another2s. END→STOP→SAVE CHORD (SMALL). Send only YamahaArranger_ChordCapture_*.txt (v2 header), plus whether the extra G→C attack improved and whether any note sticks/cuts unexpectedly. Large Inspector and repeated AllLog are unnecessary. Valid Piano style/RTR notes remain audible by design.

## Publication

- Production fix commit `a957d5460b7c56585b6249d89b2d6d17ecb2a2b6`; source/APK commit `85f3db7d698e6ef423b654ba3b6d65668128550b`, branch `diag/audio-path-presence`.
- Build #764 run36884973649 failed one new test assertion because the fixture used a zero-length section; the unchanged scheduler correctly skipped it. All25 other JVM cases passed and production code compiled. Retry85f3db7 changes only the fixture length to1 so the actual tick-zero normal NOTE_ON executes; no application change or rollback.
- **Build #765 SUCCESS**, run `36885552908`, job `110447837176`. CI host363 checks PASS; all26 targeted JVM cases PASS, including the three new lifecycle cases. `testDebugUnitTest` and `assembleDebug` both BUILD SUCCESSFUL. Real BASS/BASSMIDI SDK builds for `arm64-v8a` and `armeabi-v7a`; artifact upload and existing delivery succeed.
- [APK app-debug #765](https://github.com/pulicarpus/YamahaArranger/actions/runs/36885552908/artifacts/11175041626) / [workflow](https://github.com/pulicarpus/YamahaArranger/actions/runs/36885552908). Artifact `11175041626`, ZIP12,638,886 bytes, GitHub archive SHA256 `b4027f09434f761e05a04dfa419de30e722859d793ed2a3740534b670b38b2d7`.
- Initial publication changed exactly4 intended files against the remote #763 checkpoint;148 other blobs, including libraries/workflow, unchanged. All4 remote contents matched the intended payload. Retry changed the test file only. Final commit updates only PROJECT_NOTES.md and this checkpoint; APK source remains85f3db7.
- Local workspace remains a partial inspection snapshot with pre-existing published-history differences. Remote branch is authoritative. Android acoustic improvement and timing under device load still require the short test above; mock regressions prove stale-owner send behavior, not Yamaha-equivalent audio.
