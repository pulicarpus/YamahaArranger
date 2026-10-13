#!/usr/bin/env python3
"""Phase 2 additions + unchanged historical tests on pinned Phase 1 snapshot."""
import argparse, os, shutil, subprocess, tempfile, sys, re
from pathlib import Path
os.environ['PYTHONDONTWRITEBYTECODE']='1'
ROOT=Path(__file__).resolve().parents[1]
BASE='48f4c0353bb94d7340cbb61566766a3c2feda2d6'
def run(command,cwd=ROOT): subprocess.run(command,cwd=cwd,check=True)
def jvm(fetch):
    sys.path.insert(0,str(ROOT/'tools'))
    import test_voice_release as old
    out=ROOT/'build/live-reg-phase2';out.mkdir(parents=True,exist_ok=True)
    s=(ROOT/'app/src/main/java/com/yourapp/ui/MainViewModel.kt').read_text()
    block=s[s.index('    // BEGIN REG PHASE2 ADDITIONS'):s.index('    // END REG PHASE2 ADDITIONS')]
    def method(name):
        match=re.search(r'    (?:private )?fun '+name+r'\(',s); start=match.start()
        brace=s.index('{',start); depth=1; end=brace+1
        while depth:
            depth+= (s[end]=='{')-(s[end]=='}'); end+=1
        return s[start:end]+'\n'
    slot=s[s.index('data class KeyboardVoiceSlot('):s.index('fun defaultKeyboardVoices()')]
    harness='''package com.yourapp.yamahaarranger.ui
import com.yourapp.yamahaarranger.audio.*
import com.yourapp.yamahaarranger.arranger.ArrangerBrain
import com.yourapp.midi.MidiInputManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
val GM_VOICES = listOf("Piano" to 0)
'''+slot+'''class RecallViewModelHarness(val audioEngine: AudioEngineManager, val arrangerBrain: ArrangerBrain,
val midiInputManager: MidiInputManager, val viewModelScope: CoroutineScope) {
val _rightVoices = MutableStateFlow(List(4) { KeyboardVoiceSlot(it,"PART$it",0,enabled=it==0 || it==3) })
val _releaseControl = MutableStateFlow(ReleaseControlState())
val _transpose = MutableStateFlow(0)
val voiceRequestIds = java.util.concurrent.atomic.AtomicLongArray(4)
val _right1Volume=MutableStateFlow(100);val _right2Volume=MutableStateFlow(100)
val _right3Volume=MutableStateFlow(100);val _leftVolume=MutableStateFlow(100)
val _mixerConsole=MutableStateFlow(MixerConsoleState())
'''+block+'''
private fun keyboardVolume(channel:Int)=listOf(_right1Volume,_right2Volume,_right3Volume,_leftVolume)[channel]
private fun sendKeyboardGain(channel:Int)=audioEngine.setChannelExpression(channel,_mixerConsole.value.keyboardGain(channel,keyboardVolume(channel).value))
fun setLeftVoiceEnabled(enabled:Boolean)=arrangerBrain.setLeftVoiceEnabled(enabled)
suspend fun testSnapshot()=registrationPort.snapshot()
'''+''.join(method(n) for n in ['toggleRightVoice','onRight1VolumeChange','onRight2VolumeChange','onRight3VolumeChange','onLeftVolumeChange'])+'''
init { viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
'''+s[s.index('            _releaseControl.map'):s.index('\n        }',s.index('            _releaseControl.map'))]+'''
} }
}
'''
    fixture=out/'RecallViewModelHarness.kt';fixture.write_text(harness)
    extra=sorted((ROOT/'app/src/main/java/com/yourapp/yamahaarranger/registration').glob('*.kt'))+sorted((ROOT/'app/src/test/java/com/yourapp/registration').glob('*.kt'))+[fixture]
    def augmented(command,**kwargs):
        if 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' in command: command=command+list(map(str,extra))
        if 'org.junit.runner.JUnitCore' in command:
            # Historical style fixtures leave daemon playback workers running.
            # Keep old tests unchanged in their own JVM, exactly as historical CI.
            old_run(command,**kwargs)
            command=command[:command.index('org.junit.runner.JUnitCore')+1]+['com.yourapp.yamahaarranger.registration.RegistrationPersistenceTest','com.yourapp.yamahaarranger.registration.RecallPlannerTest','com.yourapp.yamahaarranger.registration.RegistrationCoordinatorTest','com.yourapp.yamahaarranger.registration.RecallAdapterTest']
        old_run(command,**kwargs)
    old_run=old.run;old.run=augmented
    try: old.jvm(out,fetch)
    finally: old.run=old_run
p=argparse.ArgumentParser();p.add_argument('--history',action='store_true');p.add_argument('--real',action='store_true');p.add_argument('--fetch-dependencies',action='store_true');args=p.parse_args()
run(['python3','tools/test_live_reg_phase2_isolation.py'])
jvm(args.fetch_dependencies)
if args.history or args.real:
    parent=Path(tempfile.mkdtemp(prefix='reg-phase2-history-'));checkout=parent/'source'
    try:
        run(['git','worktree','add','--detach',str(checkout),BASE])
        shutil.copytree(ROOT/'build/sff1-jvm/dependencies',checkout/'build/sff1-jvm/dependencies',dirs_exist_ok=True)
        command=['python3','tools/test_live_reg.py','--baseline']
        if args.real:command+=['--real']
        if args.fetch_dependencies:command+=['--fetch-dependencies']
        run(command,checkout)
    finally:
        if checkout.exists():run(['git','worktree','remove',str(checkout)])
        shutil.rmtree(parent)
run(['python3','tools/test_live_reg_phase2_isolation.py'])
