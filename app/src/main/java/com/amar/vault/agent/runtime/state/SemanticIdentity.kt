package com.amar.vault.agent.runtime.state

/**
 * Framework-independent identity of a logical UI target.
 *
 * The point of SemanticIdentity is to abstract away raw AccessibilityNodeInfo
 * (which is short-lived, recycled, and framework-specific) into stable logical
 * names like "the search input on WhatsApp's chat list" that survive
 * recomposition, animation, and lazy inflation.
 *
 * Today (Step 2) this is a placeholder — we define the type shape so WorldState
 * can carry it, but the resolver that builds these from raw nodes lives in
 * Step 7 (Semantic Identity System). Until then, SemanticIdentity instances
 * are constructed by hand for testing or left null.
 *
 * Why sealed:
 *   Each semantic role has different fields. SearchInput needs a fingerprint;
 *   PrimaryAction may need an action verb; ChatList may need a scroll position.
 *   Sealed enforces exhaustive when() handling in adapters and reducers.
 */
sealed class SemanticIdentity {

    /** Stable package this identity belongs to. */
    abstract val packageId: String

    /**
     * Generation counter from RootGeneration when this identity was minted.
     * Used by Step 7 to invalidate stale identities across tree rebuilds.
     */
    abstract val generationId: Long

    data class SearchInput(
        override val packageId: String,
        override val generationId: Long,
        val fingerprint: FocusFingerprint
    ) : SemanticIdentity()

    data class PrimaryAction(
        override val packageId: String,
        override val generationId: Long,
        val label: String,
        val fingerprint: FocusFingerprint
    ) : SemanticIdentity()

    data class ChatList(
        override val packageId: String,
        override val generationId: Long,
        val fingerprint: FocusFingerprint
    ) : SemanticIdentity()

    data class Unknown(
        override val packageId: String,
        override val generationId: Long,
        val hint: String
    ) : SemanticIdentity()
}