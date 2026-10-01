# Build #758 warm playback and solo checkpoint — 2026-10-01

Repository: pulicarpus/YamahaArranger. Branch: `diag/audio-path-presence`. Parent checkpoint: `0eec8ce1123a1af476aa855c5496f6eb941eb2a5`. Unchanged source/APK: `1fd6a820555b93b9e8a11bb2faa5261b4286ff92`, Build #758.

User reports Strings1/2 barely audible, weak Bass, missing snare/backbeat, metallic percussion, and Guitar/Piano dominance when listening to Rhythm2. These are user listening observations; PCM has not been independently measured.

Input: YamahaArranger_AllLog_20261001_120026.txt; 750991 bytes; 5043 lines; SHA256 `a45c0152211cdaff23ac096478765c6765e04306af918085f14b617ac17782e0`. Raw attachment stays private. References are 1-based; channel/program numbers are zero-based.

## Warm dispatch works

PLAY MainD begins at 11:58:51.113 (line6). Full section summaries finish at 11:59:04.854 (1812), 11:59:18.562 (3173), 11:59:32.267 (3375). Intervals around13.71 seconds match70 BPM/four bars. This attachment contains no font reload/attachment records.

All372 sampled AUDIO PATH notes are accepted/error0; all372 live mappings match. All248 positive-id source/native pairs preserve velocity, with maximum STYLE→AUDIO gap11 ms. The earlier cold loading stall does not explain all continuing symptoms in this warm session. Acceptance/mapping does not prove audible PCM.

## Actual solo windows

| Window | Runtime evidence | Meaning |
|---|---|---|
| 11:59:27.586–11:59:41.837 | Only Strings1/ch13 enabled among8–14; three noteOns at11:59:32.289–.295, keys52/60/64, velocity26, CC7/11=127/127, Tyros t4 strings slow | Valid Strings1 solo, still reported barely audible |
| From11:59:24.697 | Strings2/ch14 muted (3263, CC7=0); no later unmute | Strings2 solo is not demonstrated |
| 11:59:54.431–11:59:55.527 | Both Bass and Piano enabled | First1.1 seconds of Bass test are not solo |
| 11:59:55.527–12:00:01.631 | Bass only, CC7/11=127/127, velocity57–61 | Valid Bass solo later, reported weak |
| 12:00:02.138–12:00:10.709 | Rhythm2 only:26 native samples and60 complete bridge NOTE_ON dispatch records, allch9; no Piano/Guitar noteOns | No event evidence of cross-family routing |
| 12:00:10.709–12:00:17.148 | Rhythm1 enabled while Rhythm2 remains enabled | Both rhythm parts, not Rhythm1 solo |

Mixer actions2881–4911 use override volume127. Unmute sends nativeCC7=127 rather than restoring raw style values54/62/52/81/79/100. Guitar expression also changes110→127 via the existing full mixer helper. This is separate UI/override behavior; it is not CC11 resending rawCC7=62. Solo/full-mix levels therefore cannot be compared as if controllers stayed constant.

Strings1 CC7 reads100 before mute,0 while muted,127 after unmute; no62 reset. All eight sampled Strings2 notes before mute read100/100. After mute no new Strings2 NOTE_ON appears. NativeCC7 and sequencer DROP_MUTED reflect the mute controls.

## Strings and Bass: narrowed causes

Strings1 noteOns3389/3395/3400 use Tyros0/49 at velocity26 and full controllers127/127. Corresponding offs3680/3684/3688 record13.680–13.682 seconds, but user mute at3499/3500 already silences the channel around9.55 seconds after those noteOns. Ledger duration is not audible hold or PCM envelope duration.

Neither universal short notes nor CC7 reset explains this Strings1 solo. Sample eligibility at velocity26, attenuation, envelope, velocity modulation and PCM energy remain unmeasured. Retain the selected voice until those establish a justified change.

Bass solo summaries4166/4245 read127/127 and velocity57–61. They include sustained notes and short ornaments: mean914 ms/zero short offs in one window, mean430.3 ms/two short offs in another. Weak Bass cannot now be explained solely by raw styleCC7=52, which was overridden during solo. Actual sample response/register/energy remains a separate hypothesis; no blanket gain or velocity normalization is justified.

## Rhythm2: verified missing backbeat, unverified timbre

Rhythm2 notes4278 onward use dedicated STANDAR PSR-SX bank128/PC0, requestedPC73, kitFallback1, original/output unchanged. During its valid solo onlych9 bridge noteOns are dispatched. Guitar/Piano-like listening descriptions do not establish that Rhythm2 routes to those voices. Effect tails, custom sample timbre and listening-window alignment have not been measured.

Previous Inspector104309 coverage remains valid: PC0 lacks key21 at28/42 and key31 at110. Warm playback continues to send31 (4283/4318). Missing backbeat therefore agrees with the known eligible-zone hole, rather than a drum CASM drop or mute.

MainD contains no source38/40 snare or42/44/46 hi-hat notes. Metallic sounds can involve key51/53 ride/bell reference roles and Rhythm1 percussion. Those XG labels do not prove custom SF2 timbre, and key21 is not assumed to be hi-hat.

All21-kit ranking remains unchanged: no213/213 candidate. PC36 covers205/213 but loses54 and has unverified numbered samples; its key21 sample identifiers also appear at81. PC1/24 cover181/213 but still lack21. Do not choose a kit by name, split kits per key or remap31→38 without compatible musical evidence.

## Next evidence and preserved boundaries

The existing event/controller trace has reached its evidence limit. Keep Build #758:

1. Strings2 has not been isolated here. If testing it, mute other accompaniment channels and unmute14, then allow a complete new MainD section so its notes actually begin.
2. Inspect Tyros0/49 zones/generators for Strings1 keys52/60/64 at26 and Strings2 keys72/84 at33–34. Needed evidence is eligibility, attenuation, attack/velocity modulation and sample audibility, or measured solo PCM. Current zone audit covers dedicated drums, not melodic Tyros/BASS.
3. Inspect Yamaha BASS raw8/17 at the actual low-register output notes/velocity57–61 with127/127. Do not infer a gain bug from subjective level alone.
4. Identify/audition candidate drum samples at31/110,21/28,42,33 velocity layers and54/110 before declaring compatibility. Another identical AllLog will not provide this missing sample identity.

This is an evidence boundary, not a user approval requirement. No new source fix or APK is claimed. User authorization to implement/build a minimal proven fix remains applicable once evidence establishes it.

Validation:372 accepted/matched samples,248 velocity matches/max11 ms, mute/native-controller solo windows,60 Rhythm2 bridge hits and complete-section intervals checked. No new production regression test/build for documentation-only continuation. Family gate, dedicated drum separation, CC11 fix, CASM, scheduler/timing, sustain/release and native libraries remain unchanged.
