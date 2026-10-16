> Previous NO-GO study retained below. See PRECISION_RUNNER.md for the subsequent fixed protocol, unchanged official-gate run and latest decision.

# R2-3 host overhead audit — NO-GO for publication

No production code was changed in this investigation. Previously verified
functional gates and the local draft remain intact. No threshold, original
benchmark, workflow or historical baseline was replaced.

## Contracts and baselines

ROSITA_A1_A2_VALIDITY_AUDIT.md G5 specifies render overhead below 1%, measured
on the device. Device G5 remains unverified. The repository additionally pins
an R2-0 HOST MOCK gate below 1% against 163f769 in test_r20_host.py and
R20_PCM_SESSION_IMPLEMENTATION.md. That historical host contract is retained;
it includes PCM_SESSION work inherited before R2-3. The valid incremental
R2-3 baseline is its exact parent d456d733 (R2-2). Both comparisons are needed
while the R2-3 workflow still requires the historical host gate.

## Fixed protocol, no retries selected for a green result

Same machine and compiler, same mock decode/input, 128 frames per block,
150000 render blocks per timed sample. Each measured child was pinned to CPU1
from the allowed CPU set [0,1,2]. Five warm-up process runs per variant were
excluded in advance. Then 31 complete randomized-order blocks were retained,
with a predefined seed. No compilers or other benchmarks ran during timed
sampling. The randomized order is not an exactly balanced Latin-square design.

The historical series uses the unchanged R2-0 differential driver and its
original flags (-O2, ROLE_PCM_METERS=1, PCM_HEADROOM=1; compatible percussion
left at its default OFF). The R2-2 binary is compiled with the same driver,
macros, mocks and flags as its candidate counterpart. Raw process timings and
binary identities are in overhead-controlled-history.json.

The second series represents the debug native configuration, adding
COMPATIBLE_PERCUSSION=1, and loads the same four golden PDTA-only fixtures
before timing. All variants perform the same warm-up and render workload.
RESOLVED verifies actual stream binding before measurement. No sample PCM is
invented: this is mock decode, not a sound comparison. Both wall and process
CPU times are collected; NOTE_ON/OFF and snapshot timings are measured after
the render interval, separately. Data: overhead-controlled-modes.json.

Reports retain the original ratio-of-medians estimator and the strict <1%
threshold, plus paired log-ratio geometric means and a fixed 10000-resample
bootstrap 95% interval. These are distinct estimators; a bootstrap interval
around the paired mean must not be presented as an interval around the median.
The original seven-pair +2.112% failed run remains in overhead-failure.json.
Session-bound reproduction scripts/harness are in overhead-reproduction/;
they record the exact source/fixture paths used on this workspace.

## Results

Historical flags, render-only:

| Comparison | Ratio-of-medians delta | Paired geometric mean | Paired 95% interval |
|---|---:|---:|---:|
| 163f769 → R2-2 | +1.578% | +1.561% | +0.347% … +3.000% |
| 163f769 → R2-3 LEGACY | +1.388% | +0.387% | −1.311% … +2.224% |
| R2-2 → R2-3 LEGACY | −0.187% | −1.155% | −2.619% … +0.257% |

Debug native flags, render-only:

| Comparison | Ratio-of-medians delta | Paired geometric mean | Paired 95% interval |
|---|---:|---:|---:|
| R2-2 → R2-3 LEGACY | −0.700% | −0.725% | −3.300% … +1.255% |
| R2-2 → R2-3 RESOLVED | −0.675% | −0.856% | −3.387% … +1.148% |
| R2-3 LEGACY → RESOLVED | +0.025% | −0.133% | −1.592% … +1.305% |

Negative point estimates are not speedup claims. Debug point estimates pass
the median threshold, but the confidence intervals do not establish overhead
below 1% with 95% confidence. The historical median gate still FAILS; the
paired mean does not justify replacing its mandated median estimator.
This is evidence of an unresolved historical-gate/measurement limitation,
not proof of an R2-3 callback regression or of a noise-only explanation.

## Separate costs and callback audit

| Component, debug native | R2-2 LEGACY | R2-3 LEGACY | R2-3 RESOLVED |
|---|---:|---:|---:|
| Render median, ns per 128-frame block | 3851.60 | 3824.64 | 3825.59 |
| NOTE_ON/OFF pair median, ns | 1641.98 | 1647.55 | 1707.03 |
| Read-only native selection snapshot median, ns | 250.50 | 245.12 | 244.15 |

NOTE dispatch and readback cost are outside the render interval. They are
JNI caller-thread work, and can share the synth mutex; these host means do
not certify device contention/xrun tails. No extra resolver is placed on an
audio callback. R2-2 and R2-3 render function bodies are byte-identical (hash
in overhead-callback-proof.json). Heavy catalog evaluation remains in Kotlin
section/dynamic-PC setup or the bounded R22-shadow-metadata worker. Native
binding is a preset-setup JNI call. SF2 I/O/scanning is absent from render.

The existing cold resolver/shadow suite passes on three fresh JVMs with
p99 250.317/287.463/218.008 microseconds, below its unchanged 2000 us gate.
Its decision digest is unchanged. This suite's static ROSITA-table label does
not replace the separately verified original ROSITA byte replay (205 hits).

## Decision and recommendation

STOP / NO-GO: the retained historical host median gate remains above 1%.
There is no demonstrated >1% incremental R2-3 render regression to justify
altering production code. Optimizing gain, mixer, inherited render/PCM logic,
or changing a baseline/threshold to make this pass would violate scope.

Next work should validate this historical measurement on a quiet dedicated
runner with a predefined balanced short-epoch paired protocol and sufficient
precision, and separately check R2-0's historical cost against 163f769. The
Redmi Pad callback measurement is still required for actual G5. Any decision
to retire a historical HOST gate must be explicit owner authorization, not a
silent workflow edit. No commit, push, CI or APK build was performed.
