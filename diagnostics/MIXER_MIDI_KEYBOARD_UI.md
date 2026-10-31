# Mixer redesign + live MIDI keyboard mirror — experiment (NOT a merge)

Branch `experiment/mixer-redesign`, baseline `dfb22c4` (Style Editor BACK fix; APK `YamahaArranger-STYLEVOICE-dfb22c4`, CI green). No merge to main, no PR. Engine unchanged: BASSMIDI + Oboe.
The Audio Evolution Mobile Studio screenshots were used as a concept only (tall console strips side by side, vertical meter beside a big fader, M/S, pan, transport-free layout). No asset or code of that app is used.

## 1. Audit (before changes)
* **Old Mixer** = `SxMixerPage` in `SxMainScreen.kt` (page `LivePage.MIXER`, `navigation.mixer()`; Android Back already returns via `navigation.back()`; header button returned to ARRANGER/LIVE). Strips: `SxConsoleStrip` (DETAIL, MUTE, SOLO, value, vertical fader, label) + buses `SxConsoleBus` (STYLE, MASTER). Pan/EXP/REV/CHO and voice picking live in the DETAIL dialog.
* **Parts.** Keyboard parts RIGHT1/2/3 and LEFT are channels 0..3 (`state.rightVoices`, `KeyboardVoiceSlot.enabled`); Style parts are channels 8..15 (Rhythm1/2 = 8/9, Bass, Chord1/2, Pad, Phrase1/2 = 10..15) listed in `voices` (channels the loaded Style uses).
* **Volume / pan / mute / solo.** Keyboard volume = CC11 gain (`sendKeyboardGain`), pan/reverb/chorus per part via `setKeyboardMixerDetail`; Style volume/pan through `setStyleChannelMixer` → `AudioEngineManager.setChannelMixer` → native `nativeSetChannelMixer` (BASSMIDI) and `onMixerControllers` → `MidiMixerOutput.update` (CC7/10/11/91/93, per channel). Mute/Solo are gain-only (`MixerConsoleState`): `syncMixerMidiMute` → `MidiMixerOutput.setMutedChannels` (releases the router's held notes, never filters NOTE_OFF) and `setStyleChannelMute` for the internal path. Solo is cleared when the page closes (existing behaviour, kept); fader values persist.
* **USB MIDI IN.** `MidiInputManager.parseMessages` → `onNoteOn/onNoteOff` (only the chord-input channel) → `ArrangerBrain.onKeyboardNoteOn` (split → RIGHT / LEFT / ACMP). NOTE_ON velocity 0 → `onNoteOff`. There was no observer for UI; the on-screen `VirtualKeyboardPanel` (61 keys, touch) had only its own touch state.
* **Signal data that exists.** BASSMIDI mixes all parts into ONE stream: **no per-part audio level exists** and none was invented. The only real audio level is the output block: the Internal Output Gain stage already walks the buffer in the audio callback. MIDI OUT has no feedback from the PSR-E343 at all: only "a NOTE_ON was written" is knowable.
* **Device removal.** Nothing told the app when the keyboard was unplugged (no `MidiManager.DeviceCallback`).

## 2. Architecture / changes
**Mixer (UI only):** `MixerStripUi.kt` (new strips), `SxMainScreen.kt` (page layout; old `SxConsoleStrip/Bus/VerticalFader` removed), `MixerPure.kt` (pan snap/label, host-tested).
* Fullscreen landscape; one horizontally scrolling row: RIGHT/LEFT strips | Style strips | STYLE trim | MASTER. Each strip: part name + active LED, voice name (tap = DETAIL dialog with voice picker, pan, EXP, REV, CHO — unchanged), vertical meter on the left of a large fader, numeric volume, M and S buttons (48 dp), PAN slider with centre snap and "PAN L/C/R" text. Fader range 0..127 and semantics are unchanged.
* Header: **← BACK** (returns to Live/Arranger exactly as before; Android Back also works), **ACTIVE ONLY / ALL CHANNELS** (default active: enabled keyboard parts + the Style's used channels; ALL also shows keyboard parts that are OFF), **SHOW / HIDE KEYBOARD**, STYLE VOICES.
* **Indicators (two honest modes):**
  * MIDI OUT off → part meters show **NOTE ACTIVITY**: the last NOTE_ON velocity handed to the internal sound engine for that part, decaying (τ ≈ 450 ms). Caption: "not an audio level".
  * MIDI OUT on → part meters show **MIDI OUT ACTIVITY**: NOTE_ONs actually written to the keyboard (a muted part writes nothing, so it stays dark). Caption: "not an audio measurement".
  * **MASTER** meter = real output level (peak of the last audio block after the gain stage). STYLE trim has no meter.
  * Data: `ChannelActivity` (fixed atomic arrays) written by one passive line in `MidiInputManager.sendNoteOn` and one in `MidiMixerOutput.noteOn` (after a successful write); the UI polls at ~30 Hz with one shared clock read only in draw scopes (no recomposition, no allocation on the note path).
* **Live key mirror:** `LiveKeyState` (new, fixed `AtomicIntegerArray`, version counter) is written by the parser at the same points that call `onNoteOn/onNoteOff` — original note numbers, before transpose/split/CASM, velocity 0 = off — and cleared on `close()` and on any MIDI device removal (new `DeviceCallback`, clears visuals only). `VirtualKeyboardPanel` reads it: lit keys in amber (intensity = velocity), touch stays blue, union drawn, so releasing one source never clears the other. FOLLOW MIDI IN scrolls to the played area; notes outside the 61-key window are counted (◀ n below / n above ▶). The panel's CLOSE became **HIDE KEYBOARD** (also in the Mixer header); MIDI IN keeps being tracked while hidden.
* **Native (one store):** `internal_gain.h` stores the block peak (`lastBlockPeak_`, one relaxed atomic store per callback), exposed as `nativeMasterLevel()`. Gain, limiter and every sample are untouched.

## 3. Not changed (guard `tools/test_mixer_ui_guard.py`)
BASSMIDI player, audio callback processing, Internal Output Gain behaviour, Brain/routing, Style Sequencer/CASM/timing, MIDI OUT octave correction, drum mapping, voice resolver, registration/transpose, profile store, Style Editor. The router gained exactly one passive line; the input manager only added observer calls beside the existing ones.

## 4. Tests (host)
* `tests/cpv/MixerKeyboardUiTest.kt` (13): NOTE ON 60 lights C4 / OFF releases; velocity 0 = off; C-E-G three keys and one-by-one release; 500 rapid on/offs leave nothing lit and repeated identical presses are not new events; disconnect/`clear`; window mask + range on original numbers; **touch and physical on the same note do not disturb each other** (real `VirtualKeyboardController`); activity decay, source separation, ignores velocity 0/invalid channels; Mute/Solo semantics; page wiring (BACK, ACTIVE/ALL, SHOW/HIDE KEYBOARD, old strips gone, part meters never read fader values, only MASTER has an audio level, honest captions); pan snap/labels; the panel only reads the live key state.
* `tests/rtdiag/MixerKeysRtTest.kt` (8, production chain with real USB bytes): chord lights three keys at once and releases; mirror shows the original note under transpose while the engine plays the transposed one; rapid presses + disconnect clear everything; no extra MIDI OUT message from the mirror; other MIDI channels do not light it; MIDI OUT activity counts only messages really written and respects mute; mute releases only that part's held notes (no stuck note, no replay on unmute); pan of one part sends CC10 on that channel only.
* Native `tests/internal_gain_test.cpp` extended (block peak). Existing suites unchanged: CPV 302 OK locally, router unit contracts (36), MIDI OUT octave, RIGHT USB, internal gain plumbing; full rtdiag runs in CI.

## 5. Limitations
* No per-part audio level (single BASSMIDI stream); part indicators are note/MIDI activity by design.
* MIDI OUT activity shows what was written to the module, not what the PSR-E343 sounds.
* The mirror follows the keyboard's configured input channel (the one that plays RIGHT/LEFT/ACMP); other channels are not shown. Unplugging while a key is down clears on the device-removed callback.
* Layout is compiled in CI but not rendered on a device yet.

## 6. Test on the Redmi Pad + PSR-E343
1. Open the Mixer: **← BACK** top-left returns to Live/Arranger; Android Back does too. Strips scroll sideways; ACTIVE ONLY ↔ ALL CHANNELS shows the OFF keyboard parts.
2. Start a Style: part meters move with their notes (label says NOTE ACTIVITY with MIDI OUT off, MIDI OUT ACTIVITY with it on). MASTER moves with the real audio.
3. M mutes only that part (no stuck note), S solos it; pan sliders move one part only; faders keep their old range.
4. Press SHOW KEYBOARD, then press **three keys together** on the PSR-E343: all three on-screen keys light at once (amber) and go dark on release; chords, fast repeats and releasing all keys leave nothing lit. Play outside the visible range: the ◀/▶ counter appears and FOLLOW scrolls to it. Touch a key that is also held on the keyboard: releasing either leaves the other lit.
5. HIDE KEYBOARD: playing still works normally; show it again and keys that are still held appear. Unplug the keyboard with a key down: the highlight clears.
6. Confirm Style playback, MIDI OUT and sound are unchanged.
