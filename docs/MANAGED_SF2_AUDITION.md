# Diagnostic managed-SF2 audition

Extends checkpoint 3239798 / APK #769. No semantic winner, fallback, remapping or production playback changes.

Inspector → STOP → **MANAGED SF2 AUDITION (WAV + SIDECAR)** → **SCAN FINGERPRINTS** → explicitly select the managed source. Selection does not activate an arranger font. The full SHA256 is displayed. Enter source SF2 bank, **raw** PC (display PC minus one), source key and velocity. Click **SAVE AUDITION WAV + SIDECAR** once per requested probe.

Initial six probes from the metadata audit:

| SF2 | Bank | raw PC | Source key | Velocity | Target |
|---|---:|---:|---:|---:|---|
| ColomboGMGS2_BM.sf2 | 128 | 2 | 42 | 42 | Yamaha16, edge identity UNKNOWN |
| MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2 | 126 | 35 | 42 | 42 | Yamaha16, edge identity UNKNOWN |
| ColomboGMGS2_BM.sf2 | 128 | 2 | 44 | 42 | Yamaha21 pedal |
| MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2 | 126 | 35 | 44 | 42 | Yamaha21 pedal |
| ColomboGMGS2_BM.sf2 | 128 | 2 | 40 | 110 | Yamaha31 snare |
| MELODI YAMAHA PSR-SX700 & SX900 PRIME.sf2 | 126 | 35 | 91 | 110 | Yamaha31 snare |

These are instructions for tester input, not defaults or mappings in application code.

Each click writes a ZIP in Downloads/YamahaArranger containing one two-second, stereo 44.1kHz PCM16 WAV and its TXT sidecar. Send the six ZIPs back for analysis. Nothing is automatically auditioned on scan, export, selection or parameter entry.

The diagnostic copies the selected managed source into an app-private unique temporary SF2; scans and hashes that snapshot; checks the requested fingerprint, bank, rawPC, source key and velocity; retains ALL coeligible relation metadata; and only then calls an independent JNI backend. The native module owns a new font and decode stream, verifies actual font/bank/preset before and after the single NOTE_ON, and frees stream then font via RAII on every path. Kotlin deletes its snapshot in finally. No production stream/player/font handle is accepted by that backend, and no AudioEngineManager, ArrangerBrain or MIDI input calls occur.

Large-font fingerprint scans/copies may take time and require temporary storage as large as the selected SF2. Fingerprint changes, missing zones or incomplete metadata reject before synth calls; rescan after file changes. Existing audio engine initialization is required; the diagnostic does not initialize/free/reconfigure the shared BASS device. Isolated stream defaults and font volume are recorded; PCM is not normalized. Existing production CC/mixer state is not copied or changed.

Sidecar includes SHA256/managed identity/bytes, request, actual native font/bank/PC, key/velocity, render peak/RMS/clipping, every eligible preset/instrument/zone/sample relation and inherited PG/IG generators/modulators, plus WAV SHA256. Sample voice identity is still unavailable: eligibility is metadata, not proof that BASS sounded every eligible sample. Classifications remain UNKNOWN.

The compact metadata legend is corrected: SF2 op46=keynum,47=velocity,56=scaleTuning,57=exclusiveClass,58=overridingRootKey. Raw generator values and scanner NOTE_ON-free behavior are unchanged.

Regression uses the actual independent C++ renderer with BASS spies and production state sentinels, including mapping/program/preset/note/decode/font/stream failures and cleanup. JVM tests exercise fingerprint changes, invalid source/key/ranges, all coeligible layers, playback-start refusal, native failure cleanup and ZIP pairing; ViewModel boundary tests verify zero production audio/MIDI/arranger interactions and unchanged state. Existing lifecycle/controller/resolver regression guards remain in the workflow.
