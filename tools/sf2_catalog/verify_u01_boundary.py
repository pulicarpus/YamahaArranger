"""Positive source-boundary proof, independent of output-based recorder tests."""
from pathlib import Path
import re
import subprocess

ROOT=Path(__file__).resolve().parents[2]
BASE='c3f3b0a67f4d4acc2310f687abb248be1cae6643'

def old(path): return subprocess.check_output(['git','show',BASE+':'+path],cwd=ROOT).decode()
def function(text,name):
    match=re.search(r'^.*BassMidiPlayer::'+name+r'\(',text,re.M)
    if not match:raise AssertionError('missing function '+name)
    end=text.index('\n}',match.start())+2
    return text[match.start():end]

def verify():
    path='app/src/main/cpp/bassmidi_player.cpp';before=old(path);after=(ROOT/path).read_text()
    # All control/routing/transport/sample paths are the exact same source bytes.
    for name in ['noteOn','noteOff','render','allNotesOff','stopKeyboardPart','applyFonts','findDrumPreset',
                 'setKeyboardPreset','setKeyboardReleaseTime','setChannelMixer','setChannelExpression','setMasterGain']:
        assert function(before,name)==function(after,name),'protected native method changed: '+name
    b=function(before,'findMelodicPreset');a=function(after,'findMelodicPreset')
    a=a.replace(', std::string* diagnosticReason','')
    a=a.replace('        if(diagnosticReason)*diagnosticReason="no_compatible_candidate";\n','')
    a=a.replace('    if(diagnosticReason)*diagnosticReason=d.reason;\n','')
    assert a==b,'resolver gained non-diagnostic change'
    path='app/src/main/java/com/yourapp/ui/MainViewModel.kt';before=old(path);after=(ROOT/path).read_text()
    replacement='''                val nativeFidelitySnapshot = audioEngine.noteZoneReport()
                appendLine(nativeFidelitySnapshot)
                if (!uiState.value.isPlaying) appendLine(com.yourapp.yamahaarranger.arranger.StyleFidelityReport.export(
                    style, uiState.value.activeSection, nativeFidelitySnapshot))
                else appendLine("STYLE FIDELITY U0 unavailable: STOP before diagnostic export")'''
    assert after.count(replacement)==1
    assert after.replace(replacement,'                appendLine(audioEngine.noteZoneReport())')==before,'non-export ViewModel change'
    # Additional production file is an explicitly invoked metadata exporter only.
    path='app/src/main/java/com/yourapp/audio/Sf2SemanticInventory.kt'
    a=(ROOT/path).read_text(); b=old(path)
    a=a.replace("${quotedName(text(inst, i * 22, 20))}","${label(text(inst, i * 22, 20))}")
    a=a.replace(" bags=$first:$last nameHex=${nameHex(inst, i * 22)}", " bags=$first:$last")
    a=a.replace("${quotedName(preset)}", "${label(preset)}")
    a=a.replace(" bags=$first:$last nameHex=${nameHex(ph, p * 38)}", " bags=$first:$last")
    a=a.replace("${quotedName(text(sh,o,20))}","${label(text(sh,o,20))}")
    a=a.replace(" type=${u16(sh,o+44)} nameHex=${nameHex(sh,o)}", " type=${u16(sh,o+44)}")
    start=a.index('    // Full 20-byte SF2 name field, including NUL and unused padding. Export only.')
    end=a.index('    private fun label(',start)
    a=a[:start]+a[end:]
    assert a==b,'metadata exporter gained non-formatting changes'
    for path in ['app/src/main/cpp/voice_resolver.h','app/src/main/java/com/yourapp/yamahaarranger/arranger/StyleSequencer.kt',
                 'app/src/main/java/com/yourapp/midi/MidiMixerOutput.kt','app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt']:
        assert (ROOT/path).read_text()==old(path),'protected source changed: '+path
    assert not any('catalog_generated.h' in p.read_text(errors='replace') for p in (ROOT/'app/src/main').rglob('*') if p.is_file()),'catalog wired to active engine'
    print('U01 SOURCE BOUNDARY PASS: Policy C/NOTE_ON guard/drum selector/mixer/render/keyboard/transport and all non-export VM code exact')

if __name__=='__main__':verify()
