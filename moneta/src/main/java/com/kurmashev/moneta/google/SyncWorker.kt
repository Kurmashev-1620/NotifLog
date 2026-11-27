package com.kurmashev.moneta.google

import android.content.Context
import androidx.work.*
import com.kurmashev.moneta.MonetaApp
import java.util.concurrent.TimeUnit

/** Фоновая синхронизация: при открытии/сворачивании приложения и раз в 6 часов */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!MonetaApp.instance.prefs.googleOn) return Result.success()
        val r = GoogleSync.run(applicationContext)
        return when {
            r.ok || r.needSignIn -> Result.success()
            runAttemptCount < 3 -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Синхронизировать вскоре (через delaySec) — несколько вызовов подряд склеиваются в один */
        fun soon(ctx: Context, delaySec: Long = 5) {
            if (!MonetaApp.instance.prefs.googleOn) return
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(net)
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("sync", ExistingWorkPolicy.REPLACE, req)
        }

        fun periodic(ctx: Context) {
            if (!MonetaApp.instance.prefs.googleOn) return
            val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancelAll(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork("sync")
            WorkManager.getInstance(ctx).cancelUniqueWork("sync-periodic")
        }
    }
}
