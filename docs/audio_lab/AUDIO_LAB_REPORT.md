# Offline Audio Lab - root-cause report (RIGHT voices dull / weak)

Branch `lab/offline-audio`. Everything below was measured in CI with the REAL BASS/BASSMIDI Linux x86_64 SDK (BASS 2.4, lib 0x02041203)
and the user's real Colombo (`ColomboGMGS2_BM.sf2`, 273 MB) and Tyros 4 (`yamaha tyros 4_just_t4_fixed.sf2`, 526 MB) files.
Run: https://github.com/pulicarpus/YamahaArranger/actions/runs/39047818807 (artifact `audio-lab-2cf4deb...`: report.md, results.csv, PNG figures, listening WAVs).

## Method
- `tools/audio_lab/audio_lab.cpp` renders float32 stereo WAV at 48 kHz, C4 held 2 s + 1 s release, velocities 30/45/60/75/90/100/127, 18 presets.
  - **raw**: BASSMIDI configured exactly like `ensureEngine` / `applyFonts` (decode stream, NOTEOFF1, SRC 2, 1000 voices, exact one-channel FONTEX2 keyboard mapping).
  - **player**: the production `BassMidiPlayer` code (loadMelody Colombo primary, loadMelodyFallback Tyros, setKeyboardPreset, setChannelMixer 127/64/100/40/0, release 64, noteOn/render, output clamp).
  - Variants: neutral (CC7 127, CC11 127, no FX), factory (no event sent), app RIGHT controllers, and a controller/config sweep.
  - Independent open reference: FluidR3_GM through the identical BASSMIDI chain. TinySoundFont is context only (not equivalent to BASSMIDI).
- `tools/audio_lab/analyze.py`: absolute level metrics are kept apart from level-invariant timbre metrics (centroid, >4 kHz share, transient ratio, 1/3-octave log-spectral distance LSD).
- `tools/audio_lab/sf2_compare.py`: chunk-by-chunk proof of what `normalizeMelodySf2` changes.

## Measured facts
1. **Defaults of a fresh BASSMIDI stream** (not assumed): CC7=100, expression(CC11)=127, reverb=40, chorus=0, pan=64, release=64, modulation=0, cutoff(CC74)=64, resonance(CC71)=64, attack(CC73)=64, pitch=8192, channel pressure=0.
   64 is neutral for CC74/CC71/CC73/CC72 but NOT for volume, expression or reverb.
2. **SF2 normalisation** (app copy `*.bassmidi-normalized.sf2`): for Colombo only byte 22 (wBank low byte) of `phdr` records differs; samples (272,609,274 B), pgen/igen/pmod/imod/inst/ibag/pbag/shdr are byte-identical.
   For Tyros nothing differs. The original files are never modified.
3. **Production player == raw BASSMIDI with the app controllers**: max LSD 0.90 dB, max level difference 0.46 dB, median first-100 ms sample error -38 dB (renders isolated per note). The app code path adds no filtering, gain or smoothing before Oboe.
4. **What the app sends on RIGHT** (CC7 127, CC11 100, pan 64, reverb 40, chorus 0, release 64, font volume 0.9) versus neutral: **-5.0 dB** (median) = CC11 100 (square law, -4.16 dB) + font volume 0.9 (-0.92 dB). Equal to the GM default (CC7 100, CC11 127). Timbre: reverb 40 (BASSMIDI's own default) adds energy (centroid +50 Hz); nothing darkens.
5. **Controllers the app never sends** cannot explain dullness: CC74 can only darken (values 0-63; 96/127 change nothing), CC71 127 adds a resonance peak (LSD 4-5 dB), CC73 127 slows the attack by 75 ms, CC1/channel pressure add vibrato (45 cents) - none of them are sent. Pitch bend centre = identity. Sample-rate-conversion setting 0..3 changes centroid by <= 1 Hz.
6. **Velocity**: Tyros presets keep the SAME timbre at every velocity (only level changes: about 4 dB per step) - velocity can never brighten them. Colombo piano goes 547 Hz (v30) -> 850 Hz (v90) and saturates at v >= 90; Colombo steel guitar goes 538 -> 1284 Hz (>4 kHz share of the attack 0.01 % -> 11.8 %).
7. **Against an independent GM reference through the same chain** (T7): attack centroid median Colombo -0.01 octave (equal), Tyros -0.38 octave (9 of 15 presets darker than -0.3 octave; steel guitar -1.40, nylon -1.18, strings -0.93, brass -0.93). Grand piano share of energy above 4 kHz in the attack: FluidR3 3.33 %, Colombo 0.13 %, Tyros 0.10 % (about 26-33x less).
8. **Onset** (-10 dB of peak): Colombo grand 3 ms vs Tyros 10.5 ms (FluidR3 4 ms); Tyros E.Piano 18.5 ms, organ 16.5 ms, nylon 18 ms, strings 80 ms (FluidR3 strings 52 ms). Early/late energy ratio of the Tyros grand -5.4 dB, Tyros E.Piano -10.5 dB (soft attack by construction of the preset).
9. **Stereo / mono**: Tyros grand piano L/R correlation -0.34 (summing to mono loses 4.5 dB), brass 0.13 (-2.5 dB); Colombo >= 0.66. FluidR3 grand is -0.05 (-3 dB), so this is a property of some wide presets, not specific to the app.
10. **Level / headroom** (RIGHT app config, velocity 100): single C4 peak -18.8 dBFS (Colombo grand), -22.8 (Tyros grand); 4-note octave stack -12.6 dBFS -> 12.6 dB of headroom (7.6 dB even with neutral controllers).
11. **Pitch**: peak-frequency drift median 6 cents, no controller-induced vibrato with the app's controllers.

## Reading of the evidence
- No app-side misconfiguration of BASSMIDI, no controller, no velocity mapping and no preset mapping error darkens the RIGHT voices. The app path is a faithful BASSMIDI render. [measured]
- What is measurable and relevant to "closed / not punchy / weak": (a) the RIGHT level sits 5 dB below neutral and about 19 dB below a mastered track (peak -19 dBFS for one note); (b) Tyros presets are darker and slower than a standard GM reference and ignore velocity for timbre; (c) Colombo grand/bright piano carry very little energy above 4 kHz and need velocity >= 90. [measured; perceptual impact NOT proven]
- Not reproducible here: Android audio output (Oboe/AAudio, shared MMAP path, system sound effects), the speaker, and the PSR-E343 velocity distribution. YouTube normal while the app is dull would be consistent with the device applying its sound enhancement to normal media streams but not to the low-latency stream. [hypothesis, unproven]

## Logging audit (NORMAL / DIAGNOSTIC)
- Measured on device: ~0.6 ms CPU per `DebugLog.add` on the MIDI thread, 3-4 per note.
- NORMAL (default): Kotlin per-note logs off (USB parse RAW/IN, RIGHT/LEFT IN/OFF, engine NOTE trace), native per-note messages (`AUDIO PATH/LIVE/SUMMARY/CC/OFF`, `DRUM PATH`, `NOTE ZONE OBSERVED`) dropped before formatting, and the per-note coverage lookups skipped. DIAGNOSTIC restores everything (panel LATENCY -> LOG MODE). Never persisted.
- Audio callback: `onAudioReady` and `render()` contain no logging or string building (source-guard tests); the only log in `render()` is the decoder-failure message, now rate-limited (first 3, then every 512th).
- Tests: native (mode counters: NORMAL emits 0, DIAGNOSTIC 8; identical MIDI events to BASSMIDI in both), JVM (engine/MIDI OUT calls identical, defaults, no persistence, callback hygiene).

## Gate before any APK: NOT met
No specific, evidence-backed fault in the app chain exists to build a candidate on. No APK was built. Next, without logs: the listening test below.

## Listening test (no logs, no APK)
Download the artifact, copy `listen/*.wav` to the Redmi Pad and play them in a normal media player at your usual volume.
1. `colombo_GrandPiano_v100_PLAYER_app.wav` (what the app renders before Oboe) vs `fluidr3_GrandPiano_v100_PLAYER_app`/`RAW_app` (independent GM piano, same chain). Which sounds closed?
2. Same for `tyros_*`, `colombo_SteelGuitar`, `tyros_Strings`.
3. Play `*_RAW_neutral.wav` (5 dB louder) and `*_RAW_app.wav`: is "weak" a level matter?
- If the WAVs sound good in the media player but the app sounds dull -> the cause is after the app's PCM (Oboe/AAudio path, system effects). Next candidate: A/B of the output stream mode (low-latency vs standard path).
- If the WAVs also sound dull -> content/level of the SF2 presets; next candidates are voice choice and a RIGHT level headroom option (headroom is 12.6 dB).
