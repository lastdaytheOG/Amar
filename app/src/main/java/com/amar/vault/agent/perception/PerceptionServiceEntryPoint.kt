package com.amar.vault.agent.perception

import com.amar.vault.agent.runtime.events.AccessibilityEventBus
import com.amar.vault.agent.runtime.ime.ImeCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point for PerceptionService, which is constructed by Android
 * (not Hilt) and cannot use @Inject directly.
 *
 * Used in onServiceConnected() to fetch the bus + IME coordinator from
 * the Hilt graph after the service is alive. This handles the case where
 * the service connects AFTER AmarApplication.onCreate() runs — common when
 * the user enables accessibility after launching the app.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PerceptionServiceEntryPoint {
    fun accessibilityEventBus(): AccessibilityEventBus
    fun imeCoordinator(): ImeCoordinator
}