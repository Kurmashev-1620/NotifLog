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

    /** Последний выбранный счёт для новой операции */
    var lastAccountId: String
        get() = p.getString("lastAccount", "") ?: ""
        set(v) = p.edit().putString("lastAccount", v).apply()
}
