package com.kurmashev.moneta.data

import org.w3c.dom.Element
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

// ======================= Чтение xlsx (без сторонних библиотек) =======================

object XlsxReader {
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** Имя листа → строки → ячейки (текстом; числа как есть, «46344.0») */
    fun read(input: InputStream): LinkedHashMap<String, List<List<String>>> {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) files[e.name.trimStart('/')] = z.readBytes()
                e = z.nextEntry
            }
        }
        val shared = files["xl/sharedStrings.xml"]?.let { sharedStrings(dom(it)) } ?: emptyList()

        val rels = HashMap<String, String>()
        files["xl/_rels/workbook.xml.rels"]?.let { b ->
            val list = dom(b).getElementsByTagNameNS("*", "Relationship")
            for (i in 0 until list.length) {
                val r = list.item(i) as Element
                rels[r.getAttribute("Id")] = r.getAttribute("Target")
            }
        }

        val out = LinkedHashMap<String, List<List<String>>>()
        val wb = files["xl/workbook.xml"] ?: return out
        val sheets = dom(wb).getElementsByTagNameNS("*", "sheet")
        for (i in 0 until sheets.length) {
            val s = sheets.item(i) as Element
            val name = s.getAttribute("name")
            val rid = s.getAttributeNS(REL_NS, "id").ifEmpty { s.getAttribute("r:id") }
            val target = rels[rid] ?: "worksheets/sheet${i + 1}.xml"
            val path = if (target.startsWith("/")) target.trimStart('/') else "xl/$target"
            val bytes = files[path] ?: continue
            out[name] = rows(dom(bytes), shared)
        }
        return out
    }

    private fun dom(bytes: ByteArray): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        return f.newDocumentBuilder().parse(bytes.inputStream())
    }

    private fun textOf(e: Element, tag: String): String {
        val ts = e.getElementsByTagNameNS("*", tag)
        val sb = StringBuilder()
        for (i in 0 until ts.length) sb.append(ts.item(i).textContent)
        return sb.toString()
    }

    private fun sharedStrings(doc: org.w3c.dom.Document): List<String> {
        val si = doc.getElementsByTagNameNS("*", "si")
        return (0 until si.length).map { textOf(si.item(it) as Element, "t") }
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break
        }
        return n - 1
    }

    private fun rows(doc: org.w3c.dom.Document, shared: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        val rows = doc.getElementsByTagNameNS("*", "row")
        for (i in 0 until rows.length) {
            val row = rows.item(i) as Element
            val cells = row.getElementsByTagNameNS("*", "c")
            val vals = ArrayList<String>()
            for (j in 0 until cells.length) {
                val c = cells.item(j) as Element
                val idx = c.getAttribute("r").let { if (it.isEmpty()) vals.size else colIndex(it) }
                val v = when (c.getAttribute("t")) {
                    "s" -> textOf(c, "v").trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    "inlineStr" -> textOf(c, "t")
                    else -> textOf(c, "v")
                }
                while (vals.size <= idx) vals.add("")
                vals[idx] = v
            }
            out.add(vals)
        }
        return out
    }
}

// ======================= Разбор выгрузки =======================

data class ImpOp(
    val type: String, val date: Long, val category: String, val account: String,
    val amount: Double, val currency: String, val amountBase: Double, val baseCurrency: String,
    val tags: String, val comment: String, val key: String
)

data class ImpTransfer(
    val date: Long, val from: String, val to: String,
    val fromAmount: Double, val fromCur: String, val toAmount: Double, val toCur: String,
    val comment: String, val key: String
)

data class ParsedImport(
    val ops: List<ImpOp>,
    val transfers: List<ImpTransfer>,
    val accountCurrency: Map<String, String>,
    val fileBase: String?
)

object ImportParser {

    private fun norm(s: String) = s.trim().lowercase().replace('ё', 'е')

    fun parse(sheets: Map<String, List<List<String>>>, tz: TimeZone = TimeZone.getDefault()): ParsedImport {
        val ops = ArrayList<ImpOp>()
        val transfers = ArrayList<ImpTransfer>()
        val accCur = LinkedHashMap<String, String>()
        val seen = HashMap<String, Int>()
        var fileBase: String? = null

        fun keyed(base: String): String {
            val n = seen.getOrDefault(base, 0)
            seen[base] = n + 1
            return "$base#$n" // одинаковые строки в одном файле — разные покупки
        }

        for ((name, rows) in sheets) {
            val n = norm(name)
            val kind = when {
                n.contains("расход") || n.contains("expense") -> OpType.EXPENSE
                n.contains("доход") || n.contains("income") -> OpType.INCOME
                n.contains("перевод") || n.contains("transfer") -> "T"
                else -> continue
            }
            val hIdx = rows.indexOfFirst { r -> r.any { norm(it).startsWith("дата") || norm(it) == "date" } }
            if (hIdx < 0) continue
            val h = rows[hIdx].map { norm(it) }
            fun col(vararg names: String): Int = h.indexOfFirst { c -> names.any { c == it } }
            fun get(r: List<String>, i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""

            if (kind == "T") {
                val cDate = col("дата и время", "дата")
                val cFrom = col("исходящий счет")
                val cTo = col("входящий счет")
                val cFA = col("сумма в исходящей валюте счета")
                val cFC = col("валюта исходящего счета")
                val cTA = col("сумма во входящей валюте счета")
                val cTC = col("валюта входящего счета")
                val cCom = col("комментарий")
                for (r in rows.drop(hIdx + 1)) {
                    val date = parseDate(get(r, cDate), tz) ?: continue
                    val from = get(r, cFrom); val to = get(r, cTo)
                    val fa = num(get(r, cFA)) ?: continue
                    if (from.isEmpty() || to.isEmpty()) continue
                    val fc = get(r, cFC).uppercase()
                    val tc = get(r, cTC).uppercase()
                    val ta = num(get(r, cTA))
                    if (fc.isNotEmpty()) accCur.putIfAbsent(from, fc)
                    if (tc.isNotEmpty()) accCur.putIfAbsent(to, tc)
                    val com = get(r, cCom)
                    transfers.add(
                        ImpTransfer(
                            date, from, to, fa, fc, ta ?: fa, tc, com,
                            keyed("T|${Rates.day(date)}|$from|$to|$fa|${ta ?: fa}|$com")
                        )
                    )
                }
            } else {
                val cDate = col("дата и время", "дата")
                val cCat = col("категория")
                val cAcc = col("счет", "account")
                val cBase = col("сумма в валюте учета")
                val cBaseCur = col("валюта учета")
                val cAmt = col("сумма в валюте счета")
                val cCur = col("валюта счета")
                val cTags = col("теги")
                val cCom = col("комментарий")
                for (r in rows.drop(hIdx + 1)) {
                    val date = parseDate(get(r, cDate), tz) ?: continue
                    val cat = get(r, cCat); val acc = get(r, cAcc)
                    val amt = num(get(r, cAmt)) ?: num(get(r, cBase)) ?: continue
                    if (cat.isEmpty() || acc.isEmpty() || amt <= 0) continue
                    val baseCur = get(r, cBaseCur).uppercase()
                    val cur = get(r, cCur).uppercase().ifEmpty { baseCur }
                    if (fileBase == null && baseCur.isNotEmpty()) fileBase = baseCur
                    if (cur.isNotEmpty()) accCur.putIfAbsent(acc, cur)
                    val tags = get(r, cTags); val com = get(r, cCom)
                    ops.add(
                        ImpOp(
                            kind, date, cat, acc, amt, cur, num(get(r, cBase)) ?: 0.0, baseCur, tags, com,
                            keyed("${kind[0]}|${Rates.day(date)}|$acc|$cat|$amt|$cur|$com|$tags")
                        )
                    )
                }
            }
        }
        // Валюта входящего счёта не указана → та же, что у исходящего
        val fixed = transfers.map { t ->
            val fc = t.fromCur.ifEmpty { accCur[t.from] ?: "" }
            val tc = t.toCur.ifEmpty { accCur[t.to] ?: fc }
            accCur.putIfAbsent(t.from, fc)
            accCur.putIfAbsent(t.to, tc)
            t.copy(fromCur = fc, toCur = tc)
        }
        return ParsedImport(ops, fixed, accCur.filterValues { it.isNotEmpty() }, fileBase)
    }

    fun num(s: String): Double? {
        if (s.isBlank()) return null
        var t = s.replace(" ", "").replace(" ", "")
        if (t.contains(',') && !t.contains('.')) t = t.replace(',', '.') else t = t.replace(",", "")
        return t.toDoubleOrNull()
    }

    /** Число Excel (46344.0) или текст «18.11.2026» / «18.11.2026 14:30» → полночь (или время) по местному времени */
    fun parseDate(s: String, tz: TimeZone): Long? {
        if (s.isBlank()) return null
        s.toDoubleOrNull()?.let { serial ->
            if (serial < 1) return null
            val days = Math.floor(serial).toInt()
            val ms = Math.round((serial - days) * 86_400_000L)
            val c = Calendar.getInstance(tz)
            c.clear()
            c.set(1899, Calendar.DECEMBER, 30)
            c.add(Calendar.DAY_OF_YEAR, days)
            return c.timeInMillis + ms
        }
        for (p in listOf("dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy HH:mm", "dd.MM.yyyy", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")) {
            try {
                val f = SimpleDateFormat(p, Locale.US).apply { timeZone = tz; isLenient = false }
                return f.parse(s.trim())?.time
            } catch (_: Exception) {}
        }
        return null
    }
}

// ======================= Запись в базу =======================

data class ImportResult(
    val expenses: Int, val incomes: Int, val transfers: Int, val skipped: Int,
    val newCategories: Int, val newAccounts: Int, val accountIds: List<String>
)

object Importer {

    /** Иконка по названию категории или счёта */
    fun guessIcon(name: String, isAccount: Boolean = false): String {
        val n = name.lowercase()
        fun has(vararg w: String) = w.any { n.contains(it) }
        if (isAccount) return when {
            has("депозит", "deposit", "вклад") -> "savings"
            has("инвест", "invest", "акци") -> "invest"
            has("binance", "bybit", "huobi", "крипт", "usdt") -> "crypto"
            has("налич", "кошел", "такка", "донг", "йен", "доллар", "рубли", "cash") -> "wallet"
            has("gold", "карт", "card") -> "card"
            else -> "bank"
        }
        return when {
            has("продукт", "магазин", "супермаркет") -> "cart"
            has("кафе", "ресторан", "еда") -> "restaurant"
            has("такси", "транспорт", "проезд", "автобус") -> "taxi"
            has("бензин", "топливо", "азс") -> "fuel"
            has("самол", "авиа", "вьетнам", "путеш", "отпуск", "duty") -> "travel"
            has("fpv", "дрон") -> "drone"
            has("подар", "день рожд") || n.trim() == "др" -> "gift"
            has("цвет") -> "flower"
            has("рассроч", "кредит", "долг") -> "credit"
            has("daraz", "wildberries", "ozon", "aliexpress", "яндекс", "онлайн") -> "package"
            has("связь", "мобил", "интернет") -> "wifi"
            has("здоров", "аптек", "врач", "донор") -> "health"
            has("одежд", "экипир") -> "clothes"
            has("спорт") -> "sport"
            has("досуг", "игр", "кино", "развлеч") -> "game"
            has("электрон", "техник") -> "electronics"
            has("хими", "бытов") -> "chemistry"
            has("дом", "квартир", "жкх", "стройм") -> "home"
            has("gpt", "code", "подписк", "play market") -> "phone"
            has("документ", "комисси") -> "bills"
            has("парикмах", "красот") -> "beauty"
            has("мама", "семь", "дет") -> "kids"
            has("зарплат", "систем", "завод", "тоо", "ао ", "\"") -> "salary"
            has("подработ", "работа") -> "work"
            has("социал", "помощ", "карман") -> "help"
            has("возврат") -> "refund"
            has("процент") -> "bank"
            else -> "other"
        }
    }

    suspend fun apply(dao: MonetaDao, p: ParsedImport, base: String, progress: (String) -> Unit): ImportResult {
        progress("Счета и категории…")
        val accounts = dao.accountsOnce().associateBy { it.name.trim().lowercase() }.toMutableMap()
        val cats = dao.categoriesOnce().associateBy { "${it.type}|${it.name.trim().lowercase()}" }.toMutableMap()
        var newAcc = 0
        var newCat = 0
        var colorIdx = 0
        val palette = listOf(0xFFF2B655, 0xFFE8774A, 0xFFE5534B, 0xFFE96A8D, 0xFFD96BB5, 0xFFC59BE8, 0xFF8C7AE6,
            0xFF5B8DEF, 0xFF4E9BE8, 0xFF3FC1C9, 0xFF4CC38A, 0xFF7BC86C, 0xFF8FD36B, 0xFFE0C04C)

        suspend fun account(name: String): AccountEntity {
            accounts[name.trim().lowercase()]?.let { return it }
            val a = AccountEntity(
                name = name.trim(), currency = p.accountCurrency[name] ?: base,
                icon = guessIcon(name, true), color = palette[colorIdx++ % palette.size], sort = 100 + newAcc
            )
            dao.upsertAccount(a); newAcc++
            accounts[name.trim().lowercase()] = a
            return a
        }

        suspend fun category(name: String, type: String): CategoryEntity {
            val k = "$type|${name.trim().lowercase()}"
            cats[k]?.let { return it }
            val c = CategoryEntity(
                name = name.trim(), type = type, icon = guessIcon(name),
                color = palette[colorIdx++ % palette.size], sort = 100 + newCat
            )
            dao.upsertCategory(c); newCat++
            cats[k] = c
            return c
        }

        val opKeys = dao.operationImportKeys().toHashSet()
        val trKeys = dao.transferImportKeys().toHashSet()
        var skipped = 0
        var exp = 0
        var inc = 0
        val touched = LinkedHashSet<String>()
        val newOps = ArrayList<OperationEntity>()

        p.ops.forEachIndexed { i, o ->
            if (i % 25 == 0) progress("Операции: $i из ${p.ops.size}")
            if (o.key in opKeys) { skipped++; return@forEachIndexed }
            val a = account(o.account)
            val c = category(o.category, o.type)
            val cur = o.currency.ifEmpty { a.currency }
            var manual = false
            val rate = when {
                cur == base -> 1.0
                p.fileBase == base && o.amountBase > 0 -> { manual = true; o.amountBase / o.amount }
                else -> Rates.rate(dao, o.date, cur, base) ?: 1.0
            }
            newOps.add(
                OperationEntity(
                    type = o.type, amount = o.amount, currency = cur, amountBase = o.amount * rate, rate = rate,
                    accountId = a.id, categoryId = c.id, date = o.date, comment = o.comment, tags = o.tags,
                    source = Source.IMPORT, rateManual = manual, importKey = o.key
                )
            )
            touched.add(a.id)
            if (o.type == OpType.EXPENSE) exp++ else inc++
        }
        dao.upsertOperations(newOps)

        progress("Переводы…")
        val newTr = ArrayList<TransferEntity>()
        p.transfers.forEach { t ->
            if (t.key in trKeys) { skipped++; return@forEach }
            val f = account(t.from)
            val to = account(t.to)
            newTr.add(
                TransferEntity(
                    fromAccountId = f.id, toAccountId = to.id, fromAmount = t.fromAmount, toAmount = t.toAmount,
                    date = t.date, comment = t.comment, importKey = t.key
                )
            )
            touched.add(f.id); touched.add(to.id)
        }
        dao.upsertTransfers(newTr)

        return ImportResult(exp, inc, newTr.size, skipped, newCat, newAcc, touched.toList())
    }
}
