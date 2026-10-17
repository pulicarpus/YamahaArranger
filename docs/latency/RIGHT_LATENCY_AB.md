# RIGHT latency (PSR-E343 USB): stage map, onset evidence, output-buffer A/B

Branch `perf/right-latency` from `feature/virtual-keyboard` 1985068. Nothing from the older latency *patches* (2e844e3) is included.
Only the observer-only probe is reused (without the style/ACMP hooks). Default behaviour is unchanged (buffer A = 2 bursts).

## Stage map (what each number is)
| Stage | Where | Probe stat |
|---|---|---|
| 1 USB driver -> app | `MidiReceiver.onSend(timestamp)` vs `System.nanoTime()` (same CLOCK_MONOTONIC) | `midiUsbTimestampToOnSend` |
| 2 parse | onSend enter -> NOTE_ON parsed (+ thread CPU vs wall) | `midiOnSendTotal`, `midiOnSendThreadCpuTime` (wall - cpu = blocked/preempted) |
| 3 routing | `keyboardLock` wait, lock -> `startRightNote` (incl. RIGHT IN log), onSend -> all layers handed to native | `keyboardLockWait`, `rightKeyboardLockToStartRightNote`, `rightNoteOnSendToAllLayersHandedToNative` |
| 4 JNI | `engine.noteOn*` entry -> JNI call (incl. `traceAudio` formatting), JNI call duration | `rightEngineNoteOnEntryToJniCall`, `jniKeyboardNoteOn_includesNativeLockWait` |
| 5 BASSMIDI event | native: lock wait, event queued | `noteLockWait`, `noteDispatch` |
| 6 render | NOTE_ON queued -> start of the decode that consumes it -> end of that decode | `noteToRender`, `noteToBuffer`, `renderDecode`, `callbackTotal` |
| 7 output | Oboe estimate only (`calculateLatencyMillis`), buffer size, XRUN | `LATENCY_OUTPUT*`, `BUFFER ...` |
Software stages end when samples are handed to Oboe. Stage 7 is an Oboe estimate, NOT an acoustic measurement; absolute
key-to-ear latency needs an external recording.

## Measured baseline (Redmi Pad, report of 88c3dd2, RIGHT, earlier session)
USB->onSend mean 1.03 ms (max 11.9); onSend->all layers in native mean 4.46 ms (max 16.0); native lock 0.40 ms;
NOTE_ON->render start 2.40 ms; ->buffer ready 4.26 ms (max 6.63). Output: AAudio Shared+LowLatency, burst 256, buffer 512
(10.67 ms), Oboe estimate 18.82 ms, 13 XRUN. Software total ~10 ms vs output ~19 ms: the output chain is the larger, and the
buffer (2 bursts) is the only controllable part of it.

## Preset onset (offline TinySoundFont reference of the original SF2s, C4; NOT BASSMIDI)
ms after NOTE_ON until the envelope reaches -40 / -20 / -10 dB of its 0-500 ms peak:
| Preset | Colombo | Tyros 4 |
|---|---|---|
| Grand Piano | 1.0 / 1.5 / 3.0 | 4.0 / 8.5 / 12.5 (peak 35 ms) |
| E.Piano | 0 / 0 / 2.0 | 15.5 / 16.0 / 19.0 |
| Steel Guitar | 0 / 0.5 / 2.5-5.5 | 3.0 / 5.5 / 10.5 |
| Organ | 9.0 / 10.0 / 10.5 | 7.0 / 10.5 / 16.5 |
| Strings | 0 / 6.5 / 19 | 0 / 33 / 65 |
Velocity does not change these onsets. So on Tyros pianos/EP the preset itself adds ~3-15 ms before the note is felt, comparable
to the whole software chain. The in-app **ONSET TEST** repeats this with real BASSMIDI on the loaded preset.

## Experiments (all switchable, never persisted, every launch starts at A)
Panel **LATENCY** (bottom centre):
- OUTPUT BUFFER: A 2x burst (production), B 1.5x, C 1x, D ADAPTIVE (starts at 1x, +1 burst per underrun burst, max 3x, 250 ms poll off the audio thread).
  Shows buffer ms, XRUN total / since mode set, Oboe latency estimate. Saving: B ~2.7 ms, C ~5.3 ms of output buffer. Risk: XRUN.
- ONSET TEST (RIGHT, C4): needs the style stopped; output is paused for a moment; 4 velocities.
- RESET STATS / SAVE REPORT (Download/YamahaArranger).
No timbre, gain, filter, velocity, MIDI OUT, style, ACMP or CASM change. The audio callback is not modified.

## A/B procedure (Redmi Pad, PSR-E343 via USB, RIGHT1, STYLE OFF first)
1. LATENCY -> RESET STATS. Mode A. Play ~60 notes (single, fast repeats, 4-note chords). Note XRUN and the stage table. SAVE REPORT.
2. Mode C (1x). RESET STATS. Same playing for the same time. Note XRUN since mode set. Judge feel. SAVE REPORT.
3. Mode B and D the same way. Keep the mode with 0 new XRUN that feels better; if XRUN grows, go back to A.
4. Repeat with STYLE ON, with Colombo and Tyros pianos. Run ONSET TEST (style stopped) for each voice.
Success = you can feel the difference with no XRUN/crackle, not just a smaller number.

---
# Update: reading of the 20261017_213318 device report, and what was added

## What the metrics really mean (verified in code)
- `keyNotesOverwrittenBeforeRender=20` is NOT 20 lost notes. The timing probe keeps ONE pending timestamp; a keyboard NOTE_ON that
  arrives while that stamp is still waiting for the next render only merges its *timing sample* into it. Every NOTE_ON goes
  straight to `BASS_MIDI_StreamEvent` (no app-side MIDI queue exists; JNI call on the MIDI thread), BASSMIDI keeps them in order and
  applies them at the start of the next decode. 20 of 336 means 20 notes shared a render period with an earlier note (chords / fast repeats).
  Host test: chord of 4 + ON/OFF/ON on one key -> all 7 events reach BASSMIDI in order, 5 timing samples merged. The report now says so and
  also prints `keyNoteOnsQueuedToBass`.
- NOTE_ON followed by NOTE_OFF inside one 5.3 ms render period would be applied back to back (a blip); no real key press is that short.
- `bufferNow=768` in mode D: the app's adaptive step needs `getXRunCount`, which was unavailable (`xruns=unavailable`), so the growth to
  3 bursts was not made by the app's tick; 1 burst was not stable on this device in any case (callbacks over budget: 126 of 36690).
  `estimatedOutputLatencyMs` was unavailable (ErrorInvalidState) in this report, so no output estimate exists for that session.

## What the report proves
| Stage (RIGHT, mean) | Value | Reading |
|---|---|---|
| USB -> onSend | 0.69 ms | negligible |
| onSend thread: wall 247 us vs CPU 249 us | CPU-bound | not blocked/preempted; the thread is *computing* |
| keyboard lock wait | 5 us | no contention in Kotlin |
| lock -> startRightNote (RIGHT IN log) | 595 us | one log line |
| engine entry -> JNI (traceAudio format+log) | 758 us | one log line |
| JNI call incl. native lock | 276 us | native lock wait 197 us |
| onSend -> all layers in native | 2.83 ms (p95 <=8 ms) | ~1.35 ms of it is two log lines |
| note queued -> render start / buffer ready | 2.26 / 3.27 ms | half a render period + decode, as expected |
A DebugLog.add costs ~1 us on a host JVM (bench in the session scratchpad), so 0.6 ms on the device is not the algorithm itself. The most likely
explanation (NOT proven: no CPU-frequency or JIT data was collected) is that this thread runs cold: low CPU frequency and code that is rarely
executed. Whatever the cause, the cost is CPU-bound and sits on the path of every note. Consequence for chords (derived, not measured
separately): notes are handled one after another on that thread, so with ~2.8 ms per note the 4th note of a chord is handed to BASSMIDI
up to ~8 ms after the 1st.
Also proven: `renderLockWait` exceeded 2 ms 212 times (max 10.8 ms) and `styleNoteLockWait` p95 4 ms: somebody holds the synth mutex for
milliseconds. With the style running, `logAudioPath` (native, under the mutex) calls `LOGI` -> JNI AttachCurrentThread + `DebugLog.add`
for each style note. Not proven for RIGHT-only play; the new hold statistics will say who it is.

## New tools (all off / baseline by default, never persisted)
1. **LIVE vs ISOLATED (path A vs B)**: ARM, then play ONE RIGHT note (style stopped). The live callback output of the render block that consumed
   the NOTE_ON is copied (500 ms, bounded memcpy, armed only) and compared with an isolated BASSMIDI decode of the same preset / key / velocity
   with the controllers read back from the live channel (and with neutral controllers). Reports first-sample, -40/-20/-10 dB, 10-90 % rise,
   and the sample-level error (dB) and correlation over the first 100 ms. `LIVE_PATH_ADDS_NOTHING` means the app/BASSMIDI live chain does not
   alter the note: any "slow strings" character is then the preset itself or happens after the app (Oboe/AAudio/device), which only an
   external recording can separate.
2. **Synth mutex hold statistics** (`synthMutexHold_*` in the report): keyboard NOTE_ON, style NOTE_ON, NOTE_OFF, render, preset change,
   mixer controller, font load, diagnostics.
3. **NOTE LOG A/B**: B skips the log lines on the keyboard NOTE_ON/OFF path (MIDI parse log, RIGHT IN/OFF, engine NOTE_ON/OFF trace). No engine,
   controller, MIDI OUT or style change. Expect `rightNoteOnSendToAllLayersHandedToNative` to drop by roughly the two measured log costs.

## Procedure (Redmi Pad, PSR-E343, RIGHT1, STYLE OFF)
1. LATENCY -> RESET STATS. NOTE LOG A. Play 60 notes incl. 4-note chords. Wait, SAVE REPORT.
2. NOTE LOG B. RESET STATS. Same playing. SAVE REPORT. Judge chord tightness/feel.
3. Colombo piano: ARM, play C4 (single note, velocity ~100). Repeat with Tyros 4 Grand. The panel shows PATH A / PATH B / COMPARE; SAVE REPORT.
4. Send all reports.
