package com.amar.vault.agent.execution

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import com.amar.vault.agent.CurrentActivityHolder
import com.amar.vault.agent.capability.ExecutionPlan
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Layer 3 / Execution: native-search via Android's ACTION_SEARCH intent.
 *
 * Most major apps (YouTube, Amazon, Spotify, Play Store, Flipkart) register
 * an activity that handles ACTION_SEARCH with EXTRA_QUERY. This lets us
 * launch directly into their search-results screen without any UI automation.
 *
 * Reliability wins vs. UI-driven search:
 *   - No dependency on finding the search icon in the tree
 *   - No typing-and-submit race conditions
 *   - No per-app layout assumptions
 *   - Works even if the app's UI changes between versions
 *
 * When this fails (activity rejects the intent, app removed ACTION_SEARCH
 * handler, etc.) the caller should fall back to UiSearchExecutor.
 */
@Singleton
class NativeSearchExecutor @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {

    suspend fun execute(plan: ExecutionPlan.NativeSearch): ExecutionOutcome {
        val started = System.currentTimeMillis()

        // Tier 1: App-specific deep links by packageId
        val deepLinkIntent = buildDeepLinkIntent(plan.packageId, plan.query)
        if (deepLinkIntent != null) {
            return try {
                val launcher: Context = CurrentActivityHolder.get() ?: context
                launcher.startActivity(deepLinkIntent)
                ExecutionOutcome.Started(
                    packageId = plan.packageId,
                    durationMs = System.currentTimeMillis() - started,
                    route = "native_search_deep_link"
                )
            } catch (t: Throwable) {
                ExecutionOutcome.Failed(
                    packageId = plan.packageId,
                    detail = t.message ?: t::class.simpleName.orEmpty(),
                    durationMs = System.currentTimeMillis() - started
                )
            }
        }

        // Tier 2: Generic ACTION_SEARCH
        val intent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(plan.packageId)
            putExtra(SearchManager.QUERY, plan.query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(intent, 0)

        if (resolved.isEmpty()) {
            return ExecutionOutcome.NotSupported(
                packageId = plan.packageId,
                detail = "no activity handles ACTION_SEARCH for ${plan.packageId}",
                durationMs = System.currentTimeMillis() - started
            )
        }

        return try {
            val launcher: Context = CurrentActivityHolder.get() ?: context
            launcher.startActivity(intent)
            ExecutionOutcome.Started(
                packageId = plan.packageId,
                durationMs = System.currentTimeMillis() - started,
                route = "native_search"
            )
        } catch (t: Throwable) {
            ExecutionOutcome.Failed(
                packageId = plan.packageId,
                detail = t.message ?: t::class.simpleName.orEmpty(),
                durationMs = System.currentTimeMillis() - started
            )
        }
    }

    private fun buildDeepLinkIntent(packageId: String, query: String): Intent? {
        val encodedQuery = android.net.Uri.encode(query)

        return when (packageId) {
            "com.google.android.youtube" -> Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "com.spotify.music" -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse("spotify:search:$encodedQuery")).apply {
                setPackage("com.spotify.music")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "com.android.chrome" -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=$encodedQuery")).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "com.instagram.android" -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.instagram.com/explore/tags/$encodedQuery/")).apply {
                setPackage("com.instagram.android")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping" -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.amazon.in/s?k=$encodedQuery")).apply {
                setPackage(packageId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "com.flipkart.android" -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.flipkart.com/search?q=$encodedQuery")).apply {
                setPackage("com.flipkart.android")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "com.google.android.apps.maps" -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=$encodedQuery")).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "com.android.vending" -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=$encodedQuery")).apply {
                setPackage("com.android.vending")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "com.twitter.android" -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://twitter.com/search?q=$encodedQuery")).apply {
                setPackage("com.twitter.android")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "com.netflix.mediaclient" -> Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.netflix.mediaclient")
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            "com.google.android.googlequicksearchbox" -> Intent(Intent.ACTION_WEB_SEARCH).apply {
                setPackage("com.google.android.googlequicksearchbox")
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            else -> null
        }
    }
}