# MIDI OUT per-part Octave Correction — experiment (NOT a merge)

Branch `experiment/midi-out-octave`, baseline `6875b39` (pitch-stability diagnostics; CI green with APK). Engine unchanged: BASSMIDI + Oboe. No merge to main, no PR.
Scope: Style parts Bass, Chord 1, Chord 2, Pad, Phrase 1, Phrase 2 (destination channels 10..15), MIDI OUT only. Drum mapping, SF2 voices, distortion guitar, velocity, gain, CASM, RIGHT/LEFT are untouched.

## 1. Audit (read-only, before any change)
* **Style → MIDI OUT path.** `StyleSequencer` computes the output pitch once (`CasmNoteTransformer.transform` + channel transpose + the V2 octave shift, `StyleSequencer.kt` ≈ 1495–1518), stores it in `ActiveTransposedNote.outputNote`, and sends the SAME note to the internal engine (`audioEngine.noteOnStyleChannel`) and to MIDI OUT (`midiInputManager.sendNoteOn`) inside one staged command (`stageIo`). NOTE_OFF (`chordOff`, `releaseActive`) and chord retargets (`updateHeldPitch`: OFF old, ON new) reuse `outputNote`, so ON/OFF always pair on the stored output pitch; held notes keep the octave captured at NOTE_ON. Drums (8/9) use a separate drum-map note for MIDI OUT.
* **Single MIDI OUT choke point.** Every Style note byte goes through `MidiInputManager.sendNoteOn/Off` → `MidiMixerOutput.noteOn/noteOff` (serialized, `@Synchronized`, outside the audio callback). The router already owns a per-channel note ledger (`notes[ch][note]`, used by mute/solo/disable/disconnect to release exactly what was written).
* **Stop/panic.** STOP releases the ledger notes and sends CC123 on all 16 channels (`MidiInputManager.allNotesOff`), which does not touch the router ledger.
* **Existing V2 "octave shift"** (Style Editor, internal + MIDI OUT) already exists; it moves both outputs. The new correction is additive and MIDI OUT only. They stack on MIDI OUT.
* **Style Editor & storage.** The Style Voice Editor keeps a working copy of a per-Style "override profile" (JSON, schema 3, CRC32, atomic write + .bak, keyed by the Style file's SHA-256, stored beside — never inside — the Style file). SAVE PROFILE writes it; Style load restores it (`StyleOverrideRestorer`).
* **Where to apply.** Doing it in the sequencer would touch CASM/retarget code and affect the internal engine path. Doing it at the router changes only the bytes written to the module and can pair ON/OFF with its own ledger. **Conclusion: safe to implement at the MIDI OUT router; no other system needs to change.**

## 2. Before / after
* Before: `outputNote` → engine and router (`noteOn(ch, n)` writes `0x9ch n v`; `noteOff(ch, n)` writes `0x8ch n 0`).
* After: unchanged up to the router. For channels 10..15 the router writes `n + 12·k` (k = correction of that part, −2…+2); channels 0..9 and 16+ are never corrected. NOTE_ON with a result outside 0..127 is **not sent** (no wrap-around, no clamping to a different pitch) and its NOTE_OFF is dropped the same way.
* Internal BASSMIDI receives exactly what it received before (the sequencer is byte-identical: `tools/test_midi_octave_guard.py`).

## 3. NOTE_ON / NOTE_OFF pairing
Router state (preallocated, no allocation on the note path): `paired[ch][note·5 + k + 2]` counts NOTE_ONs of that input note sent with correction k, and `lastCorrection[ch][note]` remembers the newest k. A NOTE_OFF uses the correction its NOTE_ON used (newest first, otherwise any open one; an unmatched OFF uses the current correction, as an uncorrected OFF always did). So changing the correction while a note is held never strands it: the OFF is written for the corrected note that was actually turned on, the ledger count (`notes[ch][sentNote]`) matches, and mute/disable/disconnect release the corrected identities. After a STOP panic (CC123) the pairing is cleared. A new Style starts at 0 for every part (`clearOctaveCorrections()` before the saved profile is restored). Held notes finish on the octave they started with; the next note uses the new correction.

## 4. Per-Style storage
`StyleOverrideRecord.midiOutOctaves: List<StyleMidiOutOctave(channel 10..15, octaves ∈ −2..2, ≠ 0)>` in the existing profile. The JSON key `midiOutOctaves` is written **only when non-empty**, so profiles without a correction are byte-identical to what earlier builds wrote and read, and old profiles decode with no correction (schema stays 3). It is keyed by the Style's SHA-256, so it cannot leak into another Style; 0 removes the entry; RESET ALL (existing) and RESET PART clear it; the dialog has its own RESET ALL TO 0. Invalid stored values (channel outside 10..15, |octaves| > 2, 0, duplicates) are rejected by the decoder (the existing quarantine path). Rolling back to an older APK ignores the field and drops it on that APK's next save of the same Style.
UI: Style Editor header → **MIDI OUT OCTAVE** → dialog with Bass / Chord 1 / Chord 2 / Pad / Phrase 1 / Phrase 2, buttons −2 −1 0 +1 +2, value in semitones, RESET ALL TO 0; edits are heard at once on MIDI OUT and saved with SAVE PROFILE (dirty flag and read-only protection of the existing editor apply).

## 5. Tests (host; the PSR-E343 is not simulated)
* `tests/rtdiag/MidiOutOctaveTest.kt` (8): Bass 60 with −2 → 36 and its OFF turns off 36; Chord 48 with +1 → 60; 0 is byte-identical to an untouched router (all messages compared); drums 8/9, RIGHT/LEFT 0..3 and invalid values are refused and drum note 36 stays 36; 103+24 = 127 sent, 110+24 and 20−24 dropped without wrap-around and no byte > 127; correction changed while notes are held → every sent NOTE_ON has its NOTE_OFF on the same note; mute/panic/new-Style paths; **production chain** (Style sequencer → MidiInputManager → router): per part, MIDI OUT NOTE_ONs equal the internal engine's notes shifted by the part's correction, drums unchanged; **live changes of all corrections while the Style plays**: no orphan NOTE_OFF, notes left open are covered by the STOP CC123, no stuck internal Style note.
* `tests/cpv/StyleMidiOutOctaveProfileTest.kt` (6): bounds and 0-removal, store round-trip and restart, no leakage between Styles / no correction for an unknown Style, old-format profile byte-identical and decodable, invalid values rejected, resets and the restorer.
* Existing suites unchanged and run in CI: router unit contracts (`MidiMixerOutputTest`), HardRock2/ROSITA MIDI OUT chain, P1 ordered I/O, chord-change stutter, CPV, native suites.
* Evidence that a default of 0 changes nothing: the all-messages comparison above, and the guard shows the Style Sequencer, CASM, Brain, audio engine, gain and velocity code are byte-identical to the baseline.

## 6. Remaining risks
* The correction is part-wide per Style (not per section), as requested; a Style that needs different octaves in different sections needs the existing V2 octave shift (which moves the internal sound too).
* A correction pushes a note out of range only at extreme registers; those notes are silent on MIDI OUT (internal sound unaffected).
* Notes held across a change finish at the old octave (no mid-note re-strike).
* Not verified: real PSR-E343 behaviour, the touch on a physical device, the Compose dialog layout on the Redmi Pad.

## 7. Test on the PSR-E343 (short)
1. Connect, MIDI OUT ON, load a Style whose bass is an octave or two too high. Open Style Editor → MIDI OUT OCTAVE.
2. Bass −1 (or −2): play; only the PSR-E343 bass moves down, the Android sound does not change. Try Chord 1/2, Pad, Phrase 1/2 (+1 / −1).
3. Change a value while a long pad/chord is held, then STOP: no note must stay on.
4. SAVE PROFILE, load another Style (all parts 0), return to the first (corrections come back), close and reopen the app (still there). RESET ALL TO 0 returns to the original sound.
5. Confirm drums, RIGHT, LEFT and the Style without a profile sound exactly as before.
