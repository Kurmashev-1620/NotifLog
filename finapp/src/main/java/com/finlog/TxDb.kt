package com.finlog

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Tx(
    val id: Long,
    val time: Long,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val amount: Double,
    val currency: String,
    val type: TxType,
    val balance: Double?,
    val merchant: String,
    val review: Boolean
)

/** База операций (finlog.db) — хранится только на телефоне */
class TxDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "finlog.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE tx(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL," +
                "pkg TEXT NOT NULL, app TEXT NOT NULL, title TEXT NOT NULL, text TEXT NOT NULL," +
                "amount REAL NOT NULL, currency TEXT NOT NULL, type TEXT NOT NULL," +
                "balance REAL, merchant TEXT NOT NULL, review INTEGER NOT NULL, nkey TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX tx_time ON tx(time)")
        // Решения пользователя по приложениям: 1 — финансовое, −1 — игнорировать
        db.execSQL("CREATE TABLE trust(pkg TEXT PRIMARY KEY, value INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    @Synchronized
    fun insert(time: Long, pkg: String, app: String, title: String, text: String, p: Parsed, key: String): Boolean {
        val db = writableDatabase
        // То же уведомление повторно (приложения часто его обновляют)
        val sameNotif = DatabaseUtils.queryNumEntries(
            db, "tx", "nkey=? AND text=? AND time>?",
            arrayOf(key, text, (time - 10 * 60_000L).toString())
        )
        // Та же операция пришла и SMS, и пушем банка — оставляем одну
        val sameTx = DatabaseUtils.queryNumEntries(
            db, "tx", "amount=? AND currency=? AND type=? AND time>? AND time<?",
            arrayOf(p.amount.toString(), p.currency, p.type.name,
                (time - 3 * 60_000L).toString(), (time + 3 * 60_000L).toString())
        )
        if (sameNotif > 0 || sameTx > 0) return false

        val cv = ContentValues().apply {
            put("time", time); put("pkg", pkg); put("app", app); put("title", title); put("text", text)
            put("amount", p.amount); put("currency", p.currency); put("type", p.type.name)
            if (p.balance != null) put("balance", p.balance) else putNull("balance")
            put("merchant", p.merchant); put("review", if (p.confident) 0 else 1); put("nkey", key)
        }
        return db.insert("tx", null, cv) != -1L
    }

    fun addManual(amount: Double, currency: String, type: TxType, note: String) {
        val cv = ContentValues().apply {
            put("time", System.currentTimeMillis()); put("pkg", "manual"); put("app", "Вручную")
            put("title", note); put("text", note); put("amount", amount); put("currency", currency)
            put("type", type.name); putNull("balance"); put("merchant", note.ifBlank { "Вручную" })
            put("review", 0); put("nkey", "manual")
        }
        writableDatabase.insert("tx", null, cv)
    }

    fun list(review: Boolean, search: String = "", limit: Int = 3000): List<Tx> {
        var sel = "review=?"
        val args = arrayListOf(if (review) "1" else "0")
        if (search.isNotBlank()) {
            sel += " AND (merchant LIKE ? OR app LIKE ? OR text LIKE ?)"
            val s = "%${search.trim()}%"
            args.addAll(listOf(s, s, s))
        }
        return read(readableDatabase.query("tx", null, sel, args.toTypedArray(), null, null,
            "time DESC, id DESC", limit.toString()))
    }

    fun all(): List<Tx> =
        read(readableDatabase.query("tx", null, "review=0", null, null, null, "time ASC"))

    private fun read(c: Cursor): List<Tx> {
        val out = ArrayList<Tx>()
        c.use {
            val bi = it.getColumnIndexOrThrow("balance")
            while (it.moveToNext()) {
                out.add(
                    Tx(
                        it.getLong(it.getColumnIndexOrThrow("id")),
                        it.getLong(it.getColumnIndexOrThrow("time")),
                        it.getString(it.getColumnIndexOrThrow("pkg")),
                        it.getString(it.getColumnIndexOrThrow("app")),
                        it.getString(it.getColumnIndexOrThrow("title")),
                        it.getString(it.getColumnIndexOrThrow("text")),
                        it.getDouble(it.getColumnIndexOrThrow("amount")),
                        it.getString(it.getColumnIndexOrThrow("currency")),
                        TxType.valueOf(it.getString(it.getColumnIndexOrThrow("type"))),
                        if (it.isNull(bi)) null else it.getDouble(bi),
                        it.getString(it.getColumnIndexOrThrow("merchant")),
                        it.getInt(it.getColumnIndexOrThrow("review")) == 1
                    )
                )
            }
        }
        return out
    }

    fun reviewCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "tx", "review=1")

    /** Подтвердить операцию и задать её тип */
    fun confirm(id: Long, type: TxType) {
        val cv = ContentValues().apply { put("type", type.name); put("review", 0) }
        writableDatabase.update("tx", cv, "id=?", arrayOf(id.toString()))
    }

    fun setAmount(id: Long, amount: Double) {
        val cv = ContentValues().apply { put("amount", amount) }
        writableDatabase.update("tx", cv, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) = writableDatabase.delete("tx", "id=?", arrayOf(id.toString()))

    fun deleteByPkg(pkg: String) = writableDatabase.delete("tx", "pkg=?", arrayOf(pkg))

    /** Итоги за период: валюта → (расход, доход) */
    fun summary(from: Long): Map<String, Pair<Double, Double>> {
        val res = LinkedHashMap<String, Pair<Double, Double>>()
        readableDatabase.rawQuery(
            "SELECT currency, " +
                "SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END), " +
                "SUM(CASE WHEN type='INCOME' THEN amount ELSE 0 END) " +
                "FROM tx WHERE review=0 AND time>=? GROUP BY currency ORDER BY COUNT(*) DESC",
            arrayOf(from.toString())
        ).use { c ->
            while (c.moveToNext()) res[c.getString(0)] = Pair(c.getDouble(1), c.getDouble(2))
        }
        return res
    }

    fun trust(pkg: String): Int {
        readableDatabase.rawQuery("SELECT value FROM trust WHERE pkg=?", arrayOf(pkg)).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun setTrust(pkg: String, value: Int) {
        val cv = ContentValues().apply { put("pkg", pkg); put("value", value) }
        writableDatabase.insertWithOnConflict("trust", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Самая частая валюта — для ручного ввода */
    fun mainCurrency(): String {
        readableDatabase.rawQuery(
            "SELECT currency FROM tx WHERE currency<>'' GROUP BY currency ORDER BY COUNT(*) DESC LIMIT 1", null
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else "BDT" }
    }

    companion object {
        @Volatile private var inst: TxDb? = null
        fun get(ctx: Context): TxDb =
            inst ?: synchronized(this) { inst ?: TxDb(ctx.applicationContext).also { inst = it } }
    }
}
