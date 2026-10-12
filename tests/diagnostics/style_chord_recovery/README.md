# Test-only style/chord recovery probes

Run from a clone containing the candidate source and these probes:

```
python tests/diagnostics/style_chord_recovery/run.py --support-build /path/to/protected-baseline/build --out /tmp/style-chord-proof
```

The support directory must contain the original `sff1-jvm` dependency lock artifacts/test jar and `readiness-engine-host/tests.jar`. Dependencies are hash checked against the unchanged repository lock. These support classes are baseline fixtures; current production Sequencer and Brain override them in the proof jar. Kotlin compiler is the locked JVM dependency, not an arbitrary system compiler. The runner records source-derived virtual tick/bar proofs, actual logger facade concurrency/benchmark tests, keyboard isolation, and explicit retained manual-Fill failure. The separate C Default and T01 host evidence uses actual Dispatchers.Default; it is documented in the recovery report.

`VirtualClock.kt`, `DebugLogDouble.kt`, mock native engine/readiness/MIDI ports and synthetic `Rig.kt` are explicit seams. `Chord.kt` uses the original MIDI serializer with a mock Android port; it records six RTR modes and six part destinations, C–F–G–C and Major–Minor–Seventh. Run before and after with the corresponding Sequencer to compare its captures. None of these mocks is an audio or Yamaha keyboard oracle. No production source references these files; they are outside Gradle application's source sets.

The historical golden captures/guards are untouched. A expected-FILL failure is preserved and explicitly asserted as failure, never promoted to musical PASS: manual Fill followed by a newer Main request can supersede the unadmitted Fill. Positive deferred Fill tests cover the existing application's Auto Fill tail policy only.
