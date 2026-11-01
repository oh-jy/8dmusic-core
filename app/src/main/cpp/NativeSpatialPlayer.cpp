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

extern "C" JNIEXPORT jlong JNICALL Java_com_jiangyi_spatial8d_NativeSpatialPlayer_create(JNIEnv*,jclass,jdouble sr,jboolean raw,jdouble period,jdouble elev,jdouble depth,jdouble phase,jdouble direction,jdouble width,jdouble room,jdouble rt60,jdouble wet,jint denoise){
    Params p{sr,period,elev,depth,phase,direction,width,room,rt60,wet,bool(raw),false,denoise};
    return reinterpret_cast<jlong>(new Renderer(p));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_jiangyi_spatial8d_NativeSpatialPlayer_render(JNIEnv*env,jclass,jlong handle,jbyteArray data,jint bpf,jint ch,jboolean flt,jlong start,jint frames,jboolean reverse,jlong total,jlong cacheStart,jint before){
    Renderer*r=reinterpret_cast<Renderer*>(handle);jbyte*ptr=static_cast<jbyte*>(env->GetPrimitiveArrayCritical(data,nullptr));if(!ptr)return nullptr;std::vector<float>mono(frames*2);const unsigned char*s=reinterpret_cast<unsigned char*>(ptr);
    for(int i=0;i<frames;i++){long f=reverse?total-1-start-i:start+i;const unsigned char*p=s+f*bpf;double l,rr;if(flt){float fl,fr;std::memcpy(&fl,p,4);std::memcpy(&fr,p+(ch>1?4:0),4);l=fl;rr=fr;}else{l=reinterpret_cast<const int16_t*>(p)[0]/32768.0;rr=reinterpret_cast<const int16_t*>(p)[ch>1?1:0]/32768.0;}mono[i]=float((l+rr)*.5);mono[frames+i]=float(ch>1?rr:l);}
    env->ReleasePrimitiveArrayCritical(data,ptr,JNI_ABORT);std::vector<int16_t>out(frames*2);
    double inPeak=0;for(int i=0;i<frames;i++)inPeak=std::max(inPeak,std::abs(double(mono[i])));
    if(r->p.raw){for(int i=0;i<frames;i++){out[i*2]=std::lround(std::clamp(double(mono[frames+i]),-1.0,1.0)*32767);out[i*2+1]=std::lround(std::clamp(double(mono[i]*2-mono[frames+i]),-1.0,1.0)*32767);}}
    else{r->block(mono,start,frames,out,start);for(int i=0;i<frames;i++){int edge=std::min(512,3072);double gain=cacheStart>0&&before+i<edge?std::pow(double(before+i)/edge,2)*(3-2*double(before+i)/edge):1;
        out[i*2]=std::lround(std::clamp(out[i*2]/32767.0*gain,-1.0,1.0)*32767);out[i*2+1]=std::lround(std::clamp(out[i*2+1]/32767.0*gain,-1.0,1.0)*32767);}}
    double outPeak=0;for(int16_t x:out)outPeak=std::max(outPeak,std::abs(double(x)));
    if(start<44100*30&&before==0)__android_log_print(ANDROID_LOG_INFO,"8DLevels","start=%ld frames=%d raw=%d sr=%.1f p=(%.2f %.2f %.2f %.2f %.2f %.2f %.2f %.2f %.2f) rev=%d noise=%d in=%.6f out=%.1f lim=%.6f olim=%.6f",start,frames,r->p.raw,r->p.sr,r->p.period,r->p.elev,r->p.depth,r->p.phase,r->p.direction,r->p.width,r->p.room,r->p.rt60,r->p.wet,r->p.reverse,r->p.denoise,inPeak,outPeak,r->lim,r->olim);
    jbyteArray result=env->NewByteArray(out.size()*2);env->SetByteArrayRegion(result,0,out.size()*2,reinterpret_cast<jbyte*>(out.data()));return result;
}
extern "C" JNIEXPORT void JNICALL Java_com_jiangyi_spatial8d_NativeSpatialPlayer_release(JNIEnv*,jclass,jlong handle){delete reinterpret_cast<Renderer*>(handle);}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_jiangyi_spatial8d_NativeSpatialPlayer_renderStream(JNIEnv*env,jclass,jlong handle,jbyteArray data,jint bpf,jint ch,jboolean flt,jint frames,jlong streamPos){
    Renderer*r=reinterpret_cast<Renderer*>(handle);jbyte*ptr=static_cast<jbyte*>(env->GetPrimitiveArrayCritical(data,nullptr));if(!ptr)return nullptr;
    std::vector<float>mono(frames*2);const unsigned char*s=reinterpret_cast<unsigned char*>(ptr);
    for(int i=0;i<frames;i++){const unsigned char*p=s+i*bpf;double l,rr;if(flt){float fl,fr;std::memcpy(&fl,p,4);std::memcpy(&fr,p+(ch>1?4:0),4);l=fl;rr=fr;}else{l=reinterpret_cast<const int16_t*>(p)[0]/32768.0;rr=reinterpret_cast<const int16_t*>(p)[ch>1?1:0]/32768.0;}mono[i]=float((l+rr)*.5);mono[frames+i]=float(ch>1?rr:l);}
    env->ReleasePrimitiveArrayCritical(data,ptr,JNI_ABORT);std::vector<int16_t>out(frames*2);
    if(r->p.raw){for(int i=0;i<frames;i++){out[i*2]=std::lround(std::clamp(double(mono[frames+i]),-1.0,1.0)*32767);out[i*2+1]=std::lround(std::clamp(double(mono[i]*2-mono[frames+i]),-1.0,1.0)*32767);}}
    else{if(!r->hasStreamPos){r->streamPos=streamPos;r->hasStreamPos=true;}r->streamBlock(mono,frames,out);}
    jbyteArray result=env->NewByteArray(out.size()*2);env->SetByteArrayRegion(result,0,out.size()*2,reinterpret_cast<jbyte*>(out.data()));return result;
}
