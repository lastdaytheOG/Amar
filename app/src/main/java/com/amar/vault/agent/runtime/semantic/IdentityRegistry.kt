package com.amar.vault.agent.runtime.semantic

import com.amar.vault.agent.runtime.state.SemanticIdentity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory registry of semantic identities keyed by (packageId, role).
 *
 * Purpose:
 *   Identities are minted by [SemanticResolver] each time a relevant node is
 *   observed. The registry deduplicates: if WhatsApp's SearchInput identity
 *   was minted at generation 42 and is requested again at generation 47, we
 *   return the SAME logical identity (with updated generationId), so
 *   downstream consumers don't see a "new" identity for the same logical
 *   target.
 *
 * Key format:
 *   "$packageId:$roleName" — e.g. "com.whatsapp:SearchInput". Roles come from
 *   the SemanticIdentity sealed subclass simple name.
 *
 * Thread-safety:
 *   ConcurrentHashMap. Updates happen on the reducer's collector thread;
 *   reads happen on whatever thread an executor uses. CHM handles both safely
 *   without external locking.
 *
 * Lifecycle:
 *   Singleton. Entries persist for the life of the process. We don't evict
 *   on package change — when the user returns to WhatsApp, the cached
 *   identity is still valid because the fingerprint matches.
 *
 * What this is NOT:
 *   - Not a cache of AccessibilityNodeInfo. Those die immediately. The
 *     registry stores stable identity descriptors only.
 *   - Not authoritative for "is this identity currently on screen". That's
 *     answered by combining WorldState.focusedEditableIdentity with a
 *     fresh tree query.
 */
@Singleton
class IdentityRegistry @Inject constructor() {

    private val byKey = ConcurrentHashMap<String, SemanticIdentity>()

    /**
     * Look up an identity by its key. Null if never registered.
     */
    fun get(packageId: String, role: String): SemanticIdentity? =
        byKey[key(packageId, role)]

    /**
     * Register or update an identity. Returns the registered instance
     * (which may differ from the input if a previous registration is
     * being refreshed with a new generationId).
     */
    fun put(identity: SemanticIdentity): SemanticIdentity {
        val k = key(identity.packageId, identity::class.simpleName ?: "Unknown")
        byKey[k] = identity
        return identity
    }

    /**
     * All identities currently registered. For diagnostics.
     */
    fun all(): Map<String, SemanticIdentity> = byKey.toMap()

    /**
     * Clear identities for one package (used when package changes
     * dramatically — e.g. uninstall, force-stop detection).
     */
    fun clearPackage(packageId: String) {
        val toRemove = byKey.keys.filter { it.startsWith("$packageId:") }
        toRemove.forEach { byKey.remove(it) }
    }

    fun resetForTest() {
        byKey.clear()
    }

    private fun key(packageId: String, role: String): String = "$packageId:$role"
}