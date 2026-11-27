package com.kurmashev.moneta

import com.kurmashev.moneta.data.ImportParser
import com.kurmashev.moneta.data.Importer
import com.kurmashev.moneta.data.OpType
import com.kurmashev.moneta.data.Rates
import com.kurmashev.moneta.data.RateEntity
import com.kurmashev.moneta.data.XlsxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Проверяем импорт на синтетическом файле той же структуры, что и выгрузка старого приложения */
class ImportTest {

    private val strings = listOf(
        "Список расходов", "Дата и время", "Категория", "Счет", "Сумма в валюте учета", "Валюта учета",
        "Сумма в валюте счета", "Валюта счета", "Сумма операции в валюте операции", "Валюта операции", "Теги", "Комментарий",
        "Кафе", "Такка", "KZT", "BDT", "", "Узбечка", "Продукты", "Kaspi Gold",
        // переводы
        "Исходящий счет", "Входящий счет", "Сумма в исходящей валюте счета", "Валюта исходящего счета",
        "Сумма во входящей валюте счета", "Валюта входящего счета", "Сбербанк", "RUB"
    )

    private fun s(i: Int) = "<c t=\"s\"><v>$i</v></c>"
    private fun n(v: String) = "<c><v>$v</v></c>"
    private fun row(vararg cells: String) = "<row>${cells.joinToString("")}</row>"

    private fun xlsx(): ByteArray {
        val sst = strings.joinToString("") { "<si><t>$it</t></si>" }
        val header = row(s(1), s(2), s(3), s(4), s(5), s(6), s(7), s(8), s(9), s(10), s(11))
        val sheet1 = row(s(0)) + header +
            // две одинаковые покупки в кафе в один день — обе должны импортироваться
            row(n("46344.0"), s(12), s(13), n("2679.23"), s(14), n("750.0"), s(15), s(16), s(16), s(17), s(16)) +
            row(n("46344.0"), s(12), s(13), n("2679.23"), s(14), n("750.0"), s(15), s(16), s(16), s(17), s(16)) +
            row(n("46343.0"), s(18), s(19), n("5000"), s(14), n("5000"), s(14), s(16), s(16), s(16), s(16))
        val trHeader = row(s(1), s(20), s(21), s(22), s(23), s(24), s(25), s(11))
        val sheet2 = row(s(0)) + trHeader +
            row(n("46344.0"), s(26), s(13), n("7100"), s(27), n("10000"), s(15), s(16)) +
            row(n("46343.0"), s(19), s(19), n("1000"), s(14), s(16), s(16), s(16))

        val files = mapOf(
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Расходы" sheetId="1" r:id="rId5"/><sheet name="Переводы" sheetId="3" r:id="rId7"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId5" Target="worksheets/sheet1.xml"/><Relationship Id="rId7" Target="worksheets/sheet3.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">$sst</sst>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$sheet1</sheetData></worksheet>""",
            "xl/worksheets/sheet3.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$sheet2</sheetData></worksheet>"""
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            files.forEach { (name, content) ->
                z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray()); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test fun readsAndParses() {
        val tz = TimeZone.getTimeZone("Asia/Dhaka")
        val sheets = XlsxReader.read(xlsx().inputStream())
        assertEquals(listOf("Расходы", "Переводы"), sheets.keys.toList())

        val p = ImportParser.parse(sheets, tz)
        assertEquals("KZT", p.fileBase)
        assertEquals(3, p.ops.size)
        val cafe = p.ops.filter { it.category == "Кафе" }
        assertEquals(2, cafe.size)
        assertNotEquals(cafe[0].key, cafe[1].key) // одинаковые строки — разные ключи
        assertEquals(750.0, cafe[0].amount, 0.001)
        assertEquals("BDT", cafe[0].currency)
        assertEquals(2679.23, cafe[0].amountBase, 0.001)
        assertEquals("Узбечка", cafe[0].tags)
        assertEquals(OpType.EXPENSE, cafe[0].type)

        // 46344 = 18 ноября 2026, полночь по местному времени
        val c = Calendar.getInstance(tz).apply { timeInMillis = cafe[0].date }
        assertEquals(2026, c.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, c.get(Calendar.MONTH))
        assertEquals(18, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, c.get(Calendar.HOUR_OF_DAY))

        assertEquals(2, p.transfers.size)
        val t = p.transfers.first { it.from == "Сбербанк" }
        assertEquals(10000.0, t.toAmount, 0.001)
        assertEquals("BDT", t.toCur)
        // входящая валюта не указана → та же, что у исходящего счёта
        val t2 = p.transfers.first { it.from == "Kaspi Gold" }
        assertEquals("KZT", t2.toCur)
        assertEquals(1000.0, t2.toAmount, 0.001)

        assertEquals("BDT", p.accountCurrency["Такка"])
        assertEquals("RUB", p.accountCurrency["Сбербанк"])
        assertEquals("KZT", p.accountCurrency["Kaspi Gold"])
    }

    @Test fun parsingIsStableForReimport() {
        val a = ImportParser.parse(XlsxReader.read(xlsx().inputStream()))
        val b = ImportParser.parse(XlsxReader.read(xlsx().inputStream()))
        assertEquals(a.ops.map { it.key }, b.ops.map { it.key })
    }

    @Test fun numbersAndDates() {
        assertEquals(2679.23, ImportParser.num("2679.23")!!, 0.001)
        assertEquals(1250.5, ImportParser.num("1 250,5")!!, 0.001)
        assertEquals(1250.5, ImportParser.num("1,250.5")!!, 0.001)
        val tz = TimeZone.getTimeZone("Asia/Almaty")
        val d = ImportParser.parseDate("18.11.2026", tz)!!
        val c = Calendar.getInstance(tz).apply { timeInMillis = d }
        assertEquals(18, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun icons() {
        assertEquals("cart", Importer.guessIcon("Продукты"))
        assertEquals("drone", Importer.guessIcon("FPV"))
        assertEquals("package", Importer.guessIcon("Daraz"))
        assertEquals("salary", Importer.guessIcon("\"СтройТех Система\" STS"))
        assertEquals("savings", Importer.guessIcon("Депозит KZT", true))
        assertEquals("crypto", Importer.guessIcon("Binance", true))
        assertEquals("wallet", Importer.guessIcon("Такка", true))
    }

    @Test fun rates() {
        val r = listOf(RateEntity("2026-11-27", "KZT", 500.0), RateEntity("2026-11-27", "BDT", 120.0))
        // 750 ৳ → ₸: 750 / 120 * 500 = 3125
        assertEquals(3125.0, Rates.convertLatest(r, 750.0, "BDT", "KZT")!!, 0.001)
        assertEquals(1.5, Rates.convertLatest(r, 750.0, "BDT", "USD")!! / 4.166666, 0.01)
        assertEquals(null, Rates.convertLatest(r, 1.0, "VND", "KZT"))
        val m = Rates.parse("""{"date":"2026-11-27","usd":{"kzt":500.5,"bdt":120}}""")
        assertEquals(500.5, m["KZT"]!!, 0.001)
        val m2 = Rates.parse("""{"rates":{"KZT":501,"USD":1}}""")
        assertTrue(m2.containsKey("KZT"))
    }
}
