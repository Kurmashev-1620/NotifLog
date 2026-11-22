package com.finlog

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Экспорт подтверждённых операций в CSV (открывается в Excel / Google Таблицах) */
object Export {
    private const val REQ = 11

    fun start(a: Activity) {
        val name = "finance_" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".csv"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, name)
        @Suppress("DEPRECATION")
        a.startActivityForResult(i, REQ)
    }

    fun onResult(a: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val d = SimpleDateFormat("dd.MM.yyyy", Locale.US)
        val t = SimpleDateFormat("HH:mm", Locale.US)
        try {
            val all = TxDb.get(a).all()
            a.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { w ->
                w.write("﻿")
                w.write("Дата;Время;Тип;Сумма;Валюта;Описание;Источник;Баланс;Текст уведомления\n")
                for (x in all) {
                    val type = when (x.type) {
                        TxType.EXPENSE -> "Расход"; TxType.INCOME -> "Доход"; TxType.UNKNOWN -> "?"
                    }
                    val row = listOf(
                        d.format(Date(x.time)), t.format(Date(x.time)), type,
                        x.amount.toString().replace('.', ','), x.currency, x.merchant, x.app,
                        x.balance?.toString()?.replace('.', ',') ?: "", x.text
                    )
                    w.write(row.joinToString(";") { "\"" + it.replace("\"", "\"\"") + "\"" } + "\n")
                }
            }
            Toast.makeText(a, "Сохранено операций: ${all.size}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(a, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
