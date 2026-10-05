#include "bassmidi_player.h"
#include "mix_response_fixture.h"
#include <jni.h>
#include <iostream>
#include <vector>
#include <limits>
JavaVM* g_jvm=nullptr;jclass g_debugLogClass=nullptr;jmethodID g_debugLogAddMethod=nullptr;
int main(int argc,char** argv) {
 if(argc!=2)return 2;int checks=0;auto require=[&](bool b,const char* msg){++checks;if(!b){std::cerr<<msg<<'\n';std::exit(1);}};
 BassMidiPlayer player;require(player.loadMelody(mix_response_fixture::write(argv[1],0,-1,"Guitar")),"load");
 player.setChannelPreset(0,0,24,"Guitar");player.setChannelPreset(8,128,0,"StandardKit");
 require(mock_bass::roleChannelRequests.empty(),"no keyboard/rhythm child streams");
 player.setChannelPreset(12,0,24,"Guitar");
 require(mock_bass::roleChannelRequests.size()==1 && mock_bass::roleChannelRequests[0].second==12,"melodic child only");
 auto& dsp=mock_bass::dsps.front();require(dsp.flags==BASS_DSP_READONLY,"readonly flag");
 auto prior=mock_bass::history.size();player.noteZoneReport();require(mock_bass::history.size()==prior,"export sends no events");
 auto before=player.noteZoneReport();require(before.find("readbackStage=NO_REAL_NOTE")!=std::string::npos,"not verified before note");
 player.setChannelMixer(12,79,64,117,60,30);
 player.noteOn(12,60,42.0f/127,AudioPathOrigin{12,60,0,0,1});
 require(mock_bass::noteOns==1 && mock_bass::auditionNoteOns==0,"no diagnostic note duplication");
 player.setChannelExpression(12,20); // automation without an intervening NOTE_ON
 std::vector<float> pcm={.2f,-.4f,0,1.1f};const auto original=pcm;dsp.callback(1,dsp.stream,pcm.data(),pcm.size()*4,dsp.user);
 require(pcm==original,"PCM callback immutable");std::vector<float> out(512);player.render(out.data(),256);
 auto report=player.noteZoneReport();require(report.find("bindingVerified=1")!=std::string::npos,"first-note physical preset verified");
 require(report.find("callbackSamples=4")!=std::string::npos,"real callback samples");
 require(report.find("clippedSamples=1")!=std::string::npos,"passive clip counter");
 require(report.find("styleOns=1")!=std::string::npos && report.find("velocityP50=42")!=std::string::npos,"style velocity preserved");
 require(report.find("CC7Range=79:79 CC11Range=20:117")!=std::string::npos,"effective controllers unchanged");
 for(int i=0;i<9;++i)require(player.loadMelody(mix_response_fixture::write(argv[1],0,-1,"Guitar")),"reload");
 report=player.noteZoneReport();require(report.find("windows=8 omittedWindows=2")!=std::string::npos,"bounded windows with counted omissions");
 // Failed tap installation cannot reject existing production routing.
 mock_bass::failDsp=true;player.setChannelPreset(11,0,24,"Guitar");auto ons=mock_bass::noteOns;
 player.noteOn(11,60,.5f,AudioPathOrigin{11,60,0,0,2});require(mock_bass::noteOns==ons+1,"tap failure preserves notes");mock_bass::failDsp=false;
 for(const auto& q:mock_bass::roleChannelRequests)require(q.second>=10 && q.second<=15,"never drum/keyboard GetChannel");
 player.unload();require(!mock_bass::dsps[0].callback,"DSP removed before parent free");
 for(const auto& event:mock_bass::history)require(std::get<0>(event)==1,"all MIDI remains original stream");
 std::cout<<"ROLE_PCM_NATIVE checks="<<checks<<" production_player=true no_extra_NOTE_ON=true PCM_buffer_unchanged=true cap_and_cleanup=true\n";
}
