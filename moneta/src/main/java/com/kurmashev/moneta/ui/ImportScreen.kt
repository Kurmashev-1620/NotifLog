package com.kurmashev.moneta.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kurmashev.moneta.MonetaApp
import com.kurmashev.moneta.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Импорт выгрузки xlsx (листы «Расходы», «Доходы», «Переводы»).
 * После импорта — ввод текущих остатков: начальный остаток счёта рассчитывается сам.
 */
@Composable
fun ImportScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = MonetaApp.dao
    val base = MonetaApp.instance.prefs.baseCurrency
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ImportResult?>(null) }
    var saved by remember { mutableStateOf(false) }

    val accounts by remember { dao.accounts() }.collectAsState(emptyList())
    val ops by remember { dao.allOperations() }.collectAsState(emptyList())
    val transfers by remember { dao.transfers() }.collectAsState(emptyList())
    val bal = balances(accounts, ops, transfers)
    val inputs = remember { mutableStateMapOf<String, String>() }

    fun run(uri: Uri) {
        error = null; result = null; saved = false
        status = "Читаю файл…"
        scope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val sheets = ctx.contentResolver.openInputStream(uri)!!.use { XlsxReader.read(it) }
                    ImportParser.parse(sheets)
                }
                if (parsed.ops.isEmpty() && parsed.transfers.isEmpty()) {
                    error = "В файле не найдено листов «Расходы», «Доходы» или «Переводы» с колонкой «Дата»"
                } else {
                    result = Importer.apply(dao, parsed, base) { status = it }
                }
            } catch (e: Exception) {
                error = "Не удалось прочитать файл: ${e.message}"
            }
            status = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run(uri)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(C.Header).statusBarsPadding().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "Назад", tint = C.Text) }
            Text("Импорт", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                InfoCard("Из другого приложения учёта финансов",
                    "Выберите файл Excel (.xlsx) с листами «Расходы», «Доходы» и «Переводы». " +
                        "Категории и счета, которых ещё нет, создадутся сами. Повторный импорт того же файла " +
                        "не создаст дубликатов — можно докачивать новые выгрузки.")
            }
            item {
                Button(
                    onClick = {
                        picker.launch(arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/octet-stream", "application/zip"
                        ))
                    },
                    enabled = status == null,
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Выбрать файл .xlsx")
                }
            }
            status?.let { s ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = C.Gold, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp)); Text(s, color = C.Text)
                    }
                }
            }
            error?.let { e -> item { Text(e, color = C.Expense) } }

            result?.let { r ->
                item {
                    InfoCard("Готово ✓",
                        "Расходов: ${r.expenses}\nДоходов: ${r.incomes}\nПереводов: ${r.transfers}\n" +
                            "Пропущено (уже были): ${r.skipped}\n" +
                            "Новых категорий: ${r.newCategories}, новых счетов: ${r.newAccounts}")
                }
                if (r.accountIds.isNotEmpty() && !saved) {
                    item {
                        InfoCard("Текущие остатки",
                            "В выгрузке нет начальных остатков. Впишите, сколько СЕЙЧАС на каждом счёте — " +
                                "приложение само рассчитает начальный остаток. Пустое поле — оставить как есть.")
                    }
                    val list = accounts.filter { it.id in r.accountIds }
                    items(list, key = { it.id }) { a ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CatIcon(a.icon, a.color, 36)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.name, color = C.Text, fontSize = 15.sp)
                                Text("по операциям: ${money(bal[a.id] ?: 0.0, a.currency)}", color = C.Dim, fontSize = 12.sp)
                            }
                            BasicField(inputs[a.id] ?: "", { inputs[a.id] = it }, symbol(a.currency), Modifier.width(130.dp))
                        }
                    }
                    item {
                        Button(
                            onClick = {
                                scope.launch {
                                    val upd = list.mapNotNull { a ->
                                        val want = evalExpr(inputs[a.id] ?: "") ?: return@mapNotNull null
                                        val now = bal[a.id] ?: 0.0
                                        a.copy(initialBalance = a.initialBalance + (want - now), updatedAt = System.currentTimeMillis())
                                    }
                                    dao.upsertAccounts(upd)
                                    saved = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)
                        ) { Text("Сохранить остатки") }
                    }
                }
                if (saved) item { InfoCard("Остатки сохранены ✓", "Балансы счетов теперь совпадают с реальными.") }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, text: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card).padding(14.dp)) {
        Text(title, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(text, color = C.Dim, fontSize = 14.sp)
    }
}
