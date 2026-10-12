# Original phdr and LoveSong evidence preparation

Reference: style checkpoint `c75122daf5d1077db55d50d1c261144fde59af4a`.
Parent reviewed: `056be5d9f33f18139f5050cade5a1873019a8d5f`.
Decision: GO for read-only evidence acquisition; **NO-GO for master construction**.
This implements the next-evidence gate, without changing any production source,
loader, style, workflow, READY, or other development branch.

## Evidence status

| Evidence | Status | Scope |
|---|---|---|
| Original 1,148-preset metadata and fingerprint declarations | PASS | Existing CSV validation, not newly hashed original SF2 bytes |
| Read-only phdr parser | PASS | 12 synthetic structural/integrity tests; synthetic files contain no audio |
| Original four SF2 file bytes / phdr ordering | UNVERIFIED | WAITING_FOR_ORIGINAL_SF2; zero original-order rows exported |
| Exact LoveSong3 MainD source export | PASS | 10 parts/policies, 1,089 events, 503 raw note-ons; pinned baseline export |
| Exact original LoveSong style bytes / app-selected identity | UNVERIFIED | Three distinct LoveSong references; no original style file available |
| Capture validation and frozen host replay mechanism | PASS | 8 tests, including synthetic SMF + frozen parser/resolver replay |
| Original Android/BASSMIDI requests, binding, PCM, per-key donors | UNVERIFIED | No original runtime capture |
| Historical guard suite | FAIL | Preserved failures; see logs and investigation below |

The MainD export was extracted verbatim from the already tracked
`app/src/test/resources/sff1_main_d_native.tsv`. Full resource SHA256 is
`5136410612a6a721b2fc2b8e06cdb20f1fa38129870e7a5b4943f176c9c3b1a4`;
the extracted block is
`fa8bc3db1b1cf29068968a46571878a87196dd342e419ac801042735daee100d`.
Export baseline is `05cdb1f0998d082d701bc0d1979580270ae2eefc`, not the frozen
style checkpoint. Existing 483 dispatched C_MAJOR notes are a baseline
observation, not a musical correctness oracle. Other sections remain unknown.
The registry binds LoveSong1, LoveSong2 and LoveSong3 separately by exact filename,
size and original-file SHA256. Similar names cannot establish app selection.

## A. Extract original phdr locally

Requires Python 3, standard library only. Keep the four originals outside the
repository; the tool opens them read-only and does not copy them. Use exact
filenames from `tests/fixtures/percussion_inventory_fingerprints.json`.

Windows PowerShell, from repository root:

```powershell
py -3 tools/sf2_extract_original_phdr.py --sf2-dir "D:\OriginalSF2" --output-dir "D:\Evidence\phdr-new" --metadata tests/fixtures/sf2_real/SF2_ORIGINAL_PRESET_METADATA.csv --fingerprints tests/fixtures/percussion_inventory_fingerprints.json
$LASTEXITCODE
```

Android Termux, after installing Python and granting local shared-storage access
with `termux-setup-storage`, from a checkout containing tool and CSV/JSON fixtures:

```sh
python tools/sf2_extract_original_phdr.py --sf2-dir "$HOME/storage/shared/OriginalSF2" --output-dir "$HOME/storage/shared/Evidence/phdr-new" --metadata tests/fixtures/sf2_real/SF2_ORIGINAL_PRESET_METADATA.csv --fingerprints tests/fixtures/percussion_inventory_fingerprints.json
```

These commands only read originals and write diagnostic results to a **new**
output directory. Restricted/scoped storage or missing exact names must be
resolved by granting local read access, not by substituting a similar file.
Exit 0 means all four original SHA256/count/identity/EOP checks passed; 1 means
FAIL; 2 means WAITING. Existing output directories are rejected. Missing or
invalid originals emit JSON to stdout, with no phdr CSV and no output directory.

On success, return `SF2_ORIGINAL_PHDR_ORDER.csv` and
`SF2_ORIGINAL_PHDR_VERIFICATION.json`; do not commit original SF2 binaries.
The CSV ordinal is zero-based file order within its source; it is not metadata
row order or bank/program sorting. The verifier hashes full files, checks pinned
counts 892/21/93/142, validates the bank/program/name multiset, rejects duplicate
addresses, checks bounded RIFF/sfbk/pdta/phdr structure and the sole terminal EOP.
Names preserve Latin-1 bytes including trailing spaces; no trimming/repair.
Terminal fields and bag-index monotonicity are checked. Complete pbag/pgen,
instrument/sample legality, zone/key/velocity donors and audible sound are NOT
certified by this header extractor. Physical trailing bytes are hashed/reported.

Reproduce tests: `python tools/test_sf2_original_phdr.py`.

## B. Capture and replay exact LoveSong requests

Preparation (no original style or Android required):

```sh
python tools/sf2_lovesong_capture.py prepare
python tools/test_sf2_lovesong_capture.py
```

Preparation validates only the pinned source export; it does not promote it to
an Android trace or reparse missing style bytes. The synthetic replay unit test
patches the registry in memory; the real CLI cannot accept that artificial style
or mistake it for any original LoveSong file.

Acquire the exact style selected in the app, its unmodified bytes and SHA256.
Use `lovesong_fixture_registry.json` to select the exact identity. If none matches,
stop and establish a new independently verified identity instead of guessing.
Using an already available APK from the frozen checkpoint, record app/native
build identity, BASSMIDI version/build, original source hashes, real import order,
actual basenames, load states, handles/source-generation and style identity.
Do not build an APK or add production instrumentation for this diagnostic task.
Capture existing resolver/SET PRESET/VOICE/SECTION/DYNAMIC logs where available;
retain the raw log and SHA256. If existing logs omit a field, record UNKNOWN and
report the evidence gap rather than infer that field from the host resolver.

For each original request, retain section, chord, beat tick, source/destination
part and channels (explicit zero/one-based namespaces), MSB, LSB, zero-based PC,
resolver bank14, exact voice name (including explicit empty names), ACMP state,
controllers, source generation, and a raw-trace reference. Separately retain
actual selected native handle/source/hash/bank/PC/name, fallback reason/family,
drum key/velocity and donor zone/source, applied gain trim and ownership state.
The template is not a capture and is deliberately rejected by the replay CLI.
Use `mode: ANDROID_REQUEST_CAPTURE` only for real observations.

Conditional replay once exact bytes and observations exist:

```sh
python tools/sf2_lovesong_capture.py replay --style-file /local/LoveSong3.S687.prs --style-id Ballad/LoveSong3.S687.prs --bundle /local/capture.json --raw-trace /local/raw.log --output-dir /local/new-replay
```

Host replay additionally needs a C++17 compiler and the frozen source files
listed in `sf2_golden_source_snapshot.json`. On Windows use a Python/C++17
terminal with `c++` available (e.g. MSYS2); on Termux use Python and clang.
The parser and resolver header hashes are verified before compilation. The tool
parses exact style bytes, checks captured sections, and executes policy replay
twice for byte determinism. It replays captured requests, not the full Android
sequencer or Kotlin RIGHT1/2/3/LEFT path. Source indices 0..2 denote melody import
order; diagnostic index3 denotes the dedicated drum source, not a native handle.
Partial imports/single-source loads are outside this four-original contract.

Native basenames must be observed: they affect Yamaha/GM classification.
Source metadata row order remains provisional. Top-score ties have
`UNVERIFIED_ORIGINAL_ORDER`; even unique host winners do not certify real native
binding. Original phdr outputs must later be integrated before certifying any
order-dependent tie or first-drum fallback. Observed data are retained verbatim;
the raw-trace hash binds bytes but does not authenticate manual transcription.
The tool cannot verify that a claimed build identity is honest from a string.
Drum rows explicitly remain UNVERIFIED: PC73, banks126/127/128, first-kit fallback,
per-key donors and gain/PCM need native observations and full zone evidence.
Existing metadata proves absent SX/Tyros drum PC73 and Colombo candidates; it
does not establish which original-order fallback BASSMIDI selected.

## Historical guards and remaining evidence

No guard, expected hash, or production source was changed. Test logs preserve
exit codes and assertions. F12 allowlist predates the checkpoint's NextBar and
section-boundary experiments; pinned StyleSequencer SHA256 is
`c768285a3b4a303c270f1d1c68e04d559bb2951d28a249d3c19455715a97cf89`,
whereas F12 raw before/after hashes are
`09da84a3db83dddca662afc472cf74b7ead472bfaa59aa2d27e51f217cfb352e` and
`ed80d7c5983ce23ab684601e25ec59b12d9c9e8b307b2abad13051b5ed02250a`.
The drum shadow test fails while calling the historical normalization chain, before its own
structural assertions. Therefore it is FAIL, not a demonstrated new SF2 regression
and not evidence that downstream assertions passed. Other historical protected
profiles may fail separately; incomplete snapshot dependencies are UNVERIFIED.
See retained `sf2_real/historical_guard_investigation.json` for checkpoint parent,
126 unchanged production blobs and subsequent source-history evidence.

Minimum next inputs: (1) all four original phdr outputs with original full-file
hash verification; (2) exact app-selected LoveSong style bytes/hash and desired
sections/chord cases; (3) raw checkpoint Android/BASSMIDI request/binding capture
with actual import order/basenames/build identities; (4) full zone/key/velocity
donor and gain/controller observations for drums and PCM comparison. These gates
remain open; no master, loader swap, or audio certification is authorized.

Final rerun: phdr 12, capture/replay 8, golden-routing 18, real-inventory 14,
partial metadata 2 Python tests PASS; checkpoint host 11 targets PASS.
The broad voice-resolver runner passed its 15 native targets, then FAIL at the
historical drum-shadow guard. Direct mix-percussion guard FAIL at the same F12
pin. Role-PCM FAIL at ArrangerBrain and diagnostic788 FAIL at MainViewModel,
each against its own historical profile. No profiles were rebased. Commands,
complete output and exit codes are in `tests/fixtures/sf2_evidence/test_results.json`
and adjacent `.log` files.

S2 and S3 source guards PASS (4 unit tests each). Production-regression-bypass
FAIL at the protected #776 ContentResolverProvider profile. F04 runner FAIL
includes the same F12 assertion and an incomplete-local-snapshot error for
`.github/workflows/inspect-sf2.yml`; the full historical manifest scan is therefore
UNVERIFIED. The workflow was neither edited nor executed. This is not a full
Android/Gradle build or a certification of every repository-wide check.
