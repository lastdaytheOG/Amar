package com.amar.vault.agent.runtime.adapters

import android.util.Log
import com.amar.vault.agent.runtime.injection.InjectionStrategy
import com.amar.vault.agent.runtime.injection.StrategyCascade
import com.amar.vault.agent.runtime.state.BoundsQuadrant
import com.amar.vault.agent.runtime.state.FocusFingerprint
import com.amar.vault.agent.runtime.state.SemanticIdentity

/**
 * A [FrameworkAdapter] driven by declarative configuration rather than
 * code. Lets external packages contribute per-app rules without shipping
 * a JAR that the agent has to load via reflection.
 *
 * # Schema (JSON-like, see [AdapterManifest])
 * {
 *   "packageIds": ["com.example.foo"],
 *   "classifyRules": [
 *     {
 *       "matchResourceIdContains": "search_input",
 *       "matchContentDescContains": null,
 *       "matchHintContains": null,
 *       "identity": "SearchInput"
 *     },
 *     ...
 *   ],
 *   "strategyOverride": ["CLIPBOARD_PASTE", "ACTION_SET_TEXT"],
 *   "perStrategyDelayMs": { "CLIPBOARD_PASTE": 100 },
 *   "shouldClearBeforeInject": false
 * }
 *
 * # Why declarative
 * 80% of real adapters look like "match this resource_id pattern, classify
 * as that role, use these strategies." That's data, not behavior. Pulling
 * it out into a manifest lets it ship in a string resource, a JSON file,
 * or even a Play Store update without rebuilding the agent.
 *
 * # When NOT to use this
 * Adapters needing custom Kotlin logic (Compose tree-walking, Flutter
 * shadow-node detection, app-specific gesture sequences) must remain
 * code-based and live in their own files in this package.
 *
 * # Resolution priority
 * DeclarativeAdapter has the same priority as a code-based adapter via
 * [FrameworkAdapterRegistry]. If a code-based adapter ALSO matches a
 * package, the code-based one wins. Declarative covers the long tail.
 */
class DeclarativeAdapter(
    private val manifest: AdapterManifest,
    private val cascade: StrategyCascade
) : FrameworkAdapter {

    override val packageIds: Set<String> = manifest.packageIds.toSet()

    override val adapterName: String =
        manifest.name ?: "DeclarativeAdapter(${manifest.packageIds.firstOrNull() ?: "?"})"

    override fun classifyFocusedNode(
        packageId: String,
        resourceId: String?,
        contentDesc: String?,
        hint: String?,
        className: String?,
        isEditable: Boolean,
        boundsQuadrant: BoundsQuadrant?,
        generationId: Long
    ): SemanticIdentity? {
        if (!isEditable) return null

        for (rule in manifest.classifyRules) {
            val ridOk = rule.matchResourceIdContains?.let {
                resourceId?.contains(it, ignoreCase = true) == true
            } ?: true
            val descOk = rule.matchContentDescContains?.let {
                contentDesc?.contains(it, ignoreCase = true) == true
            } ?: true
            val hintOk = rule.matchHintContains?.let {
                hint?.contains(it, ignoreCase = true) == true
            } ?: true
            val classOk = rule.matchClassNameContains?.let {
                className?.contains(it, ignoreCase = true) == true
            } ?: true

            if (ridOk && descOk && hintOk && classOk) {
                val fp = FocusFingerprint(
                    resourceId = resourceId,
                    className = className,
                    contentDesc = contentDesc,
                    textHash = hint?.hashCode(),
                    boundsQuadrant = boundsQuadrant,
                    isEditable = true
                )
                val identity = when (rule.identity) {
                    "SearchInput" -> SemanticIdentity.SearchInput(
                        packageId = packageId,
                        generationId = generationId,
                        fingerprint = fp
                    )
                    else -> {
                        // Other identity types map identically; keep this as
                        // a single SearchInput entry today, expand when more
                        // identity classes (MessageComposer, FormField, etc.)
                        // are added to SemanticIdentity.
                        Log.w(TAG, "Unknown identity type '${rule.identity}'; " +
                                "treating as SearchInput")
                        SemanticIdentity.SearchInput(
                            packageId = packageId,
                            generationId = generationId,
                            fingerprint = fp
                        )
                    }
                }
                Log.d(TAG, "DECL_CLASSIFY pkg=$packageId rid=$resourceId " +
                        "→ ${identity::class.simpleName}")
                return identity
            }
        }
        return null
    }

    override fun overrideStrategies(identity: SemanticIdentity): List<InjectionStrategy>? {
        val names = manifest.strategyOverride ?: return null
        val all = cascade.strategies()
        return names.mapNotNull { wanted ->
            all.firstOrNull { it.name == wanted }
        }
    }

    override fun perStrategyDelayMs(
        strategy: InjectionStrategy,
        attemptIndex: Int
    ): Long {
        return manifest.perStrategyDelayMs[strategy.name] ?: 0L
    }

    override fun shouldClearBeforeInject(
        identity: SemanticIdentity,
        attemptIndex: Int
    ): Boolean {
        return manifest.shouldClearBeforeInject
    }

    companion object {
        private const val TAG = "DeclAdapter"
    }
}

/**
 * Declarative manifest format. Mirrors a JSON object 1:1 so external
 * configs can be parsed straight into this with kotlinx.serialization or
 * Gson if needed.
 */
data class AdapterManifest(
    val packageIds: List<String>,
    val classifyRules: List<ClassifyRule> = emptyList(),
    val strategyOverride: List<String>? = null,
    val perStrategyDelayMs: Map<String, Long> = emptyMap(),
    val shouldClearBeforeInject: Boolean = false,
    val name: String? = null
) {
    data class ClassifyRule(
        val matchResourceIdContains: String? = null,
        val matchContentDescContains: String? = null,
        val matchHintContains: String? = null,
        val matchClassNameContains: String? = null,
        val identity: String = "SearchInput"
    )
}