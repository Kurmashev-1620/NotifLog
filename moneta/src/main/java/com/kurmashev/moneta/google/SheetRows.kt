package com.kurmashev.moneta.google

import com.kurmashev.moneta.data.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone

/*
 * Как данные выглядят в Google Таблице.
 * Чистые функции без Android — их проверяют юнит-тесты.
 */
object SheetRows {

    const val OPS = "Операции"
    const val TRANSFERS = "Переводы"
    const val ACCOUNTS = "Счета"
    const val CATEGORIES = "Категории"
    const val DASH = "Дашборд"

    fun opsHeader(base: String) = listOf(
        "ID", "Дата", "Тип", "Сумма", "Валюта", "Сумма в $base", "Курс", "Категория", "Счёт",
        "Комментарий", "Теги", "Источник", "Изменено"
    )
    val TRANSFERS_HEADER = listOf("ID", "Дата", "Со счёта", "На счёт", "Списано", "Валюта", "Зачислено", "Валюта", "Комментарий")
    val ACCOUNTS_HEADER = listOf("ID", "Счёт", "Валюта", "Начальный остаток", "Баланс", "Баланс в основной", "Не в итого", "Иконка")
    val CATEGORIES_HEADER = listOf("ID", "Название", "Тип", "Иконка", "Цвет")

    fun typeName(t: String) = if (t == OpType.INCOME) "Доход" else "Расход"
    fun typeFrom(s: String) = if (s.trim().lowercase().startsWith("дох") || s.trim().lowercase() == "income") OpType.INCOME else OpType.EXPENSE
    fun sourceName(s: String) = when (s) {
        Source.PUSH -> "Уведомление"; Source.RECEIPT -> "Чек"; Source.IMPORT -> "Импорт"; else -> "Вручную"
    }

    // ---------- Даты: в таблице храним «серийное число» Excel/Sheets по местному времени ----------

    fun msToSerial(ms: Long, tz: TimeZone = TimeZone.getDefault()): Double {
        val local = ms + tz.getOffset(ms)
        return round6(local / 86_400_000.0 + 25569.0)
    }

    fun serialToMs(serial: Double, tz: TimeZone = TimeZone.getDefault()): Long {
        val days = Math.floor(serial).toInt()
        val c = Calendar.getInstance(tz)
        c.clear()
        c.set(1899, Calendar.DECEMBER, 30)
        c.add(Calendar.DAY_OF_YEAR, days)
        return c.timeInMillis + Math.round((serial - days) * 86_400_000L)
    }

    private fun round6(v: Double) = BigDecimal(v).setScale(6, RoundingMode.HALF_UP).toDouble()
    private fun round2(v: Double) = BigDecimal(v).setScale(2, RoundingMode.HALF_UP).toDouble()
    private fun round8(v: Double) = BigDecimal(v).setScale(8, RoundingMode.HALF_UP).toDouble()

    // ---------- Строки ----------

    fun opRow(o: OperationEntity, cats: Map<String, CategoryEntity>, accs: Map<String, AccountEntity>, tz: TimeZone): List<Any> =
        listOf(
            o.id, msToSerial(o.date, tz), typeName(o.type), round2(o.amount), o.currency, round2(o.amountBase),
            round8(o.rate), cats[o.categoryId]?.name ?: "", accs[o.accountId]?.name ?: "",
            o.comment, o.tags, sourceName(o.source), msToSerial(o.updatedAt, tz)
        )

    fun transferRow(t: TransferEntity, accs: Map<String, AccountEntity>, tz: TimeZone): List<Any> {
        val f = accs[t.fromAccountId]; val to = accs[t.toAccountId]
        return listOf(
            t.id, msToSerial(t.date, tz), f?.name ?: "", to?.name ?: "", round2(t.fromAmount), f?.currency ?: "",
            round2(t.toAmount), to?.currency ?: "", t.comment
        )
    }

    fun accountRow(a: AccountEntity, balance: Double, balanceBase: Double?): List<Any> = listOf(
        a.id, a.name, a.currency, round2(a.initialBalance), round2(balance), balanceBase?.let { round2(it) } ?: "",
        if (a.hidden) "да" else "", a.icon
    )

    fun categoryRow(c: CategoryEntity): List<Any> = listOf(
        c.id, c.name, typeName(c.type), c.icon, String.format("#%06X", c.color and 0xFFFFFFL)
    )

    // ---------- Сравнение строк: что пользователь поменял на компьютере ----------

    /** Одинаковое представление значения, записанного нами и прочитанного из таблицы */
    fun norm(v: Any?): String = when (v) {
        null -> ""
        is Number -> BigDecimal(v.toString()).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        else -> v.toString().trim().let { s ->
            // число, пришедшее строкой
            s.toBigDecimalOrNull()?.setScale(6, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString() ?: s
        }
    }

    /** Хеш строки без колонки «Изменено» (её меняем мы сами) */
    fun hash(row: List<Any?>, width: Int): String {
        val cells = (0 until width).map { norm(row.getOrNull(it)) }
        val md = MessageDigest.getInstance("SHA-1").digest(cells.joinToString("\u001F").toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> com.kurmashev.moneta.data.ImportParser.num(v)
        else -> null
    }

    fun str(v: Any?): String = when (v) {
        null -> ""
        is Number -> norm(v)
        else -> v.toString().trim()
    }
}

// ======================= Дашборд =======================

data class DashMonth(val label: String, val income: Double, val expense: Double)
data class DashData(
    val months: List<DashMonth>,
    val monthCats: List<Pair<String, Double>>,
    val yearCats: List<Pair<String, Double>>,
    val accounts: List<List<Any>>,
    val monthIncome: Double,
    val monthExpense: Double,
    val total: Double
)

object Dashboard {
    private val SHORT = arrayOf("Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек")

    /** Сводка для листа «Дашборд»: 12 месяцев, категории месяца и года, счета */
    fun build(
        ops: List<OperationEntity>, cats: List<CategoryEntity>, accounts: List<AccountEntity>,
        balances: Map<String, Double>, rates: List<RateEntity>, base: String, now: Calendar = Calendar.getInstance()
    ): DashData {
        val catName = cats.associate { it.id to it.name }
        val months = (11 downTo 0).map { back ->
            val r = range(PeriodType.MONTH, -back, now)
            val inR = ops.filter { it.date >= r.from && it.date < r.to }
            val c = Calendar.getInstance().apply { timeInMillis = r.from }
            DashMonth(
                "${SHORT[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR) % 100}",
                inR.filter { it.type == OpType.INCOME }.sumOf { it.amountBase },
                inR.filter { it.type == OpType.EXPENSE }.sumOf { it.amountBase }
            )
        }
        fun cat(from: Long, to: Long) = ops.filter { it.type == OpType.EXPENSE && it.date >= from && it.date < to }
            .groupBy { catName[it.categoryId] ?: "Без категории" }
            .map { (k, v) -> k to v.sumOf { it.amountBase } }
            .sortedByDescending { it.second }
        val m = range(PeriodType.MONTH, 0, now)
        val y = range(PeriodType.YEAR, 0, now)
        val monthOps = ops.filter { it.date >= m.from && it.date < m.to }
        var total = 0.0
        val accRows = accounts.map { a ->
            val b = balances[a.id] ?: 0.0
            val bb = Rates.convertLatest(rates, b, a.currency, base)
            if (!a.hidden && bb != null) total += bb
            listOf<Any>(a.name, Math.round(b * 100) / 100.0, a.currency, bb?.let { Math.round(it * 100) / 100.0 } ?: "")
        }
        return DashData(
            months, cat(m.from, m.to), cat(y.from, y.to), accRows,
            monthOps.filter { it.type == OpType.INCOME }.sumOf { it.amountBase },
            monthOps.filter { it.type == OpType.EXPENSE }.sumOf { it.amountBase },
            total
        )
    }
}
