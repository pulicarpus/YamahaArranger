# ROLE_PCM device #787: callback/decode isolation (#788 task)

Baseline musical behavior: 79af5ef7016c658553cc36b933722d5821e8e7d1. Retain the safe diagnostic snapshot/session fixes shipped in #789. Workflow numbering is assigned by GitHub, not this task label.

## Evidence and limits

The device used internal CHORD TEST, ACMP ON, physical Android media volume zero, 20–30 seconds playback before STOP/export. It used no external MIDI keyboard/output. All role windows have start=end=5152512, one window/no omissions, and ready DSP/no attach error. PCM_MIX counts exactly 10305024 silent samples, but no measured rendered samples after those windows began. Hundreds of accepted notes are not proof of rendered PCM.

This establishes missing decode accounting during the observed role windows, not a proved Android root cause. Android/Oboe output may stop requesting PCM when media output is silenced; failed decoding or an application stream stop/restart must be distinguished. No automatic restart, gain correction or tap replacement is justified by the old report.

## Minimum observations added

* AUDIO_OUTPUT: requested/successful starts, stops, start result, callback/frame totals, synth/fallback branch counts, completed synth render calls, callback count at latest start/stop, age since last callback.
* PCM_DECODE: parent render attempts, successful/failed decodes, absent parent stream, short reads, decoded bytes, last BASS failure code. Snapshot under the existing synth mutex; formatting outside it.
* ROLE_PCM_PATH: decode attempt count at window creation and at its latest observed note. This relates accepted notes to real decoder progress.

All are read-only and debug ROLE_PCM gated. Output uses lock-free 32-bit relaxed counters (process-lifetime modulo 2^32); snapshot fields are observations rather than an atomic coherent transaction. No new JNI API, lifecycle calls, output, diagnostic notes, I/O or logging in callbacks. The only extra BASS getter is last-error read immediately after a failed decode. Meter OFF removes new observers. Existing PCM taps unchanged.

## One next device test

Use a fresh app/session, same Love Song/SF2. Keep external MIDI disconnected and ACMP ON. Run CHORD TEST/Main D for 20–30 seconds with physical media volume zero, STOP and SAVE PARTS (SMALL). Then set physical media volume to a low positive level, run the same style/chord for another 20–30 seconds, STOP and export again. No in-app gain/mixer changes. Send both reports from that single session; counters are cumulative.

Interpret **differences between reports**, not absolute lifetime counts:

* Callback and parent-decode counts both flat during note demand, no application stop: Android output callback delivery is absent/stalled. If positive physical volume restores callbacks/PCM, output suppression at zero physical volume is demonstrated for this device/session (not a universal Android guarantee).
* Starts/stops or failed start correspond to flat counts: inspect that proven lifecycle failure before modifying anything.
* Callbacks exceed completed returns for a persistent interval: a callback is in flight/stalled. If synth calls advanced but parent decode attempts did not, the existing synth mutex is a candidate; if attempts advanced without success/failure, the decode itself is in flight. This remains a hypothesis until correlated across snapshots.
* Output synth callbacks advance and PCM_DECODE.failed advances: parent BASS decode failure; lastError identifies the BASS error. PCM_MIX can stay flat because its legacy counter follows successful decode.
* Output fallback grows: production callback is choosing unloaded-font voice branch, not BASSMIDI.
* Parent success/bytes/PCM span grows, role callbacks flat: child DSP tap/lifecycle problem remains, distinct from output delivery.
* Role callbacks grow with non-zero RMS/peak: actual rendered role PCM is being measured. Do not correct balance until real-device evidence supports it.

Do not infer acoustic silence, sample identity or Yamaha-exact balance from note acceptance or media-volume zero. Dry role levels exclude shared FX and physical output volume.

## Regression proof

Actual production AudioEngine::onAudioReady and BassMidiPlayer with real Linux BASSMIDI, simulated Oboe platform only: idle warm-up matching device counter, accepted style notes without callback delivery, resumed callback/positive role PCM, failed decode, fallback, stop/restart, failed start and open. Compare #787 versus current meter OFF/ON output PCM and MIDI bytes; no output behavior differences allowed. This is controlled native proof, not Android device validation. Guards strip only explicitly hashed observer blocks and retain pre-existing musical source hashes.
