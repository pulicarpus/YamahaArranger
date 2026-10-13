# LIVE CONTROL / REG MEMORY — Phase 0–1 only

Source: user-supplied `YamahaArranger_LiveControl_RegMemory_Audit_V2.zip`, notably README, CODEX_HANDOFF, IMPLEMENTATION_PLAN, REG_OUTPUT_MATRIX, REGISTRATION_MEMORY_DESIGN, PERSISTENCE_RECOVERY and TEST_PLAN. V2 supersedes V1. Its read-only wording describes Claude's audit, not this explicitly authorized implementation.

Required remote baseline was fetched and verified as `ea13199f69b4757d69e72c55cac45b9b9079aec3` on `origin/experiment/e343-compact-mixer-v2`. Branch: `experiment/live-control-reg`.

## Phase 0

`test_live_reg_isolation.py` compares **every pre-existing tracked file** with the pinned baseline. Only the three named registration production files may be added. Old guards, MainViewModel/UI, native code, style/chord/parser, MIDI input/router, audio manager, Room, RELEASE and RIGHT OFF remain byte-identical.

The old mixer integration guard intentionally allows only two production files relative to E343 9915a49; adding registration files would fail its historical scope check. It is not edited or weakened. `test_live_reg.py --baseline` creates a temporary detached worktree at the exact baseline and runs that guard, the RIGHT OFF guard and all original JVM/native regression tests unchanged. The live-branch isolation guard runs before and after, proving the tested runtime files have not changed. The CI workflow is separate and has no APK job; it adds strict real Linux BASSMIDI PCM regression testing.

## Phase 1

- `RegistrationModel.kt`: schema 2, four-part order, eight-slot bank; independent SF2 file/native bank/program/name and external MSB/LSB/program/label/profile. No persisted session SoundFont ID. INTERNAL/EXTERNAL/BOTH are data, not routing switches.
- `RegistrationCodec.kt`: pure typed JSON conversion, strict field sets, duplicate/mixed voice field rejection, canonical payload CRC32, version checks and load-time numeric/name bounds. Object keys sort lexicographically; arrays retain order; UTF-8 is used; no whitespace is emitted. No new dependency is required.
- `AtomicBankStore.kt`: dormant directory-injected component; IO dispatcher plus shared serial mutex per directory. Writes force the `.tmp`, copy a valid previous primary to a forced `.bak`, atomically rename, then read back. Unsupported atomic move fails closed. Verification failure quarantines the failed candidate, restores the validated backup and reports SAVE FAILED.

Load can return VALID, RECOVERED_BACKUP, MISSING, DAMAGED, NEWER_SCHEMA or reconstructed manifest. Corrupt bytes are retained in uniquely named quarantine files. Backup recovery does not recreate primary automatically. DAMAGED banks remain read-only even after opening a new store. Future schemas are preserved without rewriting or quarantine. Manifest reconstruction is in memory only; explicit manifest SAVE may replace a damaged manifest, never a future schema. Orphan valid banks are listed as Recovered bank. Bank creation precedes manifest publication. One-bank SAVE changes one primary bank plus its required backup; it does not rewrite other banks or manifest.

P12 implements the **storage primitive** for UNDO by writing the caller-retained old immutable snapshot through the same atomic protocol. SAVE arm/timeout/10-second UNDO UI/coordinator belongs to later phases and is not implemented.

Schema 1 was never released and no schema-1 payload format is provided by V2. No guessed migration is included. Unsupported older data fails validation and is preserved in quarantine. A future actual migration requires a pure version conversion fixture and retention of `*.v<n>.orig` as specified by the audit.

## Tests and limitations

P1–P12 are separately named JVM methods. Extra tests cover read-back corruption rollback, malformed shape/unsafe ID, concurrency across store instances, filename/ID mismatch and canonical CRC/order. Fault injection reproduces interrupted writes at forced-temp and post-rename stages; it does not simulate physical storage power loss.

Initial persistence run: 13/14 passed; P9 exposed reconstructed manifest remaining incorrectly locked as a damaged bank. Fixed the implementation, keeping P9 assertion; final persistence run: 17/17 passed. Required initial baseline: 78 JVM tests, 2253 native recorder checks, mixer integration guard and RIGHT OFF/style guard passed. Full final runner/CI evidence is authoritative for completion.

No runtime wiring, MIDI emission, applyFonts, voice recall, LIVE UI, Room change, phase 2–5, PR, merge or APK is introduced. No audio/device improvement is claimed. Directory metadata power-loss durability, Android filesystem atomic-rename behavior and device force-kill recovery require later device validation; unsupported filesystem atomic move is reported as failure.

Phase 2 is technically GO only after this phase's Actions is green; execution remains blocked pending explicit user approval. Known audit risks remain: applyFonts lock timing (D3), unverified E343 Data List/channel behavior (D11/D12), callback/coordinator races to address in Phase 2, and legacy manual SF2→MIDI voice mirroring (outside Phase 0–1).

Final local aggregate runner completed successfully (exit 0): 17 persistence JVM tests + 78 unchanged baseline JVM tests; native recorder 2253 checks, envelope metadata 30 checks, native regressions 8/70/63/32/238/211/4 checks; mixer/RIGHT OFF/live-registration isolation guards PASS. A harness-only cleanup error from generated Python cache was fixed by disabling bytecode generation; the complete runner was rerun successfully. All 451 pre-existing tracked files remain byte-identical to baseline.
