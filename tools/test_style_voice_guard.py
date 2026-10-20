#!/usr/bin/env python3
"""Style Voice Inspector & Editor isolation: only the approved files may differ from the verified LIVE V3.2 baseline.
Engine, MIDI OUT, Registration, Master Transpose and native code stay byte-identical unless a stage explicitly lists them."""
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
BASE = 'f9c95a8'
ALLOWED = {
    'app/src/main/java/com/yourapp/yamahaarranger/style/StyleVoiceInventory.kt',
    'app/src/main/java/com/yourapp/ui/StyleVoiceEditorScreen.kt',
    'app/src/main/java/com/yourapp/ui/StyleVoiceEditorOverlay.kt',
    'app/src/main/java/com/yourapp/ui/MainActivity.kt',
    'app/src/main/java/com/yourapp/ui/MainViewModel.kt',
    'app/src/main/java/com/yourapp/ui/SxMainScreen.kt',
    'app/src/main/java/com/yourapp/yamahaarranger/style/StyleOverrideStore.kt',
    'app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt',
    'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt',
    'app/src/main/java/com/yourapp/style/StyleModel.kt',
    'app/src/main/java/com/yourapp/yamahaarranger/style/StyleVoiceEditorOps.kt',
    'app/src/main/java/com/yourapp/yamahaarranger/audio/AudioEngineManager.kt',
    'tests/cpv/StyleVoiceEditorOpsTest.kt', 'tests/cpv/StyleVoiceRositaTest.kt',
    'tests/cpv/CpvOverrideIntegrationTest.kt',
    'tests/cpv/StyleVoiceInventoryTest.kt', 'tests/cpv/StyleVoiceOverrideProfileTest.kt', 'tests/cpv/CpvOverrideStoreTest.kt', 'tools/test_cpv.py', 'tools/test_style_voice_guard.py',
    '.github/workflows/style-voice-editor.yml', '.github/workflows/style-voice-editor-preview.yml',
    'docs/STYLE_VOICE_INSPECTOR_EDITOR_REPORT.md', 'docs/MULTI_SF2_VOICE_SELECTION_REPORT.md', 'docs/SX_PRIME_PRESET_INVENTORY.md',
    'app/src/main/cpp/bassmidi_player.cpp', 'app/src/main/cpp/bassmidi_player.h', 'app/src/main/cpp/audio_engine.cpp',
    'app/src/main/cpp/audio_engine.h', 'app/src/main/cpp/native_lib.cpp', 'tests/cpv_native_test.cpp',
    'app/src/main/java/com/yourapp/yamahaarranger/audio/NativeAudioBridge.kt',
    'app/src/test/java/com/yourapp/registration/RecallPlannerTest.kt',
}
PROTECTED = (
    'app/src/main/java/com/yourapp/yamahaarranger/registration/RecallPlanner.kt',
    'app/src/main/java/com/yourapp/yamahaarranger/registration/Sf2ExternalMapping.kt',
    'app/src/main/java/com/yourapp/midi/MidiInputManager.kt', 'app/src/main/java/com/yourapp/midi/MidiMixerOutput.kt',
    'app/src/main/java/com/yourapp/yamahaarranger/style/StyleVoiceRequests.kt',
)
def git(*a): return subprocess.check_output(['git', *a], cwd=ROOT, text=True)
changed = set(git('diff', '--name-only', BASE, 'HEAD').split()) | set(git('diff', '--name-only', BASE).split()) | \
    {p for p in git('ls-files', '--others', '--exclude-standard').split() if not p.startswith(('evidence/', 'build/'))}
extra = {p for p in changed - ALLOWED if not p.startswith('tools/ui_preview/')}
assert not extra, 'Unapproved or protected file changed: ' + ', '.join(sorted(extra))
for protected in PROTECTED:
    assert not git('diff', BASE, '--', protected).strip(), 'Protected file changed: ' + protected
print('STYLE VOICE GUARD PASS changed=' + ','.join(sorted(changed)))
