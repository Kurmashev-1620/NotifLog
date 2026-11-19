package com.notiflog

import android.content.Context

/**
 * Фильтры: что НЕ записывать в журнал.
 * Настройки хранятся в SharedPreferences на телефоне.
 */
object Filters {

    private const val PREFS = "filters"
    private const val K_CODES = "skip_codes"
    private const val K_BANKS = "skip_banks"
    private const val K_EXCLUDED = "excluded"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun skipCodes(c: Context) = prefs(c).getBoolean(K_CODES, true)
    fun skipBanks(c: Context) = prefs(c).getBoolean(K_BANKS, true)

    fun setOptions(c: Context, codes: Boolean, banks: Boolean) {
        prefs(c).edit().putBoolean(K_CODES, codes).putBoolean(K_BANKS, banks).apply()
    }

    /** Пакеты, исключённые вручную */
    fun excluded(c: Context): Set<String> =
        prefs(c).getStringSet(K_EXCLUDED, emptySet())?.toSet() ?: emptySet()

    fun setExcluded(c: Context, set: Set<String>) {
        prefs(c).edit().putStringSet(K_EXCLUDED, HashSet(set)).apply()
    }

    fun exclude(c: Context, pkg: String) = setExcluded(c, excluded(c) + pkg)

    // ---------- Правила ----------

    // Слова, по которым узнаём одноразовые коды / пароли
    private val CODE_WORDS = Regex(
        "(код|\\bcode\\b|\\botp\\b|парол|password|passcode|\\bpin\\b|verif|" +
            "подтвер|одноразов|\\b2fa\\b|authenticat|security code)"
    )

    // 4–8 цифр подряд или формат 123-456 / 123 456
    private val CODE_DIGITS = Regex("(?<!\\d)(\\d{4,8}|\\d{3}[- ]\\d{3})(?!\\d)")

    // Части имени пакета банковских / платёжных приложений
    private val BANK_PKG = listOf(
        "bank", "bkash", "nagad", "dbbl", "upay", "wallet", "paypal", "payoneer",
        "sber", "tinkoff", "tbank", "alfa", "vtb", "kaspi", "halyk", "finance"
    )

    // Финансовые SMS: баланс, списание, номер карты/счёта, суммы в така
    private val MONEY_WORDS = Regex(
        "(баланс|списан|зачислен|карт[аыу]?\\s*\\*|счёт\\s*\\*|счет\\s*\\*|" +
            "balance|debited|credited|\\ba/c\\b|\\bacct\\b|card\\s*(no|ending|\\*)|" +
            "\\btk\\.?\\s?\\d|\\bbdt\\b|৳|transaction|cash ?in|cash ?out)"
    )

    private fun isBankPackage(pkg: String, app: String): Boolean {
        val tokens = pkg.lowercase().split('.', '_')
        val byPkg = tokens.any { t ->
            BANK_PKG.any { t.contains(it) } || t.startsWith("pay") || t.endsWith("pay")
        }
        val a = app.lowercase()
        return byPkg || a.contains("bank") || a.contains("банк")
    }

    fun isCode(text: String): Boolean {
        val t = text.lowercase()
        return CODE_WORDS.containsMatchIn(t) && CODE_DIGITS.containsMatchIn(t)
    }

    /** true — уведомление НЕ записываем */
    fun shouldSkip(c: Context, pkg: String, app: String, title: String, text: String): Boolean {
        if (pkg in excluded(c)) return true
        val all = "$title\n$text".lowercase()
        if (skipCodes(c) && CODE_WORDS.containsMatchIn(all) && CODE_DIGITS.containsMatchIn(all)) return true
        if (skipBanks(c) && (isBankPackage(pkg, app) || MONEY_WORDS.containsMatchIn(all))) return true
        return false
    }
}
