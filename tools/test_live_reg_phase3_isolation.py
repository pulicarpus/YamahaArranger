#!/usr/bin/env python3
"""Phase 3: exact navigation-only edits plus isolated LIVE state additions."""
from pathlib import Path
import subprocess,re
ROOT=Path(__file__).resolve().parents[1]
BASE='ea1997bd8e5c9f62566ad7ad8de0fb8a79ab20f7'
VM='app/src/main/java/com/yourapp/ui/MainViewModel.kt'
SX='app/src/main/java/com/yourapp/ui/SxMainScreen.kt'
ACTIVITY='app/src/main/java/com/yourapp/ui/MainActivity.kt'
WORKFLOW='.github/workflows/live-control-reg-phase01.yml'
def before(p):return subprocess.check_output(['git','show',BASE+':'+p],cwd=ROOT,text=True)
files=subprocess.check_output(['git','ls-tree','-r','--name-only',BASE],cwd=ROOT,text=True).splitlines()
for p in files:
    if p not in {VM,SX,ACTIVITY,WORKFLOW}:
        assert (ROOT/p).is_file() and (ROOT/p).read_bytes()==subprocess.check_output(['git','show',BASE+':'+p],cwd=ROOT), 'Protected Phase 2 file changed: '+p
s=(ROOT/VM).read_text();start=s.index('\n    // BEGIN LIVE PHASE3 STATE');end=s.index('    // END LIVE PHASE3 STATE',start)+len('    // END LIVE PHASE3 STATE\n')
block=s[start:end]
assert s[:start]+s[end:]==before(VM),'Phase 2 / legacy ViewModel changed'
assert 'registrationCoordinator.recall(' in block and 'liveRequestId.get()!=token' in block
for word in ['sendProgramChange','applyFonts','selectKeyboardPreset','toggleMidiOut','allNotesOff','startStop','loadStyle','saveBank','saveManifest','AtomicBankStore']:
    assert word not in block,'Unexpected LIVE state runtime operation: '+word
expected=before(SX)
SX_EDITS=[('    var showMixer by remember { mutableStateOf(false) }', '    var navigation by remember { mutableStateOf(LiveNavigation()) }\n    val showMixer = navigation.page == LivePage.MIXER'), ('androidx.activity.compose.BackHandler(enabled = showMixer) { showMixer = false }', 'androidx.activity.compose.BackHandler(enabled = navigation.page != LivePage.HOME) { navigation = navigation.back() }'), ('LaunchedEffect(showMixer) { onMixerVisibilityChanged(showMixer) }', 'LaunchedEffect(navigation.page) { onMixerVisibilityChanged(navigation.page != LivePage.HOME) }'), ('if (!showMixer) BoxWithConstraints', 'if (navigation.page == LivePage.HOME) BoxWithConstraints'), ('                onMixer = { showMixer = true },', '                onMixer = { navigation = navigation.mixer() },\n                onLive = { navigation = navigation.live() },'), ('            onDismiss = { showMixer = false },', '            onDismiss = { navigation = navigation.back() },\n            returnLabel = if (navigation.mixerReturn == LivePage.LIVE) "LIVE" else "ARRANGER",'), ('    if (showMixer) {\n        SxMixerPage(', '    if (navigation.page == LivePage.LIVE) {\n        LiveControlScreen(state, viewModel,\n            onArranger = { navigation = navigation.home() },\n            onMixer = { navigation = navigation.mixer() })\n    }\n    if (showMixer) {\n        SxMixerPage('), ('    onMixer: () -> Unit,\n    onUtility:', '    onMixer: () -> Unit,\n    onLive: () -> Unit,\n    onUtility:'), ('"REGIST", "MIXER", "UTILITY"', '"REGIST", "MIXER", "LIVE", "UTILITY"'), ('"MIXER" -> Modifier.clickable(onClick = onMixer)', '"MIXER" -> Modifier.clickable(onClick = onMixer)\n                    "LIVE" -> Modifier.clickable(onClick = onLive)'), ('private fun SxMixerPage(state: MainUiState, vm: MainViewModel, voices: List<VoiceSlot>, presets: List<AudioEngineManager.SfPreset>, onDismiss: () -> Unit, onMixer:', 'private fun SxMixerPage(state: MainUiState, vm: MainViewModel, voices: List<VoiceSlot>, presets: List<AudioEngineManager.SfPreset>, onDismiss: () -> Unit, returnLabel: String = "ARRANGER", onMixer:'), ('Text("ARRANGER", color = SxGreen)', 'Text(returnLabel, color = SxGreen)')]
for old,new in SX_EDITS:
    assert expected.count(old)==1,old
    expected=expected.replace(old,new,1)
assert (ROOT/SX).read_text()==expected,'Unreviewed ARRANGER or MIXER edit'
expected=before(ACTIVITY).replace('val showMixer = remember { mutableStateOf(false) }','val overlayPageVisible = remember { mutableStateOf(false) }').replace('onMixerVisibilityChanged = { showMixer.value = it }','onMixerVisibilityChanged = { overlayPageVisible.value = it }').replace('if (!showMixer.value) ChordModeSelector(', 'if (!overlayPageVisible.value) ChordModeSelector(')
assert (ROOT/ACTIVITY).read_text()==expected,'Unreviewed Activity change'
assert (ROOT/WORKFLOW).read_text()==before(WORKFLOW).replace('python3 tools/test_live_reg_phase2.py --history --real --fetch-dependencies','python3 tools/test_live_reg_phase3.py --history --real --fetch-dependencies'),'Unreviewed CI change'
allowed={'app/src/main/java/com/yourapp/ui/'+name+'.kt' for name in ['LiveControlScreen','LiveControlLayout']}
extra={str(p.relative_to(ROOT)) for p in (ROOT/'app/src/main').rglob('*') if p.is_file()}-set(files)
assert extra==allowed,'Unexpected production addition: '+repr(extra)
ui=(ROOT/'app/src/main/java/com/yourapp/ui/LiveControlScreen.kt').read_text()
for name in ['onLiveRegRecall','onLiveBankChange','onSectionSelected','onLiveFill','onLiveMainSelected','onStartStop','toggleAcmp','toggleAutoFill','toggleLeftVoice','toggleRightVoice','nudgeTempo','onTransposeDown','onTransposeUp','toggleRelease']:
    assert name in ui,'Required LIVE binding missing: '+name
used=set(re.findall(r'vm(?:::|\.)(\w+)',ui))
assert used <= {'liveRegistration','currentExternal','onLiveRegRecall','onLiveBankChange','onSectionSelected','onLiveFill','onLiveMainSelected','onStartStop','toggleAcmp','toggleAutoFill','toggleLeftVoice','toggleRightVoice','nudgeTempo','onTransposeDown','onTransposeUp','toggleRelease'},used
assert '"SYNC\\nN/A",{},LiveBlue' in ui and '"TAP\\nN/A",{},LiveBlue' in ui
assert re.search(r'LiveButton\("SYNC.*?enabled=false\)',ui) and re.search(r'LiveButton\("TAP.*?enabled=false\)',ui)
assert 'onSyncStart' not in ui and 'onTapTempo' not in ui and 'onRegSlotTap' not in ui
assert 'LaunchedEffect' not in ui,'Automatic UI action added'
assert '(0..7).forEach' in ui and 'enabled=slot!=null' in ui
layout=(ROOT/'app/src/main/java/com/yourapp/ui/LiveControlLayout.kt').read_text()
assert 'onStartStop' not in layout and 'registrationCoordinator' not in layout
print(f'LIVE_REG_PHASE3_ISOLATION PASS base={BASE} protected_files={len(files)-4} old_VM_and_all_phase2_code_byte_identical=true exact_UI_navigation_edits=true U3_U4_U5_source_contracts=true')
