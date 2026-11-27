package com.kurmashev.moneta.data

import org.json.JSONArray
import org.json.JSONObject

/** Полная копия данных в JSON (скрытая папка на Google Диске) и восстановление слиянием */
object Backup {

    fun toJson(
        base: String, accounts: List<AccountEntity>, categories: List<CategoryEntity>,
        ops: List<OperationEntity>, transfers: List<TransferEntity>
    ): String {
        val o = JSONObject()
        o.put("app", "Moneta"); o.put("version", 1); o.put("base", base); o.put("created", System.currentTimeMillis())
        o.put("accounts", JSONArray(accounts.map { a ->
            JSONObject().put("id", a.id).put("name", a.name).put("currency", a.currency)
                .put("initialBalance", a.initialBalance).put("icon", a.icon).put("color", a.color)
                .put("hidden", a.hidden).put("sort", a.sort).put("updatedAt", a.updatedAt).put("deleted", a.deleted)
        }))
        o.put("categories", JSONArray(categories.map { c ->
            JSONObject().put("id", c.id).put("name", c.name).put("type", c.type).put("icon", c.icon)
                .put("color", c.color).put("parentId", c.parentId ?: JSONObject.NULL).put("sort", c.sort)
                .put("updatedAt", c.updatedAt).put("deleted", c.deleted)
        }))
        o.put("operations", JSONArray(ops.map { p ->
            JSONObject().put("id", p.id).put("type", p.type).put("amount", p.amount).put("currency", p.currency)
                .put("amountBase", p.amountBase).put("rate", p.rate).put("accountId", p.accountId)
                .put("categoryId", p.categoryId).put("date", p.date).put("comment", p.comment).put("tags", p.tags)
                .put("source", p.source).put("receiptId", p.receiptId ?: JSONObject.NULL)
                .put("updatedAt", p.updatedAt).put("deleted", p.deleted).put("rateManual", p.rateManual)
                .put("importKey", p.importKey ?: JSONObject.NULL)
        }))
        o.put("transfers", JSONArray(transfers.map { t ->
            JSONObject().put("id", t.id).put("fromAccountId", t.fromAccountId).put("toAccountId", t.toAccountId)
                .put("fromAmount", t.fromAmount).put("toAmount", t.toAmount).put("date", t.date)
                .put("comment", t.comment).put("updatedAt", t.updatedAt).put("deleted", t.deleted)
                .put("importKey", t.importKey ?: JSONObject.NULL)
        }))
        return o.toString()
    }

    data class Content(
        val base: String, val accounts: List<AccountEntity>, val categories: List<CategoryEntity>,
        val ops: List<OperationEntity>, val transfers: List<TransferEntity>
    )

    private fun JSONObject.strOrNull(k: String): String? = if (isNull(k) || !has(k)) null else getString(k)
    private fun <T> JSONArray?.list(f: (JSONObject) -> T): List<T> =
        if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }

    fun fromJson(s: String): Content {
        val o = JSONObject(s)
        return Content(
            o.optString("base", ""),
            o.optJSONArray("accounts").list { a ->
                AccountEntity(a.getString("id"), a.getString("name"), a.getString("currency"), a.optDouble("initialBalance", 0.0),
                    a.optString("icon", "wallet"), a.optLong("color", 0xFF5CC8A0), a.optBoolean("hidden"), a.optInt("sort"),
                    a.optLong("updatedAt"), a.optBoolean("deleted"))
            },
            o.optJSONArray("categories").list { c ->
                CategoryEntity(c.getString("id"), c.getString("name"), c.getString("type"), c.optString("icon", "other"),
                    c.optLong("color", 0xFF9AA5A0), c.strOrNull("parentId"), c.optInt("sort"), c.optLong("updatedAt"), c.optBoolean("deleted"))
            },
            o.optJSONArray("operations").list { p ->
                OperationEntity(p.getString("id"), p.getString("type"), p.getDouble("amount"), p.getString("currency"),
                    p.getDouble("amountBase"), p.optDouble("rate", 1.0), p.getString("accountId"), p.getString("categoryId"),
                    p.getLong("date"), p.optString("comment", ""), p.optString("tags", ""), p.optString("source", Source.MANUAL),
                    p.strOrNull("receiptId"), p.optLong("updatedAt"), p.optBoolean("deleted"), p.optBoolean("rateManual"),
                    p.strOrNull("importKey"))
            },
            o.optJSONArray("transfers").list { t ->
                TransferEntity(t.getString("id"), t.getString("fromAccountId"), t.getString("toAccountId"),
                    t.getDouble("fromAmount"), t.getDouble("toAmount"), t.getLong("date"), t.optString("comment", ""),
                    t.optLong("updatedAt"), t.optBoolean("deleted"), t.strOrNull("importKey"))
            }
        )
    }

    /** Слияние: из копии берём запись, если её нет на телефоне или в копии она новее. Ничего не удаляем. */
    suspend fun restore(dao: MonetaDao, c: Content): Int {
        var n = 0
        val accs = dao.accountsOnce().associateBy { it.id }
        val cats = dao.categoriesOnce().associateBy { it.id }
        val ops = dao.operationsOnce().associateBy { it.id }
        val trs = dao.transfersOnce().associateBy { it.id }
        val a = c.accounts.filter { (accs[it.id]?.updatedAt ?: -1) < it.updatedAt }
        val k = c.categories.filter { (cats[it.id]?.updatedAt ?: -1) < it.updatedAt }
        val o = c.ops.filter { (ops[it.id]?.updatedAt ?: -1) < it.updatedAt }
        val t = c.transfers.filter { (trs[it.id]?.updatedAt ?: -1) < it.updatedAt }
        dao.upsertAccounts(a); dao.upsertCategories(k); dao.upsertOperations(o); dao.upsertTransfers(t)
        n += a.size + k.size + o.size + t.size
        return n
    }
}
