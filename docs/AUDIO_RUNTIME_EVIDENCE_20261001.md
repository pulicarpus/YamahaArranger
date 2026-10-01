# Love Song runtime evidence and drum-zone follow-up — 2026-10-01

## Source/build identity

Repository `pulicarpus/YamahaArranger`, development branch `diag/audio-path-presence`. Diagnostic baseline APK #755: `cf5e41565c1f8668795b850bf594ac4a7c48a348`, successful run36797178721. Documentation-only baseline HEAD: `b1ae2a23d1788094cece469293543504c6b4a597`. GitHub compare confirms these differ in documentation only. Source and workflow were rechecked against the APK SHA; workflow compiles checkout directly, without CI resolver patch scripts. Family resolver inherited from #754 remains unchanged.

The new candidate adds missing drum key/velocity/sample-zone metadata evidence. It makes **no sound fix**: the active dedicated font lacks the requested kit; the files received do not contain SF2 zone/sample data, so a compatible replacement kit or snare remap cannot yet be justified. Do not interpret an APK build as a proven improvement in audio.

## Evidence inputs and reproducibility

User attachments, not committed raw logs:

| File | Bytes | Lines (`splitlines`) | SHA256 |
|---|---:|---:|---|
| YamahaArranger_AllLog_20261001_063648.txt | 929925 | 6697 | 26d68663ec29ba2c4d3beb29b082a30782612bd08b196cd971b2ed13d99f819b |
| YamahaArranger_AllLog_20261001_074808.txt | 1939490 | 10059 | 29442401d81b37f64d2ef2b74ad61a4be1ec67da50dbb9943b9616ea8c38971e |
| YamahaArranger_Inspector_20261001_074819.txt | 136751 | 2368 | 7a0c415b4dbdcc5113f1660194319548b84817806442b4b7fe7c92649c8c1079 |

Line references below are 1-based in the diagnostic 074808 AllLog unless explicitly marked Inspector/063648. Device clock/filename does not encode a commit. The 063648 trace has no build identifier and no AUDIO PATH/LIVE/DRUM/CC/SUMMARY records; it is consistent with the #754 pre-diagnostic source, not positive proof of its SHA. The later trace has #755 diagnostic signatures and is identified as the diagnostic APK by the user.

All channel and program numbers below are zero-based. No external MIDI output device is connected; MIDI OUT OFF suppresses external output. Local BASS API acceptance in the later log proves this is not a general local audio-routing failure.

## Priority 1 — dedicated drum kit identity and extended keys

**Proven identity root cause:** Love Song requests Yamaha bank127:0/PC73 on rhythm channels8/9. Style setup passes this request as audio bank128/PC73. Native `findDrumPreset` searches only the dedicated drum cache (or primary font when no dedicated font), not Colombo/Tyros secondary melody fonts. If no program matches, it returns the first available drum preset. Its historical `found=1` means a usable cache entry, not exact requested-kit success.

AllLog469/478: `DRUM PRESET RESOLVE requested=73 -> bank=128 prog=0 found=1`. DRUM PATH599/604 and all251 sampled drum records show `requestedKitPC=73 kitFallback=1`, active `STANDAR PSR-SX`, liveBank128/livePC0, NOTE_ON_SENT1. Each sampled drum original/output matches (`remapped=0`). AUDIO LIVE confirms active dedicated font/mapping; no sampled send failure or mapping mismatch occurs.

Inspector915–941 lists all21 presets in the active dedicated `DRUMKIT YAMAHA PSR-SX700 & SX900 PRIME.sf2` (152669056 bytes,2257 sample headers). Its program set is `{0,1,2,4,6,7,8,10,11,13,16,23,24,28,32,36,39,40,41,45,46}`: **73 is absent**. PC0 is first. Thus this is not numeric bank preservation losing an existing dedicated PC73, nor melodic Colombo taking over drum playback.

Inspector909/2194 does list bank128/PC73 `Alternative`, but it belongs to **Colombo**. Inspector2354+ merges preset inventories without role/source gating for its textual EXACT MATCH/SAME PC report. That report is not the native resolver's decision. The same report misleadingly offers organ for Bass and drums for Piano; UI labels Bright Piano/E.Grand for strings are also not live audio identities. Do not use these old Inspector suggestions to override runtime family gate/dedicated drum routing. `instruments=0` is also an Inspector metadata parsing defect: source tests chunk id `inst `, but SF2 uses four-byte `inst`. This does not prove fonts lack instruments; no UI/Inspector behavior is changed in this task.

**Extended note audit:** complete section inventories show raw38=0/raw40=0 on both rhythm parts in every played MainA/MainC/MainD/FillCC/FillDD. Completed MainD sections forward all117 rhythm1 and96 rhythm2 NOTE_ONs per iteration (4229–4236,6267–6274). No rhythm NOTE_ON policy drop is recorded. MainD raw serialized event lists contain rhythm1 keys54,75,80,81,82; rhythm2 includes21,31,33,51,53. Across the recorded sections, observed/serialized rhythm key union is:

- Channel8:54,69,75,80,81,82.
- Channel9:13,15,16,17,18,19,21,22,31,33,43,45,47,49,50,51,53,57.

Earlier 063648 baseline additionally plays keys37/48 in FillBB, which was not played in the later trace. Its653 bridge drum calls contain no key38/40 either. Those are different section coverage, not a mapping regression.

No name or GM table establishes which of these extended keys is Yamaha's intended snare for kit73. The Inspector provides only preset/sample-header counts, not pbag/pgen/inst/ibag/igen/shdr relationships or waveforms. **Neither missing-zone nor wrong-snare-sample root cause is proven yet.** Bank128/PC0 being accepted by BASS does not prove that key13/16/21/33/51 has a corresponding zone, or the right Yamaha/XG timbre. Selecting Colombo `Alternative`, a different dedicated kit or remapping to38 would be speculative and is deliberately not done.

## Priority 2 — Strings, Bass and Piano dynamics

775 sampled AUDIO PATH records all show NOTE_ON_SENT1/error0; all775 associated AUDIO LIVE records show mappingMatch1. Counts by channel:8=132,9=119,10=88,11=131,12=129,13=46,14=30. These are samples, not total played event counts. Positive matching source ids establish521 STYLE PATH→AUDIO PATH pairs with exactly preserved integer velocity; id0 RTR records cannot be paired to source ids and were excluded from this comparison.

| Part | Raw MainD section NOTE_ON count/range | Native sampled controllers | Live selected source |
|---|---|---|---|
| Bass10 |19 notes, velocity57–61 |CC7 always52, CC11 always127 (88 samples) |Yamaha Melody `BASS`, rawBank8 → normalized sourceBank1, PC17, destination8:4/17 |
| Piano11 |70 notes, velocity42–88 |CC7 always81, CC11 always127 (131 samples) |Yamaha Melody `Yamaha ConcertGrand`, source0/0, destination104:21/0 |
| Strings1/13 |3 notes, velocity26 exactly |CC7=100 in23 samples and62 in23; CC11=102–127 sampled |Tyros `t4 strings slow`, source0/49, destination8:5/49 |
| Strings2/14 |2 notes, velocity33–34 |CC7=100 in30 samples; CC11=100–119 sampled |Same Tyros source0/49, destination8:5/49 |

Inventory2244–2251 is raw style data before the native pipeline. MainA strings1 velocity22; FillDD strings1=27 and strings2=36; FillCC strings1=27 and strings2=31; MainC strings1=26–69, strings2=31. The higher MainC string attacks are present in native samples too. **Low string velocity is already in style data, not accidental Float/int scaling or a family mismatch.** `velocity/127f`→`lround(velocity*127)` preserves this integer; string input/native pairs verify it. Do not modify style velocity as a generic bug fix.

Style controllers intentionally request BassCC7=52, PianoCC7=81, and StringsCC7=62. MainD parsed setup is in both input logs. Native controller evidence distinguishes these style values from stale UI mixer volume100. Piano receives higher volume and generally higher velocity; this is a real control-level difference, not proof of measured acoustic amplitude or justification for hardcoded Bass gain.

**Proven application state inconsistency:** existing string activation applies a CC7/CC11 minimum100 for channels13/14, but does not update `mixerStates` to that floored native value. A later style CC11 event calls `applyStyleController`→full setChannelMixer using stored volume62, resetting CC7 even though the incoming controller changes only expression. In this trace, CC7 string13=100 at579 then62 at686; MainD100 at2206 then62 at2327. Channel14 similarly100 at1631 then62 at1704 for FillDD; no continuing CC11 changes in MainD leave its CC7 at100. Later FillDD7356 repeats the reset. This inconsistency is separate from low source velocity. It is not yet evidence that all strings' lack of presence is caused by that floor/reset; string14 has matching live mapping, CC7=100, accepted notes and still has the reported problem. No extra gain/floor policy change is made while drum compatibility remains the first priority.

The earlier063648 bridge-intent lifecycle audit (FIFO per channel/key, cleared on ALL_NOTES_OFF) matches54 string13 offs:829–12123ms, median2179ms;22 string14 offs:1535–6783ms, median1850.5ms. No matched string durations<80ms. Chord RTR changes/duplicate pitches and API acceptance limit this to observation, not actual per-voice envelope measurement. It refutes a blanket claim that all recorded strings disappear because of very short notes. Slow attack, sample velocity response and real PCM still require zone/envelope/audio evidence; the Tyros preset is retained.

## Priority 3 — DROP_NO_POLICY_WITH_CHORD

Every sampled NOTE_ON drop of this type is **src2/ch2**, a part whose inventory policy destination is **11, Piano rt**. Diagnostic ch2 is the fallback source channel after policy selection returned null; it is not a lost Bass/Strings/drum destination. All other intended destinations8–14 forward their observed NOTE_ONs in completed summaries.

Summaries count at least114 such dropped NOTE_ONs: MainA16, FillDD2, MainD88, FillCC4, MainC4. The final interrupted/cancelled section can lack a final summary;56 sampled detail records are not the total. Section inventory reports an existing policy; this is not a missing parser/CASM table. MainD src2 mask=`0x2ffc7c50` (218), source range0–127, NTR0/NTT2. `policyScore` rejects a chord when its mask bit is0. The allowed bit indices are4,6,10,11,12,13,14,18–27,29. Current source maps MAJOR→0 and MINOR→8; both bits are0, so these chords reject this auxiliary piano rule. CASM CHORD MUTE799 records that same src2 decision on the first C-major chord. Ordinary Piano src11 policy continues to play.

This drop does suppress an additional piano phrase, not the missing rhythm/Bass/Strings parts. Available evidence supports the existing mask gate; it does not establish corruption of CASM mask interpretation or intent of the Yamaha author. No fallback policy or reserved-keyboard routing bypass is added. The next diagnostic includes candidate destination, source-note ranges, raw mask and computed Yamaha chord-type index in this existing drop log to make future checks self-contained.

## Candidate changes and regression boundary

- `sf2_zone_diagnostic.h`: read-only SF2 metadata parser; resolves preset-local/global and instrument-local/global key/velocity ranges, instrument/sample links, sample-header names/base frame ranges/types. Within-level local ranges override globals; preset/instrument effective ranges intersect. Invalid/truncated/missing metadata fails to unknown, never a false missing-sample assertion. Bounds/cap apply. Only drum banks127/128 are inventoried.
- `bassmidi_player.h/cpp`: cache metadata using the bytes already read for drum preset cache during font loading, never parse/read files in note/render. Emit `DRUM ZONE` once per key or changed eligible-zone signature; verify live font/bank/PC at that observation. Logs extended key, actual velocity, requested/fallback kit, metadataKnown, matchingZones, sample/instrument names and header frame/type metadata. This never gates NOTE_ON or selects/remaps sound. Clear observation cache on reload/unload. Existing event sampling does not hide the first occurrence of a distinct key's zone audit.
- `StyleAudioPathDiagnostic.kt`: once-per-section raw key and velocity histograms for every part, so every drum key can be audited without assuming38/40 or counting sampled logs.
- `StyleSequencer.kt`: only enrich existing policy-drop diagnostic; policy conditions, scores, transforms, timing, ownership and MIDI args unchanged.
- Tests add independent synthetic RIFF/SF2 zone fixtures and actual-native observation assertions; runner executes them alongside family/native/diagnostic suites.

Family gate/header/findMelodicPreset, findDrumPreset fallback/routing, load order, primary/Colombo/Tyros candidate admission, FONTEX2, packed Yamaha bank preservation, source-preset NOWAIT preload, drum mute/NOTE_ON/OFF lifecycle, BASS_MIDI_NOTEOFF1, preset/mixer cache, CASM source/range/policies, scheduler/Main/Fill timing, sustain/release, RIGHT1/2/3/LEFT and UI are preserved. No preset/gain or snare remap is guessed. Green tests establish metadata/routing behavior, not real audio.

## Android verification and next measured action

1. Install the zone-audit candidate recorded in PROJECT_NOTES. Keep the same four SF2 files, Love Song70 BPM and current voices/controllers; clear AllLog. Play MainD for a full section, then MainA/MainC and FillCC/FillDD to cover extended key union. Restore full mix after short rhythm1/rhythm2 solos; export AllLog+Inspector and record solo timestamps.
2. Compare `STYLE NOTE MAP` raw keys with `DRUM ZONE` for each key and relevant velocity layer. Require liveVerified1/metadataKnown1 before using matchingZones0 as evidence that the **active** preset lacks a matching zone. Unknown metadata or inactive preset readback is not sample absence.
3. Inspect names for actual instrument/sample identities at keys13/16/21/31/33/51 etc. matchingZones>0 establishes metadata coverage, not sound energy/snare correctness. Frame lengths/types and sample names cannot prove waveform/timbre; names may be custom or misleading. Actual SF2 files/waveform audition may still be needed to establish Yamaha/XG semantic correspondence.
4. If kit0 has missing or semantically wrong zones, obtain/identify a compatible dedicated Yamaha kit for requested73 or evidence-based per-key mapping. Keep melodic candidate pools separated. Implement one scoped fix only after that concrete compatibility evidence, then regression/build/device test. Do not route drum through Colombo solely because Inspector finds numeric73.
5. Strings: compare source velocity histogram with positive-id native records and retain Tyros slow strings for solo test. Decide the old string floor/state inconsistency separately; treat style velocity and Bass/Piano CC levels as musical source data. Do not normalize them blindly.
6. Verify auxiliary Piano rt drops against candidate mask/type details and the raw style CASM before changing any policy logic. Preserve the ordinary piano, bass/string and rhythm routing proven here.

## Published candidate and completed CI

- Branch `diag/audio-path-presence`; implementation/APK commit `8def797ace9da1646784e74c7f1019b278eee7ab`. Its parent is documentation checkpoint `b1ae2a23d1788094cece469293543504c6b4a597`, application baseline #755. Subsequent checkpoint HEAD changes documents only; use this implementation SHA to identify the APK.
- **Build #756 SUCCESS**, run36799888326/job110171617935. [Workflow](https://github.com/pulicarpus/YamahaArranger/actions/runs/36799888326) / [app-debug APK artifact](https://github.com/pulicarpus/YamahaArranger/actions/runs/36799888326/artifacts/11134429464). One candidate, no failed/retry build and no sound fix.
- Artifact11134429464, app-debug archive12,483,868 bytes; archive digest `sha256:1886be48f3a2ccdc8e8abdcbc004359910fc533ca7806d01096d4d0b95568dc4`. This is the artifact archive digest, not an independently hashed APK-file digest.
- CI:210 family policy +29 actual-native mock routing +22 event diagnostics +16 SF2 zone metadata =277 checks pass. Real Android C++/JNI/Kotlin compilation and both configured ABIs pass, BUILD SUCCESSFUL in1m13s. Artifact upload and inherited Telegram step succeed. No audio/sample compatibility claim follows from mocks or green CI.
- Remote diff is exactly11 planned paths; each published blob matches the locally tested content.122 other blobs including18 native libraries, family resolver header and workflow remain identical to the baseline.13 native function bodies were verified byte-identical: findMelodicPreset/findDrumPreset/normalizeMelodySf2/preloadCurrentPreset/ensureEngine/setChannelMixer/setChannelExpression/setKeyboardSustain/setKeyboardReleaseTime/render/noteOn/noteOff/setChannelPreset. The modified load cache/log function only observes metadata; scheduler/policy conditions are unchanged.
- Changed files: app/src/main/cpp/sf2_zone_diagnostic.h, bassmidi_player.h/cpp; StyleAudioPathDiagnostic.kt, StyleSequencer.kt; tests/sf2_zone_diagnostic_test.cpp, native_resolver_test.cpp; tools/test_voice_resolver.py; PROJECT_NOTES.md and the two audio evidence/checkpoint documents. No UI/Inspector/resolver policy patch.
- Current conclusion: exact kit identity failure is proven, exact Yamaha snare sample compatibility is unresolved; string velocity reduction is source data, the string CC floor reset is an observed application inconsistency, auxiliary piano drops follow a rejecting mask. Next action is the Android procedure above, then one scoped evidence-based correction. Avoid repeating family/CASM investigations or speculative gain builds.

