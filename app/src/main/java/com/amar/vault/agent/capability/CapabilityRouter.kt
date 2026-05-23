package com.amar.vault.agent.capability

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.amar.vault.agent.intent.IntentType
import com.amar.vault.agent.intent.ParsedIntent
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Layer 2: Capability Router.
 *
 * Takes a ParsedIntent (from Layer 1) and produces an ExecutionPlan that
 * describes HOW the task will be executed. 100% deterministic — no LLM,
 * no probabilistic decisions. Given the same (intent, installed apps,
 * capability table) it always returns the same plan.
 *
 * Responsibilities:
 *   1. Resolve the user-facing app name to a concrete package id
 *   2. Look up what that package can do (native intents available)
 *   3. Pick the best execution route for the requested intent
 *   4. Fail clearly if the intent cannot be fulfilled
 *
 * Why deterministic:
 *   Routing is logic, not language. "Should YouTube's search be dispatched
 *   via ACTION_SEARCH or via UI click?" has a correct answer that doesn't
 *   depend on how the user phrased their request. Putting this under the
 *   LLM would make it slow, non-reproducible, and harder to debug.
 *
 * Extension points:
 *   - APP_ALIASES  : add user-facing names → package ids
 *   - CAPABILITIES : declare what each package natively supports
 *   - WORKFLOWS    : register hardcoded multi-step flows (Layer 7)
 */
@Singleton
class CapabilityRouter @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {

    fun route(parsed: ParsedIntent): RoutingResult {
        val packageId = resolvePackage(parsed.app)
            ?: return RoutingResult.Unresolvable(
                reason = UnresolvableReason.AppNotInstalled(parsed.app),
                parsed = parsed
            )

        val capabilities = capabilitiesFor(packageId)

        return when (parsed.intent) {
            IntentType.OPEN_APP     -> routeOpen(parsed, packageId)
            IntentType.SEARCH_APP   -> routeSearch(parsed, packageId, capabilities)
            IntentType.ORDER_ITEM   -> routeOrder(parsed, packageId, capabilities)
        }
    }

    // =========================================================================
    // Routers per intent type
    // =========================================================================

    private fun routeOpen(parsed: ParsedIntent, packageId: String): RoutingResult {
        return RoutingResult.Plan(
            parsed = parsed,
            packageId = packageId,
            plan = ExecutionPlan.OpenApp(packageId)
        )
    }

    private fun routeSearch(
        parsed: ParsedIntent,
        packageId: String,
        capabilities: AppCapabilities
    ): RoutingResult {
        val query = parsed.query ?: return RoutingResult.Unresolvable(
            reason = UnresolvableReason.MissingQuery,
            parsed = parsed
        )

        return if (capabilities.supportsActionSearch) {
            RoutingResult.Plan(
                parsed = parsed,
                packageId = packageId,
                plan = ExecutionPlan.NativeSearch(packageId, query)
            )
        } else {
            RoutingResult.Plan(
                parsed = parsed,
                packageId = packageId,
                plan = ExecutionPlan.UiSearch(packageId, query)
            )
        }
    }

    private fun routeOrder(
        parsed: ParsedIntent,
        packageId: String,
        capabilities: AppCapabilities
    ): RoutingResult {
        val query = parsed.query ?: return RoutingResult.Unresolvable(
            reason = UnresolvableReason.MissingQuery,
            parsed = parsed
        )

        val workflowId = capabilities.orderWorkflowId
            ?: return RoutingResult.Unresolvable(
                reason = UnresolvableReason.NoWorkflowRegistered(packageId),
                parsed = parsed
            )

        return RoutingResult.Plan(
            parsed = parsed,
            packageId = packageId,
            plan = ExecutionPlan.OrderWorkflow(packageId, workflowId, query)
        )
    }

    // =========================================================================
    // Package resolution
    // =========================================================================

    /**
     * Resolve user-facing app name to an installed package id.
     * Strategy: aliases → exact installed label match → word match → null.
     * Matches the contract of Layer 3's OpenAppExecutor; kept in sync manually.
     */
    private fun resolvePackage(appName: String): String? {
        Log.i("CapabilityRouter", "resolvePackage in='$appName' lower='${appName.lowercase()}'")

        // The Gemini app's launch identity (com.google.android.apps.bard) differs
        // from its runtime identity (com.google.android.googlequicksearchbox).
        // Launch by .bard so we land on the real Gemini conversational surface
        // (not AIM/Google Search). The semantic environment verifier handles
        // the runtime-side identity via signal-based detection.
        when (appName.lowercase().trim()) {
            "com.google.android.apps.bard",
            "com.google.android.apps.bardandroid",
            "com.google.android.apps.gemini",
            "com.google.android.bard",
            "com.google.android.gemini",
            "gemini",
            "bard" -> {
                Log.i("CapabilityRouter", "resolvePackage GEMINI '$appName' -> com.google.android.apps.bard")
                return "com.google.android.apps.bard"
            }
        }

        val normalized = normalize(appName)

        APP_ALIASES[normalized]?.let { candidates ->
            candidates.firstOrNull { isInstalled(it) }?.let { return it }
        }

        return findInstalledByLabel(normalized)
    }

    private fun findInstalledByLabel(normalizedQuery: String): String? {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcher, 0)

        for (ri in apps) {
            val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull()
                ?: continue

            if (normalize(label) == normalizedQuery) {
                return ri.activityInfo?.packageName
            }
        }

        return null
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

    // =========================================================================
    // Capability lookup
    // =========================================================================

    private fun capabilitiesFor(packageId: String): AppCapabilities =
        CAPABILITIES[packageId] ?: AppCapabilities.DEFAULT

    // =========================================================================
    // Static tables — the edit points for supported apps
    // =========================================================================

    companion object {

        /**
         * Add an alias here to make a user-facing name resolve to a specific
         * package. First installed candidate wins.
         * Keep in sync with OpenAppExecutor's COMMON_APPS.
         */
        private val APP_ALIASES: Map<String, List<String>> = mapOf(
            "whatsapp"    to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "telegram"    to listOf("org.telegram.messenger"),
            "chrome"      to listOf("com.android.chrome"),
            "youtube"     to listOf("com.google.android.youtube"),
            // Gemini lives inside Quicksearchbox but at a specific
            // MainActivity component. We use a sentinel package id —
            // resolved in OpenAppExecutor — so "gemini" launches Gemini
            // and "google" stays at the standard Search activity.
            "gemini"     to listOf("com.google.android.googlequicksearchbox#gemini"),
            "googlegemini" to listOf("com.google.android.googlequicksearchbox#gemini"),
            "askgemini"  to listOf("com.google.android.googlequicksearchbox#gemini"),
            // LLM planners often hallucinate Bard/Gemini packages that
            // don't exist on Android. Map them to the real sentinel.
            "com.google.android.apps.bard" to listOf("com.google.android.googlequicksearchbox#gemini"),
            "com.google.android.apps.bardandroid" to listOf("com.google.android.googlequicksearchbox#gemini"),
            "com.google.android.apps.gemini" to listOf("com.google.android.googlequicksearchbox#gemini"),
            "instagram"   to listOf("com.instagram.android"),
            "twitter"     to listOf("com.twitter.android"),
            "x"           to listOf("com.twitter.android"),
            "facebook"    to listOf("com.facebook.katana"),
            "spotify"     to listOf("com.spotify.music"),
            "netflix"     to listOf("com.netflix.mediaclient"),
            "gmail"       to listOf("com.google.android.gm"),
            "maps"        to listOf("com.google.android.apps.maps"),
            "googlemaps"  to listOf("com.google.android.apps.maps"),
            "photos"      to listOf("com.google.android.apps.photos"),
            "phone"       to listOf("com.google.android.dialer"),
            "dialer"      to listOf("com.google.android.dialer"),
            "messages"    to listOf("com.google.android.apps.messaging"),
            "calendar"    to listOf("com.google.android.calendar"),
            "settings"    to listOf("com.android.settings"),
            "playstore"   to listOf("com.android.vending"),
            "camera"      to listOf(
                "com.motorola.camera3",
                "com.google.android.GoogleCamera",
                "com.android.camera2"
            ),
            "gpay"        to listOf("com.google.android.apps.nbu.paisa.user"),
            "googlepay"   to listOf("com.google.android.apps.nbu.paisa.user"),
            "google"      to listOf("com.google.android.googlequicksearchbox"),
            "paytm"       to listOf("net.one97.paytm"),
            "phonepe"     to listOf("com.phonepe.app"),
            "blinkit"     to listOf("com.grofers.customerapp", "app.blinkit.consumer"),
            "swiggy"      to listOf("in.swiggy.android"),
            "zomato"      to listOf("com.application.zomato"),
            "amazon"      to listOf(
                "in.amazon.mShop.android.shopping",
                "com.amazon.mShop.android.shopping"
            ),
            "flipkart"    to listOf("com.flipkart.android")
        )

        /**
         * Declared capabilities per package. Anything not listed gets DEFAULT
         * (open-only). Add an entry here to enable native search / workflows.
         */
        private val CAPABILITIES: Map<String, AppCapabilities> = mapOf(

            "com.android.settings" to AppCapabilities(
                supportsActionSearch = true
            ),

            "com.google.android.youtube" to AppCapabilities(
                supportsActionSearch = true
            ),

            "in.amazon.mShop.android.shopping" to AppCapabilities(
                supportsActionSearch = true
            ),

            "com.amazon.mShop.android.shopping" to AppCapabilities(
                supportsActionSearch = true
            ),

            "com.spotify.music" to AppCapabilities(
                supportsActionSearch = true
            ),

            "com.flipkart.android" to AppCapabilities(
                supportsActionSearch = true
            ),

            "com.android.vending" to AppCapabilities(
                supportsActionSearch = true
            ),

            "com.google.android.googlequicksearchbox" to AppCapabilities(
                // Real Google Search supports ACTION_WEB_SEARCH (opens
                // search results). Gemini sentinel "...#gemini" doesn't
                // appear here and defaults to UI-driven path.
                supportsActionSearch = true
            ),

            "com.android.chrome" to AppCapabilities(
                supportsActionSearch = true
            ),

            "com.instagram.android" to AppCapabilities(
                supportsActionSearch = true
            ),

            "com.google.android.apps.maps" to AppCapabilities(
                supportsActionSearch = true
            ),

            "com.twitter.android" to AppCapabilities(
                supportsActionSearch = true
            ),

            "com.netflix.mediaclient" to AppCapabilities(
                supportsActionSearch = true
            ),

            "com.grofers.customerapp" to AppCapabilities(
                supportsActionSearch = true,
                orderWorkflowId = "blinkit_order_v1"
            ),
            "app.blinkit.consumer" to AppCapabilities(
                supportsActionSearch = true,
                orderWorkflowId = "blinkit_order_v1"
            ),
            "com.zepto.consumer" to AppCapabilities(
                supportsActionSearch = true
            ),
            "in.swiggy.android" to AppCapabilities(
                supportsActionSearch = true
            ),
            "com.application.zomato" to AppCapabilities(
                supportsActionSearch = true
            ),
            "com.jio.retail.etailer" to AppCapabilities(
                supportsActionSearch = true
            )
        )
    }
}

// =============================================================================
// Output types
// =============================================================================

/**
 * Capability vector for a single package. Add fields here as new categories
 * of capability are discovered (share, deep link into chat, etc.).
 */
data class AppCapabilities(
    /** True if this package accepts android.intent.action.SEARCH with SearchManager.QUERY. */
    val supportsActionSearch: Boolean = false,
    /** Registered multi-step workflow id, or null if no workflow is hardcoded. */
    val orderWorkflowId: String? = null
) {
    companion object {
        val DEFAULT = AppCapabilities()
    }
}

/**
 * Outcome of routing. Either a concrete ExecutionPlan, or an explanation of
 * why routing failed. Callers pattern-match on this; no exceptions thrown.
 */
sealed class RoutingResult {

    data class Plan(
        val parsed: ParsedIntent,
        val packageId: String,
        val plan: ExecutionPlan
    ) : RoutingResult()

    data class Unresolvable(
        val reason: UnresolvableReason,
        val parsed: ParsedIntent
    ) : RoutingResult()
}

sealed class UnresolvableReason {

    data class AppNotInstalled(val appName: String) : UnresolvableReason()

    data object MissingQuery : UnresolvableReason()

    data class NoWorkflowRegistered(val packageId: String) : UnresolvableReason()
}

/**
 * Describes HOW a task will execute. Layer 3 (Execution) consumes this.
 * Sealed — a new plan shape requires a new executor, so we force exhaustive
 * handling in the execution layer.
 */
sealed class ExecutionPlan {

    data class OpenApp(
        val packageId: String
    ) : ExecutionPlan()

    data class NativeSearch(
        val packageId: String,
        val query: String
    ) : ExecutionPlan()

    data class UiSearch(
        val packageId: String,
        val query: String
    ) : ExecutionPlan()

    data class OrderWorkflow(
        val packageId: String,
        val workflowId: String,
        val query: String
    ) : ExecutionPlan()
}