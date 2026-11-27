package com.kurmashev.moneta.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/*
 * У каждой записи:
 *  - id (UUID) — одинаковый на телефоне, в Google Таблице и в резервной копии;
 *  - updatedAt — когда менялась (по нему решаем, какая версия новее при синхронизации);
 *  - deleted — удаление «мягкое», чтобы удаление тоже синхронизировалось.
 */

fun newId(): String = UUID.randomUUID().toString()

object OpType {
    const val EXPENSE = "EXPENSE"
    const val INCOME = "INCOME"
}

object Source {
    const val MANUAL = "manual"
    const val PUSH = "push"
    const val RECEIPT = "receipt"
    const val IMPORT = "import"
}

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val currency: String,
    val initialBalance: Double = 0.0,
    val icon: String = "wallet",
    val color: Long = 0xFF5CC8A0,
    val hidden: Boolean = false,      // не учитывать в «Итого»
    val sort: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false
)

@Entity(tableName = "categories", indices = [Index("type")])
data class CategoryEntity(
    @PrimaryKey val id: String = newId(),
    val name: String,
    val type: String,                 // OpType
    val icon: String,
    val color: Long,
    val parentId: String? = null,     // подкатегория
    val sort: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false
)

@Entity(tableName = "operations", indices = [Index("date"), Index("categoryId"), Index("accountId")])
data class OperationEntity(
    @PrimaryKey val id: String = newId(),
    val type: String,                 // OpType
    val amount: Double,               // в валюте счёта
    val currency: String,             // валюта счёта
    val amountBase: Double,           // в основной валюте (для итогов и диаграмм)
    val rate: Double = 1.0,           // курс валюты счёта к основной
    val accountId: String,
    val categoryId: String,
    val date: Long,
    val comment: String = "",
    val tags: String = "",
    val source: String = Source.MANUAL,
    val receiptId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val rateManual: Boolean = false,  // курс введён вручную или взят из импорта — не перезаписывать
    val importKey: String? = null     // ключ строки из импорта — чтобы не добавить её второй раз
)

@Entity(tableName = "transfers", indices = [Index("date")])
data class TransferEntity(
    @PrimaryKey val id: String = newId(),
    val fromAccountId: String,
    val toAccountId: String,
    val fromAmount: Double,
    val toAmount: Double,
    val date: Long,
    val comment: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    val importKey: String? = null
)

/** Курс валюты на день: сколько единиц валюты за 1 USD */
@Entity(tableName = "rates", primaryKeys = ["date", "currency"])
data class RateEntity(
    val date: String,     // yyyy-MM-dd
    val currency: String,
    val perUsd: Double
)
