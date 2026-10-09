#pragma once
#include <chrono>
// F12_TIMING_BEGIN
#include <atomic>
#include <mutex>
#include <thread>
// F12_TIMING_END
#include <string>
#include <vector>
#include <deque>
#include <sstream>
#include <array>
#include <map>
#include "audio_path_diagnostic.h"
#include "voice_resolver.h"

// Explicit, finite capture. No logging/formatting, file IO or SF2 scan at send.
namespace chord_diagnostic {
inline uint64_t monoNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
inline int64_t wallMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}
struct Row {
    AudioPathOrigin origin;
    uint64_t captureOrder=0, midiOrder=0, mono=0, mapGeneration=0;
    int64_t windowChordId=0;
    int64_t wall=0;
    std::string stage, requestedName, selectedName, liveName, liveSf2, controlType;
    int channel=0,key=-1,velocity=0,sent=-1,error=0,event=0,param=0;
    int requestedBank=-1,requestedPc=-1,initialized=0,drum=0;
    int dstMsb=-1,dstLsb=-1,dstPc=-1,sourceRawBank=-1,sourcePc=-1;
    unsigned expectedFont=0,liveFont=0;
    int expectedBank=-1,liveBank=-1,livePc=-1,liveOk=0,mappingMatch=0;
    int cc7=-1,cc11=-1,cc64=-1;
    int keyBefore=-1,keyAfter=-1,previousOnOperation=-1,sameEventRepeat=0;
    int64_t previousOnId=0,previousOnAgeMs=-1,oldestOnAgeMs=-1;
};
class Capture {
public:
    static constexpr size_t cap=4096;
    std::vector<Row> rows;
    uint64_t deadline=0, dropped=0, order=0;
    bool armed=false;
    uint64_t windowUntil=0;
    int64_t windowChordId=0;
    std::deque<Row> recentPiano;
    // Accepted MIDI requests since ARM, NOT BASS voices or PCM. FIFO is an
    // observer model only; release tails/sustain may remain after NOTE_OFF.
    struct SeenOn { uint64_t mono=0; int64_t id=0; int operation=-1; };
    std::array<std::deque<SeenOn>,128> acceptedKeys;
    std::array<SeenOn,128> lastOn{};
    uint64_t ledgerOverflow=0;
    void mark(int64_t id,uint64_t now=monoNs()) {
        if(!active(now) || !id) return;
        windowChordId=id; windowUntil=now+750000000ULL;
        // Pending ch11 context is bounded to 32 rows / 250ms and flushed once.
        for(auto& row:recentPiano) if(row.mono<=now && now-row.mono<=250000000ULL) {
            row.windowChordId=id; retain(std::move(row));
        }
        recentPiano.clear();
    }
    bool interested(int channel,const AudioPathOrigin& origin,bool control,uint64_t now) const {
        return active(now) && (channel==11 || (origin.operation==1 && origin.chordId!=0) ||
            (control && windowChordId!=0 && now<windowUntil));
    }
    void retain(Row row) {
        if(rows.size()==cap) { ++dropped; return; }
        rows.push_back(std::move(row));
    }
    void arm(uint64_t now=monoNs()) {
        rows.clear(); rows.reserve(cap); dropped=order=0;
        for(auto& key:acceptedKeys) key.clear();
        lastOn={}; ledgerOverflow=0;
        recentPiano.clear(); windowUntil=0; windowChordId=0;
        deadline=now+60000000000ULL; armed=true;
    }
    bool active(uint64_t now=monoNs()) const { return armed && now<deadline; }
    void stop() { armed=false; recentPiano.clear(); }
    void append(Row row) {
        if(!active(row.mono)) return;
        if(row.channel==11 && row.key>=0 && row.key<128) {
            auto& key=acceptedKeys[row.key]; const auto previous=lastOn[row.key];
            row.keyBefore=static_cast<int>(key.size());
            row.previousOnId=previous.id; row.previousOnOperation=previous.operation;
            if(previous.mono && row.mono>=previous.mono) row.previousOnAgeMs=(row.mono-previous.mono)/1000000;
            if(!key.empty() && row.mono>=key.front().mono) row.oldestOnAgeMs=(row.mono-key.front().mono)/1000000;
            if(row.stage=="NOTE_POST" && row.sent==1) {
                row.sameEventRepeat=row.origin.id!=0 && previous.id==row.origin.id && previous.operation==row.origin.operation;
                if(key.size()==16) { key.pop_front(); ++ledgerOverflow; }
                key.push_back({row.mono,row.origin.id,row.origin.operation});
                lastOn[row.key]={row.mono,row.origin.id,row.origin.operation};
            } else if(row.stage=="OFF_POST" && row.sent==1 && !key.empty()) key.pop_front();
            row.keyAfter=static_cast<int>(key.size());
        } else if(row.channel==11 && row.stage=="CONTROL_POST" && row.sent==1 && row.controlType=="NOTES_OFF") {
            for(auto& key:acceptedKeys) key.clear();
        }
        row.captureOrder=++order;
        if(row.origin.operation==1 && row.origin.chordId!=0 && windowChordId!=row.origin.chordId)
            mark(row.origin.chordId,row.mono); // Defensive fallback for diagnostic callers.
        row.windowChordId=windowChordId;
        if(row.stage=="BASELINE" || (row.origin.operation==1 && row.origin.chordId!=0) ||
            (windowChordId!=0 && row.mono<windowUntil)) { retain(std::move(row)); return; }
        if(row.channel==11) {
            while(!recentPiano.empty() && row.mono-recentPiano.front().mono>250000000ULL) recentPiano.pop_front();
            if(recentPiano.size()==32) recentPiano.pop_front();
            recentPiano.push_back(std::move(row));
        }
    }
};
// UTF-8-safe name shortening, explicit marker; never split a multibyte codepoint.
inline std::string shortName(const std::string& name) {
    if(name.size()<=64) return name;
    size_t end=61; while(end && (static_cast<unsigned char>(name[end])&0xc0)==0x80) --end;
    return name.substr(0,end)+"...";
}
inline std::string stateText(const Row& row) {
    std::ostringstream state;
    state << "request=" << row.requestedBank << ':' << row.requestedPc << " voice='" << shortName(row.requestedName)
          << "' dstState=" << row.dstMsb << ':' << row.dstLsb << ':' << row.dstPc
          << " live=" << row.liveFont << ':' << row.liveBank << ':' << row.livePc
          << " preset='" << shortName(row.liveName) << "' sf2='" << shortName(row.liveSf2)
          << "' family=" << voice_resolver::familyName(voice_resolver::namedFamily(row.liveName))
          << " expected=" << row.expectedFont << ':' << row.expectedBank << ':' << row.sourcePc
          << " liveOk=" << row.liveOk << " match=" << row.mappingMatch << " init=" << row.initialized
          << " gen=" << row.mapGeneration << " CC=" << row.cc7 << '/' << row.cc11 << '/' << row.cc64;
    return state.str();
}
inline std::string compactReport(const Capture& capture) {
    constexpr size_t byteCap=30*1024, reservedFooter=200;
    std::ostringstream head;
    head << "=== CHORD NATIVE COMPACT v2 ===\nrows=" << capture.rows.size() << " captureDropped=" << capture.dropped
         << " ledgerOverflow=" << capture.ledgerOverflow << " active=" << capture.active() << " maxBytes=" << byteCap
         << " windows=chord_minus250ms_plus750ms\n"
         << "S=state dictionary; b/a reference actual PRE/POST independently. NOTE_PRE/POST=NOTE_ON, OFF_PRE/POST=NOTE_OFF. "
         << "m/t=native MIDI order/wallMs before>after. sent=1 accepted,0 rejected,-1 unknown. op=1 retarget,0 normal. "
         << "keys=accepted ch11 MIDI request FIFO before>after since ARM, not voice/PCM counts; pre-arm notes unknown. "
         << "prev=id:op:ageMs; oldestMs=FIFO age. repeat=same event/key accepted twice. CC=7/11/64. "
         << "Priority: ch11 retarget, other Piano/mismatch, baseline/control, ch11 normal context, other retarget.\n";
    struct Unit { const Row* before; const Row* after; };
    std::vector<Unit> units;
    for(size_t i=0;i<capture.rows.size();++i) {
        const auto& r=capture.rows[i];
        if(i+1<capture.rows.size() && (r.stage=="NOTE_PRE" || r.stage=="OFF_PRE")) {
            const auto& a=capture.rows[i+1];
            const bool pair=a.stage==(r.stage=="NOTE_PRE" ? "NOTE_POST" : "OFF_POST") &&
                a.channel==r.channel && a.key==r.key && a.origin.id==r.origin.id &&
                a.origin.chordId==r.origin.chordId && a.origin.operation==r.origin.operation &&
                a.origin.sourceChannel==r.origin.sourceChannel && a.origin.sourceNote==r.origin.sourceNote &&
                a.origin.tick==r.origin.tick && a.origin.styleBank==r.origin.styleBank && a.windowChordId==r.windowChordId;
            if(pair) { units.push_back({&r,&a}); ++i; continue; }
        }
        units.push_back({nullptr,&r});
    }
    auto priority=[](const Row& r) {
        if(r.channel==11 && r.origin.operation==1) return 0;
        if(r.channel!=11 && (r.livePc==0 || voice_resolver::namedFamily(r.liveName)==voice_resolver::Family::Piano || !r.mappingMatch)) return 1;
        if(r.stage=="BASELINE" || r.stage=="CONTROL_POST") return 2;
        if(r.channel==11) return 3;
        return 4;
    };
    std::string out=head.str(); size_t omittedRows=0,omittedUnits=0;
    std::map<std::string,int> states;
    for(int group=0;group<5;++group) for(const auto& unit:units) {
        const Row& a=*unit.after; const Row& b=unit.before ? *unit.before : a;
        if(priority(a)!=group) continue;
        // Stage both state definitions atomically with their event. A missing
        // PRE/POST remains explicitly unpaired; no synthetic readback is made.
        auto nextStates=states; std::string definitions;
        auto stateId=[&](const Row& r) {
            auto value=stateText(r); auto found=nextStates.find(value);
            if(found!=nextStates.end()) return found->second;
            int id=static_cast<int>(nextStates.size()+1); nextStates.emplace(value,id);
            definitions+="S s="+std::to_string(id)+" "+value+"\n"; return id;
        };
        int beforeId=unit.before ? stateId(b) : -1, afterId=stateId(a);
        std::ostringstream line;
        line << "N order=" << b.captureOrder << '>' << a.captureOrder << " m=" << b.midiOrder << '>' << a.midiOrder
             << " t=" << b.wall << '>' << a.wall << " context=" << a.windowChordId
             << " chordId=" << a.origin.chordId << " id=" << a.origin.id << " op=" << a.origin.operation << ' '
             << (unit.before ? (a.stage=="NOTE_POST" ? "NOTE_PRE/POST" : "OFF_PRE/POST") : a.stage)
             << " dst=" << a.channel << " src=" << a.origin.sourceChannel << " original=" << a.origin.sourceNote
             << " output=" << a.key << " v=" << a.velocity << " tick=" << a.origin.tick
             << " sent=" << a.sent << " error=" << a.error << " b=" << beforeId << " a=" << afterId;
        if(a.channel==11 && a.key>=0) line << " keys=" << b.keyBefore << '>' << a.keyAfter
             << " prev=" << a.previousOnId << ':' << a.previousOnOperation << ':' << a.previousOnAgeMs
             << " oldestMs=" << b.oldestOnAgeMs << " repeat=" << a.sameEventRepeat;
        if(a.stage=="CONTROL_POST") line << " control=" << a.controlType << " value=" << a.param;
        line << '\n'; const auto text=definitions+line.str();
        if(out.size()+text.size()+reservedFooter>byteCap) { omittedRows+=unit.before ? 2 : 1; ++omittedUnits; continue; }
        states=std::move(nextStates); out+=text;
    }
    out+="exportOmittedRows="+std::to_string(omittedRows)+" exportOmittedUnits="+std::to_string(omittedUnits)+" (captureDropped is separate).\n";
    return out;
}
}

// F12_TIMING_BEGIN
namespace f12 {
enum Kind { Other=0, NoteOn=1, NoteOff=2, Preset=3, Mixer=4, Render=5, CallbackGap=6, Chord=7, CallbackDuration=8, Count=9 };
struct Timing {
    static constexpr unsigned Capacity=64, Width=6;
    static_assert(std::atomic<uint32_t>::is_always_lock_free,"F12 requires lock-free 32-bit atomics");
    std::atomic<uint32_t> enabled{0},writers{0},used{0},dropped{0},chord{0},previousCallback{0},previousBudget{0},overwritten{0};
    std::atomic<uint32_t> counts[Count]{},maxWait[Count]{},maxHold[Count]{},rows[Capacity*Width]{};
    std::atomic<uint32_t> priorityUsed{0},priorityDrop{0},slowDrop{0},focusAt{0},slowUsed[Count]{},priority[32*Width]{},slow[Count*4*Width]{};
    uint64_t started=0,deadline=0; // only read while enabled; reset after writers drain
    static uint64_t now(){return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();}
    static uint32_t us(uint64_t t){return uint32_t(t/1000);}
    uint64_t begin() const {return enabled.load(std::memory_order_seq_cst)?now():0;}
    static void maximum(std::atomic<uint32_t>& a,uint32_t v){auto old=a.load(std::memory_order_relaxed);while(old<v&&!a.compare_exchange_weak(old,v,std::memory_order_relaxed)){} }
    void stop(){enabled.store(0,std::memory_order_seq_cst);while(writers.load(std::memory_order_seq_cst))std::this_thread::yield();}
    void arm(){stop();used=0;dropped=0;chord=0;previousCallback=0;previousBudget=0;overwritten=0;priorityUsed=0;priorityDrop=0;slowDrop=0;focusAt=0;for(auto& r:priority)r=0;for(auto& r:slow)r=0;for(auto& n:slowUsed)n=0;for(unsigned k=0;k<Count;++k){counts[k]=0;maxWait[k]=0;maxHold[k]=0;}for(auto& r:rows)r=0;started=now();deadline=started+60000000000ULL;enabled.store(1,std::memory_order_seq_cst);}
    void record(Kind k,uint64_t at,uint32_t wait,uint32_t hold,bool marker=false){
        if(!at||!enabled.load(std::memory_order_seq_cst))return;
        writers.fetch_add(1,std::memory_order_seq_cst);
        if(!enabled.load(std::memory_order_seq_cst)){writers.fetch_sub(1,std::memory_order_seq_cst);return;}
        if(at>=started && at<=deadline){
            counts[k].fetch_add(1,std::memory_order_relaxed);maximum(maxWait[k],wait);maximum(maxHold[k],hold);
            if(k==Chord){uint32_t zero=0;focusAt.compare_exchange_strong(zero,us(at));}
            if(marker||(k!=CallbackGap&&(wait>=2000||hold>=2000))){
                if(k==Chord){auto n=priorityUsed.fetch_add(1);if(n<32)write(priority+n*Width,n+1,k,at,wait,hold);else ++priorityDrop;}
                else {auto focus=focusAt.load();if(focus && uint32_t(us(at)-focus)<=1000000){auto n=slowUsed[k].fetch_add(1);if(n<4)write(slow+(k*4+n)*Width,n+1,k,at,wait,hold);else ++slowDrop;}
                unsigned n=used.fetch_add(1,std::memory_order_relaxed);auto* r=rows+(n%Capacity)*Width;auto state=r[0].load(std::memory_order_relaxed);if(state!=UINT32_MAX && r[0].compare_exchange_strong(state,UINT32_MAX,std::memory_order_acq_rel)){if(n>=Capacity)overwritten.fetch_add(1,std::memory_order_relaxed);r[1]=uint32_t(k);r[2]=us(at);r[3]=wait;r[4]=hold;r[5]=chord.load(std::memory_order_relaxed);r[0].store(n+1,std::memory_order_release);}else dropped.fetch_add(1,std::memory_order_relaxed);}}
        }
        writers.fetch_sub(1,std::memory_order_seq_cst);
    }
    void callback(uint64_t at,int frames,int rate){
        if(!at)return;
        writers.fetch_add(1,std::memory_order_seq_cst);
        if(!enabled.load(std::memory_order_seq_cst)||at<started||at>deadline){writers.fetch_sub(1,std::memory_order_seq_cst);return;}
        auto t=us(at);auto old=previousCallback.exchange(t,std::memory_order_relaxed);
        auto budget=previousBudget.exchange(rate>0?uint32_t(uint64_t(frames)*1000000/rate):0,std::memory_order_relaxed);
        writers.fetch_sub(1,std::memory_order_seq_cst);
        if(old)record(CallbackGap,at,budget,uint32_t(t-old),uint32_t(t-old)>budget+2000);
    }
    void write(std::atomic<uint32_t>* r,unsigned n,Kind k,uint64_t at,uint32_t wait,uint32_t hold){r[1]=uint32_t(k);r[2]=us(at);r[3]=wait;r[4]=hold;r[5]=chord.load();r[0].store(n,std::memory_order_release);}
    std::string report() const {
        std::ostringstream s;s<<"F12_NATIVE clock=steady_monotonic_us_mod32 cap=64 slowThresholdUs=2000 callbackGapRows=previousFrameBudgetPlus2000Us overwritten="<<overwritten.load()<<" dropped="<<dropped.load()<<" active="<<enabled.load()<<" priorityCap=32 priorityDrop="<<priorityDrop.load()<<" slowCap=36 slowDrop="<<slowDrop.load()<<" priorityOverwrite=0 slowOverwrite=0 focus=firstChord_plus1s rowPayloadBytes=3168\n";
        for(unsigned k=0;k<Count;++k)s<<"F12_NATIVE_SUM kind="<<k<<" count="<<counts[k].load()<<" maxWaitUs="<<maxWait[k].load()<<" maxHoldUs="<<maxHold[k].load()<<"\n";
        auto emit=[&](const std::atomic<uint32_t>* a,unsigned capacity,const char* pool){for(unsigned n=0;n<capacity;++n){auto* r=a+n*Width;if(r[0].load(std::memory_order_acquire)!=0 && r[0].load()!=UINT32_MAX)s<<"F12_NATIVE_ROW pool="<<pool<<" order="<<r[0].load()<<" kind="<<r[1].load()<<" atUs="<<r[2].load()<<" waitUs="<<r[3].load()<<" holdUs="<<r[4].load()<<" chordId="<<r[5].load()<<"\n";}};
        emit(priority,32,"priority");emit(slow,Count*4,"focus");emit(rows,Capacity,"recent");return s.str();
    }
};
static_assert(sizeof(Timing)<=4096,"F12 native recorder memory bound");
inline Timing timing;
class SynthLock {
    uint64_t requested_;std::lock_guard<std::mutex> lock_;uint64_t acquired_;Kind kind_;
public:
    SynthLock(std::mutex& mutex,Kind kind):requested_(timing.begin()),lock_(mutex),acquired_(requested_?Timing::now():0),kind_(kind){}
    ~SynthLock(){if(requested_){auto end=Timing::now();timing.record(kind_,requested_,uint32_t((acquired_-requested_)/1000),uint32_t((end-acquired_)/1000));}}
};
class Callback {
    uint64_t at_;
public:
    Callback(int frames,int rate):at_(timing.begin()){timing.callback(at_,frames,rate);}
    ~Callback(){if(at_)timing.record(CallbackDuration,at_,0,uint32_t((Timing::now()-at_)/1000));}
};
}
// F12_TIMING_END
