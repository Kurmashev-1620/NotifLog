package com.finlog

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var db: TxDb
    private lateinit var adapter: TxAdapter
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var grantBtn: Button
    private lateinit var btnOps: Button
    private lateinit var btnReview: Button
    private lateinit var emptyView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var reviewMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = TxDb.get(this)

        status = findViewById(R.id.status)
        summary = findViewById(R.id.summary)
        grantBtn = findViewById(R.id.btnGrant)
        btnOps = findViewById(R.id.btnOps)
        btnReview = findViewById(R.id.btnReview)
        emptyView = findViewById(R.id.empty)

        grantBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btnOps.setOnClickListener { reviewMode = false; reload() }
        btnReview.setOnClickListener { reviewMode = true; reload() }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { addManual() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { Export.start(this) }

        val list = findViewById<ListView>(R.id.list)
        adapter = TxAdapter(this)
        list.adapter = adapter
        list.emptyView = emptyView
        list.setOnItemClickListener { _, _, pos, _ -> showTx(adapter.getItem(pos)) }
    }

    override fun onResume() {
        super.onResume()
        FinListener.onNew = { handler.post { reload() } }
        if (hasAccess()) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
            }
            KeepAliveService.start(this)
            KeepAliveService.rebindListener(this)
        }
        reload()
    }

    override fun onPause() {
        super.onPause()
        FinListener.onNew = null
    }

    private fun hasAccess(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    private fun reload() {
        if (hasAccess()) {
            status.text = "● Слежу за финансовыми уведомлениями"
            status.setTextColor(0xFF2E7D32.toInt())
            grantBtn.visibility = View.GONE
        } else {
            status.text = "Нет доступа к уведомлениям. Нажмите кнопку и включите «Учёт финансов»."
            status.setTextColor(0xFFC62828.toInt())
            grantBtn.visibility = View.VISIBLE
        }

        // Итоги текущего месяца
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val sums = db.summary(cal.timeInMillis)
        val month = MONTHS[cal.get(Calendar.MONTH)]
        summary.text = if (sums.isEmpty()) {
            "$month: операций пока нет"
        } else {
            "$month\n" + sums.entries.joinToString("\n") { (cur, p) ->
                val s = Classifier.symbol(cur)
                "Расход: ${money(p.first)} $s    Доход: ${money(p.second)} $s"
            }
        }

        val rc = db.reviewCount()
        btnReview.text = if (rc > 0) "Проверить ($rc)" else "Проверить"
        btnOps.isEnabled = reviewMode
        btnReview.isEnabled = !reviewMode
        emptyView.text = if (reviewMode)
            "Нечего проверять.\nСюда попадают уведомления, где есть сумма, но непонятно — операция это или нет."
        else
            "Операций пока нет.\nКак только придёт SMS или пуш от банка — он появится здесь."

        adapter.items = db.list(reviewMode)
        adapter.notifyDataSetChanged()
    }

    // ---------- Карточка операции ----------

    private fun showTx(t: Tx) {
        val msg = buildString {
            append(t.text)
            append("\n\n")
            append(t.app).append(" · ").append(DATE.format(Date(t.time)))
            if (t.balance != null) append("\nБаланс: ").append(money(t.balance)).append(' ')
                .append(Classifier.symbol(t.currency))
            if (t.review) append("\n\nЭто финансовая операция? Выберите тип:")
        }
        AlertDialog.Builder(this)
            .setTitle("${sign(t.type)}${money(t.amount)} ${Classifier.symbol(t.currency)} · ${t.merchant}")
            .setMessage(msg)
            .setPositiveButton("Расход") { _, _ -> setType(t, TxType.EXPENSE) }
            .setNegativeButton("Доход") { _, _ -> setType(t, TxType.INCOME) }
            .setNeutralButton("Ещё…") { _, _ -> moreActions(t) }
            .show()
    }

    private fun setType(t: Tx, type: TxType) {
        db.confirm(t.id, type)
        // Подтвердил операцию из приложения — дальше доверяем ему больше
        if (t.review && t.pkg != "manual" && db.trust(t.pkg) == 0) {
            db.setTrust(t.pkg, 1)
            toast("«${t.app}» теперь считается финансовым")
        }
        reload()
    }

    private fun moreActions(t: Tx) {
        val items = arrayOf(
            "Изменить сумму",
            "Не финансовое — удалить",
            "Больше не брать уведомления из «${t.app}»"
        )
        AlertDialog.Builder(this)
            .setTitle(t.merchant)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> editAmount(t)
                    1 -> { db.delete(t.id); reload() }
                    2 -> confirmIgnore(t)
                }
            }
            .show()
    }

    private fun editAmount(t: Tx) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(money(t.amount).replace(" ", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("Сумма")
            .setView(pad(et))
            .setPositiveButton("Сохранить") { _, _ ->
                val v = Classifier.parseNum(et.text.toString())
                if (v != null && v > 0) { db.setAmount(t.id, v); reload() } else toast("Неверная сумма")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmIgnore(t: Tx) {
        AlertDialog.Builder(this)
            .setTitle("Игнорировать «${t.app}»?")
            .setMessage("Уведомления этого приложения больше не будут попадать в учёт. Уже сохранённые записи из него будут удалены.")
            .setPositiveButton("Игнорировать") { _, _ ->
                db.setTrust(t.pkg, -1)
                db.deleteByPkg(t.pkg)
                reload()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- Ручной ввод (наличные) ----------

    private fun addManual() {
        val cur = db.mainCurrency()
        val amount = EditText(this).apply {
            hint = "Сумма, ${Classifier.symbol(cur)}"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val note = EditText(this).apply { hint = "На что / от кого"; isSingleLine = true }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(amount); addView(note)
        }
        val save = { type: TxType ->
            val v = Classifier.parseNum(amount.text.toString())
            if (v != null && v > 0) {
                db.addManual(v, cur, type, note.text.toString().trim()); reload()
            } else toast("Введите сумму")
        }
        AlertDialog.Builder(this)
            .setTitle("Добавить вручную")
            .setView(pad(box))
            .setPositiveButton("Расход") { _, _ -> save(TxType.EXPENSE) }
            .setNegativeButton("Доход") { _, _ -> save(TxType.INCOME) }
            .setNeutralButton("Отмена", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        Export.onResult(this, requestCode, resultCode, data)
    }

    private fun pad(v: View): View {
        val p = (20 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            setPadding(p, p / 2, p, 0)
            addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- Список ----------

    private class TxAdapter(val ctx: Context) : BaseAdapter() {
        var items: List<Tx> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_tx, parent, false)
            val t = items[position]
            v.findViewById<TextView>(R.id.merchant).text = t.merchant
            v.findViewById<TextView>(R.id.meta).text =
                (if (t.review) "⚠ " else "") + "${t.app} · ${DATE.format(Date(t.time))}"
            v.findViewById<TextView>(R.id.snippet).text = t.text
            val a = v.findViewById<TextView>(R.id.amount)
            a.text = "${sign(t.type)}${money(t.amount)} ${Classifier.symbol(t.currency)}"
            a.setTextColor(
                when (t.type) {
                    TxType.EXPENSE -> 0xFFC62828.toInt()
                    TxType.INCOME -> 0xFF2E7D32.toInt()
                    TxType.UNKNOWN -> 0xFF757575.toInt()
                }
            )
            return v
        }
    }

    companion object {
        val DATE = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        private val MONTHS = arrayOf(
            "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
            "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
        )
        private val FMT = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US).apply {
            groupingSeparator = ' '; decimalSeparator = ','
        })

        fun money(v: Double): String = FMT.format(v)
        fun sign(t: TxType) = when (t) { TxType.EXPENSE -> "−"; TxType.INCOME -> "+"; TxType.UNKNOWN -> "" }
    }
}
