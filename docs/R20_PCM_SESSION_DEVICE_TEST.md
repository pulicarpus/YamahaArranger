# R2-0 PCM SESSION — diagnostic device gate

Policy C and SF2 selection remain unchanged. Catalog runtime OFF. Full historical regression CI remains BLOCKED. R2-0 observes existing PCM and MIDI counters; it does not isolate drum/keyboard streams or tap them with GetChannel. No gain, controller, preset or MIDI OUT write comes from mark/export.

## Session procedure without restart or SF2 reload

1. Install the diagnostic APK. Load the existing four SF2 once, then ROSITA in INTERNAL. If Android rejects an update signature, preserve application/REG data and report the error rather than uninstalling immediately.
2. STOP. Set the desired mute configuration BEFORE marking. Wait at least 3 seconds after the last mute change so previous tails can settle. In SF2 / STYLE INSPECTOR press **MARK PCM SESSION**. A successful toast gives a new session ID. The button is debug-only and disabled during playback.
3. Play the same Main A 8 bars → Main D 8 bars → STOP sequence. Do not touch keyboard or mute/solo during the session. Wait the same 5 seconds for every session. Press **SAVE REPORT** at the top of Inspector, not SAVE DRUM (SMALL).
4. Files are in Downloads/YamahaArranger/YamahaArranger_Inspector_*.txt. The PCM_SESSION block near the beginning is independent of process-lifetime peaks. Keep the report, then press MARK again for the next session. App restart and font reload are not needed.
5. Sessions: S0 all parts active; S1 Rhythm1/2 only; S2 Rhythm1/2 muted; optional S3 matches S2 with existing reverb/chorus sends manually set to zero before mark. Mark/export never changes sends automatically. Record any deliberate setup difference.

## Read and validate the report

- peakSinceMark, clippedSinceMark, RMS, samples/frames and seconds cover successful parent decode PCM summed with existing lanes BEFORE AudioEngine's existing output clamp. Idle decoded silence is included. The output clamp itself is unchanged.
- worstFrame is the absolute start frame of the render block with the highest session peak, not a claimed exact sample offset. worstGeneration records mapping generation at that block. Zero-frame sessions are not valid evidence.
- NOTE_ON attempts/sent deltas list channels 0..15. keyboardNoteOnDelta sums successful internal BASSMIDI NOTE_ON for channels 0..3. These counters do not claim to count external-only MIDI OUT messages.
- muteAtMark/muteNow show explicit mute state for all channels; muteChangesSinceMark counts actual transitions, including keyboard mute and style override/REG/solo changes. Repeated setters with the same mute value are not transitions. A valid isolation test requires zero changes and matching mute states.
- styleVelocityCountDelta is the existing style-event count per channel. Muted channels must have zero NOTE_ON deltas; active channels should have nonzero expected activity. Keyboard delta must be zero for the isolation protocol.
- CC91/CC93 at mark/export are readback only. Nonzero sends do not prove a sounding FX tail. No FX isolation/meter is added.
- Compare frames and style-velocity counts within ±10% across identical arrangements. Check markTransport=STOP, settledAtMarkSeconds>=3, matching bindings, and the same section sequence. Otherwise mark the pair INVALID rather than attributing clipping to drums.
- Legacy STYLE/RESOLVER UI tables are labeled legacy_ui_map_not_engine_binding. Use NATIVE/U0 live evidence for bindings.

## Overhead and remaining gates

Host before/after benchmarks retain a strict <1% threshold and raw timings. They use mock decode PCM and are NOT measurements of Redmi Pad callback overhead. Claude G5 requires <1% callback-time overhead on the device; this remains UNVERIFIED until comparable baseline163f769 and R2-0 device measurements exist. This diagnostic APK is for collecting that evidence, not final G5 certification or release approval.

Measure baseline and candidate with identical SF2/style, buffers, temperature and session sequence. Retain callback timing/liveness reports and compare repeated runs. Do not infer sub-1% device overhead merely from host results. Also test LIVE CONTROL, REG SAVE/RECALL, RELEASE, RIGHT OFF and E343 connect/reconnect (no automatic Bank/PC); report actual outcomes. No sound-source attribution or device compatibility is certified by host tests.
