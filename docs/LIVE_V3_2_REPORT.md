# LIVE Control V3.2 - hardware key look

Baseline `68a584b` (feature/live-control-v3-current-baseline): contains LIVE V3.1 (`463f6f3`), the Registration voice recall fix (`541eec0`) and the
Master Transpose fix (`cac8b8e`). Branch `feature/live-control-v3-2-hardware-ui`. `feature/live-control-v3-led-ui` is an older docs-only branch, unrelated, not used.

Presentation only. New `HardwareLedButton` / `HardwareKeyFace` / `HardwarePanel` (ui/HardwareLedButton.kt) and look tokens `HardwareKeyStyle`.
- Key: 5 dp rounded rectangle, graphite gradient cap, light top/left and dark bottom/right bevel, inner highlight, soft drop shadow, LED bar on top.
- Press: `collectIsPressedAsState` + 45 ms `animateDpAsState`: the cap drops 1.5 dp, shadow shrinks 4 -> 1 dp, bevel flips. The hit box is the outer static `clickable`;
  the callback is the plain click callback, never behind an animation, with no second gesture path (no double trigger).
- Applied to INTRO / MAIN / FILL / ENDING, START/STOP, ACMP, AUTO FILL, RELEASE, REG 1-8, bank previous/next, UNDO SAVE, SAVE OPTIONS, FREEZE, EXT VOICE and HOLD SAVE (same press-and-hold gesture, hardware look).
- LEDs: section keys amber (active) / blue (available) / dark gray (confirmed absent, disabled) / neutral (unknown); transport LEDs follow ACMP / AUTO FILL / RELEASE / playing state;
  REG: amber selected, cyan filled, off + "EMPTY" empty, red save target; FREEZE LED lights when any freeze group is on; bank keys light only when they can move.
- Panels: brushed graphite with a thin metal frame; groups stay separate cards.
- Unchanged: the V3.1 layout and sizes, exactly four faders (RIGHT1, RIGHT2, RIGHT3, STYLE), eight REG slots, every ViewModel callback (asserted by LiveV3Test), and all of
  MainViewModel, Registration, Master Transpose, MIDI OUT, engine, Style (asserted by tools/test_live_v32_guard.py).
- Not added: SYNC START/STOP, BREAK, FADE, MASTER fader, MUTE, branding.
