#ifdef SPATIAL_STREAM_TEST
#include <jni.h>
#include <cassert>
#include <cmath>
#include <iostream>
#include <vector>
#include "NativeSpatialPlayer.cpp"

int main(){
    {
        Params p{44100,9,40,42,0,1,110,24,58,2.6,false,false,0};
        std::vector<float>tone(4096);for(int i=0;i<4096;i++)tone[i]=float(.2*std::sin(2*PI*220*i/44100));
        Renderer local(p),stream(p);std::vector<int16_t>a(8192),b(8192);
        local.block(tone,0,4096,a);stream.streamBlock(tone,4096,b);
        bool differs=false;for(int i=0;i<8192;i++)differs|=a[i]!=b[i];
        assert(differs); // 本地淡出/输出限幅不能误带进 FM。
    }
    constexpr int sr=44100,n=sr*18;
    Params p{double(sr),9,40,42,0,1,110,24,58,2.6,false,false,100};
    std::vector<float>pcm(n);
    for(int i=0;i<n;i++)pcm[i]=float(.12*std::sin(2*PI*220*i/sr)+.0004);
    Renderer whole(p),chunks(p);
    std::vector<int16_t>a(n*2),b(n*2);
    whole.streamBlock(pcm,n,a);
    for(int at=0;at<n;){int count=std::min(4096,n-at);std::vector<float>block(pcm.begin()+at,pcm.begin()+at+count);std::vector<int16_t>part(count*2);chunks.streamBlock(block,count,part);std::copy(part.begin(),part.end(),b.begin()+at*2);at+=count;}
    for(int i=0;i<n*2;i++){if(std::abs(a[i]-b[i])>1){std::cerr<<"chunk mismatch at "<<i<<"\n";return 1;}}
    double sum=0;for(int i=sr*12*2;i<n*2;i++)sum+=double(a[i])*a[i];
    assert(sum>0);
    Renderer full(p);p.denoise=0;Renderer off(p);p.denoise=50;Renderer half(p);
    full.streamBlock(pcm,n,a);off.streamBlock(pcm,n,b);std::vector<int16_t>c(n*2);half.streamBlock(pcm,n,c);
    for(int i=sr*4*2;i<n*2;i++)assert(a[i]==b[i]); // 有声段不受噪声门影响。
    double fullRms=0,halfRms=0;for(int i=sr*4*2;i<n*2;i++){fullRms+=double(a[i])*a[i];halfRms+=double(c[i])*c[i];}
    assert(halfRms>fullRms); // 50% 的底噪衰减应弱于 UTS 的 100%。
    std::cout<<"FM stream matches UTS SpaceEngine chunk boundaries\n";
}
#endif
