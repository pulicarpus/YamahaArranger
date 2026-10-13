# MIXING CONSOLE: MIDI OUT routing and fixed tablet layout

Base verified locally and on origin: `94016b7979daff2d5cba2f1bdadff78bbcce7000`.
Experiment: `experiment/mixer-midi-out-fixed-layout`. Target UI device reported by user: Redmi Pad.
No merge to main, READY, the style checkpoint or the SF2 Master branch.

## Verified root cause

Keyboard faders used `AudioEngineManager.setChannelExpression`; keyboard detail used `setChannelMixer`.
Style trims, source CC automation, mute and section setup computed effective mixer values in the existing `StyleSequencer`, then wrote them only to AudioEngineManager/BASSMIDI. MidiInputManager forwarded notes and programs and CC72, but no mixer CC7/11/10/91/93. There was also no mixer snapshot replay when MIDI OUT was enabled or reconnected. Disabling MIDI OUT previously suppressed subsequent NOTE_OFF while previously transmitted NOTE_ON could remain held externally.

The sequencer, CASM, clock, Main/Fill scheduling, native BASSMIDI and RIGHT OFF implementation are unchanged. A controller observer at AudioEngineManager now forwards the exact effective values already computed for the native write. The controller snapshot lock does not participate in native rendering, SF2 loading, note ownership or the scheduler clock. MidiMixerOutput serializes MIDI snapshots, port replay and external note ownership.

## Channel matrix

Indices in Kotlin/JNI are zero-based; MIDI channel numbers on hardware are one-based.

| Part | Kotlin / BASS channel | MIDI hardware channel | Fader / mute binding |
|---|---:|---:|---|
| RIGHT1 | 0 | 1 | CC11 / explicit keyboard mute |
| RIGHT2 | 1 | 2 | CC11 / explicit keyboard mute |
| RIGHT3 | 2 | 3 | CC11 / explicit keyboard mute |
| LEFT | 3 | 4 | CC11 / explicit keyboard mute |
| Rhythm1 | 8 | 9 | Effective style CC7 |
| Rhythm2 | 9 | 10 | Effective style CC7 |
| Bass | 10 | 11 | Effective style CC7 |
| Chord1 | 11 | 12 | Effective style CC7 |
| Chord2 | 12 | 13 | Effective style CC7 |
| Pad | 13 | 14 | Effective style CC7 |
| Phrase1 | 14 | 15 | Effective style CC7 |
| Phrase2 | 15 | 16 | Effective style CC7 |
| Additional CASM destinations, if actually used | 4–7 | 5–8 | Effective style CC7; auxiliary STYLE CC11 trim |
| STYLE bus | No separate channel | Only used accompaniment destinations | Relative CC11 trim, 127 = unity |
| MASTER bus | No separate channel | RIGHT1/2/3, LEFT and used style destinations | Scoped CC11 trim, 100 = unity |

The live accompaniment registry comes from existing `StyleMixerChannels` / voice assignments, using actual positive-velocity NOTE_ON destinations across the style, not a fabricated eight-channel inventory. It is registered before style READY, so the first external note cannot race a UI inventory collector. Unused style channels receive no MIDI mixer CC, program replay or notes from the router.

## Controllers and lifecycle

- CC7 preserves each style channel's source volume multiplied by its channel trim. CC11 preserves the source expression, its EXP trim and STYLE trim already calculated by the sequencer.
- CC10, CC91 and CC93 mirror effective pan/reverb/chorus for the same destination. Keyboard EXP remains the existing keyboard volume/CC11 binding.
- The sequencer already applies STYLE trim to 8–15. Its public style mixer destination contract also allows 4–7; an auxiliary adapter applies STYLE trim once to these destinations without changing the scheduler or double-trimming 8–15. Default trim 127 leaves the original values unchanged.
- MIDI MASTER composes a further CC11 multiplier with each stored effective expression. It never overwrites channel CC7, sends universal Master Volume SysEx, or treats CC7 as a device-wide master. Both paths saturate above 100/unity. MIDI equipment can have a different expression-to-audio curve from BASSMIDI.
- **Gain zero is not mute.** Fader zero, MASTER zero and style CC7/11 zero keep notes alive for later fades, matching the native gain behavior. Only explicit MUTE/SOLO flags terminate currently owned external notes and suppress subsequent NOTE_ON for those parts.
- MUTE/SOLO sends NOTE_OFF only for actually transmitted notes on affected channels. NOTE_OFF remains routable during mute; unmute sends no automatic NOTE_ON. BASSMIDI retains its existing gain/mute binding. Failed NOTE_OFF is retried before audible external gain is restored.
- No new global panic, CC120, CC123, reset controllers or CC64 is used for the mixer. The checkpoint's existing per-RIGHT OFF CC120 remains intact.
- Output switching does not rewrite the native mixer. Reconnect replays only observed mixer fields; nominal CC11 starts at MIDI/BASS unity (127), and untouched CC7/PAN/REV/CHO are not invented or transmitted.
- MIDI OUT disable releases its tracked external notes before disabling transmission. Enable/reconnect replays current program/controller snapshots and MASTER composition, without replaying held notes. Current effective RELEASE is resent as existing CC72 on RIGHT1/2/3 when a port reconnects.
- Program changes restore that channel's mixer snapshot after the bank/program messages, protecting against modules that reset controllers on voice changes.

## Layout

Six blue fixed strips on the left: RIGHT1, RIGHT2, RIGHT3, LEFT, STYLE and MASTER. Only used green style strips are inside the independent horizontal scroll area on the right. Tablet widths keep 112 dp keyboard/style strips and 80 dp bus strips; smaller windows use 96 dp fixed keyboard strips with 48 dp MUTE/SOLO targets and 56 dp bus strips. Faders remain vertical 0–127. VOICE, MUTE, SOLO, PAN/DETAIL, values and ARRANGER remain available. STYLE/MASTER DETAIL explains bus semantics. No fake PCM meter is displayed.

No screenshot attachment was available in the request; the implementation follows the supplied textual layout. Tablet ergonomics and external module audio require device verification.

## Regression evidence and limits

Mandatory command: `python3 tools/test_voice_release.py --jvm --regressions --fetch-dependencies`.
Real BASSMIDI PCM command in Actions: `python3 tools/test_voice_release.py --real` after installing the real Linux SDK inputs.

Local contracts PASS: 68 JVM tests (including 22 new MIDI OUT cases), 2,253 native API-recorder checks and the companion lifecycle/routing/bank/accompaniment/percussion/role/liveness regressions. Real PCM and Android build remain gated in Actions.

New recorder cases cover five CC destinations for all keyboard parts, native/port correspondence, source automation, Main→Fill→Main restoration, balanced STYLE trim, managed-only MASTER, explicit mute ownership, gain-zero fades, multi-solo/manual-mute restoration, late key-up, repeated note ownership, retry after transport failure, output disable/enable, reconnect, program/controller restoration, removed/unused style channels, 4–7 auxiliary trim and concurrent controller writers.

Host MIDI recorder proves packet values, ordering and destination, not the external module's audio or CC support. Real Linux BASSMIDI PCM uses controlled synthetic SF2 and verifies native RELEASE, RIGHT OFF, volume/mute, SOLO and MASTER regressions; it is separate from Android/USB MIDI hardware validation. Actions must pass these gates, frozen style/RELEASE/legacy guard checks, Android SDK identity checks, both ABI builds and APK verification before publishing an APK.

The separate original F12 guard (`tools/test_drum_shadow_guards.py`) still reports its pre-existing SHA mismatch on `StyleSequencer.kt`. That scheduler file and the legacy guard are unchanged; this failure is reported separately rather than weakening the guard.
