package com.kurmashev.moneta.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Курсы валют.
 * Храним в базе «сколько единиц валюты за 1 USD» на каждый день — через доллар можно
 * пересчитать любую валюту в любую. Курсы скачиваются из бесплатных открытых источников;
 * в интернет уходит только дата, никаких данных пользователя.
 */
object Rates {

    fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    /** Курс from → to по таблице «за 1 USD»: сколько `to` за 1 `from` */
    fun cross(perUsdFrom: Double, perUsdTo: Double): Double = perUsdTo / perUsdFrom

    /** Пересчёт по последним известным курсам (для «Итого» по счетам) */
    fun convertLatest(rates: List<RateEntity>, amount: Double, from: String, to: String): Double? {
        if (from == to) return amount
        val m = rates.associate { it.currency to it.perUsd }
        val a = if (from == "USD") 1.0 else m[from] ?: return null
        val b = if (to == "USD") 1.0 else m[to] ?: return null
        return amount * cross(a, b)
    }

    /** Сколько `to` за 1 `from` на дату операции. null — курса нет (нет интернета и не скачан раньше) */
    suspend fun rate(dao: MonetaDao, ms: Long, from: String, to: String, network: Boolean = true): Double? {
        if (from == to) return 1.0
        val d = day(ms).let { if (it > day(System.currentTimeMillis())) day(System.currentTimeMillis()) else it }
        if (network && dao.rateCountOn(d) == 0) fetch(dao, d)
        val a = perUsd(dao, d, from) ?: return null
        val b = perUsd(dao, d, to) ?: return null
        return cross(a, b)
    }

    private suspend fun perUsd(dao: MonetaDao, d: String, c: String): Double? {
        if (c == "USD") return 1.0
        return (dao.rateOnOrBefore(d, c) ?: dao.latestRate(c))?.perUsd
    }

    /** Скачать курсы сегодня, если ещё не скачаны */
    suspend fun refreshToday(dao: MonetaDao) {
        val d = day(System.currentTimeMillis())
        if (dao.rateCountOn(d) == 0) fetch(dao, d)
    }

    /** Скачивает курсы на дату. Несколько источников по очереди. */
    suspend fun fetch(dao: MonetaDao, d: String): Boolean = withContext(Dispatchers.IO) {
        val today = d >= day(System.currentTimeMillis())
        val tag = if (today) "latest" else d
        val urls = listOf(
            "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@$tag/v1/currencies/usd.min.json",
            "https://$tag.currency-api.pages.dev/v1/currencies/usd.min.json"
        ) + if (today) listOf("https://open.er-api.com/v6/latest/USD") else emptyList()

        for (u in urls) {
            val map = try { parse(get(u)) } catch (_: Exception) { null } ?: continue
            val codes = CURRENCIES.map { it.code }.toSet()
            val list = map.filterKeys { it in codes }.map { (c, v) -> RateEntity(d, c, v) }
            if (list.isNotEmpty()) {
                dao.upsertRates(list)
                return@withContext true
            }
        }
        false
    }

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** Понимает оба формата: {"usd":{"kzt":..}} и {"rates":{"KZT":..}} */
    fun parse(json: String): Map<String, Double> {
        val o = JSONObject(json)
        val src = when {
            o.has("usd") -> o.getJSONObject("usd")
            o.has("rates") -> o.getJSONObject("rates")
            else -> return emptyMap()
        }
        val out = HashMap<String, Double>()
        val it = src.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = src.optDouble(k, Double.NaN)
            if (!v.isNaN() && v > 0) out[k.uppercase()] = v
        }
        return out
    }

    fun format(r: Double): String =
        java.text.DecimalFormat(if (r >= 100) "0.##" else if (r >= 1) "0.####" else "0.######",
            java.text.DecimalFormatSymbols(Locale.US).apply { decimalSeparator = ',' }).format(r)
}
