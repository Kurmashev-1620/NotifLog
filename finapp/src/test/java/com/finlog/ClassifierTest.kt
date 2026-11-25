package com.finlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierTest {

    private val SMS = "com.google.android.apps.messaging"

    @Test fun bkashCashOut() {
        val p = Classifier.classify(
            "com.bKash.customerapp", "bKash", "bKash",
            "Cash Out Tk 1,000.00 to 01712345678 successful. Fee Tk 18.50. Balance Tk 5,231.50. TrxID ABC123"
        )
        assertNotNull(p); p!!
        assertEquals(TxType.EXPENSE, p.type)
        assertEquals(1000.0, p.amount, 0.001)
        assertEquals("BDT", p.currency)
        assertEquals(5231.5, p.balance!!, 0.001)
        assertTrue(p.confident)
    }

    @Test fun bankSmsDebit() {
        val p = Classifier.classify(
            SMS, "Сообщения", "DBBL",
            "Tk 2,500.00 debited from A/C **1234 on 22-11-26 at SHWAPNO. Avl Bal Tk 10,000.00"
        )!!
        assertEquals(TxType.EXPENSE, p.type)
        assertEquals(2500.0, p.amount, 0.001)
        assertEquals(10000.0, p.balance!!, 0.001)
        assertTrue(p.confident)
    }

    @Test fun russianPurchase() {
        val p = Classifier.classify(
            SMS, "Сообщения", "900",
            "Покупка 1 250,50 ₽ в PYATEROCHKA. Карта *1234. Доступно 15 000 ₽"
        )!!
        assertEquals(TxType.EXPENSE, p.type)
        assertEquals(1250.5, p.amount, 0.001)
        assertEquals("RUB", p.currency)
        assertEquals(15000.0, p.balance!!, 0.001)
        assertTrue(p.confident)
    }

    @Test fun salaryIncome() {
        val p = Classifier.classify(
            SMS, "Сообщения", "CityBank",
            "Tk 50,000.00 credited to A/C **1234 (Salary). Balance Tk 60,000.00"
        )!!
        assertEquals(TxType.INCOME, p.type)
        assertEquals(50000.0, p.amount, 0.001)
        assertTrue(p.confident)
    }

    @Test fun nagadReceived() {
        val p = Classifier.classify(
            "com.konasl.nagad", "Nagad", "Nagad",
            "You have received Tk 300.00 from 01811111111. Balance: Tk 800.00"
        )!!
        assertEquals(TxType.INCOME, p.type)
        assertEquals(300.0, p.amount, 0.001)
        assertTrue(p.confident)
    }

    @Test fun otpIsNotTransaction() {
        assertNull(
            Classifier.classify(SMS, "Сообщения", "BRAC", "Your OTP for payment of Tk 500.00 is 482913. Do not share.")
        )
        assertNull(
            Classifier.classify(SMS, "Сообщения", "900", "Код подтверждения: 4821. Никому не сообщайте.")
        )
    }

    @Test fun promoIsDropped() {
        assertNull(
            Classifier.classify("com.daraz.android", "Daraz", "Flash sale!", "Get up to ৳500 off on electronics")
        )
    }

    @Test fun messengerChatIsDropped() {
        assertNull(Classifier.classify("com.whatsapp", "WhatsApp", "Rahim", "I paid 500 tk for lunch"))
    }

    @Test fun billReminderNotConfident() {
        val p = Classifier.classify(SMS, "Сообщения", "DESCO", "Your bill Tk 1,200 is due on 25 Nov. Please pay.")
        assertTrue(p == null || !p.confident)
    }

    @Test fun plainNotification() {
        assertNull(Classifier.classify("com.google.android.calendar", "Календарь", "Встреча", "Meeting at 10:30 tomorrow"))
        assertNull(Classifier.classify("com.google.android.youtube", "YouTube", "Новое видео", "GTA 6: 15 фактов"))
    }

    @Test fun unknownShopGoesToReviewThenTrusted() {
        val pkg = "com.global.foodpanda.android"
        val p1 = Classifier.classify(pkg, "foodpanda", "foodpanda", "Payment of ৳450 successful")!!
        assertFalse(p1.confident)
        val p2 = Classifier.classify(pkg, "foodpanda", "foodpanda", "Payment of ৳450 successful", trust = 1)!!
        assertTrue(p2.confident)
        assertEquals(450.0, p2.amount, 0.001)
    }

    @Test fun ignoredApp() {
        assertNull(Classifier.classify("com.bKash.customerapp", "bKash", "bKash", "Cash Out Tk 100 successful", trust = -1))
    }

    private val DARAZ = "Thanks for shopping with us! Hi KOCMO, We received your #690120163272006 on 25 November 2026 " +
        "and you'll be paying for this via Cash On Delivery. Package 1 Sold by: Digital Shadhu " +
        "Vacuum Storage Bags for Clothes | Travel Storage Bags Tk. 230.00 Quantity: 1 " +
        "Vacuum Storage Bags for Clothes | Travel Storage Bags Tk. 230.00 Quantity: 2 " +
        "Subtotal: Tk. 690.00 Shipping fee: Tk. 181.00 Total Saving: Tk. (0.00) T"

    @Test fun orderTruncatedUsesSubtotalPlusShipping() {
        val p = Classifier.classify("ru.mail.mailapp", "Mail", "DARAZ Bangladesh", "Order Being Processed\n$DARAZ")!!
        assertEquals(TxType.EXPENSE, p.type)
        assertEquals(871.0, p.amount, 0.001)
        assertFalse(p.confident) // посчитано — на проверку
    }

    @Test fun orderWithTotalUsesTotal() {
        val p = Classifier.classify(
            "ru.mail.mailapp", "Mail", "DARAZ Bangladesh",
            DARAZ + "otal (inclusive of tax, if any): Tk. 896.00 Paid by: Cash On Delivery"
        )!!
        assertEquals(TxType.EXPENSE, p.type)
        assertEquals(896.0, p.amount, 0.001)
    }

    @Test fun numbers() {
        assertEquals(1250.0, Classifier.parseNum("1,250")!!, 0.001)
        assertEquals(12.5, Classifier.parseNum("12,5")!!, 0.001)
        assertEquals(1250.5, Classifier.parseNum("1 250,50")!!, 0.001)
        assertEquals(1250.5, Classifier.parseNum("1,250.50")!!, 0.001)
        assertEquals(500.0, Classifier.parseNum("500.00")!!, 0.001)
    }
}
