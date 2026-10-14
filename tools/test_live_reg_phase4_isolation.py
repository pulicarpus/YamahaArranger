#!/usr/bin/env python3
"""Phase 4 allowlist; every historical guard and engine file stays byte-identical."""
from pathlib import Path
import subprocess,re
ROOT=Path(__file__).resolve().parents[1]
BASE='d03e7fb897ac15e07a80e02eb4bf5a4b1da9fe45'
VM='app/src/main/java/com/yourapp/ui/MainViewModel.kt'
UI='app/src/main/java/com/yourapp/ui/LiveControlScreen.kt'
LAYOUT='app/src/main/java/com/yourapp/ui/LiveControlLayout.kt'
WF='.github/workflows/live-reg-debug-apk.yml'
CI='.github/workflows/live-control-reg-phase01.yml'
ADDITIONS={
'app/src/main/java/com/yourapp/yamahaarranger/registration/RegistrationLibraryController.kt',
'app/src/main/java/com/yourapp/yamahaarranger/registration/RegistrationSaveState.kt',
'app/src/test/java/com/yourapp/registration/RegistrationLibraryControllerTest.kt',
'app/src/test/java/com/yourapp/registration/LivePhase4WiringTest.kt',
 'tools/test_live_reg_phase4.py','tools/test_live_reg_phase4_isolation.py','tools/test_live_reg_phase4_guard_negative.py'}
def old(path,sha=BASE):return subprocess.check_output(['git','show',sha+':'+path],cwd=ROOT)
FILES=subprocess.check_output(['git','ls-tree','-r','--name-only',BASE],cwd=ROOT,text=True).splitlines()
def verify(root=ROOT):
    for p in FILES:
        if p not in {VM,UI,CI,LAYOUT}:
            assert (root/p).is_file() and (root/p).read_bytes()==old(p),'Protected file changed: '+p
    runtime={'evidence/linux-sdk.sha256','evidence/phase4-gates.log','evidence/phase01.log',
        'evidence/android-build.log','evidence/apk-signature.txt','evidence/provenance.json',
        'evidence/YamahaArranger-LiveReg-Phase4-Debug.apk'}
    if root==ROOT:
        tracked=set(subprocess.check_output(['git','ls-files','--cached'],cwd=ROOT,text=True).splitlines())
        assert not tracked & runtime,'Runtime artifact committed as source'
    candidates=(set(subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard'],cwd=ROOT,text=True).splitlines())
        if root==ROOT else {str(p.relative_to(root)) for p in root.rglob('*') if p.is_file()})
    added=candidates-set(FILES)-runtime
    assert added <= ADDITIONS|{WF},'Unapproved addition: '+repr(added-(ADDITIONS|{WF}))
    assert (root/LAYOUT).read_text()==old(LAYOUT).decode()+'''
/** A landscape input panel stays usable while the software keyboard resizes the window. */
fun liveInputViewportSupported(widthDp: Float, heightDp: Float, imeVisible: Boolean): Boolean =
    widthDp >= 960f && (heightDp >= 600f || imeVisible)
''','Legacy layout or navigation changed'
    s=(root/VM).read_text()
    a=s.index('\n    // BEGIN LIVE PHASE4 ADDITIONS');b=s.index('    // END LIVE PHASE4 ADDITIONS',a)+len('    // END LIVE PHASE4 ADDITIONS\n')
    block=s[a:b];s=s[:a]+s[b:]
    s=s.replace('''fun onLiveBankChange(delta: Int) {
        val library=phase4Library
        if(library==null) liveRegistrationState.update { it.move(delta) }
        else { val target=liveRegistrationState.value.move(delta).bank ?: return
            viewModelScope.launch { library.selectBank(target.id) } }
    }''','fun onLiveBankChange(delta: Int) { liveRegistrationState.update { it.move(delta) } }')
    s=s.replace('        if(phase4Library?.state?.value?.saving==true) return false\n','',1)
    s=s.replace('        if(bank.id in (phase4Library?.state?.value?.readOnly ?: emptySet())) return false\n','',1)
    assert s==old(VM).decode(),'Unapproved legacy/Phase 2/Phase 3 VM edit'
    for word in ['sendProgramChange','selectKeyboardPreset','applyFonts','toggleMidiOut','allNotesOff','startStop','loadStyle','setMixerStyleChannels']:
        assert word not in block,'Forbidden Phase 4 VM operation: '+word
    assert 'registrationPort.snapshot()' in block and 'registrationCoordinator.busy.value' in block
    for name in ['RegistrationLibraryController','RegistrationSaveState']:
        text=(root/f'app/src/main/java/com/yourapp/yamahaarranger/registration/{name}.kt').read_text()
        for word in ['ArrangerBrain','AudioEngineManager','MidiInputManager','sendProgramChange','applyFonts']:
            assert word not in text,'SAVE has playback dependency: '+word
    assert (root/CI).read_text()==old(CI).decode().replace('test_live_reg_phase3.py','test_live_reg_phase4.py'),'Unapproved contracts workflow edit'
    ui=(root/UI).read_text()
    for word in ['onSyncStart','onTapTempo','sendProgramChange','loadStyle','allNotesOff']:
        assert word not in ui,'Forbidden UI operation: '+word
    for word in ['"SYNC\\nN/A",{},LiveBlue','"TAP\\nN/A",{},LiveBlue','vm::toggleRelease','vm::toggleAutoFill','vm::onStartStop','vm.onSectionSelected("Intro $n")','vm.onSectionSelected("Ending $n")']:
        assert word in ui,'Legacy LIVE control missing: '+word
    used=set(re.findall(r'vm(?:::|\.)(\w+)',ui))
    assert used <= {'liveRegistration','currentExternal','liveLibrary','initializeLiveLibrary',
        'onLiveSlotTap','onLiveBankChange','onSectionSelected','onLiveFill','onLiveMainSelected','onStartStop',
        'toggleAcmp','toggleAutoFill','toggleLeftVoice','toggleRightVoice','nudgeTempo','onTransposeDown',
        'onTransposeUp','toggleRelease','onLiveUndoSave','onLiveSaveArm','onLiveCreateBank','onLiveDefaultMode',
        'onLiveSaveOptions','onLiveFreeze','onLiveAddExternal','onLiveExternalSelected'},'Unexpected LIVE API: '+repr(used)
    assert 'liveInputViewportSupported(maxWidth.value,maxHeight.value,imeVisible)' in ui,'IME input form guard missing'
    assert ui.count('vm::onStartStop')==1,'Unexpected automatic playback action'
    wf=(root/WF).read_text()
    for word in ['test_live_reg_phase4_isolation.py','test_live_reg_phase4.py --history --real --fetch-dependencies','verify_generated_sdk','apksigner','arm64-v8a','armeabi-v7a','assembleDebug','git diff --exit-code HEAD']:
        assert word in wf,'Required APK gate missing: '+word
    baseline=old(WF,'d0486e47c1eec6f8bc71e111d5d972416cb05fd0').decode()
    a=baseline.index('      - name: Verify only');b=baseline.index('      - uses: actions/setup-java',a)
    expected=baseline[:a]+'''      - name: Verify approved Phase 4 isolation
        run: |
          set -euo pipefail
          mkdir -p evidence
          git merge-base --is-ancestor "$PHASE3_CHECKPOINT" HEAD
          python3 tools/test_live_reg_phase4_isolation.py
'''+baseline[b:]
    for before,after in [('LIVE CONTROL Phase 3 Debug APK','LIVE REG Phase 4 Debug APK'),
        ('test_live_reg_phase3.py','test_live_reg_phase4.py'),('Required Phase 3','Required Phase 4'),
        ('LiveControl-Phase3-Debug','LiveReg-Phase4-Debug'),('phase3-gates.log','phase4-gates.log'),
        ("'phase4_implemented':False","'phase4_implemented':True"),
        ("'source_checkpoint':","'phase3_checkpoint':"),
        ("'phase4_implemented':True","'phase4_implemented':True, 'phase4_revision':4")]: expected=expected.replace(before,after)
    assert wf==expected,'Unapproved APK workflow change (all gates must remain intact)'
    print('LIVE_REG_PHASE4_ISOLATION PASS protected_files='+str(len(FILES)-4)+' legacy_VM_engine_router_guards_byte_identical=true')
if __name__=='__main__':verify()
