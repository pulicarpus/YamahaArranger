# Build #810 SDK/source-integrity guard recovery

## Scope and baseline

Recovery baseline: `57a48a5963d3acba463bb215561a7c8192ffaada`, clean working tree and matching remote branch at start. F04 production commit: `e563d55bcf2112bdaf3928469206a8b783e66c6c`; Build #810 tested `61b2042a8aacc57bd1a07c548dd79296d202eced`. Branch: `fix/mix-percussion-fidelity-784`.

This patch changes harness only. All 125 tracked production paths, including StyleSequencer and native audio, remain byte-identical to the recovery baseline. Workflow, historical fixtures/goldens, and Stage 3 quarantine remain unchanged. The F04 reversible source profile keeps all 380 original baseline hashes and the exact production edit unchanged; only the approved harness edit records are refreshed.

## Failure evidence and limitation

[Build #810](https://github.com/pulicarpus/YamahaArranger/actions/runs/37810747435): SDK download step SUCCESS; Build debug APK FAILURE; both-ABI Stage 3 verification and APK upload SKIPPED. No APK was produced by #810. Original step log retried via checks endpoint: HTTP 404. GitHub jobs/logs API attempt: CONNECT rejected HTTP 403 by the environment proxy. The first CI error remains UNKNOWN; the exact CI cause is not fully verified from logs.

Local reproduction of the workflow SDK copy proves a sufficient blocker: the source guard rejects these six legitimate untracked files as production drift. This is local evidence, not a quotation of the unavailable first CI error.

## Explicit SDK contract

`tests/fixtures/f04_generated_sdk_identity.json` pins exactly two headers and four Android libraries:

- `app/src/main/cpp/basssdk/bass.h`
- `app/src/main/cpp/basssdk/bassmidi.h`
- `app/src/main/jniLibs/arm64-v8a/libbass.so`
- `app/src/main/jniLibs/arm64-v8a/libbassmidi.so`
- `app/src/main/jniLibs/armeabi-v7a/libbass.so`
- `app/src/main/jniLibs/armeabi-v7a/libbassmidi.so`

Every file must match its exact size and SHA-256; the manifest itself is SHA-pinned in the validator. The files were extracted from fresh TLS-verified downloads of the same official un4seen URLs used by the existing workflow. Both archives are byte-identical to the earlier Android SDK evidence:

| Archive | SHA-256 |
| --- | --- |
| bass24-android.zip | 236c9017728a910dfe74e79d36d98ad6784f3558c21e8812cfcbafe9bd0fef41 |
| bassmidi24-android.zip | 6ca3bd8bd0bd9663ec60995821ef0728b32173628f1ae976ffbe40f7b61f6f12 |

The existing portable and historical audit guard share this validator. A host checkout with no generated SDK is valid. A generated SDK must contain all six files; extra paths, partial sets, wrong bytes/ABI, missing files, or symlinks fail closed. The tracked path-set, normalized Git tree, physical source hashes and historical source checks still run. No directory-prefix exclusion or .gitignore change is used. Vendor SDK bytes are not committed. Future vendor drift intentionally fails and requires separate evidence/review.

## Tests

`tools/test_generated_bass_sdk_guard.py` uses the actual vendor file bytes, with temporary copies for mutation tests. It has 10 positive/negative tests, including all six individual corruptions, an ABI swap, extra header/ABI/Java paths, partial/missing SDK, symlink, manifest mutation, the 125-source identity, a tracked-source mutation, and an unknown file alongside a valid SDK. The existing F04 differential gate invokes this suite whenever SDK files are present; Android workflow step 23 supplies them before the JVM gate. Host runs without SDK retain their original behavior.

Local results and the single recovery CI run are recorded below after completion. Tests demonstrate harness/source/dispatch integrity, not improved device sound. Original 503-style corpus rerun remains BLOCKED; Android/Yamaha listening remains UNRUN.

## Stop condition

Run one existing Android workflow after all local gates pass. On FAILURE report the verified step/error and stop; no unrelated patch. On SUCCESS provide the uploaded APK artifact and await the user's listening test. No F02/F03/F12 work or production behavior changes are authorized here.

## Local completed gates

- SDK guard: 10 tests PASS using the six actual workflow-equivalent files. Both altered size and same-size SHA mutations rejected for every SDK file.
- Source profile: 5 tests PASS; portable S4/source evidence: 10 tests PASS; S5 ownership guards: 7 tests PASS, with SDK present.
- Full existing voice/native mock/regression runner: PASS, including Stage 3 exclusion, S1/S2/S3/S4/S5 guards and F04 source controls.
- F04 differential: PASS both without SDK and with SDK; baseline 83 tests, candidate 90 tests; 20 main captures and 1,046 reduced S5 runs. Deltas remain exactly 600 internal + 600 MIDI OUT NOTE_ON removals in the main replays, and 8 + 8 in the reduced replay; every retained call/OFF/owner record unchanged.
- S6/S7/S8/P0 host integration: 13 tests PASS; isolated P0: 24 tests PASS. UNKNOWN/BLOCKED classifications preserved.
- S1 fail-closed harness: 7 tests PASS.
- Existing real Linux percussion, measured balance (240 cells), headroom parity and P1 native controls: PASS in their existing limited Linux/synthetic scope. These are not Android/Yamaha certification.
- Frozen snapshot verification: all 125 production files, workflows, old fixtures and goldens byte-identical to recovery baseline; baseline profile hashes and exact F04 production edit unchanged.

A genuine depth-1 clone with all six SDK files also passed the SDK suite (10 tests) and the complete candidate S1–S5/F04 host pipeline (90 tests), without historical baseline Git objects.

CI: the single recovery run **Build #811 SUCCESS**, tested harness commit `a54a894a61034003facb080876970e67c407046a`. Local Android APK build is UNRUN because the Android SDK is not installed in this workspace.

## Completed CI and APK

[Build #811](https://github.com/pulicarpus/YamahaArranger/actions/runs/37816600268) completed SUCCESS. Build debug APK, both-ABI Stage 3 exclusion and Upload APK artifact are SUCCESS. The existing workflow builds and checks `arm64-v8a` and `armeabi-v7a`; no workflow changes were made.

[APK artifact app-debug](https://github.com/pulicarpus/YamahaArranger/actions/runs/37816600268#artifacts), ID `11568121819`, is listed on the run page at 12.5 MB. GitHub artifact archive digest: `610c5facfc856f35aa6e85e22b9a526f29ef4d59ae78bdebbdf230037e679b8b`. This is the server-reported artifact archive hash, not an independently computed APK hash. The session cannot download the authenticated artifact endpoint (404); availability is verified from the actual artifact table and successful upload step.

Structured step/artifact evidence: `docs/f04_sdk_guard_ci_evidence.json`. Final evidence is a docs-only commit and does not trigger another Android build. The recovered APK still requires user listening/device tests; no claim of improved Yamaha sound or fresh 503/503 conformance is made. STOP after this recovery.
