# Measured accompaniment mix, device #790 → #791

Baseline commit 90037c28914614e1597fb498ac7d2a2e86841d57, branch fix/mix-percussion-fidelity-784. Primary evidence: device reports YamahaArranger_PartPresence_20261006_164624.txt and _164656.txt. No new callback investigation: output callbacks, successful parent decoding and per-role PCM are proven in these reports.

## Proven cause versus limits

All measured parts have verified actual font/bank/PC, accepted notes, non-zero controllers, successful rendering, no clipping/send/map failures. No application CC reset, missing voice, gain-floor or routing bug is demonstrated. The audible imbalance is real, repeatable cross-preset output under the authored arrangement. Its contributors are different style controls/velocities/density plus SF2 layer/envelope response, not a single erroneous channel volume.

Piano: CC7=81/CC11=127, velocity mean≈72, 183→281 notes, two eligible Piano layers, preset attenuation -200 cB. Guitar: CC7=79/CC11=110, mean≈41, 443→684 notes; an eligible Acoustic Guitar layer fixes velocity to 127 while a SteelGtr layer retains note velocity. Bass: CC7=52/CC11=127, mean≈59, 49→75 notes. Pad/Phrase: CC7=62, original velocities 26/≈33.5, fewer sustained notes, differing CC11 and samples, slow attack (-2084 timecents ≈300 ms).

The earlier actual-BASS controlled response experiment showed negative attenuation can reach a full-level velocity plateau, and fixedVelocity=127 flattens that layer's note velocity response. That supports a response mechanism; last-note eligibility is not proof of every actual sample/layer contribution in the device mix. These reports do not isolate neutral per-note response or establish Yamaha-exact levels. Static trim cannot restore a velocity curve already flattened inside an SF2; it preserves its existing response and all authored MIDI values rather than rewriting generators/velocities.

## Deliberate, bounded first-pass mix decision

Do not equalize RMS or compensate quiet passages with AGC. Use Bass as an unchanged reference, keep chord beds louder than Bass, keep slow Pad and sparse Phrase behind it, and preserve the two Strings parts' relative level by applying the same binding trim. Measured gaps are ~12 dB Piano/Bass, ~10 dB Guitar/Bass, ~18 dB Bass/Pad, ~25 dB Bass/Phrase. A -6/-6/+12 dB first pass narrows those extreme gaps while retaining approximate +6/+3/-6/-12 dB role relationships to Bass. These are explicit empirical mix choices grounded in the measured/device-audible imbalance, not mathematically recovered Yamaha gains or universally calibrated instrument loudness.

| Role | #790 measured dry span dBFS (two reports) | Fixed trim | Expected same-performance dry dBFS |
|---|---:|---:|---:|
| Bass | -40.36 / -40.92 | 0 | -40.36 / -40.92 |
| Piano/Chord1 | -27.98 / -28.46 | -6 | -33.98 / -34.46 |
| Guitar/Chord2 | -30.69 / -31.18 | -6 | -36.69 / -37.18 |
| Strings1/Pad | -57.98 / -58.49 | +12 | -45.98 / -46.49 |
| Strings2/Phrase1 | -64.87 / -65.37 | +12 | -52.87 / -53.37 |

No device AFTER measurement exists yet. Projections refer to settled dry channel PCM under the same performance, not acoustic SPL or wet FX balance. A 20 ms boundary ramp prevents abrupt gain steps on reconfiguration. Measured peaks leave ample headroom for this performance: Piano≈-8.9→-14.9, Guitar≈-11.6→-17.6, Pad≈-42.6→-30.6, Phrase≈-50.8→-38.8 dBFS. No compressor, limiter, global gain or volume floor is added. Other styles/sections remain subject to device verification; these snapshots do not prove headroom for every possible high-polyphony performance.

## Implementation

Generic fixed response-trim mechanism with evidence registry keyed by **full SF2 SHA256 + source raw bank + raw PC**. No preset names, file names, Love Song, MIDI key or hardcoded channel volume select a gain. Entries cover only the exact measured MELODI Piano(0:0), Guitar(8:1), and Tyros slow Strings(0:49) content; unknown/changed SF2 or unverified native binding stays unity. Same preset on Pad/Phrase receives the same gain. Bass remains unity.

Scope is accompaniment melodic destinations 10–15 only. Rhythm, keyboard/RIGHT/LEFT, ACMP chord detection, CASM, scheduler and routing are untouched. Calibration happens after existing preset/resource configuration, not per NOTE_ON. Native BASSMIDI child DSP uses priority 1 to multiply stereo PCM by a fixed scalar before the existing read-only priority-0 role meter. No NOTE_ON/OFF, velocity, CC7/CC11, pan, FX-send, root/pitch, SF2 generator, FONTEX2, font replacement or MIDI-owner mutation. The trim is independent of diagnostic meter ON/OFF. No heavy per-note logging/search/I/O.

Child handles are shared with the existing role meter when present, with one owner at teardown. Meter DSP is removed first, gain DSP second, child once, then the unchanged parent/resource lifecycle. Setup failure leaves the binding untrimmed. Profile changes ramp toward unity/new gain; old note release tails on that same channel can share the boundary ramp, rather than acquiring new note owners or a second production stream.

MEASURED_BALANCE export records fingerprint, trim/target/current gain, evidence and setup error. ROLE_PCM measures post-trim dry output; existing controller/velocity/layer evidence remains unchanged.

## Regression

Actual production BassMidiPlayer with real Linux BASSMIDI tests 240 response combinations: CC7 52/81; CC11 0/49/83/110/127; velocity 26/39/75/127; keyboard, Rhythm and four accompaniment roles; multiple SF2s and a fixed-velocity fixture. A friend fixture injects measured fingerprints into deterministic synthetic files only in tests; production profile selection/DSP/native controller/NOTE methods are exercised unchanged. Confirm the exact scalar output ratio at every non-zero combination, zero expression silence, byte-identical CC/NOTE trace, unchanged keyboard/Rhythm output, different fingerprint→unity, repeated/simultaneous notes and teardown. Repeat with the diagnostic meter OFF: correction must remain identical.

Existing full native/JVM/transition/owner/CASM/ACMP/multi-SF2 tests and all baseline traces remain mandatory. Guard old MIDI/state methods by removing only hash-enumerated trim integration blocks; no general waiver. Old Stage 3 stays absent. Preserve the successful Telegram workflow; delivery failure makes CI fail.

## Device test

Use the same configuration/Main D/chord/physical volume and unchanged app mixer. Play 20–30 s, STOP, SAVE PARTS (SMALL). Check MEASURED_BALANCE active=1 and -6/-6/+12/+12 on the corresponding bindings, original CC7/CC11/velocity evidence, non-zero ROLE_PCM, and clipping counters. Listen to balance and transitions, including Main/Fill/Intro/Ending. Reassess measured output before another trim revision. No Yamaha-exact claim.
