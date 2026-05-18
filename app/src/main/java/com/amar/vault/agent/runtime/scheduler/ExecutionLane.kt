package com.amar.vault.agent.runtime.scheduler

/**
 * Logical queue ("lane") an operation runs on. Each lane has independent
 * concurrency rules — operations on the same lane serialize; operations on
 * different lanes can run in parallel.
 *
 * Rationale:
 *   The single-threaded "everything-through-one-coroutine" pattern is too
 *   restrictive (perception walks block injection waits). A free-for-all
 *   thread pool is too loose (two executors fight for focus). Lanes split
 *   the difference: serialize what MUST be serialized, parallelize
 *   everything else.
 *
 * Concurrency contract:
 *   - NAVIGATION: one operation at a time. Clicks, gesture taps, opens.
 *     Two parallel clicks corrupt UI state.
 *   - INJECTION: one operation at a time. Text writes, paste. Sequence
 *     matters; parallel writes overwrite each other.
 *   - RECOVERY: one operation at a time. Rollback is global, not local.
 *   - PERCEPTION: one operation at a time. Tree walks are heavy and
 *     mutating cache mid-walk produces torn snapshots.
 *   - DIAGNOSTIC: unbounded parallel. Telemetry, metrics, heartbeats.
 *
 * Cross-lane rules (enforced by OwnershipManager):
 *   - INJECTION blocks while NAVIGATION runs (don't type while clicking).
 *   - RECOVERY preempts everything else.
 *   - PERCEPTION can run alongside NAVIGATION but pauses during INJECTION.
 */
enum class ExecutionLane {
    NAVIGATION,
    INJECTION,
    RECOVERY,
    PERCEPTION,
    DIAGNOSTIC;
}