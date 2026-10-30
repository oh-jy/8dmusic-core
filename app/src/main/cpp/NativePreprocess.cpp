#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {
struct Resampler {
    double ratio, coef[33]={},ring[33]={},prev=0,cur=0;
    int wp=0,raw=0;long outCount=0;bool lpf=false;
    explicit Resampler(double r):ratio(r){
        lpf=r>1.02||r<0.98;if(!lpf)return;double sum=0;
        for(int k=0;k<33;k++){double d=(k-16)/r,s=k==16?1:std::sin(3.141592653589793*d)/(3.141592653589793*d);
            coef[k]=s/r*(.54-.46*std::cos(2*3.141592653589793*k/32));sum+=coef[k];}
        for(double&c:coef)c/=sum;
    }
    void run(const std::vector<float>&in,std::vector<float>&out){
        for(float x:in){ring[wp]=x;double f=x;if(lpf){f=0;int idx=wp;for(int k=0;k<33;k++){f+=coef[k]*ring[idx];if(--idx<0)idx=32;}}
            if(++wp==33)wp=0;raw++;if(lpf&&raw<=16)continue;int idx=raw-1-(lpf?16:0);
            if(idx==0){prev=0;cur=f;}else{prev=cur;cur=f;}
            while(true){double p=outCount*ratio;int i=int(std::floor(p));double frac=p-i,y=0;
                if(frac==0&&i==idx)y=cur;else if(frac>0&&i==idx-1)y=prev+(cur-prev)*frac;else break;
                out.push_back(float(y));outCount++;
            }
        }
    }
};
struct Wsola {
    static constexpr int win=2048,hop=1024,search=512,ll=1024;
    double w[win],ana=0,ratio;std::vector<float>q;long base=0,prev=0,k=0,obase=0;bool has=false;
    double num[win+hop]={},den[win+hop]={};
    explicit Wsola(double r):ratio(r){for(int i=0;i<win;i++)w[i]=.5-.5*std::cos(2*3.141592653589793*i/(win-1));}
    double nxc(long c,long r){double a=0,b=0,d=0;for(int j=0;j<ll;j++){double x=q[c-base+j],y=q[r-base+j];a+=x*x;b+=y*y;d+=x*y;}
        return a*b<=1e-12?-1:d/std::sqrt(a*b);}
    void drop(long bound){long cut=std::min(bound-base,long(q.size())-1);if(cut<=0)return;q.erase(q.begin(),q.begin()+cut);base+=cut;}
    bool emit(bool end,std::vector<float>&out){
        long nominal=std::lround(ana),qe=base+q.size();if(!end){long hi=nominal+search+win,lo=has?prev+hop:nominal-search;if(base>lo||qe<hi)return false;}
        if(qe<nominal+win)return false;long sel=nominal;
        if(has){long ref=prev+hop;if(ref-base>=0&&ref-base+ll<=long(q.size())){int best=0;double bv=-2;
            for(int d=-search;d<=search;d+=8){long c=nominal+d;if(c-base>=0&&c-base+ll<=long(q.size())){double v=nxc(c,ref);if(v>bv){bv=v;best=d;}}}
            for(int d=std::max(-search,best-8);d<=std::min(search,best+8);d++){long c=nominal+d;if(c-base>=0&&c-base+ll<=long(q.size())){double v=nxc(c,ref);if(v>bv){bv=v;best=d;}}}sel=nominal+best;
        }}
        long s=sel-base;if(s<0||s+win>long(q.size()))return false;long off=k*hop-obase;
        for(int j=0;j<win;j++){num[off+j]+=w[j]*q[s+j];den[off+j]+=w[j];}
        prev=sel;has=true;ana+=hop/ratio;k++;long cnt=k*hop-obase;
        if(cnt>0){out.insert(out.end(),num,num+cnt);long keep=win+hop-cnt;std::memmove(num,num+cnt,keep*sizeof(double));std::memmove(den,den+cnt,keep*sizeof(double));
            std::memset(num+keep,0,(win+hop-keep)*sizeof(double));std::memset(den+keep,0,(win+hop-keep)*sizeof(double));obase+=cnt;}
        drop(std::min(prev+hop,std::lround(ana)-search));return true;
    }
    void run(const std::vector<float>&in,std::vector<float>&out){q.insert(q.end(),in.begin(),in.end());while(emit(false,out)){}}
    void flush(std::vector<float>&out){size_t before=out.size();while(emit(true,out)){}int end=0;for(int i=0;i<win+hop;i++)if(den[i]>=.5)end=i+1;
        out.resize(before+end);for(int i=0;i<end;i++)out[before+i]=(den[i]>=0.05f)?float(num[i]/den[i]):0.0f;}
};
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_j5i_spatial8d_NativePreprocess_run(JNIEnv*env,jclass,jbyteArray data,jint bpf,jint channels,jboolean flt,jlong frames,jdouble speed,jdouble semis,jboolean tape){
    jbyte*ptr=env->GetByteArrayElements(data,nullptr);std::vector<float>a(frames),b,c;const unsigned char*s=reinterpret_cast<unsigned char*>(ptr);
    for(long i=0;i<frames;i++){const unsigned char*p=s+i*bpf;double sum=0;for(int ch=0;ch<channels;ch++){if(flt){float v;std::memcpy(&v,p+ch*4,4);sum+=v;}else{int16_t v;std::memcpy(&v,p+ch*2,2);sum+=v/32768.0;}}a[i]=float(sum/channels);}
    env->ReleaseByteArrayElements(data,ptr,JNI_ABORT);const std::vector<float>*cur=&a;
    if(tape&&speed!=1){Resampler r(speed);r.run(a,b);cur=&b;}else if(speed!=1){Wsola w(1/speed);w.run(a,b);w.flush(b);cur=&b;}
    if(semis!=0){double r=std::pow(2,semis/12);Resampler rs(r);rs.run(*cur,c);std::vector<float>d;Wsola w(r);w.run(c,d);w.flush(d);c.swap(d);cur=&c;}
    jbyteArray result=env->NewByteArray(cur->size()*2);std::vector<int16_t>out(cur->size());for(size_t i=0;i<cur->size();i++)out[i]=std::lround(std::clamp(double((*cur)[i]),-1.0,1.0)*32767);
    env->SetByteArrayRegion(result,0,out.size()*2,reinterpret_cast<jbyte*>(out.data()));return result;
}
