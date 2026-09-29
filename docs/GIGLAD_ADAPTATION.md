# GigLad Arranger Engine Adaptation Research

Date: 2026-09-29

## Purpose

Use publicly documented GigLad arranger behavior as an engineering reference for YamahaArranger Android. This is behavioral adaptation, not code copying.

## High-value findings

1. **One continuous musical timeline**
   - GigLad represents a style as one MIDI timeline with sections marked inside it.
   - Section changes therefore do not require resetting the master clock.
   - YamahaArranger should keep one monotonic arranger tick across Main/Fill/Intro/Ending transitions.

2. **Section successor model**
   - A Fill can have an explicit successor (target section, previous-playing section, or ending).
   - The Fill is therefore a one-shot transition object, not another looping Main.
   - YamahaArranger should model Fill -> target as a queued state transition.

3. **Section switch precision**
   - Normal sections switch on a beat boundary.
   - Fill sections may switch on any beat / finer precision.
   - This should be explicit policy rather than implicit scheduler behavior.

4. **Chord-switch policy is separate from section-switch policy**
   - GigLad documents Soft Retrigger, Hard Retrigger and Stop behavior when the chord changes.
   - Section changes separately support Retrigger behavior that computes the notes which should be active at the new section position.
   - YamahaArranger must not use one global NOTE_OFF strategy for both cases.

5. **Cross-boundary note safety**
   - GigLad flags events whose NOTE_ON starts in one section and NOTE_OFF ends in another as problematic.
   - It has an automatic safety NOTE_OFF at the section boundary.
   - For YamahaArranger, ownership must be tracked per style note/channel so a transition releases only outgoing style notes, never keyboard voices or unrelated channels.

6. **CTA / CASM separation**
   - Percussion is explicitly bypassed by chord transposition.
   - Melody/bass/accompaniment tracks use distinct transposition policies.
   - CTA can vary by section and even by note range.
   - YamahaArranger should keep CASM transformation before MIDI/audio dispatch and never let drum notes pass through chord transposition.

7. **External-style sound mapping**
   - GigLad acknowledges Yamaha styles use more than GM 128 sounds.
   - External style loading has a dedicated MSB/LSB/PGM mapping stage.
   - This is directly relevant to YamahaArranger's BASSMIDI bank routing and SF2 normalization.

8. **Runtime mixer overrides**
   - GigLad separates style-defined MIDI messages from live mixer overrides and can suppress incoming volume messages while playing.
   - YamahaArranger should preserve user mixer state against later style CC/program messages where appropriate.

9. **Memory/preset separation**
   - GigLad keeps instruments available through a memory pool and supports instrument changes on the same track.
   - For Android, avoid reloading SF2/sample resources on every section transition.

10. **Audio/MIDI engine isolation**
    - GigLad's release history explicitly notes a design where MIDI and audio engines run in parallel.
    - Its history also records fixes for audio pauses, section-switch overload, sustain hanging, and audio clicks.
    - YamahaArranger should keep scheduling/clock work off the audio callback and avoid blocking locks in the realtime audio path.

## Direct mapping to YamahaArranger

### StyleSequencer.kt

Current implementation already has a continuous master clock and queued seamless transitions. Keep that architecture.

Next hardening:
- replace implicit transition behavior with an explicit transition state:
  IDLE -> PLAYING -> REQUESTED -> FILL -> SUCCESSOR -> PLAYING
- preserve masterTimelineTick through every state.
- track outgoing notes by section/channel/source-note.
- release only notes owned by the outgoing style section.
- apply target section voices before its first audible event when possible, but never block the realtime audio path.
- add configurable switch precision: BAR, BEAT, ANY_BEAT.

### CasmNoteTransformer.kt / CASM policy

Add explicit policy names equivalent to:
- SOFT_RETRIGGER
- HARD_RETRIGGER
- STOP
- BYPASS

Use BYPASS for rhythm/percussion.

Chord changes must never call global allNotesOff.

### BassMidiPlayer

Keep the existing Yamaha MSB/LSB normalization and FONTEX2 routing. The GigLad research confirms that Yamaha extended banks need a mapping layer rather than assuming GM-only program numbers.

Important existing behavior to preserve:
- separate drum and melody roles
- Yamaha bank LSB preservation
- bank-aware preset lookup
- channel drum/percussion state restoration after SF2 reload

### AudioEngineManager / native audio

Adopt the GigLad lesson that section changes must not instantiate/reload instruments synchronously in the audio path.

Preload/cache instruments and only send MIDI events at transition time.

## Release-history lessons to test against

GigLad has documented fixes for:
- audio pause during arranger playback
- MIDI clock regression
- melody note hanging during split-point changes
- Auto Fill / successor handling
- sustain-pedal-off hanging notes
- note hanging in certain styles
- section-switch audio-engine overload
- chord changes arriving late
- instruments stopping unexpectedly
- wrong Yamaha bass channel mapping
- muted-track audio crackle
- multiple time signatures
- fills changing at sub-beat precision

These are not proof that GigLad uses the same internal implementation, but they are strong regression-test categories for YamahaArranger.

## Proposed implementation order

1. Add transition state/ownership invariants without changing current audio routing.
2. Add section successor + explicit switch precision.
3. Harden per-note ownership and targeted NOTE_OFF.
4. Separate chord-switch policy from section-switch policy.
5. Verify drum/CASM bypass and Yamaha bank routing.
6. Preload/cache section instrument state.
7. Add automated tests for 2/4, 3/4, 4/4 and 6/8 transitions.
8. Build APK and test Main A->Fill->Main, Main D->Fill->Main A, Intro->Main, Fill->Ending, chord changes during Fill, and sustain during transitions.

## Important non-goals

- Do not copy GigLad proprietary code.
- Do not replace the current BASSMIDI engine solely because GigLad uses a different audio architecture.
- Do not change already-working UI/sustain code while implementing the arranger engine work.
- Do not use global NOTE_OFF as a transition shortcut.

## Sources

- GigLad official documentation: https://deltarray.com/documentation/giglad/
- GigLad release history: https://deltarray.com/releases_history
