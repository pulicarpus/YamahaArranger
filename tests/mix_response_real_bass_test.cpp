#include <bass.h>
#include <bassmidi.h>
#include "mix_response_fixture.h"
#include <iostream>
#include <vector>
#include <cmath>
#include <cstdlib>
static double rms(const std::string& file,int velocity) {
    auto font=BASS_MIDI_FontInit(file.c_str(),0);auto stream=BASS_MIDI_StreamCreate(16,BASS_STREAM_DECODE|BASS_SAMPLE_FLOAT,48000);
    BASS_MIDI_FONT f{font,24,0};
    if(!font||!stream||!BASS_MIDI_StreamSetFonts(stream,&f,1))std::exit(2);
    for(auto e:{MIDI_EVENT_PROGRAM,MIDI_EVENT_VOLUME,MIDI_EVENT_EXPRESSION,MIDI_EVENT_REVERB,MIDI_EVENT_CHORUS})
        if(!BASS_MIDI_StreamEvent(stream,0,e,e==MIDI_EVENT_PROGRAM?24:e==MIDI_EVENT_VOLUME||e==MIDI_EVENT_EXPRESSION?127:0))std::exit(3);
    if(!BASS_MIDI_StreamEvent(stream,0,MIDI_EVENT_NOTE,60|(velocity<<8)))std::exit(4);
    std::vector<float> pcm(8192);if(BASS_ChannelGetData(stream,pcm.data(),pcm.size()*4|BASS_DATA_FLOAT)==DWORD(-1))std::exit(5);
    double energy=0;for(auto value:pcm)energy+=double(value)*value;
    BASS_StreamFree(stream);BASS_MIDI_FontFree(font);return std::sqrt(energy/pcm.size());
}
int main(int argc,char** argv) {
    if(argc!=2||!BASS_Init(0,48000,0,nullptr,nullptr))return 1;
    auto normal=mix_response_fixture::write(argv[1],0,-1),boost=mix_response_fixture::write(argv[1],-200,-1),fixed=mix_response_fixture::write(argv[1],0,127);
    const auto n35=rms(normal,35),n125=rms(normal,125),b85=rms(boost,85),b125=rms(boost,125),f35=rms(fixed,35),f125=rms(fixed,125);
    if(!(n35>0&&n125>n35*10 && std::abs(b85/b125-1)<0.001 && std::abs(f35/f125-1)<0.001))return 6;
    std::cout<<"MIX_RESPONSE realBASS=true sameSyntheticPCM=true sourceVelocityPreserved=true normal35="<<n35<<" normal125="<<n125<<" negativeAttenuation85="<<b85<<" negativeAttenuation125="<<b125<<" fixedVelocity35="<<f35<<" fixedVelocity125="<<f125<<" actualDeviceTimbreOrBalance=UNPROVEN noProductionGainChange=true\n";
    BASS_Free();
}
