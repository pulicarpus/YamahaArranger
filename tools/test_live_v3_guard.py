#!/usr/bin/env python3
"""LIVE V3 isolation: only UI, presentation state, tests, docs and the V3 workflow may differ from the spec commit.
Engine, BASSMIDI/Oboe, MIDI OUT, Style/CASM/ACMP, note lifecycle and resolvers stay byte-identical."""
import subprocess, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = 'be75a8babb85b439d1a651cb4cb85978e97b8b3d'
ALLOWED = {
    'app/src/main/java/com/yourapp/ui/LiveControlScreen.kt',
    'app/src/main/java/com/yourapp/ui/LiveV3Model.kt',
    'app/src/main/java/com/yourapp/ui/LiveFader.kt',
    'app/src/main/java/com/yourapp/ui/MainViewModel.kt',
    'tests/cpv/LiveV3Test.kt', 'tools/test_cpv.py', 'tools/test_live_v3_guard.py',
    '.github/workflows/live-v3.yml', 'docs/LIVE_V3_REPORT.md', 'docs/REG_TRANSPOSE_FIX.md',
    # Registration voice recall fix
    'app/src/main/java/com/yourapp/yamahaarranger/registration/RecallPlanner.kt',
    'app/src/main/java/com/yourapp/yamahaarranger/registration/Sf2ExternalMapping.kt',
    'app/src/test/java/com/yourapp/registration/RegistrationVoiceRecallTest.kt', 'tools/test_registration_recall.py',
    # Master Transpose (isolated pitch step only)
    'app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt',
    'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt', 'tests/cpv/MasterTransposeTest.kt',
}
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/'))}
extra = changed - ALLOWED
assert not extra, 'Unapproved or protected file changed: ' + ', '.join(sorted(extra))
def changed_lines(path):
    d = git('diff', '-U0', BASE, '--', path).splitlines()
    return ([l[1:] for l in d if l.startswith('-') and not l.startswith('---')],
            [l[1:] for l in d if l.startswith('+') and not l.startswith('+++')])
# MainViewModel: V3 inventory adapter + the voice mirror helper; removals only of the old selector mapping/recall return.
removed, added = changed_lines('app/src/main/java/com/yourapp/ui/MainViewModel.kt')
for l in removed:
    assert any(k in l for k in ('msb', 'lsb', 'sendProgramChange', 'sendReleaseTime', 'midiOutEnabled && preset.program', 'return applyKeyboardVoice', 'A voice change may restore', '}')), 'Unexpected MainViewModel removal: ' + l
joined = '\n'.join(added)
for word in ('audioEngine', 'sendNoteOn', 'sendNoteOff', 'applyFonts', 'allNotesOff', 'loadStyle(parsed)'):
    assert word not in joined.replace('arrangerBrain.loadStyle', ''), 'MainViewModel addition touches ' + word
# Sequencer / brain: only the Master Transpose pitch step.
for path in ('app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt', 'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt'):
    rem, add = changed_lines(path)
    tokens = ('aster', 'masterApplied', 'val target', 'val base', 'oldOctave', 'active.outputNote=target', 'target==null||target==active.outputNote',
              'ActiveTransposedNote(', 'sourceHeaderPC', 'val note=(transformed', 'private val partPresence', 'setMasterTranspose', 'sequencer = StyleSequencer',
              'keyboardTranspose = semitones', 'Master Transpose', 'isInitialized', '/**', ' * ', '*/', ')', '}', 'fun masterTransposeValue')
    for l in rem + add:
        assert any(t in l for t in tokens), path + ': unexpected change: ' + l
print('LIVE V3 GUARD PASS changed=' + ','.join(sorted(changed)))
