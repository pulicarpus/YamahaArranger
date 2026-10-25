#!/usr/bin/env python3
"""Style Editor V2 isolation. Baseline 8dc434b (reload-crash fix on top of the MIDI OUT voice cache, CI green with APK).
Production may change in: the profile model (StyleOverrideStore.kt, StyleVoiceEditorOps.kt), the editor UI/view-model, the sequencer lines that apply an octave
shift and a drum note map, and the Brain pass-throughs. Parser, CASM transformer, scheduler/clock, MIDI OUT router, P1 ordered I/O and native code stay byte-identical."""
import re, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = '8dc434b'
SEQ = 'app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt'
ALLOWED_PREFIX = ('app/src/main/java/com/yourapp/ui/', 'tests/cpv/', 'tests/rtdiag/', 'docs/', 'tools/test_', '.github/workflows/style-voice-editor.yml', 'app/src/main/assets/')
ALLOWED = {SEQ, 'app/src/main/java/com/yourapp/yamahaarranger/style/StyleOverrideStore.kt', 'app/src/main/java/com/yourapp/yamahaarranger/style/StyleVoiceEditorOps.kt',
           'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt', 'app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt'}
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = {p for p in changed if p not in ALLOWED and not p.startswith(ALLOWED_PREFIX)}
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
frozen = ['app/src/main/cpp', 'app/src/main/java/com/yourapp/midi', 'tests/fixtures']
assert not git('diff', BASE, '--', *frozen).strip(), 'native / MIDI OUT router / fixtures changed'
# Sequencer: the scheduler, clock, CASM and P1 note I/O vocabulary must not appear in added lines; removals limited to the lines this feature replaces.
FORBIDDEN_ADDED = re.compile(r'tickDeadlineNanos|TempoClock|waitForClockChange|selectPolicy\(|musicalClockLock|delay\(|OrderedNoteIo|issued\.|noteLifecycleLock|applyVoices')
REMOVED_OK = ('val note=(transformed+(channelOverride?.transpose?:0)).coerceIn(0,127)', 'stageIo(destinationChannel,note){', 'audioEngine.noteOnStyleChannel(destinationChannel,note,velocity/127f,',
              'midiInputManager.sendNoteOn(destinationChannel,note,velocity)', 'val base=if(rootOnly) rootPitchForHeld(active,chord) else CasmNoteTransformer.transform(active.sourceNote,chord,active.policy)',
              'val sourceHeaderPC:Int, val sourceTick:Long, val onId:Long', 'activeTransposedNotes[key]=ActiveTransposedNote(sourceChannel,s.event.note,destinationChannel,note,velocity,policy,policyList,s.part.name,section.name,diagBank,s.part.program,absoluteTick,diagId)',
              '"transformAsMelody=${!isDrumPart} nativeRhythmChannel=${destinationChannel == 8 || destinationChannel == 9} transpose=${channelOverride?.transpose ?: 0}")',
              'voicePins.clear(); sectionVoicePins.clear(); installedPins.clear(); partExternal.clear(); sectionExternal.clear()', '    )')
for line in git('diff', '-U0', BASE, '--', SEQ).splitlines():
    if line.startswith(('+++', '---', '@@', 'diff', 'index')): continue
    body = line[1:]
    if line.startswith('-'):
        assert any(m in body for m in REMOVED_OK), 'StyleSequencer: out-of-scope removal: ' + body.strip()[:140]
    elif line.startswith('+'):
        assert not FORBIDDEN_ADDED.search(body), 'StyleSequencer: forbidden vocabulary added: ' + body.strip()[:140]
for path, what in [('app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt', 'AudioEngineManager'), ('app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt', 'ArrangerBrain')]:
    removed = [l for l in git('diff', '-U0', BASE, '--', path).splitlines() if l.startswith('-') and not l.startswith('---')]
    assert not removed, what + ': only additions allowed'
print('EDITOR V2 GUARD PASS changed=' + ','.join(sorted(changed)))
