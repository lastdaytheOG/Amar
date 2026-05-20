package com.amar.vault.agent.resolver

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Layer 8: Resolver Improvements.
 *
 * A dedicated app-resolution service, separate from inline resolver logic
 * inside executors. Provides:
 *
 *   - Alias-first resolution (deterministic, fast, always correct)
 *   - Normalized label matching
 *   - Confidence score per match, with a REJECT threshold
 *   - Explicit rejection of ambiguous matches — never silently pick the
 *     wrong app
 *
 * Contract:
 *   resolve(name) returns one of:
 *     - Resolved(pkg, confidence)  — confidence >= ACCEPT_THRESHOLD
 *     - Ambiguous(candidates)      — multiple matches above LOW_THRESHOLD
 *     - NotFound                   — no match above LOW_THRESHOLD
 *
 * Caller decides what to do with Ambiguous (ask user, pick highest, refuse).
 * This is a stark improvement over the executor's "pick first match" behavior.
 */
@Singleton
class AppResolver @Inject constructor(
    private val context: Context
) {

    @Volatile
    private var installedCache: List<InstalledApp>? = null

    fun resolve(userFacingName: String): ResolveResult {
        if (userFacingName.isBlank()) return ResolveResult.NotFound

        val normalized = normalize(userFacingName)

        // Tier 1: explicit alias table. Confidence 1.0 (exact known mapping).
        ALIASES[normalized]?.let { candidates ->
            val installed = candidates.firstOrNull { isInstalled(it) }
            if (installed != null) {
                return ResolveResult.Resolved(installed, confidence = 1.0)
            }
        }

        val apps = getInstalled()
        if (apps.isEmpty()) return ResolveResult.NotFound

        // Tier 2: exact normalized-label match. Confidence 0.95.
        apps.firstOrNull { it.normalizedLabel == normalized }?.let {
            return ResolveResult.Resolved(it.packageName, confidence = 0.95)
        }

        // Tier 3: starts-with match. Confidence 0.80 if single, drops if ambiguous.
        val startsWith = apps.filter { it.normalizedLabel.startsWith(normalized) }
        if (startsWith.size == 1) {
            return ResolveResult.Resolved(startsWith.first().packageName, confidence = 0.80)
        }
        if (startsWith.size > 1) {
            return ResolveResult.Ambiguous(
                candidates = startsWith.map {
                    ResolveCandidate(it.packageName, it.label, confidence = 0.60)
                }
            )
        }

        // Tier 4: fuzzy (Levenshtein-normalized similarity). Must beat
        // FUZZY_MIN threshold and there must be a clear winner.
        val scored = apps.map { it to similarity(it.normalizedLabel, normalized) }
            .filter { it.second >= FUZZY_MIN }
            .sortedByDescending { it.second }

        if (scored.isEmpty()) return ResolveResult.NotFound

        val top = scored[0]
        val runner = scored.getOrNull(1)

        // Require a gap between top and runner-up to call it a clear winner.
        return if (runner == null || (top.second - runner.second) >= CLEAR_WINNER_GAP) {
            ResolveResult.Resolved(top.first.packageName, confidence = top.second)
        } else {
            ResolveResult.Ambiguous(
                candidates = scored.take(3).map {
                    ResolveCandidate(it.first.packageName, it.first.label, it.second)
                }
            )
        }
    }

    /** Drop the cached installed-apps list — call on PACKAGE_ADDED broadcasts. */
    fun invalidateCache() {
        installedCache = null
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private fun getInstalled(): List<InstalledApp> {
        installedCache?.let { return it }

        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved: List<ResolveInfo> = pm.queryIntentActivities(launcher, 0)

        val list = resolved.mapNotNull { ri ->
            val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull()
            val pkg = ri.activityInfo?.packageName
            if (!label.isNullOrBlank() && !pkg.isNullOrBlank()) {
                InstalledApp(label, normalize(label), pkg)
            } else null
        }.distinctBy { it.packageName }

        installedCache = list
        return list
    }

    private fun isInstalled(pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }

    private fun normalize(s: String): String =
        s.lowercase(Locale.ROOT).replace("[^a-z0-9]".toRegex(), "")

    /**
     * Similarity score in [0.0, 1.0] based on Levenshtein distance
     * normalized to the longer string's length.
     */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val longer = max(a.length, b.length)
        if (longer == 0) return 1.0
        val dist = levenshtein(a, b)
        return 1.0 - (dist.toDouble() / longer.toDouble())
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j

        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }

    // =========================================================================
    // Tables + constants
    // =========================================================================

    private data class InstalledApp(
        val label: String,
        val normalizedLabel: String,
        val packageName: String
    )

    companion object {
        private const val FUZZY_MIN = 0.75
        private const val CLEAR_WINNER_GAP = 0.10

        /**
         * Authoritative alias table. Add entries here to make user-facing
         * names resolve deterministically to a specific package.
         */
        private val ALIASES: Map<String, List<String>> = mapOf(
            "whatsapp"   to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "wa"         to listOf("com.whatsapp"),
            "telegram"   to listOf("org.telegram.messenger"),
            "chrome"     to listOf("com.android.chrome"),
            "googlechrome" to listOf("com.android.chrome"),
            "youtube"    to listOf("com.google.android.youtube"),
            "yt"         to listOf("com.google.android.youtube"),
            "gemini"     to listOf("com.google.android.googlequicksearchbox"),
            "googlegemini" to listOf("com.google.android.googlequicksearchbox"),
            "askgemini"  to listOf("com.google.android.googlequicksearchbox"),
            "instagram"  to listOf("com.instagram.android"),
            "ig"         to listOf("com.instagram.android"),
            "facebook"   to listOf("com.facebook.katana"),
            "fb"         to listOf("com.facebook.katana"),
            "spotify"    to listOf("com.spotify.music"),
            "netflix"    to listOf("com.netflix.mediaclient"),
            "gmail"      to listOf("com.google.android.gm"),
            "maps"       to listOf("com.google.android.apps.maps"),
            "googlemaps" to listOf("com.google.android.apps.maps"),
            "photos"     to listOf("com.google.android.apps.photos"),
            "googlephotos" to listOf("com.google.android.apps.photos"),
            "phone"      to listOf("com.google.android.dialer"),
            "dialer"     to listOf("com.google.android.dialer"),
            "messages"   to listOf("com.google.android.apps.messaging"),
            "calendar"   to listOf("com.google.android.calendar"),
            "settings"   to listOf("com.android.settings"),
            "playstore"  to listOf("com.android.vending"),
            "play"       to listOf("com.android.vending"),
            "camera"     to listOf(
                "com.motorola.camera3",
                "com.google.android.GoogleCamera",
                "com.android.camera2"
            ),
            "gpay"       to listOf("com.google.android.apps.nbu.paisa.user"),
            "googlepay"  to listOf("com.google.android.apps.nbu.paisa.user"),
            "paytm"      to listOf("net.one97.paytm"),
            "phonepe"    to listOf("com.phonepe.app"),
            "blinkit"    to listOf("app.blinkit.consumer"),
            "swiggy"     to listOf("in.swiggy.android"),
            "zomato"     to listOf("com.application.zomato"),
            "amazon"     to listOf(
                "in.amazon.mShop.android.shopping",
                "com.amazon.mShop.android.shopping"
            ),
            "flipkart"   to listOf("com.flipkart.android")
        )
    }
}

sealed class ResolveResult {
    data class Resolved(
        val packageName: String,
        val confidence: Double
    ) : ResolveResult()

    data class Ambiguous(
        val candidates: List<ResolveCandidate>
    ) : ResolveResult()

    data object NotFound : ResolveResult()
}

data class ResolveCandidate(
    val packageName: String,
    val label: String,
    val confidence: Double
)