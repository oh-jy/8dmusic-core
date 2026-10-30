#ifdef SPATIAL_STREAM_TEST
#include <jni.h>
#include <cassert>
#include <cmath>
#include <fstream>
#include <iostream>
#include <vector>
#include "NativeSpatialPlayer.cpp"

int main(){
    {
        Params p{44100,8,35,45,0,1,100,10,1.2,18,false,false,0};
        Renderer normal(p); p.clarity=0; Renderer soft(p); p.clarity=100; Renderer clear(p);
        assert(std::abs(normal.shadowAmount-.35)<1e-12 && std::abs(normal.tiltAmount-.08)<1e-12);
        assert(soft.shadowAmount>normal.shadowAmount && clear.shadowAmount<normal.shadowAmount);
        std::vector<float> input(4096);
        for(int i=0;i<4096;i++) input[i]=.1f*std::sin(2*PI*6000*i/44100);
        std::vector<int16_t> a(8192),b(8192); soft.block(input,0,4096,a); clear.block(input,0,4096,b);
        assert(a!=b);
        if(std::getenv("SPATIAL_TEST_CLARITY_ONLY")){std::cout<<"clarity checks passed\n";return 0;}
    }
    if(const char*path=std::getenv("SPATIAL_TEST_PCM")){
        // 真机导出的 44.1kHz / 16bit / 立体声原始输入，覆盖真实歌曲的启动瞬态。
        std::ifstream input(path,std::ios::binary|std::ios::ate);
        if(!input){std::cerr<<"cannot open captured PCM\n";return 1;}
        std::vector<int16_t>samples(input.tellg()/sizeof(int16_t));input.seekg(0);
        input.read(reinterpret_cast<char*>(samples.data()),samples.size()*sizeof(int16_t));
        constexpr int sr=44100;
        Params p{sr,8,35,45,0,1,100,10,1.2,18,false,false,58};
        long offset=0;if(const char*value=std::getenv("SPATIAL_TEST_OFFSET"))offset=std::strtol(value,nullptr,10);
        Renderer renderer(p);double peak=0;long silentFrames=0;
        std::ofstream rendered;if(const char*out=std::getenv("SPATIAL_TEST_OUTPUT"))rendered.open(out,std::ios::binary);
        for(size_t at=0;at<samples.size()/2;at+=4096){
            int n=std::min<size_t>(4096,samples.size()/2-at);std::vector<float>block(n);
            for(int i=0;i<n;i++)block[i]=(samples[(at+i)*2]/32768.0f+samples[(at+i)*2+1]/32768.0f)*.5f;
            std::vector<int16_t>out(n*2);renderer.block(block,0,n,out,0,0,offset+at);
            if(rendered)rendered.write(reinterpret_cast<const char*>(out.data()),out.size()*sizeof(int16_t));
            peak=std::max(peak,std::max(std::abs(renderer.rev.l.y1),std::abs(renderer.rev.r.y1)));
            if(peak>10){std::cerr<<"captured reverb exploded at "<<at<<" wet="<<peak<<"\n";return 1;}
            for(int i=0;i<n;i++)if(out[i*2]==0&&out[i*2+1]==0)silentFrames++;
        }
        if(const char*first=std::getenv("SPATIAL_TEST_FIRST_BLOCK")){
            long at=std::strtol(first,nullptr,10);Renderer firstRenderer(p);int n=std::min<size_t>(4096,samples.size()/2-at);
            std::vector<float>block(n);for(int i=0;i<n;i++)block[i]=(samples[(at+i)*2]/32768.0f+samples[(at+i)*2+1]/32768.0f)*.5f;
            std::vector<int16_t>out(n*2);firstRenderer.block(block,0,n,out,0,0,offset+at);
            double outPeak=0;for(int16_t x:out)outPeak=std::max(outPeak,std::abs(double(x)));
            std::cerr<<"first block offset="<<offset+at<<" peak="<<outPeak<<" wet="<<firstRenderer.rev.l.y1<<" olim="<<firstRenderer.olim<<"\n";
        }
        if(!std::isfinite(peak)||renderer.olim<1e-4||silentFrames>samples.size()/4){
            std::cerr<<"captured output collapsed wet="<<peak<<" olim="<<renderer.olim<<" silent="<<silentFrames<<"\n";return 1;
        }
        std::cout<<"captured PCM bounded wet="<<peak<<" limiter="<<renderer.olim<<"\n";
    }
    {
        Params p{44100,8,35,45,0,1,100,10,1.2,18,false,false,58};
        Renderer renderer(p);
        // 回归：Reverb 不得保存已失效的 Renderer 构造形参引用。
        if(&renderer.rev.p!=&renderer.p){std::cerr<<"reverb retained constructor parameter\n";return 1;}
    }
    {
        // 标准绕头只增强默认预设的前后耳廓线索，不扩大到其他空间风格。
        Params standard{44100,8,35,45,0,1,100,10,1.2,18,false,false,58};
        Params other{44100,8,35,45,0,1,100,10,1.2,18,false,false,58};
        other.depth=50;
        if(ctrl(0,standard,1).rear!=0||ctrl(2,standard,1).rear<=ctrl(2,other,1).rear){
            std::cerr<<"standard front/rear cue is not strengthened\n";return 1;
        }
    }
    {
        // 本地播放传入局部 PCM；连续歌曲帧号必须与整段渲染完全一致。
        Params p{44100,9,40,42,0,1,110,24,58,2.6,false,false,0};
        constexpr int n=44100;
        std::vector<float>pcm(n);
        for(int i=0;i<n;i++)pcm[i]=float(.2*std::sin(2*PI*220*i/44100));
        Renderer whole(p),chunks(p);std::vector<int16_t>a(n*2),b(n*2);
        whole.block(pcm,0,n,a);
        for(int at=0;at<n;at+=4096){
            int count=std::min(4096,n-at);std::vector<float>part(pcm.begin()+at,pcm.begin()+at+count);
            std::vector<int16_t>out(count*2);chunks.block(part,0,count,out,0,0,at);
            std::copy(out.begin(),out.end(),b.begin()+at*2);
        }
        for(int i=0;i<n*2;i++)if(a[i]!=b[i]){std::cerr<<"local timeline mismatch at "<<i<<"\n";return 1;}
    }
    {
        Params p{44100,9,40,42,0,1,110,24,58,2.6,false,false,0};
        std::vector<float>tone(4096);for(int i=0;i<4096;i++)tone[i]=float(.2*std::sin(2*PI*220*i/44100));
        Renderer local(p),stream(p);std::vector<int16_t>a(8192),b(8192);
        local.block(tone,0,4096,a);stream.streamBlock(tone,4096,b);
        bool differs=false;for(int i=0;i<8192;i++)differs|=a[i]!=b[i];
        assert(differs); // 本地淡出/输出限幅不能误带进 FM。
        double energy=0;for(int i=128*2;i<4096*2;i++)energy+=double(a[i])*a[i];
        double rms=std::sqrt(energy/(4096*2-128*2));
        if(rms<2000){std::cerr<<"live opening is too quiet: "<<rms<<"\n";return 1;}
        if(local.olim<=0){std::cerr<<"output limiter collapsed: "<<local.olim<<"\n";return 1;}
    }
    {
        // 新歌曲的第一块必须从静音平滑进入，不能直接在非零波形上硬起播。
        constexpr int edge=128;
        std::vector<int16_t>out(edge*2);
        for(int i=0;i<edge;i++){
            double gain=std::pow(double(i)/edge,2)*(3-2*double(i)/edge);
            out[i*2]=std::lround(30000*gain);out[i*2+1]=std::lround(30000*gain);
        }
        if(out[0]!=0||out[1]!=0||out[edge*2-1]>=30000){
            std::cerr<<"track opening is not click-safe\n";return 1;
        }
    }
    {
        Params p{44100,9,40,42,0,1,110,24,58,2.6,false,false,0};
        Renderer local(p),stream(p);std::vector<int16_t>a(8192),b(8192);
        local.block(std::vector<float>(4096),0,4096,a);stream.streamBlock(std::vector<float>(4096),4096,b);
        if(local.olim<.9){std::cerr<<"opening limiter collapsed on silence: "<<local.olim<<"\n";return 1;}
    }
    {
        // 外部淡入不能把限幅器的包络峰值一起压低，否则淡入后会先无声再慢慢恢复。
        constexpr int n=4096;
        std::vector<float>tone(n);for(int i=0;i<n;i++)tone[i]=float(.9*std::sin(2*PI*220*i/44100));
        Params p{44100,9,40,42,0,1,110,24,58,2.6,false,false,0};
        Renderer renderer(p);std::vector<int16_t>out(n*2);renderer.block(tone,0,n,out);
        for(int i=0;i<n;i++){double gain=std::pow(double(i)/n,2)*(3-2*double(i)/n);out[i*2]=std::lround(out[i*2]*gain);out[i*2+1]=std::lround(out[i*2+1]*gain);}
        if(renderer.olim<.5){std::cerr<<"fade collapsed output limiter: "<<renderer.olim<<"\n";return 1;}
        double rms=0;for(int i=n*2/2;i<n*2;i++)rms+=double(out[i])*out[i];
        if(rms<1e10){std::cerr<<"post-fade output is silent: "<<renderer.olim<<"\n";return 1;}
    }
    {
        // 宽度最大时持续非零输入，不得被输出限幅器压成静音。
        constexpr int sr=44100,n=sr*10;
        std::vector<float>tone(n);for(int i=0;i<n;i++)tone[i]=float(.5*std::sin(2*PI*220*i/sr));
        Params p{sr,8,35,45,0,1,100,10,1.2,18,false,false,58};
        Renderer renderer(p);std::vector<int16_t>out(n*2);
        for(int at=0;at<n;at+=4096){int count=std::min(4096,n-at);std::vector<float>part(tone.begin()+at,tone.begin()+at+count);std::vector<int16_t>block(count*2);renderer.block(part,0,count,block,0,0,at);std::copy(block.begin(),block.end(),out.begin()+at*2);}
        double rms=0;for(int i=sr*4*2;i<n*2;i++)rms+=double(out[i])*out[i];
        if(rms<1e8){std::cerr<<"wide output limiter collapsed: "<<rms<<" olim="<<renderer.olim<<"\n";return 1;}
    }
    {
        // 重建渲染器时混响不得在预延迟结束处产生失控瞬态。
        constexpr int sr=44100,n=sr*3;
        std::vector<float>pcm(n);for(int i=0;i<n;i++)pcm[i]=i<sr/2?float(.5*std::sin(2*PI*220*i/sr)):0;
        Params p{sr,8,35,45,0,1,100,10,1.2,18,false,false,58};
        Reverb rev(p);double peak=0;
        for(int i=0;i<n;i++){double l,r;rev.run(pcm[i],l,r);peak=std::max(peak,std::max(std::abs(l),std::abs(r)));}
        if(!std::isfinite(peak)||peak>10){std::cerr<<"reverb startup exploded: "<<peak<<"\n";return 1;}
        std::cerr<<"reverb startup peak="<<peak<<"\n";
    }
    {
        constexpr int sr=44100,n=sr*10;
        std::vector<float>pcm(n);for(int i=0;i<n;i++)pcm[i]=float(.5*std::sin(2*PI*220*i/sr));
        Params p{sr,8,35,45,0,1,100,10,1.2,18,false,false,58};
        Renderer renderer(p);std::vector<int16_t>out(4096*2);double peak=0;
        for(int at=0;at<n;at+=4096){std::vector<float>part(pcm.begin()+at,pcm.begin()+at+4096);renderer.block(part,0,4096,out,0,0,at);peak=std::max(peak,std::max(std::abs(renderer.rev.l.y1),std::abs(renderer.rev.r.y1)));if(peak>10)std::cerr<<"wet at="<<at<<" wet="<<peak<<" ag="<<renderer.rev.ag<<"\n";}
        std::cerr<<"continuous wet peak="<<peak<<"\n";
    }
    {
        Params p{44100,9,40,42,0,1,110,24,58,2.6,false,false,0};
        std::vector<float>tone(8192);for(int i=0;i<8192;i++)tone[i]=float(.2*std::sin(2*PI*220*i/44100));
        Renderer local(p);std::vector<int16_t>a(8192),b(8192);
        local.block(tone,0,4096,a);local.block(tone,4096,4096,b);
        long first=0,next=0;for(int i=0;i<4096;i++){first+=std::abs(a[i*2]);next+=std::abs(b[i*2]);}
        if(next<first/2){std::cerr<<"periodic block fade returned: "<<first<<" -> "<<next<<"\n";return 1;}
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
    // 降噪包含 30Hz 高通，不要求与完全关闭的链路逐采样一致。
    p.denoise=100;Renderer strong(p);p.denoise=50;Renderer weak(p);
    std::vector<float>quiet(n);for(int i=0;i<n;i++)quiet[i]=float(.001*std::sin(2*PI*220*i/sr)+.0004);
    std::vector<float>music(n);for(int i=0;i<n;i++)music[i]=float(.03*std::sin(2*PI*220*i/sr));
    p.denoise=0;Renderer clean(p);p.denoise=100;Renderer preserve(p);
    std::vector<int16_t>before(n*2),after(n*2);clean.block(music,0,n,before);preserve.block(music,0,n,after);
    // 高通会改变相位/包络；这里验证音乐主体没有噪声门造成的额外能量损失。
    double musicBefore=0,musicAfter=0;
    for(int i=sr*2;i<n*2;i++){musicBefore+=double(before[i])*before[i];musicAfter+=double(after[i])*after[i];}
    if(musicAfter<musicBefore*.97){std::cerr<<"denoise removed musical energy: "<<musicAfter/musicBefore<<"\n";return 1;}
    strong.streamBlock(quiet,n,a);weak.streamBlock(quiet,n,c);
    double fullRms=0,halfRms=0;for(int i=sr*4*2;i<n*2;i++){fullRms+=double(a[i])*a[i];halfRms+=double(c[i])*c[i];}
    if(halfRms<=fullRms){std::cerr<<"quiet denoise half="<<halfRms<<" full="<<fullRms<<"\n";return 1;} // 低电平段的衰减必须随等级增强。
    std::cout<<"FM stream matches UTS SpaceEngine chunk boundaries\n";
}
#endif
