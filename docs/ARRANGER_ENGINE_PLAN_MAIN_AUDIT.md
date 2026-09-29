# YamahaArranger — Arranger Engine Plan (Main Baseline Audit)

Date: 2026-09-29
Baseline audited: `main` @ `7bb971d3374e0a6a64049bc80cae98240ba41709`
Branch: `research/arranger-engine-plan-main-audit`

> **RESEARCH / PLAN ONLY.**
> No application source code is changed by this plan.

## 1. What the current main actually contains

The current `main` baseline is materially different from the earlier research branch.

### Already present

- Yamaha style parsing and section extraction.
- CASM Ctab/Ctb2 decoding.
- Yamaha CASM policy model.
- NTR/NTT transformation.
- RTR-aware live chord revoicing.
- Main A/B/C/D state.
- Fill AA/BB/CC/DD state.
- Intro/Ending state.
- Voice map extraction/display.
- channel-preserving playback.
- per-note active-voice tracking.
- exact transformed pitch reuse for note-off.
- locked-channel protection.
- SF2 playback through FluidSynth.
- Oboe low-latency output path.

The project history also contains explicit commits for CASM NTR/NTT, RTR, voice mapping, and chord routing.

### Important baseline reality

The current `main` source inspected here uses **FluidSynth**, not the BASSMIDI engine branch.

Therefore:

- BASSMIDI is a future engine option/reference, not the current main baseline.
- Do not design the next fix around an assumed BASSMIDI implementation.
- First stabilize the actual main architecture.
- Any later BASSMIDI integration must preserve the arranger contracts established here.

## 2. Current architecture vs researched arranger architecture

### Current

```
Keyboard
   ↓
ChordDetector
   ↓
ArrangerBrain
   ↓
StyleSequencer
   ↓
Coroutine delay/tick approximation
   ↓
AudioEngineManager
   ↓
NativeAudioBridge
   ↓
AudioEngine / FluidSynth
   ↓
Oboe
```

### Target

```
Keyboard / UI
      ↓
Performance State
      ↓
Arranger Conductor
      ├── master musical clock
      ├── section request
      ├── successor
      ├── switch precision
      └── chord timeline
      ↓
Style/CASM Runtime
      ├── CSEG/Sdec selection
      ├── NTR
      ├── NTT
      ├── note limits
      └── RTR
      ↓
Note Ownership / MIDI Router
      ├── section ownership
      ├── generation
      ├── destination channel
      └── targeted NOTE_OFF
      ↓
Voice / Bank State
      ↓
Non-blocking Audio Event Path
      ↓
FluidSynth/BASSMIDI/native renderer
      ↓
Oboe
```

The key work is therefore not "rewrite everything". It is to move the current implementation toward these explicit contracts one layer at a time.

## 3. Critical gaps found in main

### GAP A — Section transition is callback chaining, not a conductor

Current Main→Main behavior:

- update UI section;
- choose Fill;
- play Fill with `loopLimit=1`;
- `onComplete` calls `playSection(target)`.

This is functional but is not yet an explicit arranger transition state.

Risks:

- timing depends on coroutine completion;
- no explicit successor object;
- no queued transition priority;
- no BAR/BEAT/ANY_BEAT policy;
- no persistent master musical position across section changes.

Target model:

```
IDLE
 ↓
PLAYING
 ↓ request
REQUESTED
 ↓ legal switch point
FILL / TRANSITION
 ↓ successor
PLAYING(target)
```

### GAP B — `StyleSequencer.play()` starts by calling `stop()`

Current `play()` calls `stop()`, and `stop()` performs:

```
cancel playback job
allNotesOff()
clear activeVoices
```

This is the opposite of a seamless arranger transition.

It can cause:

- all style notes to be released at once;
- keyboard/right/left ownership to be mixed with accompaniment;
- audible gaps;
- loss of notes that should survive a boundary;
- no distinction between chord switch and section switch.

**Do not immediately remove this behavior blindly.**

First instrument and prove exactly which notes/channels are being killed during each transition.

### GAP C — global `allNotesOff()` remains in the section path

A mature arranger needs targeted release.

Required future invariant:

> A section transition releases only notes owned by the outgoing style generation.

Keyboard voices, sustain voices, drum events, and the target section must not be killed accidentally.

### GAP D — current note ownership key is too coarse

Current key:

```
channel + sourceNote
```

This can collide when the same source note is active more than once within a generation/track.

Target ownership should include at least:

- section/generation;
- source track;
- source channel;
- source note;
- destination channel;
- destination note;
- event/instance identity where needed.

### GAP E — RTR implementation is only partially policy-driven

The current live chord code explicitly handles RTR=Stop and otherwise revoices by transposing.

That is not equivalent to the complete Yamaha RTR set:

- Stop
- Pitch Shift
- Pitch Shift to Root
- Retrigger
- Retrigger to Root
- Note Generator

Plan:

- first build a behavior matrix;
- then verify each style/part against actual Yamaha examples;
- only then implement missing behaviors.

### GAP F — voice selection still contains a known unsafe heuristic

Current `StyleSequencer` can infer GM program numbers from voice-name trailing digits and keyword fallbacks.

The source itself documents the limitation: Yamaha voice IDs do not necessarily equal GM program numbers.

Therefore voice correctness must eventually use:

```
Yamaha voice identity
→ MSB
→ LSB
→ Program
→ SF2/XG mapping
```

not:

```
voice name → guessed GM program
```

### GAP G — drum detection is hard-coded in playback

Current playback treats channel 9 as drum and skips transposition.

That is a useful safety baseline, but the research says percussion identity must come from the style/CASM/channel role, with MIDI channel convention handled explicitly.

Target:

- explicit zero/one-based channel normalization;
- Rhythm1/Rhythm2 identity;
- drum preset identity;
- no chord transposition;
- no accidental melody routing.

### GAP H — audio callback has a blocking mutex

Current FluidSynth path uses a global `g_synthMutex`.

The audio callback calls:

```
soundFont.render()
```

which takes that mutex.

MIDI note/program operations take the same mutex.

Therefore a control-thread operation can delay the audio render callback.

This directly matches the class of problems found in arranger products' release histories:

- audio pause;
- transition stall;
- audio-engine overload;
- crackle;
- note hanging around control changes.

This is a **measurement-first** priority.

Do not replace the mutex blindly. First measure:

- callback duration;
- lock wait duration;
- render duration;
- program-change duration;
- mixer/voice-change duration;
- SF2 load/unload duration.

### GAP I — SF2 load/program changes are not separated from realtime scheduling

Current `loadSoundFont()` and preset changes call the native engine directly.

The arranger plan must establish:

```
control/preparation
      ↓
prepared state
      ↓
short realtime event
```

instead of performing expensive resource work at a transition boundary.

## 4. Lessons imported from similar applications

### GigLad

Adopt as behavioral reference:

- continuous musical timeline;
- explicit successor;
- configurable switch precision;
- separate chord-switch and section-switch policy;
- per-note ownership;
- section-boundary safety NOTE_OFF;
- percussion bypass;
- instrument/resource preloading;
- audio/MIDI separation.

### vArranger

Adopt:

- explicit arranger routing layer;
- Auto Fill;
- Sync Start/Stop;
- Hold;
- Manual Bass/Bass to Lowest;
- controller/knob mapping;
- style track routing independent from sound output.

### One Man Band

Adopt:

- style loading without stopping musical transport;
- controller assignment;
- multiple melody parts;
- tempo/control mapping;
- style editor concepts as future diagnostics.

### Android Arranger Keyboard

Use as Android regression reference:

- Main A-D transitions;
- Fill/Intro/Ending;
- SFF2;
- SF2/SF3;
- Sync Start;
- real-device latency behavior.

Its recent releases specifically mention smoother Main A-D transitions, so transition behavior should be a first-class Android regression category.

### Android MIDI Arranger

Use as compatibility reference:

- Yamaha Format 1;
- SoundFont routing;
- chord-pattern behavior;
- known Android latency/import limitations.

### Yamaha Style Studio / SFF2 research

Use for binary truth:

- multiple CSEG blocks;
- section grouping;
- Ctab/Ctb2 differences;
- explicit channel roles;
- NTR/NTT/HighKey/NoteLimit/RTR.

Do not assume the first CSEG is the entire style.

### Korg Pa

Use as cross-vendor validation:

- Style Element;
- Chord Variation;
- NTT;
- separate track transposition;
- percussion bypass.

## 5. Revised implementation roadmap

### Phase 0 — Baseline freeze

No behavior changes.

Capture:

- current main APK;
- current logs;
- LoveSong/S687;
- 2/4;
- 3/4;
- 4/4;
- 6/8;
- Main A/B/C/D;
- Fill;
- Intro;
- Ending;
- sustain.

Record:

- first NOTE_ON;
- first NOTE_OFF;
- transition request time;
- actual transition time;
- first target-section event;
- program-change time;
- audio callback duration.

### Phase 1 — Transition instrumentation

No behavioral redesign yet.

Add diagnostics only for:

- transition requested;
- requested target;
- source section;
- fill section;
- successor;
- master/section tick;
- note ownership count;
- allNotesOff invocation;
- program/voice apply start/end;
- first target NOTE_ON.

Goal: know exactly why a gap occurs before changing behavior.

### Phase 2 — Musical conductor model

Introduce the design, not the UI:

- master musical tick;
- section request;
- pending transition;
- successor;
- switch precision;
- section generation.

Do not reset the master clock on Main/Fill changes.

### Phase 3 — Ownership-safe transitions

Replace conceptual global release with:

```
outgoing generation
→ owned notes only
→ targeted NOTE_OFF
```

Keep global all-notes-off only for:

- explicit STOP;
- panic;
- fatal recovery.

Not normal Main/Fill transitions.

### Phase 4 — CASM/RTR fidelity

Before changing parser:

- create a behavior matrix for real styles;
- compare CSEG/Ctab/Ctb2;
- test Bass, Chord, Pad, Phrase and Rhythm separately.

Then implement only proven missing behaviors.

### Phase 5 — Voice/bank fidelity

Replace voice-name guessing with explicit Yamaha voice identity.

Validate:

- MSB;
- LSB;
- program;
- drum bank;
- Rhythm1/Rhythm2;
- SF2 preset lookup.

### Phase 6 — Realtime audio isolation

Measurement first.

Target:

- no blocking lock in audio callback;
- no SF2 load in audio callback;
- no large program/mixer operation on the callback;
- preloaded voice state;
- queued/control-thread changes;
- bounded realtime event dispatch.

The renderer remains FluidSynth initially because that is what main actually uses.

BASSMIDI can be evaluated separately after the main baseline is stable.

### Phase 7 — Time-signature and transition matrix

Run:

| Meter | Main A→B | Main→Fill→Main | Intro→Main | Ending | Chord during Fill | Sustain |
|---|---|---|---|---|---|---|
| 2/4 | test | test | test | test | test | test |
| 3/4 | test | test | test | test | test | test |
| 4/4 | test | test | test | test | test | test |
| 6/8 | test | test | test | test | test | test |

### Phase 8 — Controller / multifunction knob

Only after transport timing is stable.

Target selector:

- Tempo;
- Transpose;
- Release;
- Volume;
- Reverb/Chorus;
- voice/style parameter.

Do not couple knob processing to the audio callback.

### Phase 9 — APK acceptance

Required scenarios:

1. Main A → Main B
2. Main B → Main C
3. Main D → Main A
4. Main → Fill → target Main
5. Fill → correct successor
6. Intro → Main
7. Main → Ending
8. chord change during Fill
9. chord change on boundary
10. sustain during transition
11. drum + melody together
12. style switch while playing
13. SF2 program/bank change
14. 2/4
15. 3/4
16. 4/4
17. 6/8

## 6. Non-goals

Until evidence requires it:

- do not rewrite the entire arranger;
- do not replace FluidSynth immediately;
- do not touch UI that is unrelated;
- do not modify sustain blindly;
- do not modify CASM because of an audio dropout;
- do not introduce global NOTE_OFF as a shortcut;
- do not merge research branches solely by branch name.

## 7. Definition of done

A phase is complete only when:

- source behavior is understood;
- logs prove the relevant event sequence;
- APK builds;
- device test reproduces the intended behavior;
- no known regression appears in previous scenarios.

## 8. Golden rule

> **First make the event lifecycle deterministic; then make it musical; then make it fast.**

The current main baseline already has meaningful CASM/NTR/NTT/RTR work. The next major gain is therefore expected from **transition lifecycle + note ownership + realtime audio isolation**, not from blindly rewriting CASM.
