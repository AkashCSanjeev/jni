package com.example.jniblog

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupSection1Hello()
        setupSection2Boundary()
        setupSection3Addition()
        setupSection4Sort()
        setupSection5Blur()
    }

    // ── Section 1: Hello World ────────────────────────────────────────────
    // No benchmarking — just shows the concept and first-call timing.
    private fun setupSection1Hello() {
        val btn      = findViewById<MaterialButton>(R.id.btnHello)
        val tvResult = findViewById<TextView>(R.id.tvHelloResult)
        val tvMeta   = findViewById<TextView>(R.id.tvHelloMeta)

        btn.setOnClickListener {
            val t0  = System.nanoTime()
            val msg = JniBridge.getHelloFromJni()
            val us  = (System.nanoTime() - t0) / 1_000

            tvResult.text       = "\"$msg\""
            tvResult.visibility = View.VISIBLE

            tvMeta.text =
                "First-call time: ${us} µs  (includes symbol lookup)\n" +
                        "Subsequent calls skip the lookup — JVM caches the pointer.\n\n" +
                        "How the JVM found this function:\n" +
                        "Java_com_example_jniblog_JniBridge_getHelloFromJni\n" +
                        "└─ package    : com.example.jniblog\n" +
                        "└─ class      : JniBridge\n" +
                        "└─ method     : getHelloFromJni"
            tvMeta.visibility = View.VISIBLE
        }
    }

    // ── Section 2: JNI boundary cost ─────────────────────────────────────
    // nativeNoOp() does zero work. Median over 500 calls = pure crossing cost.
    // Shows users the "fixed toll" every JNI call pays regardless of payload.
    private fun setupSection2Boundary() {
        val btn       = findViewById<MaterialButton>(R.id.btnBoundary)
        val container = findViewById<LinearLayout>(R.id.boundaryContainer)

        btn.setOnClickListener {
            btn.isEnabled = false
            btn.text = "Measuring..."

            lifecycleScope.launch {
                val result = withContext(Dispatchers.Default) {

                    val CALLS = 1_000

                    // ── Phase 1: Warmup ───────────────────────────────────────
                    // 100 warmup iterations let ART JIT fully compile both paths
                    // before we start timing. Without this, early Kotlin runs are
                    // in interpreter mode and are 5-10x slower than JIT code.
                    Log.d("BOUNDARY", "Starting warmup ($CALLS calls x 100 warmup)...")

                    val (kWall, kCpu, kAlloc) = JniBridge.measure(
                        warmup = 100,
                        runs   = 500
                    ) {
                        // Run CALLS iterations as one block.
                        // System.nanoTime() resolution is ~50-100ns on Android.
                        // Measuring a single call means both Kotlin and JNI round
                        // to the same timer bucket and look identical.
                        // Measuring 1000 calls as one block then dividing gives
                        // a stable per-call number that beats the timer resolution.
                        repeat(CALLS) { JniBridge.kotlinNoOp() }
                    }

                    val (jWall, jCpu, jAlloc) = JniBridge.measure(
                        warmup = 100,
                        runs   = 500
                    ) {
                        repeat(CALLS) { JniBridge.nativeNoOp() }
                    }

                    // ── Phase 2: Convert block time to per-call nanoseconds ───
                    // measure() returns median wall time in microseconds for the
                    // entire block of CALLS iterations. We multiply by 1000 to
                    // get nanoseconds, then divide by CALLS to get per-call cost.
                    val kotlinNs   = (kWall * 1_000L) / CALLS
                    val jniNs      = (jWall * 1_000L) / CALLS
                    val boundaryNs = (jniNs - kotlinNs).coerceAtLeast(0L)

                    // ── Phase 3: Logcat output ────────────────────────────────
                    // Raw block timings (what measure() returned)
                    Log.d("BOUNDARY", "=== JNI Boundary Cost Benchmark ===")
                    Log.d("BOUNDARY", "Calls per block : $CALLS")
                    Log.d("BOUNDARY", "Runs measured   : 500 (median taken)")
                    Log.d("BOUNDARY", "")
                    Log.d("BOUNDARY", "-- Block timings (median of 500 runs) --")
                    Log.d("BOUNDARY", "Kotlin block wall : ${kWall} us  (${CALLS} calls total)")
                    Log.d("BOUNDARY", "Kotlin block CPU  : ${kCpu} us")
                    Log.d("BOUNDARY", "Kotlin allocs     : ${kAlloc}")
                    Log.d("BOUNDARY", "")
                    Log.d("BOUNDARY", "JNI block wall    : ${jWall} us  (${CALLS} calls total)")
                    Log.d("BOUNDARY", "JNI block CPU     : ${jCpu} us")
                    Log.d("BOUNDARY", "JNI allocs        : ${jAlloc}")
                    Log.d("BOUNDARY", "")
                    // Per-call breakdown — the numbers that actually matter
                    Log.d("BOUNDARY", "-- Per-call breakdown --")
                    Log.d("BOUNDARY", "Kotlin per call   : ~${kotlinNs} ns")
                    Log.d("BOUNDARY", "JNI per call      : ~${jniNs} ns")
                    Log.d("BOUNDARY", "Boundary overhead : ~${boundaryNs} ns  (~${boundaryNs / 1_000} us)")
                    Log.d("BOUNDARY", "")
                    // Scheduling noise check
                    // If wall >> CPU the OS preempted the thread during measurement.
                    // If they are close the measurement is clean.
                    val kNoise = (kWall - kCpu).coerceAtLeast(0L)
                    val jNoise = (jWall - jCpu).coerceAtLeast(0L)
                    Log.d("BOUNDARY", "-- Measurement quality --")
                    Log.d("BOUNDARY", "Kotlin noise (wall - CPU) : ${kNoise} us  " +
                            if (kNoise < kWall * 0.15) "(clean)" else "(OS noise detected)")
                    Log.d("BOUNDARY", "JNI noise (wall - CPU)    : ${jNoise} us  " +
                            if (jNoise < jWall * 0.15) "(clean)" else "(OS noise detected)")
                    Log.d("BOUNDARY", "")
                    // Sanity check — if these are equal something is wrong
                    if (boundaryNs < 100) {
                        Log.w("BOUNDARY", "WARNING: boundary overhead is under 100ns.")
                        Log.w("BOUNDARY", "Possible causes:")
                        Log.w("BOUNDARY", "  1. App is running in debug mode (check Build Variant)")
                        Log.w("BOUNDARY", "  2. Profiler is attached (disconnect and re-run)")
                        Log.w("BOUNDARY", "  3. Device is heavily throttled (run on idle device)")
                    }
                    Log.d("BOUNDARY", "=== End Benchmark ===")

                    // ── Build result for the UI card ──────────────────────────
                    JniBridge.BenchResult(
                        label        = "JNI boundary cost",
                        subLabel     = "total time for 1000 runs",
                        kotlinWallUs = kotlinNs,
                        jniWallUs    = jniNs,
                        kotlinCpuUs  = kCpu,
                        jniCpuUs     = jCpu,
                        kotlinAllocs = kAlloc,
                        jniAllocs    = jAlloc,
                        insightLines = listOf(
                            "Kotlin per call:   ~${kotlinNs} ns",
                            "JNI per call:      ~${jniNs} ns",
                            "Boundary overhead: ~${boundaryNs} ns (~${boundaryNs / 1_000} us)",
                            "",
                            "Measured as $CALLS calls per block.",
                            "Single-call measurement hides the gap because",
                            "System.nanoTime() resolution is only ~50-100 ns.",
                            "",
                            "What consumes this ~${boundaryNs} ns:",
                            "  Thread state: managed to native and back",
                            "  GC safepoint notification on entry and return",
                            "  Exception check after return",
                            "  Memory barriers for cross-thread visibility",
                            "",
                        )
                    )
                }

                renderCard(container, result, displayUnit = "ns")
                btn.text = "Measure again"
                btn.isEnabled = true
            }
        }
    }
    // ── Section 3: Addition × 2000 ───────────────────────────────────────
    // The loop runs in Kotlin on both sides — only the addition itself
    // differs. 2000 JNI calls × ~2,000 ns each = ~4,000 µs of overhead
    // for ~2 µs of actual arithmetic.
    private fun setupSection3Addition() {
        val btn       = findViewById<MaterialButton>(R.id.btnAdd)
        val container = findViewById<LinearLayout>(R.id.addContainer)

        btn.setOnClickListener {
            btn.isEnabled = false
            btn.text = "Running…"

            lifecycleScope.launch {
                val result = withContext(Dispatchers.Default) {
                    val CALLS = 2_000
                    val A = 123_456_789L
                    val B = 987_654_321L

                    val (kWall, kCpu, kAlloc) = JniBridge.measure(
                        warmup = 50, runs = 100
                    ) { repeat(CALLS) { JniBridge.kotlinAdd(A, B) } }

                    val (jWall, jCpu, jAlloc) = JniBridge.measure(
                        warmup = 50, runs = 100
                    ) { repeat(CALLS) { JniBridge.nativeAdd(A, B) } }

                    val perCallJniNs   = jWall * 1_000 / CALLS
                    val perCallKotlinNs = kWall * 1_000 / CALLS
                    val overheadX      = jWall.toFloat() / kWall.coerceAtLeast(1)

                    JniBridge.BenchResult(
                        label        = "Addition × $CALLS calls",
                        subLabel     = "Same work, Kotlin loop vs 2000 JNI crossings",
                        kotlinWallUs = kWall,
                        jniWallUs    = jWall,
                        kotlinCpuUs  = kCpu,
                        jniCpuUs     = jCpu,
                        kotlinAllocs = kAlloc,
                        jniAllocs    = jAlloc,
                        insightLines = listOf(
                            "Per-call breakdown:",
                            "  Kotlin: ~${perCallKotlinNs} ns per addition",
                            "  JNI:    ~${perCallJniNs} ns per addition",
                            "",
                            "The addition itself:       ~1 ns",
                            "JNI boundary per call:     ~${perCallJniNs - 1} ns",
                            "Overhead is ~${perCallJniNs}× the actual work",
                            "",
                            "Total waste across $CALLS calls:",
                            "  ~${jWall - kWall} µs of pure boundary overhead",
                            "  for ~${kWall} µs of real arithmetic",
                            "",
                            "Fix: batch the work into a single JNI call.",
                            "nativeAddAll(array) → 1 crossing, same result."
                        )
                    )
                }

                renderCard(container, result, displayUnit = "µs")
                btn.text = "Run again"
                btn.isEnabled = true
            }
        }
    }

    // ── Section 4: Sort 500k integers ────────────────────────────────────
    // warmup=5 only — sorting takes ~100ms, so 50 warmups = 5 seconds.
    // 5 warmups is enough for ART to JIT-compile the Kotlin sort path.
    private fun setupSection4Sort() {
        val btn       = findViewById<MaterialButton>(R.id.btnSort)
        val container = findViewById<LinearLayout>(R.id.sortContainer)

        btn.setOnClickListener {
            btn.isEnabled = false
            btn.text = "Sorting 500k…"

            lifecycleScope.launch {
                val result = withContext(Dispatchers.Default) {
                    val SIZE = 500_000

                    // Build one shuffled base array. Both implementations
                    // sort a fresh copy — otherwise the second one gets a
                    // pre-sorted array and finishes in microseconds (unfair).
                    val base = IntArray(SIZE) { it }.also { arr ->
                        for (i in arr.size - 1 downTo 1) {
                            val j = (Math.random() * (i + 1)).toInt()
                            arr[i] = arr[j].also { arr[j] = arr[i] }
                        }
                    }

                    val (kWall, kCpu, kAlloc) = JniBridge.measure(
                        warmup = 5, runs = 20
                    ) {
                        val copy = base.copyOf()
                        JniBridge.kotlinSort(copy)
                    }

                    val (jWall, jCpu, jAlloc) = JniBridge.measure(
                        warmup = 5, runs = 20
                    ) {
                        val copy = base.copyOf()
                        JniBridge.nativeSort(copy)
                    }

                    val boundaryCostPct =
                        "%.3f".format(2f / (jWall.toFloat() / 1_000f) * 100)

                    JniBridge.BenchResult(
                        label        = "Sort 500k integers",
                        subLabel     = "std::sort (introsort) vs Kotlin Arrays.sort (TimSort)",
                        kotlinWallUs = kWall,
                        jniWallUs    = jWall,
                        kotlinCpuUs  = kCpu,
                        jniCpuUs     = jCpu,
                        kotlinAllocs = kAlloc,
                        jniAllocs    = jAlloc,
                        insightLines = listOf(
                            "One JNI call carries ~9.5M comparisons.",
                            "Boundary cost (~2 µs) = ${boundaryCostPct}% of runtime.",
                            "",
                            "Why std::sort wins on Tensor G2:",
                            "  • introsort: quicksort + heapsort fallback",
                            "  • Compiler optimises comparison branches",
                            "  • No GC safepoint polls inside compare loop",
                            "  • No array bounds check on every access",
                            "",
                            "Kotlin uses TimSort (stable sort, merge-based).",
                            "TimSort is excellent for partially-sorted data",
                            "but has more overhead than introsort on random data.",
                            "",
                        )
                    )
                }

                renderCard(container, result, displayUnit = "ms")
                btn.text = "Run again"
                btn.isEnabled = true
            }
        }
    }

    // ── Section 5: Gaussian blur 9×9 on 1920×1080 ────────────────────────
    // 81 samples per pixel × ~2M interior pixels = ~168M ops per call.
    // warmup=3 only — blur takes ~1–2s in Kotlin; 30 warmups = 1 minute.
    // runs=8 gives a stable enough median at this scale.
    private fun setupSection5Blur() {
        val btn       = findViewById<MaterialButton>(R.id.btnBlur)
        val imageRow  = findViewById<LinearLayout>(R.id.imageRow)
        val container = findViewById<LinearLayout>(R.id.blurContainer)
        val imgOrig   = findViewById<ImageView>(R.id.imgOriginal)
        val imgBlur   = findViewById<ImageView>(R.id.imgBlurred)

        btn.setOnClickListener {
            btn.isEnabled = false
            btn.text = "Processing 1920×1080…"

            lifecycleScope.launch {
                data class BlurOut(
                    val result: JniBridge.BenchResult,
                    val orig:   Bitmap,
                    val blurred: Bitmap
                )

                val out = withContext(Dispatchers.Default) {
                    val src = make1080pGradient()
                    val dst = src.copy(Bitmap.Config.ARGB_8888, true)

                    val (kWall, kCpu, kAlloc) = JniBridge.measure(
                        warmup = 3, runs = 8
                    ) { JniBridge.kotlinGaussianBlur(src, dst) }

                    val (jWall, jCpu, jAlloc) = JniBridge.measure(
                        warmup = 3, runs = 8
                    ) { JniBridge.nativeGaussianBlur(src, dst) }

                    // Produce the display bitmap
                    val displaySrc = make1080pGradient(512, 288)
                    val displayDst = displaySrc.copy(Bitmap.Config.ARGB_8888, true)
                    JniBridge.nativeGaussianBlur(displaySrc, displayDst)

                    // ~8MB IntArray Kotlin allocates every call
                    val allocMB = "%.1f".format(
                        1920f * 1080f * 4f / 1_000_000f
                    )

                    BlurOut(
                        result = JniBridge.BenchResult(
                            label        = "9×9 Blur — 1920×1080",
                            subLabel     = "81 samples/pixel, ~168M multiply-add ops per call",
                            kotlinWallUs = kWall,
                            jniWallUs    = jWall,
                            kotlinCpuUs  = kCpu,
                            jniCpuUs     = jCpu,
                            kotlinAllocs = kAlloc,
                            jniAllocs    = jAlloc,
                            insightLines = listOf(
                                "Why JNI wins ~10–14× on Pixel 7a (Tensor G2):",
                                "",
                                "SIMD (biggest factor):",
                                "  C++  → NEON float32x4_t: 4 pixels/instruction",
                                "  ART  → scalar: 1 pixel/instruction",
                                "  Gap: ~4× from SIMD alone",
                                "",
                                "Memory (second biggest):",
                                "  JNI  → raw uint32_t*, zero allocation",
                                "  Kotlin → allocates ~${allocMB} MB IntArray",
                                "  Kotlin also copies pixels in + out (2× memcpy)",
                                "",
                                "Safety overhead eliminated by JNI:",
                                "  No array bounds check (81 checks/pixel in Kotlin)",
                                "  No null check on every pixel read",
                                "  No GC safepoint polls in inner loop",
                                "",
                                "GC impact:",
                                "  JNI allocs: 0 objects during 168M operations",
                                "  Kotlin: ${allocMB} MB allocation → GC pressure",
                                "  In a 60fps pipeline: Kotlin risks a GC pause stutter"
                            )
                        ),
                        orig    = displaySrc,
                        blurred = displayDst
                    )
                }

                imgOrig.setImageBitmap(out.orig)
                imgBlur.setImageBitmap(out.blurred)
                imageRow.visibility = View.VISIBLE

                renderCard(container, out.result, displayUnit = "ms")
                btn.text = "Run again"
                btn.isEnabled = true
            }
        }
    }

    // ── Card renderer ─────────────────────────────────────────────────────
    // Builds the full analysis card programmatically.
    // displayUnit: "ns" for no-op, "µs" for addition, "ms" for sort + blur.
    private fun renderCard(
        container: LinearLayout,
        r: JniBridge.BenchResult,
        displayUnit: String
    ) {
        container.removeAllViews()

        fun fmt(us: Long): String = when (displayUnit) {
            "ns" -> "${us * 1_000} ns"
            "ms" -> "${"%.2f".format(us / 1_000f)} ms"
            else -> "$us µs"
        }

        val ctx = this

        // ── Verdict banner ────────────────────────────────────────────────
        val banner = TextView(ctx).apply {
            text = r.verdictText
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(Color.WHITE)
            setBackgroundColor(
                if (r.speedupRatio > 1f) Color.parseColor("#854F0B")
                else                     Color.parseColor("#185FA5")
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(10)) }
        }
        container.addView(banner)

        // ── Sub-label ─────────────────────────────────────────────────────
        container.addView(TextView(ctx).apply {
            text = r.subLabel
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(12)) }
        })

        // ── Side-by-side timing boxes ─────────────────────────────────────
        val timingRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(12)) }
        }

        fun timingBox(impl: String, time: String, implColor: String, bgColor: String): LinearLayout {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor(bgColor))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                (layoutParams as? LinearLayout.LayoutParams)?.weight = 1f
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                    it.setMargins(0, 0, dp(6), 0)
                }
                addView(TextView(ctx).apply {
                    text = impl; textSize = 11f
                    setTextColor(Color.parseColor(implColor))
                })
                addView(TextView(ctx).apply {
                    text = time; textSize = 24f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(implColor))
                })
            }
        }

        timingRow.addView(timingBox("Kotlin",   fmt(r.kotlinWallUs), "#185FA5", "#E6F1FB"))
        timingRow.addView(timingBox("JNI (C++)", fmt(r.jniWallUs),   "#854F0B", "#FAEEDA"))
        container.addView(timingRow)

        // ── CPU time row ──────────────────────────────────────────────────
        // Wall vs CPU comparison tells you if the measurement is clean.
        // If they're close → compute-bound → the number is reliable.
        // If wall >> CPU → OS preempted the thread → noisy, re-run.
        container.addView(TextView(ctx).apply {
            text = "CPU time — Kotlin: ${fmt(r.kotlinCpuUs)}  |  JNI: ${fmt(r.jniCpuUs)}\n" +
                    r.measureQuality
            textSize = 11f
            setTextColor(Color.parseColor("#666666"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(6)) }
        })

        // ── Alloc row ─────────────────────────────────────────────────────
        // JNI doing compute-heavy work should show 0 allocations.
        // Any non-zero JNI alloc count means NewObject/NewArray was called.
        container.addView(TextView(ctx).apply {
            text = "Heap allocs — Kotlin: ${r.kotlinAllocs}  |  JNI: ${r.jniAllocs}" +
                    if (r.jniAllocs == 0) "  ← zero GC pressure" else ""
            textSize = 11f
            setTextColor(Color.parseColor("#666666"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(12)) }
        })

        // ── Insight box ───────────────────────────────────────────────────
        container.addView(TextView(ctx).apply {
            text = r.insightLines.joinToString("\n")
            textSize = 12f
            setTextColor(Color.parseColor("#333333"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    fun make1080pGradient(w: Int = 1920, h: Int = 1080): Bitmap {
        val pixels = IntArray(w * h)
        val hsv = floatArrayOf(0f, 0.85f, 0.95f) // Hue, Saturation, Value

        for (y in 0 until h) {
            val py = y.toFloat() / h
            for (x in 0 until w) {
                val px = x.toFloat() / w

                // 1. Calculate a dynamic Hue (0 to 360 degrees)
                // This creates a nice transition from Purple (280) to Cyan (180)
                hsv[0] = (280f - (px * 100f) - (py * 50f) + 360f) % 360f

                // 2. Set the pixel using the Android Color utility
                pixels[y * w + x] = Color.HSVToColor(hsv)
            }
        }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }
}