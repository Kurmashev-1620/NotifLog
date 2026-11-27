package com.kurmashev.moneta.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

    // ---- Разовое чтение (импорт, пересчёт) ----
    @Query("SELECT * FROM accounts WHERE deleted = 0")
    suspend fun accountsOnce(): List<AccountEntity>

    @Query("SELECT * FROM categories WHERE deleted = 0")
    suspend fun categoriesOnce(): List<CategoryEntity>

    @Query("SELECT * FROM operations WHERE deleted = 0")
    suspend fun operationsOnce(): List<OperationEntity>

    @Query("SELECT * FROM transfers WHERE deleted = 0")
    suspend fun transfersOnce(): List<TransferEntity>

    @Query("SELECT importKey FROM operations WHERE importKey IS NOT NULL")
    suspend fun operationImportKeys(): List<String>

    @Query("SELECT importKey FROM transfers WHERE importKey IS NOT NULL")
    suspend fun transferImportKeys(): List<String>

    @Upsert
    suspend fun upsertOperations(list: List<OperationEntity>)

    @Upsert
    suspend fun upsertTransfers(list: List<TransferEntity>)

    @Upsert
    suspend fun upsertAccounts(list: List<AccountEntity>)

    // ---- Курсы ----
    @Upsert
    suspend fun upsertRates(list: List<RateEntity>)

    @Query("SELECT COUNT(*) FROM rates WHERE date = :d")
    suspend fun rateCountOn(d: String): Int

    @Query("SELECT * FROM rates WHERE currency = :c AND date <= :d ORDER BY date DESC LIMIT 1")
    suspend fun rateOnOrBefore(d: String, c: String): RateEntity?

    @Query("SELECT * FROM rates WHERE currency = :c ORDER BY date DESC LIMIT 1")
    suspend fun latestRate(c: String): RateEntity?

    @Query("SELECT * FROM rates WHERE date = (SELECT MAX(date) FROM rates)")
    fun latestRates(): Flow<List<RateEntity>>
}

/** v1 → v2: курсы валют, импорт, ручной курс */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE operations ADD COLUMN rateManual INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE operations ADD COLUMN importKey TEXT")
        db.execSQL("ALTER TABLE transfers ADD COLUMN importKey TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `rates` (`date` TEXT NOT NULL, `currency` TEXT NOT NULL, " +
                "`perUsd` REAL NOT NULL, PRIMARY KEY(`date`, `currency`))"
        )
    }
}

@Database(
    entities = [AccountEntity::class, CategoryEntity::class, OperationEntity::class, TransferEntity::class, RateEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): MonetaDao

    companion object {
        fun build(ctx: Context): AppDb =
            Room.databaseBuilder(ctx, AppDb::class.java, "moneta.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
