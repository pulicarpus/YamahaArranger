# LIVE CONTROL — Phase 3 only

Baseline: `ea1997bd8e5c9f62566ad7ad8de0fb8a79ab20f7`, remote branch `experiment/live-control-reg`; Actions #38454223632 SUCCESS verified from API/logs (130 JVM, 2253 native recorder, 2594 real PCM). Working tree was clean before production changes; six pre-existing untracked Python caches were archived under `/tmp/yamaha-phase3-preflight-cache-ea1997b` without deletion.

Claude V2 IMPLEMENTATION_PLAN, CODEX_HANDOFF, TEST_PLAN and LIVE_CONTROL_UI_SPEC define Phase 3 as LIVE page/recall/bank navigation/indicators/mode badge/MIDI status. Phase 4 remains SAVE, overwrite, UNDO, FREEZE editor, external voice selection and active persistence. No invented sample presets or banks are installed.

## Implementation

- HOME/ARRANGER remains the initial page; explicit LIVE tab opens LIVE. MIXER saves its origin, so returning from MIXER restores LIVE or ARRANGER correctly. Back from LIVE goes HOME. Navigation callbacks do not call playback functions. Existing arranger controls/voice/chord pads and mixer bindings remain intact; only the mixer navigation return label becomes LIVE when appropriate.
- LIVE has Main A–D, Intro/Ending 1–3, Fill, START/STOP guarded by readiness, ACMP/AUTO FILL, RIGHT1/2/3/LEFT, tempo hold-repeat at 8Hz (accelerating after 1s), transpose and RELEASE toggle through existing APIs. SYNC and TAP are disabled N/A. No Sustain button, SAVE, FREEZE editor, external voice editor or disk writer is added.
- Eight REG slots show names/mode badges and selected/missing status. Empty slots are disabled. Bank previous/next is bounded and never recalls or cancels an in-flight recall. FREEZE flags are shown read-only.
- ViewModel adds only LIVE state and explicit wiring to the unmodified Phase 2 coordinator. It keeps newest request tokens so old completions cannot replace newer UI selection. Missing/ambiguous SF2 and per-layer failures are shown; BOTH can report SF2 failure while external succeeds. A pure state listener retains Main context for Fill; explicit LIVE Main selection records its letter before delegating to the existing section API.
- No bank-loading/SAVE UI or active disk persistence exists yet. Library wiring accepts explicitly supplied in-memory banks; default is NO BANK LOADED with eight empty slots. This means users cannot create/populate banks from this Phase 3 UI; that is Phase 4. No debug fixture is installed into production.
- Landscape geometry covers 960/1024/1280/1333 × 600/700/800dp. Touch targets are at least 64dp, Main at least 96dp, eight REG fit one row without overlap. Smaller windows show a return-to-ARRANGER fallback instead of shrinking controls. LIVE hides system bars and keeps the display awake, restoring prior window state on exit. ChordModeSelector overlay is visible only on HOME.

## Isolation

New Phase 3 guard pins the Phase 2 commit. Every pre-existing file remains identical except four measured exceptions: marked additive LIVE ViewModel block; exact navigation-only SxMainScreen replacements; three overlay-variable substitutions in MainActivity; one approved CI runner-command replacement. Removing the LIVE ViewModel block reproduces the entire original Phase 2 file. Coordinator, planner, codec, persistence, all engine/router/native/style files and every historical guard remain unchanged. Only LiveControlScreen/LiveControlLayout are permitted new production files.

Historical Phase 0–2 tests and guards run on their pinned snapshots, including the original PCM gate. Current-source JVM tests also rerun the same baseline and Phase 1/2 contracts with the new ViewModel LIVE wiring compiled into the host harness. Two JVMs preserve historical style-fixture behavior without changing or dropping assertions.

## Validation

Final current JVM: 78 unchanged baseline + 65 registration/UI tests = 143 distinct tests (130 previous + 13 Phase 3). U1/U2/U5 have geometry/mode/navigation JVM tests; U3/U4/U5 additionally have exact source action/disabled/navigation guards. LIVE wiring tests cover no side effects on install/bank change/empty slots, output-OFF/no future replay, rapid 1→2→3 status and external identity, missing SF2, old completion isolation, Main/Fill binding, and native SF2 failure with independent external success. Existing held-key/RIGHToff/transpose/RELEASE/MIDI recorder cases remain unchanged and run.

Local historical runner completed exit 0: all Phase 0–2 JVM/native regressions and guards passed, including 2253 native recorder checks. Real PCM is mandatory in Actions (local SDK download was already blocked HTTP 403 in prior phase). The new runner also requires full `:app:compileDebugKotlin` in Actions, using Gradle 8.7; this compiles real Compose/Activity/ViewModel code without assembling an APK. Its Gradle download is checked against the published distribution SHA256. Local Android SDK compilation is explicitly UNVERIFIED.

Host ViewModel tests compile the added block verbatim with real Brain/AudioEngineManager/MIDI manager/router and native/Android fixtures. Geometry and source checks do not render Compose or prove physical touch usability.

## Remaining gates / next phase

D1/D2/D5/D11 on Redmi Pad + E343 are UNVERIFIED. Android font scale, fullscreen restoration, hold gestures, overlays, visible focus and actual navigation/audio must be tested on a device. There is no claim that INTERNAL/BOTH native lock latency or E343 device timing is fixed; D3 remains open. Phase 2 timeout/drain limitations remain unchanged. Legacy manual SF2-to-MIDI mirroring remains unchanged and is not used by REG.

No APK, PR, merge, or Phase 4 is performed. Technical completion requires green CI; GO to Phase 4 additionally requires the V2 Phase 3 device gates and explicit user approval, so Phase 4 is NO-GO while those are missing.
