# Working #786 presence: measure balance before correcting gain

Baseline build #786 / ee833203fc8c37091549a457f8e99cc08c60ab7b, branch fix/mix-percussion-fidelity-784. Device confirms all parts are audible and ACMP works. Missing accompaniment is closed for this milestone. Piano/Guitar dominance remains a device observation, not a Yamaha-exact timbre judgement; no PSR-E343 comparison is available.

## Evidence gap and decision

No #786 PCM_MIX/STYLE_VELOCITY export or original four SF2 PCM files accompanied the new device result. The existing meter contains summed output, not separated role PCM. Input velocities and static SF2 attenuation do not determine actual output loudness. Therefore no evidence-based gain correction can currently be calculated. This build adds the minimum role PCM measurements and does not change gain, floors, MIDI velocity, CC7/CC11, mixer trim, fonts, source generators, CASM/NTR/NTT/RTR, ACMP, scheduler or the drum resolver.

Previous audit evidence remains relevant: Piano preset has negative preset attenuation; Guitar has a fixed-velocity layer; slow Strings has about 300 ms attack; authored part velocities/controllers differ. These are causal hypotheses for relative output, not instructions to overwrite valid SF2 layers or equalize all part RMS. Arrangement density and note duration also affect role energy.

## Native read-only measurement

BASS_MIDI_StreamGetChannel plus BASS_ChannelSetDSPEx(BASS_DSP_READONLY) taps the actual production dry PCM for melodic destinations 10..15 only. The dry signal includes MIDI controls and master volume (verified in a real BASS probe), and excludes shared reverb/chorus tails. It is not a wet role stem or an isolated per-note response measurement. Main PCM_MIX remains the post-sum measurement.

Rhythm1/Rhythm2 and RIGHT/LEFT are never tapped. BASS documentation explicitly states that activating a drum channel stream ignores individual drum-key FX sends; excluding those streams is necessary to preserve percussion behavior. No note is replayed or duplicated; no BASS_ChannelGetData is called on a child stream. Callback reads const floats and performs bounded counters only, without allocations, filesystem access, locks, logging, MIDI or font-map writes. Taps are created during existing preset/font configuration, never per NOTE_ON or render. Owned DSP/child handles are removed before the original parent stream is freed. Tap failure leaves production notes/routing untouched.

Debug device APK enables YAMAHA_ROLE_PCM_METERS; release defaults OFF. MIDI routing/controller methods are frozen against #786, allowing only the explicit measurement call sites. Existing #784/#785 and old Stage 3 exclusion guards remain in force. No Java playback code is changed.

## Report fields and limitations

Existing Inspector SAVE PART PRESENCE includes:

- ROLE_PCM_SCOPE / ROLE_PCM_STATUS: six safe destinations, readiness and failures, up to eight windows per channel, omission counts.
- ROLE_PCM: SF2/fingerprint, source raw bank and physical native bank, raw PC, requested and selected names; preset readback only after the first actual accepted note; generation at capture start.
- Per window: shared rendered start/end frames, callback samples, active-block samples, RMS over the full window, RMS over callbacks/active blocks, dBFS, peak, clipping and nonfinite counts.
- MIDI input: accepted/rejected/other note counts, velocity P10/P50/P90/mean, CC7/CC11 ranges (initial accepted note plus every subsequent sent CC event, including automation with no new note), last key/velocity. Original events are not modified.
- ROLE_LAYER: at most twelve eligible layers for the last recorded style note, sample/instrument names and IDs, stereo links, key/velocity ranges, original/override root, fixed velocity, preset/instrument attenuation and attack timecents. Metadata eligibility is not proof of the actual sounding sample voice.

Preset changes open another bounded window. priorPresetTailsPossible is true if earlier sound/notes might overlap; those windows cannot prove isolated preset loudness. Failed first-note readback remains unverified. An omission or unsupported/tap-failed role remains UNKNOWN, never silence-as-zero or a justification for gain. Compare full-window RMS only over comparable timing; active-block RMS is supplementary and includes release tails. Dry role energies cannot simply be added to reconstruct wet mix energy due to phase/cross terms and shared effects.

## Real SDK validation

A single-channel probe was PCM bit-identical ON/OFF, both with effects disabled and enabled. A broader six-role production-player test, including multi-SF2, normalized raw bank 8 (physical bank 0 in its one-bank synthetic font), repeated/simultaneous notes, NOTE_OFF, expression/volume automation and reverb/chorus, detected float summation roundoff when BASS activates channel streams: maximum 2.235174179e-8, RMS error 2.290349712e-9 (-172.8 dBFS), relative RMS gain error 1.545128470e-10. It is NOT byte-identical PCM. Regression enforces a float32 rounding bound and an independently tight RMS gain bound, and fails for a larger change. Complete MIDI routing/controller event traces are byte-identical OFF/ON. These synthetic tests validate instrumentation, not device balance or Yamaha-exact tone.

Production-player mock tests verify no extra NOTE_ON/export writes, const buffer handling, controller/velocity preservation, physical first-note verification, bounded windows/omissions, failed tap setup preserving production notes and cleanup. CI runs the entire JVM suite, host/native suite, actual BASS SDK proofs, six baseline event trace comparisons and both ABIs; old Stage 3 remains excluded.

## Device procedure and next decision

1. Start a fresh app session; load the same managed SF2s and Love Song configuration. Keep the working mixer settings; do not normalize volumes.
2. Play the same Main section for 20–30 seconds with a steady chord. Avoid switching fonts/presets during this measurement; note the section and existing mixer trims.
3. STOP, open SF2/STYLE Inspector and use existing SAVE PART PRESENCE. Send the resulting TXT with ROLE_PCM and ROLE_LAYER lines.

No reference Yamaha recording or repeat of the six old audition WAVs is needed for this output-level check. The returned per-role output, density, controllers, velocity profile and layer metadata will distinguish an application control error, clipping, SF2 response/envelope differences and authored density. Correct only a demonstrated application/response defect; do not equalize all roles or choose a gain offset solely because a part is louder. Exact Yamaha balance remains unproven without a reference.

## Changed implementation

bassmidi_player.cpp/.h; new role_pcm_meter.h; debug Gradle/CMake flag; mock/actual production-player tests and test runner; reviewed patch/protected-source manifests; CI #786 comparison and proof artifact. No routing, CASM, scheduler, ACMP, decoder, melodic selection, drum mapping or controller arithmetic changes.

References: https://www.un4seen.com/doc/bassmidi/BASS_MIDI_StreamGetChannel.html and https://www.un4seen.com/doc/bass/BASS_ChannelSetDSPEx.html
