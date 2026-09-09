package com.marketforecast.prox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Long-running scanner. It is deliberately independent from the Activity so scanning
 * continues when the UI is closed or the screen is off. It has no artificial universe
 * or result limit; concurrency is only an execution detail used to keep the device alive.
 */
class ScannerForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("mfprefs", MODE_PRIVATE)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopScanner()
            return START_NOT_STICKY
        }
        val scopeMode = intent?.getStringExtra(EXTRA_SCOPE) ?: prefs.getString("scanner_scope", "ALL") ?: "ALL"
        val type = intent?.getStringExtra(EXTRA_TYPE) ?: prefs.getString("scanner_type", "ALL") ?: "ALL"
        val tf = intent?.getStringExtra(EXTRA_TF) ?: prefs.getString("scanner_tf", "1D") ?: "1D"
        // Ordinary scanner has no user-entered horizon. The validity window is
        // derived from the actual forecast timeframe, so a 15M signal lives for
        // 15 minutes, a 1H signal for 1 hour, etc. Screenshot scanner is separate.
        prefs.edit().putString("scanner_scope", scopeMode).putString("scanner_type", type).putString("scanner_tf", tf).remove("scanner_horizon_value").remove("scanner_horizon_unit").putBoolean("scanner_running", true).putBoolean("scanner_priority_active", scopeMode == "ALL").apply()
        startForeground(NOTIFICATION_ID, notification("Сканер работает", "Подготовка данных…", false))
        if (!running.getAndSet(true)) job = scope.launch { scanLoop(scopeMode, type, tf) }
        return START_STICKY
    }

    private suspend fun scanLoop(scopeMode: String, type: String, tf: String) {
        try {
            while (currentCoroutineContext().isActive && running.get()) {
                prefs.edit().putString("scanner_status", "Подготовка полного сканирования…").putFloat("scanner_progress", 0f).apply()
                val repo = MarketRepository(bcsRefreshToken = prefs.getString("bcs_refresh_token", "")?.ifBlank { null })
                val symbols = withContext(Dispatchers.IO) { resolveSymbols(repo, scopeMode, type) }
                val tfs = if (tf == "ANY") listOf("15M", "1H", "4H", "1D", "1W") else listOf(tf)
                // Do not materialize one Deferred per instrument/timeframe: a full-market
                // scan can contain tens of thousands of jobs and that alone can exhaust
                // heap/GC on Android. Process an unbounded universe in small scheduling
                // batches while keeping the actual scanner universe completely intact.
                val total = (symbols.size.toLong() * tfs.size.toLong()).coerceAtLeast(1L)
                val completed = java.util.concurrent.atomic.AtomicLong(0L)
                val parallelism = (Runtime.getRuntime().availableProcessors() * 2).coerceIn(6, 32)
                val gate = Semaphore(parallelism)
                val out = mutableListOf<ScanRow>()
                val resultLock = Any()
                val previousKeys = loadScanRows(prefs).filter { it.expiresAt <= 0L || it.expiresAt > System.currentTimeMillis() }
                    .map { "${it.result.symbol}|${it.timeframe}|${it.signal}" }.toMutableSet()
                val emittedKeys = mutableSetOf<String>()
                val batchSize = (parallelism * 4).coerceAtLeast(24)

                for (start in symbols.indices step batchSize) {
                    if (!currentCoroutineContext().isActive || !running.get()) break
                    val batch = symbols.subList(start, minOf(start + batchSize, symbols.size))
                    val batchJobs = coroutineScope {
                        batch.flatMap { symbol -> tfs.map { symbol to it } }.map { (symbol, currentTf) ->
                            async(Dispatchers.IO) {
                                gate.withPermit {
                                    if (!currentCoroutineContext().isActive || !running.get()) return@withPermit null
                                    val pair = when (currentTf) {
                                        "15M" -> "30d" to "15m"
                                        "1H" -> "180d" to "1h"
                                        "4H" -> "180d" to "4h"
                                        "1W" -> "10y" to "1wk"
                                        else -> "2y" to "1d"
                                    }
                                    val result = runCatching {
                                        withTimeout(20_000L) {
                                            val candles = repo.load(symbol, pair.first, pair.second)
                                            if (candles.size < 30) null else {
                                                // Stage 1: use the last BCS candle for the broad market pass.
                                                // Stage 2: request the live quote only for a candidate. This prevents
                                                // thousands of per-instrument quote calls from starving the scanner.
                                                val baseline = candles.last().close
                                                val baselineCandles = mergeRealtimeCandle(candles, baseline, currentTf, System.currentTimeMillis(), symbol)
                                                val preliminary = AnalyticsEngine.analyzeForScanner(baselineCandles, baseline)
                                                if (preliminary.signal == "NO TRADE") null else {
                                                    val rawQuote = runCatching { repo.quote(symbol) }.getOrNull()
                                                    val live = reconcileLivePrice(symbol, candles, rawQuote)
                                                    val merged = mergeRealtimeCandle(candles, live, currentTf, System.currentTimeMillis(), symbol)
                                                    val f = AnalyticsEngine.analyzeForScanner(merged, live)
                                                    if (f.signal == "NO TRADE") null else {
                                                    val created = System.currentTimeMillis()
                                                    val hs = timeframeHorizonSeconds(currentTf)
                                                    ScanRow(SearchResult(symbol, symbol, "", "", "БКС"), currentTf, f.signal, f.confidence, f.score, f.rr, hs, created, created + hs * 1000L)
                                                }
                                            }
                                        }
                                    }.getOrNull()
                                    result?.let { row ->
                                        val key = "${row.result.symbol}|${row.timeframe}|${row.signal}"
                                        synchronized(resultLock) {
                                            if (emittedKeys.add(key)) {
                                                val current = loadScanRows(prefs).filter { it.expiresAt <= 0L || it.expiresAt > System.currentTimeMillis() }
                                                    .filterNot { "${it.result.symbol}|${it.timeframe}|${it.signal}" == key }
                                                val mergedRows = (current + row).sortedWith(compareByDescending<ScanRow> { it.confidence }.thenByDescending { abs(it.score) })
                                                val encodedNow = mergedRows.map { listOf(it.result.symbol, it.timeframe, it.signal, it.confidence, it.score, it.rr, it.horizonSeconds, it.createdAt, it.expiresAt).joinToString("|") }.toSet()
                                                prefs.edit().putStringSet("auto_scan_results", encodedNow).putInt("scanner_last_found", mergedRows.size).apply()
                                                if (key !in previousKeys) {
                                                    val body = "${row.result.symbol.removeSuffix(".ME")} • ${row.signal} • ${row.confidence}%\nГоризонт: ${formatHorizonSeconds(row.horizonSeconds)}\nОсталось: ${formatHorizonSeconds((row.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))}"
                                                    NotificationHelper.notifyMarket(applicationContext, "scanner|${row.result.symbol}|${row.timeframe}|${row.signal}|${row.createdAt}", "Новый сигнал: ${row.signal}", body, "SCANNER", row.result.symbol)
                                                }
                                            }
                                        }
                                    }
                                    val done = completed.incrementAndGet()
                                    if (done % 10L == 0L || done == total) {
                                        val progress = (done.toDouble() / total.toDouble()).toFloat()
                                        prefs.edit().putFloat("scanner_progress", progress).putString("scanner_status", "Сканирование: $done / $total").apply()
                                        updateForeground("Сканирование", "$done / $total", progress)
                                    }
                                    result
                                }
                            }
                        }.awaitAll()
                    }
                    batchJobs.filterNotNullTo(out)
                }
                prefs.edit().putFloat("scanner_progress", (completed.get().toDouble() / total.toDouble()).toFloat()).apply()
                val now = System.currentTimeMillis()
                val sorted = out.filter { it.expiresAt <= 0L || it.expiresAt > now }
                    .sortedWith(compareByDescending<ScanRow> { it.confidence }.thenByDescending { abs(it.score) })
                val encoded = sorted.map { listOf(it.result.symbol, it.timeframe, it.signal, it.confidence, it.score, it.rr, it.horizonSeconds, it.createdAt, it.expiresAt).joinToString("|") }.toSet()
                prefs.edit().putStringSet("auto_scan_results", encoded).putLong("scanner_last_run", now).putInt("scanner_last_found", sorted.size).putFloat("scanner_progress", 1f).putString("scanner_status", if (sorted.isEmpty()) "Сканирование завершено: подходящих подтверждённых сигналов пока нет • проверено ${completed.get()} инструментов" else "Сигналы обновлены: ${sorted.size} • проверено ${completed.get()} инструментов • срок действия зависит от таймфрейма").apply()
                updateForeground("Сканер обновлён", "Сигналы выдаются сразу по мере подтверждения", 1f)

                // The next scan is tied to the selected signal horizon. Very short horizons
                // are useful for a selected/liquid universe; a full-market scan has a safety
                // floor so it does not create an impossible network/CPU load.
                val floorMs = if (scopeMode == "ALL") 15_000L else 5_000L
                val cycleMs = when (tf) {
                    "15M" -> 15_000L
                    "1H" -> 20_000L
                    "4H" -> 30_000L
                    "1W" -> 45_000L
                    else -> 20_000L
                }
                var remaining = max(floorMs, cycleMs) / 1000L
                while (remaining > 0 && currentCoroutineContext().isActive && running.get()) {
                    delay(1_000L)
                    remaining--
                }
            }
        } catch (_: CancellationException) {
        } catch (t: Throwable) {
            prefs.edit().putString("scanner_status", "Ошибка сканера: ${t.message ?: "неизвестная ошибка"}").apply()
            updateForeground("Сканер: ошибка", t.message ?: "Неизвестная ошибка", 0f)
        } finally {
            prefs.edit().putBoolean("scanner_running", false).putBoolean("scanner_priority_active", false).apply()
            running.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun timeframeHorizonSeconds(tf: String): Long = when (tf) {
        "15M" -> 15L * 60L
        "1H" -> 60L * 60L
        "4H" -> 4L * 60L * 60L
        "1W" -> 7L * 24L * 60L * 60L
        else -> 24L * 60L * 60L
    }
    private fun formatHorizonSeconds(s: Long): String = when { s < 60 -> "$s сек"; s < 3600 -> "${s / 60} мин"; s < 86400 -> "${s / 3600} ч"; else -> "${s / 86400} дн" }
    private fun formatClock(ms: Long): String = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(ms))
    private fun loadScanRows(p: android.content.SharedPreferences): List<ScanRow> = p.getStringSet("auto_scan_results", emptySet()).orEmpty().mapNotNull { a ->
        val x = a.split("|", limit = 9)
        if (x.size < 6) null else ScanRow(SearchResult(x[0], x[0], "", ""), x[1], x[2], x[3].toIntOrNull() ?: 0, x[4].toDoubleOrNull() ?: 0.0, x[5].toDoubleOrNull() ?: 0.0, x.getOrNull(6)?.toLongOrNull() ?: 0L, x.getOrNull(7)?.toLongOrNull() ?: 0L, x.getOrNull(8)?.toLongOrNull() ?: 0L)
    }

    private fun resolveSymbols(repo: MarketRepository, scopeMode: String, type: String): List<String> {
        if (scopeMode == "SELECTED") {
            val favs = prefs.getStringSet("favorites", emptySet()).orEmpty().toList()
            if (type == "FX") {
                val fx = repo.fxCatalog().map { it.symbol }.toSet()
                return favs.filter { it in fx || it.contains("/") || it.contains("=") }.distinct()
            }
            return when (type) {
                "STOCKS" -> favs.filterNot { it.contains("/") || it.contains("=") }.distinct()
                else -> favs.distinct()
            }
        }
        // Use a scanner-specific catalogue. The general catalogue also contains
        // bonds/options/futures and can hit BCS rate limits; that used to make the
        // scanner receive an empty universe and therefore produce zero signals.
        val catalog = runCatching { repo.scannerCatalog(type) }.getOrDefault(emptyList())
        val stocks = if (type == "STOCKS" || type == "ALL") catalog.filter {
            val t = it.type.uppercase(Locale.US)
            t.contains("STOCK") || t.contains("EQUITY") || t.contains("ETF") || t.contains("DEPOSITARY") || t.contains("FUND")
        }.map { it.symbol } else emptyList()
        val fx = if (type == "FX" || type == "ALL") catalog.filter {
            it.type.contains("CURRENCY", true) || it.symbol.endsWith("=X")
        }.map { it.symbol } else emptyList()
        return (stocks + fx).distinct()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Сканер Market Forecast", NotificationManager.IMPORTANCE_LOW).apply { description = "Фоновое сканирование рынка" })
        }
    }

    private fun notification(title: String, text: String, ongoing: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 9011, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.mfp_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun updateForeground(title: String, text: String, progress: Float) {
        val n = notification(title, text, true)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.mfp_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 9011, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, (progress.coerceIn(0f, 1f) * 100).toInt(), progress <= 0f)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
    }

    private fun stopScanner() {
        running.set(false)
        job?.cancel()
        prefs.edit().putBoolean("scanner_running", false).putBoolean("scanner_priority_active", false).putString("scanner_status", "Сканер остановлен").apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running.set(false)
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.marketforecast.prox.action.START_SCANNER"
        const val ACTION_STOP = "com.marketforecast.prox.action.STOP_SCANNER"
        const val EXTRA_SCOPE = "scope"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TF = "tf"
        const val CHANNEL = "mfp_scanner"
        const val NOTIFICATION_ID = 78032
    }
}

}