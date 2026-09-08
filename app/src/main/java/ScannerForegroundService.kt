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
        val horizonValue = intent?.getIntExtra(EXTRA_HORIZON_VALUE, -1)?.takeIf { it > 0 } ?: prefs.getInt("scanner_horizon_value", 15).coerceAtLeast(1)
        val horizonUnit = intent?.getStringExtra(EXTRA_HORIZON_UNIT) ?: prefs.getString("scanner_horizon_unit", "SEC") ?: "SEC"
        prefs.edit().putString("scanner_scope", scopeMode).putString("scanner_type", type).putString("scanner_tf", tf).putInt("scanner_horizon_value", horizonValue).putString("scanner_horizon_unit", horizonUnit).putBoolean("scanner_running", true).putBoolean("scanner_priority_active", scopeMode == "ALL").apply()
        startForeground(NOTIFICATION_ID, notification("Сканер работает", "Подготовка данных…", false))
        if (!running.getAndSet(true)) job = scope.launch { scanLoop(scopeMode, type, tf, horizonValue, horizonUnit) }
        return START_STICKY
    }

    private suspend fun scanLoop(scopeMode: String, type: String, tf: String, horizonValue: Int, horizonUnit: String) {
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
                                        "15M" -> "60d" to "15m"
                                        "1H" -> "2y" to "1h"
                                        "4H" -> "2y" to "4h"
                                        "1W" -> "10y" to "1wk"
                                        else -> "5y" to "1d"
                                    }
                                    val result = runCatching {
                                        withTimeout(15_000L) {
                                            val candles = repo.load(symbol, pair.first, pair.second)
                                            if (candles.size < 30) null else {
                                                val rawQuote = repo.quote(symbol)
                                                val live = reconcileLivePrice(symbol, candles, rawQuote)
                                                val merged = mergeRealtimeCandle(candles, live, currentTf, System.currentTimeMillis(), symbol)
                                                val f = AnalyticsEngine.analyze(merged, live)
                                                if (f.signal == "NO TRADE") null else {
                                                    val created = System.currentTimeMillis()
                                                    val hs = horizonSeconds(horizonValue, horizonUnit)
                                                    ScanRow(SearchResult(symbol, symbol, "", ""), currentTf, f.signal, f.confidence, f.score, f.rr, hs, created, created + hs * 1000L)
                                                }
                                            }
                                        }
                                    }.getOrNull()
                                    val done = completed.incrementAndGet()
                                    // SharedPreferences writes are relatively expensive. Do not
                                    // write once per instrument; UI/notification progress remains
                                    // responsive while the analysis threads stay focused on data.
                                    if (done % 25L == 0L || done == total) {
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
                val previous = loadScanRows(prefs).filter { it.expiresAt <= 0L || it.expiresAt > now }
                val previousKeys = previous.map { "${it.result.symbol}|${it.timeframe}|${it.signal}" }.toSet()
                val encoded = sorted.map { listOf(it.result.symbol, it.timeframe, it.signal, it.confidence, it.score, it.rr, it.horizonSeconds, it.createdAt, it.expiresAt).joinToString("|") }.toSet()
                prefs.edit().putStringSet("auto_scan_results", encoded).putLong("scanner_last_run", now).putInt("scanner_last_found", sorted.size).putFloat("scanner_progress", 1f).putString("scanner_status", "Сигналы обновлены: ${sorted.size} • горизонт ${formatHorizon(horizonValue, horizonUnit)}").apply()
                sorted.filter { "${it.result.symbol}|${it.timeframe}|${it.signal}" !in previousKeys }.take(20).forEach { row ->
                    val body = "${row.result.symbol.removeSuffix(".ME")} • ${row.signal} • ${row.confidence}%\nГоризонт: ${formatHorizonSeconds(row.horizonSeconds)}\nСигнал действителен до: ${formatClock(row.expiresAt)}"
                    NotificationHelper.notifyMarket(applicationContext, "scanner|${row.result.symbol}|${row.timeframe}|${row.signal}|${row.createdAt}", "Новый сигнал: ${row.signal}", body, "SCANNER", row.result.symbol)
                }
                updateForeground("Сканер обновлён", "Новых сигналов: ${sorted.count { "${it.result.symbol}|${it.timeframe}|${it.signal}" !in previousKeys }}", 1f)

                // The next scan is tied to the selected signal horizon. Very short horizons
                // are useful for a selected/liquid universe; a full-market scan has a safety
                // floor so it does not create an impossible network/CPU load.
                val horizonMs = horizonSeconds(horizonValue, horizonUnit) * 1000L
                val floorMs = if (scopeMode == "ALL") 15_000L else 5_000L
                var remaining = max(floorMs, min(horizonMs, 60_000L)) / 1000L
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

    private fun horizonSeconds(value: Int, unit: String): Long = when (unit.uppercase(Locale.US)) {
        "SEC" -> value.coerceIn(1, 3600).toLong()
        "MIN" -> value.coerceIn(1, 1440).toLong() * 60L
        "HOUR" -> value.coerceIn(1, 168).toLong() * 3600L
        "DAY" -> value.coerceIn(1, 30).toLong() * 86400L
        else -> value.coerceIn(1, 1440).toLong() * 60L
    }
    private fun formatHorizon(value: Int, unit: String): String = when (unit.uppercase(Locale.US)) {
        "SEC" -> "$value сек"; "MIN" -> "$value мин"; "HOUR" -> "$value ч"; "DAY" -> "$value дн"; else -> "$value"
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
            return when (type) {
                "STOCKS" -> favs.filterNot { it.contains("/") || it.contains("=") }.distinct()
                "FX" -> favs.filter { it.contains("/") || it.contains("=") }.distinct()
                else -> favs.distinct()
            }
        }
        val stocks = if (type == "STOCKS" || type == "ALL") repo.catalog().map { it.symbol }.filter { it.endsWith(".ME") || !it.contains("/") } else emptyList()
        val fx = if (type == "FX" || type == "ALL") repo.fxCatalog().map { it.symbol } else emptyList()
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
        const val EXTRA_HORIZON_VALUE = "horizon_value"
        const val EXTRA_HORIZON_UNIT = "horizon_unit"
        const val CHANNEL = "mfp_scanner"
        const val NOTIFICATION_ID = 78032
    }
}
