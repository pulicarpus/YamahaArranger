# Family-preserving multi-SF2 resolver — checkpoint 2026-09-30

## Status and continuation contract

Development branch: `feat/family-preserving-multisf2`.
Implementation parent: `512bcc6f23636c22261b718036f5ef389cabe80a` on `feat/colombo-primary-fallback`.
Latest implementation commit: `ba56c263bdad4c4f9fb00fa59d9ce2d3463656b9`. APK Build #754 / run `36749544403` succeeded on that exact commit. The attempted subsequent documentation-only commit did not publish; family branch HEAD remains this APK commit. Completed local notes are now preserved on the diagnostic branch.
Do not merge into main without a separate decision. One concrete wrong-family collision is the purpose of this candidate APK. Do not retune scoring through repeated speculative builds.

## Evidence inspected before editing

- Full earlier `CODEX_AUDIT.md`: historical audit of `feat/voice-resolver-v2-multisf2` at `82bc5ebceffb78ff401c7d2efc32e7921c13e8bb`. Preserve it as historical evidence, not the current implementation description.
- Latest branch history, native resolver, Kotlin loaders, `PROJECT_NOTES.md`, both APK workflows, all three source-mutating CI scripts.
- `docs/VOICE_RESOLVER_V2_CHECKPOINT_20260930.md`, `docs/VOICE_RESOLVER_V2_RESEARCH_20260930.md`, coverage/corpus notes, and `docs/COLOMBO_MULTISF2_AUDIT_2026-09-30.md` originally present only on main `18081d24346a87d30f091e089a350b5481715982`. The Colombo audit is copied verbatim onto this branch; no merge was needed.
- GitHub identifies normal workflow Build #753 / run `36739936315` with main `18081d...`, and the dedicated Colombo workflow Build #1 / run `36740388552` with `512bcc6...`; both succeeded. Workflow run numbers are per-workflow. The user's device evidence labels the tested APK Build #753 and proves Colombo loaded plus A.Guitar -> Wide Piano 2. Keep that report as device evidence; exact artifact correspondence cannot be independently established from the supplied material.
- Existing history/documents prove working source-preset preload, Yamaha destination-bank preservation, drum routing, and timeline/sustain fixes. They are regression boundaries rather than new problems to fix.

## Effective source / CI reconciliation

Before this change, `.github/workflows/build.yml` checked out HEAD and then ran:

1. `tools/apply_category_preserving_resolver_test.py` — changed native selection/cross-font scoring.
2. `tools/apply_fallback_load_order_fix.py` — changed Kotlin order to load core melody + drum before optional secondary.

The Colombo workflow additionally ran `tools/apply_colombo_fallback_priority.py`, changing secondary file selection to favor Colombo.
Thus the APK source differed from repository HEAD. These scripts were executed on a local text snapshot to inspect the effective implementation before replacement. They still allowed exact numeric returns before checking family, and maintained two independent resolver implementations. Some final fallbacks could choose Piano for non-Piano requests.

Both workflows now compile checked-out source directly. The three mutation scripts and their workflow steps are removed. Proven core-first loading and Colombo pool admission priority are incorporated into Kotlin source. Both workflows run `python3 tools/test_voice_resolver.py` before Android compilation. The normal APK workflow includes this development branch. Documentation-only `docs/**` / `PROJECT_NOTES.md` pushes are ignored, allowing a build-result checkpoint without another APK build. SDK header/library downloads remain part of CI, with the same pinned NDK r26d, CMake 3.22.1, JDK17, Gradle8.7, arm64-v8a and armeabi-v7a settings. The inherited Telegram step remains continue-on-error; artifact upload is the build evidence.

## Problem fixed

A.Guitar could select Wide Piano 2 because numeric bank/program coincidence bypassed instrument-family validation. Primary and secondary resolvers both had this unsafe early exit. Wrong-family penalties in later scoring could never protect that path.

Every candidate now passes the same hard role/family gate before receiving any score, including exact bank/program and Yamaha identities. No universal Piano fallback exists. If family cannot be decoded or no compatible preset exists, selection explicitly fails; new melodic NOTE_ON events are suppressed so BASSMIDI generic mappings/default Piano cannot undo the decision. NOTE_OFF and existing sustain/release paths continue unchanged. Mapping installation failure also suppresses new melodic notes rather than trusting old/generic mappings.

## Architecture and responsibilities

`Yamaha request (MSB/LSB/PC/name) -> role -> decode family -> collect primary + secondary preset inventories -> hard reject wrong role/unknown family/wrong family -> score compatible candidates -> select source -> channel FONTEX2 mapping with original destination bank -> selected-source NOWAIT preload -> BASSMIDI`.

- `app/src/main/cpp/voice_resolver.h`: one header-only C++17 pure policy, shared by Android native source and host tests. Family classifier, request identity decoder, compatibility gate, scoring, deterministic selection.
- `app/src/main/cpp/bassmidi_player.{h,cpp}`: owns handles, per-source raw phdr preset caches and normalization maps. Primary Yamaha plus up to two optional melody sources replaces a single secondary handle. One `findMelodicPreset` replaces the two duplicated resolver functions. Source index 0=primary, 1/2=optional sources; logs always include SF2 filename so index is not mistaken for a fixed source identity.
- `AudioEngineManager.kt`: pauses the audio stream under the existing operation mutex, loads essential primary melody + dedicated drum first, then optional Colombo and optional Tyros using the existing JNI fallback-load API. Optional failure does not discard the core pair.
- `MainViewModel.kt`: admits Colombo preferentially and distinct Tyros if present, supports Yamaha filename spellings Melody/Melodi, retains the optional pool when selecting/reloading a managed SF2. Existing manual voice selection forwards the already-known preset name, avoiding loss of identity at the native boundary. No screen layout, keyboard gesture, scheduler or MIDI-output behavior changed.
- `tests/voice_resolver_test.cpp`: actual shared pure policy, Love Song documented identities plus adversarial collisions and cross-family matrix.
- `tests/native_resolver_test.cpp` and `tests/mocks/`: compile the actual `bassmidi_player.cpp` against host API recorders; parse tiny synthetic RIFF/phdr fixtures, verify actual FONTEX2/preload requests and NOTE_ON suppression. Mocks do not implement audio or assert real SDK ABI behavior.
- `tools/test_voice_resolver.py`: runs both suites; temporary fixtures/binaries are removed automatically.
- `.github/workflows/build.yml` and `build-colombo.yml`: compile source without rewriting resolver/load policy; run host regressions.
- Deleted: three `tools/apply_*` CI mutation scripts named above.
- Documentation changed/added: this checkpoint, `PROJECT_NOTES.md`, verbatim historical `CODEX_AUDIT.md`, and the verbatim Colombo audit from main.

## Family and identity decisions

Explicit instrument name is strongest family evidence: Guitar, Bass, Strings, Piano, Organ, Brass/Horn, Sax, Woodwind are distinct. Sax never falls back to Clarinet. Flute/Clarinet/Oboe share Woodwind, with textual match deciding among compatible candidates. Guitar is checked before Strings; Bassoon/Bass Clarinet and Bass Trombone are classified by their actual wind/brass identity, not the word Bass. Contrabass is Strings. GM zero-based ranges are corrected (8-15 Chromatic, 16-20 Organ, 21-22 Accordion, etc.).

An unnamed standard bank0 request may decode through GM. Unknown Yamaha variation requests may decode only from a consistent known-family Yamaha exact identity, otherwise same-MSB identity; conflicting identities fail closed. A foreign source's matching PC/bank never supplies request identity. Candidate GM inference is allowed only for recognized GM sources at basic bank0, not arbitrary foreign variation banks. Unknown candidate families are rejected.

MSB104 explicitly disables numeric Yamaha exact/MSB identity scoring and unnamed numeric identity decoding. It requires name/family/variation evidence. This implementation does not invent a complete Yamaha MegaVoice mapping table.

Names/source provenance are decoded using conservative filename/name rules, not sample waveform inspection. Renamed SF2 files or unrecognized abbreviations can reduce coverage; logs expose UNKNOWN. Source filenames must retain Yamaha/Tyros/Colombo identity until a separately reviewed explicit source metadata mechanism exists.

## Scoring after compatibility (initial policy, not corpus-tuned)

- Same family: base 1000.
- Compatible Yamaha exact raw bank+PC: +100000; compatible Yamaha MSB+PC: +90000. Neither applies to MSB104.
- Normalized exact name: +8000; nonempty partial text: +1200.
- Acoustic Guitar evidence on both request/candidate (acoustic/steel/nylon/spanish/A.Guitar): +700.
- Same-PC: +40 supporting signal only, after the family gate.
- Yamaha source: +20; primary source: +5. These cannot force source load order to defeat semantic matching.
- Equal scores tie by SF2 filename, bank, program, name.

All loaded melody sources compete globally. Colombo and Tyros can beat primary when compatible semantics score better. No wrong-family score is calculated; rejected candidates have score -1. Variation handling currently consists of textual identity plus acoustic-guitar tokens; precise articulations and other Yamaha variation mappings remain future evidence-driven work. Weights are a conservative first candidate policy, not validated against the complete 578-request corpus.

Layer suffixes Strings1/Strings2 and trailing CASM numbers normalize away for family/text matching. This preserves Strings family but does not promise distinct samples for both layers.

## Inventory, mapping and lifecycle

Secondary loading maintains independent preset caches and bank maps. Primary reload clears secondary handles, invalidates stale selections, and re-resolves initialized melodic channels. Adding a source re-scores initialized melodic requests and restores their bank/program plus source preload. Failed optional mapping restores the previous pool/channel state. Requested voice names are retained for re-resolution; an identity-only repeated call may retain its known name only when bank and PC are unchanged. A changed identity must decode independently.

Selected mappings precede generic FONTEX2 mappings. Selected raw SF2 bank is translated via that source's normalization table; missing entries fail instead of installing an unverified mapping. Destination remains Yamaha MSB/LSB/PC. Preload uses selected source handle/bank/program with `BASS_MIDI_FONTLOAD_NOWAIT`. Secondary preset inventory is exposed as `MELODY_SECONDARY_1/2` to native preset enumeration. Existing screen browsers filtering role=MELODY keep their previous browsing behavior; expanding browser UI was not part of this task.

892 Colombo presets is the previously inspected total, not a claim that all are melodic. Bank127/128 and recognized drum names never enter melodic competition. `VOICE POOL` reports the actual loaded melodic cache size, which may be less than 892; preset enumeration includes the full attached font's records.

## Regression boundary reviewed

No changes to CASM parser/model/range/source metadata, ArrangerBrain, StyleSequencer, directional Fill, Main A-D / Intro / Ending master timeline, note ownership/sustain ledger, external MIDI routing, MainScreen or SxMainScreen.

Native function bodies verified byte-identical to parent for 13 functions: ensureEngine (including BASS_MIDI_NOTEOFF1), normalizeMelodySf2, rebuildDrumPresetCache, findDrumPreset, rebuildMelodyPresetCache, noteOff, allNotesOff, setKeyboardSustain, setKeyboardReleaseTime, setChannelMixer, setChannelExpression, render, send. Dedicated drum mapping source banks127/128, destination128, channels8/9 remains unchanged. FONTEX2 is preserved. Selected-source preload and NOWAIT remain. Section preset/mixer caches and Fill->Main logic are untouched. The deliberate new NOTE_ON rejection affects only initialized melodic channels with unresolved/failed selected mappings.

## Validation achieved before APK publication

`python3 tools/test_voice_resolver.py`: 210 policy checks + 19 native routing checks pass under a host C++17 compiler. Policy build uses -Wall -Wextra -Werror. Actual native source also compiles in host integration tests with -Wall -Wextra. `git diff --check` passes. Reviewed full diff; unrelated native/Kotlin sources are unchanged.

Tests include Love Song Bass(8:4/17), Piano(104:21/0), A.Guitar(8:16/1), Strings1/2(8:5/49), foreign and Yamaha wrong-family exact matches, no-compatible-candidate failure, Sax/Woodwind isolation, drum exclusion, unknown MSB104, known Yamaha identity decoding, conflicting identity rejection, global semantic winner, pool-order independence, failed optional font load, selected-source preload, retained dedicated drum routing, reload without stale source handles, and failed mapping NOTE_ON suppression.

Host Love Song inventory is synthetic, using names/identities documented in the earlier checkpoint; actual raw SF2/style/log files were not available in this workspace. Host choices (e.g. Piano -> a compatible Colombo piano, Strings1/2 -> a compatible Colombo Strings) prove the gate/pool behavior, not the best timbre or an Android trace. No real audio, sample memory demand, device realtime latency, or complete 569-style coverage has been tested here.

## AllLog decision trail

- `VOICE REQUEST`: bank/MSB/LSB/PC/original and decoded name, role/family, decode basis.
- `VOICE CANDIDATE REJECT`: rejected same-PC collisions, filename/source bank/PC/name/family, reason (`wrong_family`, `wrong_role`, `unknown_*`), score=-1.
- `VOICE POOL`: per-source actual cached melodic count, accepted/rejected totals, hard gate rule. Other rejects are aggregated to avoid thousands of JNI log calls per Program Change.
- `VOICE CANDIDATE ACCEPT`: best candidate in each source with family, score and reasons.
- `VOICE FINAL SELECT` / `VOICE FINAL REJECT`: global outcome with selected filename/preset/family/reasons or explicit suppression.
- Existing `VOICE MAP`, `SET PRESET`, `BASSMIDI preload`: actual mapped source and unchanged Yamaha destination plus preload evidence.

Logs are intentionally per resolution, not per note. These prove why the chosen preset beat each source's best compatible alternative. FULL per-candidate scoring for all hundreds of presets is not emitted during realtime playback.

## Proven vs hypotheses / Android test checklist

Proven in host tests: hard wrong-family rejection before numeric matches, global competition, explicit fail-closed notes, source-specific FONTEX2 and preload parameters, retained mock drum routing. Prior user report proves Colombo can load and exposes the exact wrong-family defect. Historical fixes remain intact by source comparison.

Still hypotheses: best actual timbre for each Yamaha variation, loaded cache/name classification coverage on the user's exact SF2 files, optional Tyros memory headroom, Android audible result, realtime/log overhead, and whether distinct Strings1/2 variations exist and rank appropriately. Unnamed changed MSB104 Program Changes can be silent by design until identity is known; do not work around this by restoring numeric-only/Piano fallback. Collect the failing request and add evidenced semantic metadata in a later scoped change.

User Android checks, in order:

1. Install the candidate APK identified in final build evidence; keep the exact previously used Yamaha Melody, ColomboGMGS2_BM, optional Tyros4 and dedicated Yamaha Drum filenames in the managed folder.
2. Confirm core melody+drum load then optional source attachment. In Love Song resolution, confirm distinct `VOICE POOL` filenames and nonzero Colombo/Tyros candidate counts when attached.
3. Love Song Main D: mute/unmute Bass/Piano/A.Guitar/Strings1/Strings2 individually. Each FINAL candidate family must match the decoded request. In particular A.Guitar must never select Wide Piano 2 or any Piano; numeric collisions should show wrong_family rejection.
4. Check `VOICE MAP` and preload identify the same selected SF2/source program while Yamaha destination bank/LSB stays intact. Export AllLog plus Inspector. Verify at least one request can choose a compatible Colombo or Tyros semantic winner when primary is weaker; do not require Colombo to win every voice.
5. Check Rhythm1/2 stay on dedicated Yamaha Drum, and Main A-D, directional Fill, Intro/Ending remain audible with the same transition timing.
6. Check RIGHT1/2/3 and LEFT voices, sustain and release, rapid repeated String notes, and Fill->Main preset/mixer behavior. These are regression checks only; do not modify their implementation to tune this resolver.
7. Select/reload a managed SF2 and verify optional pool remains attached and no stale source mapping appears. Check low-memory behavior with Tyros present and absent.
8. Test a few MSB104 styles and record any UNKNOWN/final rejection or unexpected variation. Unknown must yield an explicit rejection, never silent automatic Piano substitution.

## Next work after device evidence

First review Love Song AllLog/Inspector against this APK's exact implementation SHA. Fix a reproducible classification or selection error in one scoped change if observed. Then generate/review the full 578-request candidate matrix using actual phdr names, banks and style metadata; the corpus itself was not available here. Only after family and routing behavior is verified should variation/articulation fidelity and distinct Strings layers be refined. Keep CASM/scheduler/sustain/drum outside that work unless new direct evidence implicates them.

## Final build evidence

- Implementation commit: `ba56c263bdad4c4f9fb00fa59d9ce2d3463656b9`; parent `512bcc6f23636c22261b718036f5ef389cabe80a`.
- Branch: `feat/family-preserving-multisf2`.
- Workflow: `.github/workflows/build.yml`, Build #754, run `36749544403`, job `110004325024`, completed SUCCESS on 2026-09-30.
- [Workflow run](https://github.com/pulicarpus/YamahaArranger/actions/runs/36749544403).
- [Download app-debug artifact](https://github.com/pulicarpus/YamahaArranger/actions/runs/36749544403/artifacts/11114086868). Artifact ID `11114086868`, archive size 12,436,339 bytes; contains `app-debug.apk`. Artifact archive SHA256: `5ecf5edc6f5f898d1165bafe5cf51a63a57b0ec7c19617a0b883d7236365c15e` (archive digest, not a separately measured APK-file digest). GitHub currently reports expiry 2026-12-29.
- CI independently printed PASS:210 policy / PASS:19 native mock checks; real Android SDK/native build and Kotlin packaging passed. Gradle printed BUILD SUCCESSFUL in 1m24s. Upload APK and inherited Telegram delivery steps succeeded.
- First candidate build succeeded; no failed build/retry or speculative follow-up code changes.
- Compile warnings were only unused private dcLastIn/Out fields in unchanged `audio_engine.h`; no resolver compilation warning was reported. Do not modify that unrelated area for this task.
- Verified 21 expected remote changed paths and 108 unrelated existing file blobs unchanged, including all 18 native `.so` libraries and CMake configuration. Remote resolver/header/workflow contents exactly match the locally reviewed/tested versions.
- Final checkpoint commit has this implementation commit as parent and changes only this document plus PROJECT_NOTES.md. Its SHA is discoverable from the branch HEAD/history; it does not claim a new APK or change compiled source. Documentation-only paths are ignored by the push workflow.
- Local `/workspace/YamahaArranger` is a text inspection snapshot initialized for diff review, not an authenticated remote clone; its local baseline HEAD is NOT the published implementation SHA. GitHub is authoritative for branch/commit/build identity. Shell clone/auth access was unavailable; publication used GitHub Git-data APIs preserving the remote base tree and binary blobs. A future session should obtain the branch from GitHub rather than treat the local snapshot history as remote history.
- Audio has not been heard/tested from this APK. Next required evidence is the Android checklist above and user AllLog/Inspector for Build #754.

## Follow-up user evidence — 2026-10-01

User tested Android and reports the family gate works and A.Guitar -> Wide Piano 2 is fixed. Remaining weak Bass/Strings/snare and dominant Piano require event/mapping/controller/lifecycle diagnostics. See AUDIO_PATH_DIAGNOSTIC_CHECKPOINT_20261001.md. Do not rollback/tune this resolver based only on subjective orchestration. The raw newest AllLog was not available to independently establish audio root cause.
