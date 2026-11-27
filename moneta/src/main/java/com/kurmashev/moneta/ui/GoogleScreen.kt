package com.kurmashev.moneta.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kurmashev.moneta.MonetaApp
import com.kurmashev.moneta.data.Backup
import com.kurmashev.moneta.google.GoogleAuth
import com.kurmashev.moneta.google.GoogleSync
import com.kurmashev.moneta.google.SyncWorker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ещё → Google: вход, таблица, синхронизация, резервные копии */
@Composable
fun GoogleScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = MonetaApp.instance.prefs
    val scope = rememberCoroutineScope()

    var on by remember { mutableStateOf(prefs.googleOn) }
    var email by remember { mutableStateOf(prefs.googleEmail) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var lastSync by remember { mutableLongStateOf(prefs.lastSync) }
    var backups by remember { mutableStateOf<List<GoogleSync.BackupFile>?>(null) }
    var restoreAsk by remember { mutableStateOf<GoogleSync.BackupFile?>(null) }

    fun sync(token: String?) {
        busy = "Синхронизация с Google Таблицей…"
        message = null
        scope.launch {
            val r = GoogleSync.run(ctx, token)
            busy = null
            message = r.message
            email = prefs.googleEmail
            lastSync = prefs.lastSync
            if (r.ok) SyncWorker.periodic(ctx)
        }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val token = if (res.resultCode == Activity.RESULT_OK) GoogleAuth.tokenFromIntent(ctx, res.data) else null
        if (token == null) {
            busy = null
            message = "Вход отменён"
        } else {
            prefs.googleOn = true; on = true
            sync(token)
        }
    }

    fun signIn() {
        busy = "Вход в Google…"
        message = null
        scope.launch {
            try {
                val r = GoogleAuth.authorize(ctx)
                if (r.hasResolution()) {
                    consent.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
                } else {
                    prefs.googleOn = true; on = true
                    sync(r.accessToken)
                }
            } catch (e: Exception) {
                busy = null
                message = "Не удалось войти: ${e.message}\n\nПроверьте настройку в Google Cloud: Android-ключ " +
                    "с пакетом com.kurmashev.moneta и SHA-1, ваша почта в Test users."
            }
        }
    }

    fun loadBackups() {
        scope.launch {
            val t = GoogleAuth.silentToken(ctx) ?: run { message = "Нужно войти заново"; return@launch }
            backups = try { GoogleSync.listBackups(t) } catch (e: Exception) { message = e.message; null }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(C.Header).statusBarsPadding().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "Назад", tint = C.Text) }
            Text("Google Таблица и копии", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!on) {
                item {
                    Info(Icons.Filled.Security, "Ваши данные — только в вашем аккаунте",
                        "Приложение получает доступ ТОЛЬКО к файлам, которые создаст само, и к своей скрытой папке. " +
                            "Остальные файлы на вашем Диске ему недоступны. У автора приложения доступа к вашим данным нет.")
                }
                item {
                    Info(Icons.Filled.Layers, "Три места хранения",
                        "1. Телефон — работает без интернета.\n" +
                            "2. Google Таблица «Moneta — мои финансы» — с дашбордом и графиками, можно смотреть и править с компьютера.\n" +
                            "3. Скрытая резервная копия на Google Диске — каждый день, 30 последних.\n\n" +
                            "Удалили приложение — данные вернутся из таблицы. Удалили таблицу — она создастся заново.")
                }
                item {
                    Button(
                        onClick = { signIn() }, enabled = busy == null,
                        modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp)
                    ) { Icon(Icons.Filled.Login, null); Spacer(Modifier.width(8.dp)); Text("Войти через Google", fontSize = 16.sp) }
                }
            } else {
                item {
                    Info(Icons.Filled.AccountCircle, email.ifEmpty { "Google-аккаунт подключён" },
                        if (lastSync > 0) "Последняя синхронизация: " +
                            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(lastSync))
                        else "Ещё не синхронизировано")
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val t = GoogleAuth.silentToken(ctx)
                                    if (t == null) signIn() else sync(t)
                                }
                            },
                            enabled = busy == null, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp)
                        ) { Icon(Icons.Filled.Sync, null); Spacer(Modifier.width(6.dp)); Text("Синхронизировать") }
                        OutlinedButton(
                            onClick = {
                                val url = prefs.sheetUrl
                                if (url.isNotEmpty()) ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                else message = "Таблица появится после первой синхронизации"
                            },
                            modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp)
                        ) { Icon(Icons.Filled.TableChart, null, tint = C.Mint); Spacer(Modifier.width(6.dp)); Text("Открыть", color = C.Text) }
                    }
                }
                item {
                    Info(Icons.Filled.Info, "Как работает",
                        "Синхронизация идёт сама: при открытии и сворачивании приложения и каждые 6 часов.\n" +
                            "Правки в таблице на компьютере (сумма, категория, дата, комментарий), новые строки и удалённые строки " +
                            "попадают в приложение при следующей синхронизации. Новую строку можно добавить внизу листа «Операции» — " +
                            "колонку ID оставьте пустой.")
                }
                item {
                    OutlinedButton({ loadBackups() }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Filled.Restore, null, tint = C.Mint); Spacer(Modifier.width(6.dp))
                        Text("Резервные копии", color = C.Text)
                    }
                }
                backups?.let { list ->
                    if (list.isEmpty()) item { Text("Копий пока нет — появится после синхронизации", color = C.Dim) }
                    items(list) { b ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(b.name.removePrefix("moneta-backup-").removeSuffix(".json"), color = C.Text, modifier = Modifier.weight(1f))
                            TextButton({ restoreAsk = b }) { Text("Восстановить", color = C.Gold) }
                        }
                    }
                }
                item {
                    TextButton({
                        prefs.googleOn = false; on = false
                        SyncWorker.cancelAll(ctx)
                        message = "Синхронизация выключена. Таблица и копии остались на вашем Диске."
                    }) { Text("Отключить Google", color = C.Expense) }
                }
            }
            busy?.let { b ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = C.Gold, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp)); Text(b, color = C.Text)
                    }
                }
            }
            message?.let { m -> item { Text(m, color = C.Text, fontSize = 14.sp) } }
        }
    }

    restoreAsk?.let { b ->
        AlertDialog(
            onDismissRequest = { restoreAsk = null }, containerColor = C.Card,
            title = { Text("Восстановить из копии?", color = C.Text) },
            text = {
                Text("Копия от ${b.name.removePrefix("moneta-backup-").removeSuffix(".json")}. " +
                    "Записи, которых нет на телефоне, добавятся; более новые версии заменят старые. Ничего не удаляется.", color = C.Dim)
            },
            confirmButton = {
                TextButton({
                    restoreAsk = null
                    busy = "Восстановление…"
                    scope.launch {
                        try {
                            val t = GoogleAuth.silentToken(ctx) ?: throw Exception("нужно войти заново")
                            val n = Backup.restore(MonetaApp.dao, Backup.fromJson(GoogleSync.downloadBackup(t, b.id)))
                            message = "Восстановлено записей: $n"
                        } catch (e: Exception) {
                            message = "Не удалось: ${e.message}"
                        }
                        busy = null
                    }
                }) { Text("Восстановить", color = C.Gold) }
            },
            dismissButton = { TextButton({ restoreAsk = null }) { Text("Отмена", color = C.Dim) } }
        )
    }
}

@Composable
private fun Info(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card).padding(14.dp)) {
        Icon(icon, null, tint = C.Mint, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(text, color = C.Dim, fontSize = 14.sp)
        }
    }
}
