package com.kurmashev.moneta.ui

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kurmashev.moneta.MonetaApp
import com.kurmashev.moneta.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

/** Добавление и редактирование операции: сетка категорий + калькулятор */
@Composable
fun AddScreen(initialType: String, editId: String?, onDone: () -> Unit) {
    val dao = MonetaApp.dao
    val prefs = MonetaApp.instance.prefs
    val base = prefs.baseCurrency
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val cats by remember { dao.categories() }.collectAsState(emptyList())
    val accounts by remember { dao.accounts() }.collectAsState(emptyList())

    var type by remember { mutableStateOf(initialType) }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var accountId by remember { mutableStateOf(prefs.lastAccountId) }
    var expr by remember { mutableStateOf("") }
    var date by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var comment by remember { mutableStateOf("") }
    var rateText by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<OperationEntity?>(null) }
    var accMenu by remember { mutableStateOf(false) }

    // Загружаем операцию для редактирования
    LaunchedEffect(editId) {
        if (editId != null) {
            dao.allOperations().first().firstOrNull { it.id == editId }?.let { o ->
                editing = o; type = o.type; categoryId = o.categoryId; accountId = o.accountId
                expr = money(o.amount).replace(" ", ""); date = o.date; comment = o.comment
                if (o.rate != 1.0) rateText = o.rate.toString()
            }
        }
    }

    val account = accounts.firstOrNull { it.id == accountId } ?: accounts.firstOrNull()
    val accCur = account?.currency ?: base
    val needRate = accCur != base
    val typeCats = cats.filter { it.type == type && it.parentId == null }

    fun save() {
        val amount = evalExpr(expr)
        when {
            amount == null || amount <= 0 -> { Toast.makeText(ctx, "Введите сумму", Toast.LENGTH_SHORT).show(); return }
            categoryId == null -> { Toast.makeText(ctx, "Выберите категорию", Toast.LENGTH_SHORT).show(); return }
            account == null -> { Toast.makeText(ctx, "Нет счёта", Toast.LENGTH_SHORT).show(); return }
        }
        val rate = if (needRate) (rateText.replace(',', '.').toDoubleOrNull() ?: 1.0) else 1.0
        val op = (editing ?: OperationEntity(
            type = type, amount = 0.0, currency = accCur, amountBase = 0.0,
            accountId = account!!.id, categoryId = categoryId!!, date = date
        )).copy(
            type = type, amount = amount!!, currency = accCur, amountBase = amount * rate, rate = rate,
            accountId = account!!.id, categoryId = categoryId!!, date = date, comment = comment.trim(),
            updatedAt = System.currentTimeMillis()
        )
        scope.launch {
            dao.upsertOperation(op)
            prefs.lastAccountId = account.id
            onDone()
        }
    }

    Column(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding()) {
        // ---- Верх: назад, тип, счёт ----
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onDone) { Icon(Icons.Filled.Close, "Закрыть", tint = C.Text) }
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(C.Card)) {
                TypeChip("Расход", type == OpType.EXPENSE) { type = OpType.EXPENSE; categoryId = null }
                TypeChip("Доход", type == OpType.INCOME) { type = OpType.INCOME; categoryId = null }
            }
            Spacer(Modifier.weight(1f))
            if (editing != null) IconButton({
                scope.launch { dao.deleteOperation(editing!!.id); onDone() }
            }) { Icon(Icons.Filled.DeleteOutline, "Удалить", tint = C.Expense) }
        }
        Box(Modifier.padding(horizontal = 16.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(C.Card).clickable { accMenu = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon(account?.icon ?: "wallet"), null, tint = C.Mint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(account?.name ?: "—", color = C.Text)
                Icon(Icons.Filled.ArrowDropDown, null, tint = C.Dim)
            }
            DropdownMenu(accMenu, { accMenu = false }) {
                accounts.forEach { a ->
                    DropdownMenuItem(text = { Text("${a.name} · ${symbol(a.currency)}") },
                        onClick = { accountId = a.id; accMenu = false })
                }
            }
        }

        // ---- Сетка категорий ----
        LazyVerticalGrid(
            GridCells.Fixed(4), Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            items(typeCats, key = { it.id }) { c ->
                val sel = c.id == categoryId
                Column(
                    Modifier.padding(4.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (sel) C.CardHi else Color.Transparent)
                        .clickable { categoryId = c.id }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        if (sel) Modifier.border(2.dp, C.Gold, CircleShape).padding(3.dp) else Modifier.padding(3.dp)
                    ) { CatIcon(c.icon, c.color, 46) }
                    Spacer(Modifier.height(4.dp))
                    Text(c.name, color = if (sel) C.Text else C.Dim, fontSize = 12.sp, maxLines = 2,
                        textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // ---- Сумма, комментарий, дата ----
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(C.Card).padding(12.dp)) {
            val color = if (type == OpType.EXPENSE) C.Expense else C.Income
            Text(
                (if (expr.isEmpty()) "0" else expr) + " " + symbol(accCur),
                color = color, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End, maxLines = 1
            )
            if (needRate) {
                val a = evalExpr(expr)
                val rate = rateText.replace(',', '.').toDoubleOrNull()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Курс 1 ${symbol(accCur)} =", color = C.Dim, fontSize = 13.sp)
                    BasicField(rateText, { rateText = it }, "курс", Modifier.width(90.dp))
                    Text(symbol(base) + if (a != null && rate != null) "  → ${money(a * rate, base)}" else "",
                        color = C.Dim, fontSize = 13.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicField(comment, { comment = it }, "Комментарий", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                val cal = Calendar.getInstance().apply { timeInMillis = date }
                AssistChip(
                    onClick = {
                        DatePickerDialog(ctx, { _, y, m, d ->
                            val c2 = Calendar.getInstance().apply { timeInMillis = date; set(y, m, d) }
                            date = c2.timeInMillis
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    },
                    label = { Text(dayTitle(date).substringBefore(","), color = C.Text) },
                    leadingIcon = { Icon(Icons.Filled.CalendarMonth, null, tint = C.Mint) }
                )
            }
            Spacer(Modifier.height(6.dp))
            Keypad(
                onKey = { k ->
                    expr = when (k) {
                        "⌫" -> expr.dropLast(1)
                        "+", "−" -> if (expr.isEmpty() || expr.last() in "+−") expr else expr + k
                        "," -> if (expr.takeLastWhile { it !in "+−" }.contains(',')) expr else expr + if (expr.isEmpty() || expr.last() in "+−") "0," else ","
                        else -> if (expr.length < 18) expr + k else expr
                    }
                },
                onSave = { save() }
            )
        }
    }
}

@Composable
private fun TypeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color(0xFF1B1B1B) else C.Dim, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) C.Gold else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp)
    )
}

@Composable
fun BasicField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = modifier,
        placeholder = { Text(hint, color = C.Dim) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.Mint, unfocusedBorderColor = C.CardHi,
            focusedTextColor = C.Text, unfocusedTextColor = C.Text, cursorColor = C.Mint
        ),
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
private fun Keypad(onKey: (String) -> Unit, onSave: () -> Unit) {
    val rows = listOf(
        listOf("7", "8", "9", "⌫"),
        listOf("4", "5", "6", "+"),
        listOf("1", "2", "3", "−"),
        listOf(",", "0", "00", "✓")
    )
    rows.forEach { row ->
        Row(Modifier.fillMaxWidth()) {
            row.forEach { k ->
                val save = k == "✓"
                Box(
                    Modifier.weight(1f).padding(4.dp).height(56.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (save) C.Gold else C.CardHi)
                        .clickable { if (save) onSave() else onKey(k) },
                    contentAlignment = Alignment.Center
                ) {
                    when (k) {
                        "⌫" -> Icon(Icons.Filled.Backspace, "Стереть", tint = C.Text)
                        "✓" -> Icon(Icons.Filled.Check, "Сохранить", tint = Color(0xFF1B1B1B), modifier = Modifier.size(30.dp))
                        else -> Text(k, color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
