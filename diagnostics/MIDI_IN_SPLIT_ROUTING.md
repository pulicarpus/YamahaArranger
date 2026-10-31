# MIDI IN left zone + adjustable Split Point — experiment (NOT a merge)

Branch `experiment/midi-in-split`, baseline `9fb34c6` (Mixer redesign + live key mirror; CI green with APK). No merge to main, no PR. Engine unchanged: BASSMIDI + Oboe.

## 1. Audit
* **Path:** USB MIDI → `MidiInputManager.parseMessages` → keys accepted only when `channel == chordInputChannel` (MIDI channel 1) → `liveKeys` (visualizer) and `onNoteOn/onNoteOff` → `ArrangerBrain.onKeyboardNoteOn` (`midiNote > splitNote` = RIGHT, else ACMP / LEFT / RIGHT). No note-range filter exists anywhere on this path. Velocity-0 NOTE_ON = NOTE_OFF. The visualizer and the routing are fed from the same parser branch.
* **Routing contract, verified on the production chain with real USB bytes** (`MidiInSplitRtTest`, left key 48 / right key 72):

| ACMP | LEFT | key in LEFT area | key in RIGHT area |
|---|---|---|---|
| OFF | OFF | **RIGHT** | RIGHT |
| OFF | ON | LEFT | RIGHT |
| ON | OFF | chord control only (no voice) | RIGHT |
| ON | ON | chord + LEFT | RIGHT |

  Both zones are lit in the visualizer in all four modes. A 3-key chord with ACMP ON / LEFT OFF is recognised. So **the app's split routing is not the cause**: if a left-zone key lights nothing, its NOTE_ON never reaches the accepted channel (the parser silently dropped it) or the keyboard did not send it.
* **Most likely cause (unproven until the device log):** the parser only accepts MIDI channel 1. Yamaha E-series keyboards can transmit the left/split zone on a different channel (Split voice / Dual voice / accompaniment settings), which the filter dropped without any trace. Alternatively the E343 sends nothing for that zone (e.g. ACMP section consumed on the keyboard).
* **Virtual keyboard:** touch plays RIGHT through its own path (engine only); it never used split/ACMP/LEFT.

## 2. Passive diagnostics (new, no effect on playing)
* `MidiInStats`: per MIDI channel NOTE_ON/OFF counts, lowest/highest note, and whether those notes were **played** or **IGNORED by the channel filter**, with "only LEFT-area keys / only RIGHT-area keys / both" relative to the split. Shown in the Split dialog ("MIDI IN (PSR-E343)") and cleared on disconnect.
* **Keyboard visualizer:** a key received on a channel that is not played lights **violet** ("received, not played"); played keys stay amber (velocity), touch blue. Channels 9–16 (the E343's own Style output) are counted but never drawn. Nothing is drawn that was not received.
* Meaning of what you see: no key lights at all → the E343 did not send it (check the keyboard's MIDI/Local/Keyboard-out settings); violet key → received but filtered → enable that channel; amber key but silent → routing (not seen in the host tests).

## 3. Fix
* **MIDI IN channels:** channel 1 stays the default (baseline). In the Split dialog, **CH2…CH8** switch extra channels on; their notes then play exactly like channel-1 notes (same routing, same visualizer). Channels 9–16 can never be enabled. Saved with the split. Warning in the dialog: do not enable a channel that repeats the same keys (e.g. Dual voice).
* **Split Point** (36…95; a key AT the split is LEFT, every higher key RIGHT — identical to the baseline `midiNote > splitNote`; default **54 = F#3**, notes named C4 = 60):
  * **SPLIT** key on Live Control (value e.g. F#3) and a SPLIT button on the on-screen keyboard open the dialog: big name + note number, "LEFT ≤ F#3 · RIGHT ≥ G3", **− / +** per semitone (and −12 / +12), **SET BY KEY**, RESET (F#3).
  * **SET BY KEY:** armed, the next key press from the E343 *or* the screen keyboard sets the split and is not played; its release is swallowed (nothing sounds or sticks). CANCEL / any −/+ cancels it.
  * Keyboard: an amber line between the last LEFT key and the first RIGHT key; LEFT keys slightly cool, RIGHT keys slightly warm.
  * Saved in `keyboard_input.txt` (split + extra channels) and restored at start; missing/invalid values → F#3 and channel 1 only.
* **Moving the split** uses a new setter that changes only the number: the next NOTE_ON uses it; held notes keep the owner they started with (maps keyed by the pressed key), so their NOTE_OFF pairs; the chord detector, applied chord, Master Transpose and Style playback are not reset (the old, unused `setSplitPoint` resets the chord context and is not used). No all-notes-off.

## 4. Changed files
`MidiInputManager.kt` (channel acceptance + stats; default behaviour identical), `LiveKeyState.kt` (display-only "ignored" layer), new `MidiInStats.kt`, `SplitPoint.kt`; `ArrangerBrain.kt` (two added functions only; routing branches untouched); `MainViewModel.kt` (state, persistence, SET BY KEY wiring); UI: `SplitDialog.kt`, `LiveControlPanels/Screen.kt`, `VirtualKeyboardPanel.kt`, `MainActivity.kt`. Guard `tools/test_split_midi_in_guard.py` keeps BASSMIDI, audio, MIDI OUT router/octave correction, CASM/Style, mixer, registration, gain byte-identical.

## 5. Tests (host)
* `tests/rtdiag/MidiInSplitRtTest.kt` (7, production chain, real USB bytes): the four-mode routing table in both areas + visualizer; chord recognised with ACMP ON/LEFT OFF; split per semitone (key at the split is LEFT); **split moved while keys are held → no stuck note, notes finish on their original owner**; split moves do not reset chord, send no all-notes-off and Style keeps playing; ignored channel shown but never played, switchable, NOTE_OFF from another channel does not release a played key, velocity 0 releases; Style channels 9–16 never played/drawn even with a bad mask.
* `tests/cpv/KeyboardSplitMidiInTest.kt` (9): names/zones/clamp/step; SET BY KEY for USB and touch (swallowed release, independent sources, cancel, clamp); persistence across a restart and bad files → baseline; per-channel stats; ignored layer; UI/VM wiring (SPLIT key, dialog, divider, persistence, non-resetting setter). `LiveV3Test` expects one extra read-only VM value (`splitNote`) on the Live screen.
* Existing suites unchanged; CPV 311 OK locally.

## 6. Limits
* What the E343 actually sends is unproven until you read the MIDI IN report on the device; the fix depends on that answer (extra channel, or a keyboard setting).
* The visualizer shows only keys the keyboard sent; if a zone sends nothing, nothing lights (by design).
* Split range 36–95; the old Brain `setSplitPoint` is left untouched and unused.

## 7. Test on the Redmi Pad + PSR-E343
1. Connect, open Live Control → **SPLIT** → read "MIDI IN (PSR-E343)". Press keys in the right area, then in the left area. Expected lines like `ch1: … played`. If the left area shows `chN … IGNORED by the channel filter`, press **CHN** to play it; if no line appears for the left area, the keyboard is not sending it (check its Local/Keyboard-out/Split settings).
2. ACMP OFF + LEFT OFF: every key plays RIGHT and lights. ACMP OFF + LEFT ON: left keys play LEFT. ACMP ON: left keys control chords (LEFT voice only if LEFT is ON).
3. Move the split with − / +; the amber line moves one key per tap. Hold a key and move the split: the key still releases normally. Style keeps playing.
4. SET BY KEY: press it, then a key on the E343 (or on screen): the split becomes that key and nothing plays.
5. Close and reopen the app: split and channels are restored.
