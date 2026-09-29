# YamahaArranger

Android Yamaha-style arranger keyboard engine.

## Current authoritative baseline

Branch: fix/yamaha-panel-sustain-clean
Commit audited: f85ba7641fd73c34cdb57a4fc9984a05ac5d1b93

This branch is the current development baseline. The old main branch is stale relative to this branch.

The current engine is no longer the original Phase-1/Phase-2 prototype described by older documentation. The live SoundFont path is BASSMIDI, the arranger has a continuous transition clock, CASM/NTR/NTT/RTR handling, Yamaha bank-aware preset routing, sustain/release controls, mixer overrides and an SF2/style inspector.

## Architecture

Yamaha STY / PRS / SFF / SFF GE
-> native SMF/Yamaha parser
-> section + part model
-> CASM policy
-> StyleSequencer
-> BASSMIDI
-> Oboe
-> Android audio

Keyboard/MIDI path:

Android MIDI / E343
-> MidiInputManager
-> chord detector / keyboard routing
-> ArrangerBrain
-> StyleSequencer + AudioEngineManager
-> BASSMIDI / Oboe

## Current engine capabilities

### Yamaha style engine

- Intro A/B/C
- Main A/B/C/D
- Fill AA/BB/CC/DD
- directional Fill model in ArrangerBrain, pending full native parser representation
- Ending A/B/C
- continuous master musical clock
- seamless section queueing
- Auto Fill
- CASM policy selection
- NTR / NTT / RTR
- note limits and high-key handling
- Bass-On handling
- style mixer/controller state
- style channel overrides
- 2/4, 3/4, 4/4 and 6/8 meter model

Yamaha documents the standard arranger structure as Intro I-III, Main A-D, Fill In A-D, Break and Ending I-III, with eight style parts: Rhythm 1-2, Bass, Chord 1-2, Pad and Phrase 1-2.

## Audio engine

BASSMIDI is the current live SoundFont engine.

Current features:

- BASS + BASSMIDI
- BASS_MIDI_NOTEOFF1
- 1000 configured MIDI voices
- PPQN 1920
- configurable SRC quality
- Yamaha bank MSB/LSB preservation
- Yamaha variation-bank normalization
- separate melody/drum SoundFont roles
- one-SF2 melody + drum mode
- BASSMIDI FONTEX2 mappings
- asynchronous sample preload
- preset enumeration
- voice-name-aware preset resolution
- channel volume/pan/expression/reverb/chorus
- master gain
- panel sustain
- Yamaha-style release time on RIGHT voices

FluidSynth remains in the repository as a legacy dependency and is not the authoritative live SoundFont path.

## Current known high-priority problems

These are documented in docs/ARRANGER_ENGINE_AUDIT.md.

1. Voice resolver ordering can select the same numeric program before semantic String/category matching.
2. BASSMIDI render and control operations share a mutex, creating realtime contention risk.
3. Style note ownership uses source-channel/source-note instead of unique event identity.
4. Directional fills are richer in ArrangerBrain than the current native StyleSection model.
5. Intro/Ending selected while idle are not yet guaranteed to become one-shot successor sequences.
6. Transition timing still combines wall-clock quantization with a coroutine-driven master clock.
7. Regression tests for transitions, repeated notes, meters and voice resolution are incomplete.

These are targeted stabilization items. The engine should not be rewritten from scratch.

## Sustain / release

The current design intentionally separates:

- panel sustain for keyboard voices;
- ACMP/chord notes;
- style note lifecycle;
- release time.

The PSR-E343 MIDI reference identifies CC64 as Sustain and CC72 as Release Time.

## Diagnostic tools

- SF2/style inspector
- String/CASM trace
- exported DebugLog
- native BASSMIDI voice-resolution logs
- SF2 preset enumeration
- style marker/CASM dumps

MIDI Voyager reverse-engineering notes are stored at:

docs/MIDI_VOYAGER_PRO_5.4.11_AUDIO_RESEARCH.md

## Audit and blueprint

Read these before modifying the engine:

- docs/ARRANGER_ENGINE_AUDIT.md
- docs/ARRANGER_ENGINE_BLUEPRINT.md

The audit compares the current implementation against:

- GigLad
- vArranger
- One Man Band
- Android Arranger Keyboard
- MIDI Voyager Pro
- Yamaha arranger/SFF behavior

## Regression strategy

Before changing engine behavior, test at minimum:

- Main A -> Main B
- Main B -> Main D
- Main D -> Main A
- Intro -> Main
- Fill -> Main
- Ending -> Stop
- repeated identical notes
- drum + melody simultaneously
- Strings voice resolution
- 2/4
- 3/4
- 4/4
- 6/8
- SoundFont reload
- sustain ON/OFF
- release-time changes

## Development rule

Do not replace working arranger subsystems blindly.

When a bug appears, identify which boundary is failing:

1. parser
2. CASM policy
3. transition scheduler
4. note ownership
5. voice resolver
6. BASSMIDI preset state
7. realtime audio path
8. UI/performance state

Then fix only that boundary and add a regression case.

## Build

The project contains native C++/NDK code and should be built through GitHub Actions on the user's Android-only workflow.

Build workflow:

.github/workflows/build.yml

SF2 inspection workflow:

.github/workflows/inspect-sf2.yml

