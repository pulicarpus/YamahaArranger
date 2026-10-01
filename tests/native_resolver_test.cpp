#include "bassmidi_player.h"
#include "sf2_rich_fixture.h"
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
    mock_bass::logs.clear();
    player.setChannelPreset(9,128,73,"Drums");
    const int beforeExtended=mock_bass::noteOns;
    player.noteOn(9,21,42.0f/127.0f,AudioPathOrigin{9,21,127*128,1238,47,true});
    check(mock_bass::noteOns==beforeExtended+1,"Zone audit never drops/remaps an extended drum note");
    check(logged("requestedKitPC=73 kitFallback=1")&&logged("livePC=0"),"Existing missing-kit fallback remains observable and unchanged");
    check(logged("DRUM ZONE ch=9 key=21 vel=42")&&logged("metadataKnown=0"),"Incomplete SF2 zone metadata cannot be claimed as missing sample proof");
    player.noteOn(9,40,0.6f,AudioPathOrigin{9,38,126*128,1238,46,true});
    check(logged("original=38 remapped=1"),"Drum note changes are observed without introducing remap");
    const auto eventsBeforeAudit=mock_bass::events;
    const auto fontBeforeAudit=mapping(12).font;
    check(player.drumKitCoverage({{9,31,110,8},{9,21,42,32}}).find("unavailable")!=std::string::npos,
          "Incomplete cached metadata fails explicitly rather than inventing kit coverage");
    check(mock_bass::events==eventsBeforeAudit && mapping(12).font==fontBeforeAudit,
          "Explicit all-kit audit sends no MIDI events and does not alter FONTEX2");
    player.setChannelMixer(13,100,64,127,40,0);
    player.setChannelExpression(13,126);
    check(mock_bass::events[{13,MIDI_EVENT_VOLUME}]==100 && mock_bass::events[{13,MIDI_EVENT_EXPRESSION}]==126,
          "Native expression-only boundary preserves effective CC7=100");
    const auto richBytes=rich_fixture::font(0,49,"t4 strings slow");
    const auto richPath=dir+"/Rich Tyros.sf2";
    { std::ofstream out(richPath,std::ios::binary);out.write(reinterpret_cast<const char*>(richBytes.data()),richBytes.size()); }
    // Detailed observations use the original player with richer font metadata.
    check(player.loadMelody(richPath),"real richer melodic metadata loaded at original font bank");
    player.setChannelPreset(13,1029,49,"Strings1");
    player.setChannelMixer(13,127,64,127,0,0);
    const int onsBeforeZone=mock_bass::noteOns;
    player.noteOn(13,60,26.0f/127.0f,AudioPathOrigin{13,60,1029,100,500,true});
    const auto report=player.noteZoneReport();
    check(mock_bass::noteOns==onsBeforeZone+1 && report.find("key=60 velocity=26")!=std::string::npos,"observer captures the one actual note without injecting more");
    check(report.find("Quiet sample")!=std::string::npos && report.find("attenuationCb=220")!=std::string::npos,"native live preset maps to cached original-bank zone and effective generators");
    const auto eventsAfterNote=mock_bass::events;
    player.noteZoneReport();
    check(mock_bass::events==eventsAfterNote && mapping(13).font==mock_bass::mappings.front().font,"export does not send controllers or change selected font");
    player.noteOn(13,60,61.0f/127.0f);
    check(player.noteZoneReport().find("Loud sample")!=std::string::npos,"a changed actual velocity audits its distinct eligible layer");
    // Chord instrumentation must preserve the exact normal event sequence.
    player.noteOn(13,60,26.0f/127.0f,AudioPathOrigin{5,60,1029,100,600,false,700,1});
    check(player.chordDiagnosticReport().find("rows=0 ")!=std::string::npos,"native chord capture remains off by default");
    mock_bass::history.clear();
    player.noteOff(13,60); player.noteOn(13,65,26.0f/127.0f); player.noteOff(13,65);
    const auto unobservedHistory=mock_bass::history;
    player.armChordDiagnostic(); mock_bass::history.clear();
    const AudioPathOrigin retarget{5,60,1029,100,601,false,700,1};
    player.noteOff(13,60,retarget); player.noteOn(13,65,26.0f/127.0f,retarget); player.noteOff(13,65,retarget);
    const auto chordReport=player.chordDiagnosticReport();
    check(mock_bass::history==unobservedHistory,"armed observation sends identical ordered messages/key/velocity as normal note path");
    check(chordReport.find("id=601 chordId=700 op=RETARGET stage=NOTE_PRE ch=13 src=5 original=60 output=65 velocity=26")!=std::string::npos,
          "pre-send capture carries same retarget event/source identity");
    check(chordReport.find("stage=OFF_POST")<chordReport.find("stage=NOTE_PRE"),"replacement off is recorded before the new on");
    check(chordReport.find("livePreset='t4 strings slow' liveFamilyByName=STRINGS mappingMatch=1")!=std::string::npos,
          "captured live SF2 name/family comes from BASS independently of source part label");
    const auto historyBeforeExport=mock_bass::history; player.chordDiagnosticReport();
    check(mock_bass::history==historyBeforeExport,"report export emits no MIDI/control messages");
    mock_bass::presets[{mapping(13).font,mapping(13).sbank,0}]="ConcertGrand";
    mock_bass::changePresetOnNote=true;
    player.noteOn(13,67,26.0f/127.0f,AudioPathOrigin{5,60,1029,100,602,false,701,1});
    const auto changedReport=player.chordDiagnosticReport();
    const auto changedStart=changedReport.find("id=602 chordId=701 op=RETARGET stage=NOTE_PRE");
    const auto changedPost=changedReport.find("id=602 chordId=701 op=RETARGET stage=NOTE_POST");
    check(changedStart<changedPost && changedReport.substr(changedStart,changedPost-changedStart).find("livePC=49")!=std::string::npos &&
          changedReport.substr(changedPost).find("livePreset='ConcertGrand' liveFamilyByName=PIANO mappingMatch=0")!=std::string::npos,
          "pre/post independently detect synthetic Piano switch during a note send without inventing/fixing it");
    mock_bass::changePresetOnNote=false; mock_bass::forcedLivePc=-1;
    mock_bass::failNote=true;
    player.noteOn(13,69,0.5f,AudioPathOrigin{5,60,1029,100,603,false,701,1}); mock_bass::failNote=false;
    check(player.chordDiagnosticReport().find("stage=NOTE_POST ch=13 src=5 original=60 output=69 velocity=64 styleBank=1029 tick=100 sent=0")!=std::string::npos,
          "actual send failure is recorded rather than claiming sound was played");
    mock_bass::failProgram=true; player.setChannelPreset(13,1029,49,"Strings1"); mock_bass::failProgram=false;
    const auto controls=player.chordDiagnosticReport();
    check(controls.find("midiType=BANK_MSB")!=std::string::npos && controls.find("midiType=BANK_LSB")!=std::string::npos &&
          controls.find("sent=0 error=0 midiType=PROGRAM param=49")!=std::string::npos,"bank/program send order and API success are explicit");
    player.setChannelPreset(14,1029,56,"Horns");
    const int rejectedBefore=mock_bass::noteOns;
    player.noteOn(14,60,0.5f,AudioPathOrigin{5,60,1029,100,604,false,701,1});
    check(mock_bass::noteOns==rejectedBefore && player.chordDiagnosticReport().find("stage=NOTE_POST ch=14 src=5 original=60 output=60 velocity=64 styleBank=1029 tick=100 sent=0")!=std::string::npos,
          "chord instrumentation retains the proven family/mapping rejection");
    const auto messagesBeforeCompact=mock_bass::history;
    const auto small=player.compactChordDiagnosticReport();
    check(small.size()<=30*1024 && small.find("NOTE_PRE/POST dst=")!=std::string::npos && small.find(" b=")!=std::string::npos && small.find(" a=")!=std::string::npos,
          "compact native report retains exact before/after evidence within30KiB");
    check(small.find("control=PROGRAM value=49")!=std::string::npos && small.find("request=1029:49")!=std::string::npos,
          "compact report carries requested program and labelled control attempt");
    mock_bass::events[{11,MIDI_EVENT_SUSTAIN}]=1;
    player.markChordDiagnostic(702);
    check(mock_bass::history==messagesBeforeCompact,"compact export/window marker send no MIDI and do not alter playback state");
    check(player.compactChordDiagnosticReport().find("CC=127/127/1")!=std::string::npos,"ch11 sustain state is read back without sending CC64");
    player.stopChordDiagnostic(); const auto stoppedReport=player.chordDiagnosticReport();
    player.noteOn(13,60,26.0f/127.0f);
    check(player.chordDiagnosticReport()==stoppedReport,"explicit stop freezes the native evidence");
    const auto richDrum=rich_fixture::font(128,1,"Audition Kit");
    const auto richDrumPath=dir+"/Rich Drum.sf2";
    { std::ofstream out(richDrumPath,std::ios::binary);out.write(reinterpret_cast<const char*>(richDrum.data()),richDrum.size()); }
    check(player.loadDrum(richDrumPath),"dedicated richer drum fixture loaded");
    const auto savedEvents=mock_bass::events;
    const auto savedFont=mapping(13).font;
    const int savedOns=mock_bass::noteOns;
    const auto wav=player.diagnosticDrumWav(128,1,60,26);
    check(wav.size()==384044 && std::memcmp(wav.data(),"RIFF",4)==0,"isolated stream creates two-second stereo WAV without normalization");
    check(mock_bass::events==savedEvents && mapping(13).font==savedFont && mock_bass::noteOns==savedOns,"audition leaves arranger MIDI controller/note/mapping state intact");
    check(!mock_bass::freedStreams.empty() && mock_bass::freedStreams.back()>1,"isolated audition stream is freed");
    check(player.diagnosticDrumWav(128,1,31,110).empty(),"audition rejects key without eligible zone instead of falling back/remapping");
    mock_bass::mismatchPreset=true;
    check(player.diagnosticDrumWav(128,1,60,26).empty(),"audition refuses live preset mismatch");
    mock_bass::mismatchPreset=false;
    mock_bass::failNote=true;
    const auto freedBeforeFailure=mock_bass::freedStreams.size();
    check(player.diagnosticDrumWav(128,1,60,26).empty() && mock_bass::freedStreams.size()==freedBeforeFailure+1,"failed audition note frees temporary stream without modifying playback");
    mock_bass::failNote=false;
    check(mock_bass::auditionNoteOns==2,"only requested successful/mismatched auditions send an isolated test note");
    mock_bass::failMapping=true;
    player.setChannelPreset(12,1040,1,"A.Guitar");
    const int failed=mock_bass::noteOns; player.noteOn(12,60,1);
    check(mock_bass::noteOns==failed,"Failed FONTEX2 cannot bypass family selection through old mappings");
    player.noteOn(9,36,1);
    check(mock_bass::noteOns==failed+1,"Mapping failure only suppresses melodic notes");
    mock_bass::failMapping=false;
    std::cout << "PASS: " << checks << " native routing checks (mock BASS; no audio assertion)\n";
}
