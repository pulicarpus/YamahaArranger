# Bank readback investigation: device evidence 783

Baseline: `cb4d92b889e9d5a87ac5d287a34f46d661103341`, build 783.
Evidence: `YamahaArranger_PartPresence_20261005_164423.txt` supplied by tester.
Conclusion: **the listed bank differences are correct source-bank translation;
no mapping error was reproduced. No production/runtime code is changed.**
The new APK uses the build-783 rendering implementation; only regression tests,
this explanation and the workflow branch trigger are added. No new diagnostic
feature or diagnostic build is introduced, and no audible improvement is claimed.

## Three distinct bank namespaces

1. Yamaha MIDI destination: Bank Select MSB/LSB (packed MSB*128+LSB) and raw PC.
2. Selected original SF2 identity: `selectedRawBank` / `selectedPC` from original
   phdr and metadata, selected by the unchanged family resolver.
3. Loaded BASSMIDI font source: `liveBank` / `livePC`, from the separate
   `.bassmidi-normalized.sf2` actually passed to `BASS_MIDI_FontInit`.

`normalizeMelodySf2` sorts every melodic bank and assigns unique source bank
numbers 0..126. Only phdr bank fields in the separate output copy are rewritten;
original metadata, preset/instrument/sample relationships, pitch generators and
sample bytes remain unchanged. Source bank numbers are scoped **per font handle**.

Full exported MELODI metadata has original banks `[0,8,62,63,126]`. Their
normalized banks are `[0,1,2,3,4]`. Tyros has melodic bank0, normalized to0.
Therefore MELODI original8 becomes physical bank1 in the *loaded normalized file*.
It is not the original file's bank1, nor MIDI destination bank1.

| Channel | Requested destination (MSB:LSB / rawPC) | Selected original SF2 | Expected loaded source bank / PC | Device readback | Result |
|---|---|---|---|---|---|
| 10 Bass | 8:4 / 17 | MELODI bank8 / PC17 | 1 / 17 | font5, 1 / 17, BASS | Correct |
| 12 Guitar | 8:10 / 1 | MELODI bank8 / PC1 | 1 / 1 | font5, 1 / 1, Steel Guitar | Correct |
| 13 Strings | 8:5 / 49 | Tyros bank0 / PC49 | 0 / 49 | font8, 0 / 49, t4 strings slow | Correct |
| 14 Phrase1 | No request in this capture | initialized=0, selected=-1 | Unknown until configured/played | default font5, 0 / 0 | Not evidence of a Phrase1 mapping |

The report's Guitar request is **1034 = 8:10**, not the earlier MainD header
1040 = 8:16. The observed current request is preserved in the reproduction.
Ch14 has seen=0, beforeTransform=0, bridgeCalls=0, attempts=0, admitted=0. A preset
readback alone does not establish that any style note reached that channel.
A separate test explicitly configures/plays Strings2 on Ch14 and verifies its
Tyros source mapping; that is not claimed as evidence of Ch14 playback in the
uploaded device session. This investigation does not change scheduling.

## Production path and BASSMIDI contract

- `setChannelPreset` stores destination MSB/LSB/program separately from selected
  original SF2 bank/program and source-role index.
- `applyFonts` resolves original raw bank through the **selected font's** bank
  table and puts the resulting bank in `FONTEX2.sbank`, with `spreset=selectedPC`.
- `dbank`, `dbanklsb`, `dpreset` preserve the Yamaha destination. `minchan` selects
  the actual zero-based channel; `numchan=1` limits its route. Selected mappings
  precede generic mappings.
- `StreamSetFonts` uses `count | BASS_MIDI_FONT_EX2`.
- Preload performs the same original-to-normalized source-bank conversion.
- `StreamGetPreset` returns **mapped loaded-font source** identity. It does not
  echo Bank Select controller values. Existing zone diagnostics translate its
  loaded bank back to the original bank before querying original SF2 metadata.

The official [StreamGetPreset documentation](https://www.un4seen.com/doc/#bassmidi/BASS_MIDI_StreamGetPreset.html)
explicitly says the returned program/bank need not match `MIDI_EVENT_PROGRAM` /
`MIDI_EVENT_BANK`, but instead what the soundfont configuration maps them to.
[FONTEX2](https://www.un4seen.com/doc/#bassmidi/BASS_MIDI_FONTEX2.html) distinguishes
source `sbank/spreset` from destination `dbank/dbanklsb/dpreset`.
Readback is preset/font evidence, not actual per-voice sample identity or PCM
quality evidence. A stale/default readback is insufficient for an unplayed role.

## Independent reproduction

Actual production `bassmidi_player.cpp`, real Linux BASS/BASSMIDI SDK, Android/JNI
logging stubs only, four synthetic tone SF2s with the exported preset addresses.
No copied resolver or bank-translation function was used. A linker wrapper
captures only the production stream's creation handle so the SDK's installed
FONTEX2 table and MIDI destination events can be read independently.

`BASS_MIDI_StreamGetFonts` returned these channel-specific installed routes:

```
ch10 sbank=1 spreset=17 dbank=8 dbanklsb=4 dpreset=17
ch12 sbank=1 spreset=1  dbank=8 dbanklsb=10 dpreset=1
ch13 sbank=0 spreset=49 dbank=8 dbanklsb=5 dpreset=49
ch14 sbank=0 spreset=49 dbank=8 dbanklsb=5 dpreset=49 [explicit test note]
```

SDK preset readback matched the expected source font/bank/program after each
note. `FontGetPreset` found the BASS/Steel presets at loaded bank1 and confirmed
that bank8 does not exist in that normalized font. Synthetic tone PCM was
nonzero; this establishes SDK addressing, not the tester's actual samples,
Android audibility, instrument identity or device performance.

Replacing `sbank=1` with `sbank=8` would address an absent loaded source. The SDK
supports first-preset fallback for an individually mapped missing source preset;
forcing raw bank8 is therefore unsafe and can produce the wrong voice despite
NOTE_ON acceptance. A negative control on a separate real SDK decode stream
confirmed this: `sbank=8/spreset=17` accepted the note but read back
`bank=0/PC=0/Yamaha ConcertGrand`, instead of BASS. The production table was
not changed by that negative control. It is not a correction to the device evidence.

The host regression calls the production loader/setter, verifies exact original
and normalized phdr fields for five banks, original-file immutability, all other
bytes unchanged, multi-font table preservation, destination controllers,
StreamGetFonts/StreamGetPreset/FontGetPreset contracts, explicit Ch10/12/13/14
routes, admitted NOTE_ON and unchanged NOTE_OFF. Its SDK boundary is a mock;
the independent real SDK run above establishes the actual API semantics.

## What the uploaded device report establishes

Ch10: 77/77 accepted NOTE_ON; Ch12: 110/110; Ch13: 33/33. Each has zero native
family/map rejections, engine/send failures and preset-map failures, nonzero
current CC7/CC11, and two eligible zones for its last note in original metadata.
This excludes the proposed raw-vs-live bank mismatch as a demonstrated loss.
It does **not** prove actual SF2 PCM audibility for every note/velocity.

Current Ch8/9/11 CC7=0 and scheduler mute counters are separate observations,
possibly reflecting the tester's solo/mute actions. No controller, gain or mute
behavior is changed or inferred as a new root cause.

All `app/src/main` files remain byte-identical to build783, including CASM /
NTR/NTT/RTR, scheduler, ACMP, drum resolver, mixer/controllers, preset routing and
NOTE ownership. This APK cannot be presented as a bank-routing sound repair:
there is no justified production repair from this evidence.
