package com.kurmashev.moneta

import com.kurmashev.moneta.data.*
import com.kurmashev.moneta.google.Dashboard
import com.kurmashev.moneta.google.GoogleSync
import com.kurmashev.moneta.google.SheetRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class GoogleTest {
    private val tz = TimeZone.getTimeZone("Asia/Dhaka")

    @Test fun serialDatesRoundTrip() {
        val c = Calendar.getInstance(tz).apply { clear(); set(2026, Calendar.NOVEMBER, 18, 14, 30) }
        val s = SheetRows.msToSerial(c.timeInMillis, tz)
        assertEquals(46344.604167, s, 0.000001) // 18.11.2026 14:30
        assertEquals(c.timeInMillis.toDouble(), SheetRows.serialToMs(s, tz).toDouble(), 1000.0)
    }

    @Test fun normalizationMatchesSheetsReadBack() {
        // мы пишем 5000.0, таблица возвращает 5000 — это одно и то же
        assertEquals(SheetRows.norm(5000.0), SheetRows.norm(5000))
        assertEquals(SheetRows.norm(2679.23), SheetRows.norm("2679.23"))
        assertEquals("", SheetRows.norm(null))
    }

    private val cat = CategoryEntity(id = "c1", name = "Продукты", type = OpType.EXPENSE, icon = "cart", color = 0xFFF2B655)
    private val acc = AccountEntity(id = "a1", name = "Такка", currency = "BDT")
    private val op = OperationEntity(
        id = "o1", type = OpType.EXPENSE, amount = 200.0, currency = "BDT", amountBase = 732.08, rate = 3.6604,
        accountId = "a1", categoryId = "c1", date = 1_795_000_000_000, comment = "колбаса", updatedAt = 1_795_000_000_000
    )

    @Test fun rowHashIgnoresUpdatedColumnAndDetectsEdits() {
        val row = SheetRows.opRow(op, mapOf("c1" to cat), mapOf("a1" to acc), tz)
        assertEquals(13, row.size)
        assertEquals("Расход", row[2])
        val h = SheetRows.hash(row, 12)
        // как придёт из таблицы: числа без «.0», колонка «Изменено» другая
        val readBack = row.toMutableList<Any?>().also { it[12] = 99999.0 }
        assertEquals(h, SheetRows.hash(readBack, 12))
        val edited = row.toMutableList<Any?>().also { it[3] = 250.0 }
        assertNotEquals(h, SheetRows.hash(edited, 12))
        // пустые хвостовые ячейки (таблица их не возвращает)
        val short = row.toMutableList<Any?>().also { it[10] = "" }.subList(0, 11)
        assertEquals(SheetRows.hash(row.toMutableList<Any?>().also { it[11] = ""; it[10] = "" }, 12), SheetRows.hash(short, 12))
    }

    @Test fun baseAmountAfterEdits() {
        // сумму поменяли, курс тот же → пересчёт по курсу
        assertEquals(915.1, GoogleSync.baseAmount(250.0, "BDT", "KZT", 3.6604, 732.08, op).first, 0.01)
        // поменяли сумму в тенге → курс пересчитывается
        val (b, r) = GoogleSync.baseAmount(200.0, "BDT", "KZT", 3.6604, 800.0, op)
        assertEquals(800.0, b, 0.001); assertEquals(4.0, r, 0.0001)
        // основная валюта
        assertEquals(1000.0, GoogleSync.baseAmount(1000.0, "KZT", "KZT", null, null, null).first, 0.001)
    }

    @Test fun dashboard() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 27, 12, 0) }
        val nov = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 10, 12, 0) }.timeInMillis
        val oct = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 10, 12, 0) }.timeInMillis
        val inc = CategoryEntity(id = "c2", name = "Зарплата", type = OpType.INCOME, icon = "salary", color = 0)
        val ops = listOf(
            op.copy(date = nov),
            op.copy(id = "o2", date = oct, amountBase = 1000.0),
            OperationEntity(id = "o3", type = OpType.INCOME, amount = 5000.0, currency = "KZT", amountBase = 5000.0,
                accountId = "a1", categoryId = "c2", date = nov)
        )
        val d = Dashboard.build(ops, listOf(cat, inc), listOf(acc), mapOf("a1" to 100.0),
            listOf(RateEntity("2026-11-27", "BDT", 120.0), RateEntity("2026-11-27", "KZT", 500.0)), "KZT", now)
        assertEquals(12, d.months.size)
        assertEquals("Ноя 26", d.months.last().label)
        assertEquals(732.08, d.months.last().expense, 0.01)
        assertEquals(5000.0, d.months.last().income, 0.01)
        assertEquals(1000.0, d.months[10].expense, 0.01)
        assertEquals(listOf("Продукты"), d.monthCats.map { it.first })
        assertEquals(1732.08, d.yearCats.first().second, 0.01)
        assertEquals(416.67, d.total, 0.01) // 100 ৳ / 120 * 500
    }

    @Test fun backupRoundTrip() {
        val json = Backup.toJson("KZT", listOf(acc), listOf(cat), listOf(op.copy(importKey = "k#0")), emptyList())
        val c = Backup.fromJson(json)
        assertEquals("KZT", c.base)
        assertEquals(op.copy(importKey = "k#0"), c.ops.single())
        assertEquals(acc, c.accounts.single())
        assertEquals(cat, c.categories.single())
    }
}
