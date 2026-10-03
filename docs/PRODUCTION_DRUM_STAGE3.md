# Stage 3 experimental production adapter

Baseline: #776, `2f71950f6343bbfb723994ae6a2373130606a857`.
Device export `b060d33343cbf4156850c468bebbae1df7838a5f648aebc3f92d96a2f19834c5`
contains 1,054 demands, zero safe routes, 1,054 ABSTAIN. Six runtime
gates were UNKNOWN: RAW_ROUTING, NATIVE_LOADED_RESOURCE, RUNTIME_PITCH,
VELOCITY_RESPONSE, OWNER_ADAPTER, CONTROLLER_LANE. Pedal also lacks complete
hat-family semantic closure and a proven shared choke lane; 959 hits have
UNKNOWN source identity. Static coverage/layer/pitch/stereo gates already passed
for the audited compatible candidates. The export omits 397 detail groups / 1,052
detail hits, but no summary rows: omissions cannot prove absent candidates.

## Production path

Experimental is OFF on startup. STOP preflight indexes all managed metadata,
checks the reviewed evidence and every eligible layer, and verifies a private
immutable snapshot SHA256 against inventory. The user explicitly chooses a
resource fingerprint. This restricts resource eligibility; it does not declare
a musical winner between equally compatible samples. Default shadow ranking
continues to abstain on ties. Nothing is selected by font name, coverage or GM key.

Only statically proven non-exclusive candidates enter native preflight. BASSMIDI
opens a dedicated font and decode stream, maps the requested actual bank/PC with
FONTEX2, synchronously preloads the exact candidate key with flags=0, and verifies
the actual font/bank/PC on all 32 physical owner lanes. Pitch bend is set/read
as 8192; the isolated stream has no pitch/transpose producer. Generator/root/
tuning safety remains a separate metadata gate. CC7, pan, CC11, reverb and chorus
mirror the source Rhythm channel; master MIDI volume mirrors the legacy stream.
No production preset/font/controller is replaced. No note is played at preflight.

Velocity response here means the original integer velocity reaches unmodified
native SF2 layers/default modulators, without a gain/velocity substitution. Full
audited layer multisets and velocity ranges must match at every admitted demand
velocity; fixed-velocity generators are rejected. It does **not** assert exact
Yamaha timbre or the same acoustic dynamics. There is still no EXACT candidate.

RAW_ROUTING is a conditional preflight admission: the actual sequencer output
must retain original key/destination/bank/rawPC/velocity, after existing CASM,
masks and overrides. Overrides or changed context abstain at dispatch. A present
native requested kit is conservatively preserved, even if the prepared table
contains a semantic exception. Font-resource epoch or capacity failure also returns to
legacy before a successful candidate ON. Only explicit production activation
uses this adapter; metadata/shadow exports remain read-only.

The immutable per-section address tables use primitive sorted arrays. No SF2
scan, file I/O, score search, font load or logging happens per note. A fixed
logical FIFO records **both** native tokens and legacy markers, separately by
part/source channel/key. This prevents a legacy OFF releasing a subsequent
mapped repeated note. Each admitted native ON has a distinct physical channel,
capturing token/font/stream/generation/bank/preset/key/lane. Thus many source keys
mapping to one candidate key can release in either order. OFF uses its captured
lane, never today's preset/flag/mapping. NOTEOFF1 remains enabled.

Native resource epochs are separate from ordinary legacy mapping generations:
melody preset setup can rebuild FONTEX2 without invalidating a private drum lane.
Font load/unload closes new admission, while held private owners remain pinned.

Section boundaries, Ending and STOP release captured experimental owners;
they do not add global note-off at Main/Fill transitions. Resource replacement
and explicit OFF clear private streams/fonts before deleting snapshots. Private
PCM is added to the existing render output in fixed-size blocks; no render heap
allocation. Decode/controller/OFF transport failures stop further admission and silence the
private mix; captured OFF bindings remain retryable until cleanup. Capacity is four
resources with 32 simultaneous owners each; excess demand uses legacy.

## Current subset and limits

With either audited snare fingerprint as the explicit resource scope and passing
native preflight, the full fixture admits **48 / 1,054 conditional rows**:

* Colombo `9e4686e4aa71…`, bank128/rawPC2: Yamaha original key31 → source40.
* MELODI `e8c7356159c2…`, bank126/rawPC35: Yamaha original key31 → source91.

These are alternatives, never two automatically ranked winners. Both retain all
actual requested velocities (69,73,90,100,101,105,110,120,121,122). The remaining
1,006 rows retain legacy: 959 unknown identities and 47 pedal hits with unresolved
hat/choke relationships. Key16 is UNKNOWN; key21 is COMPATIBLE evidence but
not production-admitted. Arbitrary semantic names and loaded PCM do not upgrade
UNKNOWN. Exclusive-class and hi-hat substitutions are intentionally blocked;
the existing legacy choke behavior is preserved. Future family admission needs
a complete reviewed family and an actual shared native choke implementation.

The 48/1,006 result is a complete fixture/adapter-contract expectation, **not a
claim that the new APK ran on the tester device**. Device native preflight must
confirm loaded resource/preset/readiness, and actual accepted/released/rejected
counters measure dispatch. Android PCM quality/xruns are not inferred from host
tests or offline shadow traces.

## Tester steps

1. Install this APK, load the same managed SF2/style and STOP.
2. SF2/STYLE INSPECTOR → SHADOW DRUM RESOLVER → Stage 3 section.
3. LOAD MANAGED SF2. Choose Colombo or MELODI by displayed fingerprint. The
   default is no selected resource, with experimental OFF.
4. PREPARE & ENABLE SAFE SUBSET. Expect conditionalSafeRows=48, ABSTAIN=1006
   only if all native gates pass. Failed candidates stay legacy.
5. Play Main/Fill/Intro/Ending, including rapid changes. STOP; STATUS shows native
   accepted/released/rejected/decodeFailures/owners. owners must return to zero.
6. Reopen the Inspector after playing. The installed preflight summary persists;
   STATUS/EXPORT combine it with fresh native counters using read-only getters.
   EXPORT saves `ProductionDrumPreflight` including admitted mappings, failed
   candidate gates and latest counters. OFF clears resources and restores legacy.

No repeat audition WAV is required. Existing shadow/metadata exports still never
activate production or send NOTE_ON. The new explicitly named preparation action
enables the experimental subset; normal style NOTE_ON triggers sound afterward.

## Regression evidence

Old legacy NOTE/preset/preload functions remain byte-protected; melody/CASM/
brain/decoder guards remain intact. Blanket shadow-era prohibition on all native
edits is replaced by function protection plus flag-OFF trace comparison against
the prior checkpoints, because Stage 3 explicitly authorizes production hooks.
New native tests execute the actual BassMidiPlayer with mock BASS: isolated
preflight, captured lane OFF, repeated/simultaneous/many-to-one owners, original
velocity bytes, Rhythm1/2, CC11, native-first, stale resources, preload/preset/ON
failure, bounded capacity, render failure and cleanup. JVM tests use the real
scheduler seam for source identities, legacy markers, overrides and section
boundaries; the full four-font/1,054-demand fixture retains every unresolved gate.


Full-suite expansion at the first Stage 3 CI run exposed five stale tests in
unchanged CASM/chord production sources (previous workflow ran a filtered suite).
The fixture now explicitly uses HIGH KEY=11 for an unwrapped root transpose,
NTR=ROOT FIXED for BYPASS, nearest-octave bass, and an impossible narrow note
limit for suppression. Added checks retain octave folding and HIGH KEY=0 wrap.
The C/E/G/A ambiguous chord test preserves the existing MIN7-over-SIX priority
(Am7/C). No CASM, chord analyzer, ACMP behavior or timing was modified to fix
these tests. Production-source hashes remain protected.
