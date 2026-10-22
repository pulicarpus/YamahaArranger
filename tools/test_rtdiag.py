#!/usr/bin/env python3
"""Realtime MIDI & audio stress DIAGNOSTIC (host). Compiles the production MIDI->Brain->Sequencer->Engine chain plus the
REAL DebugLog source, runs tests/rtdiag, and writes RTDIAG result lines. HOST/MOCK evidence only."""
import argparse, hashlib, json, re, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
A = ROOT / 'app/src/main/java/com/yourapp'


def extract_debug_log(out):
    text = (A / 'ui/MainViewModel.kt').read_text()
    start = text.index('object DebugLog {')
    depth = 0; i = start
    while True:
        c = text[i]
        if c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0: break
        i += 1
    body = text[start:i + 1]
    path = out / 'DebugLogReal.kt'
    path.write_text('package com.yourapp.yamahaarranger.ui\nimport java.text.SimpleDateFormat\nimport java.util.Date\nimport java.util.Locale\n' + body + '\n')
    return path, hashlib.sha256(body.encode()).hexdigest()


def main():
    p = argparse.ArgumentParser(); p.add_argument('--out', required=True); p.add_argument('--only', default='')
    p.add_argument('--rosita', action='store_true')
    a = p.parse_args()
    if a.rosita:
        sys.path.insert(0, str(ROOT / 'tools')); import test_cpv; test_cpv.rosita_tsv(Path(a.out) / 'rosita')
    out = Path(a.out).resolve(); out.mkdir(parents=True, exist_ok=True)
    deps = ROOT / 'build/sff1-jvm/dependencies'
    jars = [deps / n for n in ['kotlin-compiler.jar', 'kotlin-stdlib.jar', 'kotlin-reflect.jar', 'kotlin-script-runtime.jar', 'trove4j.jar', 'annotations.jar',
                               'kotlinx-coroutines-core-jvm.jar', 'junit.jar', 'hamcrest-core.jar', 'mockito-core.jar', 'byte-buddy.jar', 'byte-buddy-agent.jar',
                               'objenesis.jar', 'javax.inject.jar']]
    for j in jars: assert j.exists(), f'missing pinned JVM dependency {j.name}; run tools/test_voice_release.py --fetch-dependencies once'
    raw = (A / 'yamahaarranger/audio/NativeAudioBridge.kt').read_text()
    methods = re.findall(r'    external fun (.*?)(?=\n    external fun|\n})', raw, re.S)
    bridge = out / 'NativeAudioBridge.kt'
    bridge.write_text('package com.yourapp.yamahaarranger.audio\nimport java.nio.ByteBuffer\nopen class NativeAudioBridge {\n' +
                      ''.join('    open fun ' + x.strip() + ' { error("unconfigured JNI boundary") }\n' for x in methods) + '}\n')
    log, log_sha = extract_debug_log(out)
    src = [bridge, log, A / 'audio/SampleProvider.kt',
           A / 'yamahaarranger/audio/AudioEngineManager.kt', A / 'yamahaarranger/audio/SoundFontPresetCatalog.kt', A / 'yamahaarranger/audio/KeyboardPart.kt',
           A / 'yamahaarranger/audio/ReleaseControlState.kt', A / 'yamahaarranger/audio/RightVoiceDiag.kt', A / 'yamahaarranger/audio/VirtualKeyboard.kt',
           A / 'yamahaarranger/audio/MixerConsoleState.kt', A / 'yamahaarranger/audio/StyleMixerChannels.kt', A / 'yamahaarranger/lifecycle/ReadyToPlayGate.kt',
           A / 'arranger/ArrangerBrain.kt', A / 'style/StyleModel.kt', A / 'midi/MidiInputManager.kt', A / 'midi/MidiMixerOutput.kt',
           A / 'yamahaarranger/audio/PcmSessionControls.kt']
    src += sorted((A / 'chord').glob('*.kt'))
    src += [A / 'yamahaarranger/audio/MetronomeModel.kt', A / 'yamahaarranger/audio/MetronomeController.kt']
    arr = A / 'yamahaarranger/arranger'
    src += sorted(arr.glob('*.kt'))
    src += [q for q in sorted((ROOT / 'tests/sff5_jvm_stubs').glob('*.kt')) if q.name not in ['AudioEngineManager.kt', 'DebugLog.kt']]
    src += sorted((ROOT / 'tests/rtdiag').glob('*.kt'))
    cp = ':'.join(map(str, jars)); jar = out / 'rtdiag.jar'
    subprocess.run(['java', '-Xmx1g', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn', '-jvm-target', '17',
                    '-classpath', cp, '-d', str(jar), *map(str, src)], check=True)
    classes = a.only.split(',') if a.only else ['com.yourapp.rtdiag.RealtimeMidiStressTest', 'com.yourapp.rtdiag.MetronomeFollowTest', 'com.yourapp.rtdiag.TransposeAcmpLeftTest', 'com.yourapp.rtdiag.P1ChainTest', 'com.yourapp.rtdiag.OrderedNoteIoUnitTest']
    result = subprocess.run(['java', '-javaagent:' + str(deps / 'byte-buddy-agent.jar'), '-cp', str(jar) + ':' + cp, 'org.junit.runner.JUnitCore', *classes],
                            capture_output=True, text=True)
    (out / 'rtdiag.log').write_text(result.stdout + result.stderr)
    rows = [l[min(i for i in (l.find(k) for k in ('RTDIAG ', 'CCS ', 'SGAP ')) if i >= 0):] for l in result.stdout.splitlines() if 'RTDIAG ' in l or 'CCS ' in l or 'SGAP ' in l]
    (out / 'rtdiag-rows.txt').write_text('\n'.join(rows) + '\n')
    print('\n'.join(rows)); print('DEBUGLOG_SOURCE_SHA256', log_sha)
    tail = [l for l in result.stdout.splitlines() if l.startswith(('OK', 'Tests run', 'FAILURES')) or l[:3].strip().rstrip(')').isdigit()]
    print('\n'.join(tail))
    for l in result.stdout.splitlines():
        if 'AssertionError' in l or 'Exception' in l: print(l)
    sys.exit(result.returncode)


if __name__ == '__main__':
    main()
