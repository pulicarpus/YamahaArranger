# S8 — F03 Native Voice Identification

## Scope and baseline

Diagnostic-only, branch `fix/mix-percussion-fidelity-784`. Local and remote
baseline verified as `291153a58a0873584f2e0adfc7dd220673f1b8af`, parent
`7fe5b654ab70526c9f1f1a980aaf85e1dc9d834c`. S1–S7 conclusions and fixture
identities are preserved. No production, workflow, golden or regression guard
changes; Stage 3 remains excluded. No F03 implementation or S9 authorization.

## What S7 UNKNOWN actually means

Build #802 run `37757196098` was SUCCESS, but its native stdout/PCM metrics
were not recovered. The `mix-percussion-proof` archive id `11540508861`, SHA256
`ef9ff56f16514494c7490e761a2ac462b7e6d9eb2222d5c2b1c66482ed797b0b`, returned
HTTP404 again during S8. Archive contents/hash are metadata from S7, not a newly
verified downloaded archive. Therefore **UNKNOWN because evidence is unreadable
is distinct from classifier output UNKNOWN**. No classifier error value, survivor
or real-SDK version from #802 can honestly be reported as observed here.

Static audit of every S7 PCM metric and classifier dependency:

| S7 quantity | Construction | Limitation / classification |
| --- | --- | --- |
| referenceEnergyFirst/Second | sum of squared stereo samples in frames1280–1535, A at0/v96, B at512/v32 | Same waveform, different age/gain; actual values UNKNOWN |
| crossErrorFirst/Second, sameErrorFirst/Second | squared pointwise error / reference energy | Phase, age, envelope and amplitude all affect error; no fitted alignment or gain |
| crossSourceSurvivor, sameSourceSurvivor | error to one reference <1e-5 and other >.01 | Very strict relative error; neither similarity nor failure threshold calibrated against tail/noise controls |
| rejectedThenOffEnergy | rejected A injected by wrapper; B admitted; OFF for A | Tail energy is not a voice identity or evidence of genuine device rejection |
| stopEnergy | allNotesOff after two ONs | No calibrated silence/noise threshold; absence of perceptual sound not established |

The font has one sine sample, no sampleModes generator54 (one-shot), 4000
frames at48kHz (~83.33ms), default envelope/release and no explicit release
control. The S7 measurement is ~26.67–32ms into the stream, not beyond sample
end. OFF occurs at1024; discarded release interval256frames (~5.33ms) is not
proof that every tail has ended. Velocity changes volume/filter as well as age;
normalizing error by reference energy does not normalize gain or phase. These
are **SUSPECTED classifier confounds**, not proven causes of an observed UNKNOWN.
Alignment/delay, release tails, attack/decay, normalization, phase and threshold
must be separated before interpreting waveform mismatch as native pairing.

## S8 experiment and controls

`tests/s8_signature_fixture.h` generates a new test-only SF2; existing S5–S7
fonts/goldens remain unchanged. Velocity48 selects A/375Hz; velocity112 selects
B/1125Hz. Both use destination11/pitch60, sustained integer-period loops,
explicit near-instant attack/decay, sustain0centibels and release -12000 timecents.
A separate control font has release0timecents (1second). No reverb/chorus send.

`tests/s8_native_voice_test.cpp` links the unchanged production player to real
SDK headers/libraries. Link wrappers record accepted native calls and the actual
stream flag; the only rejected ON is explicitly injected by the test wrapper.
The no-NOTEOFF1 comparison changes a **test stream** flag only. It never edits
production flags or player behavior. Sustain uses native CC64-equivalent event
on this isolated stream. Origin metadata is passed through production note APIs.

Timeline at48kHz: A ON/frame0; B ON/frame2048 (42.67ms); first OFF/frame4096
(85.33ms); measurement window frames12288–14335 (256–298.67ms), after8192frames
of tail allowance. Second OFF/frame14336; sustain released if set; final cleanup
measurement after61440frames (1.28second). Reference trials have matching
absolute ages. Each render block is2048frames, stereo float; spectrum uses mono
average and integer bins16/48. Independent sine/cosine projections remove phase
sensitivity. Metrics retain raw energy, both powers, residual fraction and both
ratios to isolated references; no unit gain assumption is hidden.

| Trial | Expected diagnostic observation | Actual evidence available locally |
| --- | --- | --- |
| isolated A/B | Nonzero energy and >100x intended/unintended power separation | BLOCKED: SDK absent |
| overlap A+B | Both signatures, or measurable backend limitation | BLOCKED |
| same-source OFF A→B and OFF B→A | Same native key60 OFF packets; observe first residual signature, then final energy | BLOCKED |
| cross-source OFF A→B and OFF B→A | Same native wire values despite distinct logical origins; observe conflict with desired attribution | BLOCKED |
| no NOTEOFF1 | Compare real flag semantics without assuming all/oldest/newest | BLOCKED |
| sustain | Hold after first OFF; release pedal before cleanup | BLOCKED |
| long release | Tail contamination reported at170.67ms after OFF, not mistaken for sustained ownership | BLOCKED |
| injected rejected A | Only B admitted; old A OFF still has key60, no selective token | BLOCKED; injection is not real backend rejection |

Classification is deliberately conservative: residual<.05; retained signature
ratio>.5; absent signature ratio<.01. A/B/BOTH/SILENT otherwise UNKNOWN. These
are explicit exploratory thresholds, not acoustic or Yamaha certification.
The >100x reference separation guard must pass first. Low-energy tail/noise or
unmodelled spectrum can remain UNKNOWN. A retained frequency signature identifies
sample contribution, **not a native voice handle, audible success, or universally
correct FIFO/LIFO**. Final cleanup energy is recorded independently, not inferred
from event acceptance. API acceptance and sample identity are separate outputs.

## Evidence and implementation consequence

| Claim | Status | Evidence / limit |
| --- | --- | --- |
| Native OFF request contains no logical source/token | PROVEN (S7 unchanged recorder/serializer) | Production call format; source metadata cannot select A/B |
| Real NOTEOFF1 accepts/chooses a particular S8 voice | UNKNOWN pending readable SDK output | Neither mock constants nor source comments establish SDK semantics |
| S7 classifier actually returned UNKNOWN | UNKNOWN | #802 metrics unavailable |
| S7 classifier is sensitive to gain/phase/tail | SUSPECTED as actual failure cause | Pointwise-error design; no observed failing metrics |
| Linux residual signature certifies Android/PSR-E343 | UNKNOWN / not inferred | Different platform binaries and external MIDI receiver |

Smallest safe F03 design remains logical immutable instances with explicit
admission, release attribution, section/session discrimination and accounting
for destination/pitch collisions. A per-source FIFO alone cannot guarantee
native voice choice when two sources converge. Coalescing/refcount/retrigger
changes attacks/lifetimes; no alternative is implemented here. Natural carry
must retain explicit ownership across sections; interrupted cleanup must release
only its snapshot and prevent stale scheduled tasks consuming incoming owners.
Existing S6 controls remain the regression evidence for those logical concerns.

Before implementation: recover #802 raw metrics/XML; execute/read all S8 real
SDK trials with version/library SHA256; validate classifier thresholds against
reference-repeat/noise/misalignment and release controls; repeat on shipped
Android ABI/SDK/SF2 with PCM capture; establish authored ON/OFF pairing and
cross-source collision policy; obtain actual PSR-E343 receiver evidence for MIDI
OUT; establish genuine native admission/rejection handling; retain all S1–S7
guards, golden captures and Stage3 exclusion. Linux synthetic signatures alone
cannot approve a general ownership fix.

## Regression and CI

Local PASS: `test_voice_resolver.py` (all host resolver/native recorder guards),
`test_sff1_pipeline.py --existing-regressions --s2 --s3 --s4 --s5` (83 JUnit),
`test_s7_f03.py` (11 JUnit), `test_s8_f03.py` (12 JUnit), and
`test_sff1_harness.py` (7 fail-closed tests). Native C++ syntax checked with mock
headers only; this is not SDK ABI/acceptance/PCM verification. All353 tracked
baseline files remain byte-identical. No regression FAIL observed.
Commit-specific CI evidence will be recorded after the diagnostic push.
The original503-style `sff1.zip` is unavailable; full503/503 rerun is BLOCKED,
not inherited as a new PASS. Device PCM Android/PSR-E343 is UNRUN. Local native
SDK/PCM is BLOCKED: headers/libraries absent and vendor download HTTP403.
The isolated JUnit orchestration explicitly records BLOCKED; a green JUnit test
in that state means orchestration succeeded, not a successful PCM experiment.

The network configuration draft adds only `www.un4seen.com`; saved but not
published/applied. No network bypass or replacement SDK is used.
