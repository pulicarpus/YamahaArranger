# YamahaArranger — Deep Arranger Engine Audit

Baseline: fix/yamaha-panel-sustain-clean
Commit: f85ba7641fd73c34cdb57a4fc9984a05ac5d1b93
Audit date: 2026-09-29

## Purpose

File-level audit of the current branch against GigLad, vArranger, One Man Band, Android Arranger Keyboard, MIDI Voyager Pro 5.4.11 reverse-engineering notes, and Yamaha arranger/SFF/SFF GE behavior.

This audit changes documentation only. No application source code was changed.

## Reference principles

1. One continuous musical clock owns playback.
2. Sections are musical objects with explicit successor behavior.
3. Main -> Fill -> Main stays on one clock.
4. Directional fills must survive parsing when the style provides them.
5. Notes have ownership; section changes must not use global NOTE_OFF.
6. CASM is separate from voice/preset resolution.
7. Yamaha voice identity is bank MSB + bank LSB + program + semantic identity.
8. Missing voices use semantic/category fallback before generic numeric fallback.
9. Rhythm parts remain percussion.
10. Audio callback work must not block on slow operations.
11. Tempo, meter, transpose, sustain and release are live performance state.
12. Transition, note lifecycle, meter and voice mapping require regression tests.

Yamaha documents Intro I-III, Main A-D, Fill In A-D, Break and Ending I-III, with eight style parts: Rhythm 1-2, Bass, Chord 1-2, Pad and Phrase 1-2. Yamaha also distinguishes SFF and SFF GE. urlYamaha MIDI Song to Style manualhttps://europe.yamaha.com/files/download/other_assets/4/2179884/MIDI_Song_to_Style_owners_manual_En_B0.pdf

## Status

BENAR = aligned.
SETENGAH = architecture is right but incomplete/approximate.
BUG SOURCE = concrete code can explain observed failures.
LEGACY = retained but no longer authoritative.
UNKNOWN = needs instrumentation/device evidence.

---

# 1. ArrangerBrain.kt

Path: app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt

### BENAR

- Separates activeSection from currentPlayingSection.
- Main A-D and Fill groups are explicit.
- Auto Fill is modeled as a successor sequence.
- Chord changes are settled for 15 ms.
- Keyboard sustain is separated from ACMP/chord notes.
- RIGHT 1/2/3 and LEFT are separate from style channels.
- Keyboard transpose preserves the actual output pitch for matching NOTE_OFF.
- Style channel mixer/voice overrides are separate from source style data.

This is close to GigLad's section/successor model and to live arranger behavior documented by vArranger and One Man Band.

### SETENGAH

ArrangerBrain contains directional Fill names such as FillAB and FillBD, but the native parser currently cannot reliably represent all of those sections. The model is therefore richer than the parser.

Section quantization also uses a wall-clock wait before handing control to the sequencer master clock. Those two timing domains must eventually be unified.

### BUG SOURCE

Intro or Ending selected while idle is started through the ordinary playSection path. The idle START path does not automatically convert an Intro into Intro -> Main or Ending into Ending -> Stop. This can make an Intro/Ending loop instead of behaving as a one-shot section.

Priority: HIGH.

---

# 2. StyleSequencer.kt

Path: app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt

### BENAR

This is currently the strongest subsystem.

- Continuous masterClockStartedAtNanos.
- masterTimelineTick.
- pendingTransition.
- pendingSectionQueue.
- playSeamless without cancelling the active playback coroutine.
- Targeted release of outgoing style notes instead of global allNotesOff.
- Same musical clock across Main -> Fill -> Main.
- CASM policy selection.
- Runtime bank/program/mixer handling.
- Rhythm channel protection.
- String/CASM diagnostics.
- RTR 0-5 representation.

The architecture is substantially aligned with GigLad's section ownership/successor concepts.

### SETENGAH

playOnce still uses Kotlin delay and Thread.yield against a calculated target. It is not sample-accurate.

### BUG SOURCE

queueSeamlessTransition records the current master tick, but the target section is scheduled from phase zero. This is correct when the request is guaranteed to happen exactly at the desired section boundary. It is not a complete any-beat transition model.

### BUG SOURCE

activeTransposedNotes uses sourceChannel:sourceNote as its key. Two overlapping instances of the same source note on one channel collapse into one ledger entry.

BASSMIDI is configured with BASS_MIDI_NOTEOFF1, but the Kotlin ownership layer can still lose instance identity before BASSMIDI receives NOTE_OFF.

This can cause early String cutoffs, hanging notes, or incorrect transition release.

Priority: HIGH.

---

# 3. CasmNoteTransformer.kt

Path: app/src/main/java/com/yourapp/yamahaarranger/arranger/CasmNoteTransformer.kt

### BENAR

- NTR and NTT are explicit.
- Root Trans and Root Fixed are distinct.
- Chordal nearest-voicing is used instead of a simple fixed role table.
- Melody and Bass transformations are separate.
- High Key and Note Limit are modeled.
- Guitar NTR has a fallback path.
- Unit tests exist.

### SETENGAH

This is an inferred behavioral implementation, not a byte-exact Yamaha implementation.

### BUG SOURCE / LIMITATION

RTR Note Generator is explicitly deferred. SFF GE guitar behavior is therefore not complete.

Priority: MEDIUM now, HIGH for full SFF GE compatibility.

---

# 4. StyleRepository.kt

Path: app/src/main/java/com/yourapp/yamahaarranger/style/StyleRepository.kt

### BENAR

- Retrieves all parsed sections.
- Preserves non-note MIDI events.
- Extracts PPQ and meter.
- Extracts bank MSB/LSB/program.
- Extracts mixer CC state.
- Parses multiple CASM policies.
- Carries source/destination channels and voice names into Kotlin.

### SETENGAH

Tick-0 setup is handled in the repository while later program/bank changes are handled by StyleSequencer. That is valid, but the two paths must remain consistent.

The repository also depends heavily on native marker classification, so parser errors propagate directly into the arranger model.

---

# 5. style_parser.cpp

Path: app/src/main/cpp/style_parser.cpp

### BENAR

- Reads CASM.
- Reads CSEG.
- Reads Sdec.
- Reads Ctab and Ctb2.
- Applies Cntt NTT/Bass-On overrides.
- Supports multiple CSEG blocks.
- Preserves setup events before the first section.
- Correctly fixed the old Main A-D marker bug by checking the character after the word Main.

### BUG SOURCE

Directional Fill parsing is incomplete.

The native StyleSection enum only exposes FillAA, FillBB, FillCC and FillDD. A marker such as Fill BA or Fill AB cannot be preserved as a directional section. The current classifier can therefore misclassify directional fills as generic A/B/C/D fills.

This directly conflicts with the richer FillAB/FillBA/etc model already present in ArrangerBrain.

Priority: HIGH.

### BUG SOURCE / RISK

Marker classification is based on substring heuristics. Real Yamaha styles can vary in marker spelling across generations. The parser needs a normalized section grammar rather than a chain of substring guesses.

---

# 6. bassmidi_player.cpp

Path: app/src/main/cpp/bassmidi_player.cpp

## BENAR

- BASSMIDI is the current live SoundFont engine.
- BASSMIDI stream is decode + float.
- 1000 MIDI voices are configured.
- BASS_MIDI_NOTEOFF1 is enabled.
- MIDI channels 8 and 9 are marked as percussion.
- PPQN 1920 and SRC quality are configured.
- FONTEX2 is used.
- Yamaha 14-bit bank identity is preserved as MSB/LSB.
- SF2 Yamaha variation banks are normalized into legal BASSMIDI source banks.
- Separate melody and drum SF2 roles exist.
- Drum routing is restored after font replacement.
- NOTE_ON no longer overwrites program/bank.
- Preload uses BASS_MIDI_FONTLOAD_NOWAIT.
- Voice name is passed into native resolution.
- SF2 preset enumeration exists.

These are strongly aligned with the MIDI Voyager research in docs/MIDI_VOYAGER_PRO_5.4.11_AUDIO_RESEARCH.md.

## BUG SOURCE: Voice Resolver ordering

findMelodicPreset currently tries:

1. exact bank + program;
2. reduced source-bank + program;
3. same program in any bank;
4. semantic category/name search.

The third step is dangerous.

A Yamaha Strings voice can have a program number that happens to exist as Piano/E.Piano in the SF2. The resolver chooses the same numeric program before reaching the String category.

This matches the previously observed failure class:

Strings1 -> Piano/E.Piano.

The future resolver should instead use:

1. exact Yamaha bank/program;
2. explicit mapping;
3. strong semantic name/category;
4. same-family category;
5. semantically compatible numeric fallback;
6. final melodic Piano fallback.

Do not solve this by changing CASM.

Priority: CRITICAL.

## BUG SOURCE: realtime mutex contention

render() locks the same mutex used by note, program, mixer and preload operations.

Even with NOWAIT sample preload, the audio callback can wait for the same mutex held by a realtime-adjacent operation.

This is a credible source of short pauses/dropouts during section and voice changes.

Priority: CRITICAL.

## SETENGAH: sustain

Panel sustain is implemented mainly by the Kotlin note ledger; native setKeyboardSustain intentionally does not send CC64. This is valid for the current panel-sustain design, but it is not a complete native MIDI sustain implementation.

Release Time is sent to channels 0..2 as the native release parameter.

Yamaha's PSR-E343 MIDI reference identifies CC64 as Sustain and CC72 as Release Time. urlYamaha PSR-E343 MIDI Referencehttps://usa.yamaha.com/files/download/other_assets/4/329464/psre343_en_mr_a0.pdf

---

# 7. AudioEngineManager.kt

Path: app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt

### BENAR

- BASSMIDI is the live SoundFont path.
- SoundFont operations are serialized.
- Melody/drum/single-font loading modes exist.
- Oboe stream is paused during explicit SF2 replacement.
- Sustain/release/master gain are exposed.
- Voice names can reach native resolution.
- Preset inspector data can be retrieved.

### SETENGAH

Comments and compatibility naming still refer to the old FluidSynth architecture. This is documentation debt, not evidence that FluidSynth is the active engine.

### RISK

Explicit SF2 import stops the Oboe stream. That is acceptable for user-triggered SoundFont loading but must never be part of normal section or program changes.

---

# 8. NativeAudioBridge.kt

### BENAR

JNI boundaries are cleanly separated for:

audio lifecycle, SoundFont loading, notes, presets, mixer, sustain/release, master gain and preset enumeration.

No major architectural defect found.

---

# 9. native_lib.cpp

### BENAR

- JNI names match the renamed package.
- UTF-8 sanitization protects SoundFont metadata.
- Full MIDI events are preserved.
- CASM policies are serialized into Kotlin.
- Style parser state is exposed through a compact bridge.

### SETENGAH

g_lastParsedStyle is a single global parsed-style object. Fine for one active style, but not designed for concurrent parsing of multiple styles.

---

# 10. audio_engine.cpp / audio_engine.h

### BENAR

- Oboe low-latency mode.
- Exclusive -> shared fallback.
- Two-burst target buffer.
- BASSMIDI renders into Oboe.
- Legacy sample voice path remains available.

### BUG SOURCE

The audio callback inherits the mutex contention from BassMidiPlayer::render.

Do not add more DSP or UI work to this callback until that realtime boundary is safe.

---

# 11. CMakeLists.txt

### BENAR

BASS and BASSMIDI are linked and the live AudioEngine owns BassMidiPlayer.

### LEGACY

FluidSynth libraries are still imported and linked although the current live SoundFont engine is BASSMIDI.

This should eventually be cleaned up after build verification.

---

# 12. MidiInputManager.kt

### BENAR

- Real Android MIDI input.
- Running status handling.
- Realtime-byte handling.
- Channel extraction.
- Sustain CC64 detection.
- Bank MSB/LSB + Program Change output.
- Optional MIDI OUT.

### SETENGAH

Chord input is centered on a configurable channel. Good for E343 but should remain configurable for arbitrary controllers.

---

# 13. ChordDetector / AcmpChordAnalyzer

### BENAR

Chord recognition is isolated from audio rendering. Chord changes are settled before CASM retargeting.

### SETENGAH

The 15 ms settle delay is a practical Android compromise, not hardware-equivalent behavior. It should be kept measurable because increasing it increases playing latency.

---

# 14. Tests

Existing tests cover CASM transformation and chord analysis.

### BUG SOURCE / GAP

There are no equivalent automated tests for:

- Main A -> Fill -> Main B;
- Main D -> Fill -> Main A;
- Intro -> Main;
- Fill -> Main;
- Ending -> Stop;
- repeated identical note instances;
- 2/4, 3/4, 4/4, 6/8;
- simultaneous drum + melody;
- Yamaha variation-bank voice resolution.

This is currently the largest regression-test gap.

---

# 15. UI / performance controls

Relevant files include MainScreen.kt, SxMainScreen.kt, MainViewModel.kt and Sf2StyleInspectorScreen.kt.

### BENAR

The branch now contains arranger controls, mixer controls, sustain/release controls, style/SF2 diagnostics and channel overrides.

### SETENGAH

Performance state is still distributed across UI/ViewModel glue. For the planned multifunction Yamaha-style knob, use one authoritative selected-parameter state and route encoder changes into the corresponding subsystem.

---

# Cross-reference summary

| Area | Current state |
|---|---|
| Continuous master clock | BENAR architecture, timing still coroutine-based |
| Main A-D | BENAR |
| Auto Fill | BENAR/SETENGAH |
| Directional fills | BUG SOURCE at parser boundary |
| Section ownership | BENAR architecture |
| Repeated-note ownership | BUG SOURCE |
| CASM NTR/NTT | SETENGAH |
| RTR | SETENGAH |
| Yamaha bank identity | BENAR |
| Semantic voice resolution | BUG SOURCE |
| Drum routing | BENAR/SETENGAH |
| Audio realtime boundary | BUG SOURCE |
| Sustain | BENAR for panel ledger, SETENGAH native |
| Release Time | BENAR |
| Meter support | SETENGAH |
| Automated transition tests | WEAK |

# Priority list

## P0

1. Fix voice resolver ordering.
2. Remove blocking mutex from the realtime render boundary.
3. Give active style notes unique instance identity.
4. Preserve directional Fill information in the parser/model.

## P1

1. Make idle Intro/Ending one-shot.
2. Unify wall-clock quantization with master musical tick.
3. Define exact next-bar/next-beat/immediate/any-beat semantics.
4. Add transition regression tests.
5. Add voice-resolution regression tests.

## P2

1. Native/high-priority tick scheduler.
2. Dedicated VoiceResolver class.
3. Dedicated StyleTransitionPlan model.
4. Dedicated performance-state model for the multifunction knob.
5. Remove legacy FluidSynth dependencies after build verification.

# Final conclusion

This branch is no longer a prototype. Its main architecture is already appropriate for a serious Yamaha-style Android arranger:

Yamaha style -> native parser -> CASM policy -> StyleSequencer -> BASSMIDI -> Oboe -> Android.

The remaining instability is concentrated at four boundaries:

- voice identity/resolution;
- note-instance ownership;
- transition timing;
- realtime audio synchronization.

The correct next step is targeted stabilization, not an engine rewrite.
