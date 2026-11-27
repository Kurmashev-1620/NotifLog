package com.kurmashev.moneta.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kurmashev.moneta.MonetaApp
import com.kurmashev.moneta.data.CURRENCIES
import com.kurmashev.moneta.data.PeriodType
import com.kurmashev.moneta.data.Rates
import com.kurmashev.moneta.data.Seed
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MonetaTheme {
                var base by remember { mutableStateOf(MonetaApp.instance.prefs.baseCurrency) }
                Surface(Modifier.fillMaxSize(), color = C.Bg) {
                    if (base.isEmpty()) OnboardingScreen { base = it } else AppRoot()
                }
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TABS = listOf(
    Tab("home", "Главная", Icons.Filled.DonutLarge),
    Tab("ops", "Операции", Icons.Filled.FormatListBulleted),
    Tab("scan", "Чек", Icons.Filled.DocumentScanner),
    Tab("accounts", "Счета", Icons.Filled.AccountBalanceWallet),
    Tab("more", "Ещё", Icons.Filled.Menu)
)

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    val showBar = TABS.any { it.route == route }

    // Раз в день подтягиваем свежие курсы валют
    LaunchedEffect(Unit) { try { Rates.refreshToday(MonetaApp.dao) } catch (_: Exception) {} }

    Scaffold(
        containerColor = C.Bg,
        // Отступ под статус-бар каждый экран делает сам — иначе он удваивается
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) NavigationBar(containerColor = C.Card) {
                TABS.forEach { t ->
                    val center = t.route == "scan"
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (center) Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(C.Gold),
                                contentAlignment = Alignment.Center
                            ) { Icon(t.icon, t.label, tint = Color(0xFF1B1B1B)) }
                            else Icon(t.icon, t.label)
                        },
                        label = { Text(t.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = C.Mint, selectedTextColor = C.Mint,
                            unselectedIconColor = C.Dim, unselectedTextColor = C.Dim,
                            indicatorColor = if (center) Color.Transparent else C.CardHi
                        )
                    )
                }
            }
        }
    ) { pad ->
        NavHost(
            nav, startDestination = "home", modifier = Modifier.padding(pad).background(C.Bg),
            // Без затухания — чтобы предыдущий экран не просвечивал
            enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }
        ) {
            composable("home") {
                HomeScreen(
                    onAdd = { type -> nav.navigate("add/$type") },
                    onCategory = { id, p, off, cur -> nav.navigate("cat/$id/${p.name}/$off/${cur ?: "ALL"}") }
                )
            }
            composable(
                "cat/{id}/{p}/{off}/{cur}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType }, navArgument("p") { type = NavType.StringType },
                    navArgument("off") { type = NavType.IntType }, navArgument("cur") { type = NavType.StringType }
                )
            ) {
                val a = it.arguments!!
                CategoryScreen(
                    categoryId = a.getString("id")!!,
                    initialType = PeriodType.valueOf(a.getString("p")!!),
                    initialOffset = a.getInt("off"),
                    initialCur = a.getString("cur")!!.takeIf { c -> c != "ALL" },
                    onBack = { nav.popBackStack() },
                    onEdit = { id -> nav.navigate("edit/$id") }
                )
            }
            composable("import") { ImportScreen(onBack = { nav.popBackStack() }) }
            composable("ops") { OperationsScreen(onEdit = { id -> nav.navigate("edit/$id") }) }
            composable("scan") { ScanScreen() }
            composable("accounts") { AccountsScreen() }
            composable("more") { MoreScreen(onImport = { nav.navigate("import") }) }
            composable("add/{type}", arguments = listOf(navArgument("type") { type = NavType.StringType })) {
                AddScreen(initialType = it.arguments?.getString("type") ?: "EXPENSE", editId = null, onDone = { nav.popBackStack() })
            }
            composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                AddScreen(initialType = "EXPENSE", editId = it.arguments?.getString("id"), onDone = { nav.popBackStack() })
            }
        }
    }
}

// ---------------- Первый запуск: выбор основной валюты ----------------

@Composable
fun OnboardingScreen(onDone: (String) -> Unit) {
    var selected by remember { mutableStateOf("KZT") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(C.Gold),
            contentAlignment = Alignment.Center
        ) { Text("M", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = C.Header) }
        Spacer(Modifier.height(20.dp))
        Text("Добро пожаловать в Moneta", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Text)
        Spacer(Modifier.height(8.dp))
        Text(
            "Выберите основную валюту. В ней будут считаться итоги и диаграммы. " +
                "Счета могут быть в любых валютах — суммы пересчитаются по курсу.",
            color = C.Dim, fontSize = 15.sp
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(CURRENCIES) { cur ->
                val sel = cur.code == selected
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (sel) C.CardHi else C.Card)
                        .clickable { selected = cur.code }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(cur.symbol, fontSize = 22.sp, color = C.Gold, modifier = Modifier.width(52.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cur.name, color = C.Text, fontSize = 16.sp)
                        Text(cur.code, color = C.Dim, fontSize = 13.sp)
                    }
                    if (sel) Icon(Icons.Filled.CheckCircle, null, tint = C.Mint)
                }
            }
        }
        Button(
            onClick = {
                scope.launch {
                    MonetaApp.instance.prefs.baseCurrency = selected
                    Seed.run(MonetaApp.dao, selected)
                    onDone(selected)
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) { Text("Начать", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
    }
}
