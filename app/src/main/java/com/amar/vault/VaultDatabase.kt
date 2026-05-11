package com.amar.vault

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vault_items")
data class VaultItem(
    @PrimaryKey val id: String,
    val uri: String,
    val ocrText: String,
    val lang: String,

    val itemType: String,
    val pageNum: Int = 0,
    val sourceFile: String = "",
    val timestamp: Long,
    val pHash: Long = 0L,
    val tags: String = "",
    val contentHash: String = ""
)

@Fts4(contentEntity = VaultItem::class)
@Entity(tableName = "vault_fts")
data class VaultItemFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Int,
    val ocrText: String
)

data class VaultItemSearchData(
    val id: String,
    val ocrText: String,
    val tags: String,
    val itemType: String
)

@Dao
interface VaultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: VaultItem)
    @Query("SELECT * FROM vault_items WHERE contentHash = :hash LIMIT 1")
    suspend fun findByContentHash(hash: String): VaultItem?

    @Query("""
        SELECT vault_items.* FROM vault_items
        JOIN vault_fts ON vault_items.rowid = vault_fts.rowid
        WHERE vault_fts MATCH :query
        LIMIT 50
    """)
    fun searchFts(query: String): Flow<List<VaultItem>>

    @Query("SELECT * FROM vault_items WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<VaultItem>
    @Query("DELETE FROM vault_items")
    suspend fun deleteAll()

    @Query("SELECT id, ocrText, tags, itemType FROM vault_items")
    suspend fun getAllSearchableData(): List<VaultItemSearchData>

    @Query("SELECT * FROM vault_items ORDER BY timestamp DESC")
    fun getAllItems(): Flow<List<VaultItem>>

    @Query("SELECT COUNT(*) FROM vault_items")
    suspend fun getCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM vault_items WHERE pHash = :hash LIMIT 1)")
    suspend fun hashExists(hash: Long): Boolean

    @Query("SELECT * FROM vault_items WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getItemsSince(since: Long): List<VaultItem>

    @Query("SELECT * FROM vault_items ORDER BY timestamp DESC")
    suspend fun getAll(): List<VaultItem>

    @Query("SELECT uri FROM vault_items")
    suspend fun getAllUris(): List<String>

    @Delete
    suspend fun delete(item: VaultItem)
}

@Database(
    entities = [VaultItem::class, VaultItemFts::class],
    version = 2,
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile
        private var INSTANCE: VaultDatabase? = null

        fun get(context: Context): VaultDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "vault.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}