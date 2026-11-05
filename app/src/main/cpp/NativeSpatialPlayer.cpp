#include <jni.h>
#ifndef SPATIAL_STREAM_TEST
#include <android/log.h>
#else
#define __android_log_print(...) 0
#define ANDROID_LOG_INFO 0
#endif
#include <vector>
#define SPATIAL_PLAYER_IMPLEMENTATION
#include "NativeSpatialExport.cpp"

extern "C" JNIEXPORT jlong JNICALL Java_com_j5i_spatial8d_NativeSpatialPlayer_create(JNIEnv*,jclass,jdouble sr,jboolean raw,jdouble period,jdouble elev,jdouble depth,jdouble phase,jdouble direction,jdouble width,jdouble room,jdouble rt60,jdouble wet,jint denoise,jint clarity,jint trajectory){
    Params p{sr,period,elev,depth,phase,direction,width,room,rt60,wet,bool(raw),false,denoise,clarity,trajectory};
    return reinterpret_cast<jlong>(new Renderer(p));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_j5i_spatial8d_NativeSpatialPlayer_render(JNIEnv*env,jclass,jlong handle,jbyteArray data,jint bpf,jint ch,jboolean flt,jlong start,jint frames,jboolean reverse,jlong total,jlong cacheStart,jint before){
    Renderer*r=reinterpret_cast<Renderer*>(handle);jbyte*ptr=static_cast<jbyte*>(env->GetPrimitiveArrayCritical(data,nullptr));if(!ptr)return nullptr;std::vector<float>mono(frames*2);const unsigned char*s=reinterpret_cast<unsigned char*>(ptr);
    // data 已经是当前块的局部 PCM，倒放帧号也必须在块内计算。
    for(int i=0;i<frames;i++){long f=reverse?frames-1-i:i;const unsigned char*p=s+f*bpf;double l,rr;if(flt){float fl,fr;std::memcpy(&fl,p,4);std::memcpy(&fr,p+(ch>1?4:0),4);l=fl;rr=fr;}else{l=reinterpret_cast<const int16_t*>(p)[0]/32768.0;rr=reinterpret_cast<const int16_t*>(p)[ch>1?1:0]/32768.0;}mono[i]=float((l+rr)*.5);mono[frames+i]=float(ch>1?rr:l);}
    env->ReleasePrimitiveArrayCritical(data,ptr,JNI_ABORT);std::vector<int16_t>out(frames*2);
    if(r->p.raw){for(int i=0;i<frames;i++){out[i*2]=std::lround(std::clamp(double(mono[frames+i]),-1.0,1.0)*32767);out[i*2+1]=std::lround(std::clamp(double(mono[i]*2-mono[frames+i]),-1.0,1.0)*32767);}}
    else{r->block(mono,start,frames,out,0,0,cacheStart+before);for(int i=0;i<frames;i++){int edge=128;
        // 每个新缓存只平滑首块，真正切歌和 seek 都不能在非零波形上硬起播。
        double gain=before==0&&i<edge?std::pow(double(i)/edge,2)*(3-2*double(i)/edge):1;
        out[i*2]=std::lround(std::clamp(out[i*2]/32767.0*gain,-1.0,1.0)*32767);out[i*2+1]=std::lround(std::clamp(out[i*2+1]/32767.0*gain,-1.0,1.0)*32767);}}
    jbyteArray result=env->NewByteArray(out.size()*2);env->SetByteArrayRegion(result,0,out.size()*2,reinterpret_cast<jbyte*>(out.data()));return result;
}
extern "C" JNIEXPORT void JNICALL Java_com_j5i_spatial8d_NativeSpatialPlayer_release(JNIEnv*,jclass,jlong handle){delete reinterpret_cast<Renderer*>(handle);}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_j5i_spatial8d_NativeSpatialPlayer_renderStream(JNIEnv*env,jclass,jlong handle,jbyteArray data,jint bpf,jint ch,jboolean flt,jint frames,jlong streamPos){
    Renderer*r=reinterpret_cast<Renderer*>(handle);jbyte*ptr=static_cast<jbyte*>(env->GetPrimitiveArrayCritical(data,nullptr));if(!ptr)return nullptr;
    std::vector<float>mono(frames*2);const unsigned char*s=reinterpret_cast<unsigned char*>(ptr);
    for(int i=0;i<frames;i++){const unsigned char*p=s+i*bpf;double l,rr;if(flt){float fl,fr;std::memcpy(&fl,p,4);std::memcpy(&fr,p+(ch>1?4:0),4);l=fl;rr=fr;}else{l=reinterpret_cast<const int16_t*>(p)[0]/32768.0;rr=reinterpret_cast<const int16_t*>(p)[ch>1?1:0]/32768.0;}mono[i]=float((l+rr)*.5);mono[frames+i]=float(ch>1?rr:l);}
    env->ReleasePrimitiveArrayCritical(data,ptr,JNI_ABORT);std::vector<int16_t>out(frames*2);
    if(r->p.raw){for(int i=0;i<frames;i++){out[i*2]=std::lround(std::clamp(double(mono[frames+i]),-1.0,1.0)*32767);out[i*2+1]=std::lround(std::clamp(double(mono[i]*2-mono[frames+i]),-1.0,1.0)*32767);}}
    else{if(!r->hasStreamPos){r->streamPos=streamPos;r->hasStreamPos=true;}r->streamBlock(mono,frames,out);}
    jbyteArray result=env->NewByteArray(out.size()*2);env->SetByteArrayRegion(result,0,out.size()*2,reinterpret_cast<jbyte*>(out.data()));return result;
}
