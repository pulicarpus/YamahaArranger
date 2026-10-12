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

## Validation and limits

Run `python3 tools/test_voice_release.py --jvm --fetch-dependencies` for the native
API recorder and actual Kotlin engine/Brain contracts. JVM dependency hashes are
pinned. Run `python3 tools/test_voice_release.py --real` with official Linux SDKs
prepared at `/tmp/bass-linux` and `/tmp/bassmidi-linux` for actual synthesized PCM.
The experiment workflow performs both and builds the original debug configuration
for arm64-v8a and armeabi-v7a with checkpoint-pinned Android SDK bytes.

The four synthetic SF2 fixtures each declare five addresses: 0:24, 1025:24,
127:1, 128:0 and 65535:127. The API recorder exposes 5/5/5/5 = 20 rows, with five
shared address groups and 15 additional rows retained. Native BASSMIDI can expose
fewer usable addresses from the unnormalized dedicated drum fixture; its measured
inventory is reported separately in CI logs. Every returned row is tested on each
of the four keyboard channels. Real PCM checks identify the selected source by
375/750/1125/1500 Hz signatures even when names and addresses match.

RELEASE PCM comparisons use an intentionally looped, sustained SF2 preset with
one-second SF2 release, values 0/64/127, 500 ms key hold and measured dry PCM
500–1000 ms after NOTE_OFF. A separate finite one-shot preset checks the case in
which samples already end before key-up: controller send/readback succeeds but
all release tails are silent. That is a preset/sample limitation, not a failed
parameter-delivery claim.

The older `tools/test_voice_resolver.py` ran its native functional checks
successfully; its final legacy SHA guard rejects the unchanged checkpoint's
StyleSequencer because it expects an earlier F12 source. This guard was not
weakened or rewritten. The experiment instead checks byte identity against the
requested c75122d checkpoint. This is not READY certification.

No managed/device SF2 binaries are present in this workspace. Fixture counts are
not counts for the user's Yamaha/Colombo/Tyros files, and historical inspector
counts are not presented as current runtime measurements. On-device inventory
counts are emitted once per SF2 load (`VOICE INVENTORY`), and selection logs show
source ID, raw/native bank, program and readback verification.

Android validation still required: import/load the actual SF2 stack; select each
source in RIGHT1/2/3 and LEFT (ACMP off for LEFT audition); confirm the matching
source sound; compare RELEASE 0/64/127 on held looped strings/pads; repeat after
voice/SF2 change and engine restart; then compare style playback with the prior
checkpoint. No Android audibility, latency/xrun, or style smoothness is claimed
from host PCM.
