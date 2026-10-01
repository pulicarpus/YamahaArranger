# Compact drum compatibility export — stage 1 (2026-10-01)

## Source of truth and scope

- Authoritative GitHub parent: `10d82dc60acbe760791541a03f37639c5e3da0f7`, `diag/audio-path-presence`; app baseline #765 source `85f3db7d698e6ef423b654ba3b6d65668128550b`.
- Isolated working branch: `diag/drum-compatibility-compact`.
- Source commit: `f0116dd258d8946952641e69493414ec6231d0e7`.
- **Build #766 SUCCESS**, run `36897983219`, job `110489716677`; GitHub workflow completed successfully. [APK app-debug #766](https://github.com/pulicarpus/YamahaArranger/actions/runs/36897983219/artifacts/11181031108), artifact `11181031108`, ZIP12,687,200 bytes, archive SHA256 `7368d3a423d2bcbb86b12655c5b512c438433bb4b9aeb16aa2714b59729ded62`. [Workflow](https://github.com/pulicarpus/YamahaArranger/actions/runs/36897983219). One build, no retry/source fix after publication. Final checkpoint commit changes documentation only; APK source remains `f0116dd`.
- This is the approved export/observation stage. No drum/fallback/playback algorithm change, no candidate selection, no voice/gain/mixer/CASM/RTR/clock/lifecycle/owner/family/sustain/release change. No per-style exceptions. Historical keys21/31 and velocity110 occur only as regression data in the new feature, not playback/report rules.
- No new instrumentation in NOTE_ON, NOTE_OFF, retarget, controller handling or audio rendering. Existing #759 full report/audition/Strings2 solo and #762/#763 compact chord capture are preserved.
- Local directory is an isolated exact text mirror of GitHub:134 text blobs were checked against Git blob hashes before editing. It is not the earlier partial local repository.18 binary blobs stay in the authoritative GitHub base tree. Publication changed16 intended paths, retained139 old blobs, including all18 libraries, and added3 new source/test paths. No PR or merge to baseline branch.

## How to obtain the report

1. Install the successful APK for this source; load the same fonts/style and wait for font loading to finish.
2. Play MainD briefly/full section with the normal mix so ch8/9 have been initialized. STOP without switching fonts/style/voices.
3. Inspector → **SAVE DRUM (SMALL)**. Send only `Downloads/YamahaArranger/YamahaArranger_DrumCompatibility_*.txt` (max48KiB), plus whether export succeeded and playback sounded unchanged.

No large Inspector or repeat AllLog is needed. The button is disabled during style playback; the ViewModel also rejects export while playing. Export does not audition or reset anything. Existing audition remains an independent, explicit action with its original constraints.

## Report contract and interpretation

- **REQUEST**: original parsed part header bankMSB:LSB/program, section ID/name, part, source channel and possible destination channels. Unknown PC=-1 stays unknown. Dynamic bank/program presence warns that header identity is not a per-note binding. Alternative routes remain explicit.
- **LIVE**: independent `BASS_MIDI_StreamGetPreset` at export time, current source font/path, actual raw bank/program/name, native requested bank/program, effective program, initialized status and mapping generation. This is a single current snapshot, not historical per-event/pre-post capture. Native rhythm request bank may already be collapsed upstream; it must not be confused with original Yamaha MSB/LSB in REQUEST.
- **fallbackReason**: existing cache lookup is consulted read-only. A verified actual font/bank/PC agreeing with the established first-available result is `requested_PC_absent_existing_first_available`. Same-PC agreement is only `same_PC_in_existing_cache_not_Yamaha_identity_proof`. Uninitialized, unavailable live getter, unresolved cache, and actual/cache mismatch are separate unknown reasons. Export never activates the expected candidate to make verification succeed.
- **DEMAND / UNION / SECTION**: reuses diagnostic #759's raw NOTE_ON histogram for each parsed section once; destination/channel, unmodified key, velocity and count are preserved. All velocity layers are counted, NOTE_OFF/zero velocity/melodic channels excluded. Duplicate same-destination CASM alternatives do not double count. This is actual source-style demand, **not runtime sent/PCM counts**. CASM chord masks, mutes, overrides, section repetition frequency and chord-dependent alternatives are not simulated.
- Any ambiguous rhythm route invalidates the complete union, rather than inventing one route or presenting partial coverage as complete. Oversized JNI demand payload also becomes explicitly unknown. Section/request rows may be omitted by the provenance byte cap, independently of completeness of the input histogram.
- **KIT**: all cached dedicated drum presets, canonically ordered by bank/PC, with eligible hit coverage, fully covered channel-keys, unknown-hit count and missing-bin count/preview. Both bank127 and128 are distinct even when PC is equal. Summary does not rank/recommend candidates or infer identities from preset names.
- **LIVE_COVERAGE**: current actual dedicated preset versus all source needs on the same rhythm destination. Other-source or unavailable actual preset/cache means unknown. It is not a claim that the current preset played every section historically.
- **MATCH / ZONE**: all candidate key+velocity bins, with missing/covered/unknown classification, eligible layer count and per-kit zone indexes. Sample ID/name, instrument, preset/instrument bags, key/velocity ranges, sample frame bounds/rate/type, exclusive class and static attenuation are available for retained zones. Sample identity includes source/kit/zone context. Zone index is local to that kit vector. Actual BASS internal sample/voice selection and PCM remain unavailable.
- Layer details for missing bins in the actual live preset come first, derived from the data. There is no rule targeting key21/31/110. Multiple eligible zones are exposed, not collapsed into a selected layer.
- **Coverage** is only metadata eligibility. **Musical identity** is explicitly UNKNOWN without independent evidence. **Unknown** metadata/live state is not a missing zone and is not zero coverage. Same preset/sample names and higher total coverage cannot establish snare/percussion identity.
- Candidate scope is the loaded dedicated drum cache, not every installed/secondary SF2. This stage does not expand candidate admission or resolver behavior.

## Bound and omissions

Whole records, UTF-8: provenance8KiB + native40KiB ≤48KiB. Native state4KiB, kit summaries8KiB, demand10KiB and layer details18KiB have independent budgets so a large inventory/section histogram cannot consume every summary.21 kit summaries survive the stress fixture. Kit missing previews and layer ID lists have explicit caps. Every native block reports omittedRows; provenance reports exportOmittedRows. An absent retained zone definition or omitted MATCH row is an export omission, **not** proof of invalid/missing sample. Do not interpret a capped preview as the full missing set. Zero-demand0/0 is explicitly not compatibility proof.

Formatting occurs outside the native mutex after taking a snapshot; no new cache/file parse or permanent logging is introduced. Existing getters/cache access are used only on explicit export. This does not claim zero snapshot cost or device acoustic verification.

## Regression evidence

- Local full host runner: **400 checks PASS**:210 family resolver +67 native mocked routing +48 capture observer +30 SF2 zone metadata +15 existing all-kit audit +30 new compact report checks.
- Native additions verify independent request/live preset; same-PC vs fallback explanation; actual/cache mismatch unknown; eligible real fixture layer and missing31/110; no MIDI/controller write, no FONTEX2 change, no preload change, NOTEOFF1 preserved.
- Pure report tests:21 synthetic kits + same-PC alternate bank, weighted hit counts, all velocity boundaries, key21/28/42 and31/110 regression data, layered coverage counted once, arbitrary key7/velocity17, source section identity, duplicate-bin canonicalization, invalid input, metadata/getter unknown, no identity inference from name, int64 counts, printable record framing, large all-section byte cap and omission counters, all21 summary retention.
- Five added JVM profile cases: all sections/Fill exactly once with stable IDs; full-union rejection for ambiguous routing; original MSB/LSB/source/destination plus dynamic request uncertainty; UTF-8 header bound with omissions; policy duplication/zero-velocity exclusion and explicit mask uncertainty. Existing classes retain their baseline cases.
- CI uses unchanged selected JVM guard classes: `StyleExpressionRegressionTest`, `DrumStyleAuditProfileTest`, `StyleChordDiagnosticRegressionTest` (**31 cases PASS**:5+11+15). Lifecycle G→C4ms reproduction, race atomicity and same-value/different-owner tests remain unchanged. This selected guard is not the entire historical JVM suite; the handoff documents stale `CasmNoteTransformerTest` expectations, which are not used to redesign playback.
- Source comparison: remove the one new `BassMidiPlayer::drumCompatibilityReport` method and the remaining CPP is byte-identical to the remote baseline. `StyleSequencer.kt`, `CasmNoteTransformer.kt`, `voice_resolver.h`, `sf2_zone_diagnostic.h`, `drum_kit_audit.h` and existing lifecycle/expression test files remain byte-identical. Thus existing native selector/activation/controller/render/preload bodies, #765 owner lock, scheduler/timing and CC11 fix are preserved.
- **CI confirmed** the same400 host checks PASS, selected31 JVM cases PASS (`testDebugUnitTest`, BUILD SUCCESSFUL), and `assembleDebug` BUILD SUCCESSFUL with real BASS/BASSMIDI SDK for arm64-v8a and armeabi-v7a. Artifact upload and the existing workflow delivery step succeed. No local SDK search/substitution. These prove instrumentation/guard behavior and compilation, not Android musical/sample identity or acoustic equivalence.

## Changed files

Native diagnostic formatter/binding:
- `app/src/main/cpp/drum_compatibility_report.h` (new)
- `app/src/main/cpp/bassmidi_player.cpp`
- `app/src/main/cpp/bassmidi_player.h`
- `app/src/main/cpp/audio_engine.cpp`
- `app/src/main/cpp/audio_engine.h`
- `app/src/main/cpp/native_lib.cpp`

Kotlin diagnostic profile/export/storage UI:
- `app/src/main/java/com/yourapp/yamahaarranger/arranger/DrumCompatibilityProfile.kt` (new)
- `app/src/main/java/com/yourapp/yamahaarranger/audio/NativeAudioBridge.kt`
- `app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt`
- `app/src/main/java/com/yourapp/ui/MainViewModel.kt`
- `app/src/main/java/com/yourapp/ui/Sf2StyleInspectorScreen.kt`

Regression/workflow:
- `tests/drum_compatibility_report_test.cpp` (new)
- `tests/native_resolver_test.cpp`
- `tools/test_voice_resolver.py`
- `app/src/test/java/com/yourapp/yamahaarranger/arranger/DrumStyleAuditProfileTest.kt`
- `.github/workflows/build.yml` (only permit isolated branch in push list; existing SDK/test/build/delivery steps unchanged)

Documentation-only after build:
- `PROJECT_NOTES.md`
- `docs/DRUM_COMPACT_EXPORT_CHECKPOINT_20261001.md`

## Output example — synthetic only, not Android runtime

```text
LIVE export_time_snapshot ch=9 initialized=1 nativeRequestBank=128 requestPC=73 effectivePC=0 actualKnown=1 actualBank=128 actualPC=0 preset='Synthetic Kit 0' SF2='Synthetic Drum.sf2' inCandidateSource=1 mappingGeneration=0 fallbackReason=requested_PC_absent_existing_first_available
LIVE_COVERAGE ch=9 coveredHits=0/42 unknownHits=0 missing(key@vel)=21@28,21@42,31@109,31@110,31@111, scope=current_preset_vs_all_source_sections_not_historical_PCM
MATCH bank=128 PC=2 ch=9 key=21 vel=28 activeGap=1 status=MISSING_ZONE eligibleLayers=0 zones=none
MATCH bank=128 PC=2 ch=9 key=21 vel=42 activeGap=1 status=COVERED eligibleLayers=1 zones=2,
MATCH bank=128 PC=2 ch=9 key=31 vel=110 activeGap=1 status=COVERED eligibleLayers=2 zones=1,3,
BLOCK zones omittedRows=0 (omitted != missing_zone)
```

The test can write the full synthetic example by taking an output filename as its optional argument. Do not confuse this synthetic21-kit fixture (plus collision case) with the21 dedicated kits on Android.

## Stop boundary

Export implementation only. Missing zones/fallback remain visible and playback unchanged. No new algorithm, remap, kit selection, melodic measurement/gain/voice tuning or unrelated state fix follows without the user's next approval of exported evidence.
