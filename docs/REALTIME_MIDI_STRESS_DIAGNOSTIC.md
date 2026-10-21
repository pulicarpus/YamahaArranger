# Realtime MIDI & audio stress diagnostic (DIAGNOSTIC ONLY)

Branch `diag/realtime-midi-stress`, baseline `experiment/custom-sf2-drum-kit` @ `c447a32` (LIVE V3.2, Registration, Master Transpose, Style Voice Editor, Browser, Custom SF2 drum kit all intact).
No production file was changed and no APK was built. CI: run 39578306342 (job `rtdiag`) SUCCESS on `2b17206`; artifact `RTDIAG-evidence-39578306342`.

**Evidence classes used below:** `CODE` = read in source (file:line). `HOST` = measured on x86 with the production Kotlin/C++ classes but fake USB ports, a recording JNI bridge or a mock BASS (says nothing about Redmi Pad speed). `X86-BASS` = real Linux BASSMIDI, synthetic SF2. `DEVICE-OLD` = numbers already in earlier device reports in `docs/`. `MODEL` = a parameter we chose (never a measurement). `UNKNOWN` = needs a device capture.

## 1. Actual MIDI / audio path
```
PSR-E343 --USB--> Android MidiService -> MidiOutputPort dispatch thread
   MidiNoteReceiver.onSend  (MidiInputManager.kt:98)  parseMessages (running status, F8-FF skipped)
   |  DebugLog x3-4 per key event (RAW/IN lines, :168-190)      callbacks run ON THE SAME THREAD
   v  onNoteOn/Off  (MainViewModel.kt:742)
ArrangerBrain.onKeyboardNoteOn (ArrangerBrain.kt:257)  synchronized(keyboardLock)
   RIGHT:  startRightNote -> AudioEngineManager.noteOnChannel (traceAudio String.format) -> JNI -> BassMidiPlayer.noteOn
                          -> MidiInputManager.sendNoteOn -> MidiMixerOutput(@Synchronized) -> MidiInputPort.send (USB write)   [all inside keyboardLock]
   LEFT+ACMP: ChordDetector.noteOn -> onChordChanged -> scope.launch { delay(15 ms); applyChordNow }   scope = viewModelScope = MAIN thread (MainViewModel.kt:705)
        -> StyleSequencer.currentChord setter (:90) -> handleChordChange (:786) -> retargetActive per held style note (:798, lifecycle monitor)
        -> updateHeldPitch (:842): JNI NOTE_OFF + JNI NOTE_ON + USB OFF + USB ON per note, + DebugLog lines          [ON THE MAIN/UI THREAD]
StyleSequencer playback coroutine (Dispatchers.Default, :640) -> waitForNextBarOrEvent (withTimeoutOrNull ms) -> lifecycle{ transform; JNI noteOnStyleChannel; USB sendNoteOn (:1413) }
BassMidiPlayer (bassmidi_player.cpp)  ONE std::mutex mutex_ : noteOn(:1219) noteOff render(:2352) preset/mixer/diag calls
   noteOn -> logAudioPath(:1115) [2x StreamGetEvent, drum zone signature string (:1145), sampled uiLog -> logcat + JNI upcall DebugLog.add (:39-58)] -> BASS_MIDI_StreamEvent
Oboe LowLatency/Exclusive callback -> AudioEngine::onAudioReady -> BassMidiPlayer::render (same mutex) -> BASS_ChannelGetData(decode stream, 16 ch,
   SRC=2 (8-point sinc), voices 1000, no BASS_ATTRIB_MIDI_CPU) + renderPercussion lanes -> clamp -> device
STOP/START: StyleSequencer.play() calls stop() (:713/:719) -> audioEngine.allNotesOff() + midi CC123 x16 channels (GLOBAL)
```

## 2. Findings
| # | Finding | Evidence |
|---|---|---|
| F1 | **A chord change is executed on the Main/UI thread**: per change ~12 NOTE_OFF + 12 NOTE_ON JNI calls, 24 USB writes (MIDI OUT on) and ~71 DebugLog lines (`nativeCallsPerChange=24`, `usbWritesOnMainTotal=120` for 5 changes, `extraLogLinesPerChordChange=71-73`). Host cost 0.9-1.3 ms; with MODEL per-call costs (native 300 us + USB 150 us) the Main thread is blocked 13 ms, at 700/300 us 25 ms (frame budget 16.7/11.1/8.3 ms at 60/90/120 Hz). | CODE + HOST/MODEL |
| F2 | **Rapid chord changes multiply native events and voice load**: same 4.8 s of Style: 180 native NOTE_ON (1 change/1.2 s) -> 637 (120 ms) -> 871 (80 ms). Peak note instances incl. 1.5 s release tail 109 -> 257 -> 327. Real SF2 pdta: median 2 zones per note (Colombo/Tyros, p90 4, max 8) so ~2 voices per instance. | HOST (event stream) + pdta tables |
| F3 | **All JNI and USB writes run inside app monitors**: RIGHT/LEFT-voice notes inside `keyboardLock` (proved by `Thread.holdsLock`: 2/2 RIGHT native calls and 2/2 USB writes), Style notes and retargets inside `noteLifecycleLock` (43 native calls, 58-82 USB writes). `MidiInputPort.send` is a blocking write. A slow JNI call (synth mutex wait) or USB write therefore blocks every other user of that monitor (UI toggles, CC64, virtual keyboard, the Style scheduler). | HOST (lock probe) + CODE |
| F4 | **One synth mutex shared by render and every note/control/diagnostic call**, with per-note diagnostics inside it (2 extra BASS_MIDI_StreamGetEvent, a heap-built drum signature string on EVERY drum hit, sampled `uiLog` = logcat + JNI upcall to Kotlin). App-side C++ only (mock BASS): keyboard NOTE_ON 0.23 us, melodic Style 0.77 us, drum Style 2.1 us (p95 3.8 us). Contention MODEL: with render holding 3 ms of the 5.33 ms budget a 12-note burst waits 1.8 ms; at 5 ms the longest note call waited 130 ms (std::mutex unfair) and render started up to 6.7 ms late. | CODE + HOST/MODEL; DEVICE-OLD: `renderLockWait>2 ms` x212 (max 10.8 ms), `styleNoteLockWait` p95 4 ms, JNI call incl. native lock 276 us (197 us wait) |
| F5 | **Logging is a large share of the per-event cost and sits on every hot path**: 3.5 DebugLog lines per key event, 7 per ON+OFF pair. Real `DebugLog` source: 49.7 us p50 per pair vs 20.2 us with NOTE LOG B (lean) - 60 % of the host cost. DEVICE-OLD measured 0.6 ms per log line on the device (cold thread), 2.83 ms from onSend to native for one RIGHT note. The logging is not neutral. | HOST + DEVICE-OLD |
| F6 | **No event loss, duplication, mis-order or stuck note at the app level** under any synthetic load (RIGHT1 only, overlap ON-ON-OFF-OFF, ON-OFF-ON, slow/fast chords, ACMP off/on, MAIN-FILL-MAIN with chords every 150 ms): stuck 0, orphan OFF 0. Events are processed strictly in order by one thread; backlog only grows with per-call cost (MODEL 400 us/call x 12 calls per 4-note RIGHT1+2+3 chord: start lateness p50 1.0 ms, p95 1.8 ms; 1000 us: 3.0 / 7.0 ms). Parser handles running status, F8-FF bytes, split packets, SysEx, velocity-0 OFF. | HOST |
| F7 | **STOP and START cut held keyboard notes** (`play()` -> `stop()` -> global `allNotesOff` + CC123 on 16 channels = 32 USB writes per START+STOP). With 2 RIGHT keys held, both were silenced and their later key-ups became orphan OFFs. This matches "not terpotong" for Voice play when START/STOP is pressed. | HOST + CODE |
| F8 | **Chord recognition**: default Multi Finger gives exactly 1 chord per physical chord for roll spreads 0-80 ms (settle window 15 ms). Single Finger / Fingered / AI Fingered on a rolled inversion give 2-3 applied chords at spreads >= 10-30 ms; each one is a full F1 retarget storm. The UI exposes Single/Multi/AI. | HOST |
| F9 | **Voice budget**: BASSMIDI cost on x86 is linear ~1.3-1.75 us per voice per 256-frame block (p50 1.75 ms for 1000 voices at SRC=2 = 33 % of the budget; SRC 0-3 differ by < 10 % here). Cap is 1000 voices; reaching it makes BASSMIDI steal voices (audible cut). F2's 257-327 instances x ~2 zones = 500-650 voices (up to ~1000 for 4-zone presets). Factor for the Redmi Pad CPU is UNKNOWN (3-5x slower than a CI x86 core would put 1000 voices at 100-165 % of budget - an assumption, not a measurement). | X86-BASS + pdta; ARM factor UNKNOWN |
| F10 | **No guard against MIDI loopback**: if the app's MIDI OUT came back to its MIDI IN (1 ms echo) the app self-sustains ~800 writes/s. The E343 normally does not echo; nothing in the baseline proves a loop on the device. | HOST/MODEL |
| F11 | Input CC123/CC120 from the keyboard are ignored; only CC64 on the chord channel is used (characterization, not a proven cause). | HOST |
| F12 | Scheduler wake is a coroutine timeout on the shared Default pool; DEVICE-OLD (#811, capture armed) showed lateness 5-25 ms and four events 77-99 ms late around one chord change. Not reproducible on the host (p95 0.1-0.4 ms). The capture itself was armed, so it may have contributed. | DEVICE-OLD; host cannot reproduce |
| F13 | Minor: `interpolation_ = 2` is 8-point sinc while the comment says 16-point; `soundFontLoaded` is a non-volatile field read on the MIDI thread; the log panel polls `DebugLog.getAll()` at 4 Hz only while open. | CODE |

## 3. Hypotheses
**Proven (code/host):** H1 chord retarget on the Main thread with JNI+USB+logging (F1). H2 retarget multiplies native events/voices (F2). H3 JNI/USB inside app monitors (F3). H4 STOP/START global kill (F7). H5 per-event logging is heavy and native diagnostics run under the synth mutex (F4, F5).
**Not proven (needs device):** H6 render hold approaching the 5.33 ms budget starves note calls and causes underruns/cracks (model + DEVICE-OLD waits support it; needs `synthMutexHold`/XRUN under chord storms). H7 voice load (tails x zones, cap 1000, ARM speed) causes crackle/voice stealing. H8 the Android MIDI service drops/batches events when the app thread is slow. H9 Default-pool wake jitter causes the irregular Style rhythm. H10 loopback.
**Not reproduced at app level (host):** lost/duplicated/mis-ordered events, unbalanced NOTE_ON/OFF, sustain ledger leaks, excess chord changes in default Multi Finger, parser errors.

## 4. Minimal test on the Redmi Pad (existing tools, no new APK needed; the last APK has them)
1. Write down: chord mode (SINGLE/MULTI/AI), MIDI OUT on/off, Style, tempo, SF2 set.
2. LATENCY panel -> RESET STATS, NOTE LOG A. RIGHT1, Style OFF, 30 s: 4-note chords ~4 per second, then fast repeated single notes. SAVE REPORT. Repeat with NOTE LOG B. (Isolates F5 and the onSend->native time.)
3. Style ON, ACMP ON, MAIN A, change chords every ~150-250 ms for 20 s, WITHOUT CAPTURE: note XRUN/callbacks-over-budget in the LATENCY report and when it sounds bad. Repeat with MIDI OUT off.
4. Same as 3 with CAPTURE CHORD armed: END CAPTURE -> STOP -> SAVE CHORD (SMALL). Send STYLE/NATIVE rows (lateness, lifecycle wait, synth mutex waits). Say whether the capture made it worse.
5. Hold two RIGHT keys, press STOP then START: does the sound cut? (confirms F7.)
6. Loop check: MIDI OUT on, play nothing for 20 s with Style running; any `RAW NOTE_ON` line in the log means loopback.
Send the saved reports plus the wall-clock time of the worst moment.

## 5. Narrowest patch plan (NOT implemented; each item separate, A/B on device, baseline kept)
1. Move `applyChordNow`/retarget off the Main thread (run on the sequencer's own context) - no musical change.
2. Remove per-note DebugLog from the realtime paths (retarget, engine traces, RAW/IN) behind lean by default.
3. Native: collect diagnostics under the mutex, format/log after unlock (pattern already used in #788); remove the per-hit drum signature string.
4. Do not hold `keyboardLock` / `noteLifecycleLock` across USB writes: ordered MIDI OUT writer queue.
5. STOP/START: channel-scoped release of Style channels (4-15) instead of global allNotesOff/CC123.
6. Only if device data shows H7: voice/tail policy (release time, `BASS_ATTRIB_MIDI_CPU`) - musical, needs approval.
7. Only if device data shows H8/H9: dispatch-thread priority / dedicated sequencer thread.

## 6. How to rerun
`python3 tools/test_rtdiag.py --out /tmp/rtd` (JVM, needs `build/sff1-jvm/dependencies`), `python3 tools/test_rtdiag_native.py --out /tmp/rtdn`, `python3 tools/test_rtdiag_real_bass.py /tmp/bass-linux /tmp/bassmidi-linux --out /tmp/rtdr` (real SDK, CI only here). Results are lines `RTDIAG ...`.
