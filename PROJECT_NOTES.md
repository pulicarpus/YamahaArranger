# YamahaArranger — Project Notes

## Authoritative baseline

- Current branch: fix/yamaha-panel-sustain-clean
- Audited commit: f85ba7641fd73c34cdb57a4fc9984a05ac5d1b93
- Repository: pulicarpus/YamahaArranger
- main is stale relative to the current branch and must not be used as the engine baseline until this branch is promoted.
- This audit did not modify application source code.

## Current architecture

### Style pipeline

Yamaha STY / PRS / SFF / SFF GE
-> native SMF parser
-> StyleSectionModel / StylePartModel
-> CASM policy
-> StyleSequencer
-> AudioEngineManager
-> BASSMIDI
-> Oboe

### MIDI/keyboard pipeline

Android MIDI
-> MidiInputManager
-> chord/keyboard routing
-> ArrangerBrain
-> StyleSequencer and AudioEngineManager

## Current authoritative files

| File | Role |
|---|---|
| app/src/main/cpp/style_parser.cpp | SMF/style markers + CASM parser |
| app/src/main/cpp/smf_reader.cpp | Standard MIDI parsing |
| app/src/main/java/com/yourapp/yamahaarranger/style/StyleRepository.kt | Kotlin style model builder |
| app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt | musical timeline, transitions, note ownership |
| app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt | live arranger state, Auto Fill, keyboard routing |
| app/src/main/java/com/yourapp/yamahaarranger/arranger/CasmNoteTransformer.kt | NTR/NTT/RTR note transformation |
| app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt | Kotlin audio facade |
| app/src/main/cpp/bassmidi_player.cpp | BASSMIDI, SF2 mapping, voice resolution |
| app/src/main/cpp/audio_engine.cpp | Oboe output |
| app/src/main/cpp/native_lib.cpp | JNI bridge |

## Reference blueprint

See:

- docs/ARRANGER_ENGINE_AUDIT.md
- docs/ARRANGER_ENGINE_BLUEPRINT.md
- docs/MIDI_VOYAGER_PRO_5.4.11_AUDIO_RESEARCH.md

External behavioral references:

- GigLad: https://www.deltarray.com/documentation/giglad/
- vArranger: https://www.varranger.com/features/
- One Man Band: https://www.1manband.nl/features.htm
- Arranger Keyboard: https://www.audiosdroid.com/arranger-keyboard-support
- Yamaha MIDI Song to Style: https://europe.yamaha.com/files/download/other_assets/4/2179884/MIDI_Song_to_Style_owners_manual_En_B0.pdf
- Yamaha PSR-E343 MIDI Reference: https://usa.yamaha.com/files/download/other_assets/4/329464/psre343_en_mr_a0.pdf

## What is already correct

### Transition architecture

- continuous master clock
- pending transition queue
- queued successor sections
- no global allNotesOff during seamless section change
- outgoing style note release is targeted
- Auto Fill exists
- Main A-D exists
- Intro/Ending/Fill successor logic exists when the section change starts while playing

### CASM

- CSEG parsing
- Ctab/Ctb2 parsing
- Cntt overrides
- NTR
- NTT
- RTR 0-5 representation
- High Key
- Note Limits
- Bass-On
- chord mute policy
- CASM diagnostics

### Audio

- BASSMIDI live path
- BASSMIDI NOTEOFF1
- Yamaha MSB/LSB bank handling
- Yamaha variation-bank normalization
- melody/drum SF2 roles
- async preload
- drum routing restoration
- voice-name-aware native resolver
- channel mixer
- master gain

### Keyboard

- split point
- RIGHT 1/2/3
- LEFT
- transpose
- sustain ledger
- release time
- MIDI OUT
- E343-style MIDI input handling

## Known bug sources

### P0.1 Voice resolver

File:
app/src/main/cpp/bassmidi_player.cpp

Current order still prefers same numeric program before semantic category matching.

Failure class:
Strings can resolve to Piano/E.Piano instead of a String preset.

Target order:
exact bank/program -> explicit mapping -> semantic category -> family match -> compatible numeric fallback -> Piano final fallback.

Do not modify CASM to fix this.

### P0.2 Realtime mutex

File:
app/src/main/cpp/bassmidi_player.cpp

render() and control/preload operations share mutex_.

Risk:
audio callback can wait during voice/program changes.

Target:
realtime render must not depend on a UI/file-operation mutex.

### P0.3 Note instance identity

File:
app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt

Current key:
sourceChannel:sourceNote

Risk:
repeated identical notes collapse into one active record.

Target:
section generation + source track + source channel + event index or another unique instance token.

### P0.4 Directional fills

File:
app/src/main/cpp/style_parser.cpp

ArrangerBrain has directional FillAB/BA/etc concepts, but the native StyleSection model currently only represents FillAA/BB/CC/DD.

Target:
preserve source fill direction instead of reducing it to a generic fill.

### P1.1 Idle Intro/Ending

File:
ArrangerBrain.kt

Target:
starting Intro while idle should execute Intro once then Main; starting Ending while idle should execute Ending once then Stop.

### P1.2 Timing model

Current:
wall-clock next-bar quantization + coroutine scheduler + master tick.

Target:
one authoritative musical tick for quantization and section transitions.

## Sustain

Panel sustain is currently implemented as a keyboard note ledger in ArrangerBrain plus native release control.

ACMP/chord notes are intentionally excluded from keyboard sustain.

The PSR-E343 MIDI reference identifies:
- CC64 = Sustain
- CC72 = Release Time

## Regression tests required before engine changes are considered safe

1. Main A -> Main B
2. Main B -> Main D
3. Main D -> Main A
4. Intro -> Main
5. Fill -> Main
6. Ending -> Stop
7. repeated same-note events
8. 2/4
9. 3/4
10. 4/4
11. 6/8
12. drum + melody
13. Strings voice resolution
14. Yamaha variation bank resolution
15. SF2 reload
16. sustain ON/OFF
17. release time changes

## Legacy documentation warning

Older README/project notes described:

- FluidSynth as the live engine;
- sine-wave placeholder as the active SoundFont path;
- incomplete CASM;
- the old short StyleSequencer.

Those statements are no longer authoritative for this branch.

## Development rule

Do not touch a subsystem merely because the old documentation says it is incomplete.

Always inspect the current branch first.

When a bug appears, classify it as:

parser -> CASM -> transition -> note ownership -> voice resolver -> BASSMIDI state -> realtime audio -> UI state.

Then change the smallest responsible boundary and add a regression test.

## Current audit result

The current branch is suitable to become the project main baseline after documentation promotion.

The branch should not be treated as bug-free. The four P0 issues above are the primary stabilization targets.
