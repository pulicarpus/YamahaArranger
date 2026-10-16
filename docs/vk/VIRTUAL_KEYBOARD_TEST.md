# Virtual Keyboard (Redmi Pad, landscape)

Branch `feature/virtual-keyboard` (from `diag/right-voice-ab` 0eabf88: Colombo primary + Tyros fallback + RIGHT DIAG).
Permanent feature and a diagnostic aid. It does NOT claim to fix the muffled RIGHT sound.

## Use
- Button **🎹 KEYBOARD** (bottom-right of the main screen) opens a 250 dp panel at the bottom. The rest of the screen,
  Voice Selector, Mixer, Live Control and the RIGHT DIAG panel stay usable (RIGHT DIAG moves up above the keyboard).
- 61 keys C2-C7 (C4 = MIDI 60). Drag the blue overview strip above the keys to scroll horizontally. C keys show their octave.
- OCT -/+ shifts the played register by octaves (range -3..+2, always inside MIDI 0-127). No style/ACMP transpose.
- VELOCITY 1-127 (default 100): fixed for each new NOTE_ON. It is independent of the USB keyboard and of the RIGHT VELOCITY TEST switch.
- SUSTAIN ON/OFF: keeps keys sounding after you lift your finger until sustain goes OFF (virtual notes only).
- CLOSE, app pause and window-focus loss release every virtual note and turn virtual sustain off.
- The panel header shows live RIGHT 1/2/3 readback: ON/off, SF2 (COLOMBO/TYROS), BASS preset name, MATCH/MISMATCH/UNVERIFIED, last velocity sent.

## Signal path
touch -> `VirtualKeyboardController` (per-pointer ownership) -> `ArrangerBrain.virtualKeyboardSink` -> `VirtualRightVoices`
-> existing engine RIGHT channels 0/1/2 (only those switched ON, decided at NOTE_ON) -> existing BASSMIDI stream.
No new synth/stream, no MIDI OUT (nothing is sent to the PSR-E343), no chord detection, no style/CASM involvement.
A USB release never cuts a key the on-screen keyboard still holds, and vice versa (same RIGHT channel + pitch).

## Test without the PSR-E343
1. Open RIGHT DIAG, pick Acoustic Grand Piano (Colombo) for RIGHT1 in the Voice Selector; confirm COLOMBO / MATCH.
2. Open KEYBOARD, velocity 100, play C4 (the C labelled C4). Listen.
3. Select Tyros 4 Grand for RIGHT1, confirm TYROS / MATCH, play C4 again at velocity 100. Compare.
4. Reconnect the PSR-E343, play the same note at similar loudness; compare with the on-screen keyboard.
Reading: if the on-screen keyboard (velocity 100, identical path) is also dull on both SF2s, velocity is not the cause.

## Known limits
- Panel SUSTAIN is Kotlin-side (no CC64); the global panel SUSTAIN (native) still applies to RIGHT channels too.
- Opening a dialog that takes window focus releases the virtual notes (safety rule).
- Layout was written for ~1000x600 dp; the KEYBOARD button is an overlay, report any overlap with main controls.
