# Actual note zones / drum audition diagnostic — 2026-10-01

## Identity and purpose

Repository pulicarpus/YamahaArranger, branch `diag/audio-path-presence`, parent checkpoint `e7049cc4bca5ef87a421a064c322284d4ae138b1`. Baseline APK #758 source `1fd6a820555b93b9e8a11bb2faa5261b4286ff92`. This is a diagnostic candidate, not a sound fix. Published source/run/artifact identity is recorded after CI completes below.

The user explicitly authorized obtaining missing Bass/Strings SF2 zone/sample/generator evidence, comparing all21 dedicated drum kits at actual style key/velocities, optional isolated audition, and a reliable Strings2 solo procedure. No tuning, automatic kit replacement, note remap, gain increase, resolver/CASM/scheduler/sustain changes are authorized or implemented.

Previous runtime and listening evidence remains in BUILD758_RUNTIME_CHECKPOINT_20261001.md and BUILD758_WARM_SOLO_CHECKPOINT_20261001.md. Warm note dispatch/controller evidence was already sufficient; another unchanged AllLog would not expose melodic samples/envelopes or candidate drum timbre.

## Actual Bass/Strings note evidence

Metadata for primary and secondary melody fonts is parsed from original font bytes already read by the existing preset-cache loader. Original bank numbers remain in the metadata; live normalized banks from BASS are reversed through the existing bank map. No file read, SF2 parse or zone scan occurs in noteOn or render.

After the existing NOTE_ON send, the observer for channels10/13/14 reads actual BASS preset/font and controllers. It captures channel/key/velocity/id, raw/normalized bank/PC, sent status, CC7/CC11/pan/reverb/chorus/release controller, BASS font volume, stream MIDI volume and whole-font sample-memory info. It retains an immutable metadata reference. At most512 distinct channel/font/preset/key/velocity/controller/sent signatures are retained; report says limitReached explicitly. Reload clears observations; use a warm run after all fonts finish loading. Other existing AUDIO PATH counters remain available.

Inspector SAVE REPORT scans the captured identity against cached metadata and exports:

- matching zone count and every eligible layer, original preset/instrument bag index, sample ID/name, key and velocity ranges;
- sample frame/rate/loop/type/link/root/pitch-correction header data;
- effective static attenuation in centibels and a labelled static gain proxy;
- volume envelope delay/attack/hold/decay/release timecents, sustain centibels, key scaling, filter cutoff/Q, fixed key/velocity, loop/exclusive class/root override;
- raw preset and instrument generator lists, and custom modulators with source/destination/amount/amount-source/transform.

Local generators override their own global generators; instrument absolute/default values plus preset offsets are reported. Identically addressed local modulators replace their own global modulator, including zero amounts. Preset/instrument modulator evidence is kept separate. Omitted/invalid modulator tables are marked unknown rather than invented.

**BASS exposes active font/preset and controllers, but no per-voice selected sample/zone ID.** These are all eligible SF2 layers at the actual event, not a claimed BASS voice introspection result. Reports label this distinction. Static attenuation/envelope values are not evaluated velocity modulation or measured PCM gain. SF2 default modulators and engine overrides are not fully enumerated/evaluated by this parser; custom modulator records and raw generators permit targeted follow-up. Sample eligibility does not establish valid PCM bounds, sample readiness, attack audibility or timbre. No sample waveform is read by the observer.

The normal note path adds bounded getter/identity capture overhead. Formatting and all zone/generator scans occur only at export, outside the synth mutex. Cache parsing adds font-load metadata work; STOP and wait for all font attachments before the test/export.

## Drum kit evidence beyond ranking

Existing21-kit MainD report is retained with the same raw213-hit profile for Love Song. Each exact KIT VELOCITY bin now also has KIT ZONE DETAIL for every eligible sample layer, including the generators/modulators above. Key31/110 and key21/28,42 therefore show precise candidate layers rather than just total coverage score. Empty metadata remains unknown; no kit is selected by this report.

A second report combines all parsed style sections, counted once each. Section profile headers preserve attribution and requested kits; exact source key/velocity bins are canonicalized by the existing native reporter. This covers MainA/C/D, fills and other parsed sections without treating sample log counts as a complete inventory. An ambiguous rhythm destination makes the combined profile explicitly unavailable, rather than guessing. These are source-section events, not proof that every conditional CASM path was played. Existing MainD baseline denominator stays separate from the all-section denominator.

No drum key transform, requested PC, fallback, FONTEX2 priority or resolver policy is changed.

## Explicit STOP-only audition

Inspector has editable Bank/Kit PC/Key/Velocity and AUDITION (STOP). Fields initially show128/1/31/110 as an editable test event, not an installed preset. Only a key/velocity present in the loaded style can be submitted. Native code also requires an eligible zone in the dedicated cache.

Audition creates a separate one-channel BASS MIDI decode stream mapped directly to the selected dedicated source bank/PC. It sends exactly one requested note, checks live preset identity, decodes two seconds, logs peak/RMS and returns stereo PCM16 WAV. The temporary stream is freed on success/failure; MediaPlayer plays the temporary WAV and cleans up. Failed/unknown zone or live mismatch returns no audio rather than falling back. No audition MIDI message is sent on the arranger stream or external MIDI.

It uses default stream controllers and the loaded font's existing volume. It does not normalize, boost, install a kit, remap a key, modify the arranger mapping or touch synth channel state. WAV conversion clips outside[-1,1] to PCM16; peak/RMS are measured before conversion. Audition output is a separate Android MediaPlayer path, so its loudness is not directly comparable to live Oboe output. Shared font sample cache can warm during audition. Do this only while STOP, fonts are stable and no keyboard notes are being played. Decode holds the synth mutex to prevent font unload during shared-font use; this explicit action is not part of normal playback.

Audition is a timbre comparison tool. A kit having coverage or a pleasant sample does not by itself prove exact Yamaha PC73 compatibility. Test backbeat31, timekeeper21, kick33 layers and missing54 before proposing a musical mapping.

## Strings2 solo procedure

Inspector SOLO STRINGS2 explicitly unmutes ch14 and mutes other style channels8–13/15 using the existing mute API; UI state and a DIAGNOSTIC SOLO log record are updated. It does not toggle based on a potentially already-muted state. Wait a complete new MainD section so the two Strings2 noteOns actually occur. Existing mute controls restore the mix.

The existing mute/unmute helper uses override controllers (commonly volume127), as already established in the warm checkpoint. This diagnostic action does not fix/change that helper. Check actual controller values in the new report; do not assume raw style volumes were preserved by a solo action.

## Regression and validation

Local host suite passes322 checks:210 family policy,45 actual native/mock routing,22 observer,30 SF2 metadata and15 drum coverage. Added synthetic fixtures prove low/high velocity layers, signed ADSR values, global/local/preset attenuation composition, modulator cancellation, sample header export and explicit BASS introspection limits. Native tests exercise original-bank live observation, getter-only export, isolated audition WAV creation/cleanup, invalid-zone/mismatch/note-failure refusal and unchanged arranger events/fonts. Mock audio proves control isolation, not real SDK ABI/audio timbre.

14 existing native functions are byte-identical: findMelodicPreset, findDrumPreset, normalizeMelodySf2, preloadCurrentPreset, ensureEngine, setChannelMixer, setChannelExpression, setKeyboardSustain, setKeyboardReleaseTime, render, noteOn, noteOff, setChannelPreset, applyFonts. Entire StyleSequencer is unchanged, including provenCC11 fix, family/CASM gates, scheduling, ownership, Main/Fill/timing. SF2 loading functions only add metadata state. Native libraries and build workflow remain unchanged.

Kotlin targeted CI classes now contain11 tests (existing5 expression-controller tests plus6 drum-profile tests). The new profile test verifies an explicit FillDD section's key/velocity histogram without assuming MainD. Local Kotlin compiler/Android SDK is unavailable; real compilation and targeted JVM results must come from CI before claiming an APK.

The workspace is a partial text inspection snapshot; local git HEAD is not a remote SHA. Publication uses the complete remote base tree, preserving all unedited/binary paths.

## Short Android test

1. Install the successful diagnostic APK recorded below. Load the same four SF2 files and Love Song; wait for all attachments. Clear AllLog and play MainD for at least two full sections. Play MainC/FillCC/FillDD briefly if desired.
2. Open Inspector, tap SOLO STRINGS2, return to MainD and let two full sections play. Record whether Strings2 is silent/weak. Restore mix with existing mute controls; STOP and SAVE REPORT.
3. While STOP, audition candidate PCs1/24/36 at31/110,21/28 and42,33/59 and94,54/110. Keep bank128; use report to see unavailable zones. Note which sounds like a suitable backbeat/timekeeper/kick/percussion. No kit is installed by this test.
4. Export complete AllLog and Inspector. These now contain melodic layer/envelope evidence,21-kit exact layer evidence and isolated audition peak/RMS. No SF2 upload is required for this test.

## Published APK evidence

Pending candidate source commit and CI run. Do not claim build success or improved sound until verified.
