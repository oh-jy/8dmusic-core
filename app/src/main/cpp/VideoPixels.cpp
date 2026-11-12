#include <jni.h>
#include <algorithm>
#include <cstdint>

extern "C" JNIEXPORT void JNICALL
Java_com_j5i_spatial8d_VideoPixels_toYuv(JNIEnv* env, jclass, jintArray pixels, jint width, jint height,
    jobject yBuffer, jint yBase, jint yRow, jint yPixel,
    jobject uBuffer, jint uBase, jint uRow, jint uPixel,
    jobject vBuffer, jint vBase, jint vRow, jint vPixel) {
    auto* y = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto* u = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto* v = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    auto fits = [&](jobject buffer, jint base, jint row, jint pixel, int w, int h) {
        return base >= 0 && row > 0 && pixel > 0 &&
            static_cast<int64_t>(base) + static_cast<int64_t>(h - 1) * row + static_cast<int64_t>(w - 1) * pixel < env->GetDirectBufferCapacity(buffer);
    };
    if (!y || !u || !v || width <= 0 || height <= 0 || (width & 1) || (height & 1) ||
        static_cast<int64_t>(width) * height > env->GetArrayLength(pixels) ||
        !fits(yBuffer, yBase, yRow, yPixel, width, height) ||
        !fits(uBuffer, uBase, uRow, uPixel, width / 2, height / 2) ||
        !fits(vBuffer, vBase, vRow, vPixel, width / 2, height / 2)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid YUV plane layout");
        return;
    }
    auto* data = env->GetIntArrayElements(pixels, nullptr);
    if (!data) return;
    for (int row = 0; row < height; ++row) {
        for (int column = 0; column < width; ++column) {
            const uint32_t color = static_cast<uint32_t>(data[row * width + column]);
            const int red = (color >> 16) & 255, green = (color >> 8) & 255, blue = color & 255;
            y[yBase + row * yRow + column * yPixel] = static_cast<uint8_t>(std::clamp(((66 * red + 129 * green + 25 * blue + 128) >> 8) + 16, 0, 255));
            if ((row & 1) == 0 && (column & 1) == 0) {
                u[uBase + row / 2 * uRow + column / 2 * uPixel] = static_cast<uint8_t>(std::clamp(((-38 * red - 74 * green + 112 * blue + 128) >> 8) + 128, 0, 255));
                v[vBase + row / 2 * vRow + column / 2 * vPixel] = static_cast<uint8_t>(std::clamp(((112 * red - 94 * green - 18 * blue + 128) >> 8) + 128, 0, 255));
            }
        }
    }
    env->ReleaseIntArrayElements(pixels, data, JNI_ABORT);
}
