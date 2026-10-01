#include "bassmidi_player.h"
#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
JavaVM* g_jvm=nullptr;
jclass g_debugLogClass=nullptr;
jmethodID g_debugLogAddMethod=nullptr;
static int checks=0;
static void check(bool ok, const char* message) {
    ++checks; if (!ok) { std::cerr << "FAIL: " << message << '\n'; std::exit(1); }
}
static std::string sf2(const std::string& dir, const std::string& filename,
                       const std::string& name, int bank, int pc) {
    // Minimal RIFF/pdta/phdr for real normalization/cache parsing, no samples.
    std::vector<unsigned char> data(108, 0);
    auto word=[&](int offset, int value) { data[offset]=value&255; data[offset+1]=(value>>8)&255; };
    auto tag=[&](int offset, const char* text) { std::memcpy(data.data()+offset, text, 4); };
    tag(0,"RIFF"); word(4,100); tag(8,"sfbk"); tag(12,"LIST"); word(16,88);
    tag(20,"pdta"); tag(24,"phdr"); word(28,76);
    std::memcpy(data.data()+32, name.data(), std::min(size_t(20),name.size()));
    word(52,pc); word(54,bank); std::memcpy(data.data()+70,"EOP",3);
    const std::string path=dir+"/"+filename;
    std::ofstream out(path,std::ios::binary); out.write(reinterpret_cast<const char*>(data.data()),data.size());
    return path;
}
static BASS_MIDI_FONTEX2 mapping(int channel) {
    for (const auto& m: mock_bass::mappings) if (m.minchan==channel && m.numchan==1) return m;
    return {};
}
int main(int argc, char** argv) {
    check(argc==2, "temporary fixture directory required");
    const std::string dir=argv[1];
    const auto primary=sf2(dir,"Yamaha Melody.sf2","Wide Piano 2",8,1);
    const auto drums=sf2(dir,"Yamaha Drum.sf2","Standard Kit",128,0);
    const auto colombo=sf2(dir,"ColomboGMGS2_BM.sf2","Spanish Guitar",0,24);
    const auto tyros=sf2(dir,"Tyros 4.sf2","A.Guitar",0,25);
    const auto fail=sf2(dir,"fail_Colombo.sf2","Guitar",0,25);
    BassMidiPlayer player;
    check(player.loadMelody(primary) && player.loadDrum(drums),"core pair loads");
    check(mock_bass::streamFlags & BASS_MIDI_NOTEOFF1,"NOTEOFF1 survives");
    player.setChannelPreset(12,1040,1,"A.Guitar");
    const int before=mock_bass::noteOns; player.noteOn(12,60,1);
    check(mock_bass::noteOns==before,"Unresolved Guitar must not reach generic Piano note path");
    player.noteOff(12,60); // Note-off remains available even when note-on is rejected.
    player.setChannelPreset(9,128,0,"Drums"); player.noteOn(9,36,1);
    check(mock_bass::noteOns==before+1,"Dedicated drum still produces notes");
    check(player.loadMelodyFallback(colombo),"Colombo enters pool");
    auto m=mapping(12);
    check(m.font && mock_bass::fonts[m.font].find("Colombo")!=std::string::npos,
          "Existing unresolved request refreshes to compatible Colombo source");
    check(m.spreset==24 && m.sbank==0 && m.dpreset==1 && m.dbank==8 && m.dbanklsb==16,
          "Resolved source maps to preserved Yamaha destination through FONTEX2");
    check(mock_bass::preloadProgram==24 && mock_bass::preloadFlags==BASS_MIDI_FONTLOAD_NOWAIT,
          "Preload uses selected source program with NOWAIT");
    check(!player.loadMelodyFallback(fail) && mapping(12).font==m.font,
          "Optional FontInit failure preserves established pool/map");
    check(player.loadMelodyFallback(tyros),"Tyros occupies second optional slot");
    m=mapping(12);
    check(mock_bass::fonts[m.font].find("Tyros")!=std::string::npos,"Tyros semantic winner updates existing mapping");
    check(mock_bass::preloadedFont==m.font && mock_bass::preloadProgram==25,
          "Tyros preload follows selected source");
    check(player.presetList().find("MELODY_SECONDARY_2")!=std::string::npos,
          "Secondary inventory visible to preset inspection");
    const auto replacement=sf2(dir,"Yamaha replacement.sf2","Steel Guitar",8,1);
    check(player.loadMelody(replacement),"Replacing primary resolves channel against new pool");
    m=mapping(12);
    check(m.font && mock_bass::fonts[m.font].find("replacement")!=std::string::npos,
          "No stale selected secondary after primary replacement");
    bool dedicated=false;
    for (const auto& d: mock_bass::mappings) if (d.minchan==8 && d.numchan==2 && d.dbank==128)
        dedicated |= mock_bass::fonts[d.font].find("Drum")!=std::string::npos;
    check(dedicated,"Drum font/mappings survive melodic pool changes");
    // Diagnostic reads must not change MIDI delivery or controller values.
    player.setChannelMixer(12,80,64,0,0,0);
    mock_bass::logs.clear();
    const int diagBefore=mock_bass::noteOns;
    player.noteOn(12,60,0.5f,AudioPathOrigin{5,62,1040,1234,42,true});
    check(mock_bass::noteOns==diagBefore+1,"Diagnostics add no extra NOTE_ON or gain/mute fix");
    auto logged=[](const std::string& needle) {
        for(const auto& line:mock_bass::logs) if(line.find(needle)!=std::string::npos) return true;
        return false;
    };
    check(logged("CC7=80 CC11=0 NOTE_ON_SENT=1"),"Actual BASS controllers expose zero expression despite accepted note");
    check(logged("mappingMatch=1")&&logged("src=5 original=62 output=60"),"Live mapping readback correlates transformed source metadata");
    mock_bass::mismatchPreset=true;
    player.noteOn(12,62,0.5f,AudioPathOrigin{5,64,1040,1235,43,true});
    check(logged("mappingMatch=0"),"Live mismatch cannot be presented as matching mapping");
    mock_bass::mismatchPreset=false;
    mock_bass::failNote=true;
    player.noteOn(12,63,0.5f,AudioPathOrigin{5,65,1040,1236,44,true});
    check(logged("reason=bass_event_failed"),"NOTE_ON_SENT follows actual API failure");
    mock_bass::failNote=false;
    mock_bass::logs.clear();
    player.noteOn(9,38,0.8f,AudioPathOrigin{9,38,126*128,1237,45,true});
    check(logged("expected=GM_ACOUSTIC_SNARE")&&logged("NOTE_ON_SENT=1"),"Dedicated snare trace includes actual delivery");
    player.noteOn(9,40,0.6f,AudioPathOrigin{9,38,126*128,1238,46,true});
    check(logged("original=38 remapped=1"),"Drum note changes are observed without introducing remap");
    mock_bass::failMapping=true;
    player.setChannelPreset(12,1040,1,"A.Guitar");
    const int failed=mock_bass::noteOns; player.noteOn(12,60,1);
    check(mock_bass::noteOns==failed,"Failed FONTEX2 cannot bypass family selection through old mappings");
    player.noteOn(9,36,1);
    check(mock_bass::noteOns==failed+1,"Mapping failure only suppresses melodic notes");
    mock_bass::failMapping=false;
    std::cout << "PASS: " << checks << " native routing checks (mock BASS; no audio assertion)\n";
}
