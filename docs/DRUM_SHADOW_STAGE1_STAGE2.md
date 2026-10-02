# Drum resolver Stage 1–2: shadow preparation/export only

Base: GitHub `diag/sf2-semantic-inventory`, commit `376ebe2884e743f1f32ae0c56201ed5a342805bb`, successful diagnostic build #770. Stage 3–6, production substitution, candidate winners and auxiliary lanes are not implemented.

## Tester steps

1. Install this diagnostic APK. Use the current style and managed SF2 configuration. Warm the style as usual, then STOP.
2. Open SF2/STYLE INSPECTOR → SHADOW DRUM RESOLVER → EXPORT.
3. For the first export, leave the optional evidence field empty and the approximation checkbox unchecked. There are no font/style/kit/key candidate defaults. UNKNOWN/ABSTAIN is expected where semantic authority is absent; native address preservation is PASSTHROUGH with identity UNKNOWN.
4. Send the TXT saved in `Downloads/YamahaArranger/YamahaArranger_DrumShadow_<uuid>.txt`. Output is capped at 48 KiB with omitted row and layer counters. An omitted row is not evidence of a missing zone. Large styles exceeding the plan budget fail explicitly instead of silently exporting a partial plan.

No new WAVs are needed for this stage. Export never auditions, activates a font or sends a MIDI event.

Optional reviewed semantic claims may be pasted as 11 pipe-separated columns, one per line:

`MSB|LSB|rawPC|targetKey|SF2_SHA256|bank|PC|sourceKey|class|confidence|provenance`

Bank and PC in a candidate are the original inventory bank and raw PC, not a normalized/virtual bank. Classes are EXACT, COMPATIBLE, APPROXIMATION, UNKNOWN, INCOMPATIBLE. The importer cannot assert pitch, choke, ownership or resource-ready engineering proofs. A claim can therefore be displayed with its classification while its action remains ABSTAIN. Confidence is the supplied evidence confidence, not a score inferred from names or coverage. Approximation requires an explicit shadow policy; it still does not authorize audio dispatch. Contradictory negative evidence vetoes a positive claim for the same binding. Equally ranked distinct claims abstain.

## Three separate facts

- Original request: immutable parsed style demand before the existing production bank coercion. Includes section/part, source and Rhythm channels, raw MSB/LSB/PC, source key, velocity, tick and dynamic CC0/CC32/PC context. Part headers are fallback metadata; setup events update the context in stable track order.
- Current production: one read-only native snapshot at STOP, including native input request, effective bank/program, BASS getter verification, font mapping generation and handle-scoped raw/virtual bindings. This is a current state observation, **not a historical route for every style note**. Native input may already have been coerced upstream; the raw original is kept separately.
- Shadow proposal: pure offline classification, binding, complete eligible sample layer bundle and failed gates. CASM/overrides/masks are not executed by the diagnostic. Runtime logical/synth key and actual per-note sample identity remain UNKNOWN. Source demands include all available Intro/Main/Fill/Break/Ending sections, including dynamic bank/program events. They are not predictions of which muted/conditional parts will actually play. Native source paths are correlated with the SHA256 of the current managed file bytes; this does not prove that an already loaded handle contains those exact bytes if the file was replaced without reloading. BASS verification proves the active font/bank/program tuple, not a loaded-sample fingerprint.

The inventory reads all managed SF2 relations (including those outside the old compact semantic index), hashes the complete bytes and keeps raw bank/PC, preset/instrument/sample relationships, effective key/velocity ranges, original/override root, correction, generators/modulators, stereo header pairing and exclusive-class metadata. Names, eligible zones and audible PCM confer no semantic identity. No audit candidate mapping is embedded.

## Runtime boundary and cache

All I/O, hashing, scanning, sorting, indexing, compilation and export happen only in the explicit STOP worker operation. There are no added NOTE_ON/NOTE_OFF hooks, logs, allocations, locks, loading, FONTEX2 rebuilds or route lookups. The production native implementation preceding the appended getter is checked byte-for-byte against #770; the sequencer/parser/arranger policy files are hash guarded.

The snapshot takes the existing synth mutex briefly for getters and copies; formatting occurs after release. It does not initialize the synth. Preparation verifies STOP and the unchanged style before reading the snapshot and before returning the export. If playback or style changes, the result is discarded; there is no reset or compensating controller event.

The worker cache retains at most one immutable plan. Invalidation includes the complete parsed-style digest (including payload/CASM/setup changes), demand digest, SF2 SHA256/location set, evidence/provenance/proofs, policy version and complete native snapshot/generation/handles. A replaced or removed font cannot reuse its old binding. Export rescans to verify current file fingerprints; it does not trust a stale metadata cache. Limits: 32,768 demanded notes, 200,000 global relations and 65,536 stored layer references. Over-budget preparation fails closed. Retained rows/layer references and compile time are exported; actual heap usage is not claimed.

All overlapping velocity layers are retained. A proposed stereo route requires reciprocal left/right sample headers with equal rate/frame count/original key. Unreviewed pitch/root/tuning/modulators, unsafe or unknown choke/open/closed/pedal relationships, unproven ownership, missing resources and many-to-one key collisions veto proposed substitution. Engineering proofs in pure test fixtures test the decision model; the device importer cannot supply them. SUBSTITUTE means hypothetical proposal only; there is no dispatch method in the planner.

## Verification and performance limits

JVM regressions cover all 16 requested categories, explicit native preservation, negative semantic evidence, velocity layers, stereo, raw/virtual/handle isolation, identity contexts, cache invalidation and actual ViewModel read-only boundaries. Host native tests compare full NOTE_ON/OFF/controller traces with/without snapshot observations, checking unchanged fonts, mappings, NOWAIT preload state, mixer and CC11. The workflow independently compiles the exact #770 native source and the patched source with the same host mocks, compares output event traces byte-for-byte and prints NOTE_ON p50/p95/p99.

Host timing is a mock-synth wall-clock observation, not proof of Android realtime performance. Added NOTE hooks/allocations/mutexes are zero by the unchanged code boundary. Android BASS audio xruns/underruns, real mutex contention and measured heap usage are NOT MEASURED. No audio buffer changes are made. Both Android ABI builds and APK build run in GitHub Actions. Stop after the diagnostic APK; analyze the tester export before any production resolver work.
