package com.kurmashev.moneta.google

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * Вход через Google.
 * Просим только два узких доступа:
 *   drive.file     — только к файлам, которые создало само приложение (таблица, сканы чеков);
 *   drive.appdata  — скрытая папка приложения для резервных копий.
 * Ко всем остальным файлам пользователя приложение доступа не имеет.
 */
object GoogleAuth {
    private val SCOPES = listOf(
        "https://www.googleapis.com/auth/drive.file",
        "https://www.googleapis.com/auth/drive.appdata"
    )

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(SCOPES.map { Scope(it) }).build()

    /** Результат авторизации: либо токен сразу, либо нужно показать окно согласия */
    suspend fun authorize(ctx: Context): AuthorizationResult =
        Identity.getAuthorizationClient(ctx).authorize(request()).await()

    /** Токен без окон (фон, повторная синхронизация). null — нужно войти заново */
    suspend fun silentToken(ctx: Context): String? = try {
        val r = authorize(ctx)
        if (r.hasResolution()) null else r.accessToken
    } catch (_: Exception) {
        null
    }

    /** Токен из ответа окна согласия */
    fun tokenFromIntent(ctx: Context, data: Intent?): String? = try {
        Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data).accessToken
    } catch (_: Exception) {
        null
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
    addOnCanceledListener { c.cancel() }
}
