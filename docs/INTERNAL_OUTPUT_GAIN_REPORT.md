# INTERNAL OUTPUT GAIN — experiment (NOT a merge)

Branch `experiment/internal-output-gain`, baseline `f81b286` (Touch Response Custom, CI green with APK). No merge to main, no PR. Velocity curves, Style, CASM, MIDI OUT and SF2 envelopes are untouched.
User finding: Android → AUX → PSR-E343 speaker, media volume 100%, E343 volume unchanged: internal BASSMIDI sounds about **40 %** of MIDI OUT. (Perceived loudness halves roughly every −10 dB, so "40 %" is on the order of −13 dB; the gain steps below go to +12 dB.)

## Audit (code + real BASSMIDI measurements, see `docs/RIGHT_AUDIO_FIDELITY_AUDIT.md`)
| stage | current value | headroom | note |
|---|---|---|---|
| BASSMIDI stream `MIDI_VOL` | 1.0 | BASSMIDI accepts >1: 1.27 = +2.1 dB (measured) | `setMasterGain` clamps to ≤ 1.0, although the Kotlin master maps 100..127 → 1.00..1.27, so UI 101..127 does nothing |
| SoundFont volume | 0.90 | 1.0 = +0.9 dB, 1.5 = +4.4 dB | `soundFontVolume_` (changes every font and the drum font) |
| CC7 / CC11 | per-part mixer; CC7 127 vs 100 = +4.2 dB, CC11 100 = −4.2 | read the "CONTROLLERS (BASS readback)" line | per-part, not a global fix |
| engine | float stereo → hard clamp ±1.0 → Oboe | none | no limiter; any gain increase would hard-clip |
Because the missing level (≈ −8…−13 dB) is larger than all clamps and per-part volumes combined (+2.1 dB), the existing knobs cannot close the gap; the master clamp lift would also change the legacy gain for everyone and has no safety net. So a **separate, measurable stage** was added.

## What was implemented
`internal_gain.h` — one linear gain stage on the whole BASSMIDI mix (RIGHT, LEFT, Style, drums), placed in `AudioEngine::onAudioReady` right after `soundFont_.render` and before the existing clamp (metronome is mixed afterwards, unaffected):
* **0 dB (default) = legacy path:** samples are not modified; only measured. Rollback = pick 0 dB (or install the previous APK).
* **+3 / +6 / +9 / +12 dB:** `y = x·g`; above |0.8| a smooth tanh knee bends the peak toward (never above) 1.0, so loud passages are compressed instead of hard-clipped. Stateless: no look-ahead, no latency, no allocation, no lock.
* Meters (relaxed atomics, read by the UI): post-gain pre-knee peak, output peak, RMS (dBFS), `overSamples` (|x·g| > 1.0, i.e. would have hard-clipped without the knee), `limitedSamples` (inside the knee). Shown in RIGHT DIAG → INTERNAL GAIN, red "CLIP RISK" when `overSamples` > 0; RESET METER.
* Persisted in `internal_gain.txt` (integer dB, default 0). Selecting a step resets the meter.
* MIDI OUT, note velocities, Brain, Touch Response: not referenced by the stage (guard-enforced). BASSMIDI stream/font volumes unchanged.

## Tests
* `tests/internal_gain_test.cpp` (native, pure C++): 0 dB bit-identical, +6 dB formula, monotonic, output never reaches 1.0, knee continuous, 12 dB cap, NaN safe, counters/reset.
* `tests/cpv/InternalGainTest.kt`: default 0 dB, persistence/garbage, report parser and clip flag, source guards (stage sits between synth and clamp; Brain and MIDI code do not know the gain).
* `tests/rtdiag/InternalGainRtTest.kt` (production chain, mocked JNI): gain reaches the bridge only; engine velocities and MIDI OUT NOTE_ON are identical with and without gain for **RIGHT alone and RIGHT + Style**.
* Real BASSMIDI (`tests/right_fidelity_real_bass_test.cpp`, section 7): the stage on real BASSMIDI output, RIGHT only (3 ch) and RIGHT + 8 Style channels, "normal" (app volumes) and "HOT" (stream 1.27, font 1.5, CC7 127, deliberate stress): peak, RMS gain achieved versus ideal linear gain and the knee loss, over/limited counters. These use synthetic SoundFonts, not Colombo, and not the Oboe/speaker chain.
* Guard `tools/test_internal_gain_guard.py` (baseline f81b286).

## Redmi Pad test (user)
1. Install the APK, open RIGHT DIAG → INTERNAL GAIN, keep 0 dB, press RESET METER, play RIGHT only (and then with a Style running) at normal and hard touch; note peak/RMS and "CLIP RISK".
2. Try +6 dB, then +9 / +12 dB (same playing, same Android and E343 volumes). Compare with MIDI OUT through the same speaker. Watch the meter: `over` > 0 means the knee is working; sustained high `knee` counts or audible harshness means too much gain for that patch.
3. Report the preferred step, whether RIGHT + Style stays clean, and the meter line. Touch Response Custom is unchanged.
