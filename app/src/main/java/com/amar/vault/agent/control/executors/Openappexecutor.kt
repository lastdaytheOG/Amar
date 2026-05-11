package com.amar.vault.agent.control.executors

import android.content.Context
import android.content.Intent
import android.util.Log
import com.amar.vault.agent.control.ActionExecutor
import com.amar.vault.agent.control.ExecutionResult
import com.amar.vault.agent.control.ExecutorTier
import com.amar.vault.agent.control.FailureReason
import com.amar.vault.agent.control.TaskContext
import com.amar.vault.agent.dsl.ActionKind
import com.amar.vault.agent.dsl.AgentAction
import com.amar.vault.agent.perception.PackageWatchlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class OpenAppExecutor(
    private val context: Context,
    private val watchlist: PackageWatchlist
) : ActionExecutor {

    override val handles: ActionKind = ActionKind.OPEN_APP
    override val tier: ExecutorTier = ExecutorTier.INTENT

    override fun isAvailable(): Boolean = true

    data class AppInfo(
        val label: String,
        val normalizedLabel: String,
        /** Individual normalized word tokens from the label. */
        val tokens: List<String>,
        val packageName: String
    )

    private var appCache: List<AppInfo>? = null

    private fun getApps(): List<AppInfo> {
        appCache?.let { return it }

        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(intent, 0)

        val result = apps.mapNotNull { ri ->
            val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull()
            val pkg = ri.activityInfo?.packageName

            if (label != null && pkg != null) {
                val tokens = label.lowercase(Locale.ROOT)
                    .split(Regex("[^a-z0-9]+"))
                    .filter { it.isNotBlank() }
                AppInfo(
                    label = label,
                    normalizedLabel = normalize(label),
                    tokens = tokens,
                    packageName = pkg
                )
            } else null
        }.distinctBy { it.packageName }

        appCache = result
        return result
    }

    override suspend fun execute(action: AgentAction, ctx: TaskContext): ExecutionResult {
        val open = action as? AgentAction.OpenApp ?: return ExecutionResult.FatalFailure(
            reason = FailureReason.Unexpected("OpenAppExecutor received ${action.kind}"),
            durationMs = 0
        )

        val started = System.currentTimeMillis()

        val packageId = resolvePackage(open) ?: run {
            return ExecutionResult.Failed(
                reason = FailureReason.TargetNotFound(
                    target = open.app,
                    strategiesTried = listOf(
                        "explicit_package", "hardcoded_map",
                        "exact_label", "word_match", "prefix_match",
                        "contains_match", "levenshtein"
                    )
                ),
                durationMs = System.currentTimeMillis() - started
            )
        }

        watchlist.acquire(packageId)

        val launchOk = launch(packageId)
        val dur = System.currentTimeMillis() - started

        return if (launchOk) {
            ExecutionResult.Executed(
                durationMs = dur,
                resultData = mapOf("package" to packageId)
            )
        } else {
            watchlist.release(packageId)
            ExecutionResult.Failed(
                reason = FailureReason.SystemError("launch failed for $packageId"),
                durationMs = dur
            )
        }
    }

    private suspend fun resolvePackage(open: AgentAction.OpenApp): String? {
        open.packageId?.let {
            if (isPackageInstalled(it)) return it
            Log.i(TAG, "Explicit package '$it' not installed")
        }

        val normalized = normalize(open.app)
        if (normalized.isBlank()) return null

        // Camera special case (Motorola has non-standard package name)
        if (normalized == "camera") {
            resolveCamera()?.let { return it }
        }

        // Hardcoded alias map wins first — deterministic
        COMMON_APPS[normalized]?.let { candidates ->
            for (pkg in candidates) {
                if (isPackageInstalled(pkg)) return pkg
            }
        }

        return withContext(Dispatchers.Default) {
            findPackageByLabel(normalized)
        }
    }

    private fun resolveCamera(): String? {
        val candidates = listOf(
            "com.motorola.camera3",
            "com.google.android.GoogleCamera",
            "com.android.camera2"
        )
        return candidates.firstOrNull { isPackageInstalled(it) }
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun normalize(s: String): String =
        s.lowercase(Locale.ROOT).replace("[^a-z0-9]".toRegex(), "")

    /**
     * Multi-tier fuzzy app matcher.
     *
     * Tiers (first hit wins):
     *   1. EXACT       — normalized label matches query
     *   2. WORD        — any word token matches query exactly
     *   3. PREFIX      — any token or label starts with query (if query >=4 chars)
     *   4. CONTAINS    — query is a substring of any token (if query >=4 chars)
     *   5. LEVENSHTEIN — typo-tolerance with distance cap
     *
     * Each tier may return multiple candidates. Tie-breaker: shortest label
     * wins (more specific), then alphabetically stable. This gives us
     * "garena" → "Garena Free Fire" cleanly (shorter than "Garena Free Fire MAX").
     *
     * Length guards prevent "a" matching every app with letter A in it.
     */
    private fun findPackageByLabel(query: String): String? {
        val apps = getApps()
        if (apps.isEmpty()) return null

        // Tier 1: exact match
        apps.firstOrNull { it.normalizedLabel == query }?.let {
            Log.d(TAG, "matched EXACT: '${it.label}' for '$query'")
            return it.packageName
        }

        // Tier 2: word-level exact match
        // "garena" should match "Garena Free Fire" because "garena" is a word in it.
        apps.filter { it.tokens.contains(query) }
            .minByOrNull { it.normalizedLabel.length }
            ?.let {
                Log.d(TAG, "matched WORD: '${it.label}' for '$query'")
                return it.packageName
            }

        // Guard: short queries (< 4 chars) skip substring/fuzzy tiers
        // to avoid "sms" matching 30 unrelated apps.
        if (query.length < MIN_FUZZY_QUERY) return null

        // Tier 3: prefix match on any token or on the whole label
        // "subway" should match "Subway Surfers" via prefix of "subway" token.
        apps.filter { app ->
            app.tokens.any { it.startsWith(query) } ||
                    app.normalizedLabel.startsWith(query)
        }.minByOrNull { it.normalizedLabel.length }
            ?.let {
                Log.d(TAG, "matched PREFIX: '${it.label}' for '$query'")
                return it.packageName
            }

        // Tier 4: query is substring of a token (or full label)
        // "surfers" should match "Subway Surfers" because "surfers" in tokens.
        // "workout" should match "Home Workout".
        apps.filter { app ->
            app.tokens.any { it.contains(query) } ||
                    app.normalizedLabel.contains(query)
        }.minByOrNull { it.normalizedLabel.length }
            ?.let {
                Log.d(TAG, "matched CONTAINS: '${it.label}' for '$query'")
                return it.packageName
            }

        // Tier 5: Levenshtein fuzzy match on labels AND on individual tokens.
        // Handles typos: "freefier" → "freefire", "whatsap" → "whatsapp".
        //
        // Distance threshold scales with query length. Short queries are
        // strict; longer queries can tolerate more edits.
        val maxDistance = when {
            query.length <= 5 -> 1
            query.length <= 8 -> 2
            else               -> 3
        }

        val fuzzy = apps.mapNotNull { app ->
            // Best distance against whole label or any token
            val candidates = mutableListOf(levenshtein(app.normalizedLabel, query))
            app.tokens.forEach { token ->
                if (token.length >= MIN_FUZZY_QUERY) {
                    candidates += levenshtein(token, query)
                }
            }
            val best = candidates.min()
            if (best <= maxDistance) app to best else null
        }

        // Tie-breaker: smallest distance first, then shortest label.
        return fuzzy
            .sortedWith(
                compareBy({ it.second }, { it.first.normalizedLabel.length })
            )
            .firstOrNull()
            ?.also { Log.d(TAG, "matched LEVENSHTEIN dist=${it.second}: '${it.first.label}' for '$query'") }
            ?.first
            ?.packageName
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
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }

    private fun launch(packageId: String): Boolean {
        return try {
            val pm = context.packageManager

            var intent = pm.getLaunchIntentForPackage(packageId)

            if (intent == null) {
                Log.w(TAG, "Fallback launch for $packageId")

                val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(packageId)
                }

                @Suppress("DEPRECATION")
                val activities = pm.queryIntentActivities(queryIntent, 0)

                if (activities.isEmpty()) {
                    Log.e(TAG, "No launcher activity found for $packageId")
                    return false
                }

                val activity = activities[0].activityInfo

                intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(activity.packageName, activity.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            Log.d(TAG, "Launching app: $packageId")

            val launcher: Context =
                com.amar.vault.agent.CurrentActivityHolder.get() ?: context
            launcher.startActivity(intent)

            true
        } catch (t: Throwable) {
            Log.e(TAG, "Launch failed: ${t.message}")
            false
        }
    }

    companion object {
        private const val TAG = "OpenAppExecutor"

        /**
         * Minimum query length for substring / prefix / fuzzy tiers.
         * Below this we only do exact + word-level matches, to prevent
         * pathological false positives like "a" matching every app.
         */
        private const val MIN_FUZZY_QUERY = 4

        private val COMMON_APPS = mapOf(
            "whatsapp"   to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "telegram"   to listOf("org.telegram.messenger"),
            "chrome"     to listOf("com.android.chrome"),
            "youtube"    to listOf("com.google.android.youtube"),
            "instagram"  to listOf("com.instagram.android"),
            "facebook"   to listOf("com.facebook.katana"),
            "spotify"    to listOf("com.spotify.music"),
            "netflix"    to listOf("com.netflix.mediaclient"),
            "gmail"      to listOf("com.google.android.gm"),
            "maps"       to listOf("com.google.android.apps.maps"),
            "googlemaps" to listOf("com.google.android.apps.maps"),
            "photos"     to listOf("com.google.android.apps.photos"),
            "phone"      to listOf("com.google.android.dialer"),
            "dialer"     to listOf("com.google.android.dialer"),
            "messages"   to listOf("com.google.android.apps.messaging"),
            "calendar"   to listOf("com.google.android.calendar"),
            "settings"   to listOf("com.android.settings"),
            "playstore"  to listOf("com.android.vending"),
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
            "flipkart"   to listOf("com.flipkart.android"),
            "jiomart"    to listOf("com.jio.retail.etailer")
        )
    }
}