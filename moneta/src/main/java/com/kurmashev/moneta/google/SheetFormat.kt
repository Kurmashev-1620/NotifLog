package com.kurmashev.moneta.google

import com.kurmashev.moneta.data.symbol
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Оформление таблицы и раскладка листа «Дашборд».
 *
 * Дашборд (строки/столбцы):
 *   A1  заголовок, A2 когда обновлено
 *   A4:F4 итоги: на счетах / доходы месяца / расходы месяца
 *   A6:D18  12 месяцев: Месяц | Доходы | Расходы | Разница      → столбчатый график
 *   F6:G36  расходы по категориям за месяц                       → кольцевая диаграмма
 *   I6:J36  расходы по категориям за год                         → кольцевая диаграмма
 *   L6:O36  счета и балансы
 *   графики — ниже, с 39-й строки
 */
object SheetFormat {

    private fun rgb(hex: Long) = JSONObject()
        .put("red", ((hex shr 16) and 0xFF) / 255.0)
        .put("green", ((hex shr 8) and 0xFF) / 255.0)
        .put("blue", (hex and 0xFF) / 255.0)

    private fun range(sheet: Int, r0: Int, r1: Int, c0: Int, c1: Int) = JSONObject()
        .put("sheetId", sheet).put("startRowIndex", r0).put("endRowIndex", r1)
        .put("startColumnIndex", c0).put("endColumnIndex", c1)

    private fun cols(sheet: Int, c0: Int, c1: Int) = JSONObject()
        .put("sheetId", sheet).put("startColumnIndex", c0).put("endColumnIndex", c1)

    private fun numFmt(r: JSONObject, type: String, pattern: String) = JSONObject().put("repeatCell", JSONObject()
        .put("range", r)
        .put("cell", JSONObject().put("userEnteredFormat", JSONObject().put("numberFormat",
            JSONObject().put("type", type).put("pattern", pattern))))
        .put("fields", "userEnteredFormat.numberFormat"))

    private fun header(sheet: Int, row: Int, c0: Int, c1: Int) = JSONObject().put("repeatCell", JSONObject()
        .put("range", range(sheet, row, row + 1, c0, c1))
        .put("cell", JSONObject().put("userEnteredFormat", JSONObject()
            .put("backgroundColor", rgb(0x2C4A3F))
            .put("textFormat", JSONObject().put("bold", true).put("foregroundColor", rgb(0xFFFFFF)))))
        .put("fields", "userEnteredFormat(backgroundColor,textFormat)"))

    private fun width(sheet: Int, c0: Int, c1: Int, px: Int) = JSONObject().put("updateDimensionProperties", JSONObject()
        .put("range", JSONObject().put("sheetId", sheet).put("dimension", "COLUMNS").put("startIndex", c0).put("endIndex", c1))
        .put("properties", JSONObject().put("pixelSize", px)).put("fields", "pixelSize"))

    private fun hideId(sheet: Int) = JSONObject().put("updateDimensionProperties", JSONObject()
        .put("range", JSONObject().put("sheetId", sheet).put("dimension", "COLUMNS").put("startIndex", 0).put("endIndex", 1))
        .put("properties", JSONObject().put("hiddenByUser", true)).put("fields", "hiddenByUser"))

    private fun src(sheet: Int, r0: Int, r1: Int, c: Int) = JSONObject().put("sourceRange",
        JSONObject().put("sources", JSONArray(listOf(range(sheet, r0, r1, c, c + 1)))))

    private fun pos(row: Int, col: Int, w: Int, h: Int) = JSONObject().put("overlayPosition", JSONObject()
        .put("anchorCell", JSONObject().put("sheetId", 0).put("rowIndex", row).put("columnIndex", col))
        .put("widthPixels", w).put("heightPixels", h))

    private fun pie(title: String, c: Int, row: Int, col: Int) = JSONObject().put("addChart", JSONObject().put("chart", JSONObject()
        .put("spec", JSONObject().put("title", title).put("pieChart", JSONObject()
            .put("legendPosition", "RIGHT_LEGEND").put("pieHole", 0.5)
            .put("domain", src(0, 6, 36, c)).put("series", src(0, 6, 36, c + 1))))
        .put("position", pos(row, col, 560, 340))))

    private fun monthsChart() = JSONObject().put("addChart", JSONObject().put("chart", JSONObject()
        .put("spec", JSONObject().put("title", "Доходы и расходы по месяцам").put("basicChart", JSONObject()
            .put("chartType", "COLUMN").put("legendPosition", "BOTTOM_LEGEND").put("headerCount", 1)
            .put("domains", JSONArray(listOf(JSONObject().put("domain", src(0, 5, 18, 0)))))
            .put("series", JSONArray(listOf(
                JSONObject().put("series", src(0, 5, 18, 1)).put("targetAxis", "LEFT_AXIS").put("color", rgb(0x4CC38A)),
                JSONObject().put("series", src(0, 5, 18, 2)).put("targetAxis", "LEFT_AXIS").put("color", rgb(0xE5534B))
            )))))
        .put("position", pos(38, 0, 700, 360))))

    /** Оформление при создании таблицы */
    fun setup(): JSONArray {
        val r = ArrayList<JSONObject>()
        // заголовки и ширины листов с данными
        r += header(1, 0, 0, 13); r += header(2, 0, 0, 9); r += header(3, 0, 0, 8); r += header(4, 0, 0, 5)
        listOf(1, 2, 3, 4).forEach { r += hideId(it) }
        // даты
        r += numFmt(range(1, 1, 100000, 1, 2), "DATE_TIME", "dd.mm.yyyy hh:mm")
        r += numFmt(range(1, 1, 100000, 12, 13), "DATE_TIME", "dd.mm.yyyy hh:mm")
        r += numFmt(range(2, 1, 100000, 1, 2), "DATE_TIME", "dd.mm.yyyy hh:mm")
        // суммы
        r += numFmt(range(1, 1, 100000, 3, 4), "NUMBER", "#,##0.00")
        r += numFmt(range(1, 1, 100000, 5, 6), "NUMBER", "#,##0.00")
        r += numFmt(range(1, 1, 100000, 6, 7), "NUMBER", "0.######")
        r += numFmt(range(2, 1, 100000, 4, 5), "NUMBER", "#,##0.00")
        r += numFmt(range(2, 1, 100000, 6, 7), "NUMBER", "#,##0.00")
        r += numFmt(range(3, 1, 1000, 3, 6), "NUMBER", "#,##0.00")
        r += width(1, 1, 2, 130); r += width(1, 7, 9, 160); r += width(1, 9, 11, 200)
        r += width(2, 1, 2, 130); r += width(2, 2, 4, 160)
        r += width(3, 1, 2, 180); r += width(4, 1, 2, 200)
        // дашборд
        r += JSONObject().put("repeatCell", JSONObject().put("range", range(0, 0, 1, 0, 1))
            .put("cell", JSONObject().put("userEnteredFormat", JSONObject().put("textFormat",
                JSONObject().put("bold", true).put("fontSize", 18).put("foregroundColor", rgb(0x2C4A3F)))))
            .put("fields", "userEnteredFormat.textFormat"))
        r += JSONObject().put("repeatCell", JSONObject().put("range", range(0, 3, 4, 0, 6))
            .put("cell", JSONObject().put("userEnteredFormat", JSONObject().put("textFormat",
                JSONObject().put("bold", true).put("fontSize", 12))))
            .put("fields", "userEnteredFormat.textFormat"))
        r += header(0, 5, 0, 4); r += header(0, 5, 5, 7); r += header(0, 5, 8, 10); r += header(0, 5, 11, 15)
        r += numFmt(range(0, 3, 4, 0, 6), "NUMBER", "#,##0.00")
        r += numFmt(range(0, 6, 36, 1, 4), "NUMBER", "#,##0")
        r += numFmt(range(0, 6, 36, 6, 7), "NUMBER", "#,##0")
        r += numFmt(range(0, 6, 36, 9, 10), "NUMBER", "#,##0")
        r += numFmt(range(0, 6, 36, 12, 13), "NUMBER", "#,##0.00")
        r += numFmt(range(0, 6, 36, 14, 15), "NUMBER", "#,##0.00")
        r += width(0, 0, 1, 150); r += width(0, 5, 6, 170); r += width(0, 8, 9, 170); r += width(0, 11, 12, 170)
        // графики
        r += monthsChart()
        r += pie("Расходы по категориям — этот месяц", 5, 38, 8)
        r += pie("Расходы по категориям — этот год", 8, 58, 8)
        return JSONArray(r)
    }

    /** Значения листа «Дашборд»: диапазон → строки */
    fun dashboardValues(d: DashData, base: String, now: Long, tz: TimeZone): List<Pair<String, List<List<Any>>>> {
        val dash = "'${SheetRows.DASH}'"
        val f = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).apply { timeZone = tz }
        val s = symbol(base)
        return listOf(
            "$dash!A1" to listOf(
                listOf<Any>("Moneta — мои финансы"),
                listOf<Any>("Обновлено: ${f.format(Date(now))} · основная валюта: $base $s"),
                listOf<Any>(""),
                listOf<Any>("На счетах, $s", Math.round(d.total * 100) / 100.0, "Доходы за месяц", Math.round(d.monthIncome * 100) / 100.0,
                    "Расходы за месяц", Math.round(d.monthExpense * 100) / 100.0)
            ),
            "$dash!A6" to listOf(listOf<Any>("Месяц", "Доходы", "Расходы", "Разница")) +
                d.months.map { listOf<Any>(it.label, Math.round(it.income), Math.round(it.expense), Math.round(it.income - it.expense)) },
            "$dash!F6" to listOf(listOf<Any>("Категория (месяц)", "Сумма, $s")) +
                d.monthCats.take(30).map { listOf<Any>(it.first, Math.round(it.second)) },
            "$dash!I6" to listOf(listOf<Any>("Категория (год)", "Сумма, $s")) +
                d.yearCats.take(30).map { listOf<Any>(it.first, Math.round(it.second)) },
            "$dash!L6" to listOf(listOf<Any>("Счёт", "Баланс", "Валюта", "В основной, $s")) + d.accounts.take(30)
        )
    }
}
