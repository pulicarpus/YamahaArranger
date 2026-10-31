#!/usr/bin/env python3
"""Style Editor navigation fix isolation. Baseline aa7d478 (MIDI OUT octave correction, compile check green; CI APK a80960d).
Only the editor screen/overlay (BACK button, Back handling, Save/Discard/Cancel dialog), one view-model function (editorDiscard) and a pure navigation rule may change.
MIDI OUT router, octave correction, profile store/codec, Style Sequencer, CASM, Brain, audio engine/BASSMIDI and native code stay byte-identical."""
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = 'aa7d478'
J = 'app/src/main/java/com/yourapp/'
ALLOWED = {J + 'ui/StyleVoiceEditorScreen.kt', J + 'ui/StyleVoiceEditorOverlay.kt', J + 'ui/MainViewModel.kt', J + 'yamahaarranger/style/StyleEditorNavigation.kt'}
ALLOWED_PREFIX = ('tests/cpv/', 'tests/rtdiag/', 'docs/', 'diagnostics/', 'tools/test_', '.github/workflows/')
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/')) and '__pycache__' not in p}
extra = {p for p in changed if p not in ALLOWED and not p.startswith(ALLOWED_PREFIX)}
assert not extra, 'Unapproved file changed: ' + ', '.join(sorted(extra))
frozen = ['app/src/main/cpp', J + 'midi', J + 'arranger', J + 'yamahaarranger/arranger', J + 'yamahaarranger/audio', J + 'yamahaarranger/browser', J + 'yamahaarranger/registration',
          J + 'yamahaarranger/style/StyleOverrideStore.kt', J + 'yamahaarranger/style/StyleVoiceEditorOps.kt', J + 'yamahaarranger/style/Sf2KeyCoverage.kt', 'tests/fixtures']
assert not git('diff', BASE, '--', *frozen).strip(), 'MIDI OUT / octave correction / profile store / sequencer / audio / native changed'
vm = [l for l in git('diff', '-U0', BASE, '--', J + 'ui/MainViewModel.kt').splitlines() if l.startswith('-') and not l.startswith('---')]
assert not vm, 'MainViewModel: only additions allowed'
OK_REMOVED = ('Header(inventory, loaded, output, actions, onRefresh, onClose)', 'initialReplaceFor: Pair<String, Int>? = null', 'onClose = StyleVoiceEditorHost::close, actions = actions)',
              'Surface(color = SvBlack', 'Row(Modifier.fillMaxWidth().height(52.dp)')
for path in (J + 'ui/StyleVoiceEditorScreen.kt', J + 'ui/StyleVoiceEditorOverlay.kt'):
    for l in git('diff', '-U0', BASE, '--', path).splitlines():
        if l.startswith('-') and not l.startswith('---'):
            assert any(m in l for m in OK_REMOVED), path + ': out-of-scope removal: ' + l.strip()[:140]
print('EDITOR BACK GUARD PASS changed=' + ','.join(sorted(changed)))
