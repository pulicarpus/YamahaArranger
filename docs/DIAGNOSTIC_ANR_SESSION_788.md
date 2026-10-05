# #788: diagnostic lock isolation and authoritative Part Presence state

Baseline: #787, `79af5ef7016c658553cc36b933722d5821e8e7d1`, branch `fix/mix-percussion-fidelity-784`.

## Proven source mechanisms

`BassMidiPlayer::noteZoneReport()` in #787 holds the same `mutex_` as render and synchronous UI synth controls while building strings, formatting ROLE_PCM percentiles/levels, and iterating immutable SF2 zones for ROLE_LAYER. Moving the caller to a coroutine worker does not eliminate this lock chain: a slow export worker can still block a UI control waiting on that mutex. The production-function liveness test stalls the real report's numeric formatter. #787 cannot complete render/UI synth operations while that formatter is stalled (exit 5); the patched player completes both before the formatter resumes (exit 0). No Android ANR stack was supplied, so this proves a blocking path, not that every reported device ANR has the same stack. The scalar read-only DSP callback has no lock, allocation, BASS call or I/O; its processing is unchanged.

The Inspector's small Part Presence export used `MainViewModel.drumAuditStyle`, a separate private nullable cache. Playback uses singleton `ArrangerBrain.loadedStyle`. A replacement UI/ViewModel can have an empty diagnostic cache while the singleton still retains a loaded playable style. The old export consequently rejects that session before reaching scheduler/native evidence. Source proves the two state authorities can diverge; device recreation history was not provided. The export now belongs to ArrangerBrain and uses the same loaded-style reference and playing state as playback, with visibility and identity checks across worker dispatch.

## Minimal changes

- `bassmidi_player.cpp/.h`: capture counters, bounded meter windows, resource/readback values and immutable inventory references under the synth mutex. Release it before all report formatting, ROLE_LAYER scans and detailed note-zone formatting. Native resource getters remain protected; no handles are dereferenced by the formatter after unlock. The two drum channels retain only their selected preset's metadata in the snapshot. Production render, DSP, NOTE_ON/OFF, preset/controller/gain and percussion functions are unchanged.
- `ArrangerBrain.kt`: diagnostic export reads the authoritative loaded style; native snapshot and scheduler formatting run on `Dispatchers.Default`. Refuse playback and reject a style change while exporting. No style initialization, sequencing or transition logic changes.
- `MainViewModel.kt`: the small Inspector export delegates directly to ArrangerBrain, without consulting the private drum-audit cache.
- Existing Inspector file save remains on `Dispatchers.IO`; existing STOP requirement and export size bounds remain intact. ROLE_PCM and ROLE_LAYER are retained.

## Regression evidence

- Production native formatter-stall test fails against #787 and passes after the patch. Snapshot output remains identical, including ROLE_LAYER; export sends no MIDI.
- Production ArrangerBrain tests cover loaded style across UI scope replacement, truly unloaded state, current/replaced style identity, playback rejection before JNI, worker-thread execution and style changes during snapshot. Tests call the actual production entry point, not a copied helper.
- A source guard pins all executable #787 code in the four modified production files except the explicitly reviewed diagnostic functions/signature and style-reference visibility annotation. Earlier routing/controller, CASM/ACMP, scheduler, drum and Stage 3 bypass guards remain in force.
- Actual BASSMIDI, both ROLE_PCM and existing compatible percussion enabled, multi-SF2 fixture: #788 versus #787 PCM **byte-identical** and MIDI/controller trace **byte-identical**, 6 taps / 70 notes / 250 events. Synthetic fixtures do not establish device timbre or Yamaha-exact balance.
- Full host/native, JVM, prior baseline trace suite, both Android ABIs and Telegram delivery run in the existing workflow. Telegram action/condition/secrets/error policy are unchanged.

## Device validation

Load Love Song.T547 with the same managed SF2 stack. Play Main D with chord C for long enough to reproduce #787, including ordinary panel interaction. STOP, open Inspector and SAVE PARTS (SMALL). Expect the real style name, scheduler/native part rows, ROLE_PCM and eligible ROLE_LAYER evidence. No gains, controller defaults, mappings, CASM, ACMP, transition/timing or Stage 3 activation changed. Absence of all ANR popups on the actual device remains a device-validation result, not a claim based solely on host tests.
