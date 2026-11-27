package com.kurmashev.moneta.data

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Locale

// ---------------- Валюты ----------------

data class Currency(val code: String, val symbol: String, val name: String)

val CURRENCIES = listOf(
    Currency("KZT", "₸", "Казахстанский тенге"),
    Currency("RUB", "₽", "Российский рубль"),
    Currency("BDT", "৳", "Бангладешская така"),
    Currency("USD", "$", "Доллар США"),
    Currency("EUR", "€", "Евро"),
    Currency("UZS", "сўм", "Узбекский сум"),
    Currency("KGS", "с", "Киргизский сом"),
    Currency("BYN", "Br", "Белорусский рубль"),
    Currency("UAH", "₴", "Украинская гривна"),
    Currency("TRY", "₺", "Турецкая лира"),
    Currency("THB", "฿", "Тайский бат"),
    Currency("AED", "د.إ", "Дирхам ОАЭ"),
    Currency("VND", "₫", "Вьетнамский донг"),
    Currency("JPY", "¥", "Японская иена"),
    Currency("CNY", "元", "Китайский юань"),
    Currency("KRW", "₩", "Южнокорейская вона"),
    Currency("INR", "₹", "Индийская рупия"),
    Currency("GBP", "£", "Фунт стерлингов"),
    Currency("GEL", "₾", "Грузинский лари"),
    Currency("AMD", "֏", "Армянский драм"),
    Currency("AZN", "₼", "Азербайджанский манат"),
    Currency("MYR", "RM", "Малайзийский ринггит"),
    Currency("IDR", "Rp", "Индонезийская рупия")
)

fun symbol(code: String) = CURRENCIES.firstOrNull { it.code == code }?.symbol ?: code

private val FMT = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US).apply {
    groupingSeparator = ' '; decimalSeparator = ','
})

fun money(v: Double): String = FMT.format(v)
fun money(v: Double, cur: String): String = "${FMT.format(v)} ${symbol(cur)}"

/** Короткая запись больших сумм: 9,79 млн */
fun moneyShort(v: Double, cur: String): String =
    if (kotlin.math.abs(v) >= 1_000_000) "${DecimalFormat("0.##", DecimalFormatSymbols(Locale.US).apply { decimalSeparator = ',' }).format(v / 1_000_000)} млн ${symbol(cur)}"
    else money(v, cur)

// ---------------- Периоды ----------------

enum class PeriodType(val label: String) { DAY("День"), WEEK("Неделя"), MONTH("Месяц"), YEAR("Год") }

data class DateRange(val from: Long, val to: Long, val label: String)

private val MONTHS = arrayOf("Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
    "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь")
private val MONTHS_GEN = arrayOf("января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря")
private val MONTHS_SHORT = arrayOf("янв", "фев", "мар", "апр", "мая", "июн",
    "июл", "авг", "сен", "окт", "ноя", "дек")
private val WEEKDAYS = arrayOf("воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота")

fun range(type: PeriodType, offset: Int, now: Calendar = Calendar.getInstance()): DateRange {
    val c = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    when (type) {
        PeriodType.DAY -> {
            c.add(Calendar.DAY_OF_YEAR, offset)
            val from = c.timeInMillis
            val label = "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS_GEN[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
            c.add(Calendar.DAY_OF_YEAR, 1)
            return DateRange(from, c.timeInMillis, label)
        }
        PeriodType.WEEK -> {
            c.firstDayOfWeek = Calendar.MONDAY
            val shift = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // дней от понедельника
            c.add(Calendar.DAY_OF_YEAR, -shift + offset * 7)
            val from = c.timeInMillis
            val a = "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS_SHORT[c.get(Calendar.MONTH)]}"
            c.add(Calendar.DAY_OF_YEAR, 6)
            val b = "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS_SHORT[c.get(Calendar.MONTH)]}"
            c.add(Calendar.DAY_OF_YEAR, 1)
            return DateRange(from, c.timeInMillis, "$a – $b")
        }
        PeriodType.MONTH -> {
            c.set(Calendar.DAY_OF_MONTH, 1)
            c.add(Calendar.MONTH, offset)
            val from = c.timeInMillis
            val label = "${MONTHS[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
            c.add(Calendar.MONTH, 1)
            return DateRange(from, c.timeInMillis, label)
        }
        PeriodType.YEAR -> {
            c.set(Calendar.DAY_OF_YEAR, 1)
            c.add(Calendar.YEAR, offset)
            val from = c.timeInMillis
            val label = "${c.get(Calendar.YEAR)}"
            c.add(Calendar.YEAR, 1)
            return DateRange(from, c.timeInMillis, label)
        }
    }
}

/** «27 ноября, пятница» */
fun dayTitle(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance()
    val sameYear = c.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    val d = range(PeriodType.DAY, 0).from
    return when {
        ms >= d && ms < d + 86_400_000L -> "Сегодня"
        ms >= d - 86_400_000L && ms < d -> "Вчера"
        else -> "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS_GEN[c.get(Calendar.MONTH)]}" +
            (if (sameYear) "" else " ${c.get(Calendar.YEAR)}") + ", ${WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1]}"
    }
}

fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

// ---------------- Балансы ----------------

/** Баланс каждого счёта в его валюте */
fun balances(
    accounts: List<AccountEntity>,
    ops: List<OperationEntity>,
    transfers: List<TransferEntity>
): Map<String, Double> {
    val m = HashMap<String, Double>()
    accounts.forEach { m[it.id] = it.initialBalance }
    ops.forEach { o ->
        val s = if (o.type == OpType.INCOME) o.amount else -o.amount
        m[o.accountId] = (m[o.accountId] ?: 0.0) + s
    }
    transfers.forEach { t ->
        m[t.fromAccountId] = (m[t.fromAccountId] ?: 0.0) - t.fromAmount
        m[t.toAccountId] = (m[t.toAccountId] ?: 0.0) + t.toAmount
    }
    return m
}

// ---------------- Калькулятор ----------------

/** «1000+250-50» → 1200. Поддерживает + и −, запятую и точку. */
fun evalExpr(expr: String): Double? {
    if (expr.isBlank()) return null
    var total = 0.0
    var sign = 1.0
    val num = StringBuilder()
    fun flush(): Boolean {
        if (num.isEmpty()) return true
        val v = num.toString().replace(',', '.').toDoubleOrNull() ?: return false
        total += sign * v
        num.clear()
        return true
    }
    for (ch in expr) {
        when (ch) {
            '+' -> { if (!flush()) return null; sign = 1.0 }
            '-', '−' -> { if (!flush()) return null; sign = -1.0 }
            ' ' -> {}
            else -> num.append(ch)
        }
    }
    if (!flush()) return null
    return Math.round(total * 100) / 100.0
}
