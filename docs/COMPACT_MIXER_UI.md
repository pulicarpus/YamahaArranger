# Compact mixer UI experiment

Base: `2704d0bb8e2aee214af9e36ebccf2f7c2a3958af`.
Branch: `experiment/compact-mixer-ui`.

This branch deliberately excludes the E343 automatic Bank/PC experiment (`9915a49`). Use that experiment's APK separately for E343 sound comparison.

All strips are 48–56 dp wide, increasing continuously with available landscape width. RIGHT1/2/3, LEFT, STYLE and MASTER remain fixed; only used style channels participate in horizontal scrolling. Keyboard/STYLE bus strips are blue, style channel strips green, MASTER amber. Compact labels retain distinct part identity. Full-width MUTE/SOLO and DETAIL targets remain 48 dp tall; the vertical fader keeps its existing gesture, accessibility and 0–127 mapping.

VOICE selection now lives in DETAIL, along with PAN/EXP/REV/CHO. It opens the existing picker for the same part/channel. No mixer bindings, MIDI routing, controller scaling, audio engine, RELEASE or RIGHT OFF lifecycle changes are made. No simulated level meters are displayed.

Validation: host JVM geometry/label contracts cover landscape widths 640/800/960/1024/1280 dp and heights 360/480/600/800 dp. Source isolation guard verifies the production diff is restricted to the mixer UI and layout helper, fixed/scroll separation, action availability, unchanged fader implementation and byte-identical main arranger controls. Existing JVM, native recorder, frozen checkpoint and real BASSMIDI PCM gates remain mandatory before APK production.

Limit: these host contracts do not render Compose or prove touch ergonomics on Redmi Pad. Actual layout, font scaling, fader gestures and DETAIL → VOICE interaction require Android validation; no device screenshot or instrumentation result is claimed.
