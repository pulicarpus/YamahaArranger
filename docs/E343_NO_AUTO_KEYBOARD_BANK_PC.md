# E343 experiment: remove automatic keyboard Bank/PC only

Base: `2704d0bb8e2aee214af9e36ebccf2f7c2a3958af`, verified locally and on origin's `experiment/mixer-midi-out-fixed-layout`. New branch: `experiment/e343-no-auto-keyboard-program`.

## Independently verified audit evidence

The `94016b7` → `2704d0b` diff added a loop in MainViewModel.toggleMidiOut that seeded cached Bank/PC from all four keyboard slots. Their initial values are bank 0/program 0. MidiMixerOutput then replayed cached Bank MSB/LSB and PC on output enable and reconnect. This could override a voice selected on the E343 itself. CC11 remains necessary for the mixer and is intentionally retained.

The new recorder tests were also compiled against the original `2704d0b` router in a separate build directory, without resetting or changing production files: 4 expected automatic-keyboard-Bank/PC regression failures, with 23 remaining recorder tests passing. The patched suite passes 73 JVM tests, including all 27 MIDI recorder cases. Native API-recorder and companion safety regressions also pass. Hardware audio causality and latency are not established by this result.

## Exact production change

Only MainViewModel.kt and MidiMixerOutput.kt change:

1. Remove the keyboard slot Bank/PC seeding loop from toggleMidiOut.
2. Suppress cached keyboard Bank/PC in automatic replay for internal channels 0–3 (MIDI channels 1–4).
3. An explicit program() call while output is active/connected still sends Bank Select and Program Change, then restores that part's mixer controllers. Choosing a voice while output is disabled does not queue a keyboard program for automatic transmission on enable.
4. Automatic style Bank/PC replay on internal channels 4–15 remains unchanged.

CC11, other mixer controllers, MASTER, MUTE/SOLO and reconnect controller replay remain unchanged. Existing CC72 delivery is unchanged. NOTE_ON/OFF bodies, external note ownership, shared monitor/locks, queue behavior, UI layout, style/CASM/scheduler, RELEASE, RIGHT OFF and BASSMIDI remain identical to the problematic base. No lock/queue change is combined with this experiment.

`tools/test_e343_program_isolation.py` is an additive mandatory Actions gate pinned to the full base SHA. It compares both production files against the exact permitted transformation and rejects any other production-file change. Existing guards and PCM gates are retained.

## Recorder matrix

| Action | MIDI channels 1–4 Bank CC0 / CC32 / PC | CC11 and mixer | Style Bank/PC |
|---|---|---|---|
| MIDI OUT ON | Not sent automatically | Current snapshot retained/replayed | Existing behavior retained |
| Reconnect while ON | Not sent automatically, including previously selected voices | Current snapshot retained/replayed | Existing behavior retained |
| Explicit application voice selection while active/connected | Sent only to selected part | Restored for selected part | Existing behavior retained |
| Voice selected while OFF | No immediate transmission and no automatic keyboard replay on ON | Cache/replay retained | Existing behavior retained |
| Key-down/key-up with style STOP | NOTE_ON/OFF tuple unchanged | Existing behavior retained | Not altered by this patch |

## Validation limits

Required local command: `python3 tools/test_voice_release.py --jvm --regressions`.
Isolation command: `python3 tools/test_e343_program_isolation.py`.
Actions also runs real Linux BASSMIDI PCM, frozen style/RELEASE/legacy-file checks, Android SDK identity checks, both ABI builds and APK verification before APK publication.

The original F12 guard has a pre-existing StyleSequencer SHA mismatch; its source and the scheduler are unchanged and this baseline failure is reported separately. No guard assertion is weakened.

The E343 and Redmi Pad still require device A/B testing. Test output ON and reconnect while keeping the E343's front-panel voice unchanged; test explicit application voice selection separately. First test with style STOP, then verify existing mixer/style behavior. A successful device test would show whether suppressing Bank/PC alone is sufficient; it would not establish or eliminate the separate shared-monitor latency hypothesis.
