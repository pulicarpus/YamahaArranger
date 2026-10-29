# RIGHT audio fidelity audit (velocity, volume, instrument quality) — experiment, NOT a merge

Branch `experiment/right-audio-fidelity`, baseline `f5f6d71` (RIGHT USB-MIDI probe + Touch Response, CI run 40790781034 green with APK). Engine unchanged: BASSMIDI + Oboe. No merge to main, no PR.
Real-device facts supplied by the user (Redmi Pad, PSR-E343, ColomboGMGS2_BM.sf2): E343 median velocity 48–57; B: FIXED 100 makes RIGHT loud at normal touch; internal output much quieter than MIDI OUT to the keyboard synth; BASSMIDI instruments sound "raw".
**Host numbers below come from real BASSMIDI (Linux x86_64) on SYNTHETIC SoundFonts that only carry SF2-spec default behaviour. They show how BASSMIDI itself behaves; they do not measure Colombo, the Redmi Pad speaker or Oboe, and are not a substitute for listening tests.**

## 1. Path evidence: USB vs Virtual Keyboard
(Details and tables: `docs/RIGHT_USB_VELOCITY_REPORT.md`, `tests/rtdiag/RightUsbVelocityTest.kt`.) USB bytes → parser → `onNoteOn` → Brain → `startRightNote` → engine → native `lround(v*127)` is lossless 1..127; velocity is applied once (RIGHT DIAG "USB RAW / FWD" lines show raw vs forwarded); MIDI OUT gets the raw value; split/LEFT/ACMP do not alter RIGHT velocity (ACMP chord keys never reach RIGHT, LEFT keeps its own raw velocity). CC1/7/11/71/74 from the keyboard are not forwarded to BASSMIDI. The Virtual Keyboard sends a fixed 100. The only difference between the paths is the velocity value itself.

## 2. Why low velocity is quiet (measured on real BASSMIDI)
Level versus velocity, steady RMS relative to velocity 100 (identical for all four synthetic instruments):

| velocity | 20 | 40 | 48 | 57 | 64 | 80 | 100 | 127 |
|---|---|---|---|---|---|---|---|---|
| dB vs 100 | −28.0 | −15.9 | **−12.8** | **−9.8** | −7.8 | −3.9 | 0 | +4.2 |

The E343's typical 48–57 therefore arrives **10–13 dB below** what the Virtual Keyboard (100) or FIXED 100 produce. That alone explains "FIXED 100 is loud at normal touch" and "a hard press sounds clearer". It is BASSMIDI's SF2-standard velocity→attenuation response, not an app bug.
* **Attack:** the envelope *time* did not change with velocity (Strings: 140 ms to half of its own steady level at every velocity). A slow-attack voice that is 13 dB lower stays under the audible level longer, so it *feels* slower/held. A velocity→filter or velocity→attack modulator **inside Colombo** (not present in the synthetic fonts) could add real darkening/slowing: **unproven**, needs the Colombo preset zone data (existing `sf2_zone_diagnostic` / RIGHT DIAG preset readback on the device).
* **Cause ranking:** (1) raw E343 velocity low × SF2 velocity curve — proven for level; (2) possible Colombo velocity layers/filter — unproven; (3) the E343's own TOUCH setting — unchecked; (4) input curve in the app — ruled out.

## 3. Volume / gain findings
| item | finding | evidence |
|---|---|---|
| stream `BASS_ATTRIB_MIDI_VOL` | set to 1.0 at stream creation; `setMasterGain` clamps to ≤ 1.0 | `bassmidi_player.cpp` 131, 1552-1558 |
| master UI | Kotlin accepts 0..127 → 0..1.27 but native saturates at 1.0, so UI values 101..127 do nothing. BASSMIDI does accept >1: 1.27 = **+2.1 dB** (measured) | `AudioEngineManager.setMasterVolume`, RFA streamVol |
| font volume | 0.90 (−0.9 dB vs 1.0; 1.5 would be +4.4 dB) | `soundFontVolume_`, RFA fontVol |
| CC7 / CC11 | defaults: CC7 per mixer knob (127 default argument), CC11 127; CC7 127 vs 100 = +4.2 dB, 64 = −7.8, 32 = −19.8; CC11 100 = −4.2, 64 = −11.9. Check the RIGHT DIAG "CONTROLLERS (BASS readback)" line on the device: a CC7/CC11 below 127 costs level too | RFA controller |
| Oboe | BASSMIDI float stereo → clamp ±1.0 → Oboe, no gain stage, no limiter/compressor (a deliberate comment in `audio_engine.cpp`) | `audio_engine.cpp` 227-240 |
| headroom / clipping | synthetic fonts are far from full scale (single note vel127 −45 dBFS; 10 notes × (RIGHT 3 ch + 8 style ch) peak 0.43, 0 clipped samples). **Not representative of Colombo**; real peak/clipped counters come from the existing native PCM headroom telemetry on the device | RFA poly |
| internal vs MIDI OUT | MIDI OUT sends the *same raw velocity* (48–57) to the E343 synth, whose own velocity curve, gain and speaker are different. The two synths should not be level-matched blindly; only a measured internal level on the Redmi Pad can justify a master/font gain change | design |

No gain change was made. The unused +2.1 dB (master clamp) and the 0.90 font volume are candidates for a *separate* patch after a device headroom measurement.

## 4. Reverb, chorus and SF2 character
* BASSMIDI's built-in reverb and chorus **work** in the Linux build: tail energy after note-off CC91 0 / 64 / 127 = −80.8 / −70.2 / −64.7 dB; CC93 0 / 64 / 127 = −73.6 / −71.0 / −66.3 dB; peak also rises with the send. So reverb/chorus are not "dead" (the Android library is the same BASSMIDI; device confirmation pending).
* RIGHT mixer defaults are reverb send 40 and chorus send 0 (`KeyboardMixerDetail`), i.e. light reverb and no chorus. A "raw" impression can be partly this default; it is a per-part setting, not a bug. No global effect or envelope change was made.
* Amp-envelope, filter and velocity→filter behaviour of Colombo cannot be judged without the file; if the rawness comes from Colombo's own samples/envelopes, it is the SoundFont's character. Piano/Strings/Flute/Electric-Bass analogues here only show the BASSMIDI mechanism, not Colombo's sound.

## 5. Proposed change (implemented, isolated)
**Touch Response → Custom** (USB-MIDI → RIGHT1/2/3 only): `out = floor + (127−floor)·(v/127)^γ`, γ 0.30..1.50 (step 0.05), floor 1..100, 127→127, monotonic, min 1, saved with the mode; Original stays the default; MIDI OUT, Virtual Keyboard, LEFT, ACMP, Style, drums unchanged. Unlike FIXED 100 it keeps dynamics (default γ 0.55: 20→47, 40→68, 48→75, 57→82, 80→99, 100→111, 127→127).
Measured effect (real BASSMIDI, level vs velocity 100, from the E343 medians): γ 1.0: 48 → −12.8 dB, 57 → −9.8 | γ 0.60 (Soft): −5.9 / −4.1 | γ 0.50: −4.3 / −2.8 | γ 0.40: −2.6 / −1.4. Start with Custom γ 0.50–0.60 and adjust by ear.

## 6. Tests
* Host (CI, repeatable): `RightUsbVelocityTest` 10 tests (adds Custom curve properties/persistence), CPV, rtdiag, guard `tools/test_right_fidelity_guard.py` (baseline f5f6d71; native/Brain/MIDI/Style/engine byte-identical).
* Real BASSMIDI: `tests/right_fidelity_real_bass_test.cpp` (velocity sweep ×4 synthetic instruments, curves, CC7/CC11, stream/font volume, reverb/chorus tails, polyphony/clipping). Log: `evidence/right-fidelity.log`.
* Not covered (needs the device): Colombo-specific velocity layers/filters, real peak/clipping with Style + RIGHT, Oboe path, audible quality. FIXED 100 vs curve and USB vs Virtual Keyboard consistency were compared at the velocity/level level only.

## 7. Regression risks
Custom curve touches only the USB→RIGHT engine velocity (one table read); a too-low γ makes every note loud and flattens expression; default stays Original. Master/font gain, effects and envelopes are untouched. Rollback: select Original (or reinstall the previous APK `f5f6d71`).

## 8. Staged recommendation
1. Device test of Custom (γ 0.5–0.6) vs Original vs FIXED 100; record the CONTROLLERS readback line and the raw velocity stats.
2. If internal level is still low: measure Colombo peak/clipping with the existing PCM headroom telemetry, then decide a separate gain patch (lift the master clamp to use the +2.1 dB, and/or font volume 0.90→1.0) — never together with velocity changes.
3. Per-voice reverb/chorus defaults only for voices judged dry, via the existing mixer sends.
4. Dump Colombo's zones for the four voices (velocity→filter/attack modulators) to confirm or rule out the SoundFont's own contribution.
