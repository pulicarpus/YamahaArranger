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
template<typename T> struct ResultWithValue {Result r;T v;explicit operator bool()const{return r==Result::OK;}T value()const{return v;}Result error()const{return r;}};
class AudioStream;
class AudioStreamDataCallback {public: virtual ~AudioStreamDataCallback()=default;virtual DataCallbackResult onAudioReady(AudioStream*,void*,int32_t)=0;};
struct BufferResult {Result r;bool operator==(Result q)const{return r==q;}Result error()const{return r;}};
class AudioStream {
public:
 int getSampleRate()const{return 48000;}int getChannelCount()const{return 2;}
 SharingMode getSharingMode()const{return SharingMode::Exclusive;}PerformanceMode getPerformanceMode()const{return PerformanceMode::LowLatency;}
 AudioApi getAudioApi()const{return AudioApi::AAudio;}int getFramesPerBurst()const{return 256;}
 int getBufferSizeInFrames()const{return 512;}int getBufferCapacityInFrames()const{return 1024;}int getFramesPerDataCallback()const{return 256;}int getDeviceId()const{return 7;}
 ResultWithValue<int> getXRunCount()const{return {Result::OK,0};}ResultWithValue<double> calculateLatencyMillis(){return {Result::OK,12.5};}BufferResult setBufferSizeInFrames(int){return {Result::OK};}
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
