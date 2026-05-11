package com.amar.vault.agent.intent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Pre-built searchable index of installed launchable apps.
 *
 * Built once on first access (or via [refresh]) by enumerating
 * PackageManager.queryIntentActivities(MAIN/LAUNCHER). For each app we
 * pre-compute several normalized "variant" strings that the matcher
 * consults — exact, compact (whitespace-stripped), token-prefix
 * combinations, and curated alias maps for the common abbreviations
 * users actually say ("yt", "insta", "cod", "ff", etc.).
 *
 * This is a singleton on the application context — not Hilt-injected,
 * because IntentRouter is a static `object` and pulling in Hilt would
 * complicate the fast path. AppIndex.attach(context) is called once
 * at app startup; subsequent calls are no-ops.
 *
 * Thread safety:
 *   - The index itself is held in an AtomicReference.
 *   - [refresh] swaps the whole snapshot atomically.
 *   - Reads see a consistent view (the snapshot they grabbed); concurrent
 *     refresh produces a new snapshot but doesn't mutate the old one.
 *
 * Cache invalidation:
 *   The OS broadcasts PACKAGE_ADDED / PACKAGE_REMOVED but we don't
 *   subscribe yet. Calling refresh() periodically (e.g. on agent screen
 *   resume) is sufficient for v1. Future: register a BroadcastReceiver.
 */
object AppIndex {

    private const val TAG = "AppIndex"

    private val snapshotRef = AtomicReference<Snapshot?>(null)
    @Volatile private var appContext: Context? = null

    /**
     * Initialize with the application context. Idempotent. Lazy — does NOT
     * scan PackageManager until first read, to avoid blocking app startup.
     */
    fun attach(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /** Force a re-scan of installed apps. Call after PACKAGE_ADDED/REMOVED. */
    fun refresh() {
        val ctx = appContext ?: return
        snapshotRef.set(buildSnapshot(ctx))
    }

    /**
     * Lookup an entry by exact normalized variant. Returns null if no app
     * advertises this variant. Used by IntentRouter for the fast path.
     */
    fun lookupExact(normalizedQuery: String): IndexedApp? {
        val snap = ensureBuilt() ?: return null
        return snap.byVariant[normalizedQuery]
    }

    /**
     * Get all indexed apps. Used by IntentRouter's fuzzy fallback tiers.
     */
    fun all(): List<IndexedApp> {
        val snap = ensureBuilt() ?: return emptyList()
        return snap.apps
    }

    private fun ensureBuilt(): Snapshot? {
        snapshotRef.get()?.let { return it }
        val ctx = appContext ?: return null
        val built = buildSnapshot(ctx)
        // CompareAndSet so concurrent first-access doesn't double-build.
        snapshotRef.compareAndSet(null, built)
        return snapshotRef.get()
    }

    private fun buildSnapshot(context: Context): Snapshot {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(launcher, 0)

        val apps = mutableListOf<IndexedApp>()
        val byVariant = HashMap<String, IndexedApp>()

        for (ri in resolved) {
            val labelRaw = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull() ?: continue
            val pkg = ri.activityInfo?.packageName ?: continue
            if (apps.any { it.packageName == pkg }) continue   // dedupe by pkg

            val normalized = normalize(labelRaw)
            val tokens = tokenize(labelRaw)
            val variants = buildVariants(labelRaw, normalized, tokens, pkg)

            val entry = IndexedApp(
                label = labelRaw,
                normalizedLabel = normalized,
                tokens = tokens,
                variants = variants,
                packageName = pkg
            )
            apps += entry
        }

        // PASS 1: claim variants where the variant equals the app's normalized
        // label. These are the strongest matches — "google" → app labeled
        // "Google", not "Google TV". Done before any other variant insertion
        // to override first-iteration-wins ambiguity from PackageManager order.
        for (entry in apps) {
            byVariant[entry.normalizedLabel] = entry
            // Compact form (no whitespace) of the label is also a strong claim.
            val compact = entry.normalizedLabel.replace(" ", "")
            if (compact != entry.normalizedLabel) {
                byVariant.putIfAbsent(compact, entry)
            }
        }

        // PASS 2: claim all OTHER variants (tokens, bigrams, acronyms,
        // package-id last-segment, curated aliases). First-claim wins here —
        // a label-equal claim from pass 1 cannot be overwritten.
        for (entry in apps) {
            for (v in entry.variants) {
                if (v == entry.normalizedLabel) continue
                if (v == entry.normalizedLabel.replace(" ", "")) continue
                byVariant.putIfAbsent(v, entry)
            }
        }

        Log.i(TAG, "AppIndex built: ${apps.size} apps, ${byVariant.size} variants")
        return Snapshot(apps, byVariant)
    }

    // -------------------------------------------------------------------------
    // Variant generation
    // -------------------------------------------------------------------------

    /**
     * For a single installed app, produce the set of normalized strings that
     * should resolve to it. Includes:
     *   - normalized full label                ("call of duty mobile")
     *   - whitespace-stripped compact form     ("callofdutymobile")
     *   - each individual token                ("call", "duty", "mobile")
     *   - bigram pairs                         ("call of", "of duty", "duty mobile")
     *   - first-letter acronym for >= 2 tokens ("cod", "codm")
     *   - curated aliases by package/label     ("ff" for Free Fire, "yt" for YouTube)
     */
    private fun buildVariants(
        label: String,
        normalized: String,
        tokens: List<String>,
        packageName: String
    ): Set<String> {
        val out = LinkedHashSet<String>()

        // Tier A: exact normalized
        out += normalized

        // Tier B: compact (no whitespace)
        out += normalized.replace(" ", "")

        // Tier C: every individual token, if 3+ chars
        for (t in tokens) {
            if (t.length >= 3) out += t
        }

        // Tier D: bigram pairs (helps "call of duty" → "call of", "of duty")
        for (i in 0 until tokens.size - 1) {
            out += "${tokens[i]} ${tokens[i + 1]}"
        }

        // Tier E: drop common stopwords from the label and add the result
        val coreTokens = tokens.filter { it !in STOPWORDS }
        if (coreTokens.size >= 2 && coreTokens != tokens) {
            out += coreTokens.joinToString(" ")
            out += coreTokens.joinToString("")
        }

        // Tier F: acronyms (only if >=2 meaningful tokens)
        if (coreTokens.size >= 2) {
            val acronym = coreTokens.joinToString("") { it.first().toString() }
            if (acronym.length in 2..6) {
                out += acronym
            }
        }

        // Tier G: package-name based aliases (mostly useful for ambiguous labels)
        // Add the last segment of the package id if it's a real word
        val pkgLast = packageName.substringAfterLast('.')
        if (pkgLast.length in 3..15 && pkgLast.all { it.isLetter() }) {
            out += pkgLast.lowercase(Locale.ROOT)
        }

        // Tier H: curated aliases — overrides for the apps users actually
        // shorten in conversation. Lookup is by normalized label OR by
        // package id, so the alias survives label variations.
        CURATED_ALIASES[normalized]?.let { out += it }
        CURATED_ALIASES[packageName]?.let { out += it }

        return out
    }

    // -------------------------------------------------------------------------
    // Curated aliases — what real users actually type
    // -------------------------------------------------------------------------

    private val CURATED_ALIASES: Map<String, List<String>> = mapOf(
        // Common Indian / global app shorthand
        "whatsapp"                       to listOf("wa"),
        "com.whatsapp"                   to listOf("wa"),
        "youtube"                        to listOf("yt"),
        "com.google.android.youtube"     to listOf("yt"),
        "instagram"                      to listOf("insta", "ig"),
        "com.instagram.android"          to listOf("insta", "ig"),
        "facebook"                       to listOf("fb"),
        "com.facebook.katana"            to listOf("fb"),
        "twitter"                        to listOf("x"),
        "com.twitter.android"            to listOf("x"),
        "google chrome"                  to listOf("chrome", "browser"),
        "com.android.chrome"             to listOf("chrome", "browser"),

        // Games
        "garena free fire"               to listOf("ff", "freefire", "garena"),
        "free fire"                      to listOf("ff", "freefire"),
        "garena free fire max"           to listOf("ff", "freefire", "ffmax", "free fire max"),
        "free fire max"                  to listOf("ff", "ffmax", "freefiremax"),
        "call of duty mobile"            to listOf("cod", "codm", "call of duty"),
        "call of duty"                   to listOf("cod"),
        "battlegrounds mobile india"     to listOf("bgmi"),
        "pubg mobile"                    to listOf("pubg"),
        "subway surfers"                 to listOf("subway", "surfers"),
        "minecraft"                      to listOf("mc"),
        "clash of clans"                 to listOf("coc"),
        "clash royale"                   to listOf("cr"),

        // Indian payments / commerce / delivery
        "google pay"                     to listOf("gpay", "pay"),
        "phonepe"                        to listOf("pp"),
        "paytm"                          to listOf("ptm"),
        "blinkit"                        to listOf("blink"),
        "swiggy instamart"               to listOf("instamart"),
        "amazon shopping"                to listOf("amazon"),
        "flipkart"                       to listOf("fk"),
        "jiomart"                        to listOf("jio mart", "jio"),

        // Google suite
        "gmail"                          to listOf("mail"),
        "google maps"                    to listOf("maps"),
        "google photos"                  to listOf("photos"),
        "google calendar"                to listOf("calendar"),
        "google play store"              to listOf("playstore", "play store", "play"),
        "play store"                     to listOf("playstore", "play"),

        // System
        "phone"                          to listOf("dialer", "call"),
        "messages"                       to listOf("sms", "message"),
        "settings"                       to listOf("setting"),
        "files"                          to listOf("file manager", "filemanager"),
        "camera"                         to listOf("cam"),
    )

    private val STOPWORDS = setOf(
        "the", "a", "an", "of", "for", "and", "by", "to", "on", "in",
        "app", "free"  // "free" filtered cautiously — "free fire" still indexed via curated alias
    )

    private fun normalize(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tokenize(s: String): List<String> =
        normalize(s).split(' ').filter { it.isNotBlank() }

    // -------------------------------------------------------------------------
    // Snapshot
    // -------------------------------------------------------------------------

    private data class Snapshot(
        val apps: List<IndexedApp>,
        val byVariant: Map<String, IndexedApp>
    )
}

/**
 * One installed launchable app, with all the precomputed strings IntentRouter
 * needs for matching. Immutable after construction.
 */
data class IndexedApp(
    /** Original PackageManager label, untouched. */
    val label: String,
    /** Lowercased, punctuation-stripped, single-spaced. */
    val normalizedLabel: String,
    /** Tokens of normalizedLabel, no stopword filtering. */
    val tokens: List<String>,
    /** All searchable variants — primary key set for exact lookup. */
    val variants: Set<String>,
    val packageName: String
)