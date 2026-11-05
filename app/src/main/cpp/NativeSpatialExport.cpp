#include <jni.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

namespace {
constexpr double PI = 3.14159265358979323846;
constexpr double COMBS[8] = {1116,1188,1277,1356,1422,1491,1557,1617};
constexpr double ALLP[4] = {556,441,341,225};
struct Params { double sr,period,elev,depth,phase,direction,width,room,rt60,wet; bool raw,reverse; int denoise; int clarity=50; int trajectory=0; };

struct Ring {
    std::vector<double> b; int w=0;
    explicit Ring(int n):b(n,0){}
    void write(double x){b[w]=x;if(++w==int(b.size()))w=0;}
    double read(double lag) const {
        double d=std::clamp(lag,0.0,double(b.size())-4), f=d-std::floor(d); int n=int(d);
        auto at=[&](int x){int i=w-1-x;if(i<0)i+=b.size();return b[i];};
        double p0=at(n-1),p1=at(n),p2=at(n+1),p3=at(n+2),f2=f*f,f3=f2*f;
        double m0=.5*(p2-p0),m1=.5*(p3-p1);
        return (2*f3-3*f2+1)*p1+(f3-2*f2+f)*m0+(-2*f3+3*f2)*p2+(f3-f2)*m1;
    }
};
struct Fir { double b0,b1,b2,x1=0,x2=0; double run(double x){double y=b0*x+b1*x1+b2*x2;x2=x1;x1=x;return y;} };
struct Tri { double x1=0,x2=0; double run(double x){double y=.34*x+.4*x1+.26*x2;x2=x1;x1=x;return y;} };
struct Channel {
    Fir shadow{.34,.40,.26},tilt{.58,.28,.14},rear{.38,.36,.26}; Ring notch{48}; Tri a1,a2,a3;
    double h1=0,h2=0,h3=0,h4=0;
};
struct Comb {
    std::vector<double>b;int len=0,idx=0;double store=0,fb=0,damp=0;
    void resize(int n){len=std::max(4,std::min(int(b.size()),n));if(idx>=len)idx=0;}
    double run(double x){double y=b[idx];store=y*(1-damp)+store*damp;b[idx]=x+store*fb;if(++idx>=len)idx=0;return y;}
};
struct Allpass {
    std::vector<double>b;int len=0,idx=0;
    void resize(int n){len=std::max(4,std::min(int(b.size()),n));if(idx>=len)idx=0;}
    double run(double x){double y=-x+b[idx];b[idx]=x+b[idx]*.5;if(++idx>=len)idx=0;return y;}
};
struct RevChannel {
    std::array<Comb,8> cs;std::array<Allpass,4> as;std::vector<double>pre;int pi=0,plen=1;double a,x1=0,y1=0;
    RevChannel(double sr,double spread):pre(std::lround(.3*sr),0){
        a=(1/(2*PI*150))/(1/(2*PI*150)+1/sr);
        for(int i=0;i<8;i++){cs[i].b.resize(std::lround((COMBS[i]+spread)*sr/44100*5));cs[i].len=std::lround((COMBS[i]+spread)*sr/44100);}
        for(int i=0;i<4;i++){as[i].b.resize(std::lround((ALLP[i]+spread)*sr/44100*5));as[i].len=std::lround((ALLP[i]+spread)*sr/44100);}
    }
    double run(double v){double o=pre[pi];pre[pi]=v;if(++pi>=plen)pi=0;double s=0;for(auto&c:cs)s+=c.run(o);s/=8;for(auto&p:as)s=p.run(s);double y=a*(y1+s-x1);x1=s;y1=y;return y;}
};
struct Reverb {
    const Params&p;RevChannel l,r;Tri a1,a2,a3;double h1=0,h2=0,h3=0,h4=0,gh=1;
    double ea,ga,de=0,we=0,ag=1;
    Reverb(const Params&p):p(p),l(p.sr,0),r(p.sr,23),ea(std::exp(-1/(.1*p.sr))),ga(std::exp(-1/(.4*p.sr))){
        double L=std::clamp(p.room,3.0,50.0),rt=std::clamp(p.rt60,0.0,5.0),scale=std::clamp(L/10,.55,5.0);
        for(int c=0;c<2;c++){auto&x=c?r:l;x.plen=std::clamp(int(std::lround(L/343*p.sr)),1,int(x.pre.size()));double spread=c?23:0;
            for(int i=0;i<8;i++){int n=std::lround((COMBS[i]+spread)*p.sr/44100*scale);x.cs[i].resize(n);double ds=n/p.sr;x.cs[i].fb=std::min(.98,std::pow(10,-3*ds/(rt*1.3)));double fc=std::clamp(9000-120*L,3500.0,9000.0);x.cs[i].damp=std::exp(-2*PI*fc/p.sr);}
            for(int i=0;i<4;i++)x.as[i].resize(std::lround((ALLP[i]+spread)*p.sr/44100*scale));
        }
    }
    void run(double in,double&ol,double&orr){double lo=a3.run(a2.run(a1.run(in)));h4=h3;h3=h2;h2=h1;h1=in;double x=lo+(h4-lo)*gh;ol=l.run(x);orr=r.run(x);
        de=de*ea+x*x*(1-ea);double wp=(ol*ol+orr*orr)*.5;we=we*ea+wp*(1-ea);if(we>1e-6&&de>1e-6)ag=ag*ga+std::sqrt(de/we)*(1-ga);
        double target=1.25*p.wet/100*(1+.006*(p.room-10)),g=std::clamp(ag,.35,2.0)*target;ol*=g;orr*=g;
    }
};
double stable(int seed){double x=std::sin(seed*12.9898+78.233)*43758.5453;return x-std::floor(x);}
double duration(int i){return 2+stable(i*2+17)*6;}
double azimuth(double t,const Params&p){if(p.direction!=0)return p.direction*2*PI/std::max(1.0,p.period)*t+p.phase*PI/180;
    t=std::max(0.0,t);int k=0;double start=0,d=duration(0);while(t>=start+d){start+=d;d=duration(++k);}double a=p.phase*PI/180;
    for(int i=0;i<k;i++)a+=(stable(i*2+31)<.5?-1:1)*2*PI/std::max(1.0,p.period)*duration(i);
    return a+(stable(k*2+31)<.5?-1:1)*2*PI/std::max(1.0,p.period)*(t-start);
}
struct Ctrl{double dl,dr,sl,sr,rear,dn,ng,gd,gh,pl,pr,gl,gr;};
double rearCue(const Params&p){return p.trajectory==0&&p.period==8&&p.elev==35&&p.depth==45&&p.phase==0&&p.direction>0?1.8:1.0;}
Ctrl ctrl(double t,const Params&p,double makeup){double th=azimuth(t,p),phase=p.phase*PI/180,u=th-phase,e=p.elev*PI/180,phi=e*std::cos(th);
    switch(p.trajectory){case 1: th=phase+PI/2*std::sin(u);phi=e*std::sin(2*u);break;case 2:th=phase;phi=e*std::sin(u);break;case 3:phi=e*std::sin(u/2);break;case 4:th=phase+1.3*std::sin(.73*u)+std::sin(.37*u);phi=e*std::sin(.53*u);break;}
    double s=std::sin(th),cs=std::cos(th);
    double f=std::clamp(1.5*p.period,8.0,16.0),far=.5*(1+std::sin(2*PI/f*t)),d=p.depth/100;
    double f0=std::clamp(7200-62*phi*180/PI,3200.0,9800.0),ap=std::min(1.0,std::abs(phi*180/PI)/40);
    double sl=std::min(.85,std::max(0.0,.85*(1+s)*.5)),sr=std::min(.85,std::max(0.0,.85*(1-s)*.5)),w=p.width/100;
    return {.5*.63/1000*p.sr*(1+s)*w,.5*.63/1000*p.sr*(1-s)*w,sl*w,sr*w,std::clamp(-cs,0.0,1.0)*rearCue(p),std::clamp(p.sr/f0,3.0,16.0),.12+.42*ap,
        std::pow(10,-10*d*far/20),std::pow(10,-14*d*std::pow(far,1.15)/20),1-.18*w*(.5-.5*s),1-.18*w*(.5+.5*s),std::pow(10,-3.5*sl*w/20),std::pow(10,-3.5*sr*w/20)};
}
// 保守软门限：只压底噪，尽可能保留人声尾音、空间混响和弱背景。
struct Noise {double hc,ea,er,ga,gr,floor,xl=0,xr=0,yl=0,yr=0,e=0,g=1;Noise(double sr,int level):hc(std::exp(-2*PI*30/sr)),ea(std::exp(-1/(.005*sr))),er(std::exp(-1/(.08*sr))),ga(std::exp(-1/(.012*sr))),gr(std::exp(-1/(.25*sr))),floor(1-std::clamp(level,0,100)/100.0*.24){}
void run(double&l,double&r){double l0=hc*(yl+l-xl),r0=hc*(yr+r-xr);xl=l;xr=r;yl=l0;yr=r0;double v=std::max(std::abs(l0),std::abs(r0));e=e*(v>e?ea:er)+v*(1-(v>e?ea:er));double want=e<.012?floor+(1-floor)*std::pow(e/.012,2):1;g=g*(want<g?ga:gr)+want*(1-(want<g?ga:gr));l=l0*g;r=r0*g;}};
struct Renderer {
    Params p;Ring in{96};Channel l,r;Reverb rev;Noise noise;double makeup,shadowAmount,tiltAmount,lim=1,peak=0,lr,olim=1,opeak=0,olr;
    bool hasStreamPos=false;long streamPos=0;
    // Reverb 保存引用，必须绑定已复制成员；构造形参在构造结束后即失效。
    Renderer(const Params&p):p(p),rev(this->p),noise(p.sr,p.denoise),makeup(std::clamp(1/(std::pow(10,-12*(p.depth/100)/2/20)*.6),.9,3.0)),shadowAmount(.35+(50-std::clamp(p.clarity,0,100))*.004),tiltAmount(.08+(50-std::clamp(p.clarity,0,100))*.0008),lr(std::exp(-1/(.08*p.sr))),olr(std::exp(-1/(.12*p.sr))){}
    void block(const std::vector<float>&pcm,long start,int n,std::vector<int16_t>&out,long inputOffset=0,int fadeFrames=0,long timelineOffset=0){
        const long timelineStart=start+timelineOffset;
        for(int base=0;base<n;base+=64){int m=std::min(64,n-base);Ctrl a=ctrl((timelineStart+base)/p.sr,p,makeup),b=ctrl((timelineStart+base+m)/p.sr,p,makeup);
            for(int k=0;k<m;k++){long pos=start+base+k;double u=double(k+1)/m;auto v=[&](double Ctrl::*q){return a.*q+(b.*q-a.*q)*u;};double x=pcm[start+base+k-inputOffset];in.write(x);
                double ll=in.read(v(&Ctrl::dl)),rr=in.read(v(&Ctrl::dr));
                double hl=l.shadow.x1-l.shadow.run(ll);ll=ll-shadowAmount*v(&Ctrl::sl)*hl;ll=ll*(1-tiltAmount*v(&Ctrl::sl))+l.tilt.run(ll)*(tiltAmount*v(&Ctrl::sl));ll*=v(&Ctrl::gl);
                double hr=r.shadow.x1-r.shadow.run(rr);rr=rr-shadowAmount*v(&Ctrl::sr)*hr;rr=rr*(1-tiltAmount*v(&Ctrl::sr))+r.tilt.run(rr)*(tiltAmount*v(&Ctrl::sr));rr*=v(&Ctrl::gr);
                double lb=l.rear.x1;double lbv=l.rear.run(ll);ll-=.30*v(&Ctrl::rear)*(lb-lbv);double rb=r.rear.x1;double rbv=r.rear.run(rr);rr-=.30*v(&Ctrl::rear)*(rb-rbv);
                l.notch.write(ll);ll-=v(&Ctrl::ng)*l.notch.read(v(&Ctrl::dn));r.notch.write(rr);rr-=v(&Ctrl::ng)*r.notch.read(v(&Ctrl::dn));ll*=v(&Ctrl::gd);rr*=v(&Ctrl::gd);
                l.h4=l.h3;l.h3=l.h2;l.h2=l.h1;l.h1=ll;double lo=l.a3.run(l.a2.run(l.a1.run(ll)));ll=lo+(l.h4-lo)*v(&Ctrl::gh);
                r.h4=r.h3;r.h3=r.h2;r.h2=r.h1;r.h1=rr;lo=r.a3.run(r.a2.run(r.a1.run(rr)));rr=lo+(r.h4-lo)*v(&Ctrl::gh);
                ll=ll*v(&Ctrl::pl)*makeup;rr=rr*v(&Ctrl::pr)*makeup;double pk=std::max(std::abs(ll),std::abs(rr));
                peak=pk>peak?pk:peak*lr;double want=peak>1e-9?std::min(1.0,.89/peak):1;lim=want<lim?want:lim*lr+want*(1-lr);ll*=lim;rr*=lim;
                rev.gh=v(&Ctrl::gh);double wl,wr;rev.run(x,wl,wr);double far=.5*(1+std::sin(2*PI/std::clamp(1.5*p.period,8.0,16.0)*(timelineStart+base+k)/p.sr)),d=p.depth/100;
                double gd=1-.30*d*far,gw=1+1.2*d*far;ll=ll*gd+wl*gw;rr=rr*gd+wr*gw;if(p.denoise>0)noise.run(ll,rr);
                long fade=std::max(1L,pos<inputOffset?inputOffset-pos:pos-inputOffset);double gain=fadeFrames>0&&fade<fadeFrames?std::pow(double(fade)/fadeFrames,2)*(3-2*double(fade)/fadeFrames):1;ll*=gain;rr*=gain;pk=std::max(std::abs(ll),std::abs(rr));
                // 包络峰值不能跟随淡入斜坡下降：它只应衡量原始信号强度。
                // 否则首块虽从静音起播，限幅器却把随后正常波形也压到近零。
                double envPk=fadeFrames>0&&fade<fadeFrames?pk/std::max(1e-6,gain):pk;
                opeak=envPk>opeak?envPk:opeak*olr;want=opeak>1e-9?std::min(1.0,.94/opeak):1;olim=want<olim?want:olim*olr+want*(1-olr);
                ll*=olim;rr*=olim;
                out[(pos-start)*2]=std::lround(std::clamp(ll,-1.0,1.0)*32767);out[(pos-start)*2+1]=std::lround(std::clamp(rr,-1.0,1.0)*32767);
            }
        }
    }
    // UTS 电台的 SpaceEngine 不淡出，也没有本地导出专用的输出限幅。
    void streamBlock(const std::vector<float>&pcm,int n,std::vector<int16_t>&out){
        for(int base=0;base<n;base+=64){int m=std::min(64,n-base);Ctrl a=ctrl(streamPos/p.sr,p,makeup),b=ctrl((streamPos+m)/p.sr,p,makeup);
            for(int k=0;k<m;k++){double u=double(k+1)/m;auto v=[&](double Ctrl::*q){return a.*q+(b.*q-a.*q)*u;};double x=pcm[base+k];in.write(x);
                double ll=in.read(v(&Ctrl::dl)),rr=in.read(v(&Ctrl::dr));
                double hl=l.shadow.x1-l.shadow.run(ll);ll=ll-shadowAmount*v(&Ctrl::sl)*hl;ll=ll*(1-tiltAmount*v(&Ctrl::sl))+l.tilt.run(ll)*(tiltAmount*v(&Ctrl::sl));ll*=v(&Ctrl::gl);
                double hr=r.shadow.x1-r.shadow.run(rr);rr=rr-shadowAmount*v(&Ctrl::sr)*hr;rr=rr*(1-tiltAmount*v(&Ctrl::sr))+r.tilt.run(rr)*(tiltAmount*v(&Ctrl::sr));rr*=v(&Ctrl::gr);
                double lb=l.rear.x1;double lbv=l.rear.run(ll);ll-=.30*v(&Ctrl::rear)*(lb-lbv);double rb=r.rear.x1;double rbv=r.rear.run(rr);rr-=.30*v(&Ctrl::rear)*(rb-rbv);
                l.notch.write(ll);ll-=v(&Ctrl::ng)*l.notch.read(v(&Ctrl::dn));r.notch.write(rr);rr-=v(&Ctrl::ng)*r.notch.read(v(&Ctrl::dn));ll*=v(&Ctrl::gd);rr*=v(&Ctrl::gd);
                l.h4=l.h3;l.h3=l.h2;l.h2=l.h1;l.h1=ll;double lo=l.a3.run(l.a2.run(l.a1.run(ll)));ll=lo+(l.h4-lo)*v(&Ctrl::gh);
                r.h4=r.h3;r.h3=r.h2;r.h2=r.h1;r.h1=rr;lo=r.a3.run(r.a2.run(r.a1.run(rr)));rr=lo+(r.h4-lo)*v(&Ctrl::gh);
                ll=ll*v(&Ctrl::pl)*makeup;rr=rr*v(&Ctrl::pr)*makeup;double pk=std::max(std::abs(ll),std::abs(rr));peak=pk>peak?pk:peak*lr;double want=peak>1e-9?std::min(1.0,.89/peak):1;lim=want<lim?want:lim*lr+want*(1-lr);ll*=lim;rr*=lim;
                rev.gh=std::clamp(v(&Ctrl::gh),.02,1.0);double wl,wr;rev.run(x,wl,wr);double far=.5*(1+std::sin(2*PI/std::clamp(1.5*p.period,8.0,16.0)*(streamPos+k)/p.sr)),d=p.depth/100;
                double gd=1-.30*d*far,gw=1+1.2*d*far;ll=ll*gd+wl*gw;rr=rr*gd+wr*gw;if(p.denoise>0)noise.run(ll,rr);
                out[(base+k)*2]=std::lround(std::clamp(ll,-1.0,1.0)*32767);out[(base+k)*2+1]=std::lround(std::clamp(rr,-1.0,1.0)*32767);streamPos++;
            }
        }
    }
};
void put16(std::vector<unsigned char>&h,long at,int v){h[at]=v;h[at+1]=v>>8;}void put32(std::vector<unsigned char>&h,long at,uint32_t v){for(int i=0;i<4;i++)h[at+i]=v>>(i*8);}
void render(const int16_t*bytes,long frames,const Params&p,int bpf,int ch,bool flt,bool rev,const char*path){
    std::vector<float>pcm(frames);for(long i=0;i<frames;i++){long f=rev?frames-1-i:i;const unsigned char*s=reinterpret_cast<const unsigned char*>(bytes)+f*bpf;double l,r;
        if(flt){float a,b;std::memcpy(&a,s,4);std::memcpy(&b,s+(ch>1?4:0),4);l=a;r=b;}else{l=reinterpret_cast<const int16_t*>(s)[0]/32768.0;r=reinterpret_cast<const int16_t*>(s)[ch>1?1:0]/32768.0;}pcm[i]=p.raw?float(l):float((l+r)*.5);if(p.raw){pcm[i]=float(l);}
    }
    std::vector<int16_t>out(frames*2);int nw=std::min(4,std::max(1,int(std::ceil(frames/(p.sr*10)))));std::atomic<int>bad{0};std::vector<std::thread>ts;
    for(int j=0;j<nw;j++){ts.emplace_back([&,j]{long a=frames*j/nw,b=frames*(j+1)/nw,warm=std::max(p.sr*2,p.sr*p.rt60*1.5),begin=std::max(0L,a-long(warm));
        if(p.raw){for(long i=a;i<b;i++){long f=p.reverse?frames-1-i:i;const unsigned char*s=reinterpret_cast<const unsigned char*>(bytes)+f*bpf;double l,r;if(flt){float x,y;std::memcpy(&x,s,4);std::memcpy(&y,s+(ch>1?4:0),4);l=x;r=y;}else{l=reinterpret_cast<const int16_t*>(s)[0]/32768.0;r=reinterpret_cast<const int16_t*>(s)[ch>1?1:0]/32768.0;}out[i*2]=std::lround(std::clamp(l,-1.0,1.0)*32767);out[i*2+1]=std::lround(std::clamp(r,-1.0,1.0)*32767);}return;}
        Renderer renderer(p);for(long at=begin;at<b;at+=4096){int n=std::min(4096L,b-at);std::vector<int16_t>tmp(n*2);renderer.block(pcm,at,n,tmp,0,std::lround(p.sr*2));if(at+n>a){long skip=std::max(0L,a-at);std::copy(tmp.begin()+skip*2,tmp.end(),out.begin()+(std::max(at,a))*2);}}
    });}for(auto&t:ts)t.join();if(bad)return;
    std::vector<unsigned char>h(44);std::memcpy(h.data(),"RIFF",4);put32(h,4,frames*4+36);std::memcpy(h.data()+8,"WAVEfmt ",8);put32(h,16,16);put16(h,20,1);put16(h,22,2);put32(h,24,p.sr);put32(h,28,p.sr*4);put16(h,32,4);put16(h,34,16);std::memcpy(h.data()+36,"data",4);put32(h,40,frames*4);
    FILE*f=std::fopen(path,"wb");if(!f)throw std::string("无法创建 WAV 文件");bool ok=std::fwrite(h.data(),1,44,f)==44&&std::fwrite(out.data(),4,frames,f)==size_t(frames)&&std::fclose(f)==0;if(!ok)throw std::string("WAV 写入失败");
}
}
#ifndef SPATIAL_PLAYER_IMPLEMENTATION
extern "C" JNIEXPORT void JNICALL Java_com_j5i_spatial8d_NativeSpatialExport_render(JNIEnv*env,jclass,jbyteArray data,jint bpf,jint ch,jboolean flt,jlong frames,jdouble sr,jboolean reverse,jboolean raw,jdouble period,jdouble elev,jdouble depth,jdouble phase,jdouble direction,jdouble width,jdouble room,jdouble rt60,jdouble wet,jint denoise,jint clarity,jint trajectory,jstring path){
    jbyte*ptr=env->GetByteArrayElements(data,nullptr);const char*p=env->GetStringUTFChars(path,nullptr);try{Params q{sr,period,elev,depth,phase,direction,width,room,rt60,wet,bool(raw),bool(reverse),denoise,clarity,trajectory};render(reinterpret_cast<int16_t*>(ptr),frames,q,bpf,ch,flt,reverse,p);}catch(const std::string&e){env->ThrowNew(env->FindClass("java/io/IOException"),e.c_str());}env->ReleaseStringUTFChars(path,p);env->ReleaseByteArrayElements(data,ptr,JNI_ABORT);
}
#endif
