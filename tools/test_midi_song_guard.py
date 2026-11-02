#!/usr/bin/env python3
"""MIDI SONG V1 isolation guard (branch feature/midi-song-v1). Baseline 0cdcb48 (E343 SHORT_GATE checkpoint + DATA CONTROL UI).
The Song is additive: its own package, its own BASSMIDI stream, a raw MIDI OUT path, a SONG page. The Style engine, CASM/NTT, the
style parser, the MIDI OUT router, MIDI IN/split, the E343 experiment and the DATA CONTROL UI stay byte-identical."""
import re, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = '0cdcb48'
J = 'app/src/main/java/com/yourapp/'; C = 'app/src/main/cpp/'
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
ADDITIVE = [J + 'yamahaarranger/audio/AudioEngineManager.kt', J + 'yamahaarranger/audio/NativeAudioBridge.kt', J + 'midi/MidiInputManager.kt',
            C + 'bassmidi_player.h', C + 'bassmidi_player.cpp', C + 'audio_engine.h', C + 'audio_engine.cpp', C + 'native_lib.cpp', 'tests/mocks/bassmidi.h', J + 'ui/ContentResolverProvider.kt']
ALLOWED = set(ADDITIVE) | {J + 'ui/MainViewModel.kt', J + 'ui/SxMainScreen.kt', J + 'ui/SongPanel.kt', J + 'ui/SongPage.kt', J + 'ui/SongMixerPanel.kt',
           'tests/song_native_test.cpp', 'tests/song_xg_real_bass_test.cpp', 'tools/midi_fixture_gen.py', 'tests/rtdiag/RecorderRtTest.kt', 'tools/check_recorded_midi.py', 'tests/rtdiag/FullArrangerRecorderRtTest.kt', J + 'yamahaarranger/audio/VirtualKeyRouter.kt'}
PREFIX = (J + 'yamahaarranger/song/', 'tests/cpv/', 'tests/fixtures/midi/', 'tools/test_', 'tools/ui_preview/', 'diagnostics/', '.github/workflows/', 'docs/')
extra = {p for p in changed if p not in ALLOWED and not p.startswith(PREFIX)}
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
FROZEN = [J + 'yamahaarranger/arranger', J + 'arranger', J + 'style', J + 'yamahaarranger/style', J + 'chord', J + 'yamahaarranger/registration',
          J + 'midi/MidiMixerOutput.kt', C + 'style_parser.cpp', C + 'style_parser.h', C + 'smf_reader.cpp', C + 'smf_reader.h',
          J + 'ui/RotaryDataKnob.kt', J + 'ui/RotaryKnobMath.kt', J + 'ui/E343ExperimentPanel.kt', J + 'ui/RightVoiceDiagOverlay.kt',
          'tests/fixtures/englishwaltz2', 'tests/fixtures/r23', 'tests/fixtures/hardrock2', '.github/workflows/e343-quick-apk.yml']
assert not git('diff', BASE, '--', *FROZEN).strip(), 'Style engine / CASM / style parser / router / E343 / DATA CONTROL changed'
def removed(f): return [l[1:].strip() for l in git('diff', '-U0', BASE, '--', f).splitlines() if l.startswith('-') and not l.startswith('---')]
AEM_ALLOWED = ['fun allNotesOff() { DebugLog.traceAudio("ALL_NOTES_OFF"); bridge.nativeAllNotesOff() }']   # V1C: + noteTap?.allStopped()
for f in ADDITIVE:
    r = [x for x in removed(f) if not (f.endswith('AudioEngineManager.kt') and x in AEM_ALLOWED)]
    assert not r, f + ': only additions allowed, removed ' + (r[0][:100] if r else '')
VM_ALLOWED = {'fun onStartStop() = arrangerBrain.startStop()',
              # V1C: the on-screen keyboard sink calls the VirtualKeyRouter (lower area = USB ACMP/LEFT policy) instead of the RIGHT path directly
              'if (picked != null) applySplit(picked) else base.noteOn(pitch, velocity127)',
              'override fun noteOff(pitch: Int) { if (!splitByKey.onRelease(com.yourapp.yamahaarranger.audio.KeySource.TOUCH, pitch)) base.noteOff(pitch) }',
              'override fun releaseAll() = base.releaseAll()'}
assert set(removed(J + 'ui/MainViewModel.kt')) <= VM_ALLOWED, removed(J + 'ui/MainViewModel.kt')
assert sorted(removed(J + 'ui/SxMainScreen.kt')) in ([], sorted(['onInspector: () -> Unit', 'onInspector = onOpenInspector'])), removed(J + 'ui/SxMainScreen.kt')
# Song invariants
song = ''.join((ROOT / J / 'yamahaarranger/song' / n).read_text() for n in ['Smf.kt', 'SongTimeline.kt', 'SongPlayer.kt', 'SongOutputs.kt'])
assert 'allNotesOff' not in song and 'allNotesOff' not in (ROOT / J / 'ui/SongPage.kt').read_text(), 'the Song never sends All Notes Off'
mim = (ROOT / J / 'midi/MidiInputManager.kt').read_text()
assert '!in 0x80..0xEF) return false' in mim[mim.index('fun sendSongBytes'):], 'MIDI OUT Song path must refuse SysEx/system bytes'
cpp = (ROOT / C / 'bassmidi_player.cpp').read_text()
assert 'data[i] >= 0xF0' in cpp[cpp.index('bool BassMidiPlayer::songEvents'):], 'native Song path must refuse SysEx/system bytes'
# V1A.1: setup SysEx only through songSysEx, whitelisted, Song stream only; MIDI OUT never takes SysEx
sx = cpp[cpp.index('bool BassMidiPlayer::songSysEx'):cpp.index('void BassMidiPlayer::songReset')]
assert "data[0] != 0xF0 || data[length - 1] != 0xF7" in sx and 'data[3] == 0x4C' in sx and 'BASS_MIDI_StreamEvents(songStream_' in sx and 'stream_,' not in sx.replace('songStream_', ''), 'songSysEx whitelist / Song stream only'
outs = (ROOT / J / 'yamahaarranger/song/SongOutputs.kt').read_text()
ext = outs[outs.index('class ExternalSongOutput'):outs.index('fun songOutputsFor')]
assert 'override val acceptsSysEx get() = false' in ext and '< 0xF0) sink(bytes)' in ext, 'MIDI OUT output never forwards SysEx'
pl = (ROOT / J / 'yamahaarranger/song/SongPlayer.kt').read_text()
assert 'for (o in outputs) if (o.acceptsSysEx) { o.send(e.bytes); applyOverridesLocked(o) }' in pl, 'player sends SysEx only to SysEx-capable outputs'
assert 'm.dbank = 0;' in cpp[cpp.index('bool BassMidiPlayer::ensureSongStreamLocked'):] and 'm.dbank = -1' not in cpp, 'Song font map keeps bank numbers (dbank offset 0)'
assert 'BASS_ATTRIB_MIDI_SRC, static_cast<float>(interpolation_)' in cpp[cpp.index('bool BassMidiPlayer::ensureSongStreamLocked'):], 'Song stream SRC = main stream SRC'
assert cpp.count('renderSong(out,numFrames);') == 1 and 'BASS_MIDI_StreamEvents(stream_' not in cpp, 'Song events go only to songStream_'
vm = (ROOT / J / 'ui/MainViewModel.kt').read_text()
assert 'fun onStartStop() { if (!arrangerBrain.state.value.isPlaying) stopSongForStyle(); arrangerBrain.startStop() }' in vm, 'Song/Style policy'
assert 'songNote = "STYLE is playing: stop the Style first' in vm, 'PLAY SONG refused while the Style runs'
# V1A.2 SONG MIXER: voice pins live on songStream_ only; the keyboard/Style font table (applyFonts) never sees them
pin = cpp[cpp.index('int BassMidiPlayer::songSetChannelVoice'):cpp.index('std::string BassMidiPlayer::songChannelReport')]
assert 'songStream_' in pin and 'applyFonts' not in pin and 'BASS_MIDI_StreamEvent(stream_' not in pin and 'BASS_MIDI_StreamSetFonts(stream_' not in pin, 'Song voice pins: Song stream only'
apply = cpp[cpp.index('bool BassMidiPlayer::applyFonts'):cpp.index('bool BassMidiPlayer::publishedFontTableIntact')]
assert 'songPins_' not in apply, 'the keyboard/Style font table never includes Song pins'
mix = (ROOT / J / 'yamahaarranger/song/SongMixer.kt').read_text()
assert 'val voice = if (internal) o.voice else null' in mix, 'voice overrides never reach MIDI OUT'
assert 'allNotesOff' not in mix and '120' not in mix.split('fun transform')[1].split('fun state')[0], 'sustain filter never uses All Notes/Sound Off'
# V1B MIDI RECORDER: one tap at the engine, after the native call; MIDI OUT / Style router never tapped; brain unchanged (FROZEN arranger)
aem = (ROOT / J / 'yamahaarranger/audio/AudioEngineManager.kt').read_text()
on_ch = aem[aem.index('    fun noteOnChannel('):aem.index('    fun noteOnStyleChannel(')]
assert on_ch.index('bridge.nativeSfNoteOnChannel(channel, midiNote, velocity01)') < on_ch.index('noteTap?.noteOn(channel, midiNote, velocity01)'), 'tap after the native NOTE ON'
sty = aem[aem.index('    fun noteOnStyleChannel('):aem.index('    fun armChordDiagnostic(')]
assert sty.index('bridge.nativeSfNoteOnStyleChannel(') < sty.index('if (soundFontLoaded) noteTap?.noteOn(channel, note, velocity)'), 'V1C: Style tap after the native NOTE ON'
assert 'bridge.nativeAllNotesOff(); noteTap?.allStopped()' in aem, 'V1C: All Notes Off closes the recorded notes'
for f in [J + 'midi/MidiInputManager.kt', J + 'midi/MidiMixerOutput.kt', J + 'yamahaarranger/arranger/StyleSequencer.kt']:
    assert 'noteTap' not in (ROOT / f).read_text(), f + ' must not record (no double recording through MIDI OUT / Style)'
print('MIDI SONG GUARD PASS changed=' + ','.join(sorted(changed)))
