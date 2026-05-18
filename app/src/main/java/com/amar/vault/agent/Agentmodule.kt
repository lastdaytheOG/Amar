package com.amar.vault.agent

import android.content.Context
import com.amar.vault.agent.control.ControlLayer
import com.amar.vault.agent.control.ControlLayerLogger
import com.amar.vault.agent.control.ExecutorRegistry
import com.amar.vault.agent.control.TaskTerminalObserver
import com.amar.vault.agent.control.VerificationEngineApi
import com.amar.vault.agent.control.executors.ClickExecutor
import com.amar.vault.agent.control.executors.GestureTapExecutor
import com.amar.vault.agent.control.executors.HomeExecutor
import com.amar.vault.agent.control.executors.MakeCallExecutor
import com.amar.vault.agent.control.executors.OpenAppExecutor
import com.amar.vault.agent.control.executors.ReadScreenExecutor
import com.amar.vault.agent.control.executors.ScrollExecutor
import com.amar.vault.agent.control.executors.SearchAppExecutor
import com.amar.vault.agent.control.executors.SendMessageExecutor
import com.amar.vault.agent.control.executors.SendSmsExecutor
import com.amar.vault.agent.control.executors.SequenceExecutor
import com.amar.vault.agent.control.executors.TypeTextExecutor
import com.amar.vault.agent.control.executors.WaitExecutor
import com.amar.vault.agent.control.observers.WatchlistReleaser
import com.amar.vault.agent.control.persistence.AgentDatabase
import com.amar.vault.agent.control.persistence.CheckpointWriter
import com.amar.vault.agent.control.persistence.TaskCheckpointDao
import com.amar.vault.agent.perception.PackageWatchlist
import com.amar.vault.agent.perception.SnapshotCache
import com.amar.vault.agent.control.DefaultVerificationEngine

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideSnapshotCache(): SnapshotCache {
        if (!AgentStateHolder.isInitialized()) {
            AgentStateHolder.init(SnapshotCache(), PackageWatchlist())
        }
        return AgentStateHolder.snapshotCache
    }

    @Provides
    @Singleton
    fun providePackageWatchlist(snapshotCache: SnapshotCache): PackageWatchlist {
        return AgentStateHolder.watchlist
    }

    @Provides
    @Singleton
    @AgentScope
    fun provideAgentScope(): CoroutineScope = AgentScopeFactory.create()

    @Provides
    @Singleton
    fun provideAgentDatabase(@ApplicationContext context: Context): AgentDatabase =
        AgentDatabase.build(context)

    @Provides
    fun provideTaskCheckpointDao(db: AgentDatabase): TaskCheckpointDao =
        db.taskCheckpointDao()

    @Provides
    @Singleton
    fun provideCheckpointWriter(
        dao: TaskCheckpointDao,
        @AgentScope scope: CoroutineScope
    ): CheckpointWriter = CheckpointWriter(
        dao = dao,
        scope = scope
    )

    @Provides
    @Singleton
    fun provideVerificationEngine(
        @ApplicationContext context: Context,
        snapshotCache: SnapshotCache
    ): VerificationEngineApi = DefaultVerificationEngine(context, snapshotCache)

    @Provides
    @Singleton
    fun provideOpenAppExecutor(
        @ApplicationContext context: Context,
        watchlist: PackageWatchlist
    ): OpenAppExecutor = OpenAppExecutor(context, watchlist)

    @Provides
    @Singleton
    fun provideSearchAppExecutor(
        @ApplicationContext context: Context,
        watchlist: PackageWatchlist
    ): SearchAppExecutor = SearchAppExecutor(context, watchlist)

    @Provides
    @Singleton
    fun provideClickExecutor(snapshotCache: SnapshotCache): ClickExecutor =
        ClickExecutor(snapshotCache)

    @Provides
    @Singleton
    fun provideGestureTapExecutor(snapshotCache: SnapshotCache): GestureTapExecutor =
        GestureTapExecutor(snapshotCache)

    @Provides
    @Singleton
    fun provideTypeTextExecutor(snapshotCache: SnapshotCache): TypeTextExecutor =
        TypeTextExecutor(snapshotCache)

    @Provides
    @Singleton
    fun provideScrollExecutor(snapshotCache: SnapshotCache): ScrollExecutor =
        ScrollExecutor(snapshotCache)

    @Provides
    @Singleton
    fun provideWaitExecutor(): WaitExecutor = WaitExecutor()

    @Provides
    @Singleton
    fun provideHomeExecutor(): HomeExecutor = HomeExecutor()

    @Provides
    @Singleton
    fun provideReadScreenExecutor(): ReadScreenExecutor = ReadScreenExecutor()

    @Provides
    @Singleton
    fun provideSendSmsExecutor(@ApplicationContext context: Context): SendSmsExecutor =
        SendSmsExecutor(context)

    @Provides
    @Singleton
    fun provideMakeCallExecutor(@ApplicationContext context: Context): MakeCallExecutor =
        MakeCallExecutor(context)

    @Provides
    @Singleton
    fun provideSendMessageExecutor(
        @ApplicationContext context: Context,
        watchlist: PackageWatchlist
    ): SendMessageExecutor = SendMessageExecutor(context, watchlist)

    @Provides
    @Singleton
    fun provideSequenceExecutor(
        lazyControlLayer: dagger.Lazy<ControlLayer>
    ): SequenceExecutor = SequenceExecutor(lazyControlLayer)

    @Provides
    @Singleton
    fun provideSimpleIntentExtractor(
        router: com.amar.vault.agent.capability.CapabilityRouter
    ): com.amar.vault.agent.intent.SimpleIntentExtractor =
        com.amar.vault.agent.intent.SimpleIntentExtractor(router)

    @Provides
    @Singleton
    fun provideSelectionTracker(): com.amar.vault.agent.runtime.ime.SelectionTracker =
        com.amar.vault.agent.runtime.ime.SelectionTracker()

    @Provides
    @Singleton
    fun provideExecutorRegistry(
        openApp: OpenAppExecutor,
        searchApp: SearchAppExecutor,
        click: ClickExecutor,
        gestureTap: GestureTapExecutor,
        typeText: TypeTextExecutor,
        scroll: ScrollExecutor,
        wait: WaitExecutor,
        home: HomeExecutor,
        readScreen: ReadScreenExecutor,
        sendSms: SendSmsExecutor,
        makeCall: MakeCallExecutor,
        sendMessage: SendMessageExecutor,
        sequence: SequenceExecutor
    ): ExecutorRegistry = ExecutorRegistry().apply {
        register(openApp)
        register(searchApp)
        register(click)
        register(gestureTap)
        register(typeText)
        register(scroll)
        register(wait)
        register(home)
        register(readScreen)
        register(sendSms)
        register(makeCall)
        register(sendMessage)
        register(sequence)
    }

    @Provides
    @Singleton
    fun provideWatchlistReleaser(watchlist: PackageWatchlist): WatchlistReleaser =
        WatchlistReleaser(watchlist)

    @Provides
    @Singleton
    fun provideTerminalObservers(
        watchlistReleaser: WatchlistReleaser
    ): List<@JvmSuppressWildcards TaskTerminalObserver> = listOf(watchlistReleaser)

    @Provides
    @Singleton
    fun provideControlLayerLogger(): ControlLayerLogger = object : ControlLayerLogger {
        override fun info(msg: String) {
            android.util.Log.i("ControlLayer", msg)
        }

        override fun warn(msg: String) {
            android.util.Log.w("ControlLayer", msg)
        }

        override fun error(msg: String, t: Throwable?) {
            android.util.Log.e("ControlLayer", msg, t)
        }
    }

    @Provides
    @Singleton
    fun provideControlLayer(
        registry: ExecutorRegistry,
        checkpointWriter: CheckpointWriter,
        verificationEngine: VerificationEngineApi,
        @AgentScope scope: CoroutineScope,
        logger: ControlLayerLogger,
        observers: List<@JvmSuppressWildcards TaskTerminalObserver>
    ): ControlLayer = ControlLayer(
        registry = registry,
        checkpointWriter = checkpointWriter,
        verificationEngine = verificationEngine,
        scope = scope,
        logger = logger,
        terminalObservers = observers
    )

    // -------------------------------------------------------------------------
    // Step 9: framework adapters. Hilt multibinding collects every @IntoSet
    // FrameworkAdapter into a Set<FrameworkAdapter> the registry consumes.
    // -------------------------------------------------------------------------

    @Provides
    @Singleton
    @dagger.multibindings.IntoSet
    fun provideWhatsAppAdapter(): com.amar.vault.agent.runtime.adapters.FrameworkAdapter =
        com.amar.vault.agent.runtime.adapters.WhatsAppAdapter()

    @Provides
    @Singleton
    @dagger.multibindings.IntoSet
    fun provideChatGPTAdapter(): com.amar.vault.agent.runtime.adapters.FrameworkAdapter =
        com.amar.vault.agent.runtime.adapters.ChatGPTAdapter()

    @Provides
    @Singleton
    @dagger.multibindings.IntoSet
    fun provideComposeAdapter(
        cascade: com.amar.vault.agent.runtime.injection.StrategyCascade
    ): com.amar.vault.agent.runtime.adapters.FrameworkAdapter =
        com.amar.vault.agent.runtime.adapters.ComposeAdapter(cascade)

    @Provides
    @Singleton
    @dagger.multibindings.IntoSet
    fun provideFlutterAdapter(
        cascade: com.amar.vault.agent.runtime.injection.StrategyCascade
    ): com.amar.vault.agent.runtime.adapters.FrameworkAdapter =
        com.amar.vault.agent.runtime.adapters.FlutterAdapter(cascade)
}