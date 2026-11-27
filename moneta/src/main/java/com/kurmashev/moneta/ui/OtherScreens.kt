package com.kurmashev.moneta.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kurmashev.moneta.MonetaApp
import com.kurmashev.moneta.data.*
import kotlinx.coroutines.launch

@Composable
fun ScreenTitle(text: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(C.Header).statusBarsPadding().padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
}

// ======================= Операции (лента по дням) =======================

@Composable
fun OperationsScreen(onEdit: (String) -> Unit) {
    val dao = MonetaApp.dao
    val ops by remember { dao.allOperations() }.collectAsState(emptyList())
    val cats by remember { dao.categories() }.collectAsState(emptyList())
    val accounts by remember { dao.accounts() }.collectAsState(emptyList())
    val catMap = cats.associateBy { it.id }
    val accMap = accounts.associateBy { it.id }
    val days = ops.groupBy { startOfDay(it.date) }.toSortedMap(compareByDescending { it })

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Операции")
        if (ops.isEmpty()) {
            Empty("Операций пока нет", "Нажмите «+» на главной, чтобы добавить первую")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            days.forEach { (day, list) ->
                item(key = "d$day") {
                    val exp = list.filter { it.type == OpType.EXPENSE }.sumOf { it.amountBase }
                    val inc = list.filter { it.type == OpType.INCOME }.sumOf { it.amountBase }
                    val base = MonetaApp.instance.prefs.baseCurrency
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp, start = 4.dp, end = 4.dp)) {
                        Text(dayTitle(day), color = C.Dim, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (inc > 0) Text("+${money(inc, base)}  ", color = C.Income, fontSize = 13.sp)
                        if (exp > 0) Text("−${money(exp, base)}", color = C.Expense, fontSize = 13.sp)
                    }
                }
                items(list, key = { it.id }) { o ->
                    val sub = listOfNotNull(accMap[o.accountId]?.name, o.comment.ifBlank { null }).joinToString(" · ")
                    OpRow(o, catMap[o.categoryId], sub) { onEdit(o.id) }
                }
            }
        }
    }
}

// ======================= Счета =======================

@Composable
fun AccountsScreen() {
    val dao = MonetaApp.dao
    val base = MonetaApp.instance.prefs.baseCurrency
    val accounts by remember { dao.accounts() }.collectAsState(emptyList())
    val ops by remember { dao.allOperations() }.collectAsState(emptyList())
    val transfers by remember { dao.transfers() }.collectAsState(emptyList())
    val bal = balances(accounts, ops, transfers)
    val total = rememberTotal()
    var dialog by remember { mutableStateOf<AccountEntity?>(null) }
    var newAcc by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                        .background(C.Header).statusBarsPadding().padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Счета · итого", color = C.Text.copy(alpha = 0.85f))
                    Text(money(total.first, base), color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("в ${symbol(base)} по сегодняшнему курсу", color = C.Dim, fontSize = 12.sp)
                }
            }
            items(accounts, key = { it.id }) { a ->
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp)).background(C.Card).clickable { dialog = a }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CatIcon(a.icon, a.color, 42)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, color = C.Text, fontSize = 17.sp)
                        if (a.hidden) Text("не учитывается в итого", color = C.Dim, fontSize = 12.sp)
                    }
                    Text(money(bal[a.id] ?: 0.0, a.currency), color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        FloatingActionButton(
            onClick = { newAcc = true }, containerColor = C.Gold, contentColor = Color(0xFF1B1B1B), shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(60.dp)
        ) { Icon(Icons.Filled.Add, "Новый счёт") }
    }

    if (newAcc) AccountDialog(null, base) { newAcc = false }
    dialog?.let { a -> AccountDialog(a, base) { dialog = null } }
}

@Composable
private fun AccountDialog(acc: AccountEntity?, base: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(acc?.name ?: "") }
    var cur by remember { mutableStateOf(acc?.currency ?: base) }
    var start by remember { mutableStateOf(acc?.initialBalance?.let { if (it == 0.0) "" else money(it).replace(" ", "") } ?: "") }
    var hidden by remember { mutableStateOf(acc?.hidden ?: false) }
    var iconKey by remember { mutableStateOf(acc?.icon ?: "card") }
    var curMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = C.Card,
        title = { Text(if (acc == null) "Новый счёт" else "Счёт", color = C.Text) },
        text = {
            Column {
                BasicField(name, { name = it }, "Название (Kaspi Gold, Наличные…)")
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton({ curMenu = true }) { Text("Валюта: ${cur} ${symbol(cur)}", color = C.Text) }
                    DropdownMenu(curMenu, { curMenu = false }) {
                        CURRENCIES.forEach { c ->
                            DropdownMenuItem(text = { Text("${c.code} ${c.symbol} · ${c.name}") }, onClick = { cur = c.code; curMenu = false })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                BasicField(start, { start = it }, "Начальный остаток")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("wallet", "card", "bank", "savings", "invest", "crypto").forEach { k ->
                        Box(Modifier.clip(CircleShape).clickable { iconKey = k }
                            .background(if (k == iconKey) C.Gold else Color.Transparent).padding(2.dp)) {
                            CatIcon(k, 0xFF2C4A3F, 34)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(hidden, { hidden = it })
                    Text("Не учитывать в «Итого»", color = C.Text)
                }
            }
        },
        confirmButton = {
            TextButton({
                if (name.isBlank()) return@TextButton
                val v = evalExpr(start) ?: 0.0
                scope.launch {
                    MonetaApp.dao.upsertAccount(
                        (acc ?: AccountEntity(name = name, currency = cur)).copy(
                            name = name.trim(), currency = cur, initialBalance = v, hidden = hidden,
                            icon = iconKey, updatedAt = System.currentTimeMillis()
                        )
                    )
                    onClose()
                }
            }) { Text("Сохранить", color = C.Gold) }
        },
        dismissButton = {
            Row {
                if (acc != null) TextButton({
                    scope.launch {
                        MonetaApp.dao.upsertAccount(acc.copy(deleted = true, updatedAt = System.currentTimeMillis()))
                        onClose()
                    }
                }) { Text("Удалить", color = C.Expense) }
                TextButton(onClose) { Text("Отмена", color = C.Dim) }
            }
        }
    )
}

// ======================= Чек (заглушка этапа 4) =======================

@Composable
fun ScanScreen() {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Сканер чеков")
        Empty("Скоро здесь", "Этап 4: камера → скан чека → нейросеть распознаёт магазин, товары и категории → вы подтверждаете")
    }
}

// ======================= Ещё / настройки =======================

@Composable
fun MoreScreen(onImport: () -> Unit) {
    var base by remember { mutableStateOf(MonetaApp.instance.prefs.baseCurrency) }
    var pick by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Ещё")
        Column(Modifier.padding(12.dp)) {
            MoreRow(Icons.Filled.CurrencyExchange, "Основная валюта", "$base ${symbol(base)} — нажмите, чтобы сменить") { pick = true }
            MoreRow(Icons.Filled.FileUpload, "Импорт из другого приложения", "xlsx: расходы, доходы, переводы", onImport)
            MoreRow(Icons.Filled.Category, "Категории", "редактор — следующий шаг") {}
            MoreRow(Icons.Filled.FileDownload, "Экспорт", "CSV / Excel — шаг 1.6") {}
            MoreRow(Icons.Filled.CloudSync, "Google Таблица и резервная копия", "этап 2") {}
            MoreRow(Icons.Filled.NotificationsActive, "Пуш-уведомления банков", "этап 3") {}
            MoreRow(Icons.Filled.Info, "О приложении", "Moneta 0.2") {}
        }
    }
    if (pick) AlertDialog(
        onDismissRequest = { pick = false }, containerColor = C.Card,
        title = { Text("Основная валюта", color = C.Text) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(CURRENCIES) { c ->
                    Text("${c.symbol}  ${c.code} · ${c.name}", color = if (c.code == base) C.Gold else C.Text,
                        modifier = Modifier.fillMaxWidth().clickable { pick = false; if (c.code != base) confirm = c.code }.padding(12.dp))
                }
            }
        },
        confirmButton = { TextButton({ pick = false }) { Text("Закрыть", color = C.Dim) } }
    )
    confirm?.let { nw ->
        AlertDialog(
            onDismissRequest = { confirm = null }, containerColor = C.Card,
            title = { Text("Сменить на $nw ${symbol(nw)}?", color = C.Text) },
            text = {
                Text("Итоги и диаграммы будут считаться в новой валюте. Все операции пересчитаются по курсу " +
                    "на день каждой операции (нужен интернет). Суммы в валюте покупки не изменятся.", color = C.Dim)
            },
            confirmButton = {
                TextButton({
                    val old = base
                    confirm = null
                    progress = "Пересчёт…"
                    scope.launch {
                        BaseCurrency.change(MonetaApp.dao, old, nw) { i, n -> progress = "Пересчёт: $i из $n" }
                        MonetaApp.instance.prefs.baseCurrency = nw
                        base = nw
                        progress = null
                    }
                }) { Text("Пересчитать", color = C.Gold) }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Отмена", color = C.Dim) } }
        )
    }
    progress?.let { p ->
        AlertDialog(onDismissRequest = {}, containerColor = C.Card, confirmButton = {},
            title = { Text("Подождите", color = C.Text) },
            text = { Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = C.Gold, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(14.dp)); Text(p, color = C.Text)
            } })
    }
}

@Composable
private fun MoreRow(ic: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(C.Card)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(ic, null, tint = C.Mint)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = C.Text, fontSize = 16.sp)
            Text(sub, color = C.Dim, fontSize = 13.sp)
        }
    }
}

@Composable
fun Empty(title: String, sub: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(sub, color = C.Dim, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
