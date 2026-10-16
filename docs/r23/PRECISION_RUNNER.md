# Precision host runner — GO for diagnostic CI, device certification pending

All previous FAIL results (+2.112%, +1.388%) are retained unchanged. No
production edit or threshold relaxation was used for these new measurements.

The available shared Linux VM has quota 200000/100000 us; governor controls
are unavailable. This is not an exclusive physical runner. Children are pinned
to CPU1. No compiler runs during sampling. Cgroup before/after accounting is
recorded; the historical series had zero additional quota throttles.

Fixed protocol: persistent processes, 10000 render-block warm-up, 1000-block
epochs, 150 epochs per variant per trial, exactly 48 trials. Every one of all
six variant orders occurs 25 times per trial, shuffled with seed 20261016.
Each variant renders 150000 blocks of 128 frames per trial. Short epochs
reduce whole-process order drift. No trimming, optional stopping or rerun
selection was used. Timing/IPC is tooling only; production render is unchanged.
Historical flags/input match the old harness; debug flags/input match the prior
mode study and use the same four golden PDTA-only fixtures, not actual audio.

Raw trial sums, commands, cgroup stats, binary hashes and session-bound source
harnesses are in precision-runner/. Wall and CPU time are retained separately.
The ratio of medians retains the original point gate. A separate fixed-seed
10000-resample paired-log-ratio bootstrap describes paired-mean uncertainty.
These intervals characterize within-series variation, not every future ASLR
placement/machine; the prior fresh-process studies remain relevant limits.

| Wall-time comparison | Median delta | Paired mean | Paired 95% interval |
|---|---:|---:|---:|
| 163f769 → R2-2 | +0.294% | +0.336% | −0.031% … +0.685% |
| 163f769 → R2-3 LEGACY | −0.207% | −0.166% | −0.545% … +0.238% |
| R2-2 → R2-3 LEGACY, historical flags | −0.499% | −0.501% | −0.831% … −0.138% |
| R2-2 → R2-3 LEGACY, debug flags | −1.188% | +0.142% | −0.309% … +0.599% |
| R2-2 → R2-3 RESOLVED, debug flags | −1.138% | −0.236% | −0.891% … +0.457% |
| R2-3 LEGACY → RESOLVED | +0.050% | −0.378% | −1.067% … +0.325% |

All medians and paired upper 95% bounds are below +1%; CPU comparisons also
pass. Negative estimates are not speedup claims. Old failures cannot be
retrospectively attributed conclusively to one host condition.

After both studies, the unmodified test_r20_host.py was invoked exactly once
on CPU1. Its original 163f769 baseline, seven pairs, 150000-block workload,
estimator and strict <1% assertion were preserved. PASS: median −2.8444612846%.
PCM/session/MIDI/mapping checks also passed. All seven-pair arrays and the
one-attempt status are preserved in precision-runner/official-*.json.

The new workflow uses run_r23_host_controlled.py only to pin an allowed CPU
and record runner state. It invokes the unchanged historical gate, propagates
failure, and cannot select a baseline/threshold. Android still needs host.
CI must independently pass before any diagnostic APK is published.

R20 implementation/device-test rules require host gates even for diagnostics;
no separate path bypasses a failed host job. Historical source-profile CI
remains separately BLOCKED and is not certified. Release/merge and actual
Redmi Pad/E343 G5 remain NO-GO/UNVERIFIED. No sound improvement is claimed.
