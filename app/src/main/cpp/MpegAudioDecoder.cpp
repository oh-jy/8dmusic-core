#include <jni.h>
#include <lame.h>
#include <mutex>
#include <vector>

static std::mutex mpegDecoderMutex;

extern "C" JNIEXPORT jlong JNICALL
Java_com_j5i_spatial8d_MpegAudioDecoder_create(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(mpegDecoderMutex);
    return reinterpret_cast<jlong>(hip_decode_init());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_j5i_spatial8d_MpegAudioDecoder_decode(JNIEnv *env, jclass, jlong handle, jbyteArray packet, jint channels) {
    if (!handle || channels < 1 || channels > 2) return nullptr;
    std::lock_guard<std::mutex> lock(mpegDecoderMutex);
    std::vector<unsigned char> input(env->GetArrayLength(packet));
    env->GetByteArrayRegion(packet, 0, input.size(), reinterpret_cast<jbyte *>(input.data()));
    short left[1152], right[1152];
    std::vector<jbyte> output;
    for (int iteration = 0; iteration < 32; ++iteration) {
        int frames = hip_decode1(reinterpret_cast<hip_t>(handle), iteration == 0 ? input.data() : nullptr,
            iteration == 0 ? input.size() : 0, left, right);
        if (frames < 0) return nullptr;
        if (frames == 0) break;
        for (int frame = 0; frame < frames; ++frame) {
            output.push_back(static_cast<jbyte>(left[frame] & 0xff));
            output.push_back(static_cast<jbyte>((left[frame] >> 8) & 0xff));
            if (channels == 2) {
                output.push_back(static_cast<jbyte>(right[frame] & 0xff));
                output.push_back(static_cast<jbyte>((right[frame] >> 8) & 0xff));
            }
        }
    }
    jbyteArray result = env->NewByteArray(output.size());
    if (result && !output.empty()) env->SetByteArrayRegion(result, 0, output.size(), output.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_j5i_spatial8d_MpegAudioDecoder_release(JNIEnv *, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(mpegDecoderMutex);
    if (handle) hip_decode_exit(reinterpret_cast<hip_t>(handle));
}
