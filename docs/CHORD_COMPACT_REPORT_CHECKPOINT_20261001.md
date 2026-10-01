# Focused compact chord report — 2026-10-01

## Baseline and evidence carried forward

Repository `pulicarpus/YamahaArranger`, branch `diag/audio-path-presence`, parent HEAD `7827e4612657b219cbcd176f501a61169915ff61`. Application baseline: successful Build #761, source `29122e0d8707c88d7e4a0aa0b0a1b1b0dd1d45ff`. Continue CHORD_RETARGET_PRESET_CHECKPOINT_20261001.md and the proven #759 diagnostics/fixes. The user cannot transmit the large Inspector and explicitly asked for a small independent CHORD CAPTURE file; no further upload of that Inspector is required.

The supplied AllLog190755 is757,898 bytes /5,230 logical lines, SHA256 `44e0ca8e75be8a2b9e2ee58ba8349e4ece110dab5354ce58eecf962a6292cb3e`. It contains one C → F → G → C cycle after capture was armed a second time19:07:13.073; END19:07:33.330. All435 sampled native notes accepted and matching preset readbacks; ConcertGrand only on ch11, other melodic channels retain BASS/Steel Guitar/t4 strings slow. All mapGen9. Strings CC7 remains100. No dynamic PROGRAM during playback is logged.

At19:07:20.472,23.841,27.336 the existing RTR=1 path replaces three Piano ch11 notes at each change (nine total): C→F60→57,64→65,64→65; F→G57→59,65→62,65→67; G→C59→60,62→64,67→64. No cross-destination CASM selection appears among43 retarget records. This supports a possible fresh Piano attack from replacement notes; the actual preset at those nine exact sends is not proven by sampled AllLog. The full NOTE_PRE/NOTE_POST rows were in the unsent Inspector. No speculative musical fix follows from this evidence.

## Separate small export

Inspector adds **SAVE CHORD (SMALL)**, saving `Downloads/YamahaArranger/YamahaArranger_ChordCapture_YYYYMMDD_HHMMSS.txt`. It obtains only focused style/native chord records. It never calls the zone report, kit coverage, managed SF2 inventory or full Inspector exporter. The Toast shows the file size.

Hard file limit: **48KiB UTF-8**, including headers and omission footer. Style export max16KiB; native max30KiB; combined exporter bounds the entire result again, and the file writer refuses oversize data. Whole records are retained; `exportOmittedRows` and `captureDropped` distinguish export loss from capture overflow. Headers identify name/value shortening. No unbounded report is added to AllLog or permanently printed to UI/logcat.

Capture remains explicit/defaultOFF and expires after60s. Native retains a ch11 baseline plus actual retarget events on any destination. Ordinary ch11 notes/controllers use a bounded32-row,250ms pending context; chord marker flushes only recent rows once and opens a750ms following window. Control attempts on other channels are admitted during that window. Unrelated drums/ordinary other-channel notes are filtered before additional readbacks. At END, unused pending context is discarded. Baseline/context operations use getters only and no MIDI sends. The pending context is temporary diagnostic memory, not a permanent trace.

Style retains CHORD_CHANGE, genuine retarget/select/release decisions, relevant ch11 scheduled notes/offs within750ms, and setup requests in that window. Ordinary scheduled offs outside that window are excluded. The new marker is called only when an armed style chord-change observer returns a nonzero chord ID. Existing algorithm/send order is retained.

Export prioritizes CHORD_CHANGE and ch11, then other parts. Native also prioritizes other-channel Piano or mapping mismatch. A priority group may precede an earlier event in file order: use captured `order` / `m` (native MIDI order) and wall timestamps for chronology, and `chordId`+`id` to join layers. Native `context` attributes preceding/scheduled/control evidence to the short window while preserving the original event chordId separately.

Each retarget keeps source part/section/channel, original/output key, old output, velocity, event/chord IDs and CASM selection. Native NOTE_PRE/POST and OFF_PRE/POST preserve actual requested bank/program/voice, destination bank/program readback, BASS live font/bank/PC/name/file/family, expected mapping/match, initialization, map generation, controllers and send/error. Controls have explicit BANK_MSB/BANK_LSB/PROGRAM labels. BASS preset identity is not per-voice sample or PCM/audibility proof. Unknown fields remain unknown.

## Boundaries and tests

15 native playback functions are text-identical to #761: findMelodicPreset, findDrumPreset, normalizeMelodySf2, preloadCurrentPreset, ensureEngine, setChannelMixer, setChannelExpression, setKeyboardSustain, setKeyboardReleaseTime, render, noteOn, noteOff, send, setChannelPreset, applyFonts. Six sequencer methods remain identical: updateHeldPitch, policyScore, selectPolicy, rootPitchForHeld, applyStyleController, startPlayback. Only diagnostic marker calls are added at chord/no-chord entry. Family gate, resolver, kits, SF2 zone parser, scheduler, CASM behavior, timing, gain, sustain/release and libraries remain unchanged.

Host **353 checks pass**:210 family,59 native/mock,39 observer/capture,30 SF2 and15 drum coverage. New cases cover getter-only marker/export isolation, labelled requests/controls, filtering, pre250ms/post750ms window, stale pending rows, retarget inclusion, ch11 priority, hard byte cap and explicit omissions. Native tests retain the exact MIDI-history comparison from #761. Three additional Kotlin cases cover selective capture, source/event identity, UTF-8 size/whole-record limits, oversized headers, ch11 priority and final48KiB composition; original seven RTR/controller-boundary tests remain. CI targets21 cases (5 CC11,6 drum-profile,10 chord), with the existing workflow unchanged.

Bounded readback/memory work adds diagnostic overhead while armed; it does not claim zero timing cost. No new render work. Local Android/Kotlin toolchain is unavailable; real-SDK compilation and APK success must be confirmed in CI below. Local workspace remains a partial inspection snapshot, not authoritative remote history.

## Android procedure

Load the same fonts/style and wait. Play MainD/full mix with C. Arm CAPTURE CHORD once; hold C → F → G → C about2–3s each. END CAPTURE → STOP → **SAVE CHORD (SMALL)**. Send only `YamahaArranger_ChordCapture_*.txt` and say which change produced Piano. Existing AllLog #761 is retained as evidence; no repeat AllLog or large Inspector upload is required for this step.

## Publication

Candidate source/build identity and successful CI/artifact will be recorded after the build finishes.
