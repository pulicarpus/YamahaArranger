#!/usr/bin/env python3
"""Audit the actual Gradle checkout before/after; not filesystem immutability."""
import argparse, hashlib, json, subprocess
from pathlib import Path
from generated_bass_sdk_guard import PATHS, verify_generated_sdk

ROOT = Path(__file__).resolve().parents[1]

def capture(expected):
    def git(*args):
        return subprocess.check_output(['git', *args], cwd=ROOT)
    assert git('rev-parse', 'HEAD').decode().strip() == expected, 'Build SHA mismatch'
    assert not git('status', '--porcelain', '--untracked-files=no'), 'Tracked build input changed'
    tracked = set(git('ls-files', '-z').decode().rstrip('\0').split('\0'))
    inputs = {}
    for path in sorted(tracked):
        file = ROOT / path
        assert file.is_file() and not file.is_symlink(), 'Nonregular tracked input: ' + path
        assert not any(parent.is_symlink() for parent in file.parents), 'Symlink parent: ' + path
        inputs[path] = {'sha256': hashlib.sha256(file.read_bytes()).hexdigest(),
                        'mode': file.stat().st_mode & 0o777}
    untracked = set(git('ls-files', '--others', '--exclude-standard', '-z').decode().rstrip('\0').split('\0')) - {''}
    assert not untracked - PATHS, 'Unknown untracked build input: ' + str(sorted(untracked - PATHS))
    physical = set()
    for file in (ROOT/'app/src').rglob('*'):
        assert not file.is_symlink(), 'Symlink source: ' + str(file)
        if file.is_file():
            physical.add(file.relative_to(ROOT).as_posix())
    sdk = verify_generated_sdk(ROOT, physical - tracked)
    assert set(sdk) == PATHS, 'Build requires complete pinned Android SDK'
    return {'commit': expected, 'tracked': inputs, 'sdk': sdk,
            'git_status': git('status', '--porcelain').decode(),
            'limitation': 'Pre/post observation, not immutable filesystem or protection against transient privileged writes.'}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--record', required=True, type=Path)
    parser.add_argument('--compare', type=Path)
    args = parser.parse_args()
    result = capture(args.commit)
    if args.compare:
        assert result == json.loads(args.compare.read_text()), 'Build input changed between observations'
    args.record.parent.mkdir(parents=True, exist_ok=True)
    args.record.write_text(json.dumps(result, indent=2)+'\n')
    print('BUILD_INPUT_IDENTICAL' if args.compare else 'BUILD_INPUT_PINNED', args.commit)

if __name__ == '__main__':
    main()
