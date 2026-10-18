# LIVE CONTROL V3 — LED HARDWARE UI CONTRACT

Status: DESIGN CONTRACT / IMPLEMENTATION NOT YET CERTIFIED
Branch: feature/live-control-v3-led-ui
Protected starting point: main @ 18081d24346a87d30f091e089a350b5481715982
Reference: design/live-mixer-v2-reference (do not merge blindly)

## Purpose
Implement landscape Redmi Pad Live Control with hardware-inspired rectangular charcoal buttons and a small LED indicator across each button's upper edge, following the user-provided Yamaha keyboard reference photograph.

## Layout
- Compact single-row header: loaded style name, active style section, meter, RIGHT1/2/3 and LEFT voice names, tempo with small -/+, transpose with small -/+; never use oversized tempo/transpose cards.
- Center-left: INTRO 1/2/3, MAIN A/B/C/D, FILL A/B/C/D, ENDING 1/2/3, transport START/STOP, ACMP, AUTO FILL, optional controls only when backed by working callbacks.
- Center-right: four independent vertical touch faders RIGHT1, RIGHT2, RIGHT3, STYLE, 0 bottom to 127 top, with numeric value and accessible hit areas. Do not collapse RIGHT voices into one control.
- Bottom: REGISTRATION BANK selector and 8 large recall slots. Preserve existing explicit recall and freeze semantics when available.
- Prefer >=64dp targets for primary live controls; preserve readability at Redmi Pad landscape density. Avoid vertical scrolling for transport.

## LED / section state contract
The UI must distinguish:
1. PRESENT_IDLE: blue top LED, clickable.
2. PRESENT_ACTIVE: amber top LED, highlighted body.
3. PRESENT_PENDING: amber blinking only if actual pending transition state is available; otherwise do not invent.
4. ABSENT: dark gray LED/body, disabled, cannot dispatch.
5. UNKNOWN: neutral subdued appearance, must NOT be treated as confirmed absent or playable.

For Intro 1-3, Main A-D, Fill A-D, Ending 1-3, derive presence from actual parsed SFF1/SFF2 section metadata, not hardcoded example style values. A named section without verified playable data is UNKNOWN until resolved. Distinguish missing section from parsing failure. For sections lacking metadata, show UNKNOWN rather than disabling as empty. Do not use synthesized/dummy sections to imply availability.

## Visual language
Charcoal hardware keycaps, inset borders, subtle LED bars, restrained blue/amber highlights; no excessive neon bloom. Active START/STOP uses clear green/red transport affordance. User photo is inspiration, not a request to reproduce branding or existing copyrighted panel artwork.

## Safety boundaries
- UI-only changes and read-only metadata adapters; do not modify StyleSequencer, CASM routing, MIDI output, SF2 resolution, BASSMIDI/Oboe, ACMP or note lifecycle.
- Do not resurrect deprecated V2 callbacks without checking signatures against current MainViewModel and screens.
- A fader must use the existing volume action and display real observed state; never invent a second volume owner.
- No fake buttons: unsupported SYNC/TAP/BREAK/FADE controls remain hidden or visibly disabled.
- No merge to main and no claim of working APK until compile/tests/device validation.

## Acceptance checks
- INTRO 1/2/3, MAIN A/B/C/D, FILL A/B/C/D, ENDING 1/2/3 all visibly represented.
- Fixture style with only Intro 1/2, Main A/B/C, Ending 1/2: remaining section keys disabled ONLY if source metadata positively confirms absence; actual present sections remain enabled.
- Distinct state tests for absent, present, active, pending, and unknown.
- Independent R1/R2/R3/STYLE fader state and gestures, bottom-to-top 0..127.
- Tempo/transpose compact controls and loaded style/voice information visible together.
- REG 1..8 visible, correct recall, no accidental writes.
- Snapshot/geometry tests for target landscape widths and font scale.
- Diff guard: no modifications to audio, scheduler, MIDI routing or production baseline files outside approved UI/read-only metadata scope.
