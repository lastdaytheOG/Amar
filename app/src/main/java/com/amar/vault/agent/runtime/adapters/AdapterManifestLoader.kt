package com.amar.vault.agent.runtime.adapters

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.amar.vault.agent.runtime.injection.StrategyCascade
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discovers and loads declarative adapter manifests at startup.
 *
 * # Discovery mechanism
 * External APKs declare adapter manifests as `<meta-data>` entries on their
 * Application tag with `android:name="com.amar.vault.ADAPTER_MANIFEST"`
 * and `android:resource` pointing to a JSON-string resource.
 *
 * # AndroidManifest entry (external apk):
 *   <meta-data
 *       android:name="com.amar.vault.ADAPTER_MANIFEST"
 *       android:resource="@string/amar_adapter_manifest" />
 *
 * # Manifest format (string resource):
 *   <string name="amar_adapter_manifest">
 *     {
 *       "packageIds": ["com.example.foo"],
 *       "classifyRules": [...]
 *     }
 *   </string>
 *
 * # Discovery process
 *   1. Enumerate all packages with the GET_META_DATA flag.
 *   2. Filter to those that declare the magic meta-data name.
 *   3. For each, load the referenced string resource cross-process.
 *   4. Parse as JSON, build AdapterManifest, wrap in DeclarativeAdapter.
 *
 * # Built-in manifests
 * Beyond external discovery, the agent itself can include built-in
 * manifests in its own resources for app-specific adapters that don't
 * warrant a full Kotlin file. Loaded via [loadBuiltInManifest].
 *
 * # Result
 * Returns a List<FrameworkAdapter>. The caller wires these into Hilt's
 * @IntoSet binding via Agentmodule.kt's multibinding provider.
 *
 * Today, AgentModule provides the static list of code-based adapters and
 * this loader's output is logged but NOT auto-merged into the Hilt set —
 * that requires either a Hilt @Provides function that calls this loader
 * at app start, or runtime registration into FrameworkAdapterRegistry.
 * Step 16 ships the loader and the format; Hilt integration is the next
 * smaller patch.
 */
@Singleton
class AdapterManifestLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cascade: StrategyCascade
) {

    /**
     * Discover all declarative adapters across installed packages.
     */
    fun discover(): List<FrameworkAdapter> {
        val out = mutableListOf<FrameworkAdapter>()

        // Built-in manifests (shipped in agent's own assets).
        out += loadBuiltInManifests()

        // External package scan.
        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
            for (pkg in packages) {
                val metadata = pkg.applicationInfo?.metaData ?: continue
                val resId = metadata.getInt(META_DATA_KEY, 0)
                if (resId == 0) continue

                try {
                    val foreignCtx = context.createPackageContext(pkg.packageName, 0)
                    val json = foreignCtx.resources.getString(resId)
                    val manifest = parseManifest(json) ?: continue
                    val adapter = DeclarativeAdapter(manifest, cascade)
                    out += adapter
                    Log.i(TAG, "LOADED_EXTERNAL pkg=${pkg.packageName} " +
                            "appliesTo=${manifest.packageIds.joinToString(",")}")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to load manifest from ${pkg.packageName}: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "External package scan failed: ${t.message}")
        }

        Log.i(TAG, "DISCOVERY_DONE adapters=${out.size}")
        return out
    }

    /**
     * Load manifests bundled in the agent's own assets/adapters/ folder.
     * Each *.json file in that folder becomes one DeclarativeAdapter.
     *
     * Today there are no built-in manifests, but this is where you'd
     * drop new app configs without writing Kotlin. Example to add later:
     *   assets/adapters/spotify.json
     *   assets/adapters/youtube_music.json
     */
    private fun loadBuiltInManifests(): List<FrameworkAdapter> {
        val out = mutableListOf<FrameworkAdapter>()
        try {
            val assets = context.assets
            val files = assets.list("adapters") ?: return emptyList()
            for (file in files) {
                if (!file.endsWith(".json")) continue
                try {
                    val json = assets.open("adapters/$file").bufferedReader().use { it.readText() }
                    val manifest = parseManifest(json) ?: continue
                    out += DeclarativeAdapter(manifest, cascade)
                    Log.i(TAG, "LOADED_BUILTIN file=adapters/$file " +
                            "appliesTo=${manifest.packageIds.joinToString(",")}")
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to load built-in adapter $file: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "No built-in adapters folder (this is fine)")
        }
        return out
    }

    /**
     * Parse a JSON string into an AdapterManifest. Returns null on parse
     * errors so callers can skip and continue with other manifests.
     */
    fun parseManifest(json: String): AdapterManifest? {
        return try {
            val obj = JSONObject(json)
            val packageIds = obj.getJSONArray("packageIds").let { arr ->
                List(arr.length()) { arr.getString(it) }
            }
            val classifyRules = obj.optJSONArray("classifyRules")?.let { arr ->
                List(arr.length()) { i ->
                    val r = arr.getJSONObject(i)
                    AdapterManifest.ClassifyRule(
                        matchResourceIdContains = r.optString("matchResourceIdContains").takeIf { it.isNotEmpty() },
                        matchContentDescContains = r.optString("matchContentDescContains").takeIf { it.isNotEmpty() },
                        matchHintContains = r.optString("matchHintContains").takeIf { it.isNotEmpty() },
                        matchClassNameContains = r.optString("matchClassNameContains").takeIf { it.isNotEmpty() },
                        identity = r.optString("identity", "SearchInput")
                    )
                }
            } ?: emptyList()
            val strategyOverride = obj.optJSONArray("strategyOverride")?.let { arr ->
                List(arr.length()) { arr.getString(it) }
            }
            val perStrategyDelayMs = obj.optJSONObject("perStrategyDelayMs")?.let { delays ->
                val map = mutableMapOf<String, Long>()
                val keys = delays.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = delays.getLong(k)
                }
                map
            } ?: emptyMap()
            val shouldClearBeforeInject = obj.optBoolean("shouldClearBeforeInject", false)
            val name = obj.optString("name").takeIf { it.isNotEmpty() }

            AdapterManifest(
                packageIds = packageIds,
                classifyRules = classifyRules,
                strategyOverride = strategyOverride,
                perStrategyDelayMs = perStrategyDelayMs,
                shouldClearBeforeInject = shouldClearBeforeInject,
                name = name
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Manifest parse failed: ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "AdapterLoader"
        private const val META_DATA_KEY = "com.amar.vault.ADAPTER_MANIFEST"
    }
}