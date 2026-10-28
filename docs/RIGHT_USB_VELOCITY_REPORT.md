# RIGHT USB-MIDI (PSR-E343) — investigation + conditional Touch Response (experiment, NOT a merge)

Branch `experiment/right-usb-velocity`, baseline `3550a92` (Style Editor V2). No merge to main, no PR.
**The RIGHT sound problem is NOT declared solved. Only a test with the real PSR-E343 on the Redmi Pad can decide that.**

## Root cause: still unproven (leading hypothesis)

The code audit and the host measurements find **no velocity/controller processing bug** in the USB path:

* USB bytes -> parser -> `onNoteOn(d1, d2)` -> `d2/127f` -> `(v*127f).toInt()` is lossless for every value 1..127; no curve, no clamp except 1..127, no second scaling, no bit mask.
* RIGHT1/2/3 receive the same velocity (engine channels 0..2). The native side computes `lround(v*127)` clamped 1..127 (identical result).
* CC1, CC7, CC11, CC71, CC74, CC91/93, pitch bend, aftertouch and program change **from the keyboard are not forwarded to BASSMIDI** (only CC64 on the chord channel is used for sustain). So they cannot make USB differ from the Virtual Keyboard. (Whether the E343 sends any of them is measured on the device by the new probe, not assumed.)
* RELEASE is CC72 on the engine channel and is independent of the note velocity; the same for both paths.
* **Real path difference:** the Virtual Keyboard sends a fixed velocity **100** (default) through its own ledger; the PSR-E343 sends its **own touch-dependent velocity**. If a normal touch on the E343 produces low velocities (e.g. 30-60), SF2 presets with velocity-dependent attack/filter/volume (strings, pads) will sound weak and slow; a hard press yields higher velocity and a clearer sound. That matches the report ("all RIGHT voices, hard press is clearer") but is a hypothesis until the device capture shows the raw velocities. A second possible factor is the E343's own TOUCH RESPONSE setting (Function menu) — please check it.

## Comparison table (path A = Virtual Keyboard, path B = PSR-E343 USB-MIDI)

| item | A Virtual Keyboard | B USB-MIDI | difference |
|---|---|---|---|
| velocity source | fixed setting, default 100 | physical touch (value decided by the keyboard) | **yes** |
| route | `VirtualRightVoices` -> engine directly | parser -> Brain -> `startRightNote` -> engine | different code, same engine call |
| velocity at native call (Original) | = input | = input (lossless 1..127) | none |
| MIDI channel / RIGHT1-3 | engine ch 0..2 | engine ch 0..2 | none |
| CC1/7/11/64/71/74 forwarded to BASSMIDI | no | no (only CC64 sustain on chord channel) | none |
| preset/bank/program/SF2 | same engine channels | same | none |
| RELEASE (CC72) | same | same | none |
| NOTE_ON vel 0 | n/a | treated as NOTE_OFF | — |

## Measured velocities (host, production chain, `RightUsbVelocityTest`)

| raw in | parsed | routed | engine arg | native call | MIDI OUT |
|---|---|---|---|---|---|
| 20 / 40 / 60 / 80 / 100 / 127 (Original) | same | same | same | same (20/40/60/80/100/127) | same |

VK path: the same numbers through the VK ledger give identical native velocities (default key velocity 100). All 127 values 1..127 verified lossless. These tables prove what the **app** hands to BASSMIDI; they say nothing about what the E343 sends or how the speaker sounds.

## What was added

1. **Diagnostics (no audio-thread cost; atomic counters only)** — `RightInputProbe`, shown in RIGHT DIAG (button *LOG PROBE* also writes the report to the debug log): USB raw NOTE_ON velocity histogram (min/median/max/mean), velocity routed to RIGHT, velocity forwarded to the engine, the same for the Virtual Keyboard, counts of NOTE_ON/OFF/vel-0, pitch bend, channel pressure, poly aftertouch, program change, messages per channel, **every CC actually received with its last value**, and how long keys are held (<100 ms … >=1 s).
2. **TOUCH RESPONSE** (only because the app path is proven correct, so a low raw velocity is the leading explanation) — in the RIGHT DIAG panel: **Original (default)** / Soft / Medium / Hard.
   * Applies only to USB-MIDI -> RIGHT1/2/3, to the velocity handed to the internal synth. MIDI OUT keeps the raw velocity. Virtual Keyboard, LEFT, ACMP/chord, Style, Rhythm, Bass are untouched.
   * Curves (gamma on v/127): Soft 0.60, Medium 0.80, Hard 1.25 → e.g. 20→42/29/13, 40→63/50/30, 60→81/70/50, 80→96/88/71, 100→110/105/94, 127→127. Monotonic, 127→127, never 0 (NOTE_ON 0 stays NOTE_OFF), min 1. Not "everything to 127".
   * Precomputed 128-entry table: one array read, no added latency or thread hop. Saved in `touch_response.txt` (app files dir); unreadable file = Original.

## Tests

`tests/rtdiag/RightUsbVelocityTest.kt` (9): lossless 1..127 + probe stages, comparison table, controllers/bend/pressure/PC create no note events and are listed by the probe, velocity-0 NOTE_ON = NOTE_OFF, curve properties, Touch only on USB RIGHT (MIDI OUT raw, VK unaffected, ACMP chord key not mapped), RELEASE does not change velocity, persistence/default, no extra thread hop. Existing `RightVoiceDiagTest` source guard updated for the new engine-velocity expression. Local: CPV 269 OK, default rtdiag 52 OK, guard `tools/test_right_usb_guard.py` PASS. CI results: see the run linked in the final message.

## Test with the PSR-E343 (Redmi Pad)

1. Install the APK, connect the E343, open **RIGHT DIAG**, press **RESET STATS** (Touch = Original).
2. Play ~20 normal-touch notes on RIGHT1 with a string voice that sounds weak, then ~10 hard presses. Read the `USB RAW noteOn vel` line (min/median/max/mean): if a normal touch is around 30-60, the hypothesis is confirmed. Press **LOG PROBE** and send me the debug log.
3. Also read the `USB controllers:` line (which CCs the keyboard really sends) and the key-hold line.
4. Switch Touch Response to Medium, then Soft, replay the same normal touch and report whether the sound is closer to the Virtual Keyboard. Check the E343's own TOUCH setting too (Function → Touch Response) and try Hard/Medium/Soft there for comparison.
5. Confirm the Virtual Keyboard, LEFT, Style and Main/Fill behave as before.
