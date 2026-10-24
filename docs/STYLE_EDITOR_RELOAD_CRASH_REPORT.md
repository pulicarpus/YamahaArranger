# P0 — Style Editor reload hang / force close

Branch `fix/style-editor-reload-crash`, baseline `27d8bc6fa1994a11a1b4eb2a71fa0c9093e087b0` (`experiment/midi-out-voice-cache`, CI run 39878114234 / APK run 39874241927 green). Not merged, no PR.

## Symptom (device, Redmi Pad)
An unedited Style loads and plays. After a Voice is replaced with an SF2 preset in the Style Editor and saved, loading that Style again takes very long and the app force-closes.

## Root cause (reproduced on host)
`MainViewModel.onStyleFilePicked` runs on the main thread (`viewModelScope`) and, inside `audioEngine.readiness.operations.withLock { … }`, calls `restoreStyleOverrides()` whenever a saved profile exists.
`restoreStyleOverrides()` → `loadedPdtaByFile()` → `AudioEngineManager.loadedSoundFontPresets()`, which is `runBlocking { readiness.operations.withLock { … } }`.
`operations` is a coroutine `Mutex` (not reentrant), so the main thread waits forever for a lock it already holds → ANR → force close.
A Style without a profile skips `restoreStyleOverrides()` (record is null), which is why it loads fine. Saving an edit creates the profile; every later load of that Style hangs.

Evidence (tests/cpv/StyleReloadFlowTest.kt, production engine/store/restorer/sequencer, same lock order as `onStyleFilePicked`):
* before the fix: `conditionB…`, `missingPreset…`, `aChangedSoundFont…`, `repeatedReloads…` fail with "Style reload did not finish in 5000 ms (deadlock)" (stack: `runBlocking … joinBlocking`), `rootCause…` shows `loadedSoundFontPresets()` under the lock never returns, and the static guard fails because `loadedPdtaByFile` used the locking listing; condition A (no profile) passes;
* after the fix: all pass (255 CPV/integration tests OK).

## Fix
* `AudioEngineManager.soundFontPdtaByFile()`: reads the cached native preset snapshot without the lock (same as `findLoadedPreset`, which the restore already used). `MainViewModel.loadedPdtaByFile()` uses it.
* The restore logic moved unchanged into the pure `StyleOverrideRestorer.restore(...)` (StyleOverrideStore.kt) so it is testable; the view model passes lock-free readers and the sequencer pin calls.
* `onStyleFilePicked` runs the restore in `Dispatchers.Default` (native pinning no longer on the main thread) and logs `STYLE LOAD stage=restoreOverrides begin/end ms=…` into the debug log.
* Nothing else changed: parser, CASM, scheduler, StyleSequencer, the MIDI OUT cache and native code are byte-identical (`tools/test_reload_fix_guard.py`). No profile is ever deleted automatically.

## Tests added (host)
Condition A without profile; B with a saved SF2 + section + external profile inside the operation lock (pins reach the native layer: ch10 → font 2, 0:48); the hang itself; lock-free readers under the lock; missing preset (STALE, not applied, file untouched); changed SoundFont content / renamed preset (STALE); damaged / truncated / wrong-CRC profile (Style loads without overrides, file kept, not writable); unreadable file is quarantined on write, not deleted; 60 repeated reloads stay stable and bounded; static guard on the view model.

## Redmi Pad checks
1. Load the Style that crashed (profile already saved): it must load; Voice shows the saved SF2 voice; Main/Fill/Main plays.
2. Reload it 5×, switch to another Style and back.
3. Open the Style Editor, change another Voice, save, reload.
4. Rename or remove the SF2 used by the profile and reload: the entry must show STALE and the Style still loads.
5. If anything still hangs, copy the debug log: look for `STYLE LOAD stage=restoreOverrides begin` without a matching `end`.
