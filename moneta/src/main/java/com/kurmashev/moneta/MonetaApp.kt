package com.kurmashev.moneta

import android.app.Application
import android.content.Context
import com.kurmashev.moneta.data.AppDb

class MonetaApp : Application() {
    val db: AppDb by lazy { AppDb.build(this) }
    val prefs: Prefs by lazy { Prefs(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: MonetaApp
            private set
        val dao get() = instance.db.dao()
    }
}

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("moneta", Context.MODE_PRIVATE)

    /** Основная валюта учёта; пусто — первый запуск ещё не пройден */
    var baseCurrency: String
        get() = p.getString("base", "") ?: ""
        set(v) = p.edit().putString("base", v).apply()

    // ---- Google ----
    var googleOn: Boolean
        get() = p.getBoolean("googleOn", false)
        set(v) = p.edit().putBoolean("googleOn", v).apply()
    var googleEmail: String
        get() = p.getString("googleEmail", "") ?: ""
        set(v) = p.edit().putString("googleEmail", v).apply()
    var sheetId: String
        get() = p.getString("sheetId", "") ?: ""
        set(v) = p.edit().putString("sheetId", v).apply()
    var sheetUrl: String
        get() = p.getString("sheetUrl", "") ?: ""
        set(v) = p.edit().putString("sheetUrl", v).apply()
    var lastSync: Long
        get() = p.getLong("lastSync", 0)
        set(v) = p.edit().putLong("lastSync", v).apply()
    var needSignIn: Boolean
        get() = p.getBoolean("needSignIn", false)
        set(v) = p.edit().putBoolean("needSignIn", v).apply()

    /** Последний выбранный счёт для новой операции */
    var lastAccountId: String
        get() = p.getString("lastAccount", "") ?: ""
        set(v) = p.edit().putString("lastAccount", v).apply()
}
