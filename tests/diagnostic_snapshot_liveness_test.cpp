#include "bassmidi_player.h"
#include "mix_response_fixture.h"
#include <jni.h>
#include <locale>
#include <future>
#include <condition_variable>
#include <iostream>
JavaVM* g_jvm=nullptr;jclass g_debugLogClass=nullptr;jmethodID g_debugLogAddMethod=nullptr;
struct Gate {std::mutex mutex;std::condition_variable cv;bool entered=false,released=false;};
// Slow numeric formatting models a stalled diagnostic worker. This facet is
// consumed by the real production report, not a test copy of its implementation.
struct SlowNumbers:std::num_put<char> {
 Gate& gate;explicit SlowNumbers(Gate& g):gate(g){}
 iter_type do_put(iter_type out,std::ios_base& stream,char fill,unsigned long v) const override {
  {std::unique_lock<std::mutex> lock(gate.mutex);gate.entered=true;gate.cv.notify_all();gate.cv.wait(lock,[&]{return gate.released;});}
  return std::num_put<char>::do_put(out,stream,fill,v);
 }
};
int main(int argc,char** argv){
 if(argc!=2)return 2;BassMidiPlayer player;
 if(!player.loadMelody(mix_response_fixture::write(argv[1],0,-1,"Guitar")))return 3;
 player.setChannelPreset(12,0,24,"Guitar");player.noteOn(12,60,42.f/127,AudioPathOrigin{12,60,0,0,1});
 const auto expected=player.noteZoneReport();const auto count=mock_bass::history.size();
 Gate gate;const auto prior=std::locale::global(std::locale(std::locale(),new SlowNumbers(gate)));
 auto exportJob=std::async(std::launch::async,[&]{return player.noteZoneReport();});
 {std::unique_lock<std::mutex> lock(gate.mutex);if(!gate.cv.wait_for(lock,std::chrono::seconds(2),[&]{return gate.entered;})){std::cerr<<"formatter not reached\n";std::exit(4);}}
 // Same lock-taking production render and UI synth method. The report is still
 // stalled, yet both must complete, without cancelling/removing the ROLE_PCM tap.
 auto audio=std::async(std::launch::async,[&]{float out[512];player.render(out,256);player.setMasterGain(.8f);});
 const bool ready=audio.wait_for(std::chrono::milliseconds(500))==std::future_status::ready;
 {std::lock_guard<std::mutex> lock(gate.mutex);gate.released=true;}gate.cv.notify_all();
 audio.get();const auto actual=exportJob.get();std::locale::global(prior);
 if(!ready){std::cerr<<"ANR_LOCK_REGRESSION: export formatter blocks render/UI synth operation\n";return 5;}
 if(actual!=expected || actual.find("ROLE_LAYER ")==std::string::npos || mock_bass::history.size()!=count){std::cerr<<"snapshot changed/evidence lost/MIDI sent\n";return 6;}
 player.unload();
 std::cout<<"DIAGNOSTIC_LIVENESS checks=4 blocked_formatter_does_not_block_render_or_UI_synth=true snapshot_consistent=true ROLE_LAYER_preserved=true export_no_MIDI=true\n";
}
