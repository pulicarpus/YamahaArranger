# Chord change / retarget / active preset capture — 2026-10-01

## Baseline and scope

Repository `pulicarpus/YamahaArranger`, branch `diag/audio-path-presence`. Parent remote HEAD `a32a7c14ff166d657b31a448ca823827a349d2a6`; application baseline is successful **Build #759**, source `9b1a3a8a1c6e6de18dfd07eaf13bc2b9cea4fbd5`. Continue NOTE_ZONE_DIAGNOSTIC_CHECKPOINT_20261001.md, including all prior actual-note metadata, 21-kit audit, isolated audition and Strings2 solo instrumentation.

New Android listening observation: unintended Piano/ConcertGrand especially during C → F → G → C. No new correlated #759 device capture accompanies that observation. Listening alone establishes neither source part nor actual preset. This candidate adds finite observation only; no sound fix is justified yet.

## Proven source findings, not runtime root cause

1. Scheduled style notes pass source/id metadata through `noteOnStyleChannel`. Existing `updateHeldPitch` chord replacement used `noteOnChannel`, which lost that correlation. Existing sampled native logs report live preset after send only. #759 cannot reliably prove the actual pre-send preset on each chord replacement.
2. Existing chord handler selects a new CASM policy and may change destination. It sends the old destination NOTE_OFF first, then the replacement path sends an OFF on the new destination and an ON if target pitch differs. It does not activate bank/program/preset in that handler. This order is retained, including unchanged-target/deferred/STOP/policy-miss behavior. A destination may already have correct setup; absence of an activation call is not proof of a bug.
3. Retarget has no new reserved/locked/muted gate inserted. Those flags are observed as evidence of current behavior. An uninitialized destination is especially worth checking against actual BASS font identity, but neither an uninitialized destination nor fallback Piano is established on Android yet. Never infer source Piano from what was heard.
4. No reinterpretation of existing CASM decisions, no family gate bypass, no universal Piano fallback, no forced preset restoration or note remap is introduced.

## Explicit short capture

Inspector adds `CAPTURE CHORD (60s)` and `END CAPTURE`. Capture defaults OFF. Arming clears only diagnostic rows and records all16 native baseline channels; it neither clears sound nor sends MIDI. STOP does not delete rows. Expiry/END stop capture; subsequent SAVE REPORT retains the evidence. A new arm replaces the previous capture.

Kotlin retains at most2048 rows; native at most4096. Overflow is explicitly counted (`dropped`) and the original rows are retained. `active`/`timedOut` are reported. Use a short15–25 second session to avoid truncated evidence. No new per-event UI/logcat logging is added. Formatting and export occur on explicit SAVE REPORT. While armed there are additional getter/name-copy/timestamp operations and memory allocations under the existing synth mutex. This is bounded diagnostic overhead, not a claim of zero timing cost. Outside capture, native capture returns immediately on a Boolean check; send() also increments a diagnostic sequence counter. No render callback work is added.

Both layers record wall-clock milliseconds. Kotlin and native monotonic nanoseconds identify local ordering; their clock origins must not be assumed identical across runtimes. Correlate first by `chordId` + `id`, and then source/destination/key, wall time, capture order and native `midiOrder`.

Style records include CHORD_CHANGE old/new root/quality, active ledger provenance (source section/part/channel, original key/velocity/bank/headerPC/original scheduled tick/onId), policy NTR/NTT/RTR, old/new destination, transformed key and no-send/release/replacement decision. Scheduled notes/drops during capture also carry the latest chordId and source part. Source BANK state and dynamic PROGRAM requests are recorded independently. Source headerPC is not a claim about the current destination program.

Each retarget replacement carries the same event ID to the old OFF and transformed ON. External MIDI calls retain their original order/arguments. Native NOTE_PRE follows ensureEngine and precedes the unchanged family gate/send. NOTE_POST records exact send status/error and live readback under the same existing mutex. OFF_PRE/OFF_POST cover the actual replacement offs. Native captures all note channels, including keyboard; legacy/keyboard events have id0 and source unknown, not an invented style part.

CONTROL_POST records bank MSB, bank LSB, PROGRAM, drum-mode and all-notes-off attempts with actual API success and readback. Native `midiOrder` counts all existing send() attempts, including failed ones. Native `mapGen` identifies FONTEX2 generations. Each snapshot reports requested bank/PC/voice, initialized state, selected source identity, expected font/bank/PC, actual destination BANK/LSB/PROGRAM controllers, actual BASS font/bank/PC/name/file, family by preset name, mappingMatch, CC7/CC11. Unknown names remain unknown. No GM numeric label is substituted for an unknown custom preset name.

BASS preset/font identity is evidence for the event's channel state, not a per-voice sample ID, rendered PCM proof or proof that a ringing older voice changed timbre. Pre/post disagreement can expose a transient state change; a stable live preset with wrong timbre would require inspecting samples/layers or old ringing voices. Drum audition and #759 note-zone evidence remain available without alteration.

## Regression boundary and validation

Host suite passes **340 checks**:210 family policy,56 native/mock routing,29 observer/capture,30 SF2 metadata and15 drum coverage. New tests compare exact MIDI send histories with capture OFF/ON, pre/post Piano mismatch detection in a synthetic BASS mock, send rejection, failed PROGRAM, live Strings identity, export/stop isolation and hard cap/expiry. Synthetic Piano switching is a test fixture, not Android evidence.

New targeted Kotlin class exercises the real chord setter/held-note ledger for C → F → G → C, every RTR mode0–6, off-before-on and preserved velocity, destination change, CASM policy miss, unchanged pitch, deferred mode, reserved destination observation, IDs and bounded expiry. Workflow's targeted test command adds this class only; no SDK downloads, source patching or delivery changes. Existing CC11 and drum-profile tests remain selected.

12 protected native functions remain text-identical to baseline: findMelodicPreset, findDrumPreset, normalizeMelodySf2, preloadCurrentPreset, ensureEngine, setChannelMixer, setChannelExpression, setKeyboardSustain, setKeyboardReleaseTime, render, setChannelPreset, applyFonts. Changes to noteOn/noteOff/send only add observation/readback. Family policy, SF2 zone parser, drum audit code and native libraries are unchanged. Scheduler/transform/policy scoring and controller behavior remain unchanged. Source workspace is a partial inspection snapshot; remote base tree is authoritative, not local git HEAD.

Local Android/Kotlin toolchain is unavailable. CI must confirm targeted JVM tests, both real-SDK Android ABIs and APK assembly before this candidate is declared successful. Publication/build identity is appended below after CI.

## Short Android procedure

1. Install the successful build recorded below. Load the same four fonts and Love Song; wait until loading finishes. Use the full mix, play MainD with C for several seconds, and clear AllLog. Do not solo, change kits or audition during this test.
2. Inspector → CAPTURE CHORD (60s) → return to playback. Hold C → F → G → C, about2–3 seconds per chord; repeat once while MainD continues. Note the change where unwanted Piano is heard. If necessary vary change position slightly to catch a held note; the report marks activeNotes=0 when no replacement was possible.
3. Inspector → END CAPTURE, then STOP → SAVE REPORT. Export complete AllLog as well. Send **both complete Inspector and AllLog**, plus the chord transition where Piano was heard. Inspector contains the detailed finite capture; AllLog alone is insufficient for the new pre/post evidence.

## Published build evidence

Pending CI at source publication; final build/run/commit/artifact is recorded in the final documentation commit.
