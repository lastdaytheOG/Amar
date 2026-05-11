package com.amar.vault.agent.control.persistence

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

/**
 * Room database for agent checkpoints.
 *
 * Kept SEPARATE from the existing Amar Vault database (vault_db or similar) for
 * two concrete reasons:
 *   1. Independent migration cadence. Agent schema will evolve rapidly in the
 *      next few phases (episodic memory, action history, etc.). Coupling it to
 *      vault_db migrations means any agent schema change forces a vault_db
 *      migration path — painful and risky for already-shipped users.
 *   2. Clear failure isolation. If the agent DB gets corrupted by an
 *      ill-behaved migration or an OOM during checkpoint write, vault_db stays
 *      pristine. OCR/document indices keep working even with a broken agent.
 *
 * If you later decide to unify into a single DB, the migration is straightforward:
 * add these entities to the existing AppDatabase and drop this file. No code
 * outside the persistence package references AgentDatabase directly — it's
 * injected via Hilt as a type.
 *
 * Destructive migration:
 *   fallbackToDestructiveMigration() is enabled for v1. Checkpoint data is
 *   telemetry / audit — losing it on upgrade is acceptable. Remove this flag
 *   and add real migrations once checkpoints are used for anything the user
 *   would notice missing (e.g., resumable task replay across app versions).
 */
@Database(
    entities = [
        TaskCheckpoint::class,
        TaskEnvelopeSnapshot::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AgentDatabase : RoomDatabase() {

    abstract fun taskCheckpointDao(): TaskCheckpointDao

    companion object {
        internal const val DB_NAME = "agent_db"

        fun build(context: Context): AgentDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AgentDatabase::class.java,
                DB_NAME
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}