package com.kurmashev.moneta.google

import android.content.Context
import com.kurmashev.moneta.MonetaApp
import com.kurmashev.moneta.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/*
 * Синхронизация с Google Таблицей пользователя + скрытая резервная копия.
 *
 * Порядок:
 *   1. найти таблицу (или создать, если её нет / её удалили);
 *   2. ЗАБРАТЬ из таблицы то, что пользователь поменял на компьютере (правки, новые строки, удаления);
 *   3. ЗАПИСАТЬ в таблицу всё из приложения + обновить дашборд;
 *   4. положить резервную копию в скрытую папку приложения на Диске (хранится 30 последних).
 */
object GoogleSync {
    private const val SHEETS = "https://sheets.googleapis.com/v4/spreadsheets"
    private const val DRIVE = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val OPS_W = 12 // колонки операций без «Изменено» — по ним ищем правки
    private const val KEEP_BACKUPS = 30

    private val mutex = Mutex()

    data class Result(val ok: Boolean, val message: String, val needSignIn: Boolean = false)

    suspend fun run(ctx: Context, token0: String? = null): Result = mutex.withLock {
        val prefs = MonetaApp.instance.prefs
        try {
            val token = token0 ?: GoogleAuth.silentToken(ctx)
                ?: return@withLock Result(false, "Нужно заново войти в Google", true).also { prefs.needSignIn = true }
            prefs.needSignIn = false
            if (prefs.googleEmail.isEmpty()) prefs.googleEmail = email(token) ?: ""

            val dao = MonetaApp.dao
            val base = prefs.baseCurrency
            val tz = TimeZone.getDefault()

            val (id, created) = ensureSpreadsheet(token, tz)
            val hashes = if (created) HashMap() else loadHashes(ctx, id)
            val pulled = if (created) 0 else pull(dao, token, id, base, tz, hashes)
            push(dao, token, id, base, tz, hashes)
            saveHashes(ctx, id, hashes)
            backup(dao, token, base)

            prefs.lastSync = System.currentTimeMillis()
            Result(true, when {
                created -> "Таблица создана и заполнена"
                pulled > 0 -> "Готово. Из таблицы принято изменений: $pulled"
                else -> "Синхронизировано"
            })
        } catch (e: GoogleApiException) {
            when {
                e.code == 401 -> { prefs.needSignIn = true; Result(false, "Нужно заново войти в Google", true) }
                e.code == 403 && (e.message ?: "").contains("disabled", true) ->
                    Result(false, "В Google Cloud не включены Google Sheets API или Google Drive API")
                else -> Result(false, "Ошибка Google: ${e.message?.take(160)}")
            }
        } catch (e: java.io.IOException) {
            Result(false, "Нет интернета — синхронизирую позже")
        } catch (e: Exception) {
            Result(false, "Ошибка: ${e.message?.take(160)}")
        }
    }

    // ======================= Таблица =======================

    private suspend fun email(token: String): String? = try {
        Http.json("GET", "$DRIVE/about?fields=user(emailAddress)", token)
            ?.optJSONObject("user")?.optString("emailAddress")
    } catch (_: Exception) { null }

    /** id таблицы и признак «только что создана» */
    private suspend fun ensureSpreadsheet(token: String, tz: TimeZone): Pair<String, Boolean> {
        val prefs = MonetaApp.instance.prefs
        val known = prefs.sheetId
        if (known.isNotEmpty()) {
            try {
                val f = Http.json("GET", "$DRIVE/files/$known?fields=id,trashed", token)
                if (f != null && !f.optBoolean("trashed")) return known to false
            } catch (e: GoogleApiException) {
                if (e.code != 404) throw e
            }
        }
        // После переустановки приложения таблица уже может существовать — находим её
        val q = Http.enc("appProperties has { key='moneta' and value='main' } and trashed=false")
        val found = Http.json("GET", "$DRIVE/files?q=$q&spaces=drive&fields=files(id)&pageSize=5", token)
            ?.optJSONArray("files")?.optJSONObject(0)?.optString("id")
        if (!found.isNullOrEmpty()) {
            prefs.sheetId = found
            prefs.sheetUrl = "https://docs.google.com/spreadsheets/d/$found"
            return found to false
        }
        return create(token, tz) to true
    }

    private fun sheet(id: Int, title: String, cols: Int, frozen: Int) = JSONObject().put(
        "properties", JSONObject().put("sheetId", id).put("title", title)
            .put("gridProperties", JSONObject().put("columnCount", cols).put("frozenRowCount", frozen))
    )

    private suspend fun create(token: String, tz: TimeZone): String {
        val prefs = MonetaApp.instance.prefs
        val body = JSONObject()
            .put("properties", JSONObject().put("title", "Moneta — мои финансы").put("locale", "ru_RU").put("timeZone", tz.id))
            .put("sheets", JSONArray(listOf(
                sheet(0, SheetRows.DASH, 16, 0),
                sheet(1, SheetRows.OPS, 13, 1),
                sheet(2, SheetRows.TRANSFERS, 9, 1),
                sheet(3, SheetRows.ACCOUNTS, 8, 1),
                sheet(4, SheetRows.CATEGORIES, 5, 1)
            )))
        val r = Http.json("POST", SHEETS, token, body)!!
        val id = r.getString("spreadsheetId")
        Http.json("PATCH", "$DRIVE/files/$id", token, JSONObject().put("appProperties", JSONObject().put("moneta", "main")))
        Http.json("POST", "$SHEETS/$id:batchUpdate", token, JSONObject().put("requests", SheetFormat.setup()))
        prefs.sheetId = id
        prefs.sheetUrl = r.optString("spreadsheetUrl", "https://docs.google.com/spreadsheets/d/$id")
        return id
    }

    private suspend fun read(token: String, id: String, range: String): List<List<Any?>> {
        val v = Http.json(
            "GET",
            "$SHEETS/$id/values/${Http.enc(range)}?valueRenderOption=UNFORMATTED_VALUE&dateTimeRenderOption=SERIAL_NUMBER",
            token
        )
        val arr = v?.optJSONArray("values") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val row = arr.getJSONArray(i)
            (0 until row.length()).map { j -> row.opt(j) }
        }
    }

    // ======================= Забрать изменения с компьютера =======================

    private suspend fun pull(
        dao: MonetaDao, token: String, id: String, base: String, tz: TimeZone, hashes: MutableMap<String, String>
    ): Int {
        var changes = 0
        val accs = dao.accountsOnce().toMutableList()
        val cats = dao.categoriesOnce().toMutableList()

        // Счета и категории: только добавляем отсутствующие (нужно при восстановлении на новом телефоне)
        for (r in read(token, id, "'${SheetRows.ACCOUNTS}'!A2:H")) {
            val aid = SheetRows.str(r.getOrNull(0)); val name = SheetRows.str(r.getOrNull(1))
            if (name.isEmpty() || accs.any { it.id == aid || it.name.equals(name, true) }) continue
            val a = AccountEntity(
                id = aid.ifEmpty { newId() }, name = name, currency = SheetRows.str(r.getOrNull(2)).uppercase().ifEmpty { base },
                initialBalance = SheetRows.num(r.getOrNull(3)) ?: 0.0, hidden = SheetRows.str(r.getOrNull(6)).isNotEmpty(),
                icon = SheetRows.str(r.getOrNull(7)).ifEmpty { Importer.guessIcon(name, true) }
            )
            dao.upsertAccount(a); accs.add(a); changes++
        }
        for (r in read(token, id, "'${SheetRows.CATEGORIES}'!A2:E")) {
            val cid = SheetRows.str(r.getOrNull(0)); val name = SheetRows.str(r.getOrNull(1))
            val type = SheetRows.typeFrom(SheetRows.str(r.getOrNull(2)))
            if (name.isEmpty() || cats.any { it.id == cid || (it.type == type && it.name.equals(name, true)) }) continue
            val color = SheetRows.str(r.getOrNull(4)).removePrefix("#").toLongOrNull(16)?.let { it or 0xFF000000 } ?: 0xFF9AA5A0
            val c = CategoryEntity(
                id = cid.ifEmpty { newId() }, name = name, type = type,
                icon = SheetRows.str(r.getOrNull(3)).ifEmpty { Importer.guessIcon(name) }, color = color
            )
            dao.upsertCategory(c); cats.add(c); changes++
        }

        suspend fun account(name: String, cur: String): AccountEntity {
            accs.firstOrNull { it.name.equals(name.trim(), true) }?.let { return it }
            val a = AccountEntity(name = name.trim().ifEmpty { "Без счёта" }, currency = cur.ifEmpty { base },
                icon = Importer.guessIcon(name, true))
            dao.upsertAccount(a); accs.add(a)
            return a
        }

        suspend fun category(name: String, type: String): CategoryEntity {
            cats.firstOrNull { it.type == type && it.name.equals(name.trim(), true) }?.let { return it }
            val c = CategoryEntity(name = name.trim().ifEmpty { "Другое" }, type = type, icon = Importer.guessIcon(name),
                color = PALETTE_FALLBACK[cats.size % PALETTE_FALLBACK.size])
            dao.upsertCategory(c); cats.add(c)
            return c
        }

        // Операции
        val rows = read(token, id, "'${SheetRows.OPS}'!A2:M")
        val local = dao.operationsOnce().associateBy { it.id }
        val seen = HashSet<String>()
        for (r in rows) {
            val oid = SheetRows.str(r.getOrNull(0))
            if (oid.isNotEmpty()) seen.add(oid)
            val h = SheetRows.hash(r, OPS_W)
            val last = if (oid.isEmpty()) null else hashes[oid]
            val existing = local[oid]
            when {
                oid.isNotEmpty() && last == h -> continue                 // не менялась
                oid.isNotEmpty() && existing == null && last != null -> continue // удалена в приложении
                oid.isNotEmpty() && existing != null && last == null -> continue // нет истории — верим приложению
            }
            val date = SheetRows.num(r.getOrNull(1))?.let { SheetRows.serialToMs(it, tz) } ?: continue
            val amount = SheetRows.num(r.getOrNull(3))?.takeIf { it > 0 } ?: continue
            val type = SheetRows.typeFrom(SheetRows.str(r.getOrNull(2)))
            val accName = SheetRows.str(r.getOrNull(8))
            val curCell = SheetRows.str(r.getOrNull(4)).uppercase()
            val acc = account(accName, curCell)
            val cur = curCell.ifEmpty { acc.currency }
            val cat = category(SheetRows.str(r.getOrNull(7)), type)
            val (amountBase, rate) = baseAmount(amount, cur, base, SheetRows.num(r.getOrNull(6)), SheetRows.num(r.getOrNull(5)), existing)
            val op = (existing ?: OperationEntity(
                id = oid.ifEmpty { newId() }, type = type, amount = amount, currency = cur, amountBase = amountBase,
                accountId = acc.id, categoryId = cat.id, date = date
            )).copy(
                type = type, amount = amount, currency = cur, amountBase = amountBase, rate = rate,
                accountId = acc.id, categoryId = cat.id, date = date,
                comment = SheetRows.str(r.getOrNull(9)), tags = SheetRows.str(r.getOrNull(10)),
                rateManual = cur != base, updatedAt = System.currentTimeMillis(), deleted = false
            )
            dao.upsertOperation(op)
            changes++
        }
        // Строку удалили в таблице → удаляем и в приложении (если таблицу не очистили целиком)
        if (rows.isNotEmpty()) {
            for (oid in hashes.keys) if (oid !in seen && local[oid] != null) {
                dao.deleteOperation(oid); changes++
            }
        }

        // Переводы: добавляем отсутствующие (восстановление)
        val trs = dao.transfersOnce().associateBy { it.id }
        for (r in read(token, id, "'${SheetRows.TRANSFERS}'!A2:I")) {
            val tid = SheetRows.str(r.getOrNull(0))
            if (tid.isEmpty() || trs.containsKey(tid)) continue
            val date = SheetRows.num(r.getOrNull(1))?.let { SheetRows.serialToMs(it, tz) } ?: continue
            val fa = SheetRows.num(r.getOrNull(4)) ?: continue
            val from = account(SheetRows.str(r.getOrNull(2)), SheetRows.str(r.getOrNull(5)).uppercase())
            val to = account(SheetRows.str(r.getOrNull(3)), SheetRows.str(r.getOrNull(7)).uppercase())
            dao.upsertTransfer(TransferEntity(tid, from.id, to.id, fa, SheetRows.num(r.getOrNull(6)) ?: fa, date,
                SheetRows.str(r.getOrNull(8))))
            changes++
        }
        return changes
    }

    private val PALETTE_FALLBACK = listOf(0xFFF2B655, 0xFFE8774A, 0xFF4E9BE8, 0xFF4CC38A, 0xFFC59BE8, 0xFF3FC1C9)

    /** Сумма в основной валюте после правки в таблице: смотрим, что именно поменяли — курс или сумму */
    fun baseAmount(amount: Double, cur: String, base: String, rateCell: Double?, baseCell: Double?, existing: OperationEntity?): Pair<Double, Double> {
        if (cur == base) return amount to 1.0
        val r = rateCell?.takeIf { it > 0 }
        val b = baseCell?.takeIf { it > 0 }
        if (existing == null) return when {   // новая строка с компьютера
            r != null -> amount * r to r
            b != null -> b to b / amount
            else -> amount to 1.0
        }
        val rateChanged = r != null && Math.abs(r - existing.rate) > 1e-9
        val baseChanged = b != null && Math.abs(b - existing.amountBase) > 0.005
        val amountChanged = Math.abs(amount - existing.amount) > 0.001
        return when {
            rateChanged -> amount * r!! to r
            baseChanged -> b!! to b / amount
            amountChanged -> amount * existing.rate to existing.rate
            else -> existing.amountBase to existing.rate
        }
    }

    // ======================= Записать всё в таблицу =======================

    private suspend fun push(
        dao: MonetaDao, token: String, id: String, base: String, tz: TimeZone, hashes: MutableMap<String, String>
    ) {
        val accs = dao.accountsOnce()
        val cats = dao.categoriesOnce()
        val ops = dao.operationsOnce().sortedByDescending { it.date }
        val trs = dao.transfersOnce().sortedByDescending { it.date }
        val rates = dao.latestRates().first()
        val bal = balances(accs, ops, trs)
        val catMap = cats.associateBy { it.id }
        val accMap = accs.associateBy { it.id }

        val opRows = ops.map { SheetRows.opRow(it, catMap, accMap, tz) }
        hashes.clear()
        ops.forEachIndexed { i, o -> hashes[o.id] = SheetRows.hash(opRows[i], OPS_W) }

        val accRows = accs.map { a ->
            SheetRows.accountRow(a, bal[a.id] ?: 0.0, Rates.convertLatest(rates, bal[a.id] ?: 0.0, a.currency, base))
        }
        val dash = Dashboard.build(ops, cats, accs, bal, rates, base)

        Http.json("POST", "$SHEETS/$id/values:batchClear", token, JSONObject().put("ranges", JSONArray(listOf(
            "'${SheetRows.OPS}'!A1:M", "'${SheetRows.TRANSFERS}'!A1:I", "'${SheetRows.ACCOUNTS}'!A1:H",
            "'${SheetRows.CATEGORIES}'!A1:E", "'${SheetRows.DASH}'!A1:P80"
        ))))

        val data = JSONArray()
        fun put(range: String, rows: List<List<Any>>) {
            data.put(JSONObject().put("range", range).put("values", JSONArray(rows.map { JSONArray(it) })))
        }
        put("'${SheetRows.OPS}'!A1", listOf(SheetRows.opsHeader(base)) + opRows)
        put("'${SheetRows.TRANSFERS}'!A1", listOf(SheetRows.TRANSFERS_HEADER) + trs.map { SheetRows.transferRow(it, accMap, tz) })
        put("'${SheetRows.ACCOUNTS}'!A1", listOf(SheetRows.ACCOUNTS_HEADER) + accRows)
        put("'${SheetRows.CATEGORIES}'!A1", listOf(SheetRows.CATEGORIES_HEADER) + cats.map { SheetRows.categoryRow(it) })
        SheetFormat.dashboardValues(dash, base, System.currentTimeMillis(), tz).forEach { (range, rows) -> put(range, rows) }

        Http.json("POST", "$SHEETS/$id/values:batchUpdate", token,
            JSONObject().put("valueInputOption", "RAW").put("data", data))
    }

    // ======================= Резервные копии (скрытая папка приложения) =======================

    data class BackupFile(val id: String, val name: String)

    suspend fun listBackups(token: String): List<BackupFile> {
        val r = Http.json("GET", "$DRIVE/files?spaces=appDataFolder&fields=files(id,name)&pageSize=200&orderBy=name%20desc", token)
        val arr = r?.optJSONArray("files") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { BackupFile(it.getString("id"), it.getString("name")) }
            .filter { it.name.startsWith("moneta-backup-") }
    }

    suspend fun downloadBackup(token: String, fileId: String): String =
        Http.request("GET", "$DRIVE/files/$fileId?alt=media", token, null, null)

    private suspend fun backup(dao: MonetaDao, token: String, base: String) {
        val json = Backup.toJson(base, dao.accountsOnce(), dao.categoriesOnce(), dao.operationsOnce(), dao.transfersOnce())
        val name = "moneta-backup-${Rates.day(System.currentTimeMillis())}.json"
        val files = listBackups(token)
        val same = files.firstOrNull { it.name == name }
        if (same != null) {
            Http.request("PATCH", "$UPLOAD/files/${same.id}?uploadType=media", token, json.toByteArray(), "application/json")
        } else {
            val b = "moneta${System.nanoTime()}"
            val meta = JSONObject().put("name", name).put("parents", JSONArray(listOf("appDataFolder"))).toString()
            val body = "--$b\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
                "--$b\r\nContent-Type: application/json\r\n\r\n$json\r\n--$b--"
            Http.request("POST", "$UPLOAD/files?uploadType=multipart", token, body.toByteArray(), "multipart/related; boundary=$b")
        }
        // Храним только последние 30 дней
        (listBackups(token).sortedByDescending { it.name }).drop(KEEP_BACKUPS).forEach {
            try { Http.request("DELETE", "$DRIVE/files/${it.id}", token, null, null) } catch (_: Exception) {}
        }
    }

    // ======================= Хеши строк (что мы записали в прошлый раз) =======================

    private fun hashFile(ctx: Context, id: String) = File(ctx.filesDir, "sync_$id.json")

    private fun loadHashes(ctx: Context, id: String): MutableMap<String, String> {
        val f = hashFile(ctx, id)
        if (!f.exists()) return HashMap()
        return try {
            val o = JSONObject(f.readText())
            val m = HashMap<String, String>()
            o.keys().forEach { m[it] = o.getString(it) }
            m
        } catch (_: Exception) { HashMap() }
    }

    private fun saveHashes(ctx: Context, id: String, m: Map<String, String>) {
        hashFile(ctx, id).writeText(JSONObject(m as Map<*, *>).toString())
    }
}
