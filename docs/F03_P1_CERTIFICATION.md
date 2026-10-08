# F03 P1 — Yamaha musical and backend contract certification

## Decision and baseline

**NO-GO for a production F03 playback patch.** P1 narrows the Linux backend
uncertainty, but does not certify Yamaha authored pairing, Android runtime voice
selection, or PSR-E343 receiving behavior. There is no certified audible-fix
subset to enable now. P2 is not started or approved by this document.

Branch: `fix/mix-percussion-fidelity-784`. Before work, local/remote HEAD were
`d00548c946b81c501a915e6cd3347defd2ef4bb3`, parent P0 executable/test commit
`311a54054111bda00a9535e05a9faaaff14a418b`; working tree was clean.
P0 [Build #805](https://github.com/pulicarpus/YamahaArranger/actions/runs/37771555686)
is SUCCESS. All **371 tracked baseline files** are SHA256-pinned in a new
[P1 preservation manifest](../tests/fixtures/f03_p1_baseline_sha256.json).
Production, workflow, P0, S1–S8, all old fixtures/goldens and Stage 3 configuration
remain byte-identical. Only this report and isolated diagnostics/new evidence are
added. No experimental F03 APK, production dispatch hook or musical behavior
change is made. Dates refer to 2026-10-08 (Asia/Jakarta).

The complete [architecture decision](F03_ARCHITECTURE_DECISION.md),
[P0 report](F03_TEST_ONLY_PROTOTYPE.md), and existing
[S7](S7_F03_BACKEND_CONTRACT.md)/[S8](S8_F03_NATIVE_VOICE_EVIDENCE.md) evidence
are the starting point. P1 does not redo their ownership audit or corpus scan.
Existing tests are run as preservation/regression checks, not a new S1–S8 audit.

## Status definitions and evidence register

- **PROVEN:** direct inspectable evidence, with its stated scope. A documented
  API contract is proven as documentation, not proof of all deployed runtimes.
- **OBSERVED:** measured behavior in a bounded experiment; no universal/device
  or Yamaha inference.
- **UNKNOWN:** the behavior/identity is not established.
- **BLOCKED:** implementation/certification prerequisites are unmet. A behavior
  may be UNKNOWN and its implementation gate BLOCKED simultaneously.
- Test execution PASS/FAIL and runtime UNRUN are separate operational statuses.
  UNKNOWN, BLOCKED, or UNRUN cannot count as certification PASS.

| ID | Evidence | Scope |
|---|---|---|
| A | Architecture D1–D7/G1–G7 and P0 | Approved test model, explicit unresolved musical/backend gates |
| S5/S6 | Frozen references listed in architecture E5/E6 | 523 overlaps/47 styles, 276 replacements, 31 FIFO-observer-relative shortened dispatch intervals; first divergence already established, not device audio |
| S7/S8 | Frozen contract/native evidence listed in architecture E7/E8 | Nonselective packets, limited Linux PCM, short-release B survivor; long-release UNKNOWN, injected rejection not genuine admission failure |
| V1 | [Vendor StreamCreate](https://www.un4seen.com/doc/bassmidi/BASS_MIDI_StreamCreate.html) | HTTP200, `BASS_MIDI_NOTEOFF1` documented oldest-instance release |
| V2 | [Vendor StreamEvent](https://www.un4seen.com/doc/bassmidi/BASS_MIDI_StreamEvent.html) | HTTP200, event parameter and BOOL command return; no instance token in this note event |
| P1-N | [Local raw native observation](../tests/fixtures/f03_p1_linux_observation.json) | Real Linux SDK, reverse order, swapped velocity/signature, 132 measured windows |
| P1-S | [Source/SDK access evidence](../tests/fixtures/f03_p1_source_evidence.json) | URLs, HTTP results, HTML hashes, Android archive/header/library hashes and ELF exports |
| P1-I | Preservation manifest | All baseline bytes including P0 and S8 evidence protected |

Official vendor pages were retrieved through the normal session proxy with TLS
verification. Full pages/vendor binaries are not committed. P1-S retains hashes,
a short contract quote, and metadata so provenance is explicit. Original S7/S8
fixtures are never replaced by P1 results. P1-N is a new bounded observation,
**not a golden Yamaha capture**.

## 1. Musical pairing certification

### Official MIDI and Yamaha source access

The following targeted source requests were attempted; they are **not successfully
read sources** and must not be cited as support for a lifecycle rule:

| Target | Actual access | Certification consequence |
|---|---|---|
| `https://midi.org/summary-of-midi-1-0-messages` | Proxy CONNECT 403 | Message-summary text not retrieved |
| `https://midi.org/midi-1-0-detailed-specification` | Proxy CONNECT 403 | Normative repeated-ON/number-of-OFF/receiver-choice clauses not verified |
| `https://manual.yamaha.com/mi/kb-ekb/psr-sx920-sx720/en/psrsx920_en_rm_07.html` | Proxy CONNECT 403 | Candidate official manual location not read; chapter URL existence/content unverified |

A supported environment draft added `midi.org` and `manual.yamaha.com`, preserving
previous `www.un4seen.com`. The save requires review/save/publish in Environment
Settings and does **not** apply runtime access. No proxy bypass, alternate tunnel,
TLS disabling, or invented source quotation was used. Research can resume after
actual policy changes. This access limitation is **not evidence that Yamaha has
no official rule**. No claim of an exhaustive standards/manual search is made.

Required source closure is an identifiable edition/page/clause, not a blog or
application trace: MIDI 1.0 repeated NOTE_ON/NOTE_OFF semantics; official Yamaha
style creation/reference material for SFF1/SFF2 and held-note transitions; CASM
NTR/NTT/RTR descriptions and any separate overlap/loop/carry rule. If accessible
manuals discuss transformation but omit instance pairing, record that precise
scope and obtain Yamaha confirmation or independent controlled reference captures.

### What can and cannot be concluded now

Application serializer evidence (S7 and unchanged `MidiInputManager`180–199)
proves this application's wire messages carry destination channel/key/velocity,
not InstanceId, source identity, section visit or session epoch. That structural
fact is **PROVEN for this application**. It neither certifies a normative MIDI
allocator rule nor Yamaha style interpretation. The generic MIDI normative
repeated-note rule remains **UNKNOWN in this certification** pending source access.
Even a verified general receiver rule would not automatically define Yamaha
style-authored OFF pairing across section/loop boundaries.

| Musical topic | Existing usable evidence | Missing Yamaha authority/reference | Status |
|---|---|---|---|
| Same source/key repeated ON before OFF | Frozen S5 events; P0 retains both identities | Which authored ON each OFF ends, rejection/orphan rules | UNKNOWN; BLOCKED |
| Cross-source convergence after transform | S7 packet/source distinction disappears | Intended independently held durations and collision policy | UNKNOWN; BLOCKED |
| Main/Fill natural carry | S6 application controls retain ownership | Yamaha boundary continuity, loop wrap and scope for later OFF | UNKNOWN; BLOCKED |
| Interrupted Main/Fill | S6 cleanup trace, P0 snapshot/barrier tests | Required termination/carry set, replacement-section order | UNKNOWN; BLOCKED |
| Intro/Ending overlap | S5 case007 EndingB raw events | Independent duration/identity reference for that exact style/scope | UNKNOWN; BLOCKED |
| Loop/repeated visit | P0 distinct generation IDs | Authored OFF pairing across successive loop iterations | UNKNOWN; BLOCKED |
| SFF1/SFF2/CASM | Existing preservation/conformance evidence | Official pairing rule distinct from descriptor parsing | UNKNOWN; BLOCKED |
| NTR/NTT | Existing app transformation controls | Official held-instance effects when output pitches converge | UNKNOWN; BLOCKED |
| RTR/chord changes | Existing app retarget behavior is frozen | Independent held-note release/retrigger/pitch continuity authority | UNKNOWN; BLOCKED |
| Drum/ACMP | Existing regression boundaries and gates | Part-specific lifecycle, choke/one-shot and ACMP carry scope | UNKNOWN; BLOCKED |

Do not infer FIFO from S5's observer model, LIFO from one apparent duration,
or a Yamaha rule from BASSMIDI. Source/key equality is insufficient provenance
when section, iteration, transform or logical origin differs. A normal native
allocator may obey its own contract while being unable to implement the intended
musical OFF. Musical pairing and physical selection need independent proof.

The 31 shortened intervals are a **dispatch witness relative to the frozen FIFO
observer**, not 31 certified Yamaha/audio failures. The 523 overlaps are not 523
failures. Loss of an entire accompaniment channel has not been isolated to F03
on an actual device by P1; admission gates, voice selection, scheduler, receiver
and synth limits must not be relabeled ownership causes without new direct proof.

## 2. Backend contract certification

### BASSMIDI: documented capability versus observation

V1 explicitly documents:

> Only release the oldest instance upon a note off event (MIDI_EVENT_NOTE with
> velocity=0) when there are overlapping instances of the note. Otherwise all
> instances are released.

**PROVEN documented API contract:** NOTEOFF1 requests oldest-instance release,
not release of an arbitrary logical owner. V2 specifies stream, channel, event
and key/velocity parameter. That event has no musical origin/voice handle argument.
Its TRUE return is command success; it is not an audible-voice receipt. Buffering,
async processing and sustain/release lifetime must remain separate. Velocity255
stop, channel all-notes-off, or changing flag are not approved F03 substitutes.

The documentation addresses the API generally. Actual implementation on a
particular Android binary/ABI/device still needs runtime verification; Linux
observation cannot certify it. Neither documentation nor signature projection
exposes a selective native voice handle in the current application path.

### P1 isolated experiment and controls

New diagnostic files only:

- [Native probe](../tests/f03_p1_native_contract_test.cpp), compiled with the
  unchanged production `bassmidi_player.cpp` into a temporary Linux test binary.
- [Runner](../tools/test_f03_p1_native.py), integrity checks and raw JSON output.
- Test-only `StyleMixFidelityF03P1CertificationTest` for unchanged CI discovery.
- New P1 source/native/integrity fixtures. No main-source or workflow imports.

The probe reuses the frozen S8 SF2 builder as input, writes NEW temporary fonts,
and swaps only velocity ranges in a generated copy. A=375Hz and B=1125Hz;
normal A/v48,B/v112; swapped A/v112,B/v48. Reverse ON order is crossed with that
assignment, same/cross source and logical first-OFF attribution A/B. Three fresh
repeats give **48 first-OFF trials** and **48 same-frame double-OFF controls**.
There are 36 matching-age A/B/overlap reference windows: **132 windows total**.
Same channel11/pitch60; sources A4, B4 or5. OFF origin labels differ but native
channel/key values remain identical. No source label is used as a native selector.

Deterministic 48kHz timeline: first ON frame0, second ON2048, first OFF4096;
measure frames12288–14335 inclusive. A/B references use identical absolute birth
slots. Double-OFF applies both OFFs at frame4096 without rendering between them.
Final cleanup follows the second OFF and 30 additional 2048-frame windows.
No real-time wallclock/device latency measurement is claimed.

Stereo-to-mono integer-bin sin/cos projection is phase-invariant. References
must be nonzero and have >100x intended/other power separation. Existing S8
exploratory thresholds are retained: retained ratio>.5, absent ratio<.01,
residual<.05; otherwise UNKNOWN. The probe does not force UNKNOWN into a signature
or loosen thresholds. Known opposite-victim outcomes fail the documented-contract
control; unknown classification is reported UNKNOWN and cannot certify release.
No genuine rejected ON is manufactured; S8's injected control remains explicitly
injected. Sustain, long release and no-flag controls reuse S8 evidence instead
of repeating its completed investigation.

### Measured result and expected-versus-actual

Linux BASS `0x02041203`, BASSMIDI `0x02041000`; actual SDK flag `0x10000`.
Header/library hashes and full raw metric output are in P1-N. Results:

| Condition | Vendor-contract expected signature after first OFF | Measured | Classification |
|---|---|---|---|
| ON A then B; normal velocity/signature | Younger B | B for all source/OFF-attribution/repeat combinations | OBSERVED |
| ON A then B; swapped velocity/signature | Younger B, now quieter | B for all combinations | OBSERVED |
| ON B then A; normal assignment | Younger A, now quieter | A for all combinations | OBSERVED |
| ON B then A; swapped assignment | Younger A, now louder | A for all combinations | OBSERVED |
| Two OFFs same frame, either order | Both released after tail | SILENT, 48/48 controls | OBSERVED bounded PCM |
| Hypothetical logical OFF targeting younger note | Younger should end, older should remain if selective | Opposite: oldest ends, younger remains | Selective request unsupported by this event path |

48/48 first-OFF trials identified the younger signature; UNKNOWN0, contradiction0.
Maximum residual `8.2082484145e-09`; maximum absent normalized signature ratio
`5.73505352221e-08`; minimum retained ratio1; minimum reference separation
`516856239.611`. Native command returns: 240 accepted ONs,192 accepted OFFs
across controls/trials. These are command counts, not physical voice counts.
Final cleanup windows had energy<1e-12; double-OFF measurement windows classified
SILENT. Confidence is strong margin/repeatability for this fixture, **not a
probabilistic confidence percentage or universal allocation certification**.

This resolves the earlier age-versus-loudness/signature confound **within this
Linux two-note short-release test scope**. It does not certify three-plus notes,
stealing, arbitrary SF2 layers, monophonic modes, Android, hardware receivers or
Yamaha author intent. S8 long-release UNKNOWN stays UNKNOWN; tail contribution
is not evidence of an unreleased independent handle. Native handles remain UNKNOWN.

### Android ABI and other output domains

Official Android SDK ZIPs were retrieved normally and statically inspected, not
executed. P1-S contains archive SHA256, per-library/header SHA256 and exports.
These downloads are current vendor artifacts, **not proven byte-identical to the
libraries shipped in Build #805**. Runtime version/behavior is UNRUN/UNKNOWN.

| Endpoint/domain | Direct evidence | Release/admission capability | Certification gate |
|---|---|---|---|
| BASSMIDI Linux melodic stream | V1/V2 + P1-N + S8 | Oldest release observed; BOOL command result, no logical selective handle | Limited OBSERVED; not Yamaha/production GO |
| Android arm64-v8a | Vendor ELF64/AArch64; StreamCreate/Event/GetVersion exports | Same header flag exists; actual voice selection/admission/PCM unexecuted | Runtime UNKNOWN / BLOCKED |
| Android armeabi-v7a | Vendor ELF32/ARM; same exports | Same header declaration; ABI existence does not prove allocator behavior | Runtime UNKNOWN / BLOCKED |
| Internal synth/application bridge | Frozen S7 + current source boundary | Player ON/OFF returns void to caller; diagnostic native result is not ledger receipt | Admission integration BLOCKED |
| Sample fallback | Unchanged `AudioEngine::noteOff(int midiNote)`325–332 | Releases active sample voices matching pitch, no channel/instance selector there | Source contract PROVEN; actual routed/device behavior UNKNOWN |
| Percussion | Existing lane/owner/generation accounting; `percussionOff`2337 onward | Separate source-filtered owner queue and native lane/key; accepted/choked flags | No P1 rhythm PCM trial; not certified by channel11 probe |
| MIDI OUT | Frozen S7 serializer + current three-byte output methods | Stream/source/section IDs absent; transport send is not receiver acceptance | Receiver UNKNOWN / BLOCKED |
| PSR-E343 | No physical captures or receiver allocator documentation retrieved | Selective release and overlapping-note behavior UNKNOWN | UNRUN / BLOCKED |

Per-source logical queues cannot solve collisions in a shared destination/pitch
native domain. Per-output accounting must span sources and preserve admission
UNKNOWN. Sample fallback must use its real pitch-wide conflict domain. Percussion
lane/source behavior must not be replaced by melodic NOTEOFF1 assumptions.
No broad OFF, reference-count suppression, retrigger, deferred OFF, channel split
or replay policy is selected as a hidden fallback.

## 3. Case-by-case implementation feasibility

| F03 case / priority | Musical pairing proof | Backend release evidence | Musical risk / evidence needed | Decision |
|---|---|---|---|---|
| Main/Fill same-source overlap; cut duration (highest) | UNKNOWN | Linux oldest documented/observed; Android UNRUN | Wrong duration/carry; independent Yamaha duration reference plus Android exact-scope PCM | BLOCKED |
| Different sources converge on same destination/pitch (highest) | UNKNOWN | Logical source not native selector; younger-target mismatch observed | Cuts foreign accompaniment voice; intended instance and endpoint-wide victim proof | BLOCKED |
| Apparent lost accompaniment channel (highest) | F03-specific device causality UNKNOWN | Command acceptance != audible channel | Need synchronized event/admission/PCM reference; cannot blame all missing sounds on ownership | BLOCKED |
| Exact scheduled OFF reaches replaced slot | S6 structural divergence PROVEN; musical completion policy UNKNOWN | Exact logical token still not selective native token | Could kill next section; real carry/scheduler authority and backend selection gates | BLOCKED |
| Rejected ON followed by OFF | Authored pairing UNKNOWN; exact rejected token valid only under proven scope | Production caller lacks explicit receipt; S8 rejection injected | OFF can kill accepted peer; genuine failure/admission path and independent pairing needed | BLOCKED |
| Natural Main/Fill carry and loop boundary | Application/P0 continuity OBSERVED, Yamaha UNKNOWN | Output occupancy spans visits | Broken legato/stuck notes; Yamaha carry scope and real cancellation evidence | BLOCKED |
| Interrupted cleanup before next section | Snapshot invariant proven as P0 model, Yamaha termination set UNKNOWN | Unsupported/unknown endpoint blocks selective cleanup | Incoming voice cut/partial dual-output cleanup; physical all-endpoint barrier and approved abort | BLOCKED |
| Intro/Ending case007 | FIFO-observer-relative witness only | Same backend limitations | Wrong release/ending tail; independent exact-style reference | BLOCKED |
| RTR/chord retarget creating collision | App behavior preserved, official contract not retrieved | Old/new immutable bindings needed; no arbitrary selective native release | Wrong pitch/attack, lost peer; chord/transform reference plus retarget backend trials | BLOCKED |
| Rhythm/sample fallback overlap | Existing source contracts only | Different conflict domains, no new device test | Choke/one-shot/sample tail regression; domain-specific reference and PCM | BLOCKED |
| MIDI OUT / PSR-E343 / dual-output | UNKNOWN | Receiver contract UNKNOWN, no acknowledgement | Receiver hanging/cut notes even if internal result matches; physical wire+audio capture | BLOCKED |
| Stop/restart/stale-work guard | Logical P0 invariants only | Physical cancellation/drain and domain retirement UNKNOWN | Old work reaches new port/session; real scheduler/cleanup/rollback test | BLOCKED |

A narrowly targeted stale guard or accounting-only shadow might be designed
later, but is not a certified audible F03 patch and does not bypass separate P2
approval. Even a unique logical candidate does not supply a receiver contract,
true admission receipt, or approved Yamaha carry scope. No new production branch
or feature flag is created.

## 4. Minimum evidence that can open the decision

The most decisive next experiment is **one independent Yamaha style reference
paired with the same output collision on the actual target Android backend**.
It must distinguish author intent from generic receiver allocation:

1. Acquire identifiable official MIDI/Yamaha clauses (after access is enabled),
   or explicitly establish their unresolved scope. Obtain the original authorized
   witness style or a separately labeled minimal test style; record style hash,
   CASM descriptors, tempo/chord, section/loop boundaries and event order. Do not
   silently turn a reduced fixture/reconstructed style into original Yamaha intent.
2. On a real Yamaha arranger/reference engine, capture the bounded overlapping
   style passage under stable chord/tempo: isolated A, isolated B, A+B and release
   variants, plus Main D -> Fill B -> Fill A -> Main A natural/interrupted controls.
   Capture timestamped MIDI OUT and PCM together. Wire OFF alone carries no voice
   identity; calibrated audible signature/envelope evidence must independently
   distinguish which contribution ends. Reverse order/velocity controls prevent
   an age/loudness assumption. If indistinguishable, report UNKNOWN.
3. On actual shipped Android libraries, **both ABIs**, run the same independently
   defined ON/OFF trace in an isolated approved diagnostic environment, pin SDK
   binaries/version/device/sample-rate/font and capture native return plus PCM.
   P1 did not create an APK for this. Real APK/runtime testing requires separate
   approval and a plan that keeps the shipped baseline behavior intact.
4. Test requested younger release, cross-source convergence, genuine rejected ON,
   sustain/tail, stealing and minimum three-instance counterexample before
   generalizing beyond the two-note subset. If requested release cannot be
   expressed, it stays BLOCKED; propose a musical/backend policy separately.
5. If MIDI OUT is in intended scope, separately capture PSR-E343 receiver PCM and
   wire traffic. No Linux/Android rule can certify it. Narrow internal-only scope
   would itself need explicit approval; do not disable MIDI OUT silently.

A generic PSR-E343 repeated-key receiver test alone would not establish Yamaha
**style-authored pairing**. Conversely a Yamaha style reference would not prove
Android selective release. Both sides of the chosen subset must match before GO.
Restore original `sff1.zip` with SHA256
`a9b89b90e1dee25c714a076a125d6c7173bcca0e8d2029c57092b3295035d465`
for the required fresh 503/503 gate; until then it remains BLOCKED. Frozen
regression/corpus evidence is preserved and is not a new original-corpus run.

## Preserve the good baseline; STOP and rollback

Keep the current playback/CASM/NTR/NTT/RTR/chord/rhythm/ACMP/timing/mixer/routing
unchanged. Continue normal baseline mode; never switch ownership registries
mid-note, alter all-OFF behavior, rerecord goldens or activate Stage 3 to mask a
conflict. Baseline functional regressions and known failure captures remain
independent of future candidate F03 expected deltas.

Before any later production candidate: explicit approval, exact subset/reference
pairing, target backend capability and admission gate, real APK device tests,
non-F03 golden parity, original corpus503/hash, Main/Fill/Intro/Ending/loop/RTR/
ACMP/rhythm guards, both ABI Stage 3 exclusion, performance bounds and physically
verified stop/drain/output-epoch rollback. Device testing must separate event
trace correctness, API success, native PCM and audible hardware results.

STOP if evidence cannot distinguish instances, source provenance is fabricated,
UNKNOWN admission is treated as accepted, collision victim differs from intended
owner, delayed OFF reaches another epoch/visit, cleanup touches incoming notes,
MIDI receiver differs from certified scope, or preservation/regression fails.
An unexpected live conflict has no approved registry-switch/count/retrigger
fallback. An explicit abort/reset protocol must be approved and tested first.

P1 rollback is a focused revert of new diagnostic/documentation files to
`d00548c...`; no production state migration is involved. Production rollback
anchor remains final S8 `12304d813...` (source tested #804), and P0 #805 confirms
that same playback remains intact. A later implementation rollback must drain
and retire endpoints/session work safely; Git revert alone cannot prove silence.

## Integrity, regression and CI

Local validation (2026-10-08):

| Check | Result | Limit |
|---|---|---|
| Full `tools/test_voice_resolver.py` | PASS | Native/mock/metadata/structural guards retain original scopes |
| SFF1 pipeline existing-regressions/S2/S3/S4/S5 | PASS, 83 JVM tests | 20 unchanged captures and frozen S5 controls; not corpus503 rerun |
| SFF1 fail-closed harness | PASS, 7 tests | No original corpus claim |
| P0 runner | PASS, 24 tests | Synthetic bookkeeping only; Yamaha pairing still BLOCKED |
| S6/S7/S8/P0/P1 host JVM wrappers | PASS,14 tests | New P1 class compiled alongside unchanged previous classes; pinned baseline dependency jars |
| P1 real Linux probe | PASS execution | 48 younger-survivor observations,48 double-OFF controls,36 references; not target-device certification |
| Android SDK static metadata | PASS retrieval/inspection | Runtime UNRUN; shipped-binary equivalence UNKNOWN |
| Original503 corpus | BLOCKED | Original ZIP/hash unavailable |
| Physical Android and PSR-E343 | UNRUN | No new APK/device experiment |
| Baseline preservation | PASS,371/371 | Every original tracked byte identical, including P0/S1–S8/production/workflow/old fixtures/goldens |

CI: pending diagnostic-only push; no workflow change. The existing normal
workflow runs the new unit-test wrapper, builds its baseline APK and checks
Stage 3 exclusion. That normal build does not add an experimental F03 APK.
Final CI metadata will identify the tested executable commit separately from
any documentation-only evidence follow-up.

The P1 native runner verifies all 371 baseline bytes before and after execution;
CI invokes it only from the new `app/src/test` wrapper. Missing real SDK is
BLOCKED locally and a fail-closed error in CI, whose existing setup provides it.
Probe execution PASS never promotes Yamaha pairing/Android/PSR/native handles.
No old test/assertion/workflow is edited.

**Final decision: NO-GO for production F03. STOP after P1; no P2, playback patch,
experimental F03 APK, FIFO Yamaha assumption or automatic implementation.**
