#!/usr/bin/env python3
"""CPV (Colombo primary voice override) host tests.

Runs the unchanged R2-3 integration JVM suite with the new pure CPV sources and
CPV JUnit tests appended to the same compile, so every existing assertion still
executes next to the new ones. Historical test files are not edited.
"""
import argparse
import runpy
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))

MAIN = ROOT / 'app/src/main/java/com/yourapp'
CPV_SOURCES = [
    MAIN / 'yamahaarranger/style/StyleVoiceRequests.kt',
    MAIN / 'yamahaarranger/style/StyleOverrideStore.kt',
    MAIN / 'yamahaarranger/style/StyleVoiceInventory.kt',
    MAIN / 'yamahaarranger/browser/BrowserModel.kt',
    MAIN / 'yamahaarranger/browser/VoiceGroups.kt',
    MAIN / 'yamahaarranger/style/StyleVoiceEditorOps.kt',
    MAIN / 'yamahaarranger/style/Sf2KeyCoverage.kt',
    MAIN / 'yamahaarranger/audio/SoundFontRolePlanner.kt',
    MAIN / 'yamahaarranger/audio/ManagedSf2Policy.kt',
    MAIN / 'yamahaarranger/audio/RightVoiceDiag.kt',
    MAIN / 'yamahaarranger/audio/RightUsbTouch.kt',
    MAIN / 'yamahaarranger/audio/InternalGain.kt',
    MAIN / 'yamahaarranger/audio/Sf2PitchFx.kt',
    MAIN / 'midi/LiveKeyState.kt',
    MAIN / 'midi/ChannelActivity.kt',
    MAIN / 'ui/MixerPure.kt',
    MAIN / 'yamahaarranger/style/StyleEditorNavigation.kt',
    MAIN / 'yamahaarranger/audio/VirtualKeyboard.kt',
    MAIN / 'yamahaarranger/registration/RegistrationCodec.kt',
    MAIN / 'yamahaarranger/registration/RegistrationModel.kt',
    MAIN / 'ui/LiveV3Model.kt',
]
CPV_TESTS = sorted((ROOT / 'tests/cpv').glob('*.kt'))


def test_classes():
    names = []
    for path in CPV_TESTS:
        text = path.read_text()
        package = next(line.split()[1] for line in text.splitlines() if line.startswith('package '))
        names.append(package + '.' + path.stem)
    return names


def rosita_tsv(out):
    """ROSITA.STY through the unmodified production C++ parser; the JVM test rebuilds ParsedStyle from this dump."""
    import subprocess, os
    out = Path(out).resolve(); out.mkdir(parents=True, exist_ok=True)
    exe = out / 'dump_style'
    subprocess.run(['g++', '-std=c++17', '-O2', '-DANDROID_LOG_ERROR=6', '-Itests/mocks', '-Iapp/src/main/cpp',
                    'tools/dump_style_shadow_fixture.cpp', 'app/src/main/cpp/style_parser.cpp', 'app/src/main/cpp/smf_reader.cpp',
                    '-o', str(exe)], cwd=ROOT, check=True)
    tsv = out / 'rosita.tsv'
    tsv.write_bytes(subprocess.check_output([str(exe), str(ROOT / 'tests/fixtures/r23/ROSITA.STY')]))
    os.environ['ROSITA_TSV'] = str(tsv)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--suite', choices=['jvm'], required=True)
    p.add_argument('--out', required=True)
    a = p.parse_args()
    rosita_tsv(Path(a.out) / 'rosita')
    import test_voice_release as old
    original = old.run

    def augmented(command, *args, **kwargs):
        command = list(command)
        if any(str(x).endswith('/StyleSequencer.kt') for x in command):
            extra = [str(x) for x in CPV_SOURCES + CPV_TESTS if str(x) not in map(str, command)]
            command += extra
        if 'org.junit.runner.JUnitCore' in command:
            command += test_classes()
        return original(command, *args, **kwargs)

    old.run = augmented
    sys.argv = ['test_r23_regressions.py', '--suite', 'integration', '--out', a.out]
    runpy.run_path(str(ROOT / 'tools/test_r23_regressions.py'), run_name='__main__')
    print('CPV JVM PASS classes=' + ','.join(test_classes()), flush=True)


if __name__ == '__main__':
    main()
