"""Pin #787 musical code; allow only reviewed diagnostic snapshot/session changes."""
from pathlib import Path
import hashlib,json,sys
from pcm_path_guard import strip_observers
root=Path(__file__).resolve().parents[1]
def remove_function(text,signature):
    a=text.index(signature);start=text.index('{',a);end=start+1;depth=1
    while depth:
        depth+=(text[end]=='{')-(text[end]=='}');end+=1
    return text[:a]+text[end:]
def normalize(path,text):
    text=strip_observers(path,text)
    if path.endswith('bassmidi_player.cpp'):
        text=remove_function(text,'std::string BassMidiPlayer::noteZoneReport() const')
        text=remove_function(text,'std::string BassMidiPlayer::rolePcmReportLocked() const' if 'rolePcmReportLocked() const' in text else 'std::string BassMidiPlayer::rolePcmReport(')
    elif path.endswith('bassmidi_player.h'):
        text=text.replace('    std::string rolePcmReportLocked() const;','')
        text=text.replace('    static std::string rolePcmReport(const std::array<RolePcmMeter,6>& meters,\n        const std::map<std::string,std::shared_ptr<const sf2_zones::Inventory>>& inventories,uint64_t samples);','')
    elif path.endswith('MainViewModel.kt'):
        if 'suspend fun compactPartPresenceReport(): String {' in text:
            text=remove_function(text,'    suspend fun compactPartPresenceReport(): String')
        else:text=text.replace('    suspend fun compactPartPresenceReport(): String = arrangerBrain.compactPartPresenceReport()','')
    elif path.endswith('ArrangerBrain.kt'):
        text=text.replace('import kotlinx.coroutines.withContext\n','').replace('@Volatile private var loadedStyle:','private var loadedStyle:')
        text=text.replace('    // Same authoritative style reference used by startStop/playSection, not a\n    // ViewModel-local diagnostic cache that is lost when the UI is recreated.\n    fun diagnosticActiveStyle(): ParsedStyle? = loadedStyle\n','')
        if '    suspend fun compactPartPresenceReport()' in text:text=remove_function(text,'    suspend fun compactPartPresenceReport()')
    # Ignore empty lines left by removing diagnostic functions only; retain all
    # executable lines, indentation and musical string literals byte for byte.
    return "\n".join(line for line in text.splitlines() if line.strip())+"\n"
manifest=json.loads((root/'tests/fixtures/diagnostic788_protected_sources.json').read_text())
for path,h in manifest.items():
    assert hashlib.sha256(normalize(path,(root/path).read_text()).encode()).hexdigest()==h,path
    if len(sys.argv)>1:assert hashlib.sha256(normalize(path,(Path(sys.argv[1])/path).read_text()).encode()).hexdigest()==h,path
cpp=(root/'app/src/main/cpp/bassmidi_player.cpp').read_text()
a=cpp.index('std::string BassMidiPlayer::noteZoneReport() const');b=cpp.index('std::string BassMidiPlayer::drumKitCoverage',a);report=cpp[a:b]
lock=report[report.index('std::lock_guard'):report.index('} // No render/UI synth lock')]
for bad in ['ostringstream','presence <<','rolePcmReport(','detailedMatch(','sf2_zones::match(']:assert bad not in lock,bad
for bad in ['BASS_MIDI_StreamEvent','BASS_MIDI_StreamSetFonts','BASS_ChannelSetAttribute','LOGI','LOGE','ifstream']:
    assert bad not in report,bad
brain=(root/'app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt').read_text()
method=brain[brain.index('    suspend fun compactPartPresenceReport()'):brain.index('    fun compactChordDiagnosticReport()')]
assert 'withContext(Dispatchers.Default)' in method
assert method.index('withContext(Dispatchers.Default)')<method.index('audioEngine.noteZoneReport()')
assert 'diagnosticActiveStyle() !== style' in method
vm=(root/'app/src/main/java/com/yourapp/ui/MainViewModel.kt').read_text()
assert 'suspend fun compactPartPresenceReport(): String = arrangerBrain.compactPartPresenceReport()' in vm
print('DIAGNOSTIC788_GUARDS PASS: #787 musical source unchanged; report formatting/layer scans outside synth lock; authoritative session; native export on worker')
