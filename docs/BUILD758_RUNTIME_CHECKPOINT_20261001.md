# Build #758 runtime verification and 21-kit checkpoint — 2026-10-01

## Identity and decision

Repository `pulicarpus/YamahaArranger`, branch `diag/audio-path-presence`, read-only baseline HEAD `ee74bf507bbbcf360dee2a84d570150623f7ed49`. Tested APK is Build **#758**, source `1fd6a820555b93b9e8a11bb2faa5261b4286ff92`, successful run `36806478056`. Build identity is supplied by the user and diagnostic signatures agree; attachment filenames alone are not APK SHA evidence.

**CC11 fix is verified in this device session. Drum sample holes remain verified. No fully covered and semantically verified replacement kit has been established.** Coverage ranking cannot be used as automatic kit selection. No speculative sound fix, remap, gain adjustment or new APK is produced from this evidence. Preserve #758 for the next measured warm/solo test. This checkpoint supersedes the previous statement that the 21-kit report and runtime CC7 result were unavailable.

## Evidence inputs

Raw attachments remain private; only derived findings are committed. Line references are 1-based in the original attachments. All channels/programs are zero-based.

| File | Bytes | Lines | SHA256 |
|---|---:|---:|---|
| YamahaArranger_AllLog_20261001_104259.txt | 1696192 | 8648 | 25b94d59e40d63ee53a6b0c009c0f3e4ced2fdcdc090e7f095f444bfb7d6afa1 |
| YamahaArranger_Inspector_20261001_104309.txt | 573466 | 3704 | 16b434fd4d61e75e16a5da3ebaf1fc4751223297e8949293d151db90cb482e8b |

## Strings CC11 → CC7 verification

- AllLog602: Strings1/ch13 CC7=100. AllLog682: Strings2/ch14 CC7=100. These are the **only** AUDIO CC cc=7 records for their respective channels in this attachment; no CC7=62 reset occurs.
- ch13 has553 successful CC11 records (range72–127); ch14 has81 (49–119). Counts include initial setter calls, so they are controller records, not553/81 distinct incoming style events. Subsequent expression changes never emit another CC7 record in this trace.
- All32 sampled ch13 notes and all18 ch14 notes read native CC7=100, with changing CC11. Examples: ch13 AllLog1026=100/127, later summary5094=100/125 and5353=100/108; ch14 summary5111=100/100 and5567=100/119.
- Runtime agrees with the existing source fix in `StyleSequencer.applyStyleController`: CC11 calls expression-only setter then returns before full mixer. The fix commit8344604 remains mandatory; no change to floor policy or raw style velocity is justified.
- This trace verifies expression behavior under its observed conditions. User volume override, mute/unmute and a later explicit sourceCC7 were covered by existing production-controller tests but are not separately demonstrated here. Do not claim all interactive mixer regressions were device-tested.

## Native/source chain and remaining melodic presence

All488 sampled AUDIO PATH records show NOTE_ON_SENT=1/error0; all488 corresponding AUDIO LIVE records show mappingMatch=1/liveOk=1. Sample counts: rhythm1=99,rhythm2=84,Bass=65,Piano=87,Guitar=103,Strings1=32,Strings2=18. All382 positive-id sampled STYLE PATH FORWARD→AUDIO PATH pairs preserve integer velocity exactly. id0 retriggers are excluded from source pairing. Sampling does not prove API acceptance of every unsampled note or actual audible PCM.

| Part/ch | Native live source | Sample controllers | Raw MainD pattern |
|---|---|---|---|
| Bass10 | Yamaha Melody BASS, normalized1/17 (raw8/17) | CC7=52,CC11=127 in65samples |19notes,velocity57–61 |
| Piano11 | Yamaha ConcertGrand0/0 |81/127 in87samples |70ordinary notes42–88; additional src2 phrase32notes conditional on CASM |
| Guitar12 | Steel Guitar, raw8/1 |79/110 in103samples |168notes,23–70 |
| Strings1/13 | Tyros t4 strings slow0/49 |CC7=100 in32samples |3notes,velocity26 |
| Strings2/14 | Tyros t4 strings slow0/49 |CC7=100 in18samples |2notes,velocity33–34 |

Raw inventory AllLog697–705 establishes these note counts/velocities before dispatch. Weak Bass relative to Piano has a source control difference52 versus81 and lower velocity; this is not proof of PCM amplitude or an application gain defect. Strings remain sparse and low-velocity in MainD despite corrected CC7. MainC raw Strings1 reaches69 (inventory5064), which is preserved natively. Retain Tyros slow strings until actual sample/envelope/solo evidence supports another decision.

Strings sampled AUDIO OFF durations: ch13=24records,2378–3412ms; ch14=12records,3225–3438ms. Native summaries report30/14offs respectively, with0shortOff_lt80ms. The difference is expected sampling; these are diagnostic-ledger durations, not actual envelope lengths. It is incorrect to explain all remaining strings weakness by universal short notes. Tyros samload is positive and grows to14161376bytes, but whole-font memory does not prove each low-velocity key's sample/envelope or acoustic output.

Completed full MainD summaries2341–2347 and3982–3989 forward117/96rhythm hits,19Bass notes,3/2Strings notes. Auxiliary src2→dst11 still gets DROP_NO_POLICY_WITH_CHORD after Cmajor input: AllLog2746 shows mask2ffc7c50/type0. That mask excludes the major bit. Sampled drop lines32 are not total drops; observed summaries total58. Ordinary Piano continues. Do not bypass CASM to restore that phrase or blame it for missing Bass/Strings/rhythm.

## All21 kits: independently checked actual velocity coverage

Inspector4–14 identifies loaded dedicated font, raw Love Song.T547.prs MainD30720ticks/PPQ1920,117+96=213hits,10keys and requestPC73 on8/9. All21 KIT RANK totals were independently recomputed from each KIT VELOCITY bin's actual hitCount and matchingZones. All agree. No partial/unknown key or unknown hit is reported. Percentage measures eligible metadata hits, not energy/timbre.

| Rank | PC (bank128) | Preset | Covered hits | Fully covered keys | Missing keys | Inspector line |
|---:|---:|---|---:|---:|---|---:|
| 1 | 36 | KENDANG DHIDI | 205/213 (96.24%) | 9/10 | [54] | 15 |
| 2 | 1 | STANDAR DRUM 2 | 181/213 (84.98%) | 9/10 | [21] | 16 |
| 3 | 24 | REMIX PSR-SX | 181/213 (84.98%) | 9/10 | [21] | 17 |
| 4 | 0 | STANDAR PSR-SX | 173/213 (81.22%) | 8/10 | [21,31] | 18 |
| 5 | 4 | DHUT PSR SX SERIES | 157/213 (73.71%) | 7/10 | [21,31,33] | 19 |
| 6 | 7 | Clowor Pallapa | 157/213 (73.71%) | 7/10 | [21,31,33] | 20 |
| 7 | 8 | Kendang Alex | 157/213 (73.71%) | 7/10 | [21,31,33] | 21 |
| 8 | 10 | ADELLA PSR-SX | 157/213 (73.71%) | 7/10 | [21,31,33] | 22 |
| 9 | 13 | EXP : DJ FUNKOT 1 | 157/213 (73.71%) | 7/10 | [21,31,33] | 23 |
| 10 | 16 | ALEX KEPRI | 157/213 (73.71%) | 7/10 | [21,31,33] | 24 |
| 11 | 23 | DUT SERA | 157/213 (73.71%) | 7/10 | [21,31,33] | 25 |
| 12 | 28 | CLOW D'ACADEMY | 157/213 (73.71%) | 7/10 | [21,31,33] | 26 |
| 13 | 32 | CLOW D'ACADEMY II | 157/213 (73.71%) | 7/10 | [21,31,33] | 27 |
| 14 | 39 | KRISNA PSR-SX900 | 157/213 (73.71%) | 7/10 | [21,31,33] | 28 |
| 15 | 41 | CLOW KEMPUL PSR-SX | 157/213 (73.71%) | 7/10 | [21,31,33] | 29 |
| 16 | 45 | CLOW KEMPUL PSR-SX 2 | 157/213 (73.71%) | 7/10 | [21,31,33] | 30 |
| 17 | 46 | D'ROSTA SX700-SX900 | 157/213 (73.71%) | 7/10 | [21,31,33] | 31 |
| 18 | 40 | CLOWOR PSR-SX | 149/213 (69.95%) | 6/10 | [21,31,33,54] | 32 |
| 19 | 2 | STANDAR DRUM 3 | 148/213 (69.48%) | 7/10 | [21,75,80] | 33 |
| 20 | 11 | TRIAZ 2018 PSR-SX | 109/213 (51.17%) | 4/10 | [21,31,33,51,53,54] | 34 |
| 21 | 6 | TABLA GM BARATA | 106/213 (49.77%) | 3/10 | [21,31,33,53,75,80,81] | 35 |

No preset reaches213/213. PC73 is absent. Active PC0 has173/213coverage; its40missing hits are key21 at28/42 (32hits) plus key31 at110 (8hits). AllLog962/1118 independently verify active PC0 missing zones with liveVerified=1/metadataKnown=1/NOTE_ON_SENT=1. This is the proven missing-rhythm mechanism: accepted notes cannot produce a sample from an absent eligible zone. It does not establish all PCM/render behavior or exact Yamaha snare semantics.

Candidate limits:

- PC36 (rank1) covers21/31/33 and loses54/110 (8hits). Inspector74–79: key21 uses numbered002553/002583; key31 uses002788/002789; key33 uses002790. Key21 shares the same sample names/frame counts as key81 (line58). Distinct generators may change output, so this is not proof they sound identical; it does prevent inferring correct metronome/snare roles from coverage alone. Numbered samples carry no verified acoustic identity.
- PC1 (rank2) covers31/110 and33actual velocities but lacks21/28,42 (32hits). Inspector139 lists SATRIOMUSIK SF2-53/98 andSF2-51/69 at31, and141 usesSF2-42 at33. Names do not identify exact kit73 snare timbre. A preset name containing STANDAR is not compatibility evidence.
- PC24 (rank3) also lacks21; key31 uses dance/002930,002932; key33 uses003350,003351 (Inspector201/203). Equal coverage does not establish equivalent timbre.
- PC2 (rank19) covers31 but loses21/75/80, so numeric proximity or STANDAR naming cannot justify it.

Ranking is MainD-specific, not a cross-section guarantee. MainA/MainC/FillCC/FillDD/FillAA have additional raw keys; their alternative-kit coverage is not exported by this MainD audit. SelectingPC36, combining kits per key, moving31→38 or21→another key would introduce an unverified musical mapping. No replacement is implemented.

## New startup observation: loading overlaps first playback

This trace also has a cold/reload transient absent from the simple settled-voice explanation:

- CASM VOICE APPLY END AllLog687 takes7798296us. PLAY starts10:41:50.921 (688), while dedicated/optional font loading continues (683/689/854).
- Temporary mapping replaces Tyros with primary Synth Strings (675/676), then Colombo Slow Strings (839/840), before final Tyros t4 strings slow938/939. These are loading transitions; sampled native String NOTE_ONs after final load use Tyros correctly.
- STYLE PATH id1 occurs10:41:50.953 (706); corresponding native AUDIO PATH719 is10:41:52.014. id2 STYLE PATH724 occurs10:41:52.015, native855 at10:41:56.394. id3 STYLE PATH859 occurs10:41:56.397, native955 at10:42:10.226. Output stream reopens1042 at10:42:10.333.
- First full MainD summary appears10:42:11.697 (2341), roughly1.48s after final font attachment, although70BPM/4bars should take~13.71s. The next full section finishes10:42:24.894; its~13.20s interval is consistent with recovering the original timeline after a late start. Exact worker attribution and rendered timing require further measurement.

The log proves load/play overlap and multi-second gaps between correlated dispatch stages. A catch-up burst is strongly supported; mutex/file-loading attribution is a hypothesis without thread/lock timing. First Bass summary2483 has14shortoffs/19offs and mean49.7ms, so cold startup must be excluded before generalizing bass duration. No scheduler, timing, locking or load-order patch is made from this single trace. Existing regression boundaries stay intact. Warm restart after all fonts finish is the next discriminating test.

## Next device test and decision boundary

Use **Build #758**, source1fd6a82/run36806478056. No new APK number is claimed. Fourfonts/Love Song70BPM remain the same.

1. STOP; wait until dedicated, Colombo and Yamaha alternate attachments complete. Clear AllLog after this and restartMainD with the same chord for at least8bars. Record whether cold first-start stutter differs from this warm run. Export full AllLog.
2. Use existing mute controls for short Strings1,Strings2,Bass,Rhythm2 solos. Record exact pass timestamps/chord and whether each is silent, weak, or has wrong timbre. Restore full mix. Verify native CC7 remains100 after CC11 on Strings and that mute/unmute and explicit volume still work.
3. Identify/audition candidate kit samples at original key31/110,key21/28,42,key33/59,70,78,80,94 and key54/110, using a controlled Inspector/editor audition if available. Do not route keyboard layers or automatic drum playback to an unverified candidate. No raw SF2 upload is required for the coverage findings already obtained; numbered sample semantic identity remains unavailable.
4. A compatible kit fix needs semantic/sample evidence and coverage across the additional used sections; a melodic fix needs solo/zone-envelope or measured PCM evidence. If warm playback still shows long STYLE→AUDIO gaps, diagnose loader/dispatch synchronization separately without changing CASM/scheduler semantics.

## Publication and regression boundary

This continuation changes only this document and PROJECT_NOTES.md, based on parentee74bf5. No production/test/workflow/library file changes. Family gate, dedicated drum separation, CC11 fix, CASM/masks, scheduler/MainFill/timing, sustain/release, RIGHT/LEFT, packed Yamaha banks/FONTEX2/NOTEOFF1/NOWAIT and gain are preserved.

Validation here is evidence analysis: both input hashes/line counts, all21velocity-bin totals,382paired velocities,488accepted/matched sampled notes, controller counts and sample-key relations were checked. No new regression tests/build are claimed for a documentation-only continuation. Existing #758 CI296hostchecks/10targetedJUnit and twoABI results remain historical build evidence. The workflow ignores docs/** and PROJECT_NOTES.md on push, so this checkpoint should not create another APK.
