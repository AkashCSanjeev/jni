# Crossing the Line: JNI Performance on Android

A benchmark app built to answer one question: **is JNI actually worth the overhead?**

This is the companion code for the blog post *"Crossing the Line: JNI on Android — From First Call to Real Performance"*. It runs five tests on your device and shows you exactly where JNI wins, where it loses, and why.

All numbers in the blog were collected from a **Pixel 7a (Tensor G2)** running a **release build**.

---

## What it tests

| Section | What it shows |
|---|---|
| Hello World | How JNI naming works, first-call symbol lookup cost |
| Boundary cost | The fixed overhead every JNI call pays before your code runs |
| Addition x2000 | Why calling JNI in a tight loop is a bad idea |
| Sort 500k integers | Where JNI starts winning — one call, lots of work |
| 9x9 Gaussian blur on 1080p | Where JNI dominates — SIMD, zero allocation, no GC |

---

## How the measurement works

Each benchmark uses a custom `measure()` function in `JniBridge.kt` that returns three values:

- **Wall time** via `System.nanoTime()` — total elapsed time
- **CPU time** via `Debug.threadCpuTimeNanos()` — only cycles this thread used
- **Alloc count** via `Debug.getGlobalAllocCount()` — heap objects created

The function runs a warmup phase first (not counted), then measures N runs and returns the **median** — not the mean. A single GC pause can add 10ms to one run and destroy an average. The median gives you the typical execution time.

The boundary cost section measures **1000 calls as one block** and divides. Measuring a single no-op call is unreliable because `System.nanoTime()` resolution is ~50–100 ns on Android — both Kotlin and JNI round to the same bucket and look identical.

---

## Running it

**Important: run in release mode, not debug.**

In debug mode, native code runs without compiler optimisations (`-O0`). This makes JNI look artificially slow. Kotlin behaves the same in both modes, so comparing debug native against release JVM is misleading.

1. In Android Studio go to **Build > Select Build Variant**
2. Set the app module to `release`
3. Run on device