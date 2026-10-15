# R2-0 MINIMAL — PCM session marker

Base: 163f769bf056f0dd0593f78ab20fbbe700dcb56f. Branch: experiment/bassmidi-r2-0-pcm-session. Authority: ROSITA_A1_A2_VALIDITY_AUDIT.md, D1/D2/D5 only. No R2-2 activation, per-drum/keyboard GetChannel, new audio stream or FX tap.

## Implementation

Debug Inspector adds MARK PCM SESSION while stopped. JNI forwards to the existing player without starting/reloading the engine. Under the same player mutex used by render/NOTE_ON/export, mark copies HEADROOM stage/role totals, all sixteen new engine NOTE_ON attempt/sent counters, existing STYLE_VELOCITY counts, frame position, mapping generation and monotonic timestamp. CC91/93 are read via existing read-only GetEvent only.

A session observes the existing HEADROOM mixed-block peak once per successful decode callback. There is no additional PCM loop, buffer write, SDK call, allocation, MIDI event or lock in render. Peak and worst block frame/generation restart at mark; clipping, energy, samples and role/stage levels are exact counter deltas from the snapshot. Existing process-lifetime counters remain intact. A second mark advances session ID and resets the session baseline. RMS is sqrt(delta energy / delta interleaved samples); frame count is samples/2 and audio duration is frames/sampleRate. Wall duration and snapshot timestamps are separate. Failed/no-stream decode callbacks do not claim successful PCM frames.

NOTE_ON instrumentation is integer-only, under the existing lock, and includes failed/rejected attempts on channels0–15, keyboard0–3, and existing compatible-lane success. No acceptance guard or route is changed. These are internal engine event counters, not a claim to count external-only MIDI OUT.

The pure synchronized Kotlin mute observer tracks actual state transitions in the existing central style override setter and keyboard gain/mute path. Repeating a setter is not a transition. Mark/export hold its snapshot lock while calling the native snapshot, with one lock order (controls → player); render never acquires the Kotlin lock. All controls default to the existing unmuted state. Its initial timestamp is conservative: if settledAtMarkSeconds is below3, wait and mark again. No SF2 reload/restart is needed.

SAVE REPORT includes PCM_SESSION even without a style. MarkTransport=STOP is logged with the matching session ID. Legacy STYLE and RESOLVER tables are explicitly labeled legacy_ui_map_not_engine_binding, and process counters are labeled process_lifetime. Full session reports include peak/clipping/RMS/frame/time, NOTE_ON arrays, keyboard delta, style-velocity delta, mute masks and per-channel states, changes, FX sends, and stage/role deltas. Formatting runs after copying native state outside its mutex.

## Safety proof / host gates

The source boundary manifest pins the reviewed observer-only production delta. Removing only the three NOTE_ON counter lines, one render observer call and two read-only snapshot methods restores the entire native player source byte-for-byte to163f769. Removing the single pure mute observer restores the entire StyleSequencer byte-for-byte. AudioEngine render, synth scheduling, resolver, gain/limiter, font loading, measured balance, CASM, transport and MIDI OUT sources are otherwise unchanged. Existing JNI methods remain intact; the two new entry points never call ensureEngine/loadFont/GetData/StreamEvent/GetChannel.

- G1: synthetic nonzero buffers prove per-mark max, clipping, RMS, frames, worst frame/generation, note deltas and second-mark reset; buffers remain unchanged.
- G2/G3: compile the same native recorder driver against immutable163f769 and candidate, with the same synthetic decode implementation. Compare actual4096 PCM bytes (not only a checksum), all MIDI/controller/program event trace rows and mapping rows. 78 comparison rows are identical. Mark/export preserve recorder state and add no channel taps.
- G4: unchanged native contracts, 78 existing keyboard/MIDI/release/right-off/readiness/mixer tests, two mute coherence/concurrency tests, and current Phase4 LIVE/REG functional bodies (85 tests plus the original78) pass. Historical source-profile guards are not rewritten or claimed passing. The only edit to the old JVM runner adds the pure controls file to compiler inputs; no old assertion changes.
- Policy C: P1–P8/N1–N14/R1, genuine N10, main1280 opened204/swaps8, nonzero7680 unchanged, named39936 opened102/swaps20, MSB081920 opened25900/swaps8; zero closed/unexpected; original per-case goldens.
- Host overhead: baseline/candidate complete native render in alternating order, seven samples each, 150000 blocks per sample, same mock decode and existing meters. Strict median overhead gate<1% is retained. Raw timings are in host_results and CI artifacts. It is an estimate on HOST MOCK DECODE, not a device callback measurement. Local measurements were0.525% and0.247%; the latter uses final byte-comparison fixture.

## CI / decision

The new R2-0 workflow is restricted to the new branch. It checks exact parent163f769, source observer boundaries, protected baseline tree and unchanged old guards/workflows/contracts. Host gates (including strict overhead) precede the SDK-pinned two-ABI Android debug build and APK/provenance verification. The existing U0/U1 branch and main/Phase4 branches are untouched. Official SDK identity guards remain unchanged.

GO for an isolated diagnostic APK if local and CI host/build gates pass. This APK is needed to collect device evidence. Claude G5 (<1% Redmi Pad callback overhead), real LIVE/REG/RELEASE/RIGHT OFF/E343 regression and audio attribution remain UNVERIFIED until device testing; no final G5 or release GO is asserted. Full historical regression CI remains BLOCKED by baseline-profile compatibility. Follow R20_PCM_SESSION_DEVICE_TEST.md. Do not call a contaminated A1/A2 pair valid or attribute clipping to drums without the audit's acceptance checks.
