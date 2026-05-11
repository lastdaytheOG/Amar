package com.amar.vault.agent.brain

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES20
import android.os.Build
import android.util.Log
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

/**
 * Probes the device to decide which model tier to use.
 *
 * Why this exists:
 *   The agent targets Android devices from SD695-class floor up through
 *   flagships. A single fixed model choice wastes capability on high-end
 *   devices (Gemma 3 1B on a flagship is leaving quality on the table) and
 *   OOMs low-end devices (Gemma 4 E2B on an SD695 = swap thrash + 30s TTFT).
 *
 *   DeviceCapability runs ONCE at startup, produces a [DeviceTier], and that
 *   decides which [ModelSpec] to use. Cheap: only a few property reads + one
 *   EGL probe.
 *
 * Signals used:
 *   - Total RAM (from ActivityManager.MemoryInfo)
 *   - Available RAM at probe time (informational, not dispositive)
 *   - SoC model (Build.SOC_MODEL on API 31+) — some SoCs have known perf cliffs
 *   - GPU renderer string (OpenGL) — proxy for Adreno vs Mali vs Xclipse
 *   - CPU core count
 *
 * NOT used:
 *   - Benchmark runs. Takes too long for startup.
 *   - Firebase remote config / A/B. That's a phase-3 concern.
 *
 * Tier decisions are conservative. When in doubt, TIER_A (the safe default).
 */
class DeviceCapability(private val context: Context) {

    /**
     * Probe the device and return its tier. Cached — call multiple times
     * cheaply.
     */
    fun detect(): DeviceProfile {
        cached?.let { return it }

        val ram = totalRamMb()
        val cores = Runtime.getRuntime().availableProcessors()
        val socModel = safeSocModel()
        val gpu = safeGpuRenderer()
        val hasHighEndGpu = gpu?.let { isHighEndGpu(it) } ?: false

        val tier = pickTier(ram, cores, socModel, hasHighEndGpu)

        val profile = DeviceProfile(
            tier = tier,
            totalRamMb = ram,
            coreCount = cores,
            socModel = socModel,
            gpuRenderer = gpu,
            hasHighEndGpu = hasHighEndGpu
        )

        Log.i(TAG, "Device profile: $profile")
        cached = profile
        return profile
    }

    /**
     * Pick the recommended model for this device. Honors explicit user override
     * if one has been set (via settings).
     */
    fun recommendedModel(override: ModelSpec? = null): ModelSpec {
        if (override != null) return override
        val profile = detect()
        return when (profile.tier) {
            DeviceTier.TIER_S -> ModelManifest.GEMMA_4_E2B
            DeviceTier.TIER_A -> ModelManifest.GEMMA_3_1B
            DeviceTier.TIER_B -> ModelManifest.GEMMA_3_1B   // same default, just more cautious settings
        }
    }

    // -------------------------------------------------------------------------
    // Tier decision
    // -------------------------------------------------------------------------

    private fun pickTier(
        ramMb: Int,
        cores: Int,
        socModel: String?,
        hasHighEndGpu: Boolean
    ): DeviceTier {
        // Hard floor — if the device genuinely can't run Gemma 3 1B, we'll
        // degrade gracefully but still declare TIER_B. The engine layer
        // enforces OOM-avoidance; we don't refuse to load here.
        if (ramMb < 2500) return DeviceTier.TIER_B

        // TIER_S: enough RAM for Gemma 4 E2B (~1.2GB + overhead) AND decent GPU.
        // RAM check is the hard gate; GPU is what makes it actually usable.
        val canFitGemma4 = ramMb >= ModelManifest.GEMMA_4_E2B.recommendedRamMb
        if (canFitGemma4 && hasHighEndGpu && cores >= 8) {
            return DeviceTier.TIER_S
        }

        // TIER_A: the "reliable default" zone — SD695-class up to mid-range.
        // Gemma 3 1B runs well here on CPU, better with GPU.
        return DeviceTier.TIER_A
    }

    // -------------------------------------------------------------------------
    // Probes
    // -------------------------------------------------------------------------

    private fun totalRamMb(): Int {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.totalMem / (1024 * 1024)).toInt()
        } catch (t: Throwable) {
            Log.w(TAG, "RAM probe failed: ${t.message}")
            0
        }
    }

    private fun safeSocModel(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { Build.SOC_MODEL } catch (t: Throwable) { null }
        } else null
    }

    /**
     * Read the GL_RENDERER string. Requires an EGL context, which we create
     * temporarily via EGL10 — doesn't require a Surface, so this runs without
     * a UI. ~15ms cost.
     *
     * We do this defensively because GL_RENDERER can be blank on some OEM
     * ROMs, and we'd rather downgrade to TIER_A silently than crash on GPU probe.
     */
    private fun safeGpuRenderer(): String? {
        var display: EGLDisplay? = null
        var gl: EGLContext? = null
        return try {
            val egl = EGLContext.getEGL() as EGL10
            display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            if (display == EGL10.EGL_NO_DISPLAY) return null

            val version = IntArray(2)
            if (!egl.eglInitialize(display, version)) return null

            val attribs = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 4,  // OpenGL ES 2
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                EGL10.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            if (!egl.eglChooseConfig(display, attribs, configs, 1, configCount) || configCount[0] == 0) {
                return null
            }

            val ctxAttribs = intArrayOf(0x3098, 2, EGL10.EGL_NONE)  // EGL_CONTEXT_CLIENT_VERSION
            gl = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT, ctxAttribs)
            if (gl == EGL10.EGL_NO_CONTEXT) return null

            val pbufferAttribs = intArrayOf(EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE)
            val surface = egl.eglCreatePbufferSurface(display, configs[0], pbufferAttribs)
            if (surface == EGL10.EGL_NO_SURFACE) return null

            egl.eglMakeCurrent(display, surface, surface, gl)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            egl.eglDestroySurface(display, surface)
            renderer
        } catch (t: Throwable) {
            Log.w(TAG, "GPU probe failed: ${t.message}")
            null
        } finally {
            try {
                if (gl != null && display != null) {
                    (EGLContext.getEGL() as EGL10).eglDestroyContext(display, gl)
                }
                if (display != null) (EGLContext.getEGL() as EGL10).eglTerminate(display)
            } catch (_: Throwable) { /* cleanup best-effort */ }
        }
    }

    /**
     * Rough classification of GPU strings. Not perfect — new chips will get
     * classified TIER_A by default until this list is updated. Conservative.
     */
    private fun isHighEndGpu(renderer: String): Boolean {
        val r = renderer.lowercase()
        // Snapdragon flagships: Adreno 7xx and up.
        if (r.contains("adreno")) {
            val num = Regex("""adreno\s*\(?tm\)?\s*(\d+)""").find(r)?.groupValues?.get(1)?.toIntOrNull()
            if (num != null && num >= 700) return true
        }
        // Mali flagships: G710+ (Dimensity 9000, Exynos 2200+).
        if (r.contains("mali")) {
            val num = Regex("""mali-?g\s*(\d+)""").find(r)?.groupValues?.get(1)?.toIntOrNull()
            if (num != null && num >= 710) return true
        }
        // Samsung Xclipse (RDNA2-based) — anything that identifies as such is flagship.
        if (r.contains("xclipse")) return true
        // Tensor / Immortalis
        if (r.contains("immortalis")) return true
        return false
    }

    companion object {
        private const val TAG = "DeviceCapability"

        @Volatile
        private var cached: DeviceProfile? = null

        /** For tests — resets the cached probe result. */
        internal fun resetForTest() { cached = null }
    }
}

/**
 * Three-tier device classification. Fewer tiers means simpler routing;
 * more tiers means finer-grained model selection. Three hits the sweet spot.
 */
enum class DeviceTier {
    /** Flagship / near-flagship: can run Gemma 4 E2B comfortably with GPU. */
    TIER_S,

    /** Mid-range to upper-mid (SD695 through SD7 Gen 1 ish): Gemma 3 1B sweet spot. */
    TIER_A,

    /** Low-end: Gemma 3 1B in CPU-only, conservative settings. */
    TIER_B
}

data class DeviceProfile(
    val tier: DeviceTier,
    val totalRamMb: Int,
    val coreCount: Int,
    val socModel: String?,
    val gpuRenderer: String?,
    val hasHighEndGpu: Boolean
)