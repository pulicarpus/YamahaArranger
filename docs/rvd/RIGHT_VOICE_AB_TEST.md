# RIGHT Voice A/B diagnostic APK (Redmi Pad)

Diagnostic only. It does NOT fix the muffled sound and changes no production behavior with default settings.
Branch `diag/right-voice-ab` (from `6d48ae4`, baseline R2-3 `c75732f`, no latency experiment).

## What the panel shows (button "RIGHT DIAG", bottom-left of the main screen; non-modal)
Per RIGHT1/2/3:
- **SF2 STATUS** MATCH / MISMATCH / UNVERIFIED. MATCH requires: native selection verified, BASS live preset readback present,
  live font handle == handle of the requested SF2, live bank+program == requested, BASS preset name == catalog name,
  and BASS font name (`BASS_MIDI_FontGetInfo`) == INAM parsed independently from the original SF2 file.
  Anything unreadable is UNVERIFIED (never MATCH). Preset count BASS vs file is shown as information only.
- APP selected voice vs REQUESTED→BASS vs BASS LIVE readback (handle, bank, program, preset name, actual file).
- CONTROLLERS read back from BASS: CC7, CC11, CC91, CC93, CC72(release), pan, font volume, stream MIDI volume.
  CC71/CC74 show `NA` when the BASS build does not expose them (compile-time guarded) - NA is not zero.
- APP STATE (not backend): volume knob, expression sent, reverb, chorus.
- VELOCITY from PSR-E343 (last/min/median/max/n) and velocity actually sent to BASSMIDI (same fields).
- TEST ID A1/A2/B1/B2 derived from the BASS-readback SF2 of the first enabled RIGHT part and the A/B switch.

## RIGHT VELOCITY TEST switch
A: ORIGINAL (default) / B: FIXED 100. Affects only the internal synthesizer NOTE_ON of RIGHT parts, only for notes started after
the switch. MIDI OUT velocity, LEFT, ACMP, style and drums are untouched. Not saved; reset to ORIGINAL on every fresh launch.
Changing it never changes the preset or SF2.

## Procedure
1. Open RIGHT DIAG. Select a RIGHT1 voice from the normal Voice Selector (Colombo). Confirm STATUS MATCH and `actualLabel=COLOMBO`.
2. Play ~20 notes at your normal touch. Note the PSR-E343 min/median/max velocity.
3. A1: ORIGINAL. Play the same phrase. A2: tap FIXED 100, play the same phrase. Judge the sound.
4. Select a Tyros voice for RIGHT1 through the same Voice Selector. Confirm `actualLabel=TYROS` and MATCH. RESET STATS.
5. B1: ORIGINAL, B2: FIXED 100, same phrase.
6. STYLE ON and OFF for each if wanted. Save the screen (screenshots of all four tests) and report which of A1/A2/B1/B2 sound muffled.
Reading: if A2/B2 open up but A1/B1 are dull, velocity is the cause. If all four are dull, velocity is excluded and the shared
audio path/controllers/FX are next. If STATUS is not MATCH, the SF2 selection is the problem.

## Limits
- BASS cannot report the file path of a font handle. Path binding comes from the app's own font table (FontInit handle);
  independent evidence is the INAM + preset name + handle equality.
- CC71/CC74 readback only if the BASS SDK defines the event symbols; otherwise NA.
- Whether BASSMIDI applies SF2 default velocity->filter modulators cannot be read out; the A/B switch measures it indirectly.
- Polling takes the synth mutex for microseconds twice per second only while the panel is open.
