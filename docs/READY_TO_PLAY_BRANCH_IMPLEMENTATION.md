# READY TO PLAY — feature branch implementation

## Baseline and scope

Authorized by `CODEX_READY_TO_PLAY_IMPLEMENTASI_BRANCH_CI.md`; work is on
`feat/ready-to-play-gate`, never the baseline branch. Baseline/rollback:
`47972ab22d56f6b82ff413d400cdcaafd126a3a6`. Its production source is identical
to APK #814 (`a107bc493f8b98ef5b272fd1d6e830073b96c9c1`).

Six existing production files change: MainViewModel, MainScreen, SxMainScreen,
ContentResolverProvider, AudioEngineManager, and ArrangerBrain. The only new
production file is lifecycle/ReadyToPlayGate.kt. No native, CASM, sequencer,
F03/F04/F12 diagnostic, MIDI routing, mixer, SF2 voice mapping, logger,
historical guard, fixture, golden, or original workflow changes.

## Contract

* NOT_READY/LOADING/ERROR reject initial START. READY requires acknowledged
  melody and drum loads (one shared SF2 can fill both), the current parsed
  style identity and selected section, actual native output-start success,
  and no pending SF2/style request. Optional fonts and keyboard layers are
  not prerequisites. Native start acceptance is not callback-continuity proof.
* Request generations reject obsolete cache/load/parse completions. Physical
  commits and admission share a coroutine mutex; admission uses tryLock and
  never queues START. SF2 requests serialize across ViewModel instances.
  Main/Fill/chord continuation and STOP keep their old musical bodies.
* Both UI START buttons are disabled with a readiness reason. Backend admission
  also checks the actual style object, and rechecks playing state inside the
  admission transaction. UI state alone cannot authorize START.
* Output shutdown invalidates readiness immediately, then closes output on a
  worker. A load cannot resume an invalidated output epoch. New process state
  starts NOT_READY. A retained manager may keep fonts because native output
  stop closes the stream without unloading fonts; restart must acknowledge
  output again. No saved boolean restores READY.
* Cancellation/failure cannot report completed READY. Partial/exceptional
  native load conservatively invalidates required bindings; no rollback of
  already unloaded fonts is promised. Cache files keep original basenames in
  request-specific directories, preventing one request overwriting another.

## Verified tests

* Readiness model: **24 PASS**.
* Actual AudioEngineManager and ArrangerBrain with mocked native/platform
  boundaries: **17 PASS** (10 new engine tests, 3 new admission/continuation
  tests, 4 existing session regression tests). Original session assertions
  remain unchanged; one test supplies the newly required readiness setup.
* New build-input audit: **9 PASS**, using the six genuine vendor SDK files;
  wrong SHA, changed tracked files, unknown/ignored source, bad SDK and
  symlink/missing input are rejected. SDK hashes match the unchanged historical
  vendor manifest. Original SDK guard on the baseline: **10 PASS**.
* Original F04 source guard on detached baseline: **5 PASS**; full F04
  differential: **83 pre-F04 / 90 F04 tests PASS**, with only the established
  F04 delta. Original F12 source/timing guard: **PASS**; F12 JVM: **5 PASS**.
* Baseline P0: **24 PASS**; P1/S7/S8 existing Linux native probes execute
  successfully. Their UNKNOWN/BLOCKED/device limitations remain unchanged.
* Candidate F04/F12 historical source guards: **EXPECTED HISTORICAL HASH
  DRIFT — NOT PASS** (first differing production file: ArrangerBrain.kt).
* Separate readiness differential: protected production and all old fixtures
  and goldens byte-identical; unchanged Main/Fill/chord bodies and logger;
  baseline/candidate real Kotlin facade calls have identical native order and
  values in the scripted admitted scenario. This is not Android PCM evidence.
* Real Linux native probe: #811/#813 off/on and candidate off/on PCM and MIDI
  byte-identical. PCM SHA256:
  `225cad88a11de258795f7e3cc3d62967bdb5aa404cbf76e5fe6a2846b52d033d`.
  Native source is unchanged; this is not Android/Yamaha audio certification.
* Corpus 503 rerun: **BLOCKED** (original ZIP unavailable). Redmi Pad audio,
  background/recreation behavior, layout and PSR-E343: **UNRUN**.

## Auditable build

The new `ready-to-play.yml` checks out an explicit candidate SHA and a separate
physical 47972ab baseline. It runs original baseline guards/full JVM suite,
records candidate historical drift, runs readiness and candidate regressions,
and builds both ABIs from that same audited candidate directory. Four exact
historical hash-bound test methods run only on the baseline; candidate test
selection is explicit in readiness-candidate-tests.gradle, with no assertion
or historical test alteration. All other candidate test methods run normally.

Before/after records include SHA, clean tracked input, file hashes/modes and
all six exact SDK identities. Unknown untracked build files and ignored source
files are rejected. Diff, XML and logs are archived; APK Stage 3 symbols are
checked for both ABIs. These observations do not make the filesystem immutable
or protect against privileged transient writes between observations.

Build/CI results will be appended after execution. API access currently returns
Forbidden; GitHub web access works. No run, APK link or SUCCESS is inferred from
workflow creation.

## Remaining risks and device check

Request-specific cache directories are deliberately retained: deleting files
that a synth may still use would be unsafe. Repeated large SF2 replacements can
increase disk use; failed copy remains NOT_READY/ERROR. Native Boolean results
cannot certify audible output or sustained callback continuity. UI and full
Android integration require the actual APK/device gate below, not host mocks.

On Redmi Pad: cold start and attempt START during SF2/style loading; verify no
later autostart. Load the usual required pair/style, explicitly press START,
then test STOP, Main A–D/Fill and C–F–G with the same fonts/style as #814. Try
failed/cancelled loading, quick successive selections, background/recreation
and process restart. Export the existing compact capture if freeze/burst or
chord gaps remain. This feature does not claim to fix those audio symptoms.
Rollback: do not merge the feature, or revert its feature commits; baseline
47972ab and its APK #814 remain untouched. No Stage 3/P2 or musical patch follows
without separate authorization.
