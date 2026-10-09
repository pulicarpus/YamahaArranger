#include "chord_change_diagnostic.h"
#include <cassert>
#include <condition_variable>
#include <iostream>
int main(){
 auto& t=f12::timing;
 assert(!t.begin());t.record(f12::Render,f12::Timing::now(),10000,10000);assert(t.used==0);
 t.arm();auto at=t.begin();t.record(f12::Render,at,77000,100);assert(t.counts[f12::Render]==1);assert(t.maxWait[f12::Render]==77000);
 t.callback(at,448,48000);t.callback(at+9333000,448,48000);assert(t.counts[f12::CallbackGap]==1);assert(t.maxHold[f12::CallbackGap]==9333);
 t.callback(at+109333000,448,48000);assert(t.maxHold[f12::CallbackGap]==100000);
 for(int i=0;i<200;++i)t.record(f12::Chord,at+i*1000,0,0,true);
 assert(t.priorityUsed==200);assert(t.priorityDrop==168);assert(t.report().size()<20000);
 t.stop();t.arm();at=t.begin();t.chord=42;t.record(f12::Chord,at,0,0,true);
 t.record(f12::Render,at+1000,77000,100);
 for(int i=0;i<5000;++i)t.record(f12::Render,at+2000+i*1000,3000,3000);
 auto report=t.report();assert(report.find("pool=priority order=1 kind=7")!=std::string::npos);
 assert(report.find("pool=focus order=1 kind=5")!=std::string::npos);assert(report.find("waitUs=77000")!=std::string::npos);
 assert(t.slowDrop==4997);assert(t.overwritten==4937);assert(t.priorityDrop==0);
 t.stop();auto n=t.used.load();t.record(f12::Render,at,1,2,true);assert(t.used==n);assert(!t.begin());
 t.arm();assert(t.used==0);t.record(f12::Render,at,1,2,true);assert(t.used==0); // stale token from previous arm
 std::mutex mutex;mutex.lock();std::atomic<bool> entering{false},acquired{false};
 std::thread worker([&]{entering=true;f12::SynthLock lock(mutex,f12::NoteOn);acquired=true;});
 while(!entering)std::this_thread::yield();assert(!acquired);mutex.unlock();worker.join();assert(acquired);assert(t.counts[f12::NoteOn]==1);
 t.stop();assert(mutex.try_lock());mutex.unlock();
 std::thread producer([&]{for(int i=0;i<10000;++i)t.record(f12::Render,t.begin(),3000,3000);});
 for(int i=0;i<200;++i){t.arm();t.stop();}
 producer.join();t.stop();assert(t.writers==0);
 std::cout<<"F12_NATIVE_TEST PASS disabled, timing, callback budget/gap, saturation, stale arm, real mutex semantics\n";
}
