# Managed SF2 semantic inventory: diagnostic-only

Parent GitHub checkpoint:413673743436e185ab3daa1448c4a43e509e360c (source/APK#767 eb8c411). Branch:diag/sf2-semantic-inventory. Numbering CLOSED: Yamaha127:0/raw73/DataList74 PopDrumKit. No fallback/remap/winner implementation.

## Device steps
1. Install this diagnostic APK. Put all SF2 files to audit in the existing YamahaArranger/SF2 managed folder; subfolders and .SF2 uppercase are included. Existing storage permission is needed. Unloaded fonts are scanned too.
2. STOP arranger, stop keyboard playing and any existing audition, and wait for loading/imports to finish. SF2 metadata scanning itself neither starts nor stops playback. It reads files and does not install/cache/activate fonts.
3. Open SF2 / STYLE INSPECTOR. Press EXPORT SF2 METADATA. Wait for the saved filename toast. The compact text is at Downloads/YamahaArranger/YamahaArranger_SemanticInventory_<timestamp>.txt and at most48KiB UTF-8.
4. Also press FULL METADATA (ZIP) when compact omissions or opaque names require more detail. This scans again and writes Downloads/YamahaArranger/YamahaArranger_SF2MetadataFull_<timestamp>.zip. ZIP contains semantic_compact.txt and font_N.txt, each associated with source name/stable identity and final original-file SHA256. No PCM/sample audio is included.
5. Send compact export back to Cloud; send full ZIP when feasible so omitted cross-key relations are available. Do not change mapping based on export leads.

No warm style playback is needed for this file-only scanner. Folder discovery failures and per-file unreadable/invalid metadata are explicit. An empty export is not a successful compatibility finding.

## Evidence contract
All managed fonts are independently read, including inactive presets. All presets/instruments/bags/sample headers and preset→instrument→instrument-zone→sample relations are visited. Global/local generators and modulators are inherited separately at preset and instrument levels. Local mods replace identical global mod source/destination/amount-source/transform identities, including zero amounts.

Full metadata includes P/I/S/PB/IB/Z records: raw bank/program and displayPC=raw+1, preset/instrument/sample names/IDs, bag indices, effective key/velocity intersections, sample frames/loops/rate/type/link/original root/correction, override root, all explicit generator operators and custom modulators. Unreferenced instruments/sample headers are retained. PG and IG stay separate; engine/default modulators are not enumerated. Structural eligibility is not actual BASS sample voice ID, rendered pitch, PCM audibility or musical identity.

Whole-file SHA256 includes sdta and trailing bytes, read in64KiB blocks. PCM is never retained. Metadata table storage is bounded32MiB per font; relation expansion limit200000 is explicit as incomplete/UNKNOWN, not omitted/missing coverage. Full export is streamed ZIP; it does not construct a whole full-report String.

Compact indexes targets21 Hi-Hat Pedal Closed PD,16 Hi-Hat Edge10PD,31 Snare4PD. It searches source keys0–127 in all presets/fonts. Name family/articulation hints, same-key eligibility, drum banks/names are independent diagnostic leads, not selector scores. Opaque other-key drum zones are considered; non-drum/off-key/opaque relations remain available in full output. At most32 leads per target/font are retained before the byte cap; per-font and per-target budgets reduce domination by one font. examinedCandidates/exported/omitted and file/discovery omissions are explicit. Unknown or omitted data must never be treated as missing zones.

Categories EXACT/COMPATIBLE/APPROXIMATION/INCOMPATIBLE/UNKNOWN are preserved. This scanner assigns UNKNOWN to candidate identities, even when names match Yamaha target. Exact/compatible/approximation require additional independent semantic evidence; numeric program or higher coverage does not choose a winner. No automatic rejection based on name/spectrum.

## Regression boundary
No changes to native/JNI, AudioEngineManager, SoundFontInspector existing parser, voice resolver, StyleSequencer, CASM, controllers, note ownership, preload, mapping, audio rendering or existing tests. ContentResolverProvider adds separate read-only discovery; MainViewModel adds a STOP-checked file-export method. Inspector adds compact/full export buttons and streaming MediaStore save with pending/error cleanup. Existing workflow adds new diagnostic branch and13 targeted JVM cases.

432 baseline C++ checks ran locally and passed. JVM scanner and real ViewModel boundary tests run in the APK workflow, alongside existing34 selected JVM guards.13 new cases cover cross-key/non-drum preset scanning, generator/root/range/mod inheritance, entire-file digest, corrupt/unreadable inputs, compact UTF-8 bound/omissions, full ZIP relations, recursive discovery and missing-root no-creation, plus no interactions with AudioEngineManager/ArrangerBrain/MidiInputManager/StyleRepository for compact/full/error/playing-refusal paths. CI result/commit/build recorded after completion.

STOP after successful diagnostic APK. Next work is analysis of device exports; fallback or per-note remapping needs separate approval.
