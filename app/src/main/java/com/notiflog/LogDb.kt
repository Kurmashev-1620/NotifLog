package com.notiflog

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Entry(
    val id: Long,
    val time: Long,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String
)

/** Журнал хранится в SQLite-базе внутри телефона (notiflog.db) */
class LogDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx, "notiflog.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE log(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "time INTEGER NOT NULL," +
                "pkg TEXT NOT NULL," +
                "app TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "text TEXT NOT NULL," +
                "nkey TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_time ON log(time)")
        db.execSQL("CREATE INDEX idx_key ON log(nkey)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}

    /**
     * Сохраняет запись. Повторы (то же уведомление с тем же текстом
     * в течение 10 минут — приложения часто «переотправляют» его) пропускаются.
     */
    @Synchronized
    fun insert(time: Long, pkg: String, app: String, title: String, text: String, key: String): Boolean {
        val db = writableDatabase
        val dup = DatabaseUtils.queryNumEntries(
            db, "log",
            "nkey=? AND title=? AND text=? AND time>?",
            arrayOf(key, title, text, (time - 10 * 60 * 1000L).toString())
        )
        if (dup > 0) return false
        val cv = ContentValues().apply {
            put("time", time)
            put("pkg", pkg)
            put("app", app)
            put("title", title)
            put("text", text)
            put("nkey", key)
        }
        return db.insert("log", null, cv) != -1L
    }

    fun query(search: String, limit: Int): List<Entry> {
        val sel: String?
        val args: Array<String>?
        if (search.isBlank()) {
            sel = null
            args = null
        } else {
            sel = "app LIKE ? OR title LIKE ? OR text LIKE ? OR pkg LIKE ?"
            val p = "%${search.trim()}%"
            args = arrayOf(p, p, p, p)
        }
        val out = ArrayList<Entry>()
        readableDatabase.query(
            "log", arrayOf("id", "time", "pkg", "app", "title", "text"),
            sel, args, null, null, "time DESC, id DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Entry(
                        c.getLong(0), c.getLong(1), c.getString(2),
                        c.getString(3), c.getString(4), c.getString(5)
                    )
                )
            }
        }
        return out
    }

    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "log")

    fun deleteByPkg(pkg: String): Int = writableDatabase.delete("log", "pkg=?", arrayOf(pkg))

    /** Удаляет уже сохранённые записи, которые теперь попадают под фильтры */
    fun purge(ctx: Context): Int {
        val ids = query("", Int.MAX_VALUE)
            .filter { Filters.shouldSkip(ctx, it.pkg, it.app, it.title, it.text) }
            .map { it.id }
        if (ids.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.delete("log", "id=?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ids.size
    }

    fun clear() {
        writableDatabase.delete("log", null, null)
    }

    companion object {
        @Volatile
        private var inst: LogDb? = null

        fun get(ctx: Context): LogDb =
            inst ?: synchronized(this) {
                inst ?: LogDb(ctx.applicationContext).also { inst = it }
            }
    }
}
