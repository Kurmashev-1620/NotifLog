package com.finlog

/*
 * Как отличить финансовое уведомление от обычного.
 *
 * 1. Обязательно должна быть СУММА (число рядом с валютой: Tk, ৳, BDT, ₽, руб, $ ...).
 *    Нет суммы — точно не финансовое, уведомление выбрасывается.
 * 2. Дальше набираются баллы «за» и «против»:
 *      +3  слово операции: списано / debited / paid / зачислено / received ...
 *      +2  источник — банк или платёжное приложение (bKash, Nagad, *bank*, *pay* ...)
 *      +1  источник — SMS
 *      +2  есть баланс / остаток
 *      +2  есть номер карты или счёта (*1234, A/C, карта)
 *      +1  валюта указана явно
 *      +3  приложение ты сам отметил как финансовое
 *      −4  реклама (скидка, offer, cashback, up to ...)
 *      −3  напоминание об оплате (due, к оплате, задолженность)
 *      −5  одноразовый код (OTP, код подтверждения)
 *      −4  мессенджер (WhatsApp, Telegram ...) — там люди просто пишут про деньги
 * 3. Итог:
 *      ≥ 5 и понятно расход это или доход → сразу в «Операции»
 *      1…4                                → во вкладку «Проверить» (решаешь ты)
 *      ≤ 0                                → выбрасывается
 */

enum class TxType { EXPENSE, INCOME, UNKNOWN }

data class Parsed(
    val amount: Double,
    val currency: String,
    val type: TxType,
    val balance: Double?,
    val merchant: String,
    val score: Int
) {
    /** Уверенно финансовое — сразу в операции, иначе — на проверку */
    val confident: Boolean
        get() = score >= Classifier.CONFIRM_SCORE && type != TxType.UNKNOWN
}

object Classifier {

    const val CONFIRM_SCORE = 5

    // Число: 1,250.50 · 1 250,50 · 500 · 12.5
    private const val NUM = "(\\d{1,3}(?:[ ,]\\d{3})+(?:[.,]\\d{1,2})?|\\d+(?:[.,]\\d{1,2})?)"

    // Валюта
    private const val CUR =
        "(рубл[а-я]*|руб\\.?|rub|₽|р\\.?|taka|tk\\.?|bdt|৳|usd|\\\$|eur|€|kzt|₸|тг|inr|₹|rs\\.?)"

    private val CUR_NUM = Regex("(?<!\\p{L})$CUR\\s?:?\\s?$NUM")
    private val NUM_CUR = Regex("$NUM\\s?$CUR(?!\\p{L})")
    private val AMOUNT_WORD = Regex("(amount|amt|сумма|сумму)[^\\d]{0,10}$NUM")
    private val BALANCE =
        Regex("(avl\\.? ?bal|available balance|balance|bal\\.|bal:|баланс|остаток|доступно)[^\\d]{0,20}$NUM")

    private val MASK = Regex(
        "(\\*+\\d{2,4}|x{2,}\\d{2,4}|\\ba/c\\b|\\bacct\\b|\\baccount\\b|\\bcard\\b|сч[её]т|карт[аеыуо])"
    )

    // Слова операций
    private val EN_EXPENSE = Regex(
        "\\b(debited|debit of|spent|purchased?|paid|payment|withdrawn|withdrawal|cash ?out|sent|" +
            "send money|transferred to|transfer to|bill pay(ment)?)\\b"
    )
    private val EN_INCOME = Regex(
        "\\b(credited|credit of|received|deposited|deposit of|cash ?in|refund(ed)?|salary|" +
            "added to your|you have got)\\b"
    )
    private val RU_EXPENSE = listOf(
        "списан", "списание", "оплата", "оплачен", "покупка", "покупк", "снятие", "снят",
        "платёж", "платеж", "перевод на", "перевод клиенту", "отправлен"
    )
    private val RU_INCOME = listOf(
        "зачислен", "зачисление", "поступлен", "пополнен", "получен", "перевод от",
        "возврат", "зарплат", "начислен"
    )

    // Против
    private val EN_PROMO = Regex(
        "(\\b(offer|discount|cashback|win|chance|bonus|promo|coupon|sale|free|up to|off|loan|" +
            "apply now|limited time|subscribe)\\b|%)"
    )
    private val RU_PROMO = listOf(
        "скидк", "акци", "выгод", "предлож", "кешбэк", "кэшбэк", "бонус", "розыгрыш",
        "выиграй", "бесплат", "кредит до", "одобрен", "промокод"
    )
    private val EN_DUE = Regex("\\b(due|reminder|please pay|pay now|overdue|bill is ready|outstanding)\\b")
    private val RU_DUE = listOf("к оплате", "оплатите", "напомина", "задолжен", "просроч")

    private val CODE_WORDS = Regex(
        "(код|\\bcode\\b|\\botp\\b|парол|password|passcode|\\bpin\\b|verif|подтвер|одноразов|\\b2fa\\b)"
    )
    // 4–8 цифр отдельным числом (не часть суммы 1,250.00 и не телефон)
    private val CODE_DIGITS = Regex("(?<!\\d)(?<!\\d[.,])\\d{4,8}(?!\\d)(?![.,]\\d)")

    // Источники
    private val BANK_PKG = listOf(
        "bank", "bkash", "nagad", "dbbl", "rocket", "upay", "wallet", "paypal", "payoneer",
        "sber", "tinkoff", "tbank", "alfa", "vtb", "kaspi", "halyk", "finance", "money"
    )
    private val MESSENGERS = listOf(
        "whatsapp", "telegram", "viber", "orca", "instagram", "imo", "discord", "vkontakte",
        "com.vk", "skype", "snapchat", "wechat", "thoughtcrime", "messenger", "facebook"
    )

    fun isMessenger(pkg: String) = MESSENGERS.any { pkg.lowercase().contains(it) }

    fun isSms(pkg: String): Boolean {
        val p = pkg.lowercase()
        return !isMessenger(p) && (p.contains("mms") || p.contains("messaging") || p.contains("sms"))
    }

    fun isBank(pkg: String, app: String): Boolean {
        val tokens = pkg.lowercase().split('.', '_')
        val byPkg = tokens.any { t ->
            BANK_PKG.any { t.contains(it) } || t.startsWith("pay") || t.endsWith("pay")
        }
        val a = app.lowercase()
        return byPkg || a.contains("bank") || a.contains("банк")
    }

    /**
     * @param trust  1 — ты отметил приложение как финансовое, −1 — игнорировать, 0 — нет решения
     * @return null — не финансовое
     */
    fun classify(pkg: String, app: String, title: String, text: String, trust: Int = 0): Parsed? {
        if (trust < 0) return null
        val orig = "$title\n$text"
        val t = orig.lowercase()

        // ---- Сумма ----
        val bal = BALANCE.find(t)
        val balance = bal?.groupValues?.last()?.let { parseNum(it) }
        val balRange = bal?.range

        data class Hit(val start: Int, val numRange: IntRange, val num: String, val cur: String)

        val hits = ArrayList<Hit>()
        CUR_NUM.findAll(t).forEach { m ->
            hits.add(Hit(m.range.first, m.groups[2]!!.range, m.groupValues[2], m.groupValues[1]))
        }
        NUM_CUR.findAll(t).forEach { m ->
            hits.add(Hit(m.range.first, m.groups[1]!!.range, m.groupValues[1], m.groupValues[2]))
        }
        hits.sortBy { it.start }
        val inBalance = { r: IntRange -> balRange != null && r.first >= balRange.first && r.last <= balRange.last }
        val hit = hits.firstOrNull { !inBalance(it.numRange) }

        val amount: Double
        val currency: String
        if (hit != null) {
            amount = parseNum(hit.num) ?: return null
            currency = normCurrency(hit.cur)
        } else {
            val aw = AMOUNT_WORD.find(t) ?: return null   // суммы нет — не финансовое
            amount = parseNum(aw.groupValues.last()) ?: return null
            currency = ""
        }
        if (amount <= 0.0) return null

        // ---- Тип операции: какое слово встретилось раньше ----
        val expAt = firstIndex(t, EN_EXPENSE, RU_EXPENSE)
        val incAt = firstIndex(t, EN_INCOME, RU_INCOME)
        val type = when {
            expAt < 0 && incAt < 0 -> TxType.UNKNOWN
            incAt < 0 -> TxType.EXPENSE
            expAt < 0 -> TxType.INCOME
            expAt <= incAt -> TxType.EXPENSE
            else -> TxType.INCOME
        }

        // ---- Баллы ----
        var score = 0
        if (type != TxType.UNKNOWN) score += 3
        when {
            isMessenger(pkg) -> score -= 4
            isBank(pkg, app) -> score += 2
            isSms(pkg) -> score += 1
        }
        if (balance != null) score += 2
        if (MASK.containsMatchIn(t)) score += 2
        if (currency.isNotEmpty()) score += 1
        if (EN_PROMO.containsMatchIn(t) || RU_PROMO.any { t.contains(it) }) score -= 4
        if (EN_DUE.containsMatchIn(t) || RU_DUE.any { t.contains(it) }) score -= 3
        if (CODE_WORDS.containsMatchIn(t) && CODE_DIGITS.containsMatchIn(t)) score -= 5
        if (trust > 0) score += 3
        if (score <= 0) return null

        return Parsed(amount, currency, type, balance, merchant(orig, type, title, app), score)
    }

    private fun firstIndex(t: String, en: Regex, ru: List<String>): Int {
        val idx = ArrayList<Int>()
        en.find(t)?.let { idx.add(it.range.first) }
        ru.forEach { w -> t.indexOf(w).let { if (it >= 0) idx.add(it) } }
        return idx.minOrNull() ?: -1
    }

    private val MERCHANT_OUT = Regex(
        "(?:\\bat\\b|\\bto\\b|merchant:?|в магазине|получатель:?|\\bв\\b)\\s+([\\p{L}\\p{N}][\\p{L}\\p{N} .&'_*-]{1,40})",
        RegexOption.IGNORE_CASE
    )
    private val MERCHANT_IN = Regex(
        "(?:\\bfrom\\b|\\bот\\b|отправитель:?)\\s+([\\p{L}\\p{N}][\\p{L}\\p{N} .&'_*-]{1,40})",
        RegexOption.IGNORE_CASE
    )
    private val SKIP_MERCHANT = listOf("your", "you", "a/c", "acc", "account", "card", "счет", "счёт", "карт")

    private fun merchant(orig: String, type: TxType, title: String, app: String): String {
        val re = if (type == TxType.INCOME) MERCHANT_IN else MERCHANT_OUT
        for (m in re.findAll(orig)) {
            var s = m.groupValues[1]
            for (stop in listOf(". ", ",", " on ", " ref", "  ", " is ", " Fee", " fee", " bal", " Bal")) {
                val i = s.indexOf(stop)
                if (i > 0) s = s.substring(0, i)
            }
            s = s.trim().trimEnd('.')
            if (s.length >= 2 && SKIP_MERCHANT.none { s.lowercase().startsWith(it) }) return s.take(30)
        }
        return title.ifBlank { app }.take(30)
    }

    fun parseNum(raw: String): Double? {
        var s = raw.replace(" ", "")
        val dot = s.lastIndexOf('.')
        val comma = s.lastIndexOf(',')
        s = when {
            dot >= 0 && comma >= 0 ->
                if (dot > comma) s.replace(",", "") else s.replace(".", "").replace(',', '.')
            comma >= 0 ->
                if (s.length - comma - 1 == 3) s.replace(",", "") else s.replace(',', '.')
            else -> s
        }
        return s.toDoubleOrNull()
    }

    fun normCurrency(c: String): String {
        val s = c.lowercase().trimEnd('.')
        return when {
            s.startsWith("рубл") || s == "руб" || s == "rub" || s == "₽" || s == "р" -> "RUB"
            s == "taka" || s == "tk" || s == "bdt" || s == "৳" -> "BDT"
            s == "usd" || s == "$" -> "USD"
            s == "eur" || s == "€" -> "EUR"
            s == "kzt" || s == "₸" || s == "тг" -> "KZT"
            s == "inr" || s == "₹" || s == "rs" -> "INR"
            else -> s.uppercase()
        }
    }

    fun symbol(cur: String) = when (cur) {
        "BDT" -> "৳"; "RUB" -> "₽"; "USD" -> "$"; "EUR" -> "€"; "KZT" -> "₸"; "INR" -> "₹"
        else -> cur
    }
}
