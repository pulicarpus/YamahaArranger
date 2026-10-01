#include "managed_sf2_audition.h"
#include "bassmidi.h"
#include <cassert>
#include <cstring>
#include <iostream>
using namespace mock_bass;
int main(int argc,char** argv) {
    assert(argc==2);
    const std::string path=std::string(argv[1])+"/diagnostic.sf2";
    std::vector<unsigned char> raw(108);std::memcpy(raw.data()+32,"diagnostic preset",17);raw[52]=35;raw[54]=126;
    { std::ofstream file(path,std::ios::binary);file.write(reinterpret_cast<char*>(raw.data()),raw.size()); }
    // Observable production state and owner remain unchanged by the actual native implementation.
    nextStream=2;fonts[77]="active arranger.sf2";
    mappings.push_back({77,73,127,73,127,0,9,1});events[{9,MIDI_EVENT_PROGRAM}]=73;
    events[{9,MIDI_EVENT_EXPRESSION}]=83;events[{9,MIDI_EVENT_VOLUME}]=96;
    const auto productionEvents=events;const auto productionFonts=fonts;
    auto unchanged=[&] {
        assert(events==productionEvents);assert(fonts==productionFonts);
        assert(mappings.size()==1 && mappings[0].font==77 && mappings[0].sbank==127 && mappings[0].spreset==73);
        assert(noteOns==0);
        for(const auto& e:history) assert(std::get<0>(e)!=1);
        for(const auto s:freedStreams) assert(s!=1);
    };
    auto run=[&] { history.clear();auditionNoteOns=0;return managed_sf2_audition::render(path,126,35,91,110); };
    auto good=run();assert(good.wav.size()==44+44100*2*2*2);assert(auditionNoteOns==1);
    assert(good.evidence.find("bank=126 rawPC=35")!=std::string::npos);
    assert(good.evidence.find("sourceKey=91 velocity=110 noteOnCount=1")!=std::string::npos);
    assert(otherMappings[nextStream-1][0].font!=77);unchanged();
    // Failures before NOTE_ON must leave both resources/state clean.
    for(int mode=0;mode<3;++mode) {
        failMapping=mode==0;failProgram=mode==1;mismatchPreset=mode==2;
        const auto freed=freedStreams.size();auto bad=run();assert(bad.wav.empty());assert(auditionNoteOns==0);
        assert(freedStreams.size()==freed+1);unchanged();
        failMapping=failProgram=mismatchPreset=false;
    }
    changeDiagnosticPresetOnNote=true;assert(run().wav.empty());changeDiagnosticPresetOnNote=false;assert(auditionNoteOns==1);unchanged();
    failDecode=true;assert(run().wav.empty());failDecode=false;assert(auditionNoteOns==1);unchanged();
    const auto savedStream=nextStream;nextStream=0;history.clear();
    assert(managed_sf2_audition::render(path,126,35,91,110).wav.empty());nextStream=savedStream;
    assert(history.empty());unchanged();
    failNote=true;assert(run().wav.empty());failNote=false;unchanged();
    history.clear();assert(managed_sf2_audition::render(path,126,34,91,110).wav.empty());assert(history.empty());unchanged();
    assert(managed_sf2_audition::render(path,126,35,128,110).wav.empty());assert(history.empty());unchanged();
    assert(managed_sf2_audition::render(path,126,35,91,0).wav.empty());assert(history.empty());unchanged();
    assert(managed_sf2_audition::render("fail.sf2",126,35,91,110).wav.empty());unchanged();
    std::cout<<"managed SF2 audition: 12 native scenarios PASS; production events/state untouched; resources freed\n";
}
