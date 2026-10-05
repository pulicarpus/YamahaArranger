# After #785: mix-state correction and percussion identity protection

Baseline: build #785, `130cc20c79284892142f742dbf5edc2d5c7e31a4`, branch `fix/mix-percussion-fidelity-784`. Device evidence: YamahaArranger_PartPresence_20261005_203133.txt. This patch preserves successful accompaniment presence, ACMP, bank normalization and melodic routing. Old Stage 3 remains excluded.

## What the device evidence establishes

All present parts reached native dispatch: Ch8 636, Ch9 518, Bass 126, Piano 508, Guitar 740, Strings 139, Phrase strings 10. Percussion adapter accepted 845 compatible notes; 309 retained legacy routing. No failed/overflowed adapter notes were reported. These are runtime counts with repeats, not the single-pass 1,054 source demand count.

Ch8 actually played distinct tambourine, cabasa, clave, mute triangle, open triangle and shaker sample bundles. Unplayed planned routes cannot explain that channel's reported timbre. The previous report could print actualVerified=1 for unplayed routes because it queried GetPreset without requiring the recorded first-real-note verification. Reporting now requires both. BASSMIDI preset readback does not identify the actual sample voice.

Piano and guitar domination cannot be diagnosed from MIDI counters as PCM loudness. Observed input settings include Bass CC7=52/v64; Piano CC7=81/v125; Guitar CC7=79/CC11=117/v41; Strings CC7=62/CC11=85/v35. Original style velocity medians differ substantially. These authored differences are preserved.

## Proven application corrections

1. StyleSequencer's applied-state cache previously described an earlier section header even after automation changed installed CC7/CC11. Switching to another section with equal headers could therefore skip a required controller restore. Controller and style-bus updates now update the applied cache. A production-sequencer regression exercises Main A -> Fill -> Main B with equal headers and intervening automation. The same test fails against the saved #785 production StyleSequencer (10 tests, one failure) and passes with this correction (56 local production-path JVM tests). Scheduler, controller arithmetic and authored velocity are unchanged.
2. Canonical Yamaha labels had been reduced to broad family labels. Distinct Snare 5 PD / Snare 4 PD / Snare 1 PD / Snare 2 PD could select the same donor, and donor audition priority applied to every snare. Full labels are retained; each audited donor is bound to the Yamaha target actually auditioned. Colliding sample bundles for different source identities require independent target evidence; otherwise those substitutions abstain and keep legacy. Identical documented identities, such as PopDrumKit pedal keys 21/44, may share a donor while retaining separate ownership lanes. This prevents an unsafe plan; it is not evidence that inactive snare variants caused the device's current Ch8 sound.
3. Per-velocity counters and a passive post-sum PCM meter report actual input and total rendered RMS/peak/clipping. They do not change PCM, gain, SF2 data or synth state, and do not claim per-part PCM measurement. Export never plays or renders an extra note.

## SF2 response evidence and limits

Full metadata shows fixed-velocity generator 47 on one guitar layer, negative attenuation generator 48 on the piano preset, and a roughly 300 ms attack on slow strings. A real Linux BASS/BASSMIDI experiment using identical *synthetic* PCM confirms that fixed velocity flattens response, and negative attenuation can produce an upper-velocity plateau. Normal RMS v35/v125: 0.00537081/0.0685052; negative attenuation v85/v125: 0.0707149/0.0707149; fixed velocity v35/v125: 0.0707149/0.0707149. SBLIMITS is not a demonstrated fix.

These results prove engine behavior, not the loudness or correct musical intent of the tester's actual samples. Fixed-velocity layers can intentionally represent attack noise. No SF2 generators, global gain, channel boosts or melodic bindings were changed. The four actual SF2 PCM files are not present in Cloud, so subjective balance and the precise cause of “tenoneng” remain unproven. This APK corrects the proven cache/semantic faults; it does not claim Yamaha-exact timbre or a fully corrected device mix.

## Selection and safety

The complete exported metadata replay remains 710/1,054 metadata-compatible demands and 344 legacy abstentions, EXACT=0. These are potential metadata routes, not new live device verification. The newly rejected ambiguous snare variants were not demanded by the Love Song fixture, so a coverage change or an audible fix is not claimed. Key16 edge articulation remains UNKNOWN/legacy. Existing audited pedal and Snare 4 candidates remain compatible approximations; no kit winner is selected.

The original 16 protected routing/scheduler methods and seven #785 native owner/controller/resource methods are hash-guarded. NOTE_ON/OFF owner stacks, choke, note retirement, bank translation, melody, CASM, ACMP, section timing and master gain remain unchanged. Candidate planning happens before playback, not per note. Passive meters add bounded arithmetic only, no I/O/allocation/logging or NOTE_ON.

## Validation and device check

Regression includes production StyleSequencer/CC11, all-part presence, full host resolver/CASM checks, native simultaneous/repeated notes and ownership/choke/transition tests, read-only diagnostics, five baseline shadow comparisons, real BASS cross-key PCM and fixed-velocity/attenuation response experiments. CI runs the entire Android JVM suite and both ABIs and verifies old Stage 3 symbols are absent.

Use the same style/SF2 configuration. Play Main/Fill/Intro/Ending normally and export the existing Part Presence report. PCM_MIX gives post-sum, pre-output-clamp measurements; STYLE_VELOCITY gives actual input distribution; COMPATIBLE_ROUTE now distinguishes unplayed routes from first-real-note verification and includes canonical source identity and static sample IDs. No repeat of the six prior audition WAVs is requested. Device assessment of the revised balance/percussion remains necessary; any subsequent timbre change requires actual SF2 PCM/reference evidence rather than gain guesses.

## Changed files

Production: bassmidi_player.cpp/.h; StyleSequencer.kt; yamaha_drum_semantics.h; percussion_audition_evidence.h; percussion_fidelity_policy.h.
Tests: percussion_fidelity_test.cpp; percussion_inventory_probe.cpp; percussion_real_bass_test.cpp; mix_response_fixture.h; mix_response_real_bass_test.cpp; StyleMixFidelityRegressionTest.kt; protected-method and reviewed-patch manifests; test_mix_percussion_boundaries.py; test_percussion_real_bass.py.
Delivery: build.yml enables Telegram delivery for the current branch and makes delivery failure visible as CI failure. The user explicitly authorized implementation, APK build and Telegram delivery. No other recipient is added.
