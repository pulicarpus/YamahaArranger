#pragma once
#include <bass.h>
#include <bassmidi.h>
#include <array>
#include <algorithm>
#include <sstream>
#include <string>

// Owned exclusively by BassMidiPlayer under its existing mutex. No filesystem,
// allocation, preset switch, FontLoad, or logging in on/off/render. Non-exclusive
// reviewed candidates only: unresolved hat families never enter this adapter.
class ExperimentalDrumLanes {
public:
    static constexpr int capacity=4, voices=32, blockFrames=2048;
    struct Owner {
        uint64_t token=0, generation=0;
        HSOUNDFONT font=0;
        HSTREAM stream=0;
        int bank=0,pc=0,key=0,lane=0,rhythm=0;
    };
    struct Route {
        HSOUNDFONT font=0; HSTREAM stream=0; uint64_t generation=0;
        int bank=0,pc=0,key=0,rhythm=0;
        std::string fingerprint;
        std::array<Owner,voices> owners{};
    };
    ~ExperimentalDrumLanes() { clear(); }
    bool enabled=false,healthy=true;
    uint64_t accepted=0, released=0, rejected=0, decodeFailures=0, releaseFailures=0, controllerFailures=0;
    int count=0;
    std::array<Route,capacity> routes{};
    uint64_t sequence=0;
    std::array<float,blockFrames*2> scratch{};

    bool hasOwners() const {
        for(int i=0;i<count;++i)for(const auto& o:routes[i].owners)if(o.token)return true;
        return false;
    }
    void flush() {
        for(int i=0;i<count;++i)for(auto& o:routes[i].owners)if(o.token)off(o.token);
    }
    void clear() {
        enabled=false; flush();
        for(int i=0;i<count;++i) {
            if(routes[i].stream)BASS_StreamFree(routes[i].stream);
            if(routes[i].font)BASS_MIDI_FontFree(routes[i].font);
            routes[i]=Route{};
        }
        count=0;healthy=true; // sequence is monotonic across resource generations/clears.
    }
    // STOP worker only. Private immutable, SHA-verified managed snapshot remains
    // alive until this route is freed. Success requires synchronous key preload,
    // verified actual font/bank/PC and a neutral pitch lane, without a NOTE_ON.
    int prepare(const std::string& path,const std::string& sha,int bank,int pc,int key,
                int rhythm,uint64_t generation,HSTREAM production,int rate,float fontVolume) {
        if(!healthy || enabled || hasOwners() || count==capacity || path.empty() || sha.size()!=64 ||
           bank<0 || bank>65535 || pc<0 || pc>127 || key<0 || key>127 ||
           (rhythm!=8 && rhythm!=9) || !production || !generation)return 0;
        Route r; r.font=BASS_MIDI_FontInit(path.c_str(),0);
        if(!r.font)return 0;
        auto fail=[&] {if(r.stream)BASS_StreamFree(r.stream);BASS_MIDI_FontFree(r.font);return 0;};
        if(!BASS_MIDI_FontGetPreset(r.font,pc,bank) || !BASS_MIDI_FontSetVolume(r.font,fontVolume) ||
           !BASS_MIDI_FontLoadEx(r.font,pc,bank,key,0))return fail(); // never NOWAIT on prepared lane
        r.stream=BASS_MIDI_StreamCreate(voices,BASS_SAMPLE_FLOAT|BASS_STREAM_DECODE|BASS_MIDI_NOTEOFF1,rate);
        if(!r.stream)return fail();
        BASS_MIDI_FONTEX2 mapping{};mapping.font=r.font;mapping.sbank=bank;mapping.spreset=pc;
        mapping.dbank=128;mapping.dpreset=pc;mapping.minchan=0;mapping.numchan=voices;
        if(!BASS_MIDI_StreamSetFonts(r.stream,&mapping,1|BASS_MIDI_FONT_EX2) ||
           !BASS_ChannelSetAttribute(r.stream,BASS_ATTRIB_MIDI_SRC,2) ||
           !BASS_ChannelSetAttribute(r.stream,BASS_ATTRIB_BUFFER,0))return fail();
        float gain=0;
        if(!BASS_ChannelGetAttribute(production,BASS_ATTRIB_MIDI_VOL,&gain) ||
           !BASS_ChannelSetAttribute(r.stream,BASS_ATTRIB_MIDI_VOL,gain))return fail();
        for(int ch=0;ch<voices;++ch) {
            if(!BASS_MIDI_StreamEvent(r.stream,ch,MIDI_EVENT_DRUMS,1) ||
               !BASS_MIDI_StreamEvent(r.stream,ch,MIDI_EVENT_BANK,128) ||
               !BASS_MIDI_StreamEvent(r.stream,ch,MIDI_EVENT_PROGRAM,pc) ||
               !BASS_MIDI_StreamEvent(r.stream,ch,MIDI_EVENT_PITCH,8192))return fail();
            BASS_MIDI_FONT actual{};
            if(!BASS_MIDI_StreamGetPreset(r.stream,ch,&actual) || actual.font!=r.font ||
               actual.bank!=bank || actual.preset!=pc ||
               BASS_MIDI_StreamGetEvent(r.stream,ch,MIDI_EVENT_PITCH)!=8192)return fail();
            for(auto event:controllerEvents) {
                const DWORD value=BASS_MIDI_StreamGetEvent(production,rhythm,event);
                if(value==DWORD(-1) || !BASS_MIDI_StreamEvent(r.stream,ch,event,value) ||
                   BASS_MIDI_StreamGetEvent(r.stream,ch,event)!=value)return fail();
            }
        }
        r.bank=bank;r.pc=pc;r.key=key;r.rhythm=rhythm;r.generation=generation;r.fingerprint=sha;
        routes[count]=std::move(r);return ++count;
    }
    uint64_t on(int id,int rhythm,int velocity,uint64_t generation) {
        if(!healthy || !enabled || id<=0 || id>count || velocity<1 || velocity>127) {++rejected;return 0;}
        auto& r=routes[id-1];
        if(r.generation!=generation || r.rhythm!=rhythm) {++rejected;return 0;}
        int slot=0;while(slot<voices && r.owners[slot].token)++slot;
        if(slot==voices) {++rejected;return 0;} // legacy before sending anything
        if(!BASS_MIDI_StreamEvent(r.stream,slot,MIDI_EVENT_NOTE,r.key|(velocity<<8))) {++rejected;return 0;}
        const uint64_t token=++sequence;
        r.owners[slot]={token,r.generation,r.font,r.stream,r.bank,r.pc,r.key,slot,r.rhythm};
        ++accepted;return token;
    }
    bool off(uint64_t token) {
        if(!token)return false;
        for(int i=0;i<count;++i)for(auto& o:routes[i].owners)if(o.token==token) {
            // Captured physical voice lane, even after flag/section/preset change.
            if(!BASS_MIDI_StreamEvent(o.stream,o.lane,MIDI_EVENT_NOTE,o.key)) {enabled=false;healthy=false;++releaseFailures;return false;}
            o.token=0;++released;return true;
        }
        return false; // duplicate/stale OFF cannot release a newer owner
    }
    bool controller(int rhythm,DWORD event,DWORD value) {
        bool ok=true;
        for(int i=0;i<count;++i)if(routes[i].rhythm==rhythm)
            for(int ch=0;ch<voices;++ch)ok=BASS_MIDI_StreamEvent(routes[i].stream,ch,event,value) && ok;
        if(!ok) {enabled=false;healthy=false;++controllerFailures;}
        return ok;
    }
    void gain(float value) {
        for(int i=0;i<count;++i)if(!BASS_ChannelSetAttribute(routes[i].stream,BASS_ATTRIB_MIDI_VOL,value)) {enabled=false;healthy=false;++controllerFailures;}
    }
    void renderAdd(float* output,int frames) {
        // Continue rendering release tails/held owners after flag OFF. No heap allocation.
        for(int i=0;i<count;++i)for(int offset=0;offset<frames;offset+=blockFrames) {
            const int n=std::min(blockFrames,frames-offset)*2;
            const DWORD got=BASS_ChannelGetData(routes[i].stream,scratch.data(),n*sizeof(float)|BASS_DATA_FLOAT);
            if(got==DWORD(-1) || got>n*sizeof(float) || got%sizeof(float)) {enabled=false;healthy=false;++decodeFailures;continue;}
            if(healthy)for(unsigned s=0;s<got/sizeof(float);++s)output[offset*2+s]+=scratch[s];
        }
    }
    std::string report() const {
        std::ostringstream s;s<<"NATIVE_DRUM_ADAPTER v1 enabled="<<enabled<<" resources="<<count
            <<" healthy="<<healthy<<" releaseFailures="<<releaseFailures<<" controllerFailures="<<controllerFailures<<" accepted="<<accepted<<" released="<<released<<" rejected="<<rejected<<" decodeFailures="<<decodeFailures<<" owners=";
        int owners=0;for(int i=0;i<count;++i)for(const auto& o:routes[i].owners)owners+=o.token!=0;s<<owners<<'\n';
        for(int i=0;i<count;++i) {const auto& r=routes[i];s<<"ROUTE id="<<i+1<<" sha="<<r.fingerprint<<" handle="<<r.font<<" stream="<<r.stream
            <<" generation="<<r.generation<<" bank="<<r.bank<<" pc="<<r.pc<<" key="<<r.key<<" rhythm="<<r.rhythm<<" ownerLanes="<<voices
            <<" preload=SYNC_KEY pitch=8192 velocity=BYTE_IDENTITY controllers=MIRRORED choke=NON_EXCLUSIVE_ONLY\n";}
        return s.str();
    }
private:
    static constexpr std::array<DWORD,5> controllerEvents{MIDI_EVENT_VOLUME,MIDI_EVENT_PAN,MIDI_EVENT_EXPRESSION,MIDI_EVENT_REVERB,MIDI_EVENT_CHORUS};
};
