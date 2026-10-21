# Custom SF2 & Dangdut Drum Kit — Stage 1

Branch `experiment/custom-sf2-drum-kit` (from `experiment/style-voice-browser` 12c8e29). Not merged to main.
Evidence level: JVM/native HOST tests with a mocked BASS. Nothing here is verified audibly or on a real BASSMIDI instance.

## A. Custom SF2 discovery
* Audit: before this stage only the primary (Colombo) + fallback (Tyros) + one Prime Melody were attached; `.sf2` files with other names were ignored.
* Now every `.sf2` in the YamahaArranger SF2 folder is considered, no hard-coded names (`ManagedSf2Policy`). Colombo = primary and Tyros = fallback keep the automatic resolver. Everything else is attached MANUAL-ONLY: reachable by hand (Voice Selector, Style Voice Editor), never by the automatic resolver, so existing automatic Style selection does not change.
* Found without rebuilding the APK: the folder is scanned on every SF2 load.
* Budget (RAM / load time / cache duplication): at most 3 manual files and 1000 MB total; order SX Prime Melody, SX Prime Drum, then others by name. Skipped files are reported with a reason (`LIMIT_3_MANUAL_FILES`, `OVER_BUDGET_1000MB`, `EMPTY_OR_UNREADABLE`) and listed in the kit picker (`ATTACHED (manual-only)` / `NOT ATTACHED: reason`). Native pool: Tyros + 3 manual fonts.
* Stale `ready-<generation>` cache copies are deleted after a successful load (previously duplicates accumulated).

## B. Drum preset inventory
Drum presets (raw banks 127/128) of every loaded SF2 are listed apart from melodic ones (`drumKitCandidates` vs `sf2Candidates`). Identity = source SF2 + bank + program (+ pdta fingerprint stored with the pin).

SX Prime Drum (`DRUM … PRIME.sf2`, 152.7 MB, 21 presets, all bank 128; program is 0-based; native test lists exactly these):
0 STANDAR PSR-SX · 1 STANDAR DRUM 2 · 2 STANDAR DRUM 3 · 4 DHUT PSR SX SERIES · 6 TABLA GM BARATA · 7 Clowor Pallapa · **8 Kendang Alex** · 10 ADELLA PSR-SX · 11 TRIAZ 2018 PSR-SX · 13 EXP : DJ FUNKOT 1 · 16 ALEX KEPRI · 23 DUT SERA · 24 REMIX PSR-SX · 28 CLOW D'ACADEMY · 32 CLOW D'ACADEMY II · **36 KENDANG DHIDI** · 39 KRISNA PSR-SX900 · 40 CLOWOR PSR-SX · 41 CLOW KEMPUL PSR-SX · 45 CLOW KEMPUL PSR-SX 2 · 46 D'ROSTA SX700-SX900

A preset NAME does not prove the kit holds kendang samples. What the pdta does show (zone tables, not audio): Kendang Alex has 220 zones over keys 36–90 (55 keys); KENDANG DHIDI 312 zones over keys 4–91 (71 keys).

## C. Style Drum Kit Editor
* Style Voice Editor → Rhythm 1 / Rhythm 2 row → CHOOSE KIT: pick any drum kit of any loaded SF2, search by name/SF2/bank:program, AUDITION KIT, AUDITION ORIGINAL, PLAY NOTE (note stepper, 0–127), APPLY (manual), SAVE PROFILE, RESET PART.
* Rhythm 1 and Rhythm 2 are independent (own override each). RIGHT/LEFT and melodic parts are untouched; no MIDI OUT message is sent for drums.
* Scope in Stage 1: whole Style only (ALL sections). Reason: per-section kit changes would re-run native `applyFonts` at section transitions; deferred to avoid engine churn.
* Native: reviewed Colombo/Tyros kits still use `bindDrumAB`; any other loaded SF2 binds through new `bindDrumManual` (exact font id + raw bank + program, must be listed and present, readback verified; refused otherwise, never substituted).
* SAVE PROFILE / reload keep the chosen kit. If the SF2 is missing/renamed or its pdta changed, the pin shows `STALE_*`, the Style's own kit plays, and the stored choice is kept untouched (never silently replaced).
* Audition is internal only, needs the Style stopped, ends with note-off and returns the effective kit; no global reset is used.

## D. Drum Note Mapping — requirements only (NOT implemented)
Source: ROSITA.STY (production parser) note-ons on Rhythm channels vs kit key coverage from the pdta zone tables.
* ROSITA Rhythm 2 (ch 10) plays 20 distinct notes (33–37, 42, 43, 45–48, 50, 53–55, 57, 60, 62–64); Rhythm 1 (ch 9) plays 34 and 53.
* STANDAR PSR-SX (0): covers all of them. **Kendang Alex (8): no zone for keys 33, 34, 35 (ch 10) and 34 (ch 9)** — those hits would be silent. **KENDANG DHIDI (36): no zone for 46 and 54.**
* Therefore Stage 1 with a kendang kit on a Style authored for a GM/XG kit will be silent for notes outside the kit's range, and kendang hits will sit on keys the Style uses for other drums. Needed later: per-kit note remap table (Style note → kit key), fallback policy for uncovered notes (silent vs nearest vs layer to original kit), per-Style storage, audible A/B on device. Key-coverage in this report is pdta evidence, not an audio claim.

## Tests (host)
CPV JVM 234 tests OK (new: ManagedSf2PolicyTest, kit ops, kit integration, audition/STALE); native `cpv-native` PASS including SX Prime Drum 21 presets, `bindDrumManual` Rhythm 1 = 128:8 and Rhythm 2 = 128:36 independent, melodic/absent/wrong-channel refusals; see CI for LIVE/REG/Registration Recall/native suites.

## Redmi Pad test steps
1. Put extra `.sf2` (e.g. SX Prime Drum, a custom kendang SF2) in the YamahaArranger SF2 folder; reload SF2. Kit picker footer shows ATTACHED / NOT ATTACHED.
2. Load a Style, open Style Voice Editor, tap Rhythm 1 → CHOOSE KIT → Kendang Alex → PLAY NOTE 36/38/42 (Style stopped) → APPLY.
3. Rhythm 2 → KENDANG DHIDI → APPLY. START: MAIN/FILL/MAIN. Check RIGHT/LEFT unaffected and the E343 receives no drum Program Change.
4. SAVE PROFILE, restart app, reload Style: kit restored. Remove the SF2 and reload: STALE shown, Style's own kit plays.
5. Hit STOP during audition: no stuck notes.

## Risks / unverified
* Each attached SF2 is read twice (normalise + pdta fingerprint) and a `.bassmidi-normalized.sf2` twin is written: transient RAM ≈ file size, load time grows with size. 1000 MB / 3-file cap is a conservative guess, not measured on the Redmi Pad.
* Real BASS drum-bank readback for non-reviewed kits, `BASS_MIDI_FontInit` behaviour and audible result are unverified (host tests use a mock BASS).
* Audition of the ORIGINAL kit needs the Style to have played once; if a candidate kit was auditioned on a never-played Style it stays until the next START rebinding.
