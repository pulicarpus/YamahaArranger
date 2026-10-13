# Registration recall — Phase 2 only

Checkpoint: `48f4c0353bb94d7340cbb61566766a3c2feda2d6` on `experiment/live-control-reg`, fetched and verified before editing. Claude V2 is authoritative. No LIVE UI, APK, PR or merge.

## Isolation contract

Historical guards, runners, tests and all protected engine/router/native/style/persistence files remain unchanged. New guard compares every Phase 1 file. Exceptions are narrowly measured:

1. MainViewModel gains one marked additive block and one `async` import. Stripping those must reproduce the **entire original file byte-for-byte**, preserving setKeyboardVoice, connect/reconnect, RELEASE, RIGHT OFF, mixer and existing chord pads.
2. User separately approved exactly one workflow command replacement: `test_live_reg.py --baseline --real --fetch-dependencies` → `test_live_reg_phase2.py --history --real --fetch-dependencies`. Guard checks the exact resulting workflow. No other workflow line changes.
3. Only RecallPlanner and RegistrationCoordinator are added under production sources. No existing production component besides the additive ViewModel block changes.

Historical Phase 0–1 tests run unchanged in a detached worktree pinned to Phase 1; its own historical runner also uses the original ea13199 baseline snapshot. Current-source tests independently exercise the production additions. Existing style fixtures leave daemon playback workers, so baseline and new tests run in separate JVMs without editing or dropping any assertion.

## Runtime additions (explicit invocation only)

RecallPlanner: pure snapshots; OFF → SF2 → explicit external → volume → RELEASE → ON → transpose → tempo. Four parts, raw native SF2 banks up to 65535, exact file/bank/program/name resolution, ambiguous/missing rejection, independent external MSB/LSB/program, six FREEZE groups. External identity is resent on explicit recall, never inferred from SF2. MIDI OUT OFF yields a skipped issue with no replay queue.

RegistrationCoordinator: one Channel(CONFLATED) consumer, monotonic generations, superseded completion for dropped requests, 150ms same-key debounce, busy state. Each in-flight SF2 operation drains before another snapshot/transaction; no old-generation steps execute after the next consumer transaction starts. Manual request IDs prevent stale SF2 publication or retry. A timeout result does not cancel JNI: the coordinator stays busy until physical completion, so the next diff sees actual synth state.

MainViewModel: new applyKeyboardVoice returns APPLIED/SUPERSEDED/FAILED/TIMEOUT without waiting for the legacy callback; default timeout 1500ms. SF2-to-MIDI mirroring is explicitly rejected by this new API. It uses existing selectKeyboardPreset; no new native calls. A physically completed preset is published before the next serial snapshot unless superseded by a manual request. External selection uses only ExternalVoice, sends explicitly when output is active and stores no pending reconnect/enable command. Snapshot reads LEFT enabled from Brain. Volume, ON/OFF, transpose and RELEASE use existing state/control paths; RELEASE remains handled by its original distinct effective-value collector. Coordinator is lazy and never automatically recalls on construction/connection.

The busy signal is intended for the Phase 4 SAVE gate; no SAVE UI or full SAVE state machine is implemented in Phase 2.

## Validation evidence

Local final JVM: 78 unchanged baseline + 17 persistence + 35 Phase 2 = 130 passed, zero failures. Tests include R1–R11, per-group FREEZE, rapid 50-request and 10-request scenarios, manual supersession, real adapter A→B→original race, default 1500ms timeout with late drain, held-key ownership, part-scoped OFF, unchanged RELEASE bypass, missing preset, connect/reconnect/enable with no automatic Bank/PC.

ViewModel addition is extracted verbatim into a host harness together with its existing part/volume methods and RELEASE collector. It runs with the **real** AudioEngineManager, ArrangerBrain, MidiInputManager and MidiMixerOutput; Android lifecycle/native bridge are host fixtures. This is not a complete Android ViewModel lifecycle/device build.

Byte recorder: RIGHT1 ExternalVoice(0,112,5) sends `B0 00 00`, `B0 20 70`, `C0 05`, then cached `B0 0B 5D` (CC11=93); native selection/enumeration count is zero in EXTERNAL. Held key-up retains original pitch/channel, RIGHT2 OFF sends NOTE_OFF + CC120 only on ch2, transpose retains original key-up ownership. No global panic or style-channel command is introduced by recall.

A real StyleSequencer fixture plays Bass on ch11 during EXTERNAL recall and emits its scheduled `8A 24 00` after recall; natural completion is observed. A Rhythm fixture initially lacked the expected owned NOTE_OFF in the existing baseline; the test was corrected to use a Bass fixture with ownership, keeping the expected note-off and leaving style unchanged.

Full historical runner completed exit 0: 78 baseline JVM, 17 Phase 1 JVM, 2253 native recorder checks, 30 envelope metadata checks, all native regressions (8/70/63/32/238/211/4), all original baseline/Phase 1 guards. Final current-source guard protects 457 unchanged tracked files plus exact legacy ViewModel and exact single-command workflow exception.

Local real BASSMIDI download returned HTTP 403; no local PCM PASS is claimed. Actions must run --real (strict original 2594-check PCM gate) before completion. CI logs are the source of truth.

## Open risks / Phase 3 gate

- INTERNAL/BOTH still execute up to one native preset selection per changed part; there is no application retry or batch API. Existing native failure handling can perform additional font-table rebuilds; it is not modified.
- JNI uses shared native locks. EXTERNAL adds no SF2 operation but may wait for a prior in-flight native operation; existing unrelated manual SF2 operations can still delay style/keyboard MIDI. D3 timing and E343 audio latency remain UNVERIFIED.
- A permanently hung JNI call cannot safely be forcibly canceled here. API returns TIMEOUT, but coordinator must remain busy awaiting drain rather than start overlapping work. No hard real-time or device no-hang guarantee is claimed.
- Legacy manual setKeyboardVoice still mirrors MIDI-sized SF2 addresses and has its historical superseded-callback behavior; new recall does not use that callback or mirroring. This is deliberately unchanged.
- No full Android lifecycle/UI/device validation is performed in this phase. D1–D14 and device Data List/channel behavior remain open.

GO to Phase 3 only if current CI passes; implementation must wait for user approval. No UI/APK/merge is authorized by this Phase 2 implementation.
