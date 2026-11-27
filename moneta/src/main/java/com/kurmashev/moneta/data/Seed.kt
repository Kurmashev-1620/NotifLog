package com.kurmashev.moneta.data

/** Стандартный набор при первом запуске */
object Seed {

    private val EXPENSE = listOf(
        Triple("Продукты", "cart", 0xFFF2B655),
        Triple("Кафе и рестораны", "restaurant", 0xFFE8774A),
        Triple("Транспорт", "taxi", 0xFF4E9BE8),
        Triple("Дом и ЖКХ", "home", 0xFF8C7AE6),
        Triple("Связь и интернет", "wifi", 0xFF3FC1C9),
        Triple("Здоровье", "health", 0xFFE5534B),
        Triple("Одежда", "clothes", 0xFFD96BB5),
        Triple("Покупки онлайн", "package", 0xFFF29A4E),
        Triple("Развлечения", "game", 0xFF7BC86C),
        Triple("Подарки", "gift", 0xFFE96A8D),
        Triple("Красота", "beauty", 0xFFC59BE8),
        Triple("Образование", "school", 0xFF5B8DEF),
        Triple("Спорт", "sport", 0xFF4CC38A),
        Triple("Хобби", "hobby", 0xFFE0C04C),
        Triple("Другое", "other", 0xFF9AA5A0)
    )

    private val INCOME = listOf(
        Triple("Зарплата", "salary", 0xFF4FC3E8),
        Triple("Подработка", "work", 0xFF6FE3A8),
        Triple("Подарки", "gift", 0xFFE96A8D),
        Triple("Проценты", "bank", 0xFF7BE0B0),
        Triple("Возврат", "refund", 0xFF9C9CF2),
        Triple("Другое", "other", 0xFF9AA5A0)
    )

    suspend fun run(dao: MonetaDao, baseCurrency: String) {
        if (dao.categoryCount() == 0) {
            val list = EXPENSE.mapIndexed { i, (n, ic, c) ->
                CategoryEntity(name = n, type = OpType.EXPENSE, icon = ic, color = c, sort = i)
            } + INCOME.mapIndexed { i, (n, ic, c) ->
                CategoryEntity(name = n, type = OpType.INCOME, icon = ic, color = c, sort = i)
            }
            dao.upsertCategories(list)
        }
        if (dao.accountCount() == 0) {
            dao.upsertAccount(AccountEntity(name = "Наличные", currency = baseCurrency, icon = "wallet", color = 0xFF8FD36B, sort = 0))
            dao.upsertAccount(AccountEntity(name = "Карта", currency = baseCurrency, icon = "card", color = 0xFF4E9BE8, sort = 1))
        }
    }
}
