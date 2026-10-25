# RIGHT 1/2/3 audio (BASSMIDI) — "tertahan / tumpul" audit (READ-ONLY, no production change)

Branch `experiment/style-editor-v2`. Nothing in the RIGHT path, native engine or timbre was changed. Everything below is either read from the code or taken from
earlier measurements in this repository; what needs the Redmi Pad or an SF2 of the user is listed under "Not measured".

## Signal path, with evidence

| Step | What the code does | Evidence |
|---|---|---|
| NOTE_ON velocity | MIDI 0..127 → `v/127f` → Brain `(v*127f).toInt()` → native `lround(v*127)` clamped 1..127. Lossless for all 128 values (checked in float32). | `MidiInputManager`, `ArrangerBrain.onKeyboardNoteOn`, `BassMidiPlayer::noteOn` |
| NOTE_OFF | `MIDI_EVENT_NOTE` velocity 0 (not SOUNDOFF) through `BASS_MIDI_NOTEOFF1`. | `docs/RIGHT_OFF_NOTE_LIFECYCLE.md` |
| Panel SUSTAIN | A Kotlin ledger delays the NOTE_OFF; the native `setKeyboardSustain` only logs. **With SUSTAIN ON every RIGHT note is held on purpose.** | `ArrangerBrain`, `BassMidiPlayer::setKeyboardSustain` |
| Keyboard controllers | CC64 is read only on the chord input channel (chord sustain). CC1/CC7/CC11 from the keyboard are not forwarded to BASSMIDI. | `MidiInputManager` line ~171 |
| Release time | RELEASE knob → `MIDI_EVENT_RELEASE` (CC72) on channels 0..2 with readback; default 64 = neutral. Measured on real BASSMIDI with a synthetic 1 s-release SF2: −40 dB tail 10 / 400 / ≥2000 ms for 0 / 64 / 127 (docs/RELEASE_AUDIT_20261012.md). | `applyKeyboardRelease` |
| Reverb / chorus | RIGHT channels 0..2 are never given sends, so BASSMIDI defaults apply (the style channels get explicit values). | docs/RELEASE_AUDIT_20261012.md |
| Quality / voices | `BASS_ATTRIB_MIDI_SRC = 2`, 1000 voices, `BASS_ATTRIB_BUFFER = 0`, update period 10 ms. | `bassmidi_player.cpp` ~97-130, `bassmidi_player.h` |
| Output | Oboe estimate and XRUN counts are shown in the LATENCY panel; software chain ≈ 10 ms, output ≈ 19 ms in the earlier Redmi Pad report. | docs/latency/RIGHT_LATENCY_AB.md |

No path in the code lowers velocity, applies a low-pass, or shortens a release by itself. This does not prove the sound is right; it shows where a difference can NOT come from.

## Hypotheses that fit "held / dull" (all untested on the device)

1. Panel SUSTAIN is ON, or RELEASE is high: notes are held (first two rows above).
2. The preset's own envelope (slow attack, long release, low-pass filter) is what is heard: the Yamaha-style "dull" strings/pads have exactly that; the earlier onset table shows Tyros presets are slower than Colombo by 3-15 ms and Strings reach −10 dB only after 19-65 ms.
3. Default BASSMIDI reverb (never set on RIGHT) smears the tail; identical for RELEASE 0 and 127.
4. Velocity-layer selection of the SF2 (the histogram `rightForwardedHist_` exists to compare what was sent with what the SF2 expects).
5. Output buffer / XRUN (LATENCY panel) — affects feel and crackle, not brightness.

## A/B protocol on the Redmi Pad (uses tools already in the APK; changes nothing)

1. Same RIGHT preset, SUSTAIN OFF, style stopped. UTILITY → `RELEASE PCM • RIGHT n` for RELEASE 0 / 64 / 127; keep the three reports (they contain the live CC readback, the SF2's own generator 38 and the PCM tail).
2. Same notes and velocity on Colombo, Tyros and the Prime preset; compare the onset test and the RIGHT DIAG snapshot (cc7, cc11, reverb, chorus, release, velocity histogram).
3. Record the line-out for the same phrase on (a) the app and (b) the PSR-E343's own voice and compare brightness by ear and, if possible, by spectrum.
4. LATENCY panel: output buffer A vs C, note XRUN.
5. Send the reports: they separate "SF2/preset", "controller state", "effects" and "output" without guessing.

## Not measured (and therefore not claimed)
Real audio on the device; the user's Yamaha-derived SF2 presets (not in this workspace); any spectral comparison with the keyboard's own voices.

## Decision
No RIGHT-path or timbre change. A change is justified only after the protocol above identifies the cause.
