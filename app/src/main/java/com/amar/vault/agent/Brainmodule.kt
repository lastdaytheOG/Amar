package com.amar.vault.agent.brain

import android.content.Context
import com.amar.vault.agent.AgentScope
import com.amar.vault.agent.brain.download.ModelDownloader
import com.amar.vault.agent.brain.engine.Backend
import com.amar.vault.agent.brain.engine.InferenceEngine
import com.amar.vault.agent.brain.engine.LiteRtEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * Hilt wiring for the Primary Brain subsystem.
 *
 * Updated: LiteRtEngine now takes Context for cacheDir and nativeLibraryDir lookup.
 */
@Module
@InstallIn(SingletonComponent::class)
object BrainModule {

    @Provides
    @Singleton
    fun provideDeviceCapability(
        @ApplicationContext context: Context
    ): DeviceCapability = DeviceCapability(context)

    @Provides
    @Singleton
    fun provideModelSpec(
        capability: DeviceCapability
    ): ModelSpec = capability.recommendedModel()

    @Provides
    @Singleton
    fun provideModelDownloader(
        @ApplicationContext context: Context
    ): ModelDownloader = ModelDownloader(context)

    /**
     * Real LiteRT-LM engine. Takes Context for cache + native lib directories.
     * Swap to FakeInferenceEngine in a TestBrainModule when needed for tests.
     */
    @Provides
    @Singleton
    fun provideInferenceEngine(
        @ApplicationContext context: Context
    ): InferenceEngine = LiteRtEngine(context)

    @Provides
    @Singleton
    fun providePrimaryBrain(
        spec: ModelSpec,
        engine: InferenceEngine,
        downloader: ModelDownloader,
        @AgentScope scope: CoroutineScope
    ): PrimaryBrain = LiteRtBrain(
        modelSpec = spec,
        engine = engine,
        downloader = downloader,
        preferredBackend = Backend.GPU,
        scope = scope
    )
}