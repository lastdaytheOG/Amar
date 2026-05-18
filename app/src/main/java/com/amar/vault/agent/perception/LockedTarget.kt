package com.amar.vault.agent.perception

import android.graphics.Rect

/**
 * Represents a persistent semantic target lock to prevent target drift.
 */
data class LockedTarget(
    val semanticRole: String,
    val packageName: String,
    val bounds: Rect,
    val sessionId: String = java.util.UUID.randomUUID().toString()
)
