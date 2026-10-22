# Main / Fill / Main transition gap — root cause audit (AUDIT ONLY)

No production code, native code or other test was changed. Evidence is **HOST JVM only** (production
chain `ArrangerBrain → StyleSequencer → ordered note I/O → NativeAudioBridge`, real `ROSITA.STY`,
native layer replaced by a recording rig with injectable blocking). Nothing here was measured on the
Redmi Pad / PSR-E343; every device-level statement is marked HYPOTHESIS or NOT MEASURED.

## 1. Baseline

| item | value |
|---|---|
| P1 branch | `experiment/p1-ordered-note-io` |
| P1 production commit | `29b067c13755de2ebaaaee0f9b8c7529a632a74f` |
| P1 docs commit (audit base) | `29ed56f56815ac844c74c8c52e061da824f3b925` |
| P1 CI | https://github.com/pulicarpus/YamahaArranger/actions/runs/39776548789 (green, host + android) |
| Audit branch | `diag/main-fill-transition-gap` (only harness, runner hook, guard, workflow, this doc) |
| Pre-P1 comparison | `16c673a` (same harness, same load) |

`tools/test_transition_gap_guard.py` enforces: only the diagnostic files differ from `29ed56f`; `app/`,
`tests/cpv`, `tests/fixtures` untouched; `RtRig.kt` only gained the `stallThread` thread-name match.

## 2. Device symptom (user report, not measured)

P1 APK: chord changes feel normal; Main→Fill and Fill→Main have a short audible gap in nearly every
transition. No device numbers exist.

## 3. State machine (as read from the code)

```
selectMainVariation / selectSection (ArrangerBrain)
 ├─ Fill active, new request  → ACTIVE FILL RETARGET (replace pending target)
 ├─ Main→Main, Auto Fill ON   → queue Fill, then Main at next bar
 ├─ Main→Main, Auto Fill OFF  → queueNextBarMain
 ├─ currently in Main         → queueScheduledTransition(nextBarBoundary)      [quantized]
 └─ currently in Fill/other   → coroutine awaitMusicalTick → queueSeamlessTransition
                                  (starts at currentMasterTick, "phase continuous")  [wake-time]
StyleSequencer.nextBarTargetTick() returns null if next bar is <= 25 ms away
   → request falls into the immediate path (starts at the current tick, BEFORE the bar line)
startPlayback transition:
   playOnce breaks → activeNoteSnapshot + releaseActive (outgoing notes off)
   → applyVoicesProbed(section) (scheduler thread, before first new event)
   → continue playOnce(newSection)
```

## 4. Timeline of one transition (per channel)

```
authored: ... last note | tail rest (authored) | BAR LINE | head rest (authored) | first note ...
engine:   request → [boundary tick] → releaseActive → applyVoices → first event due → dispatch
                                      └ scheduler thread, serial ┘
```

The audible silence is `tail rest + head rest` (authored) + `release/apply latency` (engine) + audio
path (not measured). Only the engine part is changeable by us.

## 5. Authored vs scheduled vs dispatched

ROSITA.STY: PPQ 1920, 4/4, bar 7680 ticks, Main = 2 bars, Fill = 1 bar (only FillAA/BB/CC/DD exist),
EndingB 15361 ticks. Rests at 120 BPM (scale by 120/tempo):

- Chord/phrase parts (ch11/12/14) start 250–500 ms into a section; FillBB/FillDD drums (ch9) start after 250 ms;
  FillBB has no ch8; FillDD has only ch9/10/11/13.
- Tail rests: MainA ch8 1000 ms, other Main parts 250–500 ms, pad ch13 up to 2000–4000 ms.

→ A large part of an audible pause at Fill/Main edges is **in the Style MIDI itself**. Harness columns
`authRest` vs `firstLate` per channel separate this from engine lateness (`firstLate` = dispatched − authored-due).
Dispatched events matched scheduled events 1:1 per channel (`n=x/x`, no missing or duplicated events).

## 6. Scenario results (HOST, real ROSITA, 280 BPM; ms; first-event lateness vs authored due time)

| scenario | transitions | first-event p50 / p95 / max | notes |
|---|---|---|---|
| MainA→FillAA→MainA | 2 | 0.45 / 3.31 / 3.38 | |
| MainA→MainB auto-fill ON | 2 | 0.02 / 1.19 / 1.23 | |
| MainD→MainB via Fill (auto-fill ON) | 4 | 0.14 / 3.61 / 3.65 | |
| MainA→MainB auto-fill OFF | 1 | 1.47 / 1.52 / 1.55 | |
| Unfinished Fill → retarget | 5 | 0.33 / 4.41 / 4.76 | |
| Fill replaced by Fill | 4 | 0.39 / 1.20 / 1.26 | |
| Request at bar start / beat 2 / mid / late | 2 each | max 2.59 / 2.27 / 2.96 / 2.30 | late bar: all-event max 57 ms outlier (1 sample, scheduler wake) |
| Request < 25 ms / < 5 ms before bar | 2 each | max 1.24 / 1.44 | start is early, see §8 |
| ACMP OFF (drums only) | 2 | 0.94 / 0.94 / 4.15 | |
| LEFT+RIGHT1/2/3 playing | 2 | 0.07 / 1.66 / 1.72 | turn wait max 34 µs |
| MIDI OUT off (model 300/150 µs) | 2 | 1.68 / 4.01 / 4.50 | |
| MIDI OUT on (300/150 µs) | 2 | 1.84 / 9.14 / 9.70 | |
| MIDI OUT on (700/300 µs) | 2 | 4.44 / 14.6 / 15.7 | |
| Pending Transpose | 2 | 0.38 / 1.99 / 2.01 | |
| 100 ms native block after each switch | 2 | 2.07 / 103 / 103 | scheduler thread itself blocked |
| Rapid random transitions, 90 s | 100 | 0.77 / 3.97 / 30.3 | 13/100 off-grid, see §8 |

Sample counts are the "transitions" column (each = all destination channels). Small n for most rows;
percentiles above n≈5 are indicative only. Meters other than 4/4: **NOT MEASURED on real data** (no
2/4, 3/4, 6/8 Yamaha fixture in the repo). A SYNTHETIC arithmetic check shows the request-quantized
first boundary is bar-aligned in 2/4, 3/4, 4/4, 6/8; it is not Style validation.

Real-device lateness, audio-callback gaps, and USB/MIDI-OUT hardware latency: **NOT MEASURED**.

## 7. P1 vs pre-P1 (identical load)

First-event lateness and boundary placement are the same (e.g. MIDI OUT 700/300: 15.5 vs 15.95 ms p95;
100 ms block: 103–107 ms both; off-grid offsets −12.8…−15.5 ms vs −13.6…−15.9 ms). P1 neither causes nor
cures the transition gap. P1 *does* fix lifecycle faults that pre-P1 shows under this harness:
STOP during a blocked section switch: pre-P1 8 NOTE_ON after STOP + 5 stuck; P1 0 + 0.
(ERRATUM, found in the HardRock2 audit: this report first also claimed `LEFT+RIGHT` orphan NOTE_OFFs on pre-P1 vs P1. That was a harness race — the live-note thread was not joined before STOP — not an engine difference; with the join fix pre-P1 and P1 both pass. See docs/HARDROCK2_MAIN_FILL_TRANSITION_AUDIT.md.) Ordered-I/O turn wait max ≤ 107 µs.
No clock restart in any scenario (`playRestarts`=1 = initial play only; `globalAllNotesOff`=1 = final STOP).

## 8. Findings

### Confirmed (HOST, deterministic)

**C1 — Fill requested within ~25 ms before the bar line starts early and leaves the bar grid.**
`StyleSequencer.nextBarTargetTick` returns null when the wait is ≤ 25 ms, so the request takes the
immediate path and starts the Fill at the current tick, ahead of the bar line. The grid stays displaced
until the next quantized transition, which then cuts the shifted pattern mid-bar. Offsets measured at
request phase 7500 ticks: −20.1 ms; 7640: −4.1 ms; repeated test: −13.6…−15.9 ms. In the rapid run
13/100 transitions were off-grid (worst −18.75 ms). Start is *early* (not a silence), so alone it would
sound like a tight/cut transition, not a long pause. Estimated 1–3 % of presses → does **not** by itself
explain "nearly every transition".
Repro (fails on baseline expectation of alignment, passes only with `SGAP_EXPECT=current`):
`SectionTransitionGapTest.requestWithin25msBeforeTheBarLineLeavesTheBarGrid` (P1 and pre-P1 identical).

**C2 — Wake-time starts (`awaitMusicalTick` + `queueSeamlessTransition`, e.g. Fill replaced / from Fill).**
Start tick = wake tick, not the exact bar tick: +0.2…+2.5 ms on host. On a device with 5–25 ms wake
lateness the offset would be larger (HYPOTHESIS, scales with wake jitter).

**C3 — MIDI OUT program/bank re-send on every section switch.** `applyVoicesFromCasm` sends uncached
MIDI OUT `sendProgramChange` (+bank) for ~8 channels on the scheduler thread before the first event of
the new section. Voice apply mean 5.5 ms (OUT on, 300/150 µs) vs 1.1 ms (off); first-event p95 ≈ 9–10 ms
and ≈ 15.5 ms at 700/300 µs. Native `setChannelProgram/Mixer` are cached; MIDI OUT is not.

**C4 — Any blocking native call on the scheduler thread delays the whole transition 1:1** (100 ms block →
103 ms lateness, P1 cannot help because the scheduler itself is blocked).

**C5 — Not clock restarts, not stuck/orphan notes, not P1 ordering.** Supported by all rows above.

### Hypotheses (NOT MEASURED)

- H1: PSR-E343 audibly reacts to the per-section program/bank changes (mute/sound dropout while the keyboard
  re-loads patches) — would give a gap on each Main/Fill switch with MIDI OUT on. Most likely candidate for
  "every transition", but unproven.
- H2: audio-level gap (voice re-trigger, Oboe/synth mutex contention at the boundary).
- H3: larger scheduler wake/dispatch lateness on the device than on host.
- H4: user expects Fill to start immediately; the code intentionally starts at the next bar (Yamaha PSR-E343
  rule not verified in the repo).
- H5: authored rests in ROSITA (§5) accounting for most of the perceived pause on some Fill→Main edges.

## 9. Note lifecycle

Outgoing notes: snapshot + `releaseActive` at the boundary; prefix balance (never more OFF than ON per key)
passes in every scenario; `stuck=0`, `orphanOff=0` on P1 in all scenarios; incoming notes dispatched 1:1.
Pre-P1 shows orphan/stuck cases (see §7), none on P1.

## 10. Root cause verdict

**Not proven.** The engine-side contributors measurable on host are small (first events ≈ 0.2–4 ms late,
≈ 10–15 ms with MIDI OUT on) — below what is normally heard as a "gap". The authored Style rests (§5) account
for part of the pause. The remaining candidate (H1, MIDI OUT program changes → keyboard) and any
audio-level gap are device-side and need measurements.

## 11. Minimum patch proposals (NOT implemented; await approval)

1. **C3**: cache last-sent program/bank per channel for MIDI OUT in `applyVoicesFromCasm` (skip when unchanged)
   and/or move the sends off the scheduler thread. Acceptance: MIDI OUT usb writes at a switch with unchanged
   voices = 0; first-event p95 with OUT on ≤ OUT off + 2 ms; all regression suites green.
2. **C1**: make `nextBarTargetTick` return the next-next bar (or the exact bar tick) instead of null for the
   ≤ 25 ms window. Acceptance: `requestWithin25msBeforeTheBarLineLeavesTheBarGrid` with `SGAP_EXPECT=fixed`
   (|offset| < 0.5 ms) and no regression in the rapid run.
3. **C2**: have `queueSeamlessTransition` start at the exact bar tick, not the wake tick. Acceptance: boundary tick == bar multiple.

Risks: C1 changes when a late Fill press takes effect (up to one bar later); C3 must still re-send after a
MIDI OUT reconnect/reset; both need device validation. Do not expect these to remove an authored-rest gap.

## 12. Deterministic reproduction

`SGAP_EXPECT=current python3 tools/test_rtdiag.py --out out --rosita --only com.yourapp.rtdiag.SectionTransitionGapTest`
(optional `SGAP_ONLY=<test>`). Native blocking is injected through `RtRig` (`stallThread` name match).
P1 tree: 25 tests OK (SectionTransitionGapTest + P1ChainTest + OrderedNoteIoUnitTest), default rtdiag set
52 tests OK. Pre-P1 tree: 2 of 17 fail in the expected, lifecycle-related ways (§7).

## 13. Redmi Pad tests still needed

1. Record the PSR-E343 output (line-in / phone recorder) while pressing Main↔Fill ~30 times with MIDI OUT on vs off,
   same Style, same tempo: measure the silence length and compare with the authored rests of §5.
2. Log, per transition, timestamps of the MASTER CLOCK line, `applyVoices` start/end and first note dispatch
   (existing debug log) and the MIDI OUT write durations.
3. Repeat with ACMP off, with Auto Fill off, and with a Fill pressed in the last 25 ms of a bar.
4. Audio-only (no MIDI OUT): is the gap still audible? This separates H1 from H2/H5.

## 14. GO / NO-GO

- **NO-GO** for a "gap fix" claim: the root cause of the audible gap is not proven.
- **GO** (low risk, optional) for a separate small patch phase on C3 and C1/C2 as timing hygiene, each with its
  acceptance test above — but expect them to improve consistency, not necessarily remove the gap.
- First action should be the device diagnostic in §13 (test 4 decides between MIDI OUT cause and audio cause).
