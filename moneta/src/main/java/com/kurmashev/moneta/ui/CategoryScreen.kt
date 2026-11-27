package com.kurmashev.moneta.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kurmashev.moneta.MonetaApp
import com.kurmashev.moneta.data.*

/** Категория за период: итоги по каждой валюте + список операций с фильтром по валюте */
@Composable
fun CategoryScreen(
    categoryId: String, initialType: PeriodType, initialOffset: Int, initialCur: String?,
    onBack: () -> Unit, onEdit: (String) -> Unit
) {
    val dao = MonetaApp.dao
    val base = MonetaApp.instance.prefs.baseCurrency
    var ptype by rememberSaveable { mutableStateOf(initialType) }
    var offset by rememberSaveable { mutableIntStateOf(initialOffset) }
    var cur by rememberSaveable { mutableStateOf(initialCur) }
    val r = remember(ptype, offset) { range(ptype, offset) }

    val ops by remember(r) { dao.operations(r.from, r.to) }.collectAsState(emptyList())
    val cats by remember { dao.categories() }.collectAsState(emptyList())
    val accounts by remember { dao.accounts() }.collectAsState(emptyList())
    val cat = cats.firstOrNull { it.id == categoryId }
    val accMap = accounts.associateBy { it.id }

    val catOps = ops.filter { it.categoryId == categoryId }
    val perCur = catOps.groupBy { it.currency }.mapValues { e -> e.value.sumOf { it.amount } }
    val totalBase = catOps.sumOf { it.amountBase }
    val present = perCur.keys.sortedBy { if (it == base) "" else it }
    val filter = cur?.takeIf { it in present }
    val list = if (filter == null) catOps else catOps.filter { it.currency == filter }
    val days = list.groupBy { startOfDay(it.date) }.toSortedMap(compareByDescending { it })

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(C.Header).statusBarsPadding().padding(bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "Назад", tint = C.Text) }
                if (cat != null) CatIcon(cat.icon, cat.color, 34)
                Spacer(Modifier.width(10.dp))
                Text(cat?.name ?: "", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(
                money(if (filter == null) totalBase else perCur[filter] ?: 0.0, filter ?: base),
                color = C.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            // Сколько потрачено в каждой валюте
            if (present.size > 1 || present.any { it != base }) Text(
                present.joinToString("  ·  ") { money(perCur[it] ?: 0.0, it) },
                color = C.Text.copy(alpha = 0.8f), fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        Column(Modifier.padding(top = 8.dp)) {
            PeriodTabs(ptype) { ptype = it; offset = 0 }
            PeriodArrows(r.label, offset) { offset = it }
            if (present.size > 1 || present.any { it != base })
                CurrencyChips(present, filter, base, Modifier.padding(horizontal = 12.dp)) { cur = it }
        }
        if (list.isEmpty()) {
            Empty("Нет операций", "За этот период в категории ничего нет")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            days.forEach { (day, l) ->
                item(key = "d$day") {
                    Text(dayTitle(day), color = C.Dim, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 4.dp))
                }
                items(l, key = { it.id }) { o ->
                    val sub = listOfNotNull(
                        o.comment.ifBlank { null }, o.tags.ifBlank { null }?.let { "#$it" }, accMap[o.accountId]?.name
                    ).joinToString(" · ")
                    OpRow(o, cat, sub) { onEdit(o.id) }
                }
            }
        }
    }
}
