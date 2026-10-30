# RIGHT pitch stability audit (unwanted vibrato / "wawawa") — experiment, NOT a merge

Branch `experiment/pitch-stability`, baseline `702bcf3` (Internal Output Gain, CI green with APK). Engine unchanged: BASSMIDI + Oboe. No production behaviour was changed (Style, CASM, ACMP, MIDI OUT, drums, Touch Response/velocity, Internal Output Gain, limiter, effects and SF2 data are untouched; guard `tools/test_pitch_guard.py`).
**Status of the root cause on the device: NOT PROVEN.** No speculative patch was made. SoundFonts Colombo / Tyros / SX Prime are not available in this environment; every number below is from synthetic SoundFonts through the real BASSMIDI (Linux x86_64, offline). They show what BASSMIDI does, not what the Redmi Pad does with Colombo.

## 1. Verified findings (code audit)
* **No pitch controller reaches BASSMIDI from the app.** The code never sends CC1, pitch bend, RPN/pitch-bend range, channel pressure, key pressure, vibrato controllers or Reset All Controllers to the synth. From the PSR-E343 only NOTE_ON/OFF on the chord-input channel and CC64 (sustain) are used; CC1/7/11/71/74, pitch bend and aftertouch are parsed and ignored (the RIGHT DIAG probe counts them, see `docs/RIGHT_USB_VELOCITY_REPORT.md`).
* Controllers actually sent per RIGHT channel: CC7 volume, CC10 pan, CC11 expression, CC91 reverb (default 40), CC93 chorus (default 0), CC72 release. State is re-sent on voice change; nothing is sent twice with a different value.
* Audio path: BASSMIDI decode stream created at 48 000 Hz, read in the Oboe callback; Oboe asks for 48 000 Hz float stereo. The app stores the rate Oboe actually grants but never compares it with BASSMIDI's 48 000 Hz. If a device granted another rate the whole pitch would be off by a constant (not a wobble). The LATENCY report line "Stream opened: sr=…" shows the granted rate; not yet checked on the device.
* Internal gain stage is memoryless and sits after the synth: it cannot create a periodic pitch change.

## 2. Measurements (real BASSMIDI, synthetic SF2, one held note, 1.5 s analysed; run 41067626524, commit 40f4034)
Analyzer validated on known modulation (`tests/pitch_analysis_test.cpp`): vibrato 4/6/8 Hz detected at the right rate (depth 25–28 c of 30 c), tremolo classified as amplitude, steady tone p2p 1.2 c, offset +25 c read as +25.01 c.

| scenario | pitch p2p (cents) | pitch mod peak | amplitude depth | verdict |
|---|---|---|---|---|
| baseline (CC91 0, CC93 0) | 0.44 | 0.00 c | 0.1 % | STABLE |
| **positive control: SF2 vibrato 40 c** | 53.0 | **26 c @ 8.25 Hz** | 1.4 % | PITCH_MOD (analyzer sees real vibrato) |
| app RIGHT default (CC91 40, CC93 0) | 0.52 | 0.03 c | 0.7 % | STABLE |
| no CC sent | 0.52 | 0.03 c | 0.7 % | STABLE |
| reverb CC91 127, SF2 reverb 100 % | 1.11 | 0.08 c | 2.1 % | STABLE |
| **chorus CC93 64** | 6.5 | 3.4 c @ 0.5 Hz | **33 %** @ 0.75 Hz | PITCH_MOD + amplitude beating |
| **chorus CC93 127** | 11.7 | 6.8 c @ 0.5 Hz | **33 %** | PITCH_MOD + amplitude beating |
| **SF2 chorus send 100 %, CC93 unset / 0 / 64** | 11.65 | 6.7 c | 35 % | identical in all three: **CC93 = 0 does not cancel an SF2-supplied chorus send** |
| **CC1 modulation 127 (plain SF2)** | 66 | **32.6 c @ 8.25 Hz** | 2.1 % | PITCH_MOD (BASSMIDI's default modulator) |
| **channel pressure 127 (plain SF2)** | 66 | **32.6 c @ 8.25 Hz** | 2.1 % | PITCH_MOD (same default modulator) |
| CC1 = 0 explicit, pitch bend centre 8192 | 0.44 | 0.00 | 0.1 % | STABLE |
| SF2 modLfoToPitch 40 c | 53 | 26 c @ 8.25 Hz | 1.4 % | PITCH_MOD |
| RIGHT1+2+3 same preset, 3 channels | 0.44 / 0.52 (app defaults) | ~0 | 0.1 / 0.7 % | STABLE (no beating from identical layers) |
| stream rate 44 100 | 0.39 | 0.04 c | 0.0 % | STABLE (mean error 0.00 c) |
| internal gain 0 dB vs +12 dB (peak −6 dBFS) | 0.44 vs 0.48 | 0.00 | 0.1 % | STABLE (gain adds no modulation) |

## 3. What this proves, and what it does not
Proven on real BASSMIDI:
1. **BASSMIDI's built-in chorus makes a held note wobble**: slow pitch drift (≈ ±3…7 cents at 0.5 Hz) plus ≈ 33 % amplitude beating at ≈ 0.75 Hz — a slow "wa-wa", not a vibrato-speed one.
2. **The chorus amount is additive and saturating: an SF2 chorusEffectsSend generator cannot be turned off from the app with CC93 = 0** (the app's chorus send of 0 is correct and still leaves SF2-supplied chorus). SoundFonts whose presets carry chorus sends will chorus on every note regardless of the RIGHT "chorus 0" setting.
3. **A fast (≈ 8 Hz) vibrato, ±26–33 cents, appears if CC1 or channel pressure is non-zero or the SF2 has a vibrato/mod-LFO pitch generator** — the classic "wawawawa". The app does not send CC1/pressure, so on this path the 8 Hz wobble must come from the SoundFont itself or from a controller state the app does not control.
4. Reverb, identical-layer stacking (RIGHT1+2+3), a 44.1 kHz stream, a neutral pitch bend and the +12 dB Internal Output Gain do **not** create pitch or amplitude modulation here.
Not proven (needs the device and the real SoundFonts): whether Colombo/Tyros/SX Prime presets carry chorus or vibrato/LFO generators or modulators; whether the Oboe stream is at 48 000 Hz on the Redmi Pad; whether the audible effect is the slow chorus drift or an 8 Hz vibrato. Chorus/vibrato depth in the real SoundFonts will differ from the 100 % synthetic case.

## 4. Changes made (diagnostic only, read-only)
* `Sf2PitchFx` + RIGHT DIAG button **SF2 PITCH/FX**: for each enabled RIGHT voice and keys 48/60/72/84 it reads the SoundFont file's own preset/instrument zones and lists chorus send, reverb send, vibLfo/modLfo/modEnv→pitch, LFO delay/frequency, coarse/fine tune, scale tuning and file modulators on those destinations, and flags "SF2 itself requests chorus / pitch modulation". Tests: `tests/cpv/Sf2PitchFxTest.kt` (synthetic files with/without those generators, additive preset values, listed modulators, unreadable input → null).
* Measurement tools: `tests/pitch_analysis.h` (+ self-test), `tests/pitch_stability_real_bass_test.cpp`, logs in the CI artifact.
* No patch to chorus, vibrato, controllers or modulators. Disabling chorus/vibrato globally would remove intended musical character and is not proposed.

## 5. Decision
* **Fix: NO-GO** until the device shows which source it is (a patch now would be speculative).
* **Diagnostic APK: GO** (read-only, default behaviour identical to 702bcf3).
* Candidate fixes, only after the evidence below: (a) if the SoundFont requests chorus/vibrato itself — an optional per-SoundFont/per-voice "SF2 chorus ON/OFF" using BASSMIDI's per-font effect-ignore flag (constants dumped in `bassmidi-header-constants.txt`) while leaving instruments that need vibrato untouched; (b) if the Oboe rate is not 48 000 Hz — create BASSMIDI at the granted rate; (c) if a stray controller is seen — reset it at voice change.

## 6. Device test (short)
1. Install the APK, open RIGHT DIAG, select the Piano voice on RIGHT1 only (RIGHT2/3 off).
2. Press **SF2 PITCH/FX** and send me the amber text: it states whether Colombo's piano itself asks for chorus (`chorusSend`) or pitch modulation (`vibLfoToPitch`, `modLfoToPitch`, file mods).
3. Read the "CONTROLLERS (BASS readback)" line (CC7/CC11/CC91/CC93) and the "APP STATE reverb/chorus" line; press **LOG PROBE** while holding a note.
4. Hold one note ~4 s and say whether the wobble is slow (about one swell per second → chorus beating) or fast (about 8 per second → vibrato). Repeat on Tyros and SX Prime.
5. Open the LATENCY panel and copy the line `Stream opened: sr=…` (expected 48000).
6. Optional A/B: set the RIGHT mixer chorus to 0 and reverb to 0 and repeat; if the slow swell stays, it comes from the SoundFont's own chorus send.
