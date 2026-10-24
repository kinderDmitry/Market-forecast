package com.marketforecast.prox

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** "New forecasts found" notification, shared by the in-app scanner (when the app is not visible) and the background worker. */
internal object ScanNotify {
    fun post(context: Context, h: ScanHorizon, fresh: List<ScanHit>, ru: Boolean, minConf: Int) {
        val good = fresh
            .filter { it.confidence >= minConf }
            .sortedWith(compareByDescending<ScanHit> { it.reliability }.thenByDescending { it.confidence })
        if (good.isEmpty()) return
        val top = good.take(3).joinToString("  •  ") {
            "${it.symbol.removeSuffix(".ME")} ${it.signal} ${it.confidence}% (${ReliabilityEngine.gradeOf(it.reliability)})"
        }
        val more = if (good.size > 3) (if (ru) "  и ещё ${good.size - 3}" else "  and ${good.size - 3} more") else ""
        val title = if (ru) "📡 Новые прогнозы · ${h.label(true)}: ${good.size}" else "📡 New ${h.label(false)} forecasts: ${good.size}"
        // One notification per batch of signals and per validity window, so the same list is never announced twice.
        val bucket = System.currentTimeMillis() / h.validMs.coerceAtLeast(60_000L)
        val signature = good.joinToString(",") { it.symbol + it.signal }.hashCode()
        NotificationHelper.notifyScanner(
            context, "SCAN|${h.tf}|$signature|$bucket", title, top + more,
            if (good.size == 1) good[0].symbol else null
        )
    }
}

/** Keeps the periodic background scan in line with the settings: on / off, horizon, notifications, token. */
internal object ScanScheduler {
    private const val WORK = "scanner_bg"

    /** How often the horizon is worth re-checking (WorkManager never goes below 15 minutes). */
    fun intervalMinutes(h: ScanHorizon): Long = when (h) {
        ScanHorizon.M15 -> 15L
        ScanHorizon.H1 -> 30L
        ScanHorizon.H4 -> 60L
        ScanHorizon.D1 -> 180L
        ScanHorizon.W1 -> 720L
    }

    fun horizonOf(prefs: SharedPreferences): ScanHorizon =
        runCatching { ScanHorizon.valueOf(prefs.getString("scanner_horizon", ScanHorizon.D1.name).orEmpty()) }.getOrDefault(ScanHorizon.D1)

    fun apply(context: Context, prefs: SharedPreferences) {
        val wm = WorkManager.getInstance(context)
        val enabled = prefs.getBoolean("scanner_bg", true) &&
            prefs.getBoolean("notifications_enabled", false) &&
            SecureTokenStore.get(prefs).isNotBlank()
        if (!enabled) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        wm.enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ScanWorker>(intervalMinutes(horizonOf(prefs)), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        )
    }
}

/**
 * Looks for new forecasts while the app is closed: the favourites (or the liquid main boards) on the horizon chosen in the
 * scanner. Results are merged into the same store the scanner screen reads, so opening the app shows them at once.
 */
class ScanWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val p = applicationContext.getSharedPreferences("mfprefs", Context.MODE_PRIVATE)
        if (!p.getBoolean("scanner_bg", true) || !p.getBoolean("notifications_enabled", false)) return Result.success()
        val token = SecureTokenStore.get(p)
        if (token.isBlank()) return Result.success()
        return try {
            scan(p, token)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    private suspend fun scan(p: SharedPreferences, token: String) {
        val ctx = applicationContext
        val repo = MarketRepository(bcsRefreshToken = token, prefs = p, context = ctx)
        val horizon = ScanScheduler.horizonOf(p)
        val scope = if (p.getString("scanner_bg_scope", "FAVORITES") == "MAIN") ScanScope.MAIN else ScanScope.FAVORITES
        val favorites = p.getStringSet("favorites", emptySet()).orEmpty().toSet()
        val universe = withContext(Dispatchers.IO) {
            if (repo.catalogSize() == 0) runCatching { repo.bootstrapCatalog() }
            ScanCore.buildUniverse(repo, scope, favorites)
        }
        if (universe.isEmpty()) return

        val tf = horizon.tf
        val snap = withContext(Dispatchers.IO) { ScannerStore.load(ctx) }
        val previous = snap?.hits.orEmpty().filter { it.timeframe == tf }.associate { it.symbol to it.signal }
        val stampKey = "scanner_bg_stamp_$tf"
        // NEW flags only mean something after one earlier pass of this horizon (by the app or by this worker).
        val baseline = p.getLong(stampKey, 0L) > 0L || (snap?.finished?.get(tf) ?: 0L) > 0L

        val gate = Semaphore(4)
        val started = System.currentTimeMillis()
        val outcomes = ArrayList<Pair<SearchResult, ScanCore.Outcome>>()
        var answered = 0
        var stale = 0
        for (chunk in universe.chunked(24)) {
            // WorkManager gives a worker about ten minutes: leave without saving a half-finished picture.
            if (System.currentTimeMillis() - started > BUDGET_MS) return
            val part = coroutineScope {
                chunk.map { item ->
                    async(Dispatchers.IO) {
                        gate.withPermit { item to ScanCore.evaluate(repo, item, horizon, previous[item.symbol].orEmpty(), baseline) }
                    }
                }.awaitAll()
            }
            outcomes.addAll(part)
            for ((_, o) in part) {
                if (o.status != ScanCore.FAILED) {
                    answered += 1
                    if (o.status == ScanCore.STALE) stale += 1
                }
            }
            // Intraday horizon while the exchange is closed: nothing has a fresh candle, so there is nothing to report.
            if (horizon.intraday && answered >= 24 && stale * 10 >= answered * 9) return
        }

        val now = System.currentTimeMillis()
        val scanned = outcomes.filter { it.second.status != ScanCore.FAILED }.map { it.first.symbol }.toSet()
        val newHits = outcomes.mapNotNull { it.second.hit }
        withContext(Dispatchers.IO) { ScannerStore.saveRun(ctx, tf, scope.name, now, scanned, newHits, markFinished = false) }
        p.edit().putLong(stampKey, now).apply()

        val fresh = newHits.filter { it.isNew }
        if (baseline && fresh.isNotEmpty() && p.getBoolean("alert_signal", true)) {
            ScanNotify.post(ctx, horizon, fresh, p.getBoolean("ru", true), p.getInt("alert_min_confidence", 60).coerceIn(0, 100))
        }
    }

    private companion object {
        const val BUDGET_MS = 8 * 60_000L
    }
}

/** Settings block: switch the background scanner on or off and choose what it watches. */
@Composable
internal fun BackgroundScannerSettings(ru: Boolean, prefs: SharedPreferences) {
    var enabled by remember { mutableStateOf(prefs.getBoolean("scanner_bg", true)) }
    var mainBoard by remember { mutableStateOf(prefs.getString("scanner_bg_scope", "FAVORITES") == "MAIN") }
    val appContext = LocalContext.current.applicationContext
    val horizon = ScanScheduler.horizonOf(prefs)
    GradientCard(Modifier.fillMaxWidth()) {
        SectionHeader(
            if (ru) "Фоновый сканер" else "Background scanner",
            if (ru) "Ищет новые прогнозы, пока приложение закрыто" else "Looks for new forecasts while the app is closed"
        )
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ru) "Искать прогнозы в фоне" else "Scan in the background",
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                prefs.edit().putBoolean("scanner_bg", it).apply()
                ScanScheduler.apply(appContext, prefs)
            })
        }
        if (enabled) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = !mainBoard,
                    onClick = { mainBoard = false; prefs.edit().putString("scanner_bg_scope", "FAVORITES").apply() },
                    label = { Text(if (ru) "Избранное" else "Favorites", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = mainBoard,
                    onClick = { mainBoard = true; prefs.edit().putString("scanner_bg_scope", "MAIN").apply() },
                    label = { Text(if (ru) "Основные акции" else "Main boards", fontSize = 12.sp) }
                )
            }
        }
        Text(
            if (ru) "Срок прогноза берётся из сканера (сейчас: ${horizon.label(true)}), проверка идёт примерно раз в ${ScanScheduler.intervalMinutes(horizon)} мин. Нужны включённые уведомления и токен БКС. Android может откладывать фоновые задачи в режиме экономии заряда."
            else "The forecast horizon comes from the scanner (now: ${horizon.label(false)}); it checks about every ${ScanScheduler.intervalMinutes(horizon)} min. Notifications and a BCS token are required. Android may delay background work in battery saver.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
        )
    }
}
