# F04 — rejected melodic CASM lanes must not become phantom rhythm

Baseline / rollback source: `0b8536a018eb2661bd6e06e8864a638b31d57226` on
`fix/mix-percussion-fidelity-784`. Local/remote HEAD matched and working tree was
clean before implementation. Scope follows the approved
[production recovery roadmap](YAMAHAARRANGER_PRODUCTION_RECOVERY_ROADMAP.md).

## Production change

Only `app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt`
changes. `hasOnlyMelodicDeclarations` requires a nonempty candidate list, every
candidate source matching the event source, and every destination in10..15.
When chord is active and NOTE_ON has no selected policy, that classification
removes the historical source8/9 rhythm exemption. The existing
`DROP_NO_POLICY_WITH_CHORD` path reports rejection before any replacement/ON.

Empty, mixed, unknown/out-of-range or mismatched-source declarations retain the
old rhythm exemption. Applicable rhythm policies and no-chord behavior retain
the old path. No selectPolicy/policyScore/mask/NTR/NTT/RTR, F03 ownership, F02
ordering, F12 duration, native BASS/SF2/resolver/mixer, routing serializer,
workflow/build flags or Stage3 changes are included. Channels are zero-based.

## Independent baseline/candidate gates

`tests/fixtures/f04_source_profile.json` pins all380 original tracked file hashes
and exact old/new hashes plus reversible edits for16 approved production/test/
harness files. Reconstructed old hashes were checked against actual `git show`
from the rollback commit. Only one of those paths is production. All53 old
fixture/resource/golden files remain physically byte-identical. Historical
source/evidence assertions remain; their approved-profile recovery verifies
physical candidate bytes first, then recovers old bytes for the historical
comparison. It is not a claim that playback is unchanged.

`tools/test_f04_source_guard.py` tests physical pins, exact baseline recovery,
mutated source/harness, broadened destination scope and removed source matching.
New candidate expectations live under `app/src/test/resources/f04/`; original
S1 digests, GOLDEN table, S5 trace/digest, ledger and all prior fixtures remain.
The selected test profile follows which physical sequencer was compiled.

`tools/test_f04_differential.py` compiles and executes the pinned old sequencer
and the actual current sequencer independently. Golden expected deltas are
constructed from the executed baseline and the saved native fixture's declared
source/destination pairs, not recorded from the candidate. It allows removing
only the matching AUDIO ON and immediately paired MIDI ON that wrongly fell
back to source8/9 despite entirely melodic declarations. Destination, raw key,
velocity and tick are checked. All retained canonical musical calls must match
exactly, including PC/mixer/controller/ON/OFF arguments and ordering. Global
Mockito counters, diagnostic IDs and wall-clock latency are excluded as in S1.
Additional/remapped OFF, missing retained calls and modified calls cannot match.

The same gate covers1046 S5 reduced replay traces. Only cases386/387,
`Pop&Rock/6-8Rock.S115.bcs/FillCC`, roots0/5, are allowed to lose two phantom
source9/key60 ONs each and change their FORWARD diagnostic to the existing DROP
stage. The source9→melodic declarations are checked from the frozen fixture;
OFF and owner lines are untouched. The filename is not proof of encoded6/8.

CI discovers `F04DifferentialGateTest` through the existing JVM task. It invokes
the strict baseline/candidate runner and fails on non-F04 delta. No workflow or
Gradle modification is needed. `F04RoutingRegressionTest` uses actual sequencer,
transformer and MIDI serializer with mocked audio/Android port boundaries.

## Verified results before push

| Gate | Result / limit |
|---|---|
| Baseline pipeline | PASS83 JVM tests; all20 historical capture digests exact |
| Candidate pipeline | PASS90 JVM tests, including7 F04 controls |
| Golden differential | PASS20 runs; exactly4 changed,16 unchanged |
| BaroqueAir1 MainD, C and F major |44 phantom ON removed per run, per output; retained melodic ON64/72/48/8 on destinations10/11/12/15 |
| Unplugged2 MainD, C and F major |256 phantom ON removed per run, per output; valid rhythm128/105 on8/9, melodic48/672/144 on10/11/14 unchanged |
| LoveSong3 MainD, C/F | Canonical trace unchanged,483 ON per run; existing whole-section Love Song presence regressions remain |
| Total golden replay removals |600 internal ON +600 MIDI ON across C/F (replays, not unique style events) |
| S5 reduced differential |1046 runs,1042 unchanged;8 internal ON +8 MIDI ON removed in4 runs; all retained OFF/owner state exact |
| Routing controls | Pure melodic reject, valid rhythm, empty/mixed/unknown/mismatched declarations, no chord, note-range/mask gates, melodic ON/OFF across synthetic section names PASS |
| MIDI OUT | Actual serializer bytes tested with mocked port; no receiver/audio claim |
| S1 fail-closed | PASS7 tests |
| Host/native resolver/metadata/source/Stage3 guards | PASS `tools/test_voice_resolver.py`, including5 new F04 negative controls |
| S6/S7/S8/P0 host wrappers | PASS13 tests; native/Linux evidence retains its original bounds |
| P0 bookkeeping | PASS24; never connected to production |
| P1 Linux native | PASS132 windows; Yamaha pairing BLOCKED, Android runtime UNRUN |
| Real-BASS percussion/role PCM and balance | PASS existing suites, including240 controlled response combinations; synthetic PCM, not device timbre |
| Headroom regression | PASS real-BASS baseline791/OFF/ON PCM/MIDI byte parity and pre/post scalar/FX/polyphony controls; device peak root remains UNKNOWN |
| Original503 corpus | BLOCKED; original ZIP/hash unavailable; no fresh503/503 claim |
| Android/device audio | Local Android SDK absent; existing GitHub CI performs JVM/APK/both ABI gates. User listening remains UNRUN |

Initial candidate regression surfaced the four S5 F04-affected runs and a new
test that captured only style-specific OFF rather than the existing legacy OFF
method. The S5 delta was independently projected and checked as above, and the
new test now captures both actual OFF boundary methods. Neither production
ownership nor historical expected files were changed to address those findings.

## CI and rollback

Implementation commit/build/artifact are reported after pushing the checked
patch and observing the existing workflow. Pending CI is not SUCCESS. The
existing APK Stage3 symbol checks for arm64-v8a and armeabi-v7a remain mandatory.

Rollback is the exact scoped implementation commit back to the pinned baseline,
with its approved-profile test/harness changes reverted together. Do not revert
the historical presence/mixer/percussion corrections or enable Stage3. Stop if
any non-F04 differential/guard/CI failure occurs; do not patch F02/F03/F12 or
native audio to make this build pass.

## Device acceptance still required

Use the same style/font hashes, mixer/trim/master and controller configuration
for baseline and candidate. Check Love Song and the two F04 styles, warm Main
A–D, Main D→Fill B→Fill A→Main A, Intro→Main, Ending→Stop, ACMP ON/OFF,
LEFT/RIGHT/sustain and optional MIDI OUT, with at least two minutes playback.
Keep actual byte/report/PCM evidence separate from listening judgment.

Existing F03 overlap and F05 scheduled rhythm/no-policy OFF limitations remain.
The valid rhythm OFF regression preserves the historical one-shot contract;
it does not claim scheduled rhythm OFFs have been added or universal absence
of stuck notes. Melodic test owners clear and retained OFFs are exact. Native
voice identity, real controller disconnect behavior, timing, clipping and Yamaha
sound are not certified by logical traces or green CI.

STOP after the successful existing APK build. Wait for user audio testing;
no F02/F12/F03 follow-up or additional patch is authorized by this work.
