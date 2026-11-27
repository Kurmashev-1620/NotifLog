package com.kurmashev.moneta

import com.kurmashev.moneta.data.AccountEntity
import com.kurmashev.moneta.data.OpType
import com.kurmashev.moneta.data.OperationEntity
import com.kurmashev.moneta.data.PeriodType
import com.kurmashev.moneta.data.TransferEntity
import com.kurmashev.moneta.data.balances
import com.kurmashev.moneta.data.evalExpr
import com.kurmashev.moneta.data.money
import com.kurmashev.moneta.data.range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class LogicTest {

    @Test fun calculator() {
        assertEquals(1200.0, evalExpr("1000+250-50")!!, 0.001)
        assertEquals(12.5, evalExpr("12,5")!!, 0.001)
        assertEquals(10.75, evalExpr("10.25+0.5")!!, 0.001)
        assertNull(evalExpr(""))
    }

    @Test fun moneyFormat() {
        assertEquals("551 516,77", money(551516.77))
        assertEquals("10 000", money(10000.0))
    }

    @Test fun balancesWithTransfers() {
        val a = AccountEntity(id = "a", name = "A", currency = "KZT", initialBalance = 1000.0)
        val b = AccountEntity(id = "b", name = "B", currency = "BDT")
        val ops = listOf(
            OperationEntity(type = OpType.EXPENSE, amount = 200.0, currency = "KZT", amountBase = 200.0,
                accountId = "a", categoryId = "c", date = 0),
            OperationEntity(type = OpType.INCOME, amount = 50.0, currency = "KZT", amountBase = 50.0,
                accountId = "a", categoryId = "c", date = 0)
        )
        val tr = listOf(TransferEntity(fromAccountId = "a", toAccountId = "b", fromAmount = 300.0, toAmount = 70.0, date = 0))
        val m = balances(listOf(a, b), ops, tr)
        assertEquals(550.0, m["a"]!!, 0.001)
        assertEquals(70.0, m["b"]!!, 0.001)
    }

    @Test fun periods() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 27, 15, 0) }
        assertEquals("Ноябрь 2026", range(PeriodType.MONTH, 0, now).label)
        assertEquals("Октябрь 2026", range(PeriodType.MONTH, -1, now).label)
        assertEquals("23 ноя – 29 ноя", range(PeriodType.WEEK, 0, now).label)
        assertEquals("2025", range(PeriodType.YEAR, -1, now).label)
        val d = range(PeriodType.DAY, 0, now)
        assertEquals(86_400_000L, d.to - d.from)
    }
}
