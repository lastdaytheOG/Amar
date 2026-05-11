package com.amar.vault.agent.control.executors

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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

/**
 * Executor for the search_app action.
 *
 * Tier 1: app-specific deep link (fastest, most reliable).
 * Tier 2: generic ACTION_SEARCH with package targeting.
 * Tier 3: app launch only (search not performed — reported as Failed).
 */
class SearchAppExecutor(
    private val context: Context,
    private val watchlist: PackageWatchlist
) : ActionExecutor {

    override val handles: ActionKind = ActionKind.SEARCH_APP
    override val tier: ExecutorTier = ExecutorTier.INTENT

    override fun isAvailable(): Boolean = true

    override suspend fun execute(action: AgentAction, ctx: TaskContext): ExecutionResult {
        val search = action as? AgentAction.SearchApp ?: return ExecutionResult.FatalFailure(
            reason = FailureReason.Unexpected("SearchAppExecutor received ${action.kind}"),
            durationMs = 0
        )

        val started = System.currentTimeMillis()
        val normalizedApp = normalize(search.app)

        val deepLinkIntent = buildDeepLinkIntent(normalizedApp, search.query)
        if (deepLinkIntent != null) {
            val pkg = deepLinkIntent.`package`
            if (pkg != null) watchlist.acquire(pkg)

            val launched = launchIntent(deepLinkIntent)
            val dur = System.currentTimeMillis() - started

            return if (launched) {
                Log.d(TAG, "Deep link search succeeded for $normalizedApp: ${search.query}")
                ExecutionResult.Executed(
                    durationMs = dur,
                    resultData = mapOf(
                        "app" to search.app,
                        "query" to search.query,
                        "strategy" to "deep_link"
                    )
                )
            } else {
                if (pkg != null) watchlist.release(pkg)
                Log.w(TAG, "Deep link launch failed for $normalizedApp, trying generic search")
                tryGenericSearch(search, normalizedApp, started)
            }
        }

        return tryGenericSearch(search, normalizedApp, started)
    }

    // -------------------------------------------------------------------------
    // Tier 1: App-specific deep links
    // -------------------------------------------------------------------------

    private fun buildDeepLinkIntent(normalizedApp: String, query: String): Intent? {
        val encodedQuery = Uri.encode(query)

        return when (normalizedApp) {
            "settings" -> Intent().apply {
                setClassName(
                    "com.android.settings",
                    "com.android.settings.Settings\$SearchSettingsActivity"
                )
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            "youtube" -> Intent(Intent.ACTION_SEARCH).apply {
                `package` = "com.google.android.youtube"
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            "spotify" -> Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encodedQuery")).apply {
                `package` = "com.spotify.music"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            "chrome", "googlechrome", "browser" -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=$encodedQuery")
            ).apply {
                `package` = "com.android.chrome"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            "instagram" -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.instagram.com/explore/tags/$encodedQuery/")
            ).apply {
                `package` = "com.instagram.android"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            "amazon" -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.amazon.in/s?k=$encodedQuery")
            ).apply {
                `package` = "in.amazon.mShop.android.shopping"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            "flipkart" -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.flipkart.com/search?q=$encodedQuery")
            ).apply {
                `package` = "com.flipkart.android"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            "maps", "googlemaps" -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=$encodedQuery")
            ).apply {
                `package` = "com.google.android.apps.maps"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            "playstore", "googleplaystore" -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://search?q=$encodedQuery")
            ).apply {
                `package` = "com.android.vending"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            "twitter", "x" -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://twitter.com/search?q=$encodedQuery")
            ).apply {
                `package` = "com.twitter.android"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            "netflix" -> Intent(Intent.ACTION_SEARCH).apply {
                `package` = "com.netflix.mediaclient"
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            "google" -> Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // --- Indian delivery / grocery apps -------------------------------
            "blinkit" -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://blinkit.com/s/?q=$encodedQuery")
            ).apply {
                `package` = "app.blinkit.consumer"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            "zepto" -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.zeptonow.com/search?query=$encodedQuery")
            ).apply {
                `package` = "com.zepto.consumer"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            "swiggy" -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.swiggy.com/search?query=$encodedQuery")
            ).apply {
                `package` = "in.swiggy.android"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            "zomato" -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.zomato.com/search?q=$encodedQuery")
            ).apply {
                `package` = "com.application.zomato"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            "jiomart" -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.jiomart.com/search/$encodedQuery")
            ).apply {
                `package` = "com.jio.retail.etailer"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            else -> null
        }
    }

    // -------------------------------------------------------------------------
    // Tier 2: Generic ACTION_SEARCH with package
    // -------------------------------------------------------------------------

    private suspend fun tryGenericSearch(
        search: AgentAction.SearchApp,
        normalizedApp: String,
        started: Long
    ): ExecutionResult {
        val packageId = resolvePackage(normalizedApp) ?: return ExecutionResult.Failed(
            reason = FailureReason.TargetNotFound(
                target = search.app,
                strategiesTried = listOf("deep_link", "common_apps", "pm_label_exact")
            ),
            durationMs = System.currentTimeMillis() - started
        )

        watchlist.acquire(packageId)

        // Check if target app actually handles ACTION_SEARCH before firing.
        val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
            `package` = packageId
            putExtra(SearchManager.QUERY, search.query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val handles = pm.queryIntentActivities(searchIntent, 0).isNotEmpty()

        if (handles && launchIntent(searchIntent)) {
            Log.d(TAG, "Generic ACTION_SEARCH succeeded for $packageId: ${search.query}")
            return ExecutionResult.Executed(
                durationMs = System.currentTimeMillis() - started,
                resultData = mapOf(
                    "package" to packageId,
                    "query" to search.query,
                    "strategy" to "action_search"
                )
            )
        }

        // Fallback: open the app so the user can continue manually.
        val appLaunchIntent = pm.getLaunchIntentForPackage(packageId)
        if (appLaunchIntent != null) {
            appLaunchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchIntent(appLaunchIntent)) {
                Log.w(TAG, "App launched but search not performed natively for $packageId")
                watchlist.release(packageId)
                return ExecutionResult.Failed(
                    reason = FailureReason.SystemError(
                        "App '$packageId' does not support ACTION_SEARCH. " +
                                "App was opened but query was not entered. " +
                                "Consider multi-step (open_app + click + type_text)."
                    ),
                    durationMs = System.currentTimeMillis() - started
                )
            }
        }

        watchlist.release(packageId)
        return ExecutionResult.Failed(
            reason = FailureReason.SystemError("Could not launch search for $packageId"),
            durationMs = System.currentTimeMillis() - started
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun launchIntent(intent: Intent): Boolean {
        return try {
            val launcher: Context =
                com.amar.vault.agent.CurrentActivityHolder.get() ?: context
            launcher.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Intent launch failed: ${t.message}")
            false
        }
    }

    private fun normalize(s: String): String =
        s.lowercase(Locale.ROOT).replace("[^a-z0-9]".toRegex(), "")

    private suspend fun resolvePackage(normalizedApp: String): String? {
        COMMON_APPS[normalizedApp]?.let { candidates ->
            for (pkg in candidates) {
                if (isPackageInstalled(pkg)) return pkg
            }
        }

        return withContext(Dispatchers.Default) {
            findPackageByLabel(normalizedApp)
        }
    }

    private fun isPackageInstalled(pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * EXACT-match only. Removed the contains() branch — that caused "chrome"
     * to resolve to Google One (label "Google One" contains "chrom" variants
     * via fuzzy labels). If exact match fails, return null — caller will
     * report TargetNotFound cleanly.
     */
    private fun findPackageByLabel(query: String): String? {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(mainIntent, 0)

        for (ri in apps) {
            val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull() ?: continue
            val pkg = ri.activityInfo?.packageName ?: continue
            if (normalize(label) == query) {
                return pkg
            }
        }
        return null
    }

    companion object {
        private const val TAG = "SearchAppExecutor"

        private val COMMON_APPS = mapOf(
            "settings"    to listOf("com.android.settings"),
            "youtube"     to listOf("com.google.android.youtube"),
            "spotify"     to listOf("com.spotify.music"),
            "chrome"      to listOf("com.android.chrome"),
            "instagram"   to listOf("com.instagram.android"),
            "amazon"      to listOf(
                "in.amazon.mShop.android.shopping",
                "com.amazon.mShop.android.shopping"
            ),
            "flipkart"    to listOf("com.flipkart.android"),
            "netflix"     to listOf("com.netflix.mediaclient"),
            "twitter"     to listOf("com.twitter.android"),
            "x"           to listOf("com.twitter.android"),
            "maps"        to listOf("com.google.android.apps.maps"),
            "googlemaps"  to listOf("com.google.android.apps.maps"),
            "playstore"   to listOf("com.android.vending"),
            "whatsapp"    to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "telegram"    to listOf("org.telegram.messenger"),
            "facebook"    to listOf("com.facebook.katana"),
            "google"      to listOf("com.google.android.googlequicksearchbox"),
            "blinkit"     to listOf("app.blinkit.consumer"),
            "zepto"       to listOf("com.zepto.consumer"),
            "swiggy"      to listOf("in.swiggy.android"),
            "zomato"      to listOf("com.application.zomato"),
            "jiomart"     to listOf("com.jio.retail.etailer")
        )
    }
}