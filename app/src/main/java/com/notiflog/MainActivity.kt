package com.notiflog

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var db: LogDb
    private lateinit var adapter: LogAdapter
    private lateinit var status: TextView
    private lateinit var grantBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private var search = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = LogDb.get(this)

        status = findViewById(R.id.status)
        grantBtn = findViewById(R.id.btnGrant)
        grantBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        val list = findViewById<ListView>(R.id.list)
        adapter = LogAdapter(this)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.empty)
        list.setOnItemClickListener { _, _, pos, _ -> showDetails(adapter.getItem(pos)) }

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                search = s?.toString().orEmpty()
                reload()
            }
        })

        findViewById<Button>(R.id.btnExport).setOnClickListener { startExport() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        NotifListener.onNew = { handler.post { reload() } }
        reload()
    }

    override fun onPause() {
        super.onPause()
        NotifListener.onNew = null
    }

    private fun hasAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled?.contains(packageName) == true
    }

    private fun reload() {
        adapter.items = db.query(search, 2000)
        adapter.notifyDataSetChanged()
        val total = db.count()
        if (hasAccess()) {
            status.text = "● Запись включена · в журнале: $total"
            status.setTextColor(0xFF2E7D32.toInt())
            grantBtn.visibility = View.GONE
        } else {
            status.text = "Нет доступа к уведомлениям. Нажмите кнопку ниже и включите «Журнал уведомлений»."
            status.setTextColor(0xFFC62828.toInt())
            grantBtn.visibility = View.VISIBLE
        }
    }

    private fun showDetails(e: Entry) {
        val body = "${e.app}  (${e.pkg})\n${FULL.format(Date(e.time))}\n\n${e.text}"
        AlertDialog.Builder(this)
            .setTitle(e.title.ifBlank { e.app })
            .setMessage(body)
            .setPositiveButton("Копировать") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("notification", "${e.title}\n${e.text}"))
                Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Очистить журнал?")
            .setMessage("Все записи будут удалены без возможности восстановления.")
            .setPositiveButton("Удалить") { _, _ ->
                db.clear()
                reload()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- Экспорт в CSV (открывается в Excel) ----------

    private fun startExport() {
        val name = "notifications_" +
            SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".csv"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_TITLE, name)
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_EXPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val all = db.query("", Int.MAX_VALUE)
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { w ->
                w.write("﻿") // BOM, чтобы Excel правильно показал русские буквы
                w.write("Дата;Приложение;Пакет;Заголовок;Текст\n")
                for (e in all) {
                    w.write(
                        listOf(FULL.format(Date(e.time)), e.app, e.pkg, e.title, e.text)
                            .joinToString(";") { csv(it) } + "\n"
                    )
                }
            }
            Toast.makeText(this, "Сохранено записей: ${all.size}", Toast.LENGTH_LONG).show()
        } catch (ex: Exception) {
            Toast.makeText(this, "Ошибка экспорта: ${ex.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    // ---------- Список ----------

    private class LogAdapter(val ctx: Context) : BaseAdapter() {
        var items: List<Entry> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_log, parent, false)
            val e = items[position]
            v.findViewById<TextView>(R.id.meta).text = "${e.app} · ${FULL.format(Date(e.time))}"
            val t = v.findViewById<TextView>(R.id.title)
            t.text = e.title
            t.visibility = if (e.title.isEmpty()) View.GONE else View.VISIBLE
            val x = v.findViewById<TextView>(R.id.text)
            x.text = e.text
            x.visibility = if (e.text.isEmpty()) View.GONE else View.VISIBLE
            return v
        }
    }

    companion object {
        private const val REQ_EXPORT = 1
        private val FULL = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
    }
}
