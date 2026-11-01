# PSR-E343 MIDI OUT compatibility audit

Branch `experiment/e343-midi-out-audit` (experiment only, not merged, no PR).
Baseline: `a400482` (latest green build, split + MIDI IN). The last build the user explicitly confirmed playable was the mixer APK `9fb34c6`; `a400482` descends from it and differs only in MIDI IN / split work. Nothing here touches that behaviour.

## Verdict (honest)
* The software path was audited with two real Styles. **No bug was found in what the app sends**: Bank MSB / LSB / Program Change are written in the right order and with exactly the values the Style (CASM) asks for, and drum notes on MIDI OUT are byte-for-byte the notes the Style authored.
* Whether the PSR-E343 *has* the requested voices and drum kits is **UNKNOWN**. The Yamaha documents (MIDI Reference, Data List, Voice/Drum Kit List) could not be fetched from this session (usa.yamaha.com, manualslib.com not reachable). No bank / program / drum note was invented.
* Therefore **no mapping, fallback or drum patch was made** (Stage 7 rule: no speculative patch). Production changes are passive diagnostics plus a read-only classifier.

## Files / functions examined
`StyleSequencer` (`applyVoicesFromCasm`, dynamic PC path, `stageIo`, external-voice override skipped for drums at two `if (drum) null` sites), `MidiInputManager.sendProgramChange`, `MidiMixerOutput` (CC0 → CC32 → PC order, CC7/10/11/91/93, note ledger, mute/solo release, octave correction), `StyleVoiceEditorOps.parseDeviceCatalog` (user-supplied `E343_VOICES.tsv`), `StyleOverrideStore`.

## Routing
```
Style file (SMF tracks + CASM Ctab)
  -> parser (native C++) -> StylePartModel(bankMsb, bankLsb, program, casm dst channel)
  -> StyleSequencer.applyVoicesFromCasm / dynamic PC
       -> sendProgramChange(dst, program, msb, lsb)
            -> MidiMixerOutput : CC0(MSB) -> CC32(LSB) -> PC   (serialised, per channel)
  -> NOTE ON/OFF via stageIo (engine + MIDI OUT paired, CASM NTR/NTT already applied)
  -> MidiMixerOutput (octave correction only on ch 10..15, never drums) -> USB MIDI -> PSR-E343
Destination channels (0-based; display +1): 8/9 = Rhythm1/2, 10 Bass, 11 Chord1, 12 Chord2, 13 Pad, 14 Phrase1, 15 Phrase2. Keyboard parts use 0..3 and never receive Bank/PC from the Style.
```

## Evidence (real Styles through the production chain, host test `E343MidiOutAuditTest`)
Wire order asserted: every Program Change is preceded by CC0 then CC32 on the same channel. Controllers written: only 0, 7, 10, 11, 32, 72, 91, 93, 123 (observed 0, 11, 32, 123).

### HardRock2.S578 (display = program+1)
| Part | Dst ch (1-based) | MSB | LSB | PC (sent / display) | Style voice name |
|---|---|---|---|---|---|
| Rhythm1 'perc' | 10 | 127 | 0 | 0 / 1 | perc |
| Rhythm2 'drums' | 11 | 127 | 0 | 86 / 87 | drums, CC_Crash |
| Bass | 12 | 0 | 114 | 33 / 34 | Bass |
| Chord2 / Pad | 15 | 0 | 115 | 18 / 19 | OrgRoot, Organ |
| Phrase1 | 13 | 0 | 115 | 30 / 31 | DistGt |
| (section IntroB) | 16 | 0 | 125 | 27 / 28 | RGuit |

Authored drum notes: Rhythm1 = only note 54 (x208); Rhythm2 = 36,38,41,42,43,45,46,47,48,49,50,52,55,57.

### ROSITA.STY
Rhythm1 MSB127/LSB0 PC 0 (later 16), Rhythm2 PC 0 / 8; melodic parts all MSB0 / LSB112 (PC 33, 27, 27, 48, 27, 3, 54, 52). Authored drum notes: Rhythm1 34,53; Rhythm2 33..64 (20 distinct).

Drum notes written to MIDI OUT == notes sent to the internal engine == authored notes (asserted). No transpose on drum notes.

## Compatibility tables
Voice and drum-kit compatibility with the E343: **UNKNOWN for every row** (no Data List available). The Style Editor detail panel now shows a `PSR-E343 (MIDI OUT)` line per part: `UNKNOWN` until the user supplies `E343_VOICES.tsv`; then `EXACT / COMPATIBLE / FALLBACK_CANDIDATE / UNSUPPORTED` (pure `E343Compat`). It is informational and never applied automatically.
Known only from snippets (unverified): E343 lists use 1-based program numbers (send number-1); Bank/PC should be sent as a set; XG-style Styles request banks 0/112, 114, 115, 125 and kit bank 127 which may not all exist on a low-end GM/XGlite module.

## Hypotheses (not proven, none patched)
* **H1 'kresek' drums**: HardRock2 Rhythm1 'perc' plays only note 54 on channel 10 (MSB127 PC0). If the E343 does not treat that request as a drum kit, or kit 87 (PC 86, Rhythm2) does not exist, notes would map to the wrong samples. Needs the kit list and the device log.
* **H2 kit PC 86 absent**: ROSITA (PC 0/8/16, normal sound) vs HardRock2 (PC 86, noisy) suggests the kit number may matter. Unproven.
* **H3 voice banks**: DistGt requests MSB0/LSB115/PC30; if the E343 lacks LSB 115 it likely falls back to plain GM Distortion Guitar (raw timbre). Effects (CC91/CC93) limits on the E343 are UNKNOWN. Not an app-side velocity/gain issue and not masked.
* Drums cannot currently be overridden on MIDI OUT (the manual external voice override explicitly skips drums). A per-Style kit override is a possible future change only after H1/H2 are confirmed.

## Production changes
* `MidiMixerOutput`: passive `outputReport()` / `resetWrittenStats()` and a `written` NOTE_ON counter (additions only; wire writes untouched).
* `MidiInputManager`, `MainViewModel`: forwarding functions. `RightVoiceDiagOverlay`: chips **MIDI OUT STATE** and **CLEAR OUT NOTES**.
* `E343Compat.kt` (new, pure, no built-in data) + one read-only line in `StyleVoiceEditorScreen`.
* Not changed: native/BASSMIDI, StyleSequencer, CASM, ArrangerBrain, MIDI IN, split, ACMP/LEFT, octave correction, mixer, internal gain.

## Tests
* CPV JVM suite 318 OK (includes new `E343CompatTest`: UNKNOWN without list, 1-based/0-based, EXACT/COMPATIBLE/FALLBACK_CANDIDATE/UNSUPPORTED).
* rtdiag `E343MidiOutAuditTest` on HardRock2 and ROSITA: OK. Regressions: default rtdiag `--unit` 52, `MidiOutOctaveTest` 8, `MidiInSplitRtTest` 7, `MixerKeysRtTest` 8: all OK.
* Guard `tools/test_e343_audit_guard.py` (additions only, frozen files byte-identical to `a400482`).
* These are software-logic proofs. They are **not** proof of how the E343 sounds.

## Regression risk
Very low: the new code only reads state under the existing router lock; no change to what is written. Default behaviour is identical to baseline (there is no toggle because nothing behavioural changed).

## Test on the PSR-E343
1. Put your E343 Data List (voices and drum kits) as `E343_VOICES.tsv` in `YamahaArranger/` (`name<TAB>MSB<TAB>LSB<TAB>program(1-128)`), or paste it; then the Style Editor shows EXACT/UNSUPPORTED per part.
2. Play HardRock2 and ROSITA on MIDI OUT. Open RIGHT DIAG → **MIDI OUT STATE** while playing; copy the text (also in the debug log). Press **CLEAR OUT NOTES** between runs.
3. Mute channels one by one (Mixer) to find which Rhythm channel makes the 'kresek'; note which parts sound wrong and the Style/section.
4. Send the logs plus the Data List; then the drum / voice mapping can be decided from evidence.
