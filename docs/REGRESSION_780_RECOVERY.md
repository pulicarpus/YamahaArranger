# Build 780 production regression recovery

Device report: whole style is empty and ACMP does not work. Host mock traces did not prove full Android playback equivalence; previous OFF claims were insufficient.

## Source comparison, builds 776 through 780

- 776 / 2f71950: known working production baseline, offline/shadow diagnostics only.
- 777 / 7c919e9: first runtime integration. Added unconditional clearProductionDrum at font/style loads, owner interception in scheduled NOTE_OFF, section-boundary flush, native private resources, controller mirroring and private PCM render mixing. OFF therefore did not remove all runtime changes. These are the regression-bearing changes isolated by this recovery.
- 778 / 0c0d8e8: changes native resource/generation validation; corrected stale test expectations (no CASM/chord implementation changes).
- 779 / ab3e92d: private transport failure/mute handling.
- 780 / 31356a4: cached preflight report/UI reopening; retains preceding runtime changes.

This is a source comparison, not a device bisect. The exact causal instruction behind the empty style/ACMP symptom remains unproven without device playback. No speculative chord/CASM fix is made.

## Recovery

Restore 10 production integration files byte-for-byte to 776: native player/header/JNI/engine header, AudioEngineManager, NativeAudioBridge, StyleSequencer, MainViewModel, Inspector dialog and provider. Remove Stage 3 native methods, private lanes, render mixing, lifecycle clearing, scheduled note interception and boundary owner flush entirely. No flag, preference or UI action can activate Stage 3. Inactive Stage 3 code/tests are preserved under quarantine/stage3, outside app/native/test compilation. Pure metadata, audition and shadow diagnostics remain available. GenericDrumResolver resource scoping remains offline only.

A 15-file SHA256 guard additionally covers CASM, chord analyzer, ArrangerBrain, decoder and native audio renderer, verified against an independent CI checkout of 776. Native mock trace is compared against 770/772/773/774/776, and the complete JVM suite runs. Corrected CASM/chord test expectations from 778 remain; their production implementations are unchanged.

This APK requires real-device confirmation of complete style and ACMP playback. Stage 3 remains quarantined until that test succeeds and the runtime failure is understood.
