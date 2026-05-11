package com.amar.vault.agent.control.persistence

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Append-only checkpoint log for task state transitions.
 *
 * Why append-only instead of mutable state rows:
 *   - Recovery is trivial: SELECT * WHERE task_id = ? ORDER BY seq DESC LIMIT 1
 *     tells us the last known state. No UPSERT races.
 *   - Free audit trail for Shadow Brain's failure analysis (later phase) —
 *     we see the whole story, not just the ending.
 *   - Idempotent writes: if process dies mid-write, replay on boot handles it.
 *
 * Retention:
 *   CheckpointWriter prunes old terminal tasks on a schedule. We keep ~7 days
 *   by default so the Shadow Brain has enough failure history to analyze.
 *
 * Important: this table does NOT store the full envelope on every row.
 * Envelope is written once on Pending and referenced by task_id elsewhere.
 */
@Entity(
    tableName = "agent_task_checkpoint",
    indices = [
        Index("task_id"),
        Index("timestamp"),
        Index("is_terminal")
    ]
)
data class TaskCheckpoint(
    @PrimaryKey(autoGenerate = true)
    val seq: Long = 0,

    @ColumnInfo(name = "task_id")
    val taskId: String,

    /** Name of the TaskState subclass, e.g. "Running", "Succeeded". */
    @ColumnInfo(name = "state_name")
    val stateName: String,

    /** Transition label for quick log scanning (e.g., "Running→Verifying"). */
    @ColumnInfo(name = "transition")
    val transition: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    /** 1-indexed attempt number at time of this checkpoint, -1 for N/A states. */
    @ColumnInfo(name = "attempt_number")
    val attemptNumber: Int,

    /** JSON blob holding state-specific fields (failure reason, result data, etc.). */
    @ColumnInfo(name = "state_payload_json")
    val statePayloadJson: String,

    /** True for Succeeded/Failed/Cancelled — speeds up "find active tasks" queries. */
    @ColumnInfo(name = "is_terminal")
    val isTerminal: Boolean
)

/**
 * Companion "envelope snapshot" row — written once when a task becomes Pending.
 * Separated from the checkpoint stream because envelopes can be bulky
 * (multi-KB messages, long target strings) and we don't want to duplicate them
 * across every state transition row.
 */
@Entity(
    tableName = "agent_task_envelope",
    indices = [Index("task_id", unique = true)]
)
data class TaskEnvelopeSnapshot(
    @PrimaryKey
    @ColumnInfo(name = "task_id")
    val taskId: String,

    /** Canonical wire JSON (nested-params form). */
    @ColumnInfo(name = "envelope_json")
    val envelopeJson: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

@Dao
interface TaskCheckpointDao {

    // --- Writes ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckpoint(checkpoint: TaskCheckpoint): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEnvelope(envelope: TaskEnvelopeSnapshot)

    // --- Recovery queries ---

    @Query("""
        SELECT * FROM agent_task_checkpoint
        WHERE task_id = :taskId
        ORDER BY seq DESC
        LIMIT 1
    """)
    suspend fun lastCheckpointFor(taskId: String): TaskCheckpoint?

    @Query("""
        SELECT * FROM agent_task_envelope WHERE task_id = :taskId LIMIT 1
    """)
    suspend fun envelopeFor(taskId: String): TaskEnvelopeSnapshot?

    /**
     * Returns task_ids of tasks that have a checkpoint but no terminal checkpoint.
     * Used on app boot to resume/abort orphaned tasks.
     */
    @Query("""
        SELECT DISTINCT task_id FROM agent_task_checkpoint
        WHERE task_id NOT IN (
            SELECT task_id FROM agent_task_checkpoint WHERE is_terminal = 1
        )
    """)
    suspend fun orphanedTaskIds(): List<String>

    // --- Telemetry / pruning ---

    @Query("""
        SELECT * FROM agent_task_checkpoint
        WHERE is_terminal = 1 AND timestamp < :cutoffMs
        ORDER BY timestamp ASC
    """)
    suspend fun terminalCheckpointsBefore(cutoffMs: Long): List<TaskCheckpoint>

    @Query("DELETE FROM agent_task_checkpoint WHERE timestamp < :cutoffMs AND is_terminal = 1")
    suspend fun pruneTerminalBefore(cutoffMs: Long): Int

    @Query("""
        DELETE FROM agent_task_envelope
        WHERE task_id IN (
            SELECT task_id FROM agent_task_checkpoint
            WHERE is_terminal = 1 AND timestamp < :cutoffMs
        )
    """)
    suspend fun pruneEnvelopesBefore(cutoffMs: Long): Int

    @Query("SELECT COUNT(*) FROM agent_task_checkpoint")
    suspend fun totalCheckpoints(): Int
}