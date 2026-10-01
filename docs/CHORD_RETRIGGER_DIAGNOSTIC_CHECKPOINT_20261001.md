# Chord replacement / source ownership diagnostic — 2026-10-01

## Continuation and proven evidence

Branch `diag/audio-path-presence`, parent HEAD `c10f4b490316d7b577f37021cf5302a04bd0ac22`. Application baseline: Build #762 / source `f102b2bdfb5df0204b3a4ce33c7c6bf070d87aab`. Continue PROJECT_NOTES.md and CHORD_COMPACT_REPORT_CHECKPOINT_20261001.md; no rollback or fresh resolver investigation.

Input: `YamahaArranger_ChordCapture_20261001_200429.txt`, 47,040 UTF-8 bytes / 172 lines, SHA256 `4f7bb01d2e746a055930ab85bce9c15e41d4ab62172a2e019755fde78c24acd3`. Love Song / MainD; two C→F→G→C cycles. Device wall clock corresponds to UTC+7; timestamps below are the report's epoch milliseconds, not inferred from the filename.

All 87 exported native rows are ch11, requested bank/PC13333:0, destination104:21:0, live and expected font1:bank0:PC0 Yamaha ConcertGrand, familyPIANO, liveOk/match/init1, mapGen9, CC7/11=81/127, error0. All19 NOTE_POST and23 OFF_POST accepted. This does not establish the state of other channels: style captured260/exported69 (191 omitted); native captured407/exported87 (320 omitted); both captureDropped0. G→C replacement readbacks and later cycles were omitted. Full Inspector is neither needed nor requested.

The first two changes are joined end to end using source note identity, chordId and eventId:

| Change / chordId | eventId | held onId | original | old→new | velocity | old OFF / new ON native order |
|---|---|---|---|---|---|---|
| C→F /1036 |1038 |1022 |58 |60→57 |81 |1816 /1817 |
| C→F /1036 |1039 |1023 |63 |64→65 |86 |1818 /1819 |
| C→F /1036 |1040 |1024 |65 |64→65 |80 |1820 /1821 |
| F→G /1177 |1183 |1161 |58 |57→59 |80 |2059 /2060 |
| F→G /1177 |1186 |1170 |63 |65→62 |86 |2065 /2066 |
| F→G /1177 |1187 |1171 |65 |65→67 |81 |2068 /2070 |

Six replacements preserve source11→destination11 and original velocity; no repeated NOTE_ON for the same replacement eventId appears. OFF precedes ON, both accepted; actual preset is ConcertGrand independently at all six PRE and POST pairs. The ch11 source header PC0 and requested voice Piano/Piano rt support its provenance; the raw part name contains 0x1A bytes and is not a trustworthy human-readable label.

Normal style events1022/1023/1024 occur at1790859845993/5997/5999, keys60/64/64, velocities81/86/80. Chord1036 begins1790859846119: these notes are only126/122/120ms old. Replacements arrive1790859846126/6129/6133 with the same original velocities (actual old-on→new-on133/132/134ms). Replacement1039 and1040 send key65 four milliseconds apart, but have distinct original keys63/65 and held IDs1023/1024. Normal events1068/1069 also send key65, at1790859846835/6841 (six milliseconds apart), velocities72/62. Normal event1058 is key57/velocity60, while replacement1038 is57/81. Therefore key coalescence exists in normal style too; collapsing equal destination keys would change valid source-note behavior and cannot be justified as removal of a proven duplicate event. Velocity is MIDI data, not measured acoustic gain.

## Mechanism and limits

The unchanged source maps RTR1/2 to `retrigger=false`, RTR3/4 to `true`. In updateHeldPitch that boolean changes only the diagnostic label. Every changed target in either mode sends NOTE_OFF then NOTE_ON with the held source velocity; unchanged targets return without a send. Thus the implementation currently creates a new MIDI onset for what it calls pitch shift, and can repeat a recent full-velocity Piano onset at chord time. It does not perform an in-place per-voice pitch change or preserve a voice's envelope/phase.

This is a concrete implementation mechanism and plausible source of an accent. It does **not** prove the acoustic contribution of each SF2 voice/release tail or the exact envelope/voice handling of the Yamaha hardware reference. No measured Yamaha/PCM comparison or sample/envelope state exists in this file. Normal-vs-retarget same-key ownership and sustain were not exported. A playback fix is therefore withheld. No gain/voice remap, deletion of ch11, note deduplication, channel pitch bend or RTR bypass is proposed as a safe evidence-backed fix. A future true per-voice pitch-shift solution needs engine capability and polyphonic/ownership evidence; a channel-wide bend is not a safe minimal change for independently transformed chord notes.

## Diagnostic only, compact v2

SAVE CHORD (SMALL) remains the same Android flow and hard48KiB UTF-8 file cap. No new permanent log, PCM/render work, or diagnostic audition MIDI.

- Style captures a bounded32-row/250ms preceding ch11 context as well as750ms following context. CHORD_CHANGE and ch11 retarget decisions come first in export, then ch11 normal context and other decisions; auxiliary SELECT/OFF rows follow. This corrects #762's priority defect without altering sends. Retarget decisions now carry NTR/NTT/RTR, OFF_ON method, held onId/originTick, and, for ch11, other source-ledger owners on the old/target pitch (count and first4 identities). Normal events retain source part index, absolute tick, and observed lagUs. Concurrent source-ledger mutation produces explicit unknown evidence; the observer adds no synchronization to playback.
- Native pairs adjacent matching PRE/POST rows into one whole event unit, preserving independent state references b/a, native order and wall timestamps. S state definitions retain requested/destination/live/expected preset and readback identity; a preset change always gets a distinct definition. Missing/mismatched pairs remain explicitly unpaired. Priorities: ch11 actual retarget, other Piano/mismatch, baseline/control, normal ch11, other retarget. Retarget endings cannot be displaced by earlier normal context. Omission counters remain explicit and whole units/definitions are kept together.
- During explicit capture only, the native observer models accepted ch11 NOTE requests by key since ARM, with a bounded FIFO16/key, preceding ON id/op/age, oldest observed age, before/after count and same-event/key repeat flag. Failed sends do not update the model; observed NOTES_OFF clears it. Pre-ARM notes are unknown. This is a request ledger, **not a BASS voice count**: FIFO attribution, sustain, release tails, overlapping sample zones and voice stealing are not established by it. No count gates/deduplicates playback. ledgerOverflow is explicit.
- Read-only BASS MIDI_EVENT_SUSTAIN is added for ch11; CC field order7/11/64. Numeric SDK sustain value is reported without assuming CC semantics or changing any controller.

## Boundaries and validation

Definition-based comparison against verified remote #762 source:15 native playback functions and8 sequencer functions are text-identical (includes updateHeldPitch, both chord handlers, root transform, policy, controllers and startPlayback). StyleSequencer changes only diagnostic strings/snapshot helper; the scheduled send/transform/order code is unchanged. Native player changes only captureChordState (one CC64 getter); the new ledger/formatter live inside the diagnostic observer. Resolver/family gate, CC11 fix, drum kits, CASM transformations, scheduler/timing policy, sustain/release behavior, SF2 parser, native libraries and workflow remain unchanged. Observer overhead while armed is bounded but not claimed to be zero.

Host **363 checks pass**:210 family policy,60 native mock,48 observer/capture,30 SF2,15 drum coverage. New checks cover normal/retarget same-key requests, duplicate event distinction, accepted/rejected offs, age/FIFO limitations, independently changed PRE/POST state definitions, unpaired identities, late ch11 retarget priority, byte caps, and getter-only sustain. Existing exact MIDI history equality remains. Targeted JVM source now has23 cases (5 expression,6 drum profile,12 chord), including real held-ledger collision observation with capture OFF/ON send equality and pre-window/tick/priority/filter checks. Local Android/Kotlin toolchain unavailable; CI confirmation follows below.

## Android test

Warm the same fonts/style, MainD/full mix. Arm CAPTURE once with C already playing; C→F→G→C,2–3s per chord. Hold final C another2s so normal notes can be compared. END→STOP→SAVE CHORD (SMALL). Send only YamahaArranger_ChordCapture_*.txt and which transition sounded accented versus C held. Do not send the large Inspector or repeat the same AllLog.

## Publication

Source commit, successful APK build and artifact identity will be recorded after CI. The new APK is diagnostic, not an audible fix.
