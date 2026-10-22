# P1 experiment — ordered note I/O outside `noteLifecycleLock`

Branch `experiment/p1-ordered-note-io`, baseline **`16c673a`** (production baseline: Master Transpose, Pending Transpose, RIGHT Octave, ACMP/LEFT, Registration, Metronome; CI green + APK). Based on `docs/CHORD_CHANGE_STUTTER_ROOT_CAUSE.md` @ `75ef33b`, item **P1 only** (no P2 Main-thread move, no P3 native batching). CASM, tempo clock, BASSMIDI/Oboe/C++, UI: untouched (`tools/test_p1_guard.py`). Not merged; main untouched.
All numbers are HOST evidence (x86 JVM, production chain, fake USB ports + recording JNI bridge, MODEL per-call costs). **Nothing here shows that the audio disturbance on the Redmi Pad is fixed.**

## 1. Problem (restated, proven before the patch)
At baseline the retarget (Main thread) and every Style dispatch performed engine JNI + MIDI OUT + logging inside `synchronized(noteLifecycleLock)`. One blocked call therefore held the Style scheduler. Deterministic reproduction written and run **on the baseline before any patch** (`ChordChangeStutterTest.deterministicHundredMsJniBlockAlignedWithADueHat`): chord pressed 104 ms after a Style hi-hat so that the Main-thread retarget starts ~2 ms before the next hat is due; the first native call of the retarget sleeps 100 ms (stands in for a synth-mutex/USB stall).
| | max Style (hi-hat) lateness | hats > 20 ms |
|---|---|---|
| baseline `16c673a` (two runs) | **112.1 ms**, 103.9 ms | 3, 6 of 45 |
| P1 (three runs) | 2.1 ms, 1.6 ms, 5.0 ms | 0 of 45 |
(Earlier correction: the stall/lateness numbers in the 75ef33b report used a time-rounded grid slot that folds a delay larger than half a period into the next slot, so it under-reported stalls > 62 ms; the harness now indexes slots by hat sequence. The earlier conclusions about the no-stall tails are unaffected, they never reached 62 ms.)

## 2. Design — ordered dispatch, not just "JNI outside the lock"
* The monitor keeps guarding **owner identity + the ledger** (`activeTransposedNotes`, STALE_OWNER checks, CASM decisions). Under it each NOTE_OFF/NOTE_ON (engine + MIDI OUT, one command) is only **staged**: it gets a **ticket on its (channel,pitch) key** in ledger order and captures its parameters by value. Logs are staged unkeyed.
* At the **outermost `lifecycle{}` exit** (monitor released; nested lifecycle calls only deepen a per-thread counter) the same thread executes its commands in staging order. A command first waits until all earlier tickets of its key have finished, then runs under the **read side of a gate**, then retires its ticket (also when it throws or is skipped). Commands of different keys run concurrently, so a blocked call no longer holds the scheduler; only a later command on the very same pitch/channel waits (BASS NOTE_OFF pairs by channel+pitch, so that is the only order that matters; measured longest such wait ≤ 10 µs).
* **Order guarantee**: per key, execution order == ledger order; per thread, program order (OFF before ON of one retarget; engine before MIDI OUT inside a command). Retarget OFF(old)/ON(new) replacement keeps its pair order; a scheduler release of the same owner after a retarget gets a later ticket on the new pitch, so it cannot overtake the retarget's ON.
* **Deadlock freedom**: tickets on every key follow monitor acquisition order, so a waiter only waits for commands of strictly earlier monitor sections; nobody holds the monitor or the gate while waiting for a turn; the gate write side (STOP) is taken before the monitor and never while a turn is awaited. Proven by a 6-thread × 4000-round randomized test (60 s timeout) plus the chain stress.
* **STOP/START cancellation + session identity**: `stop()` → `OrderedNoteIo.stopSession`: take the gate write lock (waits for in-flight commands), under the monitor bump the **session epoch** and clear the ledger, then the global `allNotesOff`/CC123. Commands staged before STOP carry the old epoch and are skipped (tickets retired); commands staged after it belong to the new session. Additionally the scheduler checks, **inside the monitor, that its own coroutine job is still active** — STOP cancels the job before taking the monitor, so a dispatch that enters afterwards belongs to a dead session and is dropped.
* **Bug found by the tests and fixed by this**: at baseline, STOP issued while a retarget call is blocked lets the cancelled scheduler dispatch a note after STOP's `allNotesOff` (3 NOTE_ON after STOP returned, 2 stuck notes). P1 → 0 and 0.

## 3. Race audit (retarget × scheduler × ACMP toggle × Fill/Main × STOP)
| pair | analysis | evidence |
|---|---|---|
| retarget vs scheduler dispatch of a new note | both stage under the monitor; I/O concurrent; same pitch ordered by ticket | `P1ChainTest` random stress, `OrderedNoteIoUnitTest` |
| retarget vs scheduler release of the same owner | STALE_OWNER re-check under the monitor unchanged; OFF ticket after the retarget's ON | prefix check "never more OFFs than ONs" per (ch,note) on engine and MIDI OUT |
| retarget vs ACMP OFF (`currentChord=null` → `releaseActive`) | ledger removal under monitor; OFF staged later than the retarget's commands | `acmpToggleAndFillDuringBlockedRetargetKeepOwnership` |
| retarget vs Fill/Main outgoing release (scheduler thread) | same mechanism | same test + random stress (Main A/B/C switching) |
| retarget vs STOP | gate + epoch; in-flight finishes, rest skipped; 150 ms blocked call during STOP | `stopWhileARetargetCallIsBlockedLeavesNothingAndSkipsTheStaleRest` |
| STOP vs scheduler already past its wait | job-active check inside the monitor | same test (baseline FAILS it) |
| START right after STOP | skipped old tickets are retired, new session tickets proceed | same test (new session plays) |
| exception in a command | tickets retired in `finally`, first exception re-thrown after the batch | `aFailingCommandRetiresItsTicket` |
Not changed on purpose: keyboard (RIGHT/LEFT) path and `keyboardLock`; program/CC/voice-apply on the scheduler thread; `setVoicePin`/audition JNI under the monitor (not note path); the STOP/START global kill of held keyboard notes (baseline behaviour, F7).

## 4. Changed files
* `app/.../yamahaarranger/arranger/StyleSequencer.kt` only (production): `lifecycle{}` stages-then-flushes, `chordOff` (engine+MIDI OUT as one ordered command), retarget `updateHeldPitch`, dispatch NOTE_ON, `stop()`, `dlog` (deferred logs), job-active session check, new internal class `OrderedNoteIo` (in the same file so every JVM harness source list keeps working).
* Tests/harness: `OrderedNoteIoUnitTest` (5), `P1ChainTest` (3), `ChordChangeStutterTest` (+ deterministic repro, P1 assertions via `CCS_EXPECT`), `RtRig` (one-shot stall hook), `tools/test_rtdiag.py`, `tools/test_p1_guard.py`, workflows (`style-voice-editor.yml` branch wiring, `rtdiag.yml`), this report and the previous report.
* The guard `test_p1_guard.py` limits removed StyleSequencer lines to engine/MIDI/log/lifecycle/STOP lines and forbids CASM/tempo-clock/voice/scheduler-wait vocabulary in added lines. No guard was weakened.

## 5. Baseline vs P1, identical load and fixtures (HOST)
Synthetic Style, 120 BPM, hat every 16th, native/USB model 300/150 µs, MIDI OUT on unless noted; hat lateness = actual − tick grid; `lc wait` = longest wait of an external contender for the Style monitor.
| scenario | baseline hat p50/p95/p99/max (ms) | P1 hat p50/p95/p99/max (ms) | baseline lc wait max | P1 lc wait max | native calls under the monitor (baseline → P1) |
|---|---|---|---|---|---|
| no chord change | 0.0/0.50/1.59/2.49 | 0.0/0.73/1.57/3.40 | 6.7 ms | 0.07 ms | 226 → 0 |
| C→F→G→C 500 ms | 0.0/0.37/0.77/1.59 | 0.0/0.74/0.93/4.43 | 15.0 ms | 0.97 ms | 442 → 0 |
| 250 ms | 0.0/0.70/1.53/1.61 | 0.0/0.84/2.30/2.45 | 13.7 ms | 0.27 ms | 646 → 0 |
| 125 ms | 0.0/0.32/0.44/0.46 | 0.0/0.50/0.68/3.11 | 18.3 ms | 1.17 ms | 884 → 0 |
| 80 ms | -0.07/0.37/0.90/3.23 | -0.02/0.48/0.50/0.52 | 13.0 ms | 1.06 ms | 778 → 0 |
| model 700/300, 125 ms | 0.0/0.49/0.58/0.63 | 0.0/0.26/0.30/1.60 | 33.9 ms | 0.05 ms | 695 → 0 |
| ROSITA 125 ms | 0.0/1.54/1.90/2.63 | 0.0/2.52/3.41/3.93 | 14.0 ms | 2.4 ms | 642 → 0 |
| ROSITA 250 ms, RIGHT1/2/3+LEFT | 0.0/1.41/2.53/3.11 | -0.03/1.40/1.95/2.49 | 8.0 ms | 10.0 ms* | 485 → 0 |
| Main↔Fill switching 250 ms | 0.0/0.45/1.17/7.47 | 0.0/1.11/2.36/6.82 | 13.8 ms | 1.9 ms | 534 → 0 |
| pending transpose each change | 0.0/0.39/0.59/1.15 | 0.0/0.49/1.20/2.09 | 15.9 ms | 1.1 ms | 490 → 0 |
(* the one outlier of the P1 contender, probably the keyboard-thread JNI/USB work or host scheduling; the Style monitor itself is no longer held across I/O.)
Repeated trials, 12 × ~9 s, 732 hats each, no contender: control (no change) baseline p99 3.05 / **max 211.8 ms** (host outlier) vs P1 p99 3.32 / max 7.4; chords 125 ms baseline p99 2.53 / max 52.9 vs P1 p99 1.83 / **max 3.9 ms**. The tail outliers appear at baseline even without chord changes → host noise, so the **deterministic reproduction (§1), not these statistical tails, is the proof**; the statistical runs show **no regression** (P1 not worse: p50 0.0 / p95 0.5–0.8 ms in both).
Workload unchanged: native calls 514/790/1124/1018 (identical), peak note instances 152/185/243/304 (identical), NOTE_ON/OFF counts identical, MIDI OUT counts identical. Longest same-key turn wait inside P1: ≤ 10 µs. Allocation: one small array per flush.
Note lifecycle: BASS_MIDI_NOTEOFF1 oracle: stuck 0 / orphan 0 in every scenario; per-key prefix check passes on engine and MIDI OUT for random stress with 3 seeds × 2 START/STOP sessions (ACMP, LEFT, transpose, Main/Fill, RIGHT notes interleaved).

## 6. Regression suites on P1 (local)
rtdiag default set 52/52 (stress 17, metronome follow, Transpose/ACMP/LEFT contract 18, P1 chain 3, ordered-IO unit 5, …) · ChordChangeStutter harness 15/15 incl. P1 assertions · CPV JVM 236/236 (Musical Transpose, ROSITA C→F/C→G, source guards) · Registration recall 106/106 · LIVE/REG 86/86 · `test_p1_guard` PASS. Native/C++ suites are unaffected (no native change) and run in CI.

## 7. Residual risks
1. HOST only. The synth mutex, USB service latency, thread priorities, GC and the Oboe callback are not modelled; the Redmi Pad outcome is unknown. If the real stall is the **render holding the synth mutex**, every native call blocks regardless of locks — P1 then helps only by not serialising unrelated work behind it; P3/other work would be needed.
2. A blocked call still delays later commands on the **same pitch/channel** and (inside a thread) the commands staged after it by that thread, e.g. the rest of one retarget.
3. Command execution moved from inside to just after the monitor: any code that assumed "I/O done while the monitor is held" (none found in the sequencer; the keyboard path is unchanged) would be affected.
4. STOP now waits for in-flight commands (bounded by the longest blocked call) before the global kill.
5. The baseline behaviour "STOP/START cuts held RIGHT/LEFT keys" remains.
6. Per-flush array allocation and a `ThreadLocal` lookup per lifecycle call (µs-level, not measured on ARM).

## 8. Decision
**GO for a device A/B experiment only** (the race, STOP/START, ownership and regression suites passed; no test failed). **Not** a claim that the audio issue is fixed, and **no merge** without your approval. Final CI/APK identity is appended below once the workflow is green.

## 9. A/B test on the Redmi Pad (PSR-E343)
APK A = current baseline APK (`16c673a`, run 39715673588, artifact `YamahaArranger-STYLEVOICE-16c673ad…`); APK B = this experiment (artifact name carries the commit SHA, see below). Same Style, tempo, SF2 set, MIDI OUT state, chord mode.
1. Style playing, ACMP ON, Metronome FOLLOW ON; change chords C→F→G→C about every 250 ms then 125 ms for 30 s; MIDI OUT off, then on. Note the wall-clock time of every audible hold. LATENCY panel: RESET STATS first, SAVE REPORT after (compare `styleEventWakeLatenessBeforeLifecycle`, `…DispatchLatenessAfterLifecycle`, `chordApplyDuration`, synth-mutex waits, XRUN).
2. Drum-only (ACMP OFF) and chord held without changes as controls.
3. STOP/START while holding a chord and changing it; press STOP in the middle of fast chord changes: no stuck note, no note after STOP.
4. RIGHT1/2/3 + LEFT playing during fast chord changes; Main/Fill switching; Pending Transpose onset.
5. Repeat A, B, A, B order to cancel drift. Report only what is heard and what the saved reports show.
