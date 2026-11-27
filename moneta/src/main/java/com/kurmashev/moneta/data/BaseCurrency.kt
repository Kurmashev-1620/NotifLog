package com.kurmashev.moneta.data

/** Смена основной валюты: все суммы «в основной валюте» пересчитываются по курсу на день операции */
object BaseCurrency {

    suspend fun change(dao: MonetaDao, old: String, new: String, progress: (Int, Int) -> Unit): Int {
        if (old == new) return 0
        val ops = dao.operationsOnce()
        val factorByDay = HashMap<String, Double?>()
        val latest = Rates.rate(dao, System.currentTimeMillis(), old, new) ?: 1.0
        val updated = ArrayList<OperationEntity>()
        ops.forEachIndexed { i, o ->
            if (i % 20 == 0) progress(i, ops.size)
            val newBase = if (o.currency == new) o.amount else {
                // старая сумма в основной × курс старая→новая на тот день (сохраняет ручные курсы)
                val d = Rates.day(o.date)
                val f = factorByDay.getOrPut(d) { Rates.rate(dao, o.date, old, new) } ?: latest
                o.amountBase * f
            }
            updated.add(
                o.copy(
                    amountBase = newBase,
                    rate = if (o.amount > 0) newBase / o.amount else 1.0,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        dao.upsertOperations(updated)
        progress(ops.size, ops.size)
        return updated.size
    }
}
