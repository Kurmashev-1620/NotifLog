package com.kurmashev.moneta.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

// Тёмная изумрудная тема с золотым акцентом
object C {
    val Bg = Color(0xFF121A17)          // фон
    val Header = Color(0xFF2C4A3F)      // изумрудная шапка
    val Card = Color(0xFF232C28)        // карточки
    val CardHi = Color(0xFF2D3833)      // выделенная карточка
    val Gold = Color(0xFFF2C14E)        // главный акцент, кнопка «+»
    val Mint = Color(0xFF5CC8A0)        // активная вкладка
    val Text = Color(0xFFEDEFE9)
    val Dim = Color(0xFFA3ADA8)
    val Expense = Color(0xFFF0776A)
    val Income = Color(0xFF6FD39B)
}

@Composable
fun MonetaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Gold,
            onPrimary = Color(0xFF1B1B1B),
            secondary = C.Mint,
            background = C.Bg,
            surface = C.Card,
            surfaceVariant = C.CardHi,
            onBackground = C.Text,
            onSurface = C.Text,
            onSurfaceVariant = C.Dim
        ),
        content = content
    )
}

/** Иконки категорий и счетов по ключу (ключ хранится в базе и в Google Таблице) */
val ICONS: Map<String, ImageVector> = linkedMapOf(
    "cart" to Icons.Filled.ShoppingCart,
    "restaurant" to Icons.Filled.Restaurant,
    "cafe" to Icons.Filled.LocalCafe,
    "taxi" to Icons.Filled.LocalTaxi,
    "car" to Icons.Filled.DirectionsCar,
    "fuel" to Icons.Filled.LocalGasStation,
    "home" to Icons.Filled.Home,
    "wifi" to Icons.Filled.Wifi,
    "phone" to Icons.Filled.PhoneAndroid,
    "health" to Icons.Filled.LocalHospital,
    "clothes" to Icons.Filled.Checkroom,
    "package" to Icons.Filled.LocalShipping,
    "game" to Icons.Filled.SportsEsports,
    "gift" to Icons.Filled.CardGiftcard,
    "flower" to Icons.Filled.LocalFlorist,
    "beauty" to Icons.Filled.Spa,
    "school" to Icons.Filled.School,
    "sport" to Icons.Filled.FitnessCenter,
    "pet" to Icons.Filled.Pets,
    "hobby" to Icons.Filled.Palette,
    "drone" to Icons.Filled.FlightTakeoff,
    "travel" to Icons.Filled.Flight,
    "tools" to Icons.Filled.Build,
    "electronics" to Icons.Filled.Devices,
    "chemistry" to Icons.Filled.CleaningServices,
    "kids" to Icons.Filled.ChildCare,
    "credit" to Icons.Filled.CreditScore,
    "bills" to Icons.Filled.Receipt,
    "salary" to Icons.Filled.Work,
    "work" to Icons.Filled.Handyman,
    "bank" to Icons.Filled.AccountBalance,
    "refund" to Icons.Filled.Replay,
    "help" to Icons.Filled.VolunteerActivism,
    "savings" to Icons.Filled.Savings,
    "wallet" to Icons.Filled.AccountBalanceWallet,
    "card" to Icons.Filled.CreditCard,
    "invest" to Icons.Filled.Insights,
    "crypto" to Icons.Filled.Paid,
    "other" to Icons.Filled.MoreHoriz
)

fun icon(key: String): ImageVector = ICONS[key] ?: Icons.Filled.MoreHoriz

val PALETTE = listOf(
    0xFFF2B655, 0xFFE8774A, 0xFFE5534B, 0xFFE96A8D, 0xFFD96BB5, 0xFFC59BE8, 0xFF8C7AE6,
    0xFF5B8DEF, 0xFF4E9BE8, 0xFF3FC1C9, 0xFF4CC38A, 0xFF7BC86C, 0xFF8FD36B, 0xFFE0C04C, 0xFF9AA5A0
)
