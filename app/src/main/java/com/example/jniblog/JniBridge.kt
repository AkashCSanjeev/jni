package com.example.jniblog

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Debug

object JniBridge {

    init { System.loadLibrary("jniblog") }

    // ── JNI declarations ──────────────────────────────────────────────────
    external fun getHelloFromJni(): String
    external fun nativeNoOp(): Int
    external fun nativeAdd(a: Long, b: Long): Long
    external fun nativeSort(arr: IntArray)
    external fun nativeGaussianBlur(bitmapIn: Bitmap, bitmapOut: Bitmap)

    // ── Kotlin equivalents ────────────────────────────────────────────────
    fun kotlinNoOp(): Int = 1
    fun kotlinAdd(a: Long, b: Long): Long = a + b
    fun kotlinSort(arr: IntArray) = arr.sort()

    fun kotlinGaussianBlur(src: Bitmap, dst: Bitmap) {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        for (y in 4 until h - 4) {
            for (x in 4 until w - 4) {
                var r = 0; var g = 0; var b = 0
                for (ky in -4..4) {
                    for (kx in -4..4) {
                        val p = px[(y + ky) * w + (x + kx)]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8)  and 0xFF
                        b +=  p         and 0xFF
                    }
                }
                out[y * w + x] = Color.rgb(r / 81, g / 81, b / 81)
            }
        }
        dst.setPixels(out, 0, w, 0, 0, w, h)
    }

    // ── Result data class ─────────────────────────────────────────────────
    data class BenchResult(
        val label: String,
        val subLabel: String,
        // Wall-clock median — what the user actually experiences
        val kotlinWallUs: Long,
        val jniWallUs: Long,
        // CPU-only time — excludes OS scheduling noise
        // If wall ≈ CPU → compute-bound → measurement is clean
        // If wall >> CPU → thread was preempted → noisy, re-run
        val kotlinCpuUs: Long,
        val jniCpuUs: Long,
        // Heap allocations during measurement
        // JNI doing heavy work should always show 0
        val kotlinAllocs: Int,
        val jniAllocs: Int,
        val insightLines: List<String>
    ) {
        val speedupRatio: Float
            get() = kotlinWallUs.toFloat() / jniWallUs.toFloat()

        val verdictText: String get() = when {
            speedupRatio > 1.15f ->
                "JNI is ${"%.2f".format(speedupRatio)}× faster"
            speedupRatio < 0.85f ->
                "Kotlin is ${"%.2f".format(1f / speedupRatio)}× faster"
            else -> "Roughly equal"
        }

        val jniNoiseUs: Long
            get() = (jniWallUs - jniCpuUs).coerceAtLeast(0)

        val measureQuality: String
            get() = if (jniNoiseUs < jniWallUs * 0.15f)
                "Compute-bound — clean measurement"
            else
                "OS noise: ${jniNoiseUs} µs — re-run on idle device"
    }

    // ── Measurement harness ───────────────────────────────────────────────
    //
    // Returns Triple(wallUs, cpuUs, allocCount) — all median values.
    //
    // warmup: mandatory — without it, ART JIT hasn't compiled the Kotlin
    //         path yet. First 20-30 Kotlin runs are in interpreter mode
    //         and are 5-10× slower than JIT code. Completely misleading.
    //
    // We collect wall time AND cpu time per run:
    //   wall = System.nanoTime()           — includes OS scheduling gaps
    //   cpu  = Debug.threadCpuTimeNanos()  — only this thread's CPU cycles
    //
    // Median (not mean) — a single GC pause can add 10ms to one run
    // and destroy an average. Median gives the "typical" experience.
    //
    fun <T> measure(
        warmup: Int = 50,
        runs: Int = 100,
        block: () -> T
    ): Triple<Long, Long, Int> {
        repeat(warmup) { block() }

        val wallUs  = LongArray(runs)
        val cpuUs   = LongArray(runs)
        val allocs  = IntArray(runs)

        repeat(runs) { i ->
            val w0 = System.nanoTime()
            val c0 = Debug.threadCpuTimeNanos()
            val a0 = Debug.getGlobalAllocCount()

            block()

            wallUs[i]  = (System.nanoTime()          - w0) / 1_000
            cpuUs[i]   = (Debug.threadCpuTimeNanos() - c0) / 1_000
            allocs[i]  = Debug.getGlobalAllocCount() - a0
        }

        wallUs.sort()
        cpuUs.sort()

        return Triple(
            wallUs[runs / 2],
            cpuUs[runs / 2],
            allocs.average().toInt()
        )
    }

    // Convenience overload matching your existing API signature
    fun <T> measureMedianUs(
        warmup: Int = 30,
        runs: Int = 50,
        block: () -> T
    ): Long = measure(warmup, runs, block).first
}