package com.kurmashev.moneta.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kurmashev.moneta.MonetaApp
import com.kurmashev.moneta.data.*

data class CatSlice(val category: CategoryEntity, val sum: Double, val share: Double)

/** «Итого» по видимым счетам в основной валюте (по последним курсам) */
@Composable
fun rememberTotal(): Pair<Double, List<String>> {
    val dao = MonetaApp.dao
    val base = MonetaApp.instance.prefs.baseCurrency
    val accounts by remember { dao.accounts() }.collectAsState(emptyList())
    val ops by remember { dao.allOperations() }.collectAsState(emptyList())
    val transfers by remember { dao.transfers() }.collectAsState(emptyList())
    val rates by remember { dao.latestRates() }.collectAsState(emptyList())
    val bal = balances(accounts, ops, transfers)
    var total = 0.0
    val missing = ArrayList<String>()
    accounts.filter { !it.hidden }.forEach { a ->
        val b = bal[a.id] ?: 0.0
        val v = Rates.convertLatest(rates, b, a.currency, base)
        if (v != null) total += v else if (b != 0.0) missing.add(a.currency)
    }
    return total to missing.distinct()
}

@Composable
fun HomeScreen(onAdd: (String) -> Unit, onCategory: (String, PeriodType, Int, String?) -> Unit) {
    val dao = MonetaApp.dao
    val base = MonetaApp.instance.prefs.baseCurrency

    var expense by rememberSaveable { mutableStateOf(true) }
    var ptype by rememberSaveable { mutableStateOf(PeriodType.MONTH) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var curFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val r = remember(ptype, offset) { range(ptype, offset) }

    val periodOps by remember(r) { dao.operations(r.from, r.to) }.collectAsState(emptyList())
    val cats by remember { dao.categories() }.collectAsState(emptyList())
    val (total, missing) = rememberTotal()

    val type = if (expense) OpType.EXPENSE else OpType.INCOME
    val typeOps = periodOps.filter { it.type == type }
    val present = typeOps.map { it.currency }.distinct().sortedBy { if (it == base) "" else it }
    val filter = curFilter?.takeIf { it in present }
    val shown = if (filter == null) typeOps else typeOps.filter { it.currency == filter }
    val shownCur = filter ?: base
    val byCat = shown.groupBy { it.categoryId }
        .mapValues { e -> e.value.sumOf { if (filter == null) it.amountBase else it.amount } }
    val periodSum = byCat.values.sum()
    val slices = byCat.mapNotNull { (id, s) ->
        cats.firstOrNull { it.id == id }?.let { CatSlice(it, s, if (periodSum > 0) s / periodSum else 0.0) }
    }.sortedByDescending { it.sum }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                        .background(C.Header)
                        .statusBarsPadding()
                        .padding(top = 12.dp, bottom = 52.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Итого", color = C.Text.copy(alpha = 0.85f), fontSize = 16.sp)
                    Text(money(total, base), color = C.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    if (missing.isNotEmpty()) Text("нет курса: ${missing.joinToString()}", color = C.Dim, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        HeaderTab("РАСХОДЫ", expense) { expense = true }
                        HeaderTab("ДОХОДЫ", !expense) { expense = false }
                    }
                }
            }
            item {
                Column(
                    Modifier.padding(horizontal = 14.dp).offset(y = (-36).dp)
                        .clip(RoundedCornerShape(24.dp)).background(C.Card).padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PeriodTabs(ptype) { ptype = it; offset = 0 }
                    PeriodArrows(r.label, offset) { offset = it }
                    if (present.size > 1 || present.any { it != base }) {
                        CurrencyChips(present, filter, base, Modifier.padding(horizontal = 12.dp)) { curFilter = it }
                    }
                    Spacer(Modifier.height(6.dp))
                    Donut(slices, Modifier.size(220.dp)) {
                        Text(moneyShort(periodSum, shownCur), color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        if (slices.isEmpty()) Text("нет операций", color = C.Dim, fontSize = 12.sp)
                        else if (filter != null) Text("только ${symbol(filter)}", color = C.Dim, fontSize = 12.sp)
                    }
                }
            }
            items(slices, key = { it.category.id }) { s ->
                CategoryRow(s, shownCur, Modifier.offset(y = (-24).dp)) {
                    onCategory(s.category.id, ptype, offset, filter)
                }
            }
        }
        FloatingActionButton(
            onClick = { onAdd(type) },
            containerColor = C.Gold, contentColor = Color(0xFF1B1B1B), shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(64.dp)
        ) { Icon(Icons.Filled.Add, "Добавить", Modifier.size(32.dp)) }
    }
}

@Composable
fun PeriodTabs(selected: PeriodType, onSelect: (PeriodType) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        PeriodType.values().forEach { p ->
            val sel = p == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(p) }.padding(6.dp)) {
                Text(p.label, color = if (sel) C.Mint else C.Dim, fontSize = 15.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(40.dp).height(3.dp).clip(CircleShape)
                    .background(if (sel) C.Mint else Color.Transparent))
            }
        }
    }
}

@Composable
fun PeriodArrows(label: String, offset: Int, onOffset: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ onOffset(offset - 1) }) { Icon(Icons.Filled.ChevronLeft, "Назад", tint = C.Dim) }
        Text(label, color = C.Text, fontSize = 16.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        IconButton({ if (offset < 0) onOffset(offset + 1) }, enabled = offset < 0) {
            Icon(Icons.Filled.ChevronRight, "Вперёд", tint = if (offset < 0) C.Dim else Color.Transparent)
        }
    }
}

/** Фильтр по валютам: «Все в ₸» или только одна валюта */
@Composable
fun CurrencyChips(present: List<String>, selected: String?, base: String, modifier: Modifier = Modifier, onSelect: (String?) -> Unit) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("Все в ${symbol(base)}", selected == null) { onSelect(null) }
        present.forEach { c -> Chip("${symbol(c)} $c", selected == c) { onSelect(c) } }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color(0xFF1B1B1B) else C.Text, fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) C.Gold else C.CardHi)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun HeaderTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(8.dp)) {
        Text(text, color = if (selected) C.Text else C.Text.copy(alpha = 0.55f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(110.dp).height(3.dp).clip(CircleShape).background(if (selected) C.Text else Color.Transparent))
    }
}

@Composable
fun Donut(slices: List<CatSlice>, modifier: Modifier, center: @Composable ColumnScope.() -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.15f
            val d = size.minDimension - stroke
            val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
            if (slices.isEmpty()) {
                drawArc(C.CardHi, 0f, 360f, false, topLeft, Size(d, d), style = Stroke(stroke))
            } else {
                val gap = if (slices.size > 1) 1.5f else 0f
                var start = -90f
                slices.forEach { s ->
                    val sweep = (360f * s.share).toFloat()
                    if (sweep > gap) drawArc(Color(s.category.color), start + gap / 2, sweep - gap, false,
                        topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = center)
    }
}

@Composable
fun CatIcon(key: String, color: Long, size: Int = 44) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(color)), contentAlignment = Alignment.Center) {
        Icon(icon(key), null, tint = Color.White, modifier = Modifier.size((size * 0.55f).dp))
    }
}

@Composable
private fun CategoryRow(s: CatSlice, cur: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)).background(C.Card).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CatIcon(s.category.icon, s.category.color)
        Spacer(Modifier.width(12.dp))
        Text(s.category.name, color = C.Text, fontSize = 17.sp, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${Math.round(s.share * 100)}%", color = C.Dim, fontSize = 15.sp)
        Spacer(Modifier.width(10.dp))
        Text(money(s.sum, cur), color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** Строка операции: сумма в валюте покупки + «≈» в основной, если валюта другая */
@Composable
fun OpRow(o: OperationEntity, c: CategoryEntity?, subtitle: String, onClick: () -> Unit) {
    val base = MonetaApp.instance.prefs.baseCurrency
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
            .background(C.Card).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CatIcon(c?.icon ?: "other", c?.color ?: 0xFF9AA5A0, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c?.name ?: "Без категории", color = C.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank())
                Text(subtitle, color = C.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            val inc = o.type == OpType.INCOME
            Text(
                (if (inc) "+" else "−") + money(o.amount, o.currency),
                color = if (inc) C.Income else C.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium
            )
            if (o.currency != base) Text("≈ ${money(o.amountBase, base)}", color = C.Dim, fontSize = 12.sp)
        }
    }
}
