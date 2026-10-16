# R2-3 — host GO for isolated diagnostic CI/APK

Branch: experiment/bassmidi-r2-3-drum-ab.
Required direct parent: d456d733c788b46ae06fe09c8b687eeb4915d554.
Exact commit/run identities are recorded by CI provenance/publication report.

Previous STOP results remain in RECALL_CAPTURE_AUDIT.md, overhead-failure.json
and OVERHEAD_METHODOLOGY.md. PRECISION_RUNNER.md records fixed precision studies
and one predeclared unchanged historical-gate run: all medians and paired upper
95% bounds pass +1%; the original historical gate also PASSes. No production
change was made to chase timing. CI must independently pass before APK upload.

Retained gates: LIVE/REG85, integration80, JVM78+mute2, full native regressions,
real N10/full Policy C pools, ROSITA205/205, Colombo Standard/Room/Rock and GS128
native binding/readback, note/decode stream identity, mode/stale rejection,
LEGACY4096 bytes/85 traces identical, PDTA/golden/catalog and source boundaries.
Cold resolver p99 is 218–287 us on three JVMs, below unchanged 2000 us.

The reviewed LIVE/REG fixture handshake preserves every original assertion.
Original guards/workflows are unchanged. Loader, normalization, render, mixer,
Policy C, gain, CASM, ACMP, LIVE CONTROL, REG MEMORY, RELEASE and MIDI OUT remain
protected. Only the new diagnostic branch/workflow may publish the APK after
host and two-ABI Android gates. Full historical source-profile CI is still
BLOCKED, not claimed PASS. Release/merge/device G5 remain NO-GO/UNVERIFIED
pending real Redmi Pad/E343 testing. Follow DEVICE_TEST.md.
