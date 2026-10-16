# R2-4 Drum Punch — diagnostic implementation

Mandatory parent: c75732f28fec58fbfbc7e626bc380b10a00722b8, the R2-3 source
with successful run38833703203 attempt2, not the Kotlin-failed ce68890d build.
Branch: experiment/bassmidi-r2-4-drum-punch. Exact candidate SHA is in CI provenance.
Owner explicitly authorizes a dedicated device-first diagnostic workflow separate
from the historical benchmark gate. Old workflows/guards/assertions are untouched;
historical full CI remains BLOCKED, not passed or certified by this build.

## Technique and boundaries

Default ORIGINAL executes the existing R2-3 RESOLVED path. No optional streams,
DSP or new events are active in ORIGINAL. Host comparisons retain both the old
LEGACY proof (4096 bytes/85 rows) and a direct c75732f RESOLVED differential.

LEVEL+/PUNCH+ isolate two groups per source channel8/9 in four optional BASSMIDI
DECODE streams, reusing the already loaded font handle. They never load another
SF2 or modify its bytes, and never call StreamGetChannel. Each source note is
sent exactly once, preserving its channel, key, velocity and ON/OFF order.
A bounded per-key FIFO retains OFF ownership across kit changes and fallback.
No score/metadata/file I/O, allocation, logging or new lock occurs in render;
setup happens on the existing control/JNI path before notes. Decode is chunked
at512 frames with bounded stack buffers. Existing render locking remains.

Targets are restricted to the reviewed Colombo fingerprint
ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506,
raw bank127, PCs0/8/16, keys35/36 kick and38/40 snare. U1 catalog verifies the
XG namespace and golden PDTA verifies all velocities and no cross-key exclusive
classes for these targets. Other identities use the unchanged parent path.
This is not a universal assumption about all SF2 kits or a ROSITA-name special case.
Installed FONTEX2 and the actual stem preset after NOTE_ON are checked. Resolver
R2-3 functions, SF2 loader/normalizer, Policy C and channel mappings remain intact.
A cleanup hook frees optional streams before the existing font unload.

LEVEL+ requests up to12% additional target amplitude. PUNCH+ starts with that
ceiling and adds up to6% at a kick/snare hit, decaying with a12ms time constant.
These are conservative tested ceilings, not unconditional fixed gains: the
actual current PCM mix including unity stems determines the gain budget every
stereo frame. A common stereo gain avoids changing target pan. Safe budget drops
immediately and recovers over20ms. No gain is applied to parent, bass or hats.
Existing source pitch, note duration, MIDI velocity and output clamp are untouched.
Existing master/channel values are mirrored to optional streams, never boosted.

For finite unity-stem baseline B and requested additional target contribution D,
choose 0<=alpha<=1 so |B+alpha D|<=max(.98,|B|). The roundoff fallback discards
only the added contribution if it would cross max(1,|B|). Existing clipped B is
never globally clamped/limited by R24. This bound is relative to the same isolated
unity mix, not a proof that splitting shared BASS FX preserves R2-3 waveforms.

## Known limits — must compare on Redmi Pad

Separate reverb/chorus states and synth voice pools may differ from the shared
R2-3 stream. No FX parameters are deliberately changed, but FX/voice-pool/CPU
behavior is not certified equivalent in modified modes. Extra decoders increase
CPU; host mock timing and binding tests cannot certify device cost or timbre.
ORIGINAL is the exact control. Failures/unknown identities fall back explicitly;
headroom can reduce gain to zero. No improved sound or reduced clipping claim is
made before matched device O/L/P tests. Main/Phase4/LIVE branches are protected.

## Safety evidence

Minimum tests: exact ORIGINAL resolved PCM/events versus c75732f, existing R23
LEGACY/native binding checks, exact target routing and one ON/OFF per source note,
readback on the decoded stream, kit-transition OFF ownership, unknown/GS fallback,
STOP/drained-job admission/no MIDI OUT, LIVE/REG and original ROSITA205/205 replay,
source-boundary mutations, PDTA key proof, DSP nonfinite and clipping tests with
normal O2 and Android -ffast-math, Kotlin and native two-ABI CI build.

The initial overly coarse block budget correctly blocked all boost in an
already-clipping fixture: 1000 guarded blocks, maxExtra0, 52132 clips unchanged.
It failed the positive-effect assertion; no APK was built from that draft.
The implementation was corrected to stereo-frame source-gain restriction, not
by removing the assertion or permitting extra clipping. Initial failure is in
initial-dsp-failure.log. Final normal/fast-math fixture tests retain all assertions:
104264 clipped samples before and after across both modes, positive effect and
finite output. A controlled clean-budget test distinguishes transient versus level.
The pure-DSP p99 is tens of microseconds/512 frames on host, excluding BASS decode.
Actual CI numbers and key-proof.json are artifacts. Device performance remains open.

SAVE REPORT includes the requested mode, gain ceilings/attack/recovery, actual
budgeted gain, protection count, stem handles/font/PC/readback/fallback, native
failures, routed notes, plus unchanged per-channel PCM_SESSION counters, peak,
RMS, clipping, nonfinite and full R23 identities. Mode controls are STOP-only and
only enabled for RESOLVED. R2-3 sound/resolver remains the control.

API reference: https://www.un4seen.com/doc/bassmidi/BASS_MIDI_StreamEvent.html
The documented DRUM_LEVEL range ends at127 (normal/full); no undocumented values
or fake whole-channel per-key DSP are used. The target PCM isolation is real.

Local test-orchestration note: one superseded run was invalid because the fixture
CLI was strengthened to require a raw-PCM output file while its older runner
was still active (two arguments versus three). That run aborted at fixture
argument admission, not in audio code, and is not counted as PASS. The final
frozen fixture+runner completes successfully and compares all4096 raw PCM bytes,
plus the full event trace, against the exact c75732f source. Raw PCM SHA256:
08b6730a04836813da245808f9c5227ce74513b7deef07a2d0ffad8bec6ea56b.
