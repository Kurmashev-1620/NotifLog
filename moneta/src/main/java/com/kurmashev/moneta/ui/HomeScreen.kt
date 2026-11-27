package com.kurmashev.moneta.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kurmashev.moneta.MonetaApp
import com.kurmashev.moneta.data.*

data class CatSlice(val category: CategoryEntity, val sum: Double, val share: Double)

@Composable
fun HomeScreen(onAdd: (String) -> Unit) {
    val dao = MonetaApp.dao
    val base = MonetaApp.instance.prefs.baseCurrency

    var expense by rememberSaveable { mutableStateOf(true) }
    var ptype by rememberSaveable { mutableStateOf(PeriodType.MONTH) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val r = remember(ptype, offset) { range(ptype, offset) }

    val periodOps by remember(r) { dao.operations(r.from, r.to) }.collectAsState(emptyList())
    val allOps by remember { dao.allOperations() }.collectAsState(emptyList())
    val accounts by remember { dao.accounts() }.collectAsState(emptyList())
    val transfers by remember { dao.transfers() }.collectAsState(emptyList())
    val cats by remember { dao.categories() }.collectAsState(emptyList())

    // Итого по всем счетам (пока счета в других валютах считаются по курсу 1 — курсы будут в шаге 1.4)
    val bal = balances(accounts, allOps, transfers)
    val total = accounts.filter { !it.hidden }.sumOf { bal[it.id] ?: 0.0 }

    val type = if (expense) OpType.EXPENSE else OpType.INCOME
    val byCat = periodOps.filter { it.type == type }.groupBy { it.categoryId }
        .mapValues { e -> e.value.sumOf { it.amountBase } }
    val periodSum = byCat.values.sum()
    val slices = byCat.mapNotNull { (id, s) ->
        cats.firstOrNull { it.id == id }?.let { CatSlice(it, s, if (periodSum > 0) s / periodSum else 0.0) }
    }.sortedByDescending { it.sum }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            // ---- Шапка ----
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
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        HeaderTab("РАСХОДЫ", expense) { expense = true }
                        HeaderTab("ДОХОДЫ", !expense) { expense = false }
                    }
                }
            }
            // ---- Карточка с периодом и диаграммой ----
            item {
                Column(
                    Modifier.padding(horizontal = 14.dp).offset(y = (-36).dp)
                        .clip(RoundedCornerShape(24.dp)).background(C.Card).padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        PeriodType.values().forEach { p ->
                            val sel = p == ptype
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { ptype = p; offset = 0 }.padding(6.dp)) {
                                Text(p.label, color = if (sel) C.Mint else C.Dim, fontSize = 15.sp,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.width(40.dp).height(3.dp).clip(CircleShape)
                                    .background(if (sel) C.Mint else Color.Transparent))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ offset-- }) { Icon(Icons.Filled.ChevronLeft, "Назад", tint = C.Dim) }
                        Text(r.label, color = C.Text, fontSize = 16.sp, modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        IconButton({ if (offset < 0) offset++ }, enabled = offset < 0) {
                            Icon(Icons.Filled.ChevronRight, "Вперёд", tint = if (offset < 0) C.Dim else Color.Transparent)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Donut(slices, Modifier.size(220.dp)) {
                        Text(moneyShort(periodSum, base), color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        if (slices.isEmpty()) Text("нет операций", color = C.Dim, fontSize = 12.sp)
                    }
                }
            }
            // ---- Категории ----
            items(slices, key = { it.category.id }) { s ->
                CategoryRow(s, base, Modifier.offset(y = (-24).dp))
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
private fun CategoryRow(s: CatSlice, base: String, modifier: Modifier) {
    Row(
        modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth()
            .clip(RoundedCornerShape(18.dp)).background(C.Card).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CatIcon(s.category.icon, s.category.color)
        Spacer(Modifier.width(12.dp))
        Text(s.category.name, color = C.Text, fontSize = 17.sp, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${Math.round(s.share * 100)}%", color = C.Dim, fontSize = 15.sp)
        Spacer(Modifier.width(10.dp))
        Text(money(s.sum, base), color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}
