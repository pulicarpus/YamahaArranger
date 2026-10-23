# MIDI OUT voice cache — experiment (NOT a production merge)

Branch `experiment/midi-out-voice-cache`, baseline `29ed56f56815ac844c74c8c52e061da824f3b925` (P1 ordered note I/O, CI #39776548789).
Audit reference: `diag/hardrock2-transition-gap` `e3d762e` (docs/HARDROCK2_MAIN_FILL_TRANSITION_AUDIT.md, CI #39826496809).
No merge to main, no PR. **A better host result is not proof that the gap on the PSR-E343 speaker is gone: the decision waits for the user's own test with HardRock2 and ROSITA.**

## What changed (production)

| file | change |
|---|---|
| `midi/MidiMixerOutput.kt` | Keeps `sentVoice[ch]` = the Bank/Program that was **actually written** (all three messages accepted); `programs[ch]` stays the wanted value. For style channels (4..15) `program()`/`replayChannel()` skip Bank+Program when `sentVoice` already equals the wanted triple, and then re-assert controllers only if they changed (non-forced). When a voice really changes: Bank MSB → Bank LSB → Program Change, then all known controllers are forced (the module may reset them after a Program Change). A rejected write stops the sequence and records nothing, so the next selection sends everything again. `sentVoice` is cleared with the controller cache on enable/disable and connect/disconnect, and for a channel that leaves the managed set. New `forgetSentVoice(ch)` and `forgetAllSent()`. Keyboard parts (0..3): every explicit selection is still written, unchanged. |
| `midi/MidiInputManager.kt` | Two pass-through methods: `forgetSentVoice(channel)`, `forgetAllSentMidiState()`. |
| `arranger/StyleSequencer.kt` | Two lines: the explicit "re-send this Style voice" actions (`resendMidiVoice`, `auditionBegin`) call `forgetSentVoice(channel)` first so they still always write. Nothing else in the sequencer, P1 note I/O, CASM, scheduler or clock changed (`tools/test_midi_cache_guard.py` enforces it). |

Port change / reconnect / reset: a new connection goes through `setConnected(true)` → full replay. `forgetAllSentMidiState()` exists for a device reset or a manual keyboard change.

## Proof (host)

`MidiMixerOutputTest`: 36 tests OK (27 existing unchanged + 9 new): identical style voice not rewritten; changed program/bank MSB/LSB sends the full triple then controllers; mixer changes stay immediate around a cached voice; output OFF→ON and reconnect replay the voice in full; disabled output stores the wanted voice and sends it on enable; a rejected write is never recorded as sent (and a failed Bank Select stops the sequence); explicit reapply and module reset resend; keyboard parts keep sending every explicit selection and a re-added style channel is sent again; production manager round trip.

Real Styles through the production chain (`SectionTransitionGapTest`, MIDI OUT with modelled per-write cost, `SGAP_EXPECT=cache`):
* HardRock2: Main↔Fill bursts toward a section contain **0** messages for every channel that already holds its voice; the only message group is the first-time voice of RGuit (ch14) when FillDD introduces it (12 messages).
* ROSITA (real voice differences between sections): `ch11 PCs = [27, 48, 3]`, `ch13 PCs = [48, 52, 54]` — every real change is still sent, in order, none repeated back to back; no Program Change ever repeats the last value.

Regression (P1 tree + patch, local): `SectionTransitionGapTest` + `P1ChainTest` + `OrderedNoteIoUnitTest` 28/28 on HardRock2 and 20/20 on ROSITA (0 stuck notes, 0 orphan NOTE_OFF, STOP during a blocked section switch 0/0, no clock restart); default realtime set 52/52 (stress, metronome, transpose/ACMP/LEFT, P1 chain, ordered I/O); chord-change stutter 15/15 (`CCS_EXPECT=p1`). CI runs these plus CPV, LIVE/REG, registration recall, native suites and the Android build.

## Before / after (same harness, same modelled write cost, n = 2 transitions per scenario, 4–5 for the LEFT+RIGHT run — indicative only)

HardRock2 (before = `29ed56f`, after = patch):

| scenario | MIDI OUT msgs per transition | first-event lateness p50/p95/max (ms) | harmonic cut→re-strike, engine-added (n/p50/p95/max ms) | voice-apply mean |
|---|---|---|---|---|
| MainA↔FillAA, write cost 0 µs | 40 → **0** | 2.77/8.68/9.22 → 2.68/12.47/12.74 | 3/4.78/4.78/7.77 → 3/4.52/4.52/11.68 | 423 → 449 µs |
| write cost 150 µs | 40 → **0** | 10.45/12.41/13.84 → **2.25/3.67/4.12** | 3/13.32/13.32/14.96 → **3/2.58/2.58/3.93** | 8223 → 362 µs |
| write cost 300 µs | 40 → **0** | 13.83/17.09/17.57 → **2.59/6.11/6.64** | 3/15.13/15.13/15.90 → **3/2.93/2.93/3.92** | 12558 → 381 µs |
| MainD↔FillDD, 300 µs, LEFT+RIGHT live | 48–52 → **12** (first-time ch14 only) | 20.24/22.22/23.04 → **1.78/7.95/9.30** | 5/19.31/20.05/23.83 → **5/3.64/3.78/8.03** | 16902 → 1520 µs |
| native 300 µs + MIDI OUT 300/150 µs model | 20 → **0** | 9.71/14.84/15.43 → 2.45/4.86/5.46 | 3/7.11/7.11/16.12 → 3/3.64/3.64/4.01 | 3480 → 323 µs |
| native 700 µs + MIDI OUT 700/300 µs model | 20 → **0** | 12.92/15.04/16.19 → 7.20/9.69/12.54 | 3/13.22/13.22/13.59 → 3/7.04/7.04/9.02 | 6374 → 326 µs |

ROSITA control (only channels whose voice really differs are written):

| scenario | msgs per transition | first-event lateness p50/p95/max (ms) | cut→re-strike engine-added p50 (ms) |
|---|---|---|---|
| MainA↔FillAA, 0 µs | 48/56 → 8/8 | 1.14/5.0/5.36 → 0.71/3.58/3.68 | 2.80 → 1.85 |
| 150 µs | 48/56 → 8/8 | 4.12/10.01/10.23 → 0.88/4.16/4.41 | 9.17 → 3.44 |
| 300 µs | 48/56 → 8/8 | 13.62/18.99/19.43 → 1.90/6.63/7.03 | 17.57 → 4.99 |
| MainD↔FillDD, 300 µs, LEFT+RIGHT | 36/34 → 28/18 | 1.96/17.40/18.45 → 1.92/15.95/17.02 | (n=4; p50 is a metric artefact in both) |

HardRock2 at 0 µs write cost shows no gain (nothing to save when writes are free); its p95/max rose within noise of n = 2. The residual 2–3 ms cut→re-strike is the engine's own boundary path (also present with MIDI OUT off, ≈ 2.7 ms before the patch).
Costs of 150/300/700 µs per write are **models**; the real `MidiInputPort.send` cost on the Redmi Pad is **not measured**.

## Residual risks

* The keyboard's real reaction (the audible gap on the PSR-E343 speaker) is not simulated; if the gap persists the cause lies elsewhere (e.g. the keyboard's own handling, the audio path, the Style's authored rests, the early-window Fill start of C1).
* If the keyboard's voice/bank is changed by hand between section switches, the cache believes the Style voice is still active; toggling MIDI OUT off/on, reconnecting, or an explicit "re-send voice" restores it (`forgetAllSentMidiState()` is available for a future reset detector, none is wired).
* A Program Change that fails halfway is retried in full next time; Bank MSB/LSB are re-sent with every real voice change (not only the Program) on purpose.
* `tools/test_mixer_integration.py` / `test_voice_release.py` (legacy E343-checkpoint guards, not part of this CI chain) compare `MidiMixerOutput.kt` with an older checkpoint and would flag this intentional change; `test_voice_release.py --jvm` already fails on the P1 baseline for an unrelated frozen-Brain guard.

## Redmi Pad tests (user)

1. HardRock2 Main→Fill→Main ×30, MIDI OUT on, with this APK vs the P1 APK; note whether the short silence is gone or shorter.
2. Same with ROSITA (control).
3. MIDI OUT off/on mid-play, replug the keyboard, and change a Style voice override: the voice must be restored/applied each time.
4. Change the keyboard voice by hand, then press Fill: report whether the Style voice is expected back (known limitation above).
5. Quick check of normal play: chord changes, STOP/START, volume/pan/reverb/chorus faders.
