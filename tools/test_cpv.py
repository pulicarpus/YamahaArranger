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
    MAIN / 'yamahaarranger/audio/SoundFontRolePlanner.kt',
    MAIN / 'yamahaarranger/audio/RightVoiceDiag.kt',
    MAIN / 'yamahaarranger/registration/RegistrationCodec.kt',
    MAIN / 'yamahaarranger/registration/RegistrationModel.kt',
]
CPV_TESTS = sorted((ROOT / 'tests/cpv').glob('*.kt'))


def test_classes():
    names = []
    for path in CPV_TESTS:
        text = path.read_text()
        package = next(line.split()[1] for line in text.splitlines() if line.startswith('package '))
        names.append(package + '.' + path.stem)
    return names


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--suite', choices=['jvm'], required=True)
    p.add_argument('--out', required=True)
    a = p.parse_args()
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
