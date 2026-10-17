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
