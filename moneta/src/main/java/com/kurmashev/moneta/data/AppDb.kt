package com.kurmashev.moneta.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MonetaDao {
    // ---- Счета ----
    @Query("SELECT * FROM accounts WHERE deleted = 0 ORDER BY sort, name")
    fun accounts(): Flow<List<AccountEntity>>

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun accountCount(): Int

    @Upsert
    suspend fun upsertAccount(a: AccountEntity)

    // ---- Категории ----
    @Query("SELECT * FROM categories WHERE deleted = 0 ORDER BY sort, name")
    fun categories(): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun categoryCount(): Int

    @Upsert
    suspend fun upsertCategories(c: List<CategoryEntity>)

    @Upsert
    suspend fun upsertCategory(c: CategoryEntity)

    // ---- Операции ----
    @Query("SELECT * FROM operations WHERE deleted = 0 AND date >= :from AND date < :to ORDER BY date DESC")
    fun operations(from: Long, to: Long): Flow<List<OperationEntity>>

    @Query("SELECT * FROM operations WHERE deleted = 0 ORDER BY date DESC, updatedAt DESC")
    fun allOperations(): Flow<List<OperationEntity>>

    @Upsert
    suspend fun upsertOperation(o: OperationEntity)

    @Query("UPDATE operations SET deleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun deleteOperation(id: String, now: Long = System.currentTimeMillis())

    // ---- Переводы ----
    @Query("SELECT * FROM transfers WHERE deleted = 0 ORDER BY date DESC")
    fun transfers(): Flow<List<TransferEntity>>

    @Upsert
    suspend fun upsertTransfer(t: TransferEntity)
}

@Database(
    entities = [AccountEntity::class, CategoryEntity::class, OperationEntity::class, TransferEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): MonetaDao

    companion object {
        fun build(ctx: Context): AppDb =
            Room.databaseBuilder(ctx, AppDb::class.java, "moneta.db").build()
    }
}
