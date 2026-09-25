# MIDI Voyager Pro 5.4.11 — Audio Engine / SoundFont Reverse-Engineering Notes

> Static reverse-engineering note created from the user-supplied APK **MIDI Voyager Pro 5.4.11.apk**.
> This document records findings relevant to YamahaArranger. No YamahaArranger source code was changed by this research.

## 1. Artifact

- APK: `MIDI Voyager Pro 5.4.11.apk`
- SHA-256: `3c5582a0a895457f295547a454fce96abfb4833a6dc75813c1666ee540b464c0`
- `classes.dex`: 3,143,108 bytes
- Bundled default SoundFont: `assets/8Rock11e.sf2` (~3.18 MB)
- Other bundled audio assets include WAV files used by UI/count-in/demo functionality.
- Native audio libraries are present for four ABIs:
  - `libbass.so`
  - `libbassmidi.so`
  - `libbassmix.so`
  - `libbassflac.so`
  - `libbassopus.so`
  - `libbasswv.so`

## 2. Core audio architecture found in the APK

The APK is **BASS/BASSMIDI based**, not FluidSynth.

The DEX contains the Java JNI wrapper `com.un4seen.bass.BASSMIDI`. Static call-site analysis found application code invoking:

- `BASS_MIDI_StreamCreate`
- `BASS_MIDI_StreamEvent`
- `BASS_MIDI_StreamEvents`
- `BASS_MIDI_StreamGetEvent(s)`
- `BASS_MIDI_StreamGetEventsEx`
- `BASS_MIDI_StreamGetFonts`
- `BASS_MIDI_StreamSetFonts`
- `BASS_MIDI_StreamLoadSamples`
- `BASS_MIDI_FontInit`
- `BASS_MIDI_FontLoad`
- `BASS_MIDI_FontGetInfo`
- `BASS_MIDI_FontGetPreset`
- `BASS_MIDI_FontGetPresets`
- `BASS_MIDI_FontSetVolume`
- `BASS_MIDI_FontUnload`
- `BASS_MIDI_FontFree`
- `BASS_MIDI_FontCompact`

The native `libbassmidi.so` exports the corresponding BASSMIDI implementation. It links against BASS channel functions. `libbassmix.so` provides the BASS mixer layer.

### Practical pipeline

```
MIDI file / MIDI IN
      |
      v
MIDI event/program/bank processing in app
      |
      v
BASS_MIDI stream
      |
      +---- BASS_MIDI font/preset resolution
      |
      +---- MIDI events / controllers / program changes
      |
      v
SoundFont samples (SF2/SFZ)
      |
      v
BASS audio/mixer
      |
      v
Android audio output
```

The exact Android output implementation is configurable in the app; embedded strings show **OpenSL ES, AAudio1, AAudio2 and AudioTrack** options. The app also exposes buffer-size/update-period controls.

## 3. SoundFont loading model

The embedded help and release notes establish the following model.

### SF2

- SF2 is the recommended format.
- The default bundled SF2 acts as a fallback SoundFont.
- By default, only samples needed by the current MIDI file are loaded.
- There is also a **Load completely** mode that loads all samples into RAM.
- Multiple SoundFonts can be loaded simultaneously in Pro.

### SFZ

- SFZ is supported.
- SFZ normally represents a single instrument/preset.
- SFZ sounds are not inherently organized as normal 0–127 bank/preset tables.
- The user can explicitly map an SFZ preset to a GM MIDI program.
- If no mapping is configured, the SFZ sound is mapped to bank 0 / preset 0.

### SoundFont collections / merged preset model

The Pro version can:

- choose which banks from each SF2 are active;
- select only particular presets;
- reassign a melodic preset to another MIDI program number;
- configure drum presets separately;
- combine several SoundFonts into one effective preset collection.

This is important: **the MIDI program number arriving from the MIDI file is not necessarily the same numeric preset index inside the original SF2**.

## 4. SoundFont priority

The app uses SoundFont ordering as part of preset resolution.

Documented behavior:

> If a preset number exists in multiple SoundFonts, the preset from the **earlier** SoundFont in the collection is used.

This means the effective instrument table is a **merged/priority-resolved preset map**, not simply "open one SF2 and use its raw bank/program."

The app's Instruments Mixer [P] screen displays the resulting combined preset list after:

- SoundFont order;
- enabled/disabled presets;
- bank filters;
- program reassignment.

## 5. Critical discovery — fallback voice algorithm

The APK contains an explicit embedded description of its fallback behavior.

When the requested instrument/program is **not present in the available SoundFont**, the application:

1. Detects that the requested instrument/program is missing.
2. Identifies the requested instrument's **instrument category**.
3. Uses the **General MIDI (GM) Sound Set** as the semantic reference.
4. Looks for a **similar-sounding instrument/category** that actually exists in the loaded SoundFont.
5. If that fails:
   - for a melody instrument, it falls back to **GM program 0 (Piano)**;
   - for a sound effect or percussion instrument, the track/channel's notes are **not played**.

This is the exact documented behavior embedded in version 5.4.11.

### Important interpretation

The fallback is therefore **semantic/category based**, not simply:

```
missing Yamaha program X
        |
        v
use nearest numeric MIDI program X-1 / X+1
```

The documented logic is closer to:

```
Requested instrument
      |
      +-- exact preset available?
      |       |
      |       +-- yes -> use it
      |
      +-- no
          |
          v
      identify GM instrument category
          |
          v
      find similar category/instrument in loaded SF
          |
          +-- found -> use that preset
          |
          +-- not found
                 |
                 +-- melodic -> Piano (program 0)
                 |
                 +-- SFX/percussion -> suppress notes
```

The APK does not expose unobfuscated source for the exact similarity-scoring routine. The application classes are heavily obfuscated (many app classes appear under short names such as `o.*`). Therefore this note records the **verified behavioral contract**, but does not invent an internal numeric scoring formula.

## 6. Program reassignment is a separate mechanism from fallback

The app also supports explicit preset reassignment.

Example documented by the app:

- An SFZ/SF2 instrument may internally occupy program 0.
- The user can assign its destination MIDI program to a semantically appropriate GM slot, such as Acoustic Guitar.
- Once reassigned, a MIDI program change to that destination program can select that sound.

Therefore there are two distinct concepts:

### A. Explicit mapping

```
source SF preset
      |
      v
user-configured destination MIDI program
```

### B. Automatic fallback

```
MIDI requests missing program
      |
      v
GM category identification
      |
      v
similar available preset
```

YamahaArranger should not confuse these two mechanisms.

## 7. Bank handling

The help documents a special bank convention:

- banks 0–126: melodic
- banks 127 + 128: assumed drum banks

The app explicitly supports fallback from one drum bank to another for XG material. The help says that when an XG System On message is present, the app can use **bank 128 for drumkits if bank 127 is unavailable**.

This is relevant to Yamaha styles because Yamaha/XG drum routing frequently uses bank 127/128 conventions.

## 8. Dynamic MIDI program changes

The APK contains explicit support for MIDI program/bank changes.

Static analysis found BASSMIDI event constants and calls related to:

- `MIDI_EVENT_PROGRAM`
- `MIDI_EVENT_BANK`
- `MIDI_EVENT_BANK_LSB`
- `BASS_MIDI_StreamEvent`

The help also documents that a program change is more than a raw program number: the selected preset may live on another bank, so a bank change can be sent before the program change.

This reinforces a key YamahaArranger rule:

**bank MSB + bank LSB + program must remain an atomic voice identity.**

Do not let a later Program Change overwrite or discard the currently selected Yamaha bank context.

## 9. Audio sample loading / performance behavior

Release notes document two loading modes:

### Normal / demand loading

Only samples required by the current MIDI file are loaded.

Advantages:
- lower RAM use;
- less unnecessary sample loading.

### Complete loading

All samples from all presets are loaded into RAM.

Advantages:
- no additional sample preloading when switching MIDI files.

Disadvantage:
- substantially higher RAM usage.

The app also reports a "Preloading samples..." stage.

This suggests the BASSMIDI layer is doing actual preset/sample dependency preparation rather than blindly loading the entire SF2 for every song.

## 10. Volume/audio mixing model

The app documents multiple volume layers:

1. MIDI channel volume (CC7).
2. Track volume for multi-track MIDI files.
3. Global MIDI-file volume.
4. SoundFont master volume.
5. Individual sample amplitude inside the SoundFont.
6. MIDI note velocity.

It also supports dynamic versus static channel-volume modes.

For YamahaArranger this is important because a correct preset with incorrect CC7/track/master scaling can still sound substantially different from MIDI Voyager.

## 11. Audio latency/buffering

The APK documents:

- buffer size;
- buffer update period;
- low-latency settings for MIDI IN;
- larger buffers for stuttering/dropout situations.

The embedded UI strings identify:

- OpenSL ES;
- AAudio1;
- AAudio2;
- AudioTrack.

The application therefore has a configurable Android audio-output layer below the BASS/BASSMIDI synthesis stage.

## 12. Effects / DSP evidence

The bundled BASS library exports standard BASS FX constants/functions, including:

- chorus;
- compressor;
- distortion;
- echo;
- flanger;
- phaser;
- reverb;
- parametric EQ;
- volume effects.

However, the static APK inspection does **not** by itself prove that every exported BASS FX capability is actively applied to every MIDI playback path. Treat these as available engine capabilities, not as proof of an always-on effect chain.

## 13. What this means for YamahaArranger

The biggest lesson for the current Build 518 problem is the distinction:

```
Yamaha voice identity
    =
    source channel
    + bank MSB
    + bank LSB
    + program
    + voice semantic/category
```

followed by:

```
Voice Resolver
    |
    +-- exact SF2 preset
    |
    +-- explicit/reassigned preset
    |
    +-- semantic GM-category fallback
    |
    +-- melodic fallback -> Piano
    |
    +-- SFX/percussion fallback -> suppress
```

This is much closer to the behavior documented inside MIDI Voyager than treating the raw Yamaha Program Change as a universal GM preset index.

### Specific Build 518 implication

The observed:

```
Strings1 -> PC 1 -> Bright Piano
Strings2 -> PC 2 -> E.Grand Piano
```

behavior is **not** a valid semantic fallback.

If a Yamaha String voice cannot be found in the active SF2, the resolver should search for an available string/ensemble-like preset before falling back to Piano. It should never select E.Piano merely because a raw PC value happened to be 2.

## 14. Recommended YamahaArranger implementation model

Do not modify CASM/NTR/NTT/RTR to solve this.

Keep these layers separate:

```
STY/PRS parser
   |
   +--> CASM policy
   |       |
   |       +--> note transformation/routing
   |
   +--> original voice setup
           |
           +--> source channel
           +--> bank MSB
           +--> bank LSB
           +--> program
           +--> voice name/category
                    |
                    v
               Voice Resolver
                    |
                    v
               BASSMIDI preset
```

The resolver should log at least:

```
VOICE RESOLVE
src=13
name=Strings1
requestedBank=...
requestedProgram=...
exact=...
fallbackCategory=STRINGS
resolvedBank=...
resolvedProgram=...
resolvedPresetName=...
reason=...
```

This will make future Build logs immediately show whether a wrong sound is caused by:

- parser loss of bank/program;
- wrong Yamaha-to-GM interpretation;
- missing SF2 preset;
- incorrect fallback;
- wrong BASSMIDI bank/program event;
- or the actual SF2 content.

## 15. Important limitations of this reverse engineering

- The APK's application classes are obfuscated.
- The public BASS/BASSMIDI native libraries are binary components; this analysis did not modify or reverse-engineer proprietary BASS implementation internals.
- The exact internal similarity-ranking formula for fallback voices was not recovered.
- The fallback **behavior** is directly documented inside the APK and is therefore substantially stronger evidence than an external guess.
- The bundled default SF2 is present, so preset availability can be independently inspected later if needed.
- No YamahaArranger source code was changed during this analysis.

## 16. Bottom line

**MIDI Voyager Pro 5.4.11 does not simply substitute a missing instrument by blindly using the same/nearby GM program number.**

Its documented fallback model is:

**requested instrument -> GM category identification -> similar available instrument -> melodic Piano fallback / suppress SFX-percussion if no suitable fallback exists.**

Its normal preset system additionally supports:

**multiple SF2s + bank/preset filters + preset priority + explicit program reassignment + BASSMIDI bank/program handling.**

This behavior should be used as a reference model for YamahaArranger's future **Voice Resolver**, while preserving the already-working CASM and note-routing pipeline.
