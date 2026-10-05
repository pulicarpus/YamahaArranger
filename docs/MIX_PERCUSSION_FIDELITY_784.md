# Mix and percussion fidelity after device build #784

Baseline: `7274e05f52ec9122d9b63674f795d50be652cbc6` (`test/bank-translation-783`).
Device evidence: all accompaniment present and ACMP working; poor balance and wrong percussion.
This patch preserves that presence correction, CASM/NTR/NTT/RTR, scheduler, bank normalization,
main FONTEX2 table, melodic family routing, AudioEngineManager, keyboard voices and ACMP.
The retired Stage 3 implementation remains excluded from both native ABIs.

## A. Proven mix defects and correction

The production sequencer forced string-part volume and expression to at least 100 at setup.
This overrode softer authored controller values. Panel part-volume operations also invoked an
absolute native-volume setter that reset pan/expression/effect sends; the STYLE bus wrote an
absolute CC11 value, erasing the style expression envelope. A partial voice/mute override froze
unrelated controllers at default values. These are source-proven defects, not judgments about
SF2 loudness or device gain.

The raw style state now remains authoritative. Part CC7 and CC11 controls are relative trims
(default 127 = unity): `round(source * trim / 127)`. STYLE is a separate expression trim, applied
only to accompaniment destinations 8..15. Explicit mute sends zero effective volume; unmute
restores the latest raw state, including tick-zero changes received while muted. Pan/reverb/chorus
are overridden only when explicitly changed. CC11 stays a dedicated expression event and never
resends CC7. The string floor is removed; string preset selection itself is unchanged.

This preserves dynamics, including a source CC11 fade to zero. It does not claim perceptual
loudness equivalence across SF2s. Master gain, native mixer equations and controller decoder
are unchanged. Existing stored registrations containing non-neutral trims remain user trims.

## B. Proven percussion mismatch and bounded correction

Closed numbering fact: Love Song requests Yamaha MSB127/LSB0/rawPC73, PopDrumKit display74.
Missing requested drum programs previously selected a first available kit and retained original
keys without evidence of family/articulation compatibility. For example, keys80/81 in the runtime
legacy font share opaque sample48/root67 and scaleTuning **0** (generator56), with no exclusive
class. This is not evidence of separate muted/open triangles. It is also **not** evidence of a
+13/+14-semitone transposition: generator56 is scaleTuning, not exclusiveClass (generator57).
Coverage or audible PCM cannot establish Yamaha identity.

The independent adapter uses canonical Yamaha family, alternate-group and key-off facts from
[Yamaha PSR-SX900/SX700 Data List](https://data.yamaha.com/files/download/other_assets/3/1279213/psrsx900_sx700_en_dl_a0.pdf),
and the immutable #770 audition evidence. No style name, Love Song mapping or SF2 filename
is a production selector. Four SHA256 donor observations promote prior COMPATIBLE auditions;
UNKNOWN auditions are excluded. These observations are donor evidence, not hardcoded Yamaha
key mappings or kit winners. No candidate is classified EXACT.

Candidate names require corroborating kit context (at least four named drum families), singleton
key zones, all velocity values1..127 covered, neutral root/pitch/correction, no fixed key/velocity,
no unproved custom modulators, finite non-looping samples, no address offsets, validated stereo
links, consistent layers and appropriate exclusive-class evidence. Open/closed/edge hat targets
remain unsupported; pedal requires closed/open kit context and positive exclusive class. Snare
roll/brush/rim and synthetic/electronic articulations are excluded. Generic kick/snare remain
COMPATIBLE family substitutions, not exact Yamaha samples. Unknown or unsafe requests stay legacy.

### Full Love Song evidence replay

The production C++ policy, rather than a duplicate Python scorer, is run against every zone in
all four immutable device exports. Counts are **metadata eligibility, not device activation**.
Runtime availability, preload budget and native first-note readback can reduce activation.

| Destination | Yamaha source key | Hits | Compatible metadata candidate | Donor bank/rawPC/key |
|---|---:|---:|---|---|
| Rhythm1 Ch8 |54 tambourine|60|Colombo MT-32 Tambourine|128/3/54|
| Rhythm1 Ch8 |69 cabasa|40|Colombo Cabasa~1|128/2/69|
| Rhythm1 Ch8 |75 claves|17|MELODI Clave(L)1 + Clave(R)1|62/5/75|
| Rhythm1 Ch8 |80 muted triangle|80|Colombo Mute Triangle|128/2/80|
| Rhythm1 Ch8 |81 open triangle|36|Colombo Open Triangle|128/2/81|
| Rhythm1 Ch8 |82 shaker|280|Colombo Small Shaker|128/2/82|
| Rhythm2 Ch9 |21 pedal closed hat|47|Colombo SC-55_Pedal HiHat, #770 audition|128/2/44|
| Rhythm2 Ch9 |31 snare|48|Colombo SC-55 Tight Snare, #770 audition|128/2/40|
| Rhythm2 Ch9 |33 kick|102|Colombo YamahaStanKick!L|127/24/33|

Total1054: **710 COMPATIBLE metadata hits; 344 legacy/ABSTAIN; EXACT0**.
Rhythm1:513/515 compatible metadata hits; wind chime84 stays legacy.
Rhythm2:197/539 compatible metadata hits. Key16 edge has95 UNKNOWN hits, remains legacy.
Other unproved hats/toms/cymbals/side-stick remain legacy. The PC24 entry is a single kick
candidate, never a kit-level winner chosen by coverage. Cross-key claves58 in MELODI126/35
remain a possible donor; stronger neutral same-key stereo layers75 are selected by this replay.

## Native boundaries and lifecycle

`YAMAHA_COMPATIBLE_PERCUSSION` defaults OFF; this device-test debug APK enables it, release
keeps it OFF. This is a fresh bounded adapter, not reinstatement of the old Stage 3 path.
Only Rhythm1/2 destination8/9 and an explicit matching Yamaha source origin can substitute.
An available native requested program or an already compatible legacy zone remains untouched.

Catalog/fingerprint parsing and at most eight full preset preloads happen during paused import
using existing loaded primary/drum/secondary SF2 handles. No font scan, I/O, preload, allocation
or ranking occurs per note or audio callback. Donor raw bank is translated using the existing
normalization table; neither that table nor the main stream mapping changes.

Each rhythm owns a separate128-channel decode stream. Original source key selects an auxiliary
lane; the donor key selects pitch, preserving independent many-to-one voices. Only the same
rhythm's CC7/10/11/91/93 are mirrored. Existing master attenuation is mirrored without changing
its value. Failed resource/controller/preload checks leave subsequent notes legacy.

FONTEX2 maps each auxiliary lane to drum destinationbank128, canonical MIDI BANK0/DRUMS1.
The complete table and controllers are read back before use. The actual SDK's GetPreset only
returns information after a real note: the first actual style NOTE_ON validates font, physical
bank and program under the existing audio mutex. A mismatch stops the auxiliary note before
render and sends the same request through legacy. There is no warm-up NOTE_ON. Auxiliary PCM
is added after the unchanged primary render using fixed stack chunks; no replacement main handle.

Owner binding includes source, auxiliary stream, borrowed font, generation, bank/program/key,
lane, Yamaha group and accepted/choked state. OFF follows the original owner. Manual orphan OFF
cannot release a style owner. Repeated voices and many-to-one keys remain distinct. Cross-stream
alternate groups preserve Yamaha directional relationships: group64 may stop96, not vice versa.
Native legacy-only choke behavior remains untouched. Unknown relationships are not inferred from
GM key numbers. Resource retirement frees only auxiliary streams before borrowed fonts are freed.

The real scheduler intentionally treats rhythm samples as one-shots with no scheduled NOTE_OFF.
Identical completed owner bindings are compressed; finite sample-duration bounds plus a conservative
FX guard expire bookkeeping without inventing an OFF. Unproved looping/modulated legacy voices
remain pinned until STOP. Tests cover2000+ one-shots/choked pedal hits without owner exhaustion.

## Verification and device procedure

Regression protects16 baseline #784 method bodies (including main font table, bank normalization,
melodic resolver, CASM/transition/NOTE lifecycle; preset/send additions are isolated callouts).
Legacy native/shadow traces remain compared with #770/#772/#773/#774/#776 with the new flag OFF.
The existing full JVM suite plus nine production mix tests runs in CI. Native ON tests cover
both rhythms, repeated/simultaneous and many-to-one notes, layers, choke, transitions, generation
reload, resource/controller failures, first-note readback mismatch and orphan OFF isolation.
Unsafe root/velocity/stereo/modulator/loop/tuning fixtures must abstain.

A separate test uses the actual Linux BASS/BASSMIDI libraries with synthetic SF2 PCM: raw melodic
bank62 normalizes to physicalbank0, cross-key75→58 emits auxiliary PCM with no duplicate production
ON,10000 one-shot strikes do not exhaust owners, and melodic program24 stays bound. This tests
native API behavior, **not tester timbre or Android audio latency**. Both Android ABIs are built.

Device test, same Love Song and the same four managed SF2s:

1. Load all four fonts through the existing roles; leave part volume/expression trims and STYLE
   at127 for authored balance. Leave master and keyboard settings at their established values.
2. Confirm every accompaniment part remains audible and ACMP works. Play Main/Fill/Intro/Ending,
   rapid changes and at least two minutes of continuous rhythm (well beyond32 shaker strikes).
3. Compare percussion family and balance; do not expect exact Yamaha timbre or repaired edge16.
4. STOP and export the existing Part Presence report. `PERCUSSION_FIDELITY` supplies compatibleOns,
   legacyOns, failedOns, overflowDropped and chokes; `COMPATIBLE_ROUTE` includes fingerprint, raw/
   physical bank, donor key, layers and first-real-note verification. Require no unexpected failures
   or overflow. Native main-stream readback still describes legacy binding; it is not the routed
   sample-voice identity. Actual sample-voice IDs remain unavailable in the BASS getter API.

No additional diagnostic APK or repeated audition WAV request is needed. Device listening and
this existing export determine actual activation and balance; metadata710 is not a measured
claim that all710 were played by the adapter.
