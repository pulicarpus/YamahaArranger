# Voice & Release experiment

Branch: `experiment/universal-sf2-release`. Parent/source checkpoint:
`c75122daf5d1077db55d50d1c261144fde59af4a`.
No merge to READY. Scheduler, CASM, Fill, Intro/Ending, style clock,
ArrangerBrain, style models/parsers and chord handling remain byte-identical to
that checkpoint. Existing style defects/uncertified behavior remain unchanged.

## Implementation

- Native browser inventory is cached from each loaded BASSMIDI font's actual
  `FontGetPresets`/`FontGetPreset` results. Normalized source banks are translated
  back to original SF2 banks. No sample-data `phdr` scanning on refresh and no
  invented GM list or legacy identity-free fallback.
- Each row carries role, session-unique SoundFont ID, source filename, preset
  name, original bank and program. Bank/program duplicates across fonts retain
  separate identities. IDs are never recycled on reload, even if BASS handles are.
- RIGHT1/2/3/LEFT select an exact source through a mapping scoped to one keyboard
  channel. Source handle/native-bank/program are checked with `StreamGetPreset`.
  The style family resolver and ACMP mappings remain separate. Failed selections
  report failure and retain the previous verified route when rollback succeeds;
  unavailable/unverified routes suppress NOTE_ON instead of sounding a fallback.
- The existing four voice cards and paged LCD are retained. LEFT is selectable,
  all SF2 roles and drum banks are available, search and an SF2 filter are added,
  and previous/next navigation reaches every page. A category can be widened to
  all presets. Selection failures are shown explicitly. Missing source identities
  are marked unavailable after reload.
- RELEASE remains the existing parameter/large TEMPO–TRANSPOSE–RELEASE knob.
  UI values are delivered serially from StateFlow off the UI thread. BASSMIDI
  `MIDI_EVENT_RELEASE` receives 0–127 unchanged: 64 is normal, 0 is -64 and 127 is
  +63 relative adjustment. Accepted event/readback is checked for channels 0–2.
  The stored value is replayed after voice changes, SF2 changes and decoder/output
  restarts. LEFT/ACMP and CC64 Sustain are untouched.

BASS documentation:
https://www.un4seen.com/doc/bassmidi/BASS_MIDI_StreamEvent.html
https://www.un4seen.com/doc/bassmidi/BASS_MIDI_FontGetPresets.html

## Routing correction after Android test of 7121a35

Android testing found RIGHT2/RIGHT3 staying on Piano and selecting their voices
changing another part. The confirmed path was: open the RIGHT2/RIGHT3 card, then
choose a category and a preset in the LCD. Both category callbacks assigned
`selectedVoiceLayer = 0`, so the preset request was redirected to RIGHT1.

The browser now owns a typed `KeyboardPart` (RIGHT1=0, RIGHT2=1, RIGHT3=2, LEFT=3).
Category changes only filter the list; the immutable target part travels with the
preset click into ViewModel and the native channel request. Per-part request
versions reject superseded selections under the native-operation lock. Atomic
StateFlow updates retain simultaneous choices for different parts. Successful
callbacks cannot close a newly opened browser for another part. Keyboard preset
changes now send bank/program/drum/release/sound-off only to the selected part;
full keyboard replay occurs only at a font lifecycle boundary. Optional MIDI OUT
mirroring is gated by MIDI OUT and correctly splits the original packed bank.

## PCM diagnostic included in the APK

UTILITY records actual live preset readback for each part, plus the source and
RELEASE value sampled at the first NOTE_ON after a preset/release change. NOTE_ON
and NOTE_OFF counters help distinguish incorrect routing from missing key-up,
including an existing externally enabled Sustain state. This adds bounded
counters/readback only, with no formatting or JNI logging inside NOTE_ON/render.

To test RELEASE on Android:

1. Select a sustained/looped strings or pad preset on the desired RIGHT part.
2. Stop style, open UTILITY and press `RELEASE PCM • RIGHT n`.
3. Save the full log and optionally `SAVE WAV`. The WAV contains three segments
   in order **0, 64, 127**, each with 500 ms hold, 2 seconds after NOTE_OFF and a
   250 ms gap. The probe uses C4, velocity 100, and the actual selected SF2 source.

The three isolated decoders use the already loaded font and verify its exact
native handle/bank/program. They neither send production-stream MIDI nor change
production fonts/controllers. Output is briefly paused and restored through the
existing lifecycle path; the user's RELEASE setting remains in place. The probe
is blocked during style playback and for LEFT, because the RELEASE knob applies
only to RIGHT1/2/3. It is a diagnostic, not a Sustain control.

The log reports held RMS, early/late/full tail RMS, and the last 10 ms block above
-40 dB relative to the held note. `RESPONDS` indicates measured envelope changes.
`NO_HELD_PCM...` or `NO_DISTINCT_TAIL_AT_THIS_KEY_HOLD_WINDOW` explicitly limits the
conclusion to this preset/key/hold/window; neither is claimed as a delivery failure
or universal proof that the SF2 cannot respond. The WAV permits listening to short
release differences that a late-only RMS window would miss. These are actual
Android BASSMIDI decoder samples when run on the device, not microphone or Oboe
speaker-output verification.

## Validation and limits

Run `python3 tools/test_voice_release.py --jvm --regressions --fetch-dependencies`
for native API recorder, retained native routing/percussion/lifecycle/PCM observer
safety tests and actual Kotlin engine/Brain contracts. JVM dependency hashes are
pinned. Run `python3 tools/test_voice_release.py --real` with official Linux SDKs
prepared at `/tmp/bass-linux` and `/tmp/bassmidi-linux` for actual synthesized PCM.
The workflow performs both and builds the original debug configuration for
arm64-v8a and armeabi-v7a with checkpoint-pinned Android SDK bytes.

The four synthetic SF2 fixtures each expose five addresses: 0:24, 1025:24, 127:1,
128:0 and 65535:127, yielding **5/5/5/5 = 20 rows**. Five shared address groups and
15 additional rows are retained. Every row is selected on all four keyboard
channels. Real PCM identifies each source by a distinct sample signature.

Additional routing tests keep four independent sources active together, use a
LEFT note in the actual split range, change RIGHT2 to another preset from RIGHT1's
SF2, verify every other part's source, preserve their held PCM, and check all ACMP
channels 8–15. Recorder tests assert that changing RIGHT2 sends no MIDI to any
other production channel. The very same offline probe shipped in the APK is
checked against real Linux BASSMIDI and cannot alter production state.

Live-stream RELEASE comparisons use a sustained, looped preset with one-second
SF2 release at values 0/64/127. A finite one-shot preset separately checks the case
where samples already end before key-up: controller readback succeeds but all
release tails are silent. That is a sample limitation in that test, not failed
parameter delivery.

The older `tools/test_voice_resolver.py` ran its native functional checks
successfully; its final legacy SHA guard rejects the unchanged checkpoint's
StyleSequencer because it expects an earlier F12 source. This guard was not
weakened, disabled or rewritten. The experiment additionally enforces byte
identity against the requested c75122d checkpoint. No READY certification or
baseline merge is performed.

No managed/device SF2 binaries are present in this workspace. Fixture counts are
not counts for the user's Yamaha/Colombo/Tyros files, and historical inspector
counts are not presented as current runtime measurements. On-device inventory
counts are emitted once per SF2 load (`VOICE INVENTORY`), while UTILITY snapshots
identify the actual source used at selection and NOTE_ON.

Android validation still required for this correction: choose a part, then its
category and preset; confirm all four independent sounds, same/different SF2s and
preset retention; compare the RELEASE knob on sustained voices; run/save the
on-device PCM probe; compare live playback with the previous style checkpoint.
Host PCM does not certify Android speaker audibility, latency/xruns or style
smoothness.

## RELEASE audit follow-up (2026-10-12)

Android reported RELEASE 0 and 127 sounding identical on a Strings preset. The
control path is intact; see [RELEASE_AUDIT_20261012.md](RELEASE_AUDIT_20261012.md).
The probe now adds live delivery, original SF2 envelope metadata, a -60 dB
threshold, the tail with live reverb/chorus sends (including dry signal), and a
single `RELEASE DIAGNOSIS` line. MIDI OUT
now carries CC72 to RIGHT1-3 (PSR-E343 response unverified).
