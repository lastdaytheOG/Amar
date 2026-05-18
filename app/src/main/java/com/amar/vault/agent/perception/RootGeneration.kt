package com.amar.vault.agent.perception

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic counter incremented on every accessibility-root mutation.
 *
 * Nodes captured during snapshot generation N are valid ONLY while
 * RootGeneration.current() == N. Any node held across a generation
 * increment must be reacquired before use, or the action will target
 * a stale view (causing the "click was mechanical no-op" pattern).
 *
 * Increments happen at the start of each PerceptionService.walkAndPublish.
 * Readers check via [matches]:
 *
 *     val gen = RootGeneration.current()
 *     val node = resolve(target)
 *     // ... time passes ...
 *     if (!RootGeneration.matches(gen)) reacquire()
 */
object RootGeneration {

    private val counter = AtomicLong(0L)

    fun current(): Long = counter.get()

    fun increment(): Long = counter.incrementAndGet()

    fun matches(generationId: Long): Boolean = counter.get() == generationId
}