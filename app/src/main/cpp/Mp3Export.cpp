#include <jni.h>
#include <lame.h>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace {
uint32_t u32(const unsigned char *p) {
    return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
}

void encode(const char *wavPath, const char *mp3Path) {
    FILE *in = std::fopen(wavPath, "rb");
    FILE *out = std::fopen(mp3Path, "wb");
    lame_t lame = nullptr;
    try {
        if (!in || !out) throw std::string("无法打开导出文件");
        unsigned char header[12];
        if (std::fread(header, 1, 12, in) != 12 || std::memcmp(header, "RIFF", 4) ||
            std::memcmp(header + 8, "WAVE", 4)) throw std::string("无效的 WAV 文件");
        uint16_t channels = 0, bits = 0, format = 0;
        uint32_t sampleRate = 0;
        bool haveFormat = false;
        while (true) {
            unsigned char chunk[8];
            if (std::fread(chunk, 1, 8, in) != 8) throw std::string("WAV 缺少音频数据");
            uint32_t size = u32(chunk + 4);
            if (!std::memcmp(chunk, "fmt ", 4)) {
                unsigned char fmt[40] = {};
                size_t n = size > sizeof(fmt) ? sizeof(fmt) : size;
                if (std::fread(fmt, 1, n, in) != n) throw std::string("无效的 WAV 格式");
                format = uint16_t(fmt[0] | fmt[1] << 8);
                channels = uint16_t(fmt[2] | fmt[3] << 8);
                sampleRate = u32(fmt + 4);
                bits = uint16_t(fmt[14] | fmt[15] << 8);
                haveFormat = true;
                if (size > n && std::fseek(in, size - n, SEEK_CUR)) throw std::string("WAV 跳转失败");
            } else if (!std::memcmp(chunk, "data", 4)) {
                break;
            } else if (std::fseek(in, size + (size & 1), SEEK_CUR)) {
                throw std::string("WAV 跳转失败");
            }
        }
        if (!haveFormat || format != 1 || channels != 2 || bits != 16 || !sampleRate) {
            throw std::string("仅支持 16 bit 立体声 PCM WAV");
        }
        lame = lame_init();
        if (!lame || lame_set_in_samplerate(lame, sampleRate) ||
            lame_set_num_channels(lame, 2) || lame_set_brate(lame, 192) ||
            lame_set_quality(lame, 2) || lame_set_mode(lame, STEREO) ||
            lame_init_params(lame) < 0) throw std::string("MP3 编码器初始化失败");
        std::vector<short> pcm(8192 * 2);
        std::vector<unsigned char> mp3(32768);
        while (true) {
            size_t frames = std::fread(pcm.data(), 4, 8192, in);
            if (frames) {
                int n = lame_encode_buffer_interleaved(lame, pcm.data(), int(frames),
                                                       mp3.data(), int(mp3.size()));
                if (n < 0 || (n && std::fwrite(mp3.data(), 1, n, out) != size_t(n))) {
                    throw std::string("MP3 编码或写入失败");
                }
            }
            if (frames != 8192) break;
        }
        if (std::ferror(in)) throw std::string("读取 WAV 失败");
        int n = lame_encode_flush(lame, mp3.data(), int(mp3.size()));
        if (n < 0 || (n && std::fwrite(mp3.data(), 1, n, out) != size_t(n))) {
            throw std::string("MP3 收尾失败");
        }
        lame_close(lame);
        std::fclose(in);
        if (std::fclose(out)) throw std::string("MP3 文件写入失败");
    } catch (...) {
        if (lame) lame_close(lame);
        if (in) std::fclose(in);
        if (out) std::fclose(out);
        std::remove(mp3Path);
        throw;
    }
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_j5i_spatial8d_Mp3Export_nativeEncode(JNIEnv *env, jclass, jstring wav, jstring mp3) {
    const char *wavPath = env->GetStringUTFChars(wav, nullptr);
    const char *mp3Path = env->GetStringUTFChars(mp3, nullptr);
    try {
        encode(wavPath, mp3Path);
    } catch (const std::string &message) {
        env->ThrowNew(env->FindClass("java/io/IOException"), message.c_str());
    } catch (...) {
        env->ThrowNew(env->FindClass("java/io/IOException"), "MP3 编码失败");
    }
    env->ReleaseStringUTFChars(wav, wavPath);
    env->ReleaseStringUTFChars(mp3, mp3Path);
}
