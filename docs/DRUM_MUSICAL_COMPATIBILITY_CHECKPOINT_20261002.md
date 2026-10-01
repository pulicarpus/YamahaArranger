# Drum musical compatibility investigation — diagnostic only

## Baseline and stop boundary

GitHub parent `0bcd66ae0157b5511cc4824a03be4ca1134bc6fb`, branch `diag/drum-compatibility-compact`; APK baseline #766 source `f0116dd258d8946952641e69493414ec6231d0e7`. Protected production baseline remains #765 / `diag/audio-path-presence` HEAD `10d82dc`.

New diagnostic source: `eb8c411a2817bed3dfda031d40f89a1ad7fae26d`. **Build #767 SUCCESS**, run `36902714643`, job `110505537289`. [APK app-debug](https://github.com/pulicarpus/YamahaArranger/actions/runs/36902714643/artifacts/11182227661), artifact `11182227661`, ZIP12,741,839 bytes; archive SHA256 `dc285339baeede79072bdca9a9c457ccbf82c5841d945d89dbe790bcb43d9a5f`. [Workflow](https://github.com/pulicarpus/YamahaArranger/actions/runs/36902714643). One CI build, no retry. Final checkpoint commit is documentation only; APK source stays `eb8c411`.

Only explicit export/test/isolated-audition file retention changes. No change to `findDrumPreset`, fallback/kit admission or voice selection, gain/volume, note remap, melodic resolver, CASM/RTR, clock/scheduler, lifecycle #765/valid-owner behavior, CC11, FONTEX2/NOTEOFF1/NOWAIT or sustain/release. No algorithm follows this investigation without user approval.

## Input evidence

`YamahaArranger_DrumCompatibility_20261002_002753.txt`, 42,247 bytes, SHA256 `239928dc90d9a0a9b7fbff66089b994ee520cd5cf2f68420fe66a70422584393`.

Actual header style **Love Song.T547.prs**, not S687; PPQ1920, 15 parsed sections, source demand complete. All examined rhythm REQUEST rows retain original Yamaha127:0/PC73 and dynamicBankPC=false. Native request is already collapsed to128/PC73; export-time BASS getter verifies both ch8/9 actually use dedicated bank128/PC0 STANDAR PSR-SX, generation13, with `requested_PC_absent_existing_first_available`.

The dedicated font's21 cached presets omit73. Header/path is provenance, not a cryptographic identity of the style or SF2 content. The report does not include raw SF2 or hardware reference recordings.

All216 UNION rows survive and independently sum to1054: ch8=515, ch9=539. These are source NOTE_ON needs with each parsed section counted once, not runtime sent counts, performance section-repeat frequency, or PCM. Musical role on actual kit73 remains unverified.

## Candidate comparison from #766

All bank/program/channel numbers zero-based. Names are identifiers, not musical evidence.

| Bank128 PC | Name | Ch8 eligible hits | Ch9 eligible hits | Total | Missing hits / bins | Fully covered channel-keys | Musical identity / PCM |
|---|---|---:|---:|---:|---:|---:|---|
| 0 | STANDAR PSR-SX | 515/515 | 300/539 | 815/1054 (77.32%) | 239 /49 | 22/31 | UNKNOWN /not measured |
| 1 | STANDAR DRUM 2 | 515/515* | 484/539* | 999/1054 (94.78%) | 55 /11 | 27/31 | UNKNOWN /not measured |
| 24 | REMIX PSR-SX | 515/515* | 491/539* | 1006/1054 (95.45%) | 48 /5 | 29/31 | UNKNOWN /not measured |

`*` Ch8/9 candidate splits are derived from the verified exporter, not a candidate runtime activation: missing previews are generated in ascending(ch,key,velocity) order and start with ch9; a missing ch8 bin would appear first. PC24's full missing list is uncapped. All candidates have unknownHits=0 in cached metadata. This demonstrates eligibility, not correct timbre or actual synth readiness.

- **PC0 — PROVEN:** every demand velocity for ch9 keys13,15,16,17,18,19,21,22,31 is missing. These49 bins account for239 hits. Other source bins have zones.
- **PC24 — PROVEN:** only ch9 key21 at28/35/36/42 (47 hits) and key22/48 (1 hit) are missing; full list5 bins. Relative to current PC0, metadata gains191 hits and loses none. That gain does not establish musical suitability.
- **PC1 — PROVEN:** full deficit55 hits/11 bins; retained missing preview proves key18 at36/38/43/49/52 (6 hits), key19/42 (1), key21 at28/35 (28), totaling35 hits in8 bins. **The remaining20 missing hits/3 bins are not identified in this export.** Do not silently fill the list with21/36,21/42 and22/48 based on expectations. Net improvement is184; exact gain/loss profile versus PC0 needs retained full mapping.
- **PC1 — PROVEN derived eligibility:** all ch9 bins before first missing key18, including all key16 velocities, have zones because the missing preview is ordered. Key31/110 alone has29 hits; after the35 explicitly missing hits, only20 missing hits remain, so that29-hit bin cannot be missing. Other key31 velocities remain less constrained. These deductions do not provide its sample identity.

## Priorities: actual PC0 deficits, not largest aggregate kit score

All rows below are ch9. Counts combine all15 source sections once.

| Key | Hit count | Actual velocity:count pairs | Generic XG reference role* | PC0 | PC1 evidence now | PC24 |
|---|---:|---|---|---|---|---|
|16|95|28:27,32:1,34:1,36:3,37:2,38:1,39:8,41:2,42:34,57:8,75:8|Whip Slap|missing all|eligibility all95 derived; sample UNKNOWN|covered95; sample UNKNOWN|
|31|48|69:1,73:1,90:1,100:2,101:2,105:1,110:29,120:2,121:6,122:3|Snare Soft|missing all|110 covered29 derived; other bins/sample need comparison|covered48; sample UNKNOWN|
|21|47|28:16,35:12,36:1,42:18|Metronome Click|missing all|28/35 proven missing;36/42 not retained|missing all|
|15|20|27:1,36:3,38:3,39:3,41:3,43:5,47:1,48:1|High Q|missing all|eligible stereo-type sample metadata retained|eligible mono-type sample metadata retained|
|13|14|25:1,34:1,71:1,76:1,77:10|Surdo Mute|missing all|4 eligible zone entries/2 unique sample IDs|1 zone/1 sample ID|
|17|7|32:1,36:1,38:4,39:1|Scratch Push|missing all|covered by ordered-preview deduction; sample UNKNOWN|covered7; sample UNKNOWN|
|18|6|36:1,38:1,43:1,49:1,52:2|Scratch Pull|missing all|missing all|covered6; sample UNKNOWN|
|19|1|42:1|Finger Snap|missing|missing|covered1; sample UNKNOWN|
|22|1|48:1|Metronome Bell|missing|not retained|missing|

16+31+21 =190/239 missing hits (**79.50%**). Highest individual missing bins:16/42=34 hits,31/110=29,16/28=27,21/42=18,21/28=16,21/35=12,13/77=10. Key16 should not be ignored while chasing a snare-only fix.

`*` **STRONG EVIDENCE for generic role only**: pinned [JJazzLab KeyMapXG.java](https://github.com/jjazzboss/JJazzLab/blob/cd1944afdf8a18b659cdbf63538b6df625a515ec/model/Midi/src/main/java/org/jjazz/midi/api/keymap/KeyMapXG.java), re-read during this investigation. It labels these extended keys. It is not an official SX700/SX900127:0/73 per-key catalog. Earlier repository timing evidence places31/110 on MainD beats2/4, strengthening the backbeat/snare interpretation. Exact kit variation roles/articulation still UNKNOWN. Do not re-label21 as hi-hat merely from listening or assume every XG kit uses identical semantics.

## Retained sample/zone evidence: PROVEN structure, UNKNOWN timbre

| Ch/key | PC0 | PC1 eligible metadata | PC24 eligible metadata |
|---|---|---|---|
|9/13|0 zones|z260..263; presetBag16/instrumentBags293..296; sample130 twice,131 twice; instrument sf2-08; names SATRIOMUSIK SF2-99 /SATRIOMUSIK SF2-00; keys13 only, velocity0..127|z115; bags48:2742; sample1306 '002987'; instrument dance; keys13 only, velocity0..127|
|9/15|0 zones|z268/269; bags16:301/302; samples133/134; names SATRIOMUSIK SF2-56 /SATRIOMUSIK SF2-00; instrument sf2-08; keys15 only, velocity0..127|z117; bags48:2744; sample1308 '002989'; instrument dance; keys15 only, velocity0..127|

At key13, PC1 sample130 frames9006679:9059831 and131 frames9059863:9113015 are53152 frames each at44100Hz; PC24 sample1306 frames56663929:56669245 is5316 frames. Key15: PC1 samples133/134 have29184 frames each; PC24 sample1308 has4601. These are raw sample regions, not audible duration/envelope or level measurements.

- **PROVEN:** candidates use different sample objects/regions on13/15. PC1 types4/2 denote SF2 left/right sample types; PC24 type1 denotes mono. SampleLink was not exported in v1, so linked pairing is not proven by type alone.
- **PROVEN:** PC1 key13 repeats references to each of two sample IDs in four eligible zone entries. This is a metadata structural fact, **not proof of duplicate NOTE_ON, redundant BASS voices, louder gain or wrong attack**. Different generators/pan/tuning may matter; v1 did not retain those details. Do not connect this to the protected stale-owner bug without evidence.
- **PROVEN:** retained layers have exclusiveClass0 and static attenuationCb=-100. That is cached generator evidence, not actual gain or a reason to change volume.
- **UNKNOWN:** numbered/opaque names identify no drum class. Neither 'dance' nor kit/file name establishes High Q, Surdo, Whip Slap or Snare compatibility. No candidate's musical identity is confirmed.
- **UNKNOWN:** active BASS voice count/sample ID, actual waveform contribution, pitch/envelope/modulator result and acoustic mix. Four metadata layers are not necessarily four independent stereo waveforms/onsets.

## Why another small diagnostic is necessary

The report has complete state and21 kit summaries, but `BLOCK demand omittedRows=306` and `BLOCK zones omittedRows=6303`. All216 UNION bins survive; only73 per-section bin rows survive. Only145 MATCH rows and18 ZONE rows survive, mainly first key13 and early15 velocities. The previous exporter visits active holes by ascending key/velocity and all21 kits per bin. This explains why the95-hit key16,48-hit key31 and47-hit key21 are starved even though low-count13/25 is described extensively.

**PROVEN diagnostic limitation:** omitted rows are unavailable evidence, not missing sample/coverage. There is no justification to claim a complete sample map or choose a production fallback from v1.

## Minimal diagnostic additions in #767 source

- User enters1..4 bank:PC pairs in Inspector; blank preserves v1/all-kit behavior. No prefilled0/1/24, no name/style/program/key rule, no auto-selected winner. Comparison input never enters audio preset/override state.
- Explicit v2 focused comparison reuses the same immutable dedicated metadata snapshot and independent live BASS getter. Native playback/activation/controller/decoder functions remain byte-identical. No new logging in note/render/chord loops.
- Group actual velocities that have exactly the same eligible zone-index set. MAP records retain velocity:hitCount, missing/covered/unknown, layers, zone IDs and unique sample IDs. Keys sorted by **actual missing hit count**, then demand hit count, then deterministic channel/key; only evidence retention is prioritized, not candidate selection.
- Per-candidate/per-channel coverage explicitly reports missing bins/hits, unknown hits, gained/lost versus actual preset and unknown delta. Candidate absent metadata is UNKNOWN, never MISSING_ZONE.
- Zone dictionary adds source-context bags/sample/link/type/frame/loop/root/pitch correction, complete present preset/instrument generator values and custom modulator records/known flags. Default SF2 modulators/BASS overrides are not enumerated; semantic identity and actual sample voice remain UNKNOWN.
- Hard native40KiB plus provenance8KiB ≤48KiB; whole-record omissions remain explicit. State/candidate/mapping budgets are protected. Zone detail uses remaining bytes only after those blocks, so it cannot crowd out the comparison mapping. More complex real SF2 layers may still require two candidates or one candidate in separate small exports; no unlimited Inspector dump.
- **SAVE WAV** retains the existing2s verified isolated decode audition in Downloads/YamahaArranger as bank/PC/key/velocity-stamped PCM16 WAV. Existing decoder, font volume, controllers and validation are unchanged; no normalization/gain/remap/preset installation. Actual-style key/velocity and an eligible dedicated zone are required. Missing bins are refused, rather than played via another kit. WAV is the output of an isolated preset audition, not the production style mix or Yamaha hardware reference.

BASS evidence boundary: `BASS_MIDI_StreamGetPreset` exposes font/bank/PC; controller getters and FontGetInfo expose state/font aggregates. The APIs used here do **not** expose the actual sample ID/voice identity instantiated by one NOTE_ON. Eligible zone sets must not be promoted to such identity. A WAV proves the rendered waveform of that controlled audition after it is inspected, not which eligible layer internally instantiated, nor Yamaha compatibility.

## Regression guard

Local **432 checks PASS**:210 family resolver +70 native routing +48 capture observer +30 SF2 +15 original all-kit audit +30 compact v1 +29 focused comparison. Three native additions check gap priority, byte-identical default v1 output, and unchanged complete MIDI/controller/FONTEX2/preload state. Twenty-nine new focused checks cover arbitrary programs/keys, stereo/repeated sample IDs, velocity grouping, absent/invalid metadata, live uncertainty, canonical order, count/input bounds, true216-bin demand shape, priority of16 then31 then21, complete synthetic mapping retention and heavy-zone omission isolation.

The216-bin CSV is **derived source demand only**, SHA-linked to the uploaded report. Test inventories matching815/999/1006 counters are explicitly synthetic, not actual SF2/sample evidence. They must not be quoted as a newly proven PC1 full missing map.

Targeted JVM guard expands to34 cases (5 expression,14 drum,15 chord); new cases test generic selection parsing/canonicalization/limits and no influence on source histogram/voice setup. Protected G→C4ms reproduction/atomic race/different-owner cases remain unchanged.

Source comparison removes only the explicit `BassMidiPlayer::drumCompatibilityReport` diagnostic method and proves all remaining native CPP byte-identical to #766. Sequencer, CASM transformer, voice resolver, SF2 parser, old v1 formatter, expression/chord guard files and workflow remain byte-identical. Existing isolated audition decoder also remains byte-identical; only its WAV persistence/UI action is added. Remote source changes16 intended paths, leaves143 existing blobs including18 native libraries untouched and adds3 report/test/fixture files. No PR/merge to baseline.

**CI confirms432 host checks and all34 selected JVM cases PASS**, `testDebugUnitTest` and `assembleDebug` BUILD SUCCESSFUL, real BASS/BASSMIDI SDK for arm64-v8a and armeabi-v7a, artifact upload and existing delivery success. No source/workflow changes after publication. Local test initially showed zone-only budget starvation even for synthetic captured demand; formatter was corrected before the source commit to borrow unused protected-block space. No production audio function was involved. Actual #767 Android zone/PCM evidence remains pending and cannot be inferred from host fixtures.

## Is evidence sufficient for universal fallback?

**Sufficient to design an evidence model and compatibility guard; insufficient to select/deploy a universal fallback policy or declare PC1/24 correct.** Proven coverage failure and data-shape differences constrain a design. They do not identify actual requested kit73, a compatible per-key map, or PCM/timbre.

Still missing:

1. Full retained actual PC1/PC24 sample/zone map for high-demand16/31 and unresolved21, plus positive controls and any newly lost mappings (especially PC1's unidentified20 hits).
2. Authoritative Yamaha kit/model-specific127:0/73 identity/per-key semantics or controlled original-arranger reference for those roles; generic XG labels are only supporting evidence.
3. Actual PCM at identical requested key/velocity and unchanged conditions for candidate comparisons; describe or provide WAVs. Do not infer musical class from numeric sample names.
4. Generator/sampleLink/modulator/choke evidence for overlapped layers and musical consistency across the other covered keys. A higher covered-hit count may introduce wrong percussion timbre elsewhere.
5. Stable style/SF2 content identities for a registry and broader style/section corpus; current path/header alone is not an exact content fingerprint.

## Recommended design, NOT implemented

Preserve the complete Yamaha MSB/LSB/PC, model/profile, kit/SFX role, source/destination and provenance separately from SF2 raw bank/program and native effective binding. Registry candidates by verified source content and allowed role. Describe coverage and musical identity as independent dimensions with evidence/confidence and limitations.

Coverage is a playable-eligibility guard using actual demand/velocity and separately recorded lost/gained mappings, never a highest-total-coverage winner. Admit a substitute only when kit/per-key semantics are documented or verified by controlled reference/audition; distinguish exact identity, documented compatible substitute, approximation and unresolved. Unknown musical identity remains visible. Check positive controls, stereo/modulation/choke/velocity boundaries and all relevant sections before any rollout.

Unresolved21/28 and21/35 are already empty on all three examined candidates, so extending diagnostic to other kits is justified **only to investigate that residual need**. Consult retained key-specific evidence across the existing inventory, then compare user-selected alternatives; do not choose PC36 or any kit from names/aggregate counts or older unverified same-filename snapshots. No default note remap or composite kit follows. Keep melodic family gates and lifecycle independent.

## Android procedure

1. Same fonts/style, warm MainD/full mix → STOP. Inspector compare input: `128:0,128:1,128:24` → SAVE DRUM (SMALL). Send only the v2 DrumCompatibility text first; no big Inspector/repeated AllLog.
2. For PCM evidence, keep the same fonts and use existing fields/SAVE WAV for PC1 thenPC24, bank128, key16 velocity42 and key31 velocity110. These four WAVs have identical event requests and default audition conditions. If a zone is unavailable, report refusal; do not substitute a note.
3. Positive control if needed: key33/velocity94 on PC0/1/24. Missing-key PC0 audition is intentionally refused by the existing eligibility guard. Comparing an absent sample does not need a speculative remap or a fabricated silent WAV.
4. Report perceived identity/attack (including UNKNOWN) and preferably a matching Yamaha reference. Audition PCM is not the style full mix; don't change production voice/gain to compensate.

After publication/report: **STOP. No production fallback algorithm implemented.**


## Published file scope

Source commit16 paths:
- `app/src/main/cpp/drum_compatibility_comparison.h` (new formatter)
- `app/src/main/cpp/bassmidi_player.cpp` / `.h` (explicit export overload only)
- `app/src/main/cpp/audio_engine.cpp` / `.h` (diagnostic forwarding only)
- `app/src/main/cpp/native_lib.cpp` (new focused JNI getter)
- `app/src/main/java/com/yourapp/yamahaarranger/audio/NativeAudioBridge.kt`
- `app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt`
- `app/src/main/java/com/yourapp/yamahaarranger/arranger/DrumCompatibilityProfile.kt` (comparison parser only)
- `app/src/main/java/com/yourapp/ui/MainViewModel.kt` (on-demand route only)
- `app/src/main/java/com/yourapp/ui/Sf2StyleInspectorScreen.kt` (comparison field and existing-WAV persistence)
- `tests/drum_compatibility_comparison_test.cpp` (new)
- `tests/fixtures/drum_demand_766.csv` (new, derived demand only)
- `tests/native_resolver_test.cpp`
- `tools/test_voice_resolver.py`
- `app/src/test/java/com/yourapp/yamahaarranger/arranger/DrumStyleAuditProfileTest.kt`

Documentation-only follow-up: `PROJECT_NOTES.md`, this checkpoint. Old report and both protected baseline branches/source ancestry remain; no branch switch/rollback/merge/PR or algorithm patch.
