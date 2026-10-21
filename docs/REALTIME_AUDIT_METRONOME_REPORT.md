# Full realtime audio pipeline audit + Metronome

Branch `feature/realtime-audit-metronome` (from `diag/realtime-midi-stress` 1251efe, itself from `experiment/custom-sf2-drum-kit` c447a32). Not merged to main.
Evidence classes: `CODE` (read in source), `HOST` (production Kotlin/C++ on x86 with fake USB ports / recording JNI bridge / mock BASS - says nothing about Redmi Pad speed), `X86-BASS` (real BASSMIDI, synthetic SF2), `MODEL` (a cost parameter we chose, never a measurement), `DEVICE-OLD` (numbers from earlier device reports in `docs/`), `NOT MEASURED`.
Re-validated on this HEAD: every finding of `docs/REALTIME_MIDI_STRESS_DIAGNOSTIC.md` (F1-F13) still holds; the code paths they cite were not changed except by the observer hooks below.

## 1. Pipeline map (threads, clocks, queues, locks)
| Stage | Thread | Clock / timestamp | Queue | Lock / blocking | Allocation / logging | Risk |
|---|---|---|---|---|---|---|
| E343 -> USB -> MidiService | Android system | USB frame time; `onSend(timestamp)` = CLOCK_MONOTONIC | service socket to the app (NOT observable; `midiQueueDepth = NOT MEASURED`, proxy `midiUsbTimestampToOnSend`) | platform | - | slow `onSend` delays everything behind it; drop behaviour UNKNOWN |
| `MidiNoteReceiver.onSend` + parser (`MidiInputManager.kt:98`) | MIDI dispatch thread | `System.nanoTime` | none | none | 3-4 `DebugLog.add` per key event (String + SimpleDateFormat) | log cost is on the note path |
| `ArrangerBrain.onKeyboardNoteOn` (:257) | same MIDI thread | - | none | `keyboardLock` held across JNI + USB write | `traceAudio` `String.format` per note | blocks UI toggles/CC64/virtual keyboard |
| ChordDetector -> `onChordChanged` (:400) | MIDI thread, then Main (`scope.launch`, viewModelScope = Main.immediate) | `delay(15 ms)` settle (intentional) | coroutine queue on Main | - | ~71 DebugLog lines per chord change | retarget runs on the UI thread |
| `currentChord` setter -> `handleChordChange` -> `retargetActive` (StyleSequencer :90/:786/:798) | **Main thread** | - | - | `noteLifecycleLock` per note, JNI + USB inside | ~12 NOTE_OFF + 12 NOTE_ON + 24 USB writes per change | contends with the scheduler for the lifecycle monitor |
| Style scheduler (`playOnce`) | `Dispatchers.Default` | TempoClock (CLOCK_MONOTONIC) integrated per tempo segment; `withTimeoutOrNull(ms)` wake | none | lifecycle monitor per event, JNI + USB inside | per-event diagnostics | wake jitter, lock waits |
| JNI -> `BassMidiPlayer::noteOn` | caller thread | `steady_clock` | none (BASS applies at next decode) | ONE `std::mutex mutex_` shared with render | `logAudioPath`: 2x StreamGetEvent, drum signature string per hit, sampled `uiLog` (logcat + JNI upcall) | priority inversion with the RT callback |
| Oboe callback -> `BassMidiPlayer::render` | AAudio RT thread | callback start / frame count | Oboe buffer (2 bursts by default) | takes `mutex_` | none in normal path | underrun when `mutex_` is held; voice cost |
| BASSMIDI decode | RT thread, inside `mutex_` | - | - | - | voices <= 1000, SRC 8-point sinc, no `MIDI_CPU` cap | cost linear in voices (X86-BASS 1.3-1.75 us/voice/256 frames) |
| MIDI OUT | Main / MIDI / Default threads | - | none (blocking socket write) | `MidiMixerOutput` monitor | - | per-write latency lands inside app locks |
**Style clock vs audio clock (not assumed synchronous):** the Style clock is `System.nanoTime` integrated per tempo segment; the audio clock is the Oboe frame counter. They are only related through "an event dispatched at time T is rendered by the first callback whose block starts after T" (same for every note). Quantisation = one callback (5.3 ms @ 256 frames); there is no timestamp-accurate event scheduling inside BASSMIDI (events are applied at block start). The new metronome uses exactly this relation for Follow mode (section 4) and frame counting for Standalone.

## 2. New instrumentation (observer only; production behaviour unchanged)
`LatencyProbe` already had hooks that were **never called in production code** on this baseline (chord, scheduler, section stats were empty). Now wired (every call is `if(enabled)` + one nanoTime; fixed arrays, no allocation/formatting/IO on hot paths; reports are built by the export button):
`midiEventsIn`/s, `chordChanges`/s, `chordFirstKeyToRecognized`, `chordRecognizedToApplyStart`, `chordFirstKeyToApplyStart`, `chordApplyDuration_handleChordChange`, `chordFirstKeyToRetargetDone`, `retargetNoteReplacements` per change, ring of the last 16 `CHORD_TRANSITION` rows, scheduler wake/dispatch lateness, section request->boundary->apply->first note.
Native (`latency_probe.h`): style/retarget `NOTE_ON -> render start / buffer ready`, callback start-to-start interval + count over 150 % of budget (late-callback proxy), active BASSMIDI voices per callback (histogram, max), last 48 callbacks (`CALLBACK_TIMELINE startNs durUs`, same clock as the Kotlin stamps).
Export: LATENCY panel -> SAVE REPORT (unchanged flow). `NOT MEASURED`: MIDI queue depth inside Android; Oboe xrun counter when the stream reports `unavailable`; absolute key-to-ear latency (needs an external recording).
`tools/test_realtime_guard.py` proves the production diff in StyleSequencer / ArrangerBrain / MidiInputManager / bassmidi_player.cpp / AudioEngineManager contains only observer or metronome lines; the MIDI OUT writer, CASM transformer, chord detector, resolver and Registration are byte-identical to c447a32.

## 3. Chord transition: what the time is spent on (HOST measurement, 7 changes per row; p50/p95 are histogram upper bounds)
| Phase | Spread 0 ms | Spread 20 ms | MODEL native 300 us + USB 150 us, MIDI OUT on | Kind |
|---|---|---|---|---|
| first key -> chord recognised | 0.17 ms | **40.6 ms** (finger spread) | 0.19 ms | musical (the player's hand) |
| recognised -> apply starts | 15.9 ms | 15.9 ms | 15.8 ms | **intentional** 15 ms settle (`chordSettleMs`) + ~0.9 ms Main queue |
| retarget loop on Main (`applyDuration`) | 0.6-0.8 ms | 0.7 ms | **10.0 ms mean, 12.2 max** | technical (JNI + USB + logs, 24 native calls + 24 USB writes) |
| first key -> retarget done | 16.6-17.2 ms | 57 ms | 25.9 ms mean | sum |
| scheduler wake lateness during these runs | mean 0.45-0.73 ms, p95 <= 4 ms, max 7.6 ms | similar | **mean 3.6 ms, p95 16 ms, max 44.6 ms** | technical: the Main-thread retarget takes `noteLifecycleLock` note by note and the Style scheduler waits for it |
| by bar phase (mid-beat, just before / after the bar line, during Fill) | no difference in any phase | | | the code does not quantise chord changes |
Reading: with free native/USB calls the retarget is cheap; with per-call costs of a few hundred microseconds (the order of magnitude seen on the device: JNI 276 us incl. 197 us lock wait, DEVICE-OLD) the same 48 calls take ~10 ms on the UI thread and push Style events late by up to 44 ms. The device value of these costs is **NOT MEASURED** until the new report is captured. The 15 ms settle is the only deliberate delay; hands that spread a chord over 20 ms add 40 ms before anything can react (Multi Finger needs 3 notes; Single/AI Fingered change chord on rolled inversions, see diagnostic F8).

## 4. Metronome (Stage B) - status: implemented, host-tested; NOT yet heard on the Redmi Pad
* Click generator `metronome_engine.h` mixed in `AudioEngine::onAudioReady` **after** BASSMIDI and the clamp (one relaxed atomic load when off). It never uses BASSMIDI, the synth mutex, a MIDI channel, Style, ACMP, note ownership or MIDI OUT (`midi` is not referenced). Click = short synthesized sine burst (strong 1760 Hz, medium 1320, weak 1046 Hz), own volume.
* **Standalone**: clock = counting output frames (drift-free relative to the audio clock, independent of any thread). Tempo = main YA tempo, applied immediately (the time to the next pulse is rescaled). START/STOP independent of the Style. Meters 2/4, 3/4, 4/4, 6/8; beat 1 accented. **6/8 = six eighth-note pulses per bar in two groups of three: Strong, weak, weak, Medium, weak, weak**; BPM is quarter notes, so at 120 a pulse is 250 ms. A meter change restarts the bar at the next pulse.
* **Follow Style**: no second musical timer. A planner thread (plain background thread, not UI, not an Android timer) reads the Style's own TempoClock through read-only accessors (`tickToNanos`, `masterTickNow`, `loopOriginTick`, tempo revision), and queues absolute CLOCK_MONOTONIC targets 40 ms ahead into a lock-free ring. The audio callback fires a click at frame 0 of the first block whose start is >= the target - the same relation a Style NOTE_ON has. Tempo change -> one generation bump re-plans (duplicates are dropped natively); Style STOP -> flush and silence; a click more than 100 ms late is dropped (counted), never played late. Meter and accents follow the Style's own meter; bar origin = start of the current section loop. **Pickup/anacrusis**: not detectable from the parsed Style, so a pickup bar shows its downbeat on the section start until the next section; time-signature changes inside a Style are not modelled. The first downbeat after START is clicked if the planner sees the start within 40 ms (planner polls every 4 ms while idle).
* Visual beat indicator: UI polls one atomic at ~30 Hz only while the panel is open (display, not a clock).
### Objective synchronisation (HOST, real StyleSequencer on the rig; kick drum on beats 1 and 3 vs click target)
| Case | click spacing | kick - click offset p50 / p95 / max | after STOP | notes MIDI sent by metronome |
|---|---|---|---|---|
| steady 120 BPM | 500.000 ms exactly | 0.88 / 1.25 / 5.3 ms | no click planned | 0 |
| 120 -> 168 BPM mid-run | 500 -> 357.143 ms (= 60000/168) | 1.12 / 1.34 / 3.9 ms | none | 0 |
| MAIN -> FILL -> MAIN | 500.000 ms, continuous | 0.90 / 4.0 / 4.5 ms | none | 0 |
The offset is the Style scheduler's own lateness against the ideal grid (kick always after the click target, never before); the click itself has zero drift. Style event stream with metronome ON vs OFF: identical (first 150 events compared, 168 total). Standalone native test: onsets within 1 frame of the grid for block sizes 137/240/256/512/1024 at 48 kHz; tempo change 120->90 gives 32000-frame intervals; Follow: all clicks fire once, lateness < one block, stale/cancelled/duplicate clicks never sound; disabled = buffer bit-identical.
**Not verified:** audible alignment on the device (needs the A/B test below), the Oboe output latency of the click versus drums (identical path by design, not measured), battery/CPU cost of the planner thread (4-10 ms wakeups only while FOLLOW is ON).

## 5. Other new findings in this pass
* `MidiInputManager.allNotesOff()` writes CC123 on 16 channels **even when the MIDI OUT button is off** (it only checks that a port exists). START and STOP each cause it (`StyleSequencer.play()` -> `stop()`): 32 writes in the host run with MIDI OUT off. This reaches the E343 and silences its own sounding notes. Not changed here (baseline behaviour).
* Planner/metronome never produce any NOTE/CC MIDI (asserted by test).

## 6. Hypotheses status
Proven (code/host): Main-thread retarget with JNI+USB+logs; lock contention between that retarget and the scheduler (lifecycle monitor); per-event logging cost; STOP/START global note kill; CC123 regardless of MIDI OUT.
Not proven (needs device): render lock contention/priority inversion (std::mutex has no priority inheritance; callers are normal-priority threads) causing underruns; voice load/stealing on ARM; Android MIDI service drops; Default-pool wake jitter. All are now observable in the SAVE REPORT.

## 7. Redmi Pad test (APK from the CI run named in the hand-over message)
1. LATENCY -> RESET STATS. Play your normal fast-chord passage with Style ACMP ON for 30-60 s (note: chord mode, MIDI OUT on/off, SF2 set, tempo). Press SAVE REPORT while the Style is STOPPED. Send the TXT. Look for: `CHORD_TRANSITION` rows, `chordApplyDuration`, `styleEventWakeLatenessBeforeLifecycle` p95/max, `renderLockWait`, `callbackStartToStartInterval`, `callbackIntervalOver150pctOfBudget`, `activeBassVoices` max, `styleNoteQueuedToRenderStart`, `xruns`.
2. Repeat once with MIDI OUT off and once with NOTE LOG B (lean) to separate USB and logging cost.
3. METRONOME button: Standalone 4/4 and 6/8 at 90 and 140 BPM (listen for steadiness while you play RIGHT/LEFT); then FOLLOW STYLE: START a Style, listen for click vs kick/snare; change tempo with +/-; MAIN->FILL->MAIN; STOP (click must stop). Record a phone-mic or line recording if possible: the click and the kick should coincide to within a few ms.
4. Tell us whether the click ever lands audibly before or after the drums and whether any note or Style sound changes with the metronome ON.

## 8. Stage C - next realtime patches (not applied; one at a time, each with regression + A/B)
1. Move `applyChordNow`/retarget off the Main thread (same ordering, same musical result). A/B: `chordApplyDuration`, scheduler wake lateness, UI frame time. Regression: CPV/R22/R23 event traces byte-identical.
2. Remove per-note logging from retarget, engine traces and parser by default (lean as default; verbose only in diagnostic mode). A/B: onSend->native and apply duration.
3. Native: build diagnostics under the mutex, emit after unlock; remove the per-drum-hit signature string. A/B: `renderLockWait`, `styleNoteLockWait`.
4. MIDI OUT writer on its own thread with an ordered queue (no USB write inside `keyboardLock`/`noteLifecycleLock`). Regression: note-balance test on the MIDI OUT stream.
5. START/STOP: release only Style channels 4-15; keyboard notes untouched. CC123 only when MIDI OUT is on.
6. Voice/tail policy and thread priority only if the new report shows voices near the cap or late callbacks.
No buffer size or polyphony change is proposed before the report exists.
