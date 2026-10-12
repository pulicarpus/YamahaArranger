# Experimental style/chord recovery — not verified on Android

Protected READY: c13fa3e1aec416b8378010e13b1c86914c246e4a.
Input musical source: e75cee02887dfee3f0b3f8c924ffa40fcfc899a1.
Frozen APK source: c75122daf5d1077db55d50d1c261144fde59af4a.
Branch: experiment/style-chord-recovery. No READY/main merge or release.

## Implemented changes

1. `MainViewModel.kt`, DebugLog: cached per-thread formatter preserving HH:mm:ss.SSS/Locale.US/current system timezone; bounded standard ArrayDeque storage with short monitor-protected append/copy/clear. Both 30/30000 limits, prefixes, duplicates, actual newlines and static JNI methods retained. Formatter and string joining remain outside the logger monitor. No logger method calls native/MIDI/arbitrary callbacks under this monitor, so it introduces no logger→native lock ordering. Producers can still wait for a snapshot copy; this is not a wait-free/hard realtime logger. Legacy exception propagation is not changed or claimed no-throw. Capture-only primitive counters record formatter cost and logger lock wait/hold. `F12_LOGGER` is exported by existing CHORD CAPTURE; aggregates are best effort/non-atomic and do not prove audio success.
2. `StyleSequencer.kt`: wake for already admitted immediate transitions, consider every pending boundary while waiting, wait at equal boundary/event deadline, and recheck pending transition after suspension before outgoing dispatch. Existing ownership cleanup and natural queue handling are retained.
3. `ArrangerBrain.kt`: when Main is sounding and the original helper yields a known future target, publish that target to Sequencer before the boundary instead of posting from a separate timer at the boundary. Main→Intro/Fill/Main/Ending follows that existing target. Existing B shortcut and C tempo mapping remain. Actual section-start callbacks publish three volatile references to UI readers; this is visibility, not atomic multi-field/session state-machine certification. Ending retains the existing one-shot completion→STOP policy. Non-Main request policies and the existing ≤25ms window/fallback remain unchanged.

No NTR/NTT/RTR, F03 source/key ownership, note sorting/F02, terminal duration/F12, SoundFont resolver, native BASSMIDI, mixer, Oboe callback or routing change. Stage3/P2 remains off. No global ALL_NOTES_OFF introduced for Main/Fill transitions; Ending's terminal STOP keeps its original policy.

## Audit findings matched against actual code

| Finding | Decision/evidence |
|---|---|
| New formatter and ConcurrentLinkedQueue.size per log | PROVEN source and host. Removed repeated formatter creation and O(n) queue-size traversal. Android dominant-cost/audio effect UNKNOWN. |
| Ending queued to natural Main section end | PROVEN source/host. Two-bar Main, request500ms: Ending4000ms/tick800 before →2000ms/tick400 after. Completion STOP preserved. |
| Pending transition not waking/rechecked after wait | PROVEN original-class witness: admitted500ms, Fill started3000ms with leaked outgoing ON before; after500ms without that ON. |
| Quantized admission versus worker boundary | Original strict diagnostic12/24 FAIL →24/24 PASS. Fix serves known targets while Main runs. |
| RTR1/2 pitch change uses OFF→ON like retrigger | PROVEN code/logical captures; six RTR modes×six part destinations. C→F→G→C and Major→Minor→Seventh before/after native-request and MIDI-wire captures identical. No RTR repair or global make-before-break. Yamaha held-voice contract/backend per-instance pitch control remains UNKNOWN/BLOCKED. |
| 3–8ms Android spacing / creaking noise | Device observation retained, cause UNKNOWN. Audit has a lateness-unit typo: old records expressed in microseconds convert to about77–100ms, not77–100 microseconds. Host logger benchmark does not certify Helio G99 latency. |
| Shared BASSMIDI mutex / render | Source fact, unchanged. Existing F12 native waits/holds/callback durations/gaps can be compared to logger statistics. |
| Xrun count / interpolation guess | No speculative change. A safe export-thread ownership contract for shared Oboe stream is not established; no new getXRunCount read/disposal race introduced. Callback-gap data is not xrun count. Interpolation not changed. |

## Host checks executed

- Logger:4 legacy checks;8 candidate checks including deterministic blocked-writer clear/snapshot, capacity/duplicates/concurrent-no-loss, snapshot/clear stress, timing enable/disable. Host benchmark with30000 retained lines: old p50~247.5µs/p99~539.4µs; candidate p50~0.57µs/p99~0.93µs. One host run, not Android guarantee. Message construction before add, JNI and renderer costs are outside this formatter measurement.
- Original-source section/timing matrix:99 PASS (96 Main/AutoFill/Intro/Ending admissions across four meters, one/two-bar Main, early/middle/late requests;2 existing deferred AutoFill-tail sequences;1 pending replacement). Deferred Fill results are application-policy proofs, not Yamaha interruption oracle.
- Original strict admission24/24 PASS, before12 FAIL preserved.
- New wake/outgoing-event witness, Ending callback and keyboard isolation PASS.
- T01 192/192 plus2 controls PASS; B144/144 PASS; C234/234 PASS; actual Default/nanoTime18/18 PASS (native/Android doubles).
- Musical/F04 group90/90; READY Engine/Brain/session17/17; admission24/24 PASS. These functional tests are not historical candidate identity certification or S01 STOP proof.
- LoveSong3 uploaded real file SHA2565c3dc10c4d20be5a28d6b572f7341bbb2889c2d0854b17f3ee5fe8fb26698773:9 controls and32 tempo/section replays PASS, original parser/decoder; native boundary is a mock, MIDI serializer real. Captured control requests/bytes retain their expected order. Not PCM/device evidence.
- Historical fixtures/golden/guards/build configuration unchanged. Three exact production blobs differ; other414 original tracked files are protected. SDK6 retain original identities. Separate experimental input verifier has positive and negative source/guard/fixture/workflow/build-input/unknown-input/SDK/index/symlink/SHA/parent controls. CI records exact source and workflow SHA and verifies source before/after original assembleDebug.

All test seams are documented in tests/diagnostics/style_chord_recovery. Assertions are retained. Earlier harness failures are preserved externally: Intro's legitimate return-Main was initially misclassified as leakage; baseline retarget collector initially omitted noteOnChannel; a T01 integrity run rejected concurrent experimental-workflow edits and passed once files were frozen. None required musical assertions/golden relaxation.

## Explicit unresolved cases

- Strict manual FillB→requestFillA→newerMainA: baseline AND candidate cancel the unadmitted middle Fill. The strict witness remains FAIL; this is not a new regression or Yamaha-certified cancellation. Its capture is retained and the portable runner asserts this known failure separately. Two positive AutoFill-tail sequences reach FillB→FillA→MainA / FillD→FillB→MainB at ticks400/800/1200. Do not conflate these request paths.
- Intro/Ending START from stopped transport retain the known loop/successor-contract problem. No forced default Main added. Historical evidence, not freshly claimed device PASS.
- Existing ≤25ms/very-late request fallback, Inside Intro/Ending rules, active-Fill interruption, late native setup and backend scheduling stall are not globally certified.
- S01 cancellation/old-worker dispatch remains a known failure; no session redesign/disposal fix.
- F03 overlap pairing/selective release and audible RTR continuity remain unresolved. No audio improvement claim from green tests/CI.
- Original503-style ZIP/hash unavailable: BLOCKED, not rerun503/503.
- Android/PSR-E343, PCM continuity and actual xrun counts: UNRUN/UNKNOWN.

## Device comparison and capture

Compare this APK against e75cee0 on the same Redmi Pad, SF2/style, volume and routing. Main A–D with Auto Fill on/off; running Intro→Main; Main→Ending from a multi-bar Main; repeated Main during active Fill; C–F–G–C and Major–Minor–Seventh; tempo120→60,60→120,120→90. Observe missing parts, unexpected held notes, timing and crackle; host results do not certify their absence on Android. PSR-E343/MIDI OUT testing is optional and separate from internal audio.

For one small report: Main stable → ARM CHORD CAPTURE → reproduce one chord/section glitch → END CAPTURE → SAVE CHORD. F12_LOGGER reports formatter/monitor cost, F12_STYLE dispatch/lifecycle/setup, F12_NATIVE synth/render/callback timing. Do not use large AllLog or equate a callback gap with xrun. Clock pairing/drop/truncation status remains authoritative; no inferred audio PASS from incomplete rows.

## Rollback

Input branch/source is untouched. Logger-only change is d285ba2dcc8a843cd3fc718528cffffb5b42552b; admission repair4f061e31fe3518767114fa3dcaf24d9ade00047c; visibility1e2c0aeb29db415c2133d3e5650c4fcde353dbc2; scope restrictionc75122daf5d1077db55d50d1c261144fde59af4a. Logger can be reverted independently; unwind the three admission commits together for scheduling rollback. Future debug workflow must pin whichever source is chosen. Never reset protected READY, regenerate historical golden/hash or call experimental build a READY PASS.
