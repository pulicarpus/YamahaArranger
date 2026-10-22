# Chord-change stutter — root-cause investigation (AUDIT ONLY)

Branch `diag/chord-change-stutter`, baseline `feature/transpose-octave-acmp-left` @ `16c673a` (Master Transpose, Pending Transpose, RIGHT Octave, ACMP/LEFT independence, Drum-only, Registration, Metronome all present; CI run 39715673588 SUCCESS + APK). Comparison baseline for "before the new features": `034855a`.
**No production file changed** (`tools/test_chord_stutter_guard.py`: only `tests/rtdiag/*`, `tools/test_rtdiag.py`, `.github/workflows/rtdiag.yml`, this document). No APK built.

Evidence classes: **CODE** (read in source), **HOST** (production Kotlin chain on x86 JVM with fake USB ports and a recording JNI bridge — says nothing about Redmi Pad speed), **MODEL** (per-call native/USB cost that *we chose*, spin-wait; never a measurement), **DEVICE-OLD** (numbers from earlier device reports), **NOT MEASURED**. Verdict labels: Confirmed / Strong evidence / Hypothesis / Not measured.

## 1. Symptom
Style normally steady; at a chord change, sometimes (intermittently) the whole accompaniment seems to hold for a moment, like lost tempo continuity, then continues. Not a late bass/chord entry, not a restart from beat 1. ACMP OFF / drum-only: not reported. No metronome test yet. PSR-E343 unavailable.

## 2. Pipeline and threads (CODE)
```
PSR-E343 -USB-> Android MidiService -> MidiOutputPort dispatch thread  [T-MIDI]
  MidiNoteReceiver.onSend -> parser -> DebugLog x3-4/event -> callbacks on T-MIDI
  ArrangerBrain.onKeyboardNoteOn          synchronized(keyboardLock)
     RIGHT/LEFT: engine JNI + MIDI OUT USB write            [T-MIDI, inside keyboardLock]
     ACMP: ChordDetector.noteOn -> onChordChanged(onset)    [T-MIDI, inside keyboardLock]
           pending transpose commit (same transaction)      [T-MIDI]
           scope.launch{ delay(15 ms); synchronized(keyboardLock){state}; applyChordNow }   scope = viewModelScope = Main  [T-MAIN]
  applyChordNow -> sequencer.currentChord = target.transposedBy(activeTranspose)           [T-MAIN]
     -> handleChordChange -> for EVERY active style note: retargetActive
          lifecycle{ synchronized(noteLifecycleLock) { CASM selectPolicy; chordOff (JNI NOTE_OFF); MIDI OUT OFF; JNI NOTE_ON; MIDI OUT ON; DebugLog x3 } }   [T-MAIN]
Style scheduler coroutine (Dispatchers.Default pool)  [T-SCHED]
  for each event: waitForNextBarOrEvent (coroutine timeout, absolute deadline from TempoClock)
                  lifecycle{ CASM transform; JNI noteOnStyleChannel; MIDI OUT noteOn }     (same noteLifecycleLock)
Metronome FOLLOW planner thread  [T-METRO]  reads Style clock only (ArrangerBrainClock -> sequencer.tickToNanos); pushes future click times to native
BassMidiPlayer: ONE std::mutex for noteOn/noteOff/render/diagnostics;  Oboe callback [T-AUDIO] -> BassMidiPlayer::render (same mutex)
```
Locks (acquisition order seen in code; no nesting between the first two): `keyboardLock` (Brain) · `noteLifecycleLock` (Sequencer) · `musicalClockLock` (short, clock reads) · `MidiMixerOutput` `@Synchronized` (every MIDI OUT note) · `DebugLog.logLock` · native synth `mutex_`.
Edges: keyboardLock → engine JNI → synth mutex; keyboardLock → MidiMixerOutput → `MidiInputPort.send`; lifecycle → engine JNI → synth mutex; lifecycle → MidiMixerOutput → `MidiInputPort.send`; DebugLog from inside all of them. No lock inversion found: `applyChordNow` takes `keyboardLock` only for a few field writes and releases it before touching the sequencer; `setAcmpEnabled`/STOP commit call the sequencer outside `keyboardLock`.
Blocking operations inside monitors (CODE, proved by `Thread.holdsLock` in the rig): JNI native note calls (synth mutex wait possible) and `MidiInputPort.send` (blocking socket write). In the host runs 76–80 % of all native calls are made while holding `noteLifecycleLock` and 42–62 % of them on the Main thread (retarget).

## 3. What was built
`tests/rtdiag/ChordChangeStutterTest.kt` (+ small hooks in `RtRig`: optional real Style, one-shot blocking native call). Production chain, nothing mocked in between. Style timeline is observed from the OUTSIDE (instants of drum NOTE_ON at the JNI boundary, fitted against the Style's own tick grid; ROSITA.STY MainA via the unmodified C++ parser) so the observer adds nothing to the realtime path. A contender thread samples `synchronized(noteLifecycleLock)` / `keyboardLock` acquisition every ~0.3 ms (host only). Re-run: `python3 tools/test_rtdiag.py --out DIR --rosita --only com.yourapp.rtdiag.ChordChangeStutterTest` (env `CCS_ONLY=testName` to select), rows `CCS scenario=...`.
Not measured (needs device): Oboe callback duration / xrun, real synth-mutex wait, USB write latency, GC. BASSMIDI cost curves exist only from the older x86 job (`tools/test_rtdiag_real_bass.py`, runs in the same workflow).

## 4. Results — Style continuity under chord changes (HOST, synthetic Style, 120 BPM, hi-hat every 16th; lateness = actual − tick grid)
Model costs native/USB per call: 300/150 µs unless stated. MIDI OUT on unless stated.

| scenario | hats | p50 | p95 | p99 | max (ms) | max within 250 ms after a change | gaps/bursts |
|---|---|---|---|---|---|---|---|
| control: no chord change | 53 | 0.0 | 0.85 | 1.31 | 2.08 | 0.45 | 0/0 |
| C→F→G→C every 500 ms | – | 0.0 | 0.38 | 1.12 | 2.43 | 0.75 | 0/0 |
| every 250 ms | – | 0.0 | 0.50 | 1.89 | 5.76 | 5.76 | 0/0 |
| every 125 ms | 45 | 0.0 | 0.40 | 0.50 | 0.52 | 0.52 | 0/0 |
| every 80 ms | – | -0.01 | 1.85 | 1.91 | 3.31 | 3.31 | 0/0 |
| C→Am→F→G every 125 ms | – | 0.0 | 0.41 | 0.82 | 0.84 | 0.82 | 0/0 |
| model 300/150, MIDI OUT **off**, 125 ms | – | 0.0 | 0.42 | 0.47 | 1.03 | 1.03 | 0/0 |
| model 300/150, MIDI OUT **on**, 125 ms | – | 0.0 | 0.61 | 1.20 | 1.66 | 1.66 | 0/0 |
| model 700/300, MIDI OUT on, 125 ms | 35 | 0.0 | 0.44 | 0.72 | 0.86 | 0.86 | 0/0 |
| change on the bar line, every bar (300/150) | – | 0.0 | 0.97 | 2.12 | 2.50 | 2.25 | 0/0 |
| change mid-bar, every bar (300/150) | – | 0.0 | 0.65 | 1.94 | 13.09 | 13.09 | 0/0 |
| spread 8 ms between fingers, 250 ms | – | 0.0 | 0.53 | 1.34 | 1.79 | 1.79 | 0/0 |
| press new chord before releasing old, 250 ms | – | 0.0 | 0.38 | 1.30 | 2.37 | 2.37 | 0/0 |
| RIGHT 1/2/3 playing + ACMP + LEFT, 250 ms | – | 0.0 | 0.49 | 0.79 | 3.70 | 3.70 | 0/0 |
| Main↔Fill switching every 2nd change, 250 ms | – | 0.0 | 0.51 | 0.64 | 7.51 | 7.51 | 0/0 |
| pending transpose applied at every onset, 500 ms | – | 0.0 | 0.46 | 0.95 | 3.07 | 3.07 | 0/0 |
Repeated trials without the contender (6 × ~9 s each, 366 hats): control p99 4.97 / max 10.2 ms; chords 500 ms p99 2.0 / max 5.0; chords 125 ms p99 1.5 / max 5.4; chords 125 ms at 700/300 µs p99 2.7 / max 8.5. The tails with chord changes are **not heavier than the control**; isolated 10–40 ms outliers appear in *both* (e.g. control host-raw max 40.7 ms on HEAD, chords-500 max 40.0 ms on the `034855a` baseline) → host/JIT/CPU noise on a shared 4-core runner, not caused by chord changes.
**Result: no missed hats, no catch-up bursts, no clock restart or shift in any scenario (Confirmed on host).** `LatencyProbe` histograms (production observer hooks) agree. The host does **not** reproduce a perceptible pause.

## 5. Lock contention (HOST + MODEL)
| scenario | `noteLifecycleLock` wait seen by an external contender: p99 / max | native calls (on Main) | peak note instances incl. 1.5 s tail |
|---|---|---|---|
| control, no chord change | 0 µs / 7.0 ms | 226 (0) | 115 |
| chords 500 ms | 0 / 15.9 ms | 514 (216) | 152 |
| chords 125 ms | 17 µs / 20.0 ms | 1124 (700) | 243 |
| chords 80 ms | 247 µs / 17.4 ms | 1018 (656) | 304 |
| model 700/300, 125 ms | 881 µs / 35.4 ms | 876 (538) | 230 |
| ROSITA 125 ms | 450 µs / 13.3 ms | 882 (394) | 212 |
| ROSITA 250 ms, RIGHT 1/2/3 + LEFT | 68 µs / 36.4 ms | 1000 (188) | 210 |
**Confirmed (CODE+HOST):** a chord change makes the Main thread hold the Style monitor for tens of ms in total (one retarget = ~9–12 native NOTE_OFF/ON pairs, each inside `lifecycle{}` with its own JNI + MIDI OUT + DebugLog); an outside thread waited up to 35 ms. The *scheduler* is not starved on the host because the retarget releases the monitor between notes, so each wait it sees is ≤ one note (≤ ~1.5 ms at the modelled costs).
`keyboardLock` waits by the contender: max 4–16 ms (MIDI-thread RIGHT/LEFT JNI + USB work inside it). `applyChordNow`'s new `keyboardLock` section (added with Pending Transpose) is a few field writes; it can only wait for such a note handler.

## 6. Deterministic reproduction: one blocked call inside the retarget (HOST)
`oneBlockedJniCallInTheMainThreadRetargetStallsTheStyleScheduler`: the first native call of the Main-thread retarget (made while holding `noteLifecycleLock`) sleeps for X ms, once per chord change, 10 changes at 500 ms.
| X | max Style lateness HEAD | max Style lateness `034855a` | control |
|---|---|---|---|
| 10 ms | 0.8 | 1.6 | 0.8 / 1.3 |
| 30 ms | 9.5 | 0.6 | |
| 60 ms | 2.9 | 2.1 | |
| 100 ms | **44.2** | **38.4** | |
The Style event is delayed by the *remainder* of the block at the instant it falls due → lateness is spread over 0…X and is visible only when a block overlaps a scheduled event (the hi-hat grid here has an event every 125 ms; a real Style has many more events per second, including bass/chord/pad starts that the ear hears as "the accompaniment holds"). **This reproduces an intermittent, tempo-continuity-like gap deterministically, and it is identical before and after the new features.** Short blocks (≤ 30 ms) are mostly absorbed. Whether the device ever blocks a call for 40–100 ms inside the retarget is **not measured**; DEVICE-OLD (`#811`, capture armed, earlier report) saw four Style events 77–99 ms late around one chord change.

## 7. Metronome as a reference
FOLLOW mode reads the *Style's own clock* (`ArrangerBrainClock` → `StyleSequencer.tickToNanos`): it is **not an independent clock**. Click intervals were exactly 500.0 ms in every run, kick-minus-click offset p50 1.7–2.1 ms, p95 1.8–2.5 ms, max 2.5 ms (no change) / 2.5–3.6 ms (chords 125 ms, also at 700/300 µs). So the *schedule* is stable and Style *dispatch* stayed within ~3.6 ms (HOST, recording sink, no native audio). A stable metronome therefore proves the tick schedule, not that notes were dispatched or rendered on time; it would only expose a stall as a growing kick-minus-click offset (which is how to use it on the device).

## 8. Polyphony / voices (HOST event stream; instances ≠ BASSMIDI voices)
| load | native NOTE_ON+OFF per 4.8 s | peak instances (1.5 s tail) |
|---|---|---|
| A control (no change) | 226 | 115 |
| B slow (500 ms) | 514 | 152 |
| C fast (125 ms) / 80 ms | 1124 / 1018 | 243 / 304 |
| D + RIGHT1/2/3 + LEFT + MIDI OUT (ROSITA 250 ms) | 1000 | 210 |
Simultaneous NOTE_ON within 5 ms: 5–11 (bar-line bursts), identical with and without chord changes. Allocation per run (all threads) 1.9 MiB control → 6.8 MiB (500 ms) → 11.7 MiB (125 ms); ~71 DebugLog lines per change; GC effect NOT MEASURED. Real SF2s average ~2 zones per note, so ~2 voices per instance (pdta, earlier report); actual voice count and ARM render cost **Not measured**.

## 9. MIDI OUT on/off, HEAD vs baseline
MIDI OUT off vs on (300/150 µs, 125 ms): hat p95 0.42 vs 0.61 ms, max 1.03 vs 1.66 ms — no relevant difference; with the 300/150 and 700/300 models no stall appears either (the spin cost is paid per call and the monitor is released between notes). HEAD vs `034855a`: `nativeCallsOnMainThread` identical (538/538, 700/700, 658/656) → **the retarget work is unchanged by Transpose/Octave/ACMP-LEFT**; HEAD makes ~25 % more native calls in total only because ACMP ON + LEFT ON now *sounds* the LEFT voice (+182 calls over 30 changes, on T-MIDI inside `keyboardLock`, not inside the Style monitor). Pending-transpose commits (`variant_pendingTransposeEachChange`) added no measurable lateness (p99 0.95, max 3.1 ms). Hat tails HEAD vs baseline are equal within the noise above.

## 10. Hypotheses checked
| Hypothesis | Verdict |
|---|---|
| Style clock stops/shifts at a chord change | **Eliminated on host** (no gaps, no bursts, grid p50 0.0 ms); NOT MEASURED on device |
| Scheduler waits for the retarget to finish | **Eliminated** structurally (per-note monitor sections); but see next |
| One long blocking call inside a retarget/dispatch monitor delays the Style by the remainder of the block | **Confirmed (deterministic, HOST/MODEL)**; magnitude on device unknown |
| Retarget runs on Main and does JNI + USB + logging inside the Style monitor | **Confirmed (CODE+HOST)** |
| Chord change causes mass NOTE_OFF/NOTE_ON | **Confirmed but bounded**: ~9–12 pairs per change (RTR 1–4 policies), rises with change rate (see §8) |
| Global `allNotesOff` on a chord change | **Eliminated** (0 in every window; only `StyleSequencer.stop()` calls it, CODE) |
| Old NOTE_OFF kills a new note (identity) | **Eliminated on host**: stuck 0, orphan 0 in all 69 runs except one (RIGHT keys held while the final STOP ran = known STOP/START global kill, F7 of the earlier report, not the chord path) |
| Event queue backlog | Not observed (no catch-up bursts); no app queue exists (MIDI callbacks are synchronous on T-MIDI) |
| New features added work to the chord path | **Eliminated** (§9) |
| Synth-mutex wait / render time grows after a chord burst (voice load) | **Hypothesis**, supported by DEVICE-OLD waits (`renderLockWait>2 ms` ×212, max 10.8 ms) — **Not measured** after this change set |
| `MidiInputPort.send` blocks (Android MIDI service busy) with MIDI OUT on | **Hypothesis**; MIDI OUT state in the user report is unknown |
| GC pause from per-change allocation / log strings | **Hypothesis**; allocation measured, GC NOT MEASURED |
| Oboe underrun / xrun | **Not measured** |

## 11. Likely root causes, ordered by evidence
1. **(Strong evidence, mechanism Confirmed)** Chord retarget on the Main thread performs JNI + MIDI OUT + logging **inside `noteLifecycleLock`**, the same monitor the scheduler needs for every note. Any blocking call inside it (synth mutex held by the renderer after the chord's NOTE burst, a slow `MidiInputPort.send`, a log/GC stall) delays whatever Style events fall due meanwhile, by the remainder of the block — intermittent by construction, and absent in drum-only mode (no retarget, no harmonic notes), which matches the report. Faster chord changes multiply the exposure (more pairs, more voices).
2. **(Hypothesis)** Voice surge + shared synth mutex: each change re-strikes up to ~12 notes (~24 voices) while the previous ones are still in release; the render callback and every note call share one mutex.
3. **(Hypothesis)** USB write blocking inside monitors (only if MIDI OUT was on).
4. **(Hypothesis)** Per-change log/allocation → GC.
None of these is proven to be the pause the user hears; only the *mechanism* of propagation (1) is proven.

## 12. Minimum patch plan (NOT applied — waiting for approval; each separately A/B-able, baseline kept)
- **P1 (addresses 1, 3):** stop holding `noteLifecycleLock` across JNI/USB in `retargetActive`/`updateHeldPitch`/dispatch: update the ownership ledger inside the monitor, then issue the NOTE_OFF/ON afterwards through one ordered writer (or at least outside the lock). Risk: note identity (OFF must still pair with its own ON — keep the `ActiveTransposedNote` identity and the existing STALE_OWNER checks), CASM unchanged.
- **P2 (addresses 1, 4):** run `applyChordNow` on the sequencer's own context instead of Main; remove per-note `DebugLog` in retarget (lean by default). No musical change.
- **P3 (2):** one native call per chord retarget (batch OFF/ON) — native + JNI change, only if device data shows mutex waits.
- Do not touch tempo/tick clock, buffers, CASM, ACMP/LEFT/Transpose logic. Risk to those features: P1/P2 touch only the retarget dispatch path; the existing 18 transpose/ACMP/LEFT contract tests, the stress and metronome tests and the ROSITA C→F/C→G comparison must stay identical.

## 13. Device test still needed (Redmi Pad + PSR-E343; no new APK needed — the last APK has the LATENCY panel)
1. Note: chord mode, MIDI OUT on/off, Style, tempo, SF2 set, screen refresh rate.
2. Metronome FOLLOW ON, Style playing ROSITA/any, ACMP ON, change chords C→F→G→C at ~250 ms and ~125 ms for 30 s, MIDI OUT off; then repeat with MIDI OUT on. Record wall-clock time of every audible hold. LATENCY panel: RESET STATS before, SAVE REPORT after (watch `styleEventWakeLatenessBeforeLifecycle`, `…DispatchLatenessAfterLifecycle`, `chordApplyDuration`, synth-mutex waits, XRUN). A hold should show as a spike in dispatch lateness; if the metronome click stays in phase with the kick while sound holds, the stall is downstream of the schedule.
3. Repeat in drum-only (ACMP OFF) and with ACMP ON + chord held but not changed: confirms the harmony-retarget dependence.
4. Repeat once with RIGHT 1/2/3 off and LEFT off to separate keyboard-voice load.
5. Send the saved reports plus the timestamps.

## 14. Reproduce on the host
`python3 tools/test_rtdiag.py --out /tmp/ccs --rosita --only com.yourapp.rtdiag.ChordChangeStutterTest` (12 tests, ~12 min; add `CCS_ONLY=name1,name2` to select). CI: workflow `Realtime MIDI & audio stress diagnostic`, job `rtdiag`, branch `diag/chord-change-stutter`.
