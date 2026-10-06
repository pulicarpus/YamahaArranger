#include "bassmidi_player.h"
#define main roleFixtureMain
#include "role_pcm_real_bass_test.cpp"
#undef main
#include <cassert>
class MeasuredBalanceTestAccess {
public:
 static void fingerprint(BassMidiPlayer& p,const std::string& path,const std::string& value){p.percussionFingerprints_[path]=value;}
 static bool slot(BassMidiPlayer& p,int ch,float db){return p.measuredBalance_[ch-10].dsp && p.measuredBalance_[ch-10].db==db;}
};
int main(int argc,char** argv){
 if(argc!=3)return 2;const std::string dir=argv[1];bool enabled=std::string(argv[2])=="on";
 BassMidiPlayer p;auto piano=font(dir,0,0,"Piano",-1),guitar=font(dir,8,1,"Guitar",127),strings=font(dir,0,49,"Strings",-1);
 assert(p.loadMelody(piano));assert(p.loadMelodyFallback(guitar));assert(p.loadMelodyFallback(strings));assert(p.loadDrum(percussion_fixture::write(dir,"drums.sf2",128,0,true)));
 if(enabled){
  // Test-only injection of measured identity into deterministic PCM fixtures.
  // The production lookup, gates, DSP, controller and note methods are unchanged.
  const std::string melody="e8c7356159c200d945f13e113595ff06e7955779b0e9361709d9c7cdba164a82";
  MeasuredBalanceTestAccess::fingerprint(p,piano,melody);MeasuredBalanceTestAccess::fingerprint(p,guitar,melody);
  MeasuredBalanceTestAccess::fingerprint(p,strings,"de5b1404630840a2a897e77c6083e2231c5d1523e6571a8afd972a4f684d36ce");
 }
 p.setChannelPreset(0,0,0,"Piano");p.setChannelPreset(8,128,0,"StandardKit");
 p.setChannelPreset(11,0,0,"Piano");p.setChannelPreset(12,8*128,1,"Guitar");p.setChannelPreset(13,0,49,"Strings");p.setChannelPreset(14,0,49,"Strings");
 if(enabled){assert(MeasuredBalanceTestAccess::slot(p,11,-6));assert(MeasuredBalanceTestAccess::slot(p,12,-6));assert(MeasuredBalanceTestAccess::slot(p,13,12));assert(MeasuredBalanceTestAccess::slot(p,14,12));}
 p.setMasterGain(.4f);std::vector<float> block(512);for(int i=0;i<16;++i)p.render(block.data(),256);
 std::ofstream csv(dir+"/response.tsv");unsigned cells=0;
 for(int ch:{0,8,11,12,13,14})for(int volume:{52,81})for(int expression:{0,49,83,110,127})for(int velocity:{26,39,75,127}){
  p.allNotesOff();for(int i=0;i<32;++i)p.render(block.data(),256);
  p.setChannelMixer(ch,volume,64,expression,0,0);p.noteOn(ch,ch==8?33:60,float(velocity)/127,AudioPathOrigin{ch,60,0,cells,cells});
  double energy=0;float peak=0;for(int i=0;i<32;++i){p.render(block.data(),256);for(float x:block){energy+=double(x)*x;peak=std::max(peak,std::abs(x));}}
  csv<<ch<<'\t'<<volume<<'\t'<<expression<<'\t'<<velocity<<'\t'<<std::sqrt(energy/(32*512))<<'\t'<<peak<<'\n';
  p.noteOff(ch,ch==8?33:60,AudioPathOrigin{ch,60,0,cells,cells});++cells;
 }
 auto before=eventTrace.size();auto report=p.noteZoneReport();assert(eventTrace.size()==before);std::ofstream(dir+"/balance-report.txt")<<report;
 // A different file identity immediately removes the binding trim (no gain floor).
 MeasuredBalanceTestAccess::fingerprint(p,piano,"DIFFERENT_CONTENT");p.setChannelPreset(11,0,0,"Piano");for(int i=0;i<16;++i)p.render(block.data(),256);
 assert(p.noteZoneReport().find("MEASURED_BALANCE ch=11 active=0 fingerprint=DIFFERENT_CONTENT trimDb=0 currentGain=1 targetGain=1")!=std::string::npos);
 // Preset changes plus repeated/simultaneous notes never create MIDI from trimming.
 for(int i=0;i<20;++i){p.noteOn(13,60,.4f,AudioPathOrigin{13,60,0,i,i});p.noteOn(13,60,.6f,AudioPathOrigin{13,60,0,i,i+100});p.noteOn(14,64,.3f,AudioPathOrigin{14,64,0,i,i+200});p.render(block.data(),256);p.noteOff(13,60);p.noteOff(13,60);p.noteOff(14,64);}
 p.unload();std::ofstream trace(dir+"/midi.bin",std::ios::binary);trace.write(reinterpret_cast<const char*>(eventTrace.data()),eventTrace.size()*sizeof(RoutedEvent));
 std::cout<<"MEASURED_BALANCE_REAL cells="<<cells<<" controllers_velocity_NOTE_ON_OFF_preserved=true multi_SF2=true mismatch_unity=true\n";
}
