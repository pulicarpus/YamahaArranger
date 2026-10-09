# F12 diagnostic retention, no musical optimization

Initial HEAD: `c0ba8c8733902a2d184ad53f941de1b422ce6414`, branch `fix/mix-percussion-fidelity-784`. #811 remains the audio baseline; #813 diagnostic source is `406608c83c790e9ed81ae5a0120cdaa3cb2080a9`. The previously untracked capture-decision document is preserved separately.

Both uploaded device captures were read. SHA256: 055107 `aab0fe7f62a199830c63abfe2a05f9c7c2de441cbaa7bbd358c4406435ca93f3`; 060641 `7201522350a42c2d7c6e8878123193bad99544033c08cb148e4d800a2df06089`. The second has one chord change, zero CASM-apply calls, dispatch max 61.686 ms, lifecycle wait 24.339 ms, render wait 4.637 ms, callback gap 40.337 ms. Its STYLE/NATIVE overwrite counts are 182/1248. These are independent maxima, not a causal timeline; neither old capture can retrospectively recover overwritten markers.

## Retention

Only two production diagnostic files change: `ChordChangeDiagnostic.kt` and `chord_change_diagnostic.h`. Existing UI/export calls remain unchanged. All musical source files, MIDI arguments/order, mutex scopes, scheduler, presets, mixer, CASM, F03/F04, SF2 and Stage 3 policy remain identical to #813.

- STYLE: separate append-only 64 marker slots for setter/chord/section/JNI bracket. Native: 32 chord-marker slots. Regular dispatch/render cannot overwrite them. Full priority pools count dropped new markers; no silent replacement.
- Separate focus pools preserve the first four qualifying slow samples **per kind** during the first second after the first chord (STYLE starts at setter entry, before lifecycle snapshot; native starts at its chord marker). STYLE 32 slots, native 36 slots. One busy kind cannot consume another kind's allowance. Excess samples increment slowDrop; protected focus rows are not overwritten. Initial setter/snapshot rows may carry prior chord context; align by time, not context label alone.
- Existing recent rings retain 64 rows each and their independent overwrite/publication-drop counters. Aggregates remain whole-window maxima/counts. Focus pools do not claim complete timing coverage or capture every slow operation. They deliberately retain the first action, not later actions in a long session.
- Fixed row payload: STYLE 5120 bytes, native 3168 bytes (including existing recent arrays); counters, runtime atomic wrappers and formatting storage are additional. Native recorder sizeof is statically capped at 4096 bytes; Kotlin primitive atomic-array payload is 5248 bytes plus a fixed number of scalar wrappers/fields. All native arrays are allocated before callback execution. New callback work uses existing lock-free 32-bit atomics only; no logging, heap allocation, IO or new mutex. Formatting and sorting occur at export, outside realtime.

## Correlation/export

Existing 48-KiB UTF-8 whole-line bound remains. Export places correlation status/pairs and priority markers before aggregate/focus/recent timing rows, then legacy records. It reports omissions rather than expanding the file. Priority overflow, missing/unmatched markers, running capture, absent focus rows in either domain, or timing export truncation yield CORRELATION_INCOMPLETE. CORRELATION_READY means usable retained clock pairs plus focus observations, **not complete coverage, proven audio continuity or certified root cause**. SlowDrop and regular loss remain visible even when clock correlation is ready.

For matched STYLE JNI start K, call duration d and native marker N, signed modulo-2^32 native-minus-STYLE offset lies in [N-K-d-2, N-K+2] microseconds, allowing quantization. Per-pair uncertainty is d+4 microseconds. Epochs are not equated. The modulo representation has a 71.58-minute wrap period; capture lasts at most 60 seconds. Drift is unverified and each pair is exported separately. Missing pairs never receive fabricated offsets.

## Verification

Deterministic native and Kotlin tests flood 5000 events after a chord. Priority marker and original slow wait survive; recent overwrite and focus/priority drop counters are checked exactly. Tests cover overflow, disabled/stopped/stale tokens, concurrent ARM/END, actual mutex semantics, matched/missing/overflow correlation, modulo wrap, and bounded export with 10000 legacy lines. Kotlin suite: 5 PASS; native/source integrity controls: PASS. Existing strict #787 historical guard: PASS. Exact diagnostic source overlay updated for the reviewed observer bytes only; original historical/golden/production-source manifests are not changed or relaxed.

F04 differential: baseline 83/candidate 90 PASS, BaroqueAir1/Unplugged2/LoveSong and existing protected scenarios, no event delta beyond the established F04 allowlist. Real Linux synth fixture (70 notes, 250 MIDI events): #811, #813 OFF/ON, retention OFF/ON all PCM/MIDI byte-identical. PCM SHA256 `225cad88a11de258795f7e3cc3d62967bdb5aa404cbf76e5fe6a2846b52d033d`; MIDI SHA256 `1001bca015567776c3b9cd740f63dabc388d6cb946a6b016ae956329bfd08851`. This is synthetic Linux evidence, not Android/keyboard timing certification.

Full resolver/native/source regression runner PASS; S6 4 PASS, S7 11 PASS on clean retry, S6/S7/S8 12 PASS, S6/S7/S8/P0 13 PASS. Initial host S7 run reported Mockito invalid-class instrumentation while host builds overlapped; retry passed without changing test assertions. Causation of that transient host failure is not certified. Android CI results will be appended before completion. Original corpus 503 rerun remains BLOCKED because the original ZIP is unavailable. No claim that the gap is fixed.

## Redmi Pad test / STOP

Use the same style/SF2/tempo/input as #811/#813. Settle Main B with C. CAPTURE CHORD, change **C to F once**, wait briefly for the audible gap, then END CAPTURE → STOP → SAVE CHORD (SMALL). Send that single TXT and describe whether drum also stopped. Keep the capture short and perform no later chord changes; focus pools preserve the first action. If capture itself worsens timing, report it. STOP after APK succeeds; await device evidence before any musical optimization.
