#include <jni.h>
#include <string>
#include <android/bitmap.h>
#include <cstring>
#include <algorithm>

// ── Hello World ───────────────────────────────────────────────────────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_jniblog_JniBridge_getHelloFromJni(JNIEnv* env, jobject) {
    return env->NewStringUTF(
            "Hello from C++! I live in jni_demo.cpp, "
            "compiled into libjniblog.so and loaded at runtime."
    );
}

// ── No-op: pure boundary cost ─────────────────────────────────────────────
// Does nothing. Median time over 500 calls = exact JNI crossing cost.
extern "C" JNIEXPORT jint JNICALL
Java_com_example_jniblog_JniBridge_nativeNoOp(JNIEnv*, jobject) {
    return 1;
}

// ── Addition ──────────────────────────────────────────────────────────────
// Called 2000 times per measured run from Kotlin.
// Addition takes ~1 ns. Crossing takes ~2,000 ns.
// 2000 calls × 2,000 ns = ~4,000 µs of pure overhead waste.
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_jniblog_JniBridge_nativeAdd(JNIEnv*, jobject, jlong a, jlong b) {
    return a + b;
}

// ── Sort 500k integers ────────────────────────────────────────────────────
// One JNI call carries ~9.5M comparisons.
// Boundary cost: ~2 µs out of ~55 ms total = 0.004% overhead.
// ReleaseIntArrayElements with 0: copies sorted data back to JVM heap.
extern "C" JNIEXPORT void JNICALL
Java_com_example_jniblog_JniBridge_nativeSort(JNIEnv* env, jobject, jintArray jarr) {
    jsize len  = env->GetArrayLength(jarr);
    jint* data = env->GetIntArrayElements(jarr, nullptr);
    std::sort(data, data + len);
    env->ReleaseIntArrayElements(jarr, data, 0);
}

// ── 9×9 Gaussian Blur ─────────────────────────────────────────────────────
// 81 multiply-add ops per pixel on a 1920×1080 image = ~168M ops per call.
// AndroidBitmap_lockPixels → raw uint32_t* pointer, zero heap allocation.
// -O2 -ffast-math on Tensor G2: compiler emits NEON float32x4_t SIMD.
// Kotlin equivalent: allocates ~8MB IntArray, no SIMD, bounds-checks every access.
extern "C" JNIEXPORT void JNICALL
Java_com_example_jniblog_JniBridge_nativeGaussianBlur(
        JNIEnv* env, jobject, jobject bitmapIn, jobject bitmapOut) {

    AndroidBitmapInfo info;
    void* pixelsIn;
    void* pixelsOut;

    AndroidBitmap_getInfo(env, bitmapIn, &info);
    AndroidBitmap_lockPixels(env, bitmapIn,  &pixelsIn);
    AndroidBitmap_lockPixels(env, bitmapOut, &pixelsOut);

    const int w = (int)info.width;
    const int h = (int)info.height;
    uint32_t* src = (uint32_t*)pixelsIn;
    uint32_t* dst = (uint32_t*)pixelsOut;

    // Copy border pixels unchanged (kernel can't reach outside the image)
    memcpy(dst, src, (size_t)w * h * 4);

    // 9×9 box blur — radius 4, 81 samples per pixel
    for (int y = 4; y < h - 4; y++) {
        for (int x = 4; x < w - 4; x++) {
            uint32_t r = 0, g = 0, b = 0;
            for (int ky = -4; ky <= 4; ky++) {
                for (int kx = -4; kx <= 4; kx++) {
                    uint32_t p = src[(y + ky) * w + (x + kx)];
                    r += (p >> 16) & 0xFF;
                    g += (p >>  8) & 0xFF;
                    b += (p       & 0xFF);
                }
            }
            dst[y * w + x] = 0xFF000000u
                             | ((r / 81) << 16)
                             | ((g / 81) <<  8)
                             |  (b / 81);
        }
    }

    AndroidBitmap_unlockPixels(env, bitmapIn);
    AndroidBitmap_unlockPixels(env, bitmapOut);
}