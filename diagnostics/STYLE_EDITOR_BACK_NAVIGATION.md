# Style Editor navigation fix (← BACK, Android Back, unsaved-changes prompt) — experiment, NOT a merge

Branch `experiment/style-editor-back`, baseline `aa7d478` (MIDI OUT octave correction; APK built from the identical app code at `a80960d`). No merge to main, no PR.

## Audit of the existing navigation (before the patch)
* The editor is a fullscreen overlay: `StyleVoiceEditorOverlay` (composed in `MainActivity` after the main screen) shown while `StyleVoiceEditorHost.visible`; opened from the "STYLE VOICES" button (`SxMainScreen`), closed by `StyleVoiceEditorHost.close()`.
* The only exit was a **CLOSE** button at the far RIGHT end of a single header row that also holds the title, six chips, SAVE PROFILE, RESET ALL, MIDI OUT OCTAVE and REFRESH. With the added buttons the row is wider than the screen on small displays, so CLOSE can be pushed off-screen.
* No `BackHandler` existed in the overlay, so Android Back fell through to the main screen's handler (`SxMainScreen`: back only when not on HOME) or to the default (leaves/closes the activity). The overlay did not consume Back.
* Unsaved state: `StyleEditorUiState.dirty` (working profile ≠ saved profile) already exists; nothing asked before closing, and edits stay live in the working copy.

## Patch (smallest possible)
* **← BACK** is now the first element of the header row (left, cannot be pushed off). It and CLOSE call the same function.
* **Android Back button/gesture**: a `BackHandler` installed by the Android-only overlay (the shared screen stays free of Android APIs so the desktop preview still builds) calls the same function. It is composed after the main screen, so it takes precedence over the main-screen handler; it returns to the main screen and never exits the app.
* **Shared rule** (`StyleEditorNavigation`, pure Kotlin): profile clean → close at once; unsaved changes → dialog **SAVE / DISCARD / CANCEL**.
  * SAVE: existing `editorSave`; the page closes only if the profile was really written (or removed because it became empty); on "Not saved…"/"No Style…" it stays and shows the reason.
  * DISCARD: new `MainViewModel.editorDiscard`: the saved profile (or none) becomes the working profile again and is applied through the existing `executeOverrideChanges` diff (the same path every edit and RESET uses); then the page closes.
  * CANCEL / tap outside: stays in the editor.
* Nested dialogs (Replace, MIDI OUT OCTAVE, RESET confirm) keep their own dismissal; Back closes them first.
* Not touched (guard `tools/test_editor_back_guard.py`): MIDI OUT router, octave correction, profile store/codec, Style Sequencer, CASM, Brain, audio engine/BASSMIDI, native code. Saved profiles are never modified by leaving the editor; DISCARD only drops unsaved edits.

## Tests (host)
`tests/cpv/StyleEditorNavigationTest.kt` (5): clean → close now, dirty → ask; save closes only after a real write (messages checked against `editorSave`'s texts); ← BACK is the first header element and BACK/CLOSE share one callback; Back handler installed by the overlay only; Save/Discard/Cancel present; discard reuses the existing diff and makes no MIDI/engine/sequencer calls. CPV 290 OK. The Compose layout itself is compiled in CI (`compileDebugKotlin`) but not rendered on the host.

## Test on the Redmi Pad
1. Open the Style Editor: **← BACK** is at the top-left; press it → main screen, the app stays open.
2. Open again, press the Android Back button/gesture → same result (not exit).
3. Change a voice or MIDI OUT octave, press ← BACK or Android Back → Unsaved changes prompt. SAVE → closes, reopen: the change is there. DISCARD → closes, reopen: the change is gone and the Style sounds as last saved. CANCEL → you stay in the editor with the change intact.
4. With no changes, ← BACK closes immediately. While a Replace/MIDI OUT OCTAVE dialog is open, Back closes that dialog first.
5. Style playback, MIDI OUT and saved settings must be unchanged by opening and leaving the editor.
