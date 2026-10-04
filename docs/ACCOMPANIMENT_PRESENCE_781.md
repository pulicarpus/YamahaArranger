# Accompaniment presence regression after device build 781

Baseline: `6bc5f3aa006f908f4cc98d39ba6d7449ee3c9a84` (build 781).
Working branch: `fix/accompaniment-presence-781`, directly descended from that
commit. Device evidence: same Love Song.T547.prs / four managed fonts, app
zero-based Ch8/9/11 audible, ACMP working. This patch does not reactivate Stage 3.

## Proven mechanisms, and limits of the diagnosis

1. A failed `BASS_MIDI_StreamSetFonts` during **one channel preset change**
   previously erased the admitted melodic route of **every** initialized melodic
   channel. Production-player fault injection reproduces the collective loss:
   the new test fails on unchanged Ch10 against baseline 781. This can silence
   Bass/Chord/Pad/Phrase and keyboard channels even when their old native font
   table is intact. No font handle is freed by this operation.

   The fix retains other channels only after `StreamGetFonts(FONTEX2)` verifies
   every field of the complete last successfully published table. The changed
   channel still fails closed. Altered/unreadable tables retain global fail-closed
   behavior. Font replacement failure behavior remains unchanged. Successful
   routing, preload NOWAIT, FONTEX2, valid-owner and NOTE_OFF paths are unchanged.

2. Jules PR 31 identifies a real latent name-classification defect, but was based
   on main rather than build 781. We did not merge the PR or use its copied test
   predicate. The production sequencer used `startsWith("dr")` and broad
   `contains("dr")`, misclassifying DreamPad/DriveGtr/DrawbarOrgan. Role decisions
   now agree with the existing native channel contract: Rhythm1/2 destinations
   8/9, melodic destinations 10..15. Drum-name abbreviations require delimiters.
   Real scheduler tests verify bank, transformed pitch, velocity and NOTE_OFF.
   **Those names are absent from the tested Love Song**, so this is not claimed
   as its device root cause.

3. Baseline 781 already forwards the missing audible parts through the real
   sequencer/transformer. The native map-failure trigger on the tester's device
   has not been captured; therefore the actual device root cause and restoration
   of audibility remain unconfirmed. We do not open CASM masks or change musical
   policy to force more notes. The new bounded per-part report identifies the
   native gate or zero controllers in the same APK rather than adding a series
   of diagnostic builds.

## Actual pipeline checked

`native StyleParser/SMF/CASM -> ParsedStyle -> selected source CASM policy /
 destination -> StyleSequencer -> CasmNoteTransformer -> noteOnStyleChannel /
 AudioEngineManager -> NativeAudioBridge -> BassMidiPlayer::setChannelPreset /
 family selection / FONTEX2 / CC7+CC11 -> BASS_MIDI_StreamEvent(NOTE)`.

NOTE_OFF still uses the established sequencer ledger / valid-owner path.
`CasmNoteTransformer`, `AudioEngineManager`, `native_lib`, `AcmpChordAnalyzer`,
CASM decoding, section timing and production drum resolver are byte-unchanged.
No bank/program/velocity/gain/family-score redesign is included.

## Fixture per-part evidence

Real production Kotlin scheduler, native-parser archived Love Song fixture,
one MainD pass, C major. Raw counts here are destination-attributed events in
**this section**, not the all-section totals displayed in the export.

| Part / app channel | Raw ON | CASM-admitted / after transform / audio boundary | Loss reason | Source request MSB:LSB / rawPC |
|---|---:|---:|---|---|
| Rhythm1 / 8 | 117 | 117 | none | 127:0 / 73 |
| Rhythm2 / 9 | 96 | 96 | none | 127:0 / 73 |
| Bass / 10 | 19 | 19 | none | 8:4 / 17 |
| Chord1 / 11 | 102 | 70 | 32 intentional CASM mask/range rejects | 104:21 / 0 |
| Chord2 / 12 | 168 | 168 | none | 8:16 / 1 |
| Pad / 13 | 3 | 3 | none | 8:5 / 49 |
| Phrase1 / 14 | 2 | 2 | none | 8:5 / 49 |
| Phrase2 / 15 | 0 | 0 | absent from this style | none |

All 15 sections, C major and C minor, preserve the baseline per-destination
forward counts. MainC also explicitly checks every known count. A separate
fixture includes all eight roles, including Phrase2, and verifies all admitted
notes reach the actual audio boundary. No part is manufactured for Love Song.

The unchanged family resolver over the four exported font inventories selects
MELODI bank8/PC17 BASS, bank8/PC1 Steel Guitar, bank0/PC0 Yamaha ConcertGrand and
Tyros bank0/PC49 t4 strings slow for the melodic requests. These are metadata
selection evidence, not proof that the actual device samples rendered. No
fallback candidate or drum semantic winner is changed.

Native production-player tests check all six melodic part routes, actual API
preset readback, controller preservation, map-failure isolation, fail-closed
unknown table, recovery on a successful retry and read-only export. Existing
native tests cover Rhythm1/2, owner/preload/expression and multi-font behavior.
The existing happy-path production MIDI trace remains byte-identical to the
older protected baselines. The hash guard now explicitly distinguishes ten
unchanged protected files from five reviewed accompaniment patches; the old
776 hash manifest is not rewritten to claim equivalence.

An additional local real Linux BASS/BASSMIDI experiment compiled the actual
production player and rendered nonzero PCM for Ch8..14 using **synthetic tone
fonts with matching metadata addresses**. It verifies the SDK addressing path,
not user-SF2 audibility, timbre or Android performance. None of those synthetic
fonts are shipped or used as semantic evidence.

## Device test using this APK

1. Fresh app start, load the same four SF2 and Love Song; allow font loads to
   complete. Keep accompaniment mixer/locks unchanged from the known test.
2. Play C major MainD, plus Intro/Main/Fill/Ending and chord changes. Check ACMP
   still responds and whether Bass/Guitar/Strings Ch10/12/13/14 are audible.
3. STOP, open SF2/STYLE INSPECTOR, select **SAVE PARTS (SMALL)**. Share the single
   `YamahaArranger_PartPresence_*.txt` in Downloads/YamahaArranger.

The report is at most 48 KiB, scheduler detail at most 16 KiB with explicit
omitted-row count. Fixed scheduler counters distinguish missing CASM from
intentional mask/range rejection and track locked/muted/reserved/articulation
losses, before/after transform and bridge calls. Native fixed counters expose
attempted and accepted NOTE_ON, family/map gate, engine/send failures, zero
CC7/CC11, map-install failures, request/selected/live preset, font/path,
generation and last note eligible zone count. Snapshot is read-only and sends
no MIDI. Native counts are process-lifetime explicit style-origin events;
scheduler counts reset on style voice-map load. They are not silently equated.
Live preset readback is BASS's latest played voice; eligible zones are metadata
for the last note only, not a full PCM or all-velocity assertion.

Accepted BASS events alone do not prove audible samples. If the device remains
quiet with accepted counts and nonzero controllers, inspect reported resource /
last-note zone evidence before any further musical change. This milestone does
not change melody, RIGHT/LEFT, ACMP split, CASM/NTR/NTT/RTR or drum mapping.
