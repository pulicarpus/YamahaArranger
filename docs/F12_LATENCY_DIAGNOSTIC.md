# F12 latency isolation — diagnostic APK, no musical fix

Baseline HEAD `a3dc588b3aea2622a020ccbc95800f9cfbc6723e`; APK #811 source `a54a894a61034003facb080876970e67c407046a`. The new source profile pins every tracked baseline file/hash and six exact reversible edits (five production files plus the guard adapter). All 125 production paths remain; no new production path. F04 profile, historical fixtures/goldens, terminal duration, note ownership, CASM, Stage 3, routing and workflow remain unchanged.

## Device evidence

Both uploaded files were read directly. SHA-256 and exported scheduled rows are preserved in `f12_build811_device_evidence.json`. Style: LoveSong3.S687(1).prs. Eight exported chord changes. Eleven exported lateness observations include 77,618 / 84,878 / 92,581 / 99,816 us at the same MainA tick 84477: three FORWARD decisions and one DROP_NO_POLICY_WITH_CHORD. These decisions are recorded at wallMs 1791553152688–2710, before recorded F→G chord marker 720 at 1791553152715. `contextId=720` attributes the retrospective window; it does not prove causality. Setter entry may precede that marker because activeNoteCount acquires the monitor before legacy CHORD_CHANGE recording. A new setter-entry marker therefore precedes all that work. Native NOTE_PRE/POST pairs for 714–716 share their respective millisecond timestamps, but begin after acquiring the native mutex; this does not exclude prior mutex waiting.

Export omitted 237 Kotlin rows and 146 native rows. PARTS has starts=started=1, stops=0; callbacks=synthCallbacks=returned=8,430, frames=3,776,640; decode calls=success=8,430, failed=noStream=shortReads=0. These prove cumulative progress, not uninterrupted output during the brief gap. Root cause remains UNKNOWN.

## Observer contract

Use existing CAPTURE CHORD / END CAPTURE. Samples are accepted for at most 60 seconds per arm; END disables the timing paths. Administration and formatting are outside the audio callback. Native realtime observer performs no logging, formatting, IO, SF2 scan, heap buffer allocation, new mutex, or audio restart. Counters and slots are lock-free 32-bit atomics, statically asserted. Lifecycle publication/refcounts use sequential consistency; rows publish only when complete. Native stop/rearm drains only short recorder writes, never tokens waiting for the synth mutex. Tokens older than the new arm are rejected.

Clock: Kotlin System.nanoTime and native steady_clock, exported as microseconds modulo 2^32. Compare unsigned deltas; a 60-second window spans at most one wrap. Do not assume cross-domain origins: chordId and the Kotlin JNI mark bracket bound offset/uncertainty. Native chord marker lies inside that bracket. Timing IDs use the low 32 bits of legacy IDs.

Each ring retains the most recent 64 qualifying rows, never grows, and reports overwritten/dropped rows. Aggregates cover every accepted observation. Ring-slot collisions are dropped. Missing rows cannot disprove a stall. END then STOP before export stabilizes writers. Timing rows precede legacy chord rows; existing SAVE CHORD 48 KiB bound remains. Some legacy rows can be omitted; inspect export counters.

SynthLock retains the same std::lock_guard, mutex and scope; Kotlin inline wrapper retains the same synchronized monitor, block and return behavior. No musical arguments or MIDI ordering change. Hold measurements exclude their final publication overhead. Reentrant monitor samples overlap and must not be summed as exclusive CPU. No scheduler, tempo, F03/F04, preset policy, resolver, SF2, sample or balance change. Disabled native probes do flag reads without new clock calls.

| Domain/kind | Meaning |
|---|---|
| STYLE 0 | noteLifecycleLock wait and hold, including snapshots/reentrant calls |
| STYLE 1 | applyVoicesFromCasm duration |
| STYLE 2 / 6 | style lateness before / after lifecycle monitor; absolute tick retained |
| STYLE 3 / 4 | chord / actual section-setup marker; section uses Java String.hashCode |
| STYLE 5 | JNI native chord-mark bracket; holdUs is whole call duration |
| STYLE 7 | chord setter entry before snapshot/retarget; tick field contains requested root or -1 |
| NATIVE 0 | expression/release/master gain/all-notes-off/chord-mark mutex |
| NATIVE 1 / 2 / 3 / 4 / 5 | NOTE_ON / NOTE_OFF / preset / mixer / render mutex wait and hold |
| NATIVE 6 | callback interval: waitUs=previous frame-period budget, holdUs=actual entry gap |
| NATIVE 7 / 8 | native chord marker / whole callback duration |

STYLE rows: wait >=2ms or hold/lateness >=10ms; markers always. Native mutex/duration rows: >=2ms. Callback-gap rows: entry interval exceeds previous frames/sampleRate budget by >2ms. Android batching can change cadence; this is a candidate anomaly, not underrun certification. `order` is per-domain only.

## One Redmi Pad capture

1. Same style/SF2, tempo, Auto Fill and chord input method as #811; media volume positive. Play briefly without CAPTURE to confirm the ordinary symptom/control.
2. CAPTURE CHORD. Main A/C for ~5 seconds, C→F→G, then one Main/Fill transition with one chord change while Fill sounds. End within 30–40 seconds; avoid rapid repeated panel presses.
3. Immediately after the reproduced gap: END CAPTURE → STOP → SAVE CHORD (SMALL). Send that one TXT and the action at the worst gap. PARTS (SMALL) is optional supplementary cumulative evidence. No export during playback or AllLog/String dumps.
4. Report if CAPTURE itself worsens the symptom. Byte parity does not certify zero device timing overhead.

Interpretation: STYLE 2 low but STYLE 6/monitor wait high locates monitor contention. Both lateness values high with low waits points toward scheduling/pre-dispatch work. Native render wait plus a long synth-control hold indicates shared-mutex blocking. Long render hold/callback duration with low wait points inside render/decode. Callback gap with short render/waits points toward output delivery/scheduling and needs corroboration. Section apply plus preset hold isolates activation cost. Correlate OFF/control and PCM evidence before attributing gaps to mute/release. Accepted NOTE_ON is not audible PCM proof; multiple mechanisms can coexist.

## Gates and STOP

Required: old F04 differential (83 baseline/90 candidate tests), source guards, S1–S8/P0, headroom, and new timing tests. New tests cover OFF mode, known timing, bounded wrap/overwrite/expiry/stale arm, original native mutex semantics, every overlay mutation, actual sequencer capture OFF/ON MIDI/note/controller parity and real Linux synthetic PCM/MIDI OFF/ON byte parity. Synthetic timing/audio does not certify Redmi Pad/Android/Yamaha. Original 503 corpus rerun remains BLOCKED.

Rollback is #811 / `a3dc588`. After diagnostic APK success STOP and await device capture; no automatic audio/musical patch. Local and CI results follow below.

## Local completed gates

- F12 native controls/source overlay: PASS, including 10,000 concurrent producer attempts during 200 ARM/END cycles. Every modified path mutation rejected.
- F12 Kotlin/actual sequencer/native wrapper: 4 tests PASS, including 32-bit timestamp wrap and 64-row export boundary. The boundary test caught and corrected an observer export loop error before publication.
- F04 source tests: 5 PASS. Full voice/native/mock/source regression runner: PASS.
- F04 differential: 83 baseline / 90 candidate tests PASS, 20 full captures and 1,046 reduced overlap runs. Exactly the existing F04 deltas; no additional event delta or golden changes.
- Genuine depth-1 checkout with six actual official Android SDK files: SDK controls 10 PASS, F12 source/native controls PASS, candidate pipeline 90 PASS.
- S6/S7/S8/P0 host integration: 13 PASS; isolated P0: 24 PASS; S1 fail-closed harness: 7 PASS.
- Real Linux multi-SF2 synthetic synth: #811 baseline vs diagnostic OFF vs diagnostic ON PCM and MIDI BYTE_IDENTICAL (70 notes / 250 MIDI events per run). Native callback-gap controls are synthetic, not a device observation.
- Existing real Linux headroom/response baseline parity: PASS. Device audio/latency improvement remains UNKNOWN.

Native timing rows label the latest chord marker when published; it can advance while a call waits. Use interval overlap and the JNI bracket, not that context label alone, to assign causality. Callback-gap intervals precede atUs; mutex/callback-duration intervals start at atUs. Source and helper tests leave UNKNOWN/BLOCKED classifications unchanged.

Android CI: pending the diagnostic commit/push. Local Android SDK is unavailable; no local APK claim.
