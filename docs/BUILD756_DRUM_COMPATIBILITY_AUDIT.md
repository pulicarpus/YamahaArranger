# Build756 drum compatibility audit — 2026-10-01

## Checkpoint and evidence status

**Historical audit, superseded continuation:** the user has now authorized an on-device all-kit Inspector export plus the proven CC11 fix. No SF2 upload is needed or requested. See [new candidate checkpoint](DRUM_ALL_KIT_CHECKPOINT_20261001.md). The missing twenty-kit data will be produced from the already loaded Android metadata cache.

Repository pulicarpus/YamahaArranger, branch `diag/audio-path-presence`, remote HEAD `882505d44cf0f08a2834c067cb62ba2c246455da`; source/APK756 `8def797ace9da1646784e74c7f1019b278eee7ab`, successful run36799888326. Effective StyleSequencer/workflow were rechecked at remote HEAD; docs-only follow-up does not alter APK source. CI has no resolver mutation scripts.

Git blob hashes of the local files used for this investigation match the remote HEAD tree exactly: `.github/workflows/build.yml`, `bassmidi_player.cpp`, `bassmidi_player.h`, `sf2_zone_diagnostic.h`, `StyleSequencer.kt`, and `AudioEngineManager.kt`. The wider local workspace is a partial text snapshot, not a faithful clone; unrelated snapshot files must not be committed or used to infer a repository-wide diff.

This new audit/CSV/checkpoint are local documents. No application source changes, commit, push or APK build were made. The user requires proven PC73→compatible kit evidence before fix/build. Raw dedicated SF2 or full zone export for all21 kits is still required to complete that prerequisite; a file-location question was sent while independent analysis continued.

Inputs: YamahaArranger_AllLog_20261001_082913.txt,1553128 bytes/6930lines/SHA256 `70216951508f792c1f3e825837f26d6a200588a24256f4c0db981ab8bce35f41`; YamahaArranger_Inspector_20261001_082925.txt,136649bytes/2368lines/SHA256 `226298e99fb1d20e7d7b1b371113e49a38be793d1ce9a30a44c6bd5f2ac722f5`. Line references below are 1-based in AllLog unless marked Inspector.

## Full MainD histogram

STYLE NOTE MAP753/755 contains every raw source NOTE_ON. A full MainD is30720ticks/PPQ1920/4bars/4-4:117Rhythm1 hits,96Rhythm2 hits. Completed summaries2544/2545 prove every hit forwarded. Serialized MainD models3534/6008 associate each key with velocity/tick; repeated model dumps are deduplicated, not counted as new hits.

Runtime column counts every per-note MIDI OUT NOTE_ON suppressed record during MainD, emitted immediately after local audio dispatch. External MIDI OFF does not suppress BASS audio. Three MainD windows contain119/99 (full117/96 plus next-loop prefix2/3),29/23 and12/11 hits, total160/133. This is complete bridge dispatch evidence; native API records are sampled and cannot individually prove acceptance for all293 calls. No sampled API rejection or mismatch was found on drum keys. Last interrupted section may lack final summary.

All keys request PC73 and play fallback STANDAR PSR-SX bank128/PC0. Zone observations below have liveVerified1,metadataKnown1,NOTE_ON_SENT1. Counts are distinct zone entries, not unique waveform counts. [Full CSV with zone names/layers](BUILD756_MAIND_DRUM_HISTOGRAM.csv).

| Rhythm/ch | Key | Hit/4bars | Runtime all MainD | Source velocity | Extended XG reference role | PC0 matching zones | Actual instrument/sample names |
|---|---:|---:|---:|---|---|---:|---|
| Rhythm1/8 | 54 | 8 | 11 | 110–110 | Tambourine | 3 | sf2-05 / SF2-08 |
| Rhythm1/8 | 75 | 6 | 7 | 57–110 | Claves | 2 | sf2-05 / SF2-43+SF2-19 |
| Rhythm1/8 | 80 | 27 | 37 | 54–94 | Mute Triangle | 1 | sf2-05 / SF2-46 |
| Rhythm1/8 | 81 | 12 | 17 | 60–93 | Open Triangle | 1 | sf2-05 / SF2-46 |
| Rhythm1/8 | 82 | 64 | 88 | 28–65 | Shaker | 2 | sf2-05 / SF2-47+SF2-20 |
| Rhythm2/9 | 21 | 32 | 45 | 28–42 | Metronome Click (timekeeper) | 0 | none |
| Rhythm2/9 | 31 | 8 | 11 | 110–110 | Snare Soft (backbeat) | 0 | none |
| Rhythm2/9 | 33 | 16 | 22 | 59–94 | Kick Soft | 2 | sf2-12 / SF2-42 (two entries) |
| Rhythm2/9 | 51 | 34 | 47 | 31–64 | Ride Cymbal 1 | 2 | sf2-11 / SF2-71 (two entries) |
| Rhythm2/9 | 53 | 6 | 8 | 34–64 | Ride Bell | 2 | sf2-11 / SF2-73 (two entries) |

Sample basename prefix is `SATRIOMUSIK ` for all SF2-* names; full exact names, range/layer/frame/type metadata appear in CSV. Source38/40 are absent; counters0/0 are correct. Reference-map hi-hat42/44/46 are also absent. Do not assume key21 is hi-hat or remap31 to38.

## Yamaha/XG semantics and sample comparison

References pinned to commits: [JJazzLab KeyMapXG.java,cd1944a](https://github.com/jjazzboss/JJazzLab/blob/cd1944afdf8a18b659cdbf63538b6df625a515ec/model/Midi/src/main/java/org/jjazz/midi/api/keymap/KeyMapXG.java) labels21 MetronomeClick,31 SnareSoft,33 KickSoft,51 Ride1,53 RideBell,54 Tambourine,75Claves,80/81 Mute/OpenTriangle,82Shaker. [Ardour Yamaha MU90R,64f31c6](https://github.com/Ardour/ardour/blob/64f31c65f0521ecf5a2aecabb9889b0020b176d1/share/patchfiles/Yamaha_MU90R.midnam) corroborates extended31/33. These are useful XG implementations/device maps, not an official SX700/SX900 Data List for exact PC73. [Tyros5 YamahaRefSynth.ins](https://github.com/jjazzboss/JJazzLab/blob/cd1944afdf8a18b659cdbf63538b6df625a515ec/plugins/YamJJazz/src/main/resources/org/jjazz/yamjjazz/resources/YamahaRefSynth.ins) shows kit-dependent31 BrushSlap/VintageSlap variants and does not identify73. Exact PC73 kit name/variant remains unverified; Colombo Alternative73 is a foreign preset, not proof of Yamaha identity.

Raw key31 plays at1920,5760,9600,13440,17280,21120,24960,28800: beats2/4 each bar, all velocity110. This strongly supports snare/backbeat role independently of GM38/40, and matches the extended XG label. Key33 plays downbeats1/3 plus pickups (0,3360,3840,4800...), supporting kick role. Key21 every960tick alternates42/28; this is timekeeper evidence, not proof of hi-hat timbre. Key51 has eighth-note ride pattern and ornaments. Rhythm original/output match in sampled CASM→native trace: no transposition/remap bug is evidenced. CASM describes voice/routing/transform policy, not actual per-key sample identity.

Proven compatibility failure: DRUM ZONE933 shows key31/110 zero eligible zones on verified kit0. Thus8backbeats per MainD and11 runtime MainD backbeats are sent to an unmapped key. DRUM ZONE784 shows key21/42 zero zones; velocity28 produces no zone-signature change in the complete observer, consistent with zero zones too. Keys21+31 represent40/96 Rhythm2 hits per full MainD (41.67%), and56/133 runtime MainD bridge hits (42.11%). Empty21/31 explains missing rhythm components; it does not mean the entire kit is silent. Other recorded section keys13/15/16/17/18/19/22 also have zero zones.

Key33 has two mono matching entries with SF2-42 at observed layers0–67,68–74,75–81,82–88,89–95,16288baseframes. Key80/81 share mono SF2-46/395067frames; offsets/envelopes/exclusive class may distinguish mute/open. Key51 mono SF2-71/415744frames and53 mono SF2-73/400384frames exist. Generic numbered names cannot establish acoustic role, timbre or amplitude. Same-name overlapping zones are not automatically a duplication bug; preset/instrument generators need review. Presence of zones on33/81 disproves blanket sample absence, but does not prove semantic compatibility for every hit.

Metadata parser inspects preset/instrument/global/local key/velocity ranges and sample links of loaded font bytes. Eligibility is not sample readiness, waveform audibility or Yamaha semantic verification. No speculative snare map, kit selection or gain is proposed as a completed fix.

## All21 dedicated preset inventory

DRUM ZONE CACHE64 valid1/presets21 proves device parsing sees21kits; Build756 logs matching zones only for the active kit. Inspector915–941 lists all presets but no zone relations. No raw SF2 is present in workspace/attachments; Android file URIs are not server paths. Twenty other kit zone maps cannot be ranked from names. PC0 has partial10-key evidence; this is not an all-preset compatibility audit completed.

| Bank | PC | Dedicated preset | MainD zone evidence |
|---:|---:|---|---|
| 128 | 0 | STANDAR PSR-SX | 21/31 missing;8 other keys covered in observed layers |
| 128 | 1 | STANDAR DRUM 2 | Not exported; need SF2/full zone data |
| 128 | 2 | STANDAR DRUM 3 | Not exported; need SF2/full zone data |
| 128 | 28 | CLOW D'ACADEMY | Not exported; need SF2/full zone data |
| 128 | 16 | ALEX KEPRI | Not exported; need SF2/full zone data |
| 128 | 4 | DHUT PSR SX SERIES | Not exported; need SF2/full zone data |
| 128 | 7 | Clowor Pallapa | Not exported; need SF2/full zone data |
| 128 | 8 | Kendang Alex | Not exported; need SF2/full zone data |
| 128 | 10 | ADELLA PSR-SX | Not exported; need SF2/full zone data |
| 128 | 11 | TRIAZ 2018 PSR-SX | Not exported; need SF2/full zone data |
| 128 | 23 | DUT SERA | Not exported; need SF2/full zone data |
| 128 | 24 | REMIX PSR-SX | Not exported; need SF2/full zone data |
| 128 | 32 | CLOW D'ACADEMY II | Not exported; need SF2/full zone data |
| 128 | 36 | KENDANG DHIDI | Not exported; need SF2/full zone data |
| 128 | 39 | KRISNA PSR-SX900 | Not exported; need SF2/full zone data |
| 128 | 40 | CLOWOR PSR-SX | Not exported; need SF2/full zone data |
| 128 | 46 | D'ROSTA SX700-SX900 | Not exported; need SF2/full zone data |
| 128 | 41 | CLOW KEMPUL PSR-SX | Not exported; need SF2/full zone data |
| 128 | 45 | CLOW KEMPUL PSR-SX 2 | Not exported; need SF2/full zone data |
| 128 | 6 | TABLA GM BARATA | Not exported; need SF2/full zone data |
| 128 | 13 | EXP : DJ FUNKOT 1 | Not exported; need SF2/full zone data |

73 is absent. Do not select PC1/2/41 by standard/brush name, numeric proximity or preset order. Do not admit Colombo melody/drum presets implicitly. Ranking requires weighted key/velocity coverage across213actual MainD notes, separate semantic certainty/missing/unknown, explicit backbeat31/110, kick33 layers59/70/78/80/94, key21 at28/42 and every rhythm1/ride key. Include Fill/MainC low-key profiles before a cross-section mapping. A no-remap candidate lacking the backbeat zone is unsuitable. Coverage without known semantics requires waveform audition/sample identification. If no kit qualifies, report missing compatible kit rather than silently select firstPC0. Never make a kit73 mapping universal for unrelated drum requests.

## Separate CC7 Strings1 bug: proven cause, scoped fix proposal

Nine reapply/reset pairs:CC7=100 at712/2643/3120/3609/4271/4797/5290/6094/6741, then62 at870/2742/3232/3733/4370/4888/5369/6180/6843. At870–871 volume62 accompanies expression126. Raw MainD controllers3534/6008 have CC7 only tick0=62; CC11 tick0=127,564=126,844=125,1128=124,1412=123,... No sourceCC7 at564 requests this volume drop.

`applyCasmChannelVoices` sends the existing string volume floor100 but leaves mixerStates.volume=62. `applyStyleController` updates expression then calls full setChannelMixer, resending that62. This is a proven application side effect, separate from genuinely low source string velocity. SourceCC7=62 at activation is real source data; a later CC11 event does not authorize resetting another controller.

Source locations: `StyleSequencer.kt:495` retains raw controller volume, `:509` computes the existing effective string volume, `:536` sends that effective mixer state, and `:556–578` processes style controllers using the raw stored state. `AudioEngineManager.kt:240` already exposes an expression-only JNI call. `bassmidi_player.cpp:1270` sends only `MIDI_EVENT_EXPRESSION` for that call. Drum fallback is independently traced to `bassmidi_player.cpp:745`: when no requested program exists, `findDrumPreset` returns the first enumerated preset without examining its note map.

Minimal planned correction: dispatch styleCC11 through existing AudioEngineManager.setChannelExpression→JNI→BassMidiPlayer.setChannelExpression so only MIDI_EVENT_EXPRESSION changes. Preserve expression override/coercion and existing muted volume, explicit later sourceCC7, all voice/mapping/timing behavior. Do not hardcode100 or change the old gain floor. Other style pan/reverb full-mixer resends may share the state risk; focus the evidenced expression bug rather than broaden changes speculatively. Cache/state synchronization and UI volume helper defaults are separate issues.

Required regression when implemented:CC7=100 survives styleCC11=126/125; explicit sourceCC7=62 still works; mutedCC7=0 survives expression; expression override honored; preset identity/note counts unchanged. Test actual production dispatch, not a duplicated formula. No patch/test/APK claimed yet: the latest user requires candidate-kit proof before fix/build.

## Continuation

New local files: this report, BUILD756_MAIND_DRUM_HISTOGRAM.csv, PROJECT_NOTES audit checkpoint. Remote HEAD and APK remain882505d/756. Request raw dedicated drum SF2 (152669056bytes) or full per-preset key/velocity/instrument/sample export for all21kits; SX700/SX900 PC73 Data List would settle exact kit variation semantics. Preserve private SF2/raw logs locally; publish only necessary derived evidence when authorized prerequisite is met.

Then compare all candidates, choose only a proven compatible kit, implement one scoped drum correction and isolated CC11 dispatch correction with meaningful tests, review diff, build/monitor APK and update checkpoint. Regression boundary: family gate/multiSF2 melody resolver, CASM masks/source/ranges, scheduler/MainFill timing, sustain/release, RIGHT/LEFT/UI, Yamaha packed banks/FONTEX2/NOTEOFF1/source NOWAIT preload/cache, and source Bass/Strings gain unchanged.
