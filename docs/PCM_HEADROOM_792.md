# Device #791 headroom investigation — no new balance correction

Baseline: `3c156ee18f8c15241d76ae7edfa33b105263e26e`, branch `fix/mix-percussion-fidelity-784`. Primary evidence: `YamahaArranger_PartPresence_20261006_185020.txt`; compare device #790 `_164624` and `_164656`. The earlier #791 expected-level table is **not an observed result** and does not describe this device result.

## What is established

All five melodic destinations have accepted notes, verified font/native-bank/program, and non-zero PCM. No new missing-part, mapping, or controller-zero cause is shown. Device #791 reports:

| Part | Dry span dBFS | Peak (float full scale=1) | Samples exceeding 1 |
|---|---:|---:|---:|
| Bass | -36.6663 | 0.0832764 | 0 |
| Piano | -20.0075 | 8.7455 | 4424 |
| Guitar | -30.3635 | 2.82947 | 417 |
| Pad | -44.1146 | 0.0377183 | 0 |
| Phrase1 | -50.554 | 0.0159182 | 0 |
| Whole process mix, different time window | -20.274 | 17.0664 | 8096 |

The existing engine hard-clamps the summed float output to [-1,1]. These over-range samples therefore cause hard clipping, not a uniform attenuation of quiet parts. Piano's dry RMS is approximately 16.66 dB above Bass, 24.11 dB above Pad, and 30.55 dB above Phrase1. Masking is plausible; this report is not proof of an acoustic SPL ratio or that clipping alone makes other parts inaudible. Piano/Guitar crest factors are ~38.84/~39.40 dB: uncommon high transients and average loudness must be investigated separately.

The measured -6/-6/+12/+12 trims are active and settled at export. Their final gain values cannot establish what happened at each earlier peak. The process PCM_MIX peak (17.0664) and the individual role peaks need not occur in the same block. RMS values cannot be arithmetically added: correlation, phase, shared wet effects, polyphony, and differing time windows matter. This report's role window starts at frame 3,588,352, whereas PCM_MIX also includes all earlier rendered audio. Report #791 also has different note counts/CC11 ranges from #790. Direct before/after subtraction is not a controlled response test.

## SF2 evidence, across the relevant complete presets

Full metadata export (not only last-note ROLE_LAYER) establishes:

- MELODI bank0/rawPC0 Piano: 104 zones, two eligible layers per mapped key (21–108), pan -500/+500. Samples labelled L/R are type1/unlinked; treat them as two mapped mono layers, not a guaranteed linked stereo sample. Preset attenuation -200 cB, long decay, release 441 timecents, mostly looped samples. Negative attenuation can boost/flatten effective velocity response; earlier real-BASS identical-sample tests showed a high-velocity plateau. Neither that test nor names prove the peak of the real PCM.
- MELODI bank8/rawPC1 Guitar: 16 zones, one/two layers depending on key. Acoustic layer fixes velocity127, while SteelGtr retains ordinary note velocity; preset attenuation150 cB, some instrument zones add30 cB. Preset/instrument reverb/chorus generators, pans, envelopes and loops differ by layer. The dry/wet ratio is therefore not inferable from CC91/93 alone.
- MELODI bank8/rawPC17 Bass: eight zones; two eligible zones across mapped keys, including duplicate sample875 for the last device key. This is evidence of layering, not proof that both samples have independent energy or that a gain is missing.
- Tyros bank0/rawPC49 Strings: 38 zones, two eligible L/R-named mono layers over keys23–120, pan -499/+499, attenuation0, attack -2084 timecents (~300ms), looped samples and release1365. Original low velocity, authored expression, note duration/density and sample response contribute to low RMS. No application gain floor is justified.

The actual sample data from those device SF2s is not available in Cloud. Local small files carrying those filenames are synthetic preset-contract fixtures, not those fingerprinted SoundFonts. Do not call synthetic tests reproduction of the exact device waveform.

## Rendering audit and remaining uncertainty

Path: SF2 voices/layers -> per-channel dry float stream -> #791 fixed trim DSP (priority1) -> read-only ROLE_PCM (priority0) -> BASS parent with shared FX -> separate percussion-lane additions -> PCM_MIX -> existing AudioEngine hard clamp -> device output/physical volume. BASS_MIDI_StreamGetChannel documentation confirms float format and dry-before-shared-FX semantics; no child GetData is used.

There is one existing scalar DSP per melodic channel and one sum per percussion lane. Controller values and master are not newly written here. Runtime trim ramp never intentionally exceeds its old/target gains. Tests with production #791 code, real Linux BASSMIDI, Android-style -ffast-math, polyphony, release/loop fixtures, preset refresh and FX did not reproduce the device's exceptional peaks. This rules out those tested mechanisms in those fixtures; it does not prove the device samples, Android SDK, or performance are safe.

**The exact source of the 17.0664 peak remains unresolved.** Current evidence does not distinguish an already-over-range native channel input from a DSP-stage anomaly, parent FX/other voices, or the final lane sum. Do not blame one SF2 generator, remove valid layers, change CC/velocity, or add arbitrary attenuation/limiting based on this snapshot.

## Minimal patch

Read-only stage accounting, compiled with ROLE_PCM diagnostics (separately disable via YAMAHA_PCM_HEADROOM=0):

- Capture input of the existing trim DSP, and existing priority0 meter output. Untrimmed channels use identical pre/post observations. No new DSP, channel stream, NOTE, route, controller or BASS render/query call in the audio callback.
- Synchronized parent/individual percussion-lane/sum statistics for each successful decode. Record a bounded worst summed block and worst block per melodic role, keyed by decode/frame/generation. The report prints role worst blocks only when over range.
- Record trim gain range and pre/post sample-count agreement; don't infer trim correctness from a final target value alone.
- Calculate what the existing hard clamp does to level, **without changing the output buffer**. Label this `clamp_model`, not a second output meter. Current master getter is export-only and labelled snapshot-time, not peak-time.
- Numeric fixed-size accumulators only; no allocation, strings, file I/O, SDK getters or logging in render. Report formatting happens after the existing short snapshot lock. Bitwise nonfinite checks work under -ffast-math.

All #791 gains, ramps, actual clamp, MIDI events, mappings, resource lifecycle, percussion, CASM, ACMP, scheduler and routing remain intact. Removing only SHA-enumerated observer blocks recovers exact #791 C++ source/header. This is an evidence build, **not a claim that balance is fixed**.

## Verification and next device run

Tests compare actual production PCM and MIDI byte-for-byte for verified #791 source, current observers OFF, and current ON with real BASSMIDI, matched trim identities, 240 controller/velocity response combinations, polyphony and shared FX. A deliberate high-polyphony synthetic phase exercises over-range floats. Observer unit tests verify pre/post gain, synchronized stages, bounded snapshots, reset, hard-clamp model and nonfinite handling under fast-math. Existing full suites and Android ABIs remain mandatory; Telegram workflow unchanged.

Fresh app session, same SF2/style/mixer/master, play Main D/chord C for 20–30s, STOP, SAVE PARTS (SMALL). Record any section/chord/master changes. No need to change physical volume to get pre-device PCM evidence.

Interpret HEADROOM_ROLE with HEADROOM_WORST for the same decode: pre already high => upstream native/sample/layer/polyphony issue; bounded pre but invalid post/gain relationship => trim-stage issue; roles bounded but parent high => shared FX/unmetered parent channels remain candidates; parent bounded but sum high => inspect recorded lane contributions; high sum with clamp_model=1 => actual existing output saturation. Do not sum separate temporal maxima or call parent-minus-roles an isolated wet signal. The next report localizes the stage; actual sample/voice attribution may still require an isolated controlled audition if the input is already over range.
