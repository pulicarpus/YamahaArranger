# Runtime audio-path diagnostic — 2026-10-01

## Identity and purpose

Branch: `diag/audio-path-presence`. Source parent: `ba56c263bdad4c4f9fb00fa59d9ce2d3463656b9`, family-preserving APK Build #754, run `36749544403`.
Diagnostic implementation/APK commit: `cf5e41565c1f8668795b850bf594ac4a7c48a348`. Build #755 / run `36797178721` completed SUCCESS. Branch HEAD after the final checkpoint commit changes documentation only; compiled source remains this APK SHA. This is an observation build, not a subjective sound improvement or a preset/gain fix. No merge/PR is made.

User Android evidence after #754: family gate works; A.Guitar -> Wide Piano 2 is fixed. Piano dominates, Bass is audible but buried, Strings practically absent despite Tyros t4 strings slow mapping, Drum audible but snare missing/wrong; arrangement sounds empty. These are reported device observations, not independently measured amplitudes. At #755 design time the raw AllLog was unavailable. It has since been supplied, followed by diagnostic AllLog074808 and Inspector074819. See [runtime evidence and zone follow-up](AUDIO_RUNTIME_EVIDENCE_20261001.md) for complete analysis; this supersedes that evidence gap. Kit73 absence/fallback, preserved low source velocity and auxiliary piano mask rejection are proven; key-zone/snare/PCM root cause remains open.

## Effective source baseline

GitHub confirms family branch HEAD remains ba56c263; main remains 18081d24346a87d30f091e089a350b5481715982. Both build workflows on ba56c263 compile checkout directly and run host tests; the three CI source-mutating scripts were removed in #754. This branch inherits that reconciliation unchanged. The normal build workflow adds this diagnostic branch to push filters; docs/** and PROJECT_NOTES.md are ignored for documentation-only follow-up commits.

Earlier final #754 checkpoint updates existed only locally because remote write calls were interrupted. This diagnostic commit also preserves those completed notes, correcting their assumption that a subsequent family-branch docs commit already existed. GitHub commit/run SHA, not the local inspection snapshot git HEAD, is authoritative.

## Audited chain and source findings (hypotheses, not fixes)

1. StyleSequencer selects a policy, checks no-policy/chord, unsupported articulation, reserved keyboard, locked/muted, and the historical transform-null fallback before dispatch. Old native logs did not quantify all these drops. Inventory can reveal that a supposed part has no NOTE_ON events in the selected section.
2. CASM/style setup carries Yamaha MSB/LSB and program, but drum setup passes bank128 to native and native can select a default available kit when requested program is absent. A correct dedicated font name alone does not prove the desired snare sample exists in that kit.
3. Native always treats channel8/9 as drums. In playOnce, `isDrumPart` is `destination==9 || isDrumVoice(policy.voiceName)`; channel8 with a policy name not recognized as drum can enter melodic transform. This is a concrete classification asymmetry in source, NOT proof that Love Song snare traverses it. Do not change it without a matching trace.
4. Drum events generally bypass activeTransposedNotes. A source NOTE_OFF without an entry is skipped; this can be normal for one-shot percussion. The diagnostic marks OFF_NO_ACTIVE_LEDGER, not a universal drum bug.
5. Selected preset can be correct yet native NOTE_ON suppressed by an unresolved/failed mapping gate. Old event logs did not expose actual API success. Family gate remains mandatory.
6. setChannelMixer sends CC7/CC11, and style controllers may send later changes. Existing setChannelVolume calls the complete mixer method with default expression/pan/etc. A mixer control can therefore overwrite another controller. Whether this causes the reported presence issue must be shown in actual controller trace.
7. Strings setup already floors CC7/CC11 to 100 for strings channels13/14 unless overridden; adding gain blindly is not justified. Dynamic controllers, mute/lock, no matching per-note policy, failed dispatch or short notes may still make them silent.
8. Event ordering at equal ticks places NOTE_ON before NOTE_OFF. The existing duplicate-source-note replacement/ownership code can yield short logical note durations; slow strings attack could make short notes inaudible. Actual duration evidence and real SF2 envelopes are required before blaming timing or changing scheduler.
9. Bass/Piano relative presence requires actual velocity/controller/event comparisons. A high control-level proxy is not measured audio energy; Piano can dominate because other parts are absent, because levels differ, or because source samples/envelopes differ. No hardcoded gain or replacement preset is added.

## Instrumentation

- STYLE INVENTORY: once per section object (bounded cache), source channels, CASM destinations/voice names, source header bank/PC, NOTE_ON/OFF counts, raw38/raw40, velocity range. Headers are not asserted to be the active dynamic bank/kit.
- STYLE PATH: unique NOTE_ON id, section/tick, source/destination, original/output note, velocity, current source-bank state, voice, decision (FORWARD or specific suppression), transpose, transformAsMelody and nativeRhythmChannel. The existing checks and transform expressions remain functionally identical.
- STYLE SUMMARY: all observed NOTE_ON and forward counts plus decisions per channel, emitted at natural section end or transition break. Cancellation can omit the final section summary; sampled native/source records still remain.
- The same native NOTE_ON call carries the id/origin metadata through AudioEngineManager -> NativeAudioBridge -> JNI -> AudioEngine -> BassMidiPlayer. Channel, note and velocity arguments and external MIDI events are unchanged. No duplicate NOTE_ON is introduced. If SF2 or native engine is absent, sampled bridge/engine failures are explicit. Existing RIGHT/LEFT calls continue through their original API.
- AUDIO PATH: requested voice, expected SF2/preset, requested PC, raw source bank/normalized source bank, destination MSB/LSB/PC, actual sent velocity, actual CC7/CC11 read via BASS_MIDI_StreamGetEvent, actual BASS_MIDI_StreamEvent return, error and rejection reason.
- AUDIO LIVE: actual font/bank/program/name read through BASS_MIDI_StreamGetPreset/FontGetPreset; liveSF2 inferred from its loaded handle; compare with expected selected channel mapping (drum source bank from the existing cache, not merely any127/128 bank); FONTEX2 generation counter; source/original/output/current-style-bank/tick/id; font-level samload/samsize.
- DRUM PATH: live kit/name/bank/program, requestedKitPC/kitFallback, output note, original/remapped flag, sent status. Notes38/40 have GM acoustic/electric snare labels, 37 side-stick,39 hand-clap,35/36 kick. Labels do not prove Yamaha/XG sample identity; other notes are KIT_SPECIFIC_UNVERIFIED.
- AUDIO CC: successful controller changes only; no controller is changed by diagnostics.
- AUDIO OFF / AUDIO SUMMARY: accepted/rejected notes, velocity range/mean, zero-controller count, control-level proxy, drum38/40 attempted/sent counts, sent NOTE_OFF, duration estimate, short-off count (<80ms), orphan offs and bounded observation ledger overflow. Diagnostic ledger mirrors oldest-instance release for observation; it never controls music ownership or BASS state. pendingSentOns is NOT active synth voice count: one-shot drums may end without explicit off. Ledger is bounded to16 timestamps/key.

Source/native sampling each allows first4 normal and first4 snare events per channel per approximately2s; matched origin samples force corresponding native details if native OFF traffic consumed its own budget. Counters observe all events even when details are sampled. Summaries are about2s on subsequent native NOTE_ONs, so an idle channel or stop may not flush its final window. First note after silence can report a longer window. Max log line buffer remains1024; long filenames/voice names are truncated in event diagnostics. No new resolver candidate logging is added.

## Limits of the evidence

NOTE_ON_SENT=1 proves API acceptance, not audible PCM or that a drum key has a playable sample zone. mappingMatch=1 compares active preset identity, not waveform fidelity. samload is whole-font sample memory, not per-note readiness or amplitude. BASS getters are read-only. No render loop, per-channel PCM extraction, SF2 generator/sample parsing, master gain, per-font gain, compressor/limiter, or audio-buffer behavior is changed. If all event/mapping/controller/lifecycle evidence is correct yet a solo part remains inaudible, inspect actual SF2 key/velocity zones, attack/release/sample data next, using the user's files; do not infer that from green CI.

Some retriggers from existing RTR paths use the legacy note API and therefore id=0/origin=-1. They are observed natively but not attributed to a new source/tick. No RTR behavior was changed to improve observability.

## Changed files and regression boundary

Production: new `audio_path_diagnostic.h`; observation state in bassmidi_player.h/cpp; metadata-only entry in audio_engine.h/cpp/native_lib.cpp and NativeAudioBridge.kt/AudioEngineManager.kt; small observer hooks in StyleSequencer.kt plus new StyleAudioPathDiagnostic.kt. Build workflow only adds diagnostic branch. Tests update actual native mocks/harness plus add audio_path_diagnostic_test.cpp; tools/test_voice_resolver.py runs all suites. PROJECT_NOTES.md and checkpoint documents record the work.

Family resolver policy/header and findMelodicPreset are unchanged. Drum find/cache/FONTEX2 routing, MIDI values, Yamaha packed banks, FONTEX2 priority, source-program NOWAIT preload, SF2 loading/pool, mixer values, source/range CASM metadata, CASM transformer, scheduler tick calculations/transitions/Main/Fill selection, activeTransposedNotes behavior, RTR, sustain/release, RIGHT1/2/3/LEFT APIs and screen layouts remain unchanged. Scheduler hooks observe the existing branches; no branch condition or transform decision is fixed. Native send now returns the existing API result; other callers retain their old behavior.

## Host validation before build

210 original policy checks, 26 actual-native routing checks with mock BASS (19 baseline +7 diagnostic assertions), and22 diagnostic counter/ledger/budget checks pass. Native tests prove diagnostics add no extra NOTE_ON or mixer fix; log actual CC7=80/CC11=0 with accepted note; log an active-preset mismatch; log actual note send failure; correlate original/output/id; trace snare38/40 and remap observation. Pure tests prove bounded log/timestamp storage and counters, drum-only snare counts, FIFO duration observation and summary interval. Host mocks do not prove SDK ABI, Android sound, or sample/envelope fidelity. Android CI compiles against the real downloaded BASS/BASSMIDI SDK. diff --check and manual diff review are required before publication.

## Android test instructions

Use exactly the same Yamaha Melody, Colombo, Tyros4 and dedicated Yamaha Drum files and the same Love Song/Main D, tempo, chord and mixer settings as #754. Do not adjust gain/voices yet. Channel numbering is zero-based:8/9 Rhythm1/2,10 Bass,11 Piano,12 A.Guitar,13/14 Strings1/2; add1 for conventional MIDI channels.

1. Clear AllLog and play full Love Song Main D for4-8 bars without keyboard playing. Export AllLog + Inspector. Note diagnostic APK build number/implementation SHA.
2. Repeat with existing channel mute controls: Rhythm1 alone, Rhythm2 alone (listen especially snare38/40), Bass alone, Piano alone, Strings1 alone, Strings2 alone, then restore full mix. Export after each short pass or record exact pass timestamps. Do not change voice/gain or controller settings beyond temporary mute. Record chord/tempo and when mute/unmute happens.
3. For each sampled id follow STYLE PATH -> AUDIO PATH -> AUDIO LIVE -> DRUM PATH. Original38/40 should remain the intended drum key unless explicitly evidenced otherwise. Check stage suppression, nativeRhythmChannel/transformAsMelody, requested kit versus live kit, live SF2 and mappingMatch, velocity and CC7/CC11, NOTE_ON_SENT/error. Do not interpret raw38 in a melodic inventory as a snare.
4. For strings, keep Tyros t4 strings slow. Check inventory/forward counts, note accepted, live source/name/mapping, CC7/CC11, velocity and held_ms/shortOff counts. If fully correct but inaudible solo, preserve the trace and provide actual SF2 preset/zone/envelope metadata for the next investigation.
5. Compare Bass/Piano AUDIO SUMMARY velocity/zeroCC/controlProxy data during unmuted full-mix windows; exclude intentionally muted windows. If controllers are similar and all parts sound solo, source timbre/envelope/audio measurement remains the next hypothesis rather than raising Bass automatically.
6. Verify original CASM/fill/transition, sustain/release and keyboard behavior remain intact as regression checks. Instrumentation itself adds limited logging overhead; report any timing regression introduced only in this diagnostic APK.

Stop making builds until this evidence is reviewed. Fix only an observed, reproducible break in the chain; retain the family gate and the selected Strings preset until evidence justifies a scoped change.

## Final APK evidence

- Branch `diag/audio-path-presence`; implementation/APK SHA `cf5e41565c1f8668795b850bf594ac4a7c48a348`; parent `ba56c263bdad4c4f9fb00fa59d9ce2d3463656b9`.
- **Build #755 SUCCESS**: [workflow run36797178721](https://github.com/pulicarpus/YamahaArranger/actions/runs/36797178721), job110163179470, completed2026-10-01. Checkout compiles this exact source with no CI resolver mutation.
- [Download app-debug](https://github.com/pulicarpus/YamahaArranger/actions/runs/36797178721/artifacts/11134221355). Artifact11134221355; archive12,459,240 bytes; SHA256 archive digest `cc954e2e38f73ed13d578a91b56c0c02103598d06f2c60b5f140e878bd06b9fa`. This digest is not a separately measured APK-file digest. Current artifact expiry2026-12-30.
- CI printed PASS210 resolver +PASS26 native mock routing +PASS22 diagnostic checks. Real Android SDK C++/JNI/Kotlin compilation and both configured ABIs passed; BUILD SUCCESSFUL in1m4s. Artifact upload succeeded; inherited Telegram step succeeded.
- One diagnostic candidate build, no failure/retry and no gain/preset/scheduler fix.
- Reviewed remote diff: exactly19 expected paths,111 unrelated existing blobs unchanged, all18 native libraries retained, resolver header SHA unchanged. Ten protected native function bodies were verified byte-identical locally (resolver, drum lookup, bank normalization, preload, engine setup, mixer/expression, sustain/release, render).
- CI reports existing unused fields/parameters plus an observation warning: `transformed == null` is always false. Existing `chord?.let { transform(...) } ?: sourceNote` already makes this value nonnullable; the observer's DROP_TRANSFORM_NULL branch cannot fire in this build. The old Elvis-continue was similarly ineffective. **Do not use absence of this drop log to prove the transformer returned a usable note**: null-transform results can be masked by the existing source-note fallback. This is source/compiler evidence, not proof of the Android presence root cause. No second cosmetic build or CASM semantic change was made.
- This checkpoint update changes only PROJECT_NOTES.md and docs. The branch HEAD may therefore be newer than the APK implementation commit; no new APK is claimed for that docs HEAD.
- Historical #755 completion: raw device AllLog was unavailable then. Current runtime evidence and the next zone-audit candidate are documented in AUDIO_RUNTIME_EVIDENCE_20261001.md and PROJECT_NOTES.md; do not repeat the old investigation or claim key/sample compatibility is already proven.
- BASS preset readback/sample counters around the first cold note may be incomplete while NOWAIT loading proceeds. Compare repeated warm bars and sample memory changes before treating a single liveOk=0/infoOk=0 as definitive failure. Preset identity/API acceptance/font sample-memory are not audible-energy or key-zone proof.
- Local workspace `/workspace/YamahaArrangerAudioDiag` is a text inspection snapshot for diff/tests, not a full remote clone; local baseline git SHA is not the published SHA. Read GitHub branch/commit/build identity when resuming.
