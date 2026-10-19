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

## Visual correction (after the first Redmi Pad test: VISUAL NO-GO)
The first V3.2 key was one gradient rectangle with a full-width LED strip, which looked like V3.1. Rebuilt as three visible layers:
- **Bezel**: near-black frame with a metal rim and a hard offset drop shadow (3 dp at rest, 1 dp pressed).
- **Cap**: inset 3 dp inside the bezel, stronger graphite gradient, light top/left and dark bottom/right bevel drawn as lines, rim highlight; pressed = cap sinks 2 dp, inner shadow, darker face.
- **LED lamp**: a short horizontal lamp (26 dp, MAIN 32 dp) in its own dark housing above the label, with a small bounded glow when lit; off is gray, unknown a dull neutral; absent keys are disabled. Bank previous/next keys are small and carry no lamp.
- **Proportions**: keys are fixed-height physical-button shapes (60 dp, MAIN 68 dp, transport 66 dp) instead of cards that fill the area.
- **Shared panels**: the style / transport / mixer / REG drawing moved to `LiveControlPanels.kt`, used by the Android screen and by the preview renderer, so the preview shows the real component code.

Validation render: `tools/ui_preview` compiles the same Compose source files with Compose Desktop (Skia) and writes PNGs (workflow `live-v3-2-preview.yml`, published on branch `preview/live-v3-2`).
`01_specimen_before_after.png` puts the V3.2 key of commit 8b4526c above the corrected key in every state; `02`/`03` render the style, transport, mixer and REG panels at 1000x600 dp and 1280x800 dp.
Limits: the desktop renderer uses DejaVu Sans instead of Android's Roboto; the header is not rendered (it depends on the Android ViewModel); it is not a device screenshot.
