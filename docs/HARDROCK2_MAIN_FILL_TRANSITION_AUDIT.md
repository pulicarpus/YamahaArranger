# HardRock2.S578.prs — Main / Fill / Main transition audit (AUDIT ONLY)

No production code, native code or other suite was changed. All numbers are **HOST JVM** evidence: the production chain
(`ArrangerBrain → StyleSequencer → ordered note I/O → MidiInputManager/MidiMixerOutput → fake MIDI port`) with the native layer
mocked and a **modelled** per-write MIDI OUT cost. The PSR-E343, the Android MIDI stack and the audio path are **not simulated**; nothing here proves
what the keyboard does.

## 1. Baseline and inputs

| item | value |
|---|---|
| P1 baseline | `experiment/p1-ordered-note-io` production `29b067c13755de2ebaaaee0f9b8c7529a632a74f`, docs `29ed56f56815ac844c74c8c52e061da824f3b925`, CI run 39776548789 |
| Audit branch | `diag/hardrock2-transition-gap` (from `diag/main-fill-transition-gap`, CI #39809787680 SUCCESS) |
| Fixture | `tests/fixtures/hardrock2/HardRock2.S578.prs`, 32 425 bytes, sha256 `8207c97317738dc2cb8c3cbdea83b17cd11198ee23335bb5fb4c29685d3e1281` (supplied by the user; not replaced by ROSITA or a synthetic Style) |
| Control | `tests/fixtures/r23/ROSITA.STY` (same harness, same code) |
| Pre-P1 comparison | `16c673a`, same harness files, same load |

## 2. Is `.prs` a Yamaha Style? (validated through the project parser)

Yes. The unmodified production C++ parser (`style_parser.cpp`, `smf_reader.cpp`) accepts the file (exit 0):
SMF header `MThd` (type 0, PPQ 1920), `MTrk`, marker `SFF2`, 4 × `CSEG`, 27 × `Ctb2` (SFF2 CASM), 1 × `OTSc`, time signature 4/4, tempo 0x07E479 (≈116 BPM).
15 sections: IntroA/B/C, MainA–D, FillAA/BA/BB/CC/DD, EndingA/B/C; 106 CASM policies, 4 275 events. The app also lists `.prs` explicitly (`ContentResolverProvider.kt`).
Bar = 7 680 ticks; Main A = 2 bars, B/C/D = 4 bars, Fills = 1 bar (FillBA = 7 681 ticks).

## 3. HardRock2 vs ROSITA — what the Style itself contains

| | HardRock2 | ROSITA |
|---|---|---|
| destination channels per Main | 5–6 (8 perc, 9 drums/crash, 10 bass, 11 DistGt, 13 Organ/OrgRoot, 14 RGuit in C/D) | 6–7 |
| note-carrying channels in Main A | 5 (76 NOTE_ON) | 7 (113) |
| Bank/Program/mixer per destination, Main vs Fill | **identical in every Main↔Fill pair** (all 20 pairs: `identicalVoiceAndBankAndMixer = common`) | differs in voice name/mixer for most channels |
| sustained harmonic layers | OrgRoot = one note held ≈ the whole section (tick 0 → len−40), DistGt, Bass | shorter phrases |
| Fill tail/head rests | Fills start at tick 0 on all channels (head rest 0 ms; RGuit 8.9 ms), OrgRoot ends 40 ticks (≈10 ms) before the bar line | 250–500 ms head rests on several channels (see previous report) |

Consequence: in HardRock2 nearly **every** layer (bass, DistGt, organ pad, drums, perc) sounds right up to and right after the bar line, so almost no authored
silence masks an engine delay. ROSITA's authored rests hide it. This is a Style property, not an engine fault, but it makes any engine delay audible.

## 4. Voice setup / MIDI OUT path (code facts)

`StyleSequencer.applyVoicesFromCasm` (runs on the scheduler thread on every section change, before the first event of the new section):
for each destination channel it calls `midiInputManager.sendProgramChange(dst, prog, msb, lsb)` unconditionally (no comparison with what was last sent).
`MidiMixerOutput.program()` stores the program and, when output is active, calls `replayChannel(ch, includeProgram = true)`, which writes
**Bank MSB, Bank LSB, Program Change** and then `apply(ch, force = true)` which writes **CC7, CC10, CC11, CC91, CC93** — i.e. up to **8 messages per channel**, `force` bypasses the `sent[]` cache.

Measured with the production wiring (`audioEngine.onMixerControllers → setMixerControllers`, all channels 4..15 active):

| transition | setup msgs per switch | redundant vs last value sent | before first style NOTE_ON | after first style NOTE_ON |
|---|---|---|---|---|
| HardRock2 MainA↔FillAA (5 dst) | 40 (BankMSB 5, BankLSB 5, PC 5, CC7/10/11/91/93 5 each) | **40/40** | 40 | 0 |
| HardRock2 MainD↔FillDD (6 dst) | 48 | **48/48** | 48 | 0 |
| ROSITA MainA↔FillAA | 48–56 | 47/48 – 55/56 | all | 0 |

* The whole burst is written by the same scheduler thread, strictly **before** the first NOTE_ON of the new section (`styleNoteOnsInsideBurst = 0`, `msgsAfterFirstStyleNoteOn = 0` in all HardRock2 Main/Fill bursts).
* Order on a harmonic channel (ch11, MIDI OUT): `NOTE_OFF (outgoing) @ −0.4 ms → BankMSB → PC → … burst … → NOTE_ON (incoming)`.
  Outgoing notes are released before the burst; incoming notes wait for it.
* Drum/perc channels (8, 9) get their notes without NOTE_OFF (one-shots), so a "Bank/PC while a note is open" count on those channels is not meaningful.

## 5. Engine delay with controlled MIDI write cost (MODEL, not a measurement)

First-event lateness vs authored due time, all destination channels, ms (p50/p95/max; n = 2 transitions per row, 100 in the rapid run, so p95/p99 are indicative only):

| scenario (HardRock2) | first-event lateness | burst span | harmonic cut→re-strike, engine-added (n / p50 / p95 / max) |
|---|---|---|---|
| MIDI OUT off, native 300 µs/150 µs model | 1.65 / 4.01 / 4.46 | – | 3 / 2.69 / 2.69 / 2.96 |
| MIDI OUT on, write cost 0 µs | 2.77 / 8.68 / 9.22 | 0.1–0.4 | 3 / 4.78 / 4.78 / 7.77 |
| MIDI OUT on, 150 µs per write | 10.45 / 12.41 / 13.84 | 6.2–9.5 | 3 / 13.32 / 13.32 / 14.96 |
| MIDI OUT on, 300 µs per write | 13.83 / 17.09 / 17.57 | 12.1 | 3 / 15.13 / 15.13 / 15.90 |
| MainD→FillDD→MainD, 300 µs, LEFT+RIGHT live | 20.24 / 22.22 / 23.04 | 15–19 | 5 / 19.31 / 20.05 / 23.83 |
| rapid random transitions 90 s (MIDI OUT off) | 2.13 / 4.53 / 12.77 | – | 140 / 2.87 / 4.89 / 7.53 |
| request at bar start / beat 2 / mid / late / <25 ms / <5 ms | max 2.47 / 1.90 / 5.14 / 2.54 / 2.34 / 2.47 | – | – |
| Auto Fill on/off, ACMP off, Pending Transpose | max 2.39 / 1.92 / 1.49 / 9.18 | – | – |
| 100 ms native block after each switch | 100.2 / 102.4 / 102.5 | – | 3 / 102.1 |

"Harmonic cut→re-strike, engine-added" = time between the engine's NOTE_OFF at the boundary and the incoming NOTE_ON on the same harmonic destination, minus the Style's own head rest (0 ms here).
ROSITA control, same harness: cut/re-strike on **1** harmonic channel per transition (p50 1.9 ms MIDI OUT off; 9.2 ms at 150 µs; 17.6 ms at 300 µs, n = 1 each); HardRock2: on **3** channels (bass, DistGt, organ) per transition (p50 2.7 / 13.3 / 15.1 ms).
Pre-P1 (`16c673a`) gives the same values within noise (e.g. 300 µs: burst 12.6–14.6 ms, first-event p50 15.2 vs 13.8 ms; cut→re-strike p50 15.6 vs 15.1 ms). **P1 neither causes nor fixes this.**
The real per-write cost on the Redmi Pad (Android `MidiInputPort.send` → USB) is **NOT MEASURED**; 150–300 µs is an assumption.

Authored vs scheduled vs dispatched, per channel: for every transition the rows `SGAP transition …` give authored head rest, first-event lateness and event counts per destination channel (all channels dispatched; no clock restart, `playRestarts = 1`; 0 stuck notes, 0 orphan NOTE_OFFs on P1 in every scenario).
One count difference on HardRock2: destination 13 has 4 authored NOTE_ONs but 3 are dispatched at every transition (authored = OrgRoot root note + 3 organ chord tones; actual notes 52, 60, 64) — CASM/NTT filtering, same on pre-P1; it does not shorten any silence (the other three notes start together). The "≈429 ms" values in earlier index-paired lateness rows for FillDD come from this 7-vs-5 mismatch (pairing artefact), not from a real delay.

## 6. Confirmed vs hypothesis

**Confirmed (host, deterministic):**
1. Every Main↔Fill (and Fill→Main) switch of HardRock2 sends 40–52 MIDI OUT messages (Bank MSB/LSB, Program Change, CC7/10/11/91/93 per channel) that are **100 % redundant** with the values already sent — voice, bank and mixer of every channel are identical in all Main and Fill sections.
2. They are sent on the scheduler thread after the outgoing harmonic NOTE_OFFs and before the first incoming NOTE_ON, so every sustaining layer (bass, DistGt, organ) is released, stays silent for the burst duration, then re-struck.
3. The delay scales linearly with the MIDI OUT write cost and with the number of destination channels (burst 12 ms @300 µs for 5 channels, 15–19 ms for 6 channels).
4. HardRock2 cuts and re-strikes **three** harmonic layers per transition, ROSITA one; HardRock2 has no authored rest that hides it. Both fit "Style with many instruments is worse".
5. P1 baseline behaves like pre-P1 for this path; early-window (<25 ms) Fill requests still start ≈14–16 ms early (previous report, C1).

**Hypotheses (NOT proven):**
- H1: the PSR-E343 audibly reacts to repeated Bank/Program Change/CC7/CC11 on a channel that is sounding (voice re-initialisation, envelope/volume reset), independent of the host-side delay.
- H2: real `MidiInputPort.send` cost on the tablet is higher than 300 µs per message (burst ≥ 15–50 ms) or varies (garbage collection, USB scheduling).
- H3: audio-level gap (Oboe/synth) — not modelled.
- H4: repeated CC7/CC11 resets visibly shorten pad/guitar decays on the keyboard.

## 7. Is there a specific proven cause?

**No.** A specific, reproducible *engine-side mechanism* is proven on host (§6 items 1–4: redundant setup burst between release and re-strike on three layers), and it is the only identified difference that scales with "number of instruments". Whether its size on the device (unknown write cost) or the keyboard's reaction to the repeated Program Change is what is heard is **NOT proven**: no device measurement exists, and the host cannot show the PSR-E343's response. Authored silence is excluded as the cause for HardRock2 (all layers play from tick 0).

## 8. Deterministic repro

`python3 tools/test_rtdiag.py --out out --rosita --style tests/fixtures/hardrock2/HardRock2.S578.prs --style-name HardRock2.S578.prs --only com.yourapp.rtdiag.SectionTransitionGapTest`
(tests `midiOutSetupBurstMainFillMain`, `voiceSetupInventoryPerSection`, plus all earlier scenarios; `SGAP_ONLY=<test>` filters; `SGAP_EXPECT=current` asserts the known early-window offsets).
Results: P1 tree — HardRock2 27 tests OK, ROSITA 27 tests OK. Pre-P1 (`SectionTransitionGapTest`, 19 tests): only `stopDuringABlockedSectionSwitch` fails (8 NOTE_ON after STOP, 5 stuck), as before; P1 0/0.
Harness change worth noting: the LEFT+RIGHT scenario now joins the live-note thread before STOP. Without it the scenario shows 3 orphan NOTE_OFFs on **both** pre-P1 and P1 (a harness race); the previous report's pre-P1/P1 LEFT+RIGHT claim is corrected (erratum in `MAIN_FILL_TRANSITION_GAP_AUDIT.md`).

## 9. Minimum patch proposal (NOT implemented — needs approval)

Smallest change: in `MidiMixerOutput.program()` replay the channel only when `(program, msb, lsb)` differs from what was last sent on that channel, and make `replayChannel` apply non-forced when only the program was unchanged (keep `force` for reconnect/enable, which already call `invalidateSent()`/`replay()`). File/function: `app/src/main/java/com/yourapp/midi/MidiMixerOutput.kt` → `program()` / `replayChannel()`. No change to `StyleSequencer`, P1 or native code.
Acceptance tests (all on this harness): HardRock2 Main↔Fill switch with MIDI OUT on sends **0** Bank/PC/CC messages when nothing changed and still sends them when the voice really changes (ROSITA, Intro→Main) and after reconnect/enable; first-event lateness with MIDI OUT on within +1 ms of MIDI OUT off; harmonic cut→re-strike engine-added p95 ≤ 3 ms at 300 µs per write; `MidiMixerOutput` unit tests; all existing suites (CPV, registration, LIVE, metronome, realtime guard) unchanged.
Risks: the keyboard may have been changed manually between switches (a skipped re-send would then not restore the Style voice) → keep the explicit "Apply Style voices" and reconnect paths sending; a wrong cache after MIDI OUT toggle (covered by `invalidateSent`).

## 10. Redmi Pad / PSR-E343 tests still needed (before any claim)

1. HardRock2 Main→Fill→Main ×30, MIDI OUT **on** vs **off** (internal sound only): is the gap still there without MIDI OUT? (separates H1/H2/H4 from audio-path H3.)
2. Same with ROSITA as control, same tempo.
3. Record the PSR-E343 line-out and measure the silence per instrument (sound editor), and log the Android-side time of `MidiInputPort.send` for the 40–52 messages (DebugLog already timestamps `CASM VOICE APPLY START/END`).
4. Mute channels one at a time (DistGt, Organ, Bass) to see which layer's drop-out is audible.
5. Only after 1–4: run the patch from §9 as an experimental build and repeat.

## 11. GO / NO-GO

- **NO-GO** for claiming the audible gap is fixed or its cause is proven.
- **GO**, as a separate low-risk experimental phase, for the §9 patch (removes 100 % redundant traffic and 12–19 ms of modelled delay on the HardRock2 path) **together with** the device tests of §10 — approval required; nothing is implemented.
