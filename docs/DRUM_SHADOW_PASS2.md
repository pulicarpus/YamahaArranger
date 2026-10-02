# Shadow Pass 2 — reviewed semantic evidence, diagnostic only

Base: successful build #772, commit `c2bd51c7896d8418e53d681e30bff0a649fb4874`. Production native implementation, JNI, sequencer, CASM/RTR, controller/mixer/gain, scheduler, NOTE_OFF ownership, FONTEX2, NOWAIT, NOTEOFF1 and audio buffer remain unchanged. There is no added NOTE_ON/OFF hook. No Stage 3 backend or dispatch capability exists.

## Audit of the tester export #772

The 47,688-byte report has 1,054 raw source demands, all ABSTAIN/UNKNOWN/no_semantic_authority. Original MSB127/LSB0/rawPC73 is preserved; numbering is CLOSED. The STOP snapshot has input bank128/program73 and actual bank128/program0 on both Rhythm channels. It does not prove any historical per-note dispatch.

Only 58 detailed rows survived; 996 were omitted. The visible rows include six key21 demands but no key16/key31 rows. Absence from this bounded detail is not absence of demand. Both STOP font SHA256 values are null and no normalized bank rows appear: path correlation did not establish a managed-file fingerprint for the active handles. This patch does not guess those bindings or change the loader to obtain them.

## Reviewed data, not executable mappings

`app/src/main/resources/drum_shadow_audit770_v1.tsv` is a versioned registry of three Yamaha semantic targets and six candidate claims from `Yamaha_Audition770_Complete_Analysis.md`. The analysis document SHA256, candidate SF2 SHA256, raw bank/program/source key, velocity region, each complete eligible relation's SHA256, sidecar SHA256 and WAV SHA256 are retained. There is no SoundFont-name selection or conditional PopDrumKit/key remapping in code. Other reviewed identities/fonts can be added as data with a new version and provenance.

- Edge target: both closed-hat candidates remain UNKNOWN. Audible PCM/coverage does not prove edge articulation.
- Pedal Closed target: both source44 candidates remain COMPATIBLE claims. No winner.
- Snare target: both generic-snare candidates remain COMPATIBLE claims. No winner.
- No EXACT claims. Confidence tiers are qualitative audit assessments, not probability scores; numeric confidence is unscored for these records.

Registry schema (pipe-separated, bounded 32 KiB):

`DRUM_SHADOW_EVIDENCE|schema|registryVersion|analysisDocumentSHA256`

`TARGET|id|manufacturer|protocol|MSB|LSB|rawPC|sourceKey|label|family|technique|reference|referenceVersion`

`CANDIDATE|id|targetId|SF2_SHA256|rawBank|rawPC|candidateSourceKey|velocityLow|velocityHigh|classification|confidenceTier|isolatedObservedVelocities|eligibleRelationSHA256s|provenance|metadataProvenance|PCMProvenance`

The parser rejects malformed records, ambiguous target identities and duplicate IDs atomically. The complete-byte registry SHA256/version, target references and all candidate/provenance fields participate in worker cache invalidation, in addition to the #772 style/font/policy/native snapshot dimensions. Identical files are deduplicated by fingerprint for metadata lookup so duplicate paths do not invent duplicate layers.

Imported manual claims are unreviewed supplements. When a reviewed target exists, supplemental claims are classified UNKNOWN and cannot promote that target or force a winner. Engineering proofs cannot be asserted through either data format.

## Gate results are separate from semantic claims

Every candidate is reported, including UNKNOWN, absent fonts and ambiguous alternatives. Fingerprint equality means current managed file bytes match the audited content; it does not prove loaded sample bytes. Audited eligible layer signatures include relation multiplicity, inherited generators/modulators, ranges, tuning/root and sample IDs; a layer mismatch abstains.

Passed gates may include registry authority, matching managed fingerprint, eligible layers, velocity region, matching audited layer signatures and mono/stereo metadata validation. Failed gates include unknown semantic articulation, unreviewed supplemental authority, fingerprint/layer mismatch, untested demand velocity, unproven pitch behavior, choke/open/closed/pedal relationships, ownership, readiness, ambiguity and collisions.

All packaged engineering proofs are false/UNKNOWN. Isolated audition at velocity42/110 is not production readiness, pitch/choke certification or ownership proof. For pedal demands at velocity28/35/36, metadata supports the same eligible layers but those velocities were not auditioned; the export says so. The single-note WAVs are not requested again.

Device Pass 2 therefore remains ABSTAIN for these candidates. A pure test fixture can propose SUBSTITUTE only after supplying all hypothetical engineering proofs and removing ambiguity; even then there is no synth dispatch API. Ambiguous COMPATIBLE candidates retain their semantic classification while selected candidate is null. Group selectedCrossKey=false means no selected route; candidateCrossKeys and each candidate evaluation show the cross-key leads.

## Export steps

1. Install the diagnostic APK. Load the same style and managed SF2 configuration, warm normally, then STOP.
2. SF2/STYLE INSPECTOR → SHADOW DRUM RESOLVER.
3. Leave **Use verified audit #770 evidence (shadow only)** checked. Leave supplemental claims empty and approximation unchecked.
4. EXPORT; send `Downloads/YamahaArranger/YamahaArranger_DrumShadowPass2_<uuid>.txt`.

Output v2 retains three scopes: RAW_STYLE_DEMAND, current STOP_NATIVE_SNAPSHOT, and ACTUAL_RUNTIME_DISPATCH=UNKNOWN. Runtime logical key and synth key are unknown; raw context is not replayed through CASM/overrides. Current STOP state is exported once, not repeated as if it were a per-note observation.

The 48 KiB report places action/classification counts, all target summaries, registry claims, candidate evaluations and their metadata layers before aggregated raw demand details. Repeated demands are grouped with hit counts and first/last tick. Target demand groups are prioritized over unrelated UNKNOWN groups. The footer counts omitted groups/demand notes, candidate evaluations, layers and metadata; omission still does not mean no zone. Complete per-note actual routing would require a separately approved stage; this patch adds no runtime hook.

## Regression and build

The existing 432 host checks, 12 managed-audition scenarios, 46 shadow native checks and #772 JVM suite remain. New registry tests replay eight exact metadata relations from the six #770 sidecars and cover UNKNOWN preservation, both candidate ambiguities, fingerprint/layer mismatch, velocity scope, choke/ownership vetoes, unreviewed claims, atomic validation, cache provenance/version and bounded prioritized export. The actual ViewModel export is verified to call only the existing read-only snapshot getter and leave production state intact.

Workflow comparison compiles both #770 and #772 native sources against identical host mocks and compares production event traces byte-for-byte. Structural guards also check the complete native implementation/JNI against #772. Host p50/p95/p99 remain mock observations, not Android xrun/underrun/heap measurements. The workflow prints JUnit XML counts, builds arm64-v8a/armeabi-v7a, and verifies that the registry data is present in the APK.

STOP after diagnostic APK success. No winner, Stage 3, production cross-key/substitution or backend preparation.
