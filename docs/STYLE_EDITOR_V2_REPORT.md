# Style Editor V2 — octave shift, drum/kendang note map, E343 catalog loader, RIGHT audio audit

Branch `experiment/style-editor-v2` (from `fix/style-editor-reload-crash` 8dc434b = MIDI OUT voice cache + reload-crash fix). Not merged, no PR.
Host evidence only; no sound on a SoundFont or on the PSR-E343 has been heard by this report.

## 0. Reload crash (P0)
Fixed on the base branch (docs/STYLE_EDITOR_RELOAD_CRASH_REPORT.md): the Style load took the engine operation lock and the profile restore asked for a locking preset list on the same main thread. Profiles are never deleted automatically; regression tests are in `tests/cpv/StyleReloadFlowTest.kt`. This branch keeps the fix and extends the same restore with the new entries.

## 1. Editor flow (STYLE → SECTION → PART → VOICE → AUDITION → APPLY → SAVE)
The existing editor already offers section tabs, part rows (Style request, actual voice, status, replacement), REPLACE (INTERNAL SF2 by name from all loaded SoundFonts, or EXTERNAL MIDI OUT), THIS SECTION / ALL SECTIONS, AUDITION, APPLY, RESET PART / SECTION / ALL, SAVE PROFILE as a separate JSON next to the Style (keyed by content hash). V2 adds, without changing that flow: OCTAVE SHIFT on melodic parts and DRUM NOTE MAP on Rhythm 1/2 in the detail panel, a PSR-E343 named-voice list in the EXTERNAL tab, RESET ALL that also covers the new entries, "unsaved" detection for them.

**PSR-E343 voice catalog.** The Yamaha PSR-E343 Data List could not be obtained here (the sandbox cannot reach Yamaha's servers), and a catalog must not be invented. The app therefore ships no built-in list. If `/storage/emulated/0/YamahaArranger/E343_VOICES.tsv` exists (one voice per line: name, MSB, LSB, program 1-128; tab, `;` or `,`; `#` comments) the EXTERNAL tab lists those names with search. Manual MSB/LSB/PC and the Registration EXT list stay. **Needed from the user: the Data List PDF (or the voice table) to make the catalog built-in.**

## 2. Octave shift per melodic part
Bass (ch10), Chord1/2 (11/12), Pad (13), Phrase1/2 (14/15): −2…+2, per part, THIS SECTION or ALL SECTIONS (section beats part-wide; a section-level 0 is kept and overrides a part-wide shift). Applied to the output pitch after CASM/NTT (`+12*octave`), so the chord root, RIGHT, LEFT and Master Transpose are untouched; it feeds the internal engine and MIDI OUT. The shift is captured in the note ledger at NOTE_ON, so NOTE_OFF, chord retarget (RTR) and STOP use the stored pitch and always pair. Persisted in the profile (schema 3).
Tests (production chain, ROSITA + HardRock2): shifted parts move by exactly the chosen octaves while every other channel's pitch set is unchanged; same note count; MIDI OUT equals the engine; section scope, section-0 override; chord change + STOP leave 0 stuck and 0 orphan NOTE_OFF; invalid edits refused.

## 3. Drum / kendang note mapper
Per Rhythm 1/2, per output (INTERNAL SF2 or MIDI OUT, separate maps), THIS SECTION or ALL SECTIONS. The dialog lists the notes the Style really plays on that part with their counts, SOURCE → TARGET (0-127) with −/+, slider and number entry, ▶ audition of source (Style's own kit) or target (chosen kit), PREVIEW ORIGINAL / MAPPED, SHIFT ALL TARGETS (±1 / ±12) as a starting point (each note stays individually editable), several sources may share one target (the dialog says so; nothing is dropped), APPLY (next notes), RESET MAP, SAVE PROFILE. Only the note number changes; velocity, timing, duration and order are untouched; drums keep their one-shot ledger so there is no stuck/orphan NOTE_OFF; CASM harmonic transposition is not applied to drums.
**Sample warning.** The target hint is read from the SoundFont's own zone tables (`Sf2KeyCoverage`: preset × instrument key ranges, sample zones only): "sample present", "NO SAMPLE on this note (silent)", or "not provable" when the file/preset cannot be read; MIDI OUT cannot be checked. Key ranges only — velocity layers and loudness are not evaluated.
Tests: production chain (internal vs MIDI OUT maps differ, same events in the same order, velocities untouched, many-to-one keeps every event, section scope, removal restores the Style); `Sf2KeyCoverageTest` with synthetic SF2 whose samples sit on keys 60/62/65 only; CI step with **real BASSMIDI** on that synthetic kendang-like SF2: Style notes 38/42/45 silent, mapped 60/62/65 audible, a target without a zone silent (`tests/drum_map_real_bass_test.cpp`). The example numbers are examples, not a verified Yamaha-to-kendang mapping.

## 4. RIGHT audio
Audit only (docs/RIGHT_AUDIO_AUDIT.md): no production change, a device A/B protocol with the tools already in the APK.

## 5. Safety
`tools/test_editor_v2_guard.py`: native code, MIDI OUT router (the cache), fixtures, parser, CASM, scheduler/clock and P1 ordered I/O are byte-identical; the sequencer diff is limited to the octave/drum lines. `MasterTransposeTest.sourceGuard…` was updated for the one explicit post-CASM term (`+12*octave`) and now also asserts that the term never involves the master transpose. Two profile tests that hard-coded schema 2 now use `StyleOverrideCodec.SCHEMA` (3). Older schema-1/2 profiles still read and upgrade; a newer schema stays read-only.

## 6. What still needs the Redmi Pad / PSR-E343
Compose screens were reviewed and compiled only by the Android build in CI (no emulator here); real sound of any mapping; the Data List for the built-in E343 catalog; RIGHT audio per the protocol.
