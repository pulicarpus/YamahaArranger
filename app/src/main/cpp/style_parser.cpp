#include "style_parser.h"
#include <algorithm>
#include <cctype>
#include <cstring>

namespace { std::string toLower(std::string s){std::transform(s.begin(),s.end(),s.begin(),[](unsigned char c){return std::tolower(c);});return s;} uint32_t be32(const uint8_t*p){return(uint32_t(p[0])<<24)|(uint32_t(p[1])<<16)|(uint32_t(p[2])<<8)|uint32_t(p[3]);} }
StyleSection StyleParser::classifyMarkerText(const std::string& text){std::string t=toLower(text);if(t.find("intro")!=std::string::npos){if(t.find('1')!=std::string::npos||t.find('a')!=std::string::npos)return StyleSection::IntroA;if(t.find('2')!=std::string::npos||t.find('b')!=std::string::npos)return StyleSection::IntroB;if(t.find('3')!=std::string::npos||t.find('c')!=std::string::npos)return StyleSection::IntroC;return StyleSection::IntroA;}if(t.find("main")!=std::string::npos){size_t pos=t.find("main")+4;while(pos<t.size()&&(t[pos]==' '||t[pos]=='_'||t[pos]=='-'))++pos;if(pos<t.size()){if(t[pos]=='a')return StyleSection::MainA;if(t[pos]=='b')return StyleSection::MainB;if(t[pos]=='c')return StyleSection::MainC;if(t[pos]=='d')return StyleSection::MainD;}return StyleSection::MainA;}if(t.find("fill")!=std::string::npos){size_t pos=t.find("fill")+4;while(pos<t.size()&&(t[pos]==' '||t[pos]=='_'||t[pos]=='-'))++pos;if(pos<t.size()){if(t[pos]=='a')return StyleSection::FillAA;if(t[pos]=='b')return StyleSection::FillBB;if(t[pos]=='c')return StyleSection::FillCC;if(t[pos]=='d')return StyleSection::FillDD;}if(t.find("aa")!=std::string::npos)return StyleSection::FillAA;if(t.find("bb")!=std::string::npos)return StyleSection::FillBB;if(t.find("cc")!=std::string::npos)return StyleSection::FillCC;if(t.find("dd")!=std::string::npos)return StyleSection::FillDD;if(t.find("ba")!=std::string::npos)return StyleSection::BreakDown;}if(t.find("break")!=std::string::npos)return StyleSection::BreakDown;if(t.find("ending")!=std::string::npos){if(t.find('1')!=std::string::npos||t.find('a')!=std::string::npos)return StyleSection::EndingA;if(t.find('2')!=std::string::npos||t.find('b')!=std::string::npos)return StyleSection::EndingB;if(t.find('3')!=std::string::npos||t.find('c')!=std::string::npos)return StyleSection::EndingC;return StyleSection::EndingA;}return StyleSection::Unknown;}
std::string StyleParser::trimAscii(const std::string& text){size_t a=0;while(a<text.size()&&std::isspace(static_cast<unsigned char>(text[a])))++a;size_t b=text.size();while(b>a&&std::isspace(static_cast<unsigned char>(text[b-1])))--b;return text.substr(a,b-a);}
void StyleParser::parseCasm(const uint8_t* data,size_t size){
    size_t casmStart=size;
    uint32_t casmLen=0;
    for(size_t i=0;i+8<=size;++i){
        if(std::memcmp(data+i,"CASM",4)==0){
            casmStart=i;
            casmLen=be32(data+i+4);
            break;
        }
    }
    if(casmStart==size||casmLen==0)return;

    const size_t casmEnd=std::min(size,casmStart+8+size_t(casmLen));
    size_t p=casmStart+8;

    while(p+8<=casmEnd){
        const char*tag=reinterpret_cast<const char*>(data+p);
        const uint32_t len=be32(data+p+4);
        const size_t payloadStart=p+8;
        const size_t payloadEnd=std::min(casmEnd,payloadStart+size_t(len));
        if(payloadStart>casmEnd||payloadEnd<payloadStart)break;

        if(std::memcmp(tag,"CSEG",4)==0){
            std::vector<StyleSection> csegSections;
            std::map<uint8_t,std::pair<uint8_t,bool>> cnttByChannel;
            size_t q=payloadStart;

            while(q+8<=payloadEnd){
                const char*subTag=reinterpret_cast<const char*>(data+q);
                const uint32_t subLen=be32(data+q+4);
                const size_t subStart=q+8;
                const size_t subEnd=std::min(payloadEnd,subStart+size_t(subLen));
                if(subStart>payloadEnd||subEnd<subStart)break;

                if(std::memcmp(subTag,"Sdec",4)==0){
                    std::string text(reinterpret_cast<const char*>(data+subStart),subEnd-subStart);
                    size_t begin=0;
                    while(begin<=text.size()){
                        size_t comma=text.find(',',begin);
                        std::string item=trimAscii(text.substr(begin,comma==std::string::npos?std::string::npos:comma-begin));
                        StyleSection sec=classifyMarkerText(item);
                        if(sec!=StyleSection::Unknown)csegSections.push_back(sec);
                        if(comma==std::string::npos)break;
                        begin=comma+1;
                    }
                }

                if(std::memcmp(subTag,"Cntt",4)==0&&subLen>=2){
                    // Cntt is a per-source-channel NTT override.
                    // Byte 0 = channel, byte 1 = NTT (bits 0..6) + Bass On (bit 7).
                    const uint8_t*d=data+subStart;
                    cnttByChannel[d[0]]=std::make_pair(static_cast<uint8_t>(d[1]&0x7F),(d[1]&0x80)!=0);
                }

                auto attachPolicy=[&](const CasmPolicy& policy){
                    for(StyleSection sec:csegSections){
                        auto it=sections_.find(sec);
                        if(it==sections_.end())continue;
                        for(auto& part:it->second.parts){
                            if(part.midiChannel==policy.sourceChannel){
                                part.casmPolicies.push_back(policy);
                                if(!part.casm.valid)part.casm=policy;
                            }
                        }
                    }
                };

                if(std::memcmp(subTag,"Ctab",4)==0&&subLen>=27){
                    const uint8_t*d=data+subStart;
                    CasmPolicy policy;
                    policy.valid=true;
                    policy.sourceChannel=d[0];
                    policy.voiceName=trimAscii(std::string(reinterpret_cast<const char*>(d+1),8));
                    policy.destinationChannel=d[9];
                    policy.sourceChordRoot=d[18];
                    policy.sourceChordType=d[19];
                    policy.ntr=d[20];
                    policy.ntt=d[21]&0x7F;
                    policy.highKey=d[22];
                    policy.noteLimitLow=d[23];
                    policy.noteLimitHigh=d[24];
                    policy.rtr=d[25];

                    // Verified Ctab layout: 34-bit chord mute is bytes 13..17.
                    policy.chordMuteMask=0;
                    for(int k=13;k<=17;k++)policy.chordMuteMask=(policy.chordMuteMask<<8)|d[k];

                    policy.sourceNoteLow=0;
                    policy.sourceNoteHigh=127;
                    attachPolicy(policy);
                }

                if(std::memcmp(subTag,"Ctb2",4)==0&&subLen>=40){
                    const uint8_t*d=data+subStart;
                    const uint8_t middleLow=d[20];
                    const uint8_t middleHigh=d[21];

                    auto makeRangePolicy=[&](size_t base,uint8_t low,uint8_t high){
                        CasmPolicy policy;
                        policy.valid=true;
                        policy.sourceChannel=d[0];
                        policy.voiceName=trimAscii(std::string(reinterpret_cast<const char*>(d+1),8));
                        policy.destinationChannel=d[9];
                        policy.sourceChordRoot=d[18];
                        policy.sourceChordType=d[19];
                        policy.ntr=d[base];
                        policy.ntt=static_cast<uint8_t>(d[base+1]&0x7F);
                        policy.chordMuteMask=0;
                        for(int k=13;k<=17;k++)policy.chordMuteMask=(policy.chordMuteMask<<8)|d[k];
                        policy.bassOn=(d[base+1]&0x80)!=0;
                        policy.highKey=d[base+2];
                        policy.noteLimitLow=d[base+3];
                        policy.noteLimitHigh=d[base+4];
                        policy.rtr=d[base+5];
                        policy.sourceNoteLow=low;
                        policy.sourceNoteHigh=high;
                        attachPolicy(policy);
                    };

                    if(middleLow>0)makeRangePolicy(22,0,static_cast<uint8_t>(middleLow-1));
                    makeRangePolicy(28,middleLow,middleHigh);
                    if(middleHigh<127)makeRangePolicy(34,static_cast<uint8_t>(middleHigh+1),127);
                }

                if(subEnd<=q)break;
                q=subEnd;
            }

            // Cntt is stored separately from Ctab. Apply its authoritative NTT
            // and Bass-On values after all tables in this CSEG have been read.
            for(StyleSection sec:csegSections){
                auto it=sections_.find(sec);
                if(it==sections_.end())continue;
                for(auto& part:it->second.parts){
                    auto ct=cnttByChannel.find(part.midiChannel);
                    if(ct==cnttByChannel.end())continue;
                    for(auto& policy:part.casmPolicies){
                        policy.ntt=ct->second.first;
                        policy.bassOn=ct->second.second;
                    }
                    if(part.casm.valid){
                        part.casm.ntt=ct->second.first;
                        part.casm.bassOn=ct->second.second;
                    }
                }
            }
        }

        if(payloadEnd<=p)break;
        p=payloadEnd;
    }
}

bool StyleParser::parse(const uint8_t* rawStyBytes,size_t size){if(!smf_.parse(rawStyBytes,size))return false;sections_.clear();for(const auto&track:smf_.tracks()){struct Boundary{uint32_t tick;StyleSection section;};std::vector<Boundary>boundaries;for(const auto&ev:track.events)if(ev.status==0xFF&&(ev.metaType==0x06||ev.metaType==0x01)){std::string text(ev.metaOrSysexData.begin(),ev.metaOrSysexData.end());StyleSection sec=classifyMarkerText(text);if(sec!=StyleSection::Unknown)boundaries.push_back({ev.tick,sec});}if(boundaries.empty())continue;
std::map<uint8_t,std::vector<MidiEvent>> setupByChannel;
for(const auto&ev:track.events){
    if(ev.tick<boundaries.front().tick&&ev.status>=0x80&&ev.status<0xF0){
        // Keep the original event data. It will be retimed to the start of
        // each section when copied below. Setting tick=0 here would underflow
        // when the section's startTick is later subtracted from every event.
        setupByChannel[ev.channel].push_back(ev);
    }
}
for(size_t i=0;i<boundaries.size();++i){uint32_t startTick=boundaries[i].tick;uint32_t endTick=(i+1<boundaries.size())?boundaries[i+1].tick:track.events.back().tick+1;auto&secData=sections_[boundaries[i].section];secData.section=boundaries[i].section;secData.lengthTicks=std::max(secData.lengthTicks,endTick-startTick);std::map<uint8_t,std::vector<MidiEvent>>byChannel;for(const auto&ev:track.events){if(ev.tick<startTick||ev.tick>=endTick)continue;if(ev.status==0xFF&&(ev.metaType==0x06||ev.metaType==0x01))continue;if(ev.status==0xFF&&ev.metaType==0x2F)continue;if(ev.status<0x80)continue;byChannel[ev.channel].push_back(ev);}for(auto&[channel,events]:byChannel){
    auto setupIt=setupByChannel.find(channel);
    if(setupIt!=setupByChannel.end()){
        std::vector<MidiEvent> normalizedSetup;
        normalizedSetup.reserve(setupIt->second.size());
        for (const auto& setup : setupIt->second) {
            MidiEvent copy = setup;
            copy.tick = startTick;
            normalizedSetup.push_back(std::move(copy));
        }
        events.insert(events.begin(), normalizedSetup.begin(), normalizedSetup.end());
    }
    StylePart part;part.midiChannel=channel;part.name=track.name.empty()?("Ch"+std::to_string(channel)):track.name;for(auto&ev:events)ev.tick-=startTick;part.events=std::move(events);for(const auto&ev:part.events){const uint8_t hi=ev.status&0xF0;if(hi==0xB0&&ev.data1==0)part.bankMsb=ev.data2;else if(hi==0xB0&&ev.data1==32)part.bankLsb=ev.data2;else if(hi==0xC0)part.program=ev.data1;else if(hi==0x90&&ev.data2>0)break;}secData.parts.push_back(std::move(part));}}}parseCasm(rawStyBytes,size);return !sections_.empty();}
std::string styleSectionToString(StyleSection s){switch(s){case StyleSection::IntroA:return"IntroA";case StyleSection::IntroB:return"IntroB";case StyleSection::IntroC:return"IntroC";case StyleSection::MainA:return"MainA";case StyleSection::MainB:return"MainB";case StyleSection::MainC:return"MainC";case StyleSection::MainD:return"MainD";case StyleSection::FillAA:return"FillAA";case StyleSection::FillBB:return"FillBB";case StyleSection::FillCC:return"FillCC";case StyleSection::FillDD:return"FillDD";case StyleSection::BreakDown:return"BreakDown";case StyleSection::EndingA:return"EndingA";case StyleSection::EndingB:return"EndingB";case StyleSection::EndingC:return"EndingC";default:return"Unknown";}}
StyleSection styleSectionFromString(const std::string&s){static const std::map<std::string,StyleSection>m={{"IntroA",StyleSection::IntroA},{"IntroB",StyleSection::IntroB},{"IntroC",StyleSection::IntroC},{"MainA",StyleSection::MainA},{"MainB",StyleSection::MainB},{"MainC",StyleSection::MainC},{"MainD",StyleSection::MainD},{"FillAA",StyleSection::FillAA},{"FillBB",StyleSection::FillBB},{"FillCC",StyleSection::FillCC},{"FillDD",StyleSection::FillDD},{"BreakDown",StyleSection::BreakDown},{"EndingA",StyleSection::EndingA},{"EndingB",StyleSection::EndingB},{"EndingC",StyleSection::EndingC}};auto it=m.find(s);return it!=m.end()?it->second:StyleSection::Unknown;}
