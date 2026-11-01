# PSR-E343 MIDI OUT compatibility audit

Branch `experiment/e343-midi-out-audit` (experiment only, not merged, no PR).
Baseline: `a400482` (latest green build, split + MIDI IN). The last build the user explicitly confirmed playable was the mixer APK `9fb34c6`; `a400482` descends from it and differs only in MIDI IN / split work. Nothing here touches that behaviour.

## Update 2026-11-01 (continued): official Yamaha documents read, Voice List and Drum Kit List extracted
Sources (downloaded in this session after `usa.yamaha.com` / `data.yamaha.com` were allowed; the PDFs are NOT committed, only their identity):
| Document | URL (redirects to data.yamaha.com) | SHA-256 |
|---|---|---|
| PSR-E343/YPT-340 Owner's Manual (en, b0) | `https://usa.yamaha.com/files/download/other_assets/6/329476/psre343_en_om_b0.pdf` | `2172a1ef397960f2dbd377d8968d3cbbc6163dea8109f8416ce95e3d67076261` |
| PSR-E343/YPT-340 MIDI Reference (en, a0) | `https://usa.yamaha.com/files/download/other_assets/4/329464/psre343_en_mr_a0.pdf` | `682179009b63b3fa7a86fd3491b20c3bb8acf25354392818dcaf61f667c0d80d` |

The Voice List and the Drum Kit List are in the **Owner's Manual** (pp. 48-55); the MIDI Reference holds the Implementation Chart, SysEx and Effect map, and no voice table.

Produced (data only, nothing wired into the app):
* `docs/yamaha/E343_VOICES.tsv`: 550 list numbers; **523 MIDI-selectable rows** = Panel 1-152 and 180-205 (incl. 18 drum kits 188-205) + XGlite 206-550. The 27 DUAL voices (153-179) are printed with "–" for MSB/LSB/PC (they layer two voices and cannot be selected over MIDI): kept as `#DUAL` comment lines. Columns 1-4 are exactly the format the app already reads (`name, MSB, LSB, program 1-128`); extra columns: voice no., list, category, kind, source page.
* `docs/yamaha/E343_DRUMKITS.tsv`: one row per (kit, note) with **MIDI note number and keyboard note number in separate columns** (keyboard = MIDI + 12, manual footnote p. 53), note names (Yamaha C3 = 60), sound, `same_as_std1` flag, source page.
* `diagnostics/e343/extract_e343_lists.py` (extractor, poppler `pdftotext` + `pdfplumber`, re-run gives byte-identical TSVs), `diagnostics/e343/e343_style_compat.py` (classifier over the production parser's Style dump), per-Style results `diagnostics/e343/HardRock2_E343_compat.md`, `diagnostics/e343/ROSITA_E343_compat.md`.

Extraction checks (the script exits non-zero if any fails): voice numbers exactly 1..550; MSB/LSB 0-127, Program 1-128; no duplicate MSB/LSB/Program triple; 18 drum kits; each Drum Kit List header (MSB/LSB/PC) equals the Panel Voice List row of that kit; keyboard note = MIDI note + 12 on every row; Standard Kit 1 read identically on both pages where it is printed; Standard Kit 1 gives Bass Drum 36 / Snare M 38 / Hi-Hat Closed 42 on the MIDI column; SFX Kit 1 lands on the XG positions (36 Cutting Noise ... 91 Maou).

Facts from the documents used below:
* "Program Numbers 001 to 128 directly relate to MIDI Program Change Numbers 000 to 127" (Owner's Manual p. 48): the list is 1-based, the wire is 0-based.
* Drum Kit List: the **MIDI** note column is the one that matters for MIDI OUT (Standard Kit 1: 36 Bass Drum, 38 Snare M, 42 Hi-Hat Closed, ...). The *keyboard* column is one octave higher (the E343 panel plays drums an octave up). The app sends the Style's authored notes unchanged, which is the MIDI column: **no octave error on the wire**.
* Drum kits are MSB 127 (10 kits: PC 1, 2, 9, 17, 25, 26, 33, 41, 49, 113) and MSB 126 (8 kits: PC 1, 2, 37, 40, 41, 113, 115, 128), always LSB 0.
* MIDI Implementation Chart, Recognized: Bank Select 0/32, 1, 6, 7, 10, 11, 64, 71-74, 72, 91, 93, RPN, Program 0-127, Note 0-127, All Notes Off 123-125. Every controller the app writes (0, 7, 10, 11, 32, 72, 91, 93, 123) is recognized. By default the E343 is a 16-channel multitimbral tone generator; maximum polyphony 32 notes (shared with its own accompaniment/keyboard).
* **Not documented anywhere in either document:** what the E343 does when it receives a Bank/Program that is not in its list (fallback to another LSB, to Standard Kit, ignore, keep the previous voice). Every "probable fallback" below is therefore UNVERIFIED.
* Ambiguities kept, not resolved by guessing: (a) page 55 kits (199-205) do not print Standard Kit 1 beside them; their blank cells are flagged `footnote_only` (the p. 53 footnote says blank = same as Standard Kit 1). (b) Sound Effect Kit (205) prints "Drum Loop" and "Huuaah!" across merged rows; no note is claimed for them (comment lines in the TSV). (c) Indian Kit 2's header prints `126/000/0115`; the Panel list gives 126/0/115.

### Result: HardRock2.S578 (what the router actually writes; wire order proved again by `E343MidiOutAuditTest`, run in this session: OK 2 tests)
| Part (Style name) | MIDI ch (1-based) | Sent MSB/LSB/PC (0-based) | List Program | E343 status | E343 list facts |
|---|---|---|---|---|---|
| Rhythm1 'perc' | 9 | 127/0/0 | 1 | **EXACT** | #188 Standard Kit 1. Only note 54 = Tambourine (x208). |
| Rhythm2 'drums' (+ 'CC_Crash') | 10 | 127/0/86 | 87 | **UNSUPPORTED** | No E343 kit 127/0/87. Notes 36,38,41,42,43,45,46,47,48,49,50,52,55,57 all exist in Standard Kit 1 / Rock Kit; whether the E343 falls back to a kit is undocumented. |
| Bass | 11 | 0/114/33 | 34 | **FALLBACK_CANDIDATE** | 0/114/34 not listed. Same Program: #45 Finger Bass 0/112/34, #44 Dynamic Electric Bass 0/116/34, XGlite #305 Finger Bass 0/0/34. |
| Phrase1 'DistGt' | 12 | 0/115/30 | 31 | **FALLBACK_CANDIDATE** | 0/115/31 not listed. Same Program: #43 Distortion Guitar 0/112/31, XGlite #296 Distortion Guitar 0/0/31. |
| Chord2+Pad 'OrgRoot'/'Organ' | 14 | 0/115/18 | 19 | **FALLBACK_CANDIDATE** | 0/115/19 not listed. Same Program: #17 Rock Organ 0/112/19, #18 Purple Organ 0/114/19, #13 Cool! Organ 0/118/19, #14 Cool! Rotor Organ 0/117/19, XGlite #260 Rock Organ 0/0/19 ... |
| Chord1 'RGuit' (7 sections) | 15 | 0/125/27 | 28 | **FALLBACK_CANDIDATE** | 0/125/28 not listed. Same Program: #39 Clean Guitar 0/112/28, #37 60s Clean Guitar 0/117/28, XGlite #288 Clean Guitar 0/0/28. |

The Style file itself writes **two** Bank/PC sets at tick 0 in each part (a first set, then the final one): drums 127/0/84 then 127/0/1 (Rhythm1) or 127/0/87 (Rhythm2); Bass 0/112/34 (EXACT on the E343) then 0/114/34; DistGt 0/113/31 then 0/115/31; RGuit 0/123/28 then 0/125/28. The app sends only the final set (as the sequencer applies the last tick-0 values), which is what a Yamaha instrument ends up on as well. Not changed. Also: in FillAA one RGuit part has no CASM entry (0/123/28); whether the setup path sends it is not proven by the host test (marked UNPROVEN in the per-Style file).

### Result: ROSITA.STY
**Every request is EXACT**: kits 127/0/1 Standard Kit 1, 127/0/9 Room Kit (Rhythm2, MainA), 127/0/17 Rock Kit (Rhythm1, MainB); voices 0/112/PC 4 Honky-tonk Piano, 28 Clean Guitar, 34 Finger Bass, 49 String Ensemble, 53 Choir, 55 Air Choir. All authored drum notes (Rhythm1 34, 53; Rhythm2 33..64, 20 distinct) have a sound in the selected kit.

### What this means (evidence vs. inference)
* **Proven from the list:** ROSITA asks the E343 only for voices and kits it has; HardRock2 asks for one drum kit (127/0/87, Rhythm2, ch 10) and four melodic banks (0/114/34, 0/115/31, 0/115/19, 0/125/28) that the E343 does not have.
* **Consistent with, but not proof of,** the user's report (ROSITA normal, HardRock2 'kresek'/raw). H2 (missing kit) is now confirmed at the list level; what the E343 actually plays for it is unknown. H1 is narrowed: Rhythm1 (ch 9) asks for Standard Kit 1, which exists, and ROSITA uses the same ch 9 / MSB 127 request and sounds normal, so ch 10 (Rhythm2, kit 87) is the first suspect.
* **Probable but undocumented:** the melodic requests fall back to the same Program on another bank (XG convention is LSB 0, i.e. the plain XGlite voice), which would explain the raw DistGt/Organ timbre (H3).

### Candidate manual corrections (suggestions only; NOT applied, mapping unchanged)
Melodic parts can be set per Style with the existing manual MIDI OUT voice override (Style Editor, EXTERNAL tab, which will list these names once `E343_VOICES.tsv` is on the device): Bass → #45 Finger Bass 0/112/34; DistGt → #43 Distortion Guitar 0/112/31; Organ → #17 Rock Organ 0/112/19 (or #18 Purple Organ 0/114/19); RGuit → #39 Clean Guitar 0/112/28. For Rhythm2 a kit such as #191 Rock Kit 127/0/17 would be the natural candidate, **but the app currently cannot override drums on MIDI OUT** (the external override skips drum parts by design); that would be a code change and is out of scope here.

## Verdict (honest)
* The software path was audited with two real Styles. **No bug was found in what the app sends**: Bank MSB / LSB / Program Change are written in the right order and with exactly the values the Style (CASM) asks for, and drum notes on MIDI OUT are byte-for-byte the notes the Style authored.
* Whether the PSR-E343 *has* the requested voices and drum kits is now answered from the official Owner's Manual (see the update above): ROSITA all EXACT; HardRock2 has 1 unsupported drum kit and 4 melodic requests with a bank the E343 does not list. What the E343 does with an unlisted request remains undocumented.
* **No mapping, fallback or drum patch was made** (Stage 7 rule: no speculative patch; the user also asked not to change production code or the MIDI OUT mapping). Production changes are passive diagnostics plus a read-only classifier.

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
Correction (2026-11-01): the 1-based channel column of the first version of this table was off by one for some rows. The wire log of `E343MidiOutAuditTest` (`sent chN`, 1-based) gives:
| Part | Dst ch (1-based) | MSB | LSB | PC (sent / display) | Style voice name |
|---|---|---|---|---|---|
| Rhythm1 'perc' | 9 | 127 | 0 | 0 / 1 | perc |
| Rhythm2 'drums' | 10 | 127 | 0 | 86 / 87 | drums, CC_Crash |
| Bass | 11 | 0 | 114 | 33 / 34 | Bass |
| Phrase1 | 12 | 0 | 115 | 30 / 31 | DistGt |
| Chord2 / Pad | 14 | 0 | 115 | 18 / 19 | OrgRoot, Organ |
| Chord1 (IntroB and 6 other sections) | 15 | 0 | 125 | 27 / 28 | RGuit |

Authored drum notes: Rhythm1 = only note 54 (x208); Rhythm2 = 36,38,41,42,43,45,46,47,48,49,50,52,55,57.

### ROSITA.STY
Rhythm1 MSB127/LSB0 PC 0 (later 16), Rhythm2 PC 0 / 8; melodic parts all MSB0 / LSB112 (PC 33, 27, 27, 48, 27, 3, 54, 52). Authored drum notes: Rhythm1 34,53; Rhythm2 33..64 (20 distinct).

Drum notes written to MIDI OUT == notes sent to the internal engine == authored notes (asserted). No transpose on drum notes.

## Compatibility tables
See the update at the top (per-Style tables) and `diagnostics/e343/*_E343_compat.md` (every request incl. the overridden tick-0 sets, sections, and the drum notes with their E343 sound names). The Style Editor `PSR-E343 (MIDI OUT)` line stays `UNKNOWN` on the device until `E343_VOICES.tsv` is copied there (the app ships no built-in list; nothing changed in the app). With the file present it shows the statuses above (proved by `tests/cpv/E343CatalogTest`, which feeds the TSV through the production `parseDeviceCatalog` and `E343Compat`).

## Hypotheses (status after the Data List)
* **H2 kit 87 absent: CONFIRMED at list level.** The E343 has no 127/0/87. ROSITA's kits (1, 9, 17) exist. Device behaviour on the missing kit: unknown.
* **H1 'kresek' drums:** narrowed to Rhythm2 (ch 10). Rhythm1 (ch 9, Standard Kit 1, note 54 Tambourine) is EXACT. Needs the device test below to prove which channel makes the noise.
* **H3 voice banks: CONFIRMED at list level** for Bass (LSB 114), DistGt (115), Organ (115), RGuit (125). Fallback behaviour unknown; effects (CC91/93) are recognized by the E343.
* Drums cannot currently be overridden on MIDI OUT (the manual external voice override explicitly skips drums). A per-Style kit override is a possible future change only after H1 is confirmed on the device.

## Production changes
* `MidiMixerOutput`: passive `outputReport()` / `resetWrittenStats()` and a `written` NOTE_ON counter (additions only; wire writes untouched).
* `MidiInputManager`, `MainViewModel`: forwarding functions. `RightVoiceDiagOverlay`: chips **MIDI OUT STATE** and **CLEAR OUT NOTES**.
* `E343Compat.kt` (new, pure, no built-in data) + one read-only line in `StyleVoiceEditorScreen`.
* Not changed: native/BASSMIDI, StyleSequencer, CASM, ArrangerBrain, MIDI IN, split, ACMP/LEFT, octave correction, mixer, internal gain.

## Tests
* 2026-11-01 continuation: CPV JVM suite **321 OK** (adds `E343CatalogTest`: 523 selectable rows, HardRock2/ROSITA statuses through the production parser + classifier); `E343MidiOutAuditTest` re-run on HardRock2 and ROSITA: OK; extractor re-run byte-identical; guard extended (no `app/` change after `7ba5b00`).
* CPV JVM suite 318 OK (includes new `E343CompatTest`: UNKNOWN without list, 1-based/0-based, EXACT/COMPATIBLE/FALLBACK_CANDIDATE/UNSUPPORTED).
* rtdiag `E343MidiOutAuditTest` on HardRock2 and ROSITA: OK. Regressions: default rtdiag `--unit` 52, `MidiOutOctaveTest` 8, `MidiInSplitRtTest` 7, `MixerKeysRtTest` 8: all OK.
* Guard `tools/test_e343_audit_guard.py` (additions only, frozen files byte-identical to `a400482`).
* These are software-logic proofs. They are **not** proof of how the E343 sounds.

## Regression risk
Very low: the new code only reads state under the existing router lock; no change to what is written. Default behaviour is identical to baseline (there is no toggle because nothing behavioural changed).

## Test on the PSR-E343
1. Copy `docs/yamaha/E343_VOICES.tsv` from this branch to `/storage/emulated/0/YamahaArranger/E343_VOICES.tsv`; the Style Editor then shows EXACT / FALLBACK_CANDIDATE / UNSUPPORTED per part and the EXTERNAL tab lists the E343 voice names (no app change needed).
2. Play HardRock2 and ROSITA on MIDI OUT. Open RIGHT DIAG → **MIDI OUT STATE** while playing; copy the text (also in the debug log). Press **CLEAR OUT NOTES** between runs.
3. Mute channels one by one (Mixer) to find which Rhythm channel makes the 'kresek'; start with Rhythm2 (ch 10, kit 87). Note which parts sound wrong and the Style/section.
3b. Optional A/B for the melodic parts: set the candidates above with the manual MIDI OUT override (Bass 0/112/34, DistGt 0/112/31, Organ 0/112/19, RGuit 0/112/28) and compare by ear. Remove the override afterwards if not wanted.
4. Send the logs plus the Data List; then the drum / voice mapping can be decided from evidence.
