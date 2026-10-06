#pragma once
#include <memory>
namespace oboe {
enum class Result { OK=0,ErrorInternal=-1 };
enum class Direction { Output };enum class PerformanceMode { None,LowLatency };
enum class SharingMode { Shared,Exclusive };enum class AudioFormat { Float };
enum class ChannelCount { Stereo };enum class Usage { Media };enum class ContentType { Music };
enum class DataCallbackResult { Continue };enum class AudioApi { AAudio };
inline bool failOpen=false,failStart=false;
inline const char* convertToText(Result r) {return r==Result::OK?"OK":"ErrorInternal";}
class AudioStream;
class AudioStreamDataCallback {public: virtual ~AudioStreamDataCallback()=default;virtual DataCallbackResult onAudioReady(AudioStream*,void*,int32_t)=0;};
struct BufferResult {Result r;bool operator==(Result q)const{return r==q;}Result error()const{return r;}};
class AudioStream {
public:
 int getSampleRate()const{return 48000;}int getChannelCount()const{return 2;}
 SharingMode getSharingMode()const{return SharingMode::Exclusive;}PerformanceMode getPerformanceMode()const{return PerformanceMode::LowLatency;}
 AudioApi getAudioApi()const{return AudioApi::AAudio;}int getFramesPerBurst()const{return 256;}
 int getBufferSizeInFrames()const{return 512;}BufferResult setBufferSizeInFrames(int){return {Result::OK};}
 Result requestStart(){return failStart?Result::ErrorInternal:Result::OK;}
 Result requestStop(){return Result::OK;}Result close(){return Result::OK;}
};
class AudioStreamBuilder {
public:
 AudioStreamBuilder* setDirection(Direction){return this;}AudioStreamBuilder* setPerformanceMode(PerformanceMode){return this;}
 AudioStreamBuilder* setSharingMode(SharingMode){return this;}AudioStreamBuilder* setFormat(AudioFormat){return this;}
 AudioStreamBuilder* setChannelCount(ChannelCount){return this;}AudioStreamBuilder* setSampleRate(int){return this;}
 AudioStreamBuilder* setDataCallback(AudioStreamDataCallback*){return this;}AudioStreamBuilder* setUsage(Usage){return this;}
 AudioStreamBuilder* setContentType(ContentType){return this;}
 Result openStream(std::shared_ptr<AudioStream>& s){if(failOpen)return Result::ErrorInternal;s=std::make_shared<AudioStream>();return Result::OK;}
};
}
