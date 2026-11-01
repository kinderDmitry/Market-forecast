package com.marketforecast.prox

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Keeps the full BCS catalogue on the device without anybody waiting for it: it is downloaded (and refreshed every 12 hours)
 * in the background, also while the app is closed. A fresh catalogue is a few milliseconds of work, a stale one is rebuilt.
 */
class CatalogWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("mfprefs", Context.MODE_PRIVATE)
        val token = SecureTokenStore.get(prefs)
        if (token.isBlank()) return Result.success()
        return try {
            val repo = MarketRepository(bcsRefreshToken = token, prefs = prefs, context = applicationContext)
            withContext(Dispatchers.IO) { repo.syncCatalog() }
            if (repo.catalogSyncedAt() > 0L || runAttemptCount >= 3) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}

internal object CatalogScheduler {
    private const val PERIODIC = "catalog_sync"
    private const val NOW = "catalog_sync_now"

    fun apply(context: Context, prefs: SharedPreferences) {
        val wm = WorkManager.getInstance(context)
        if (SecureTokenStore.get(prefs).isBlank()) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CatalogWorker>(12, TimeUnit.HOURS).setConstraints(constraints).build()
        )
        // A one-off run right now, so a fresh install does not wait up to 12 hours for the first full catalogue.
        wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<CatalogWorker>().setConstraints(constraints).build())
    }
}
