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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Foreground scanner. Keeps the scanner independent from the Activity and writes all
 * state to SharedPreferences so the UI can reconnect after rotation/process recreation.
 */
class ScannerForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var scanJob: Job? = null
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopScanner()
            return START_NOT_STICKY
        }

        val scopeMode = intent?.getStringExtra(EXTRA_SCOPE)
            ?: prefs.getString("scanner_scope", "ALL") ?: "ALL"
        val instrumentType = intent?.getStringExtra(EXTRA_TYPE)
            ?: prefs.getString("scanner_type", "ALL") ?: "ALL"
        val timeframe = intent?.getStringExtra(EXTRA_TF)
            ?: prefs.getString("scanner_tf", "1D") ?: "1D"

        prefs.edit()
            .putString("scanner_scope", scopeMode)
            .putString("scanner_type", instrumentType)
            .putString("scanner_tf", timeframe)
            .putBoolean("scanner_running", true)
            .putBoolean("scanner_priority_active", scopeMode == "ALL")
            .putFloat("scanner_progress", 0f)
            .putString("scanner_status", "Подготовка сканирования…")
            .remove("scanner_completion_signature")
            .apply()

        startForeground(NOTIFICATION_ID, notification("🔎 Сканер работает", "⏳ Подготовка данных…", 0f))
        if (running.compareAndSet(false, true)) {
            scanJob = serviceScope.launch { scanLoop(scopeMode, instrumentType, timeframe) }
        }
        return START_STICKY
    }

    private suspend fun scanLoop(scopeMode: String, instrumentType: String, timeframe: String) {
        try {
            val repo = MarketRepository(
                bcsRefreshToken = prefs.getString("bcs_refresh_token", "")?.ifBlank { null },
                prefs = prefs
            )

            while (currentCoroutineContext().isActive && running.get()) {
                val symbols = withContext(Dispatchers.IO) {
                    resolveSymbols(repo, scopeMode, instrumentType)
                }
                val timeframes: List<String> = if (timeframe == "ANY") {
                    listOf("15M", "1H", "4H", "1D", "1W")
                } else {
                    listOf(timeframe)
                }

                val total = (symbols.size.toLong() * timeframes.size.toLong()).coerceAtLeast(1L)
                val completed = AtomicLong(0L)
                val activeRows = loadScanRows(prefs)
                    .filter { it.expiresAt <= 0L || it.expiresAt > System.currentTimeMillis() }
                val previous = activeRows.map { rowKey(it) }.toSet()
                val found = mutableListOf<ScanRow>()

                fun acceptRow(candidate: ScanRow) {
                    val old = (activeRows + found).firstOrNull { rowKey(it) == rowKey(candidate) && (it.expiresAt <= 0L || it.expiresAt > System.currentTimeMillis()) }
                    // Every completed scan is a fresh validation event. Keep the same row key,
                    // but renew its action horizon so the countdown never gets stuck on
                    // the previous scan cycle.
                    val stable = candidate
                    synchronized(found) {
                        found.removeAll { rowKey(it) == rowKey(stable) }
                        found.add(stable)
                    }
                    if (old == null && rowKey(candidate) !in previous) notifySignal(stable)
                }

                if (symbols.isEmpty()) {
                    setStatus("Нет инструментов для сканирования. Проверьте БКС и избранное.", 1f)
                } else {
                    // Worker-pool model: unlike the old fixed batches, workers keep
                    // consuming symbols as soon as a request finishes. This removes
                    // the "wait for the slowest 8th request" pauses on market-wide scans.
                    val concurrency = if (scopeMode == "SELECTED") 8 else 12
                    val gate = Semaphore(concurrency)
                    coroutineScope {
                        val jobs = symbols.flatMap { symbol ->
                            if (!currentCoroutineContext().isActive || !running.get()) return@flatMap emptyList()
                            timeframes.map { currentTf ->
                                async(Dispatchers.IO) {
                                    gate.withPermit {
                                        val row = scanOne(repo, symbol, currentTf)
                                        val done = completed.incrementAndGet()
                                        val progress = (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                                        if (done == 1L || done % 5L == 0L || done == total) {
                                            setStatus("🔎 Сканирование: $done / $total", progress)
                                        }
                                        row
                                    }
                                }
                            }
                        }
                        jobs.awaitAll().forEach { row ->
                            if (row != null) acceptRow(row)
                        }
                    }
                }

                val now = System.currentTimeMillis()
                val merged = synchronized(found) {
                    (found + loadScanRows(prefs))
                        .filter { it.expiresAt <= 0L || it.expiresAt > now }
                        .distinctBy { rowKey(it) }
                        .sortedWith(compareByDescending<ScanRow> { it.confidence }.thenByDescending { abs(it.score) })
                }
                saveScanRows(prefs, merged)
                prefs.edit()
                    .putLong("scanner_last_run", now)
                    .putInt("scanner_last_found", merged.size)
                    .putFloat("scanner_progress", 1f)
                    .putString(
                        "scanner_status",
                        if (merged.isEmpty()) {
                            "🔎 Сканирование завершено: подтверждённых сигналов пока нет • проверено ${completed.get()}"
                        } else {
                            "🔔 Сигналы обновлены: ${merged.size} • проверено ${completed.get()}"
                        }
                    )
                    .apply()
                updateForeground("✅ Сканирование завершено", "Найдено сигналов: ${merged.size}", 1f)
                // A foreground scanner may run continuously. Do not spam a completion
                // notification after every refresh cycle when nothing has changed.
                // Notify once for a completed scan state and again only when the signal
                // set/count changes or the user starts a new scan.
                val completionSignature = merged
                    .map { "${rowKey(it)}|${it.confidence}" }
                    .sorted()
                    .joinToString(";")
                val previousCompletionSignature = prefs.getString("scanner_completion_signature", null)
                val completionChanged = completionSignature != previousCompletionSignature
                if (completionChanged) {
                    NotificationHelper.notifyDirect(
                        applicationContext,
                        NotificationHelper.CHANNEL_MARKET,
                        if (merged.isEmpty()) "🔎 Сканирование завершено" else "🔔 Сканирование завершено",
                        if (merged.isEmpty()) "📭 Проверено: ${completed.get()} • подтверждённых сигналов пока нет" else "🎯 Найдено сигналов: ${merged.size} • проверено: ${completed.get()}",
                        "SCANNER",
                        null,
                        null,
                        (now and 0x7fffffff).toInt()
                    )
                    prefs.edit().putString("scanner_completion_signature", completionSignature).apply()
                }

                val pauseMs = when (timeframe) {
                    "15M" -> 15_000L
                    "1H" -> 20_000L
                    "4H" -> 30_000L
                    "1W" -> 45_000L
                    else -> 20_000L
                }
                delay(pauseMs)
            }
        } catch (_: CancellationException) {
            // Normal service cancellation.
        } catch (t: Throwable) {
            val message = t.message ?: "Неизвестная ошибка"
            prefs.edit().putString("scanner_status", "Ошибка сканера: $message").apply()
            updateForeground("❌ Сканер: ошибка", message, 0f)
        } finally {
            running.set(false)
            prefs.edit()
                .putBoolean("scanner_running", false)
                .putBoolean("scanner_priority_active", false)
                .apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun scanOne(repo: MarketRepository, symbol: String, timeframe: String): ScanRow? {
        val pair = when (timeframe) {
            "15M" -> "60d" to "15m"
            "1H" -> "2y" to "1h"
            "4H" -> "2y" to "4h"
            "1W" -> "10y" to "1wk"
            else -> "2y" to "1d"
        }
        return runCatching {
            withTimeout(20_000L) {
                // Candle history and the canonical live quote are independent BCS
                // requests. Fetch them concurrently so a slow quote endpoint does not
                // unnecessarily serialize the entire scanner.
                coroutineScope {
                    val candlesJob = async(Dispatchers.IO) { repo.load(symbol, pair.first, pair.second) }
                    val quoteJob = async(Dispatchers.IO) { runCatching { repo.quote(symbol) }.getOrNull() }
                    val candles = candlesJob.await()
                    if (candles.size < 30) return@coroutineScope null
                    val quote = quoteJob.await()
                    val live = reconcileLivePrice(symbol, candles, quote)
                if (!live.isFinite() || live <= 0.0) return@withTimeout null
                val merged = mergeRealtimeCandle(candles, live, timeframe, System.currentTimeMillis(), symbol)
                    // The scanner deliberately calls the same admission path as the
                    // Forecast screen. A signal shown here must therefore match the
                    // forecast direction for the same candles and live price.
                    val forecast = AnalyticsEngine.analyzeForScanner(merged, live)
                    if (forecast.signal == "NO TRADE") return@coroutineScope null
                    val created = System.currentTimeMillis()
                    val horizon = timeframeHorizonSeconds(timeframe)
                    ScanRow(
                        result = SearchResult(symbol, symbol, "", "", "БКС"),
                        timeframe = timeframe,
                        signal = forecast.signal,
                        confidence = forecast.confidence,
                        score = forecast.score,
                        rr = forecast.rr,
                        horizonSeconds = horizon,
                        createdAt = created,
                        expiresAt = created + horizon * 1000L
                    )
                }
            }
        }.getOrNull()
    }

    private fun resolveSymbols(repo: MarketRepository, scopeMode: String, type: String): List<String> {
        if (scopeMode == "SELECTED") {
            val favorites = prefs.getStringSet("favorites", emptySet()).orEmpty().toList()
            return when (type) {
                "FX" -> {
                    val fx = runCatching { repo.fxCatalog().map { it.symbol }.toSet() }.getOrDefault(emptySet())
                    favorites.filter { it in fx || it.contains("/") || it.endsWith("=X") }
                }
                "STOCKS" -> favorites.filterNot { it.contains("/") || it.endsWith("=X") }
                else -> favorites
            }.distinct()
        }

        val catalog = runCatching { repo.scannerCatalog(type) }.getOrDefault(emptyList())
        val stocks = if (type == "STOCKS" || type == "ALL") {
            catalog.filter {
                val t = it.type.uppercase(Locale.US)
                t.contains("STOCK") || t.contains("EQUITY") || t.contains("ETF") ||
                    t.contains("DEPOSITARY") || t.contains("FUND")
            }.map { it.symbol }
        } else emptyList()
        val fx = if (type == "FX" || type == "ALL") {
            catalog.filter { it.type.contains("CURRENCY", true) || it.symbol.endsWith("=X") }
                .map { it.symbol }
        } else emptyList()
        return (stocks + fx).distinct()
    }

    private fun loadScanRows(p: android.content.SharedPreferences): List<ScanRow> =
        p.getStringSet("auto_scan_results", emptySet()).orEmpty().mapNotNull { encoded ->
            val x = encoded.split("|", limit = 9)
            if (x.size < 6) return@mapNotNull null
            ScanRow(
                result = SearchResult(x[0], x[0], "", "", "БКС"),
                timeframe = x[1],
                signal = x[2],
                confidence = x[3].toIntOrNull() ?: 0,
                score = x[4].toDoubleOrNull() ?: 0.0,
                rr = x[5].toDoubleOrNull() ?: 0.0,
                horizonSeconds = x.getOrNull(6)?.toLongOrNull() ?: 0L,
                createdAt = x.getOrNull(7)?.toLongOrNull() ?: 0L,
                expiresAt = x.getOrNull(8)?.toLongOrNull() ?: 0L
            )
        }

    private fun saveScanRows(p: android.content.SharedPreferences, rows: List<ScanRow>) {
        val encoded = rows.map {
            listOf(
                it.result.symbol, it.timeframe, it.signal, it.confidence,
                it.score, it.rr, it.horizonSeconds, it.createdAt, it.expiresAt
            ).joinToString("|")
        }.toSet()
        p.edit().putStringSet("auto_scan_results", encoded).apply()
    }

    private fun rowKey(row: ScanRow): String =
        "${row.result.symbol}|${row.timeframe}|${row.signal}"

    private fun notifySignal(row: ScanRow) {
        val body = "${row.result.symbol.removeSuffix(".ME")} • ${row.signal} • ${row.confidence}%\n" +
            "Горизонт: ${formatHorizonSeconds(row.horizonSeconds)}"
        NotificationHelper.notifyMarket(
            applicationContext,
            "scanner|${row.result.symbol}|${row.timeframe}|${row.signal}|${row.createdAt}",
            "🚨 Новый сигнал: ${row.signal}",
            body,
            "SCANNER",
            row.result.symbol
        )
    }

    private fun setStatus(status: String, progress: Float) {
        prefs.edit().putString("scanner_status", status).putFloat("scanner_progress", progress).apply()
        updateForeground("🔎 Сканер работает", status, progress)
    }

    private fun timeframeHorizonSeconds(tf: String): Long = when (tf) {
        "15M" -> 15L * 60L
        "1H" -> 60L * 60L
        "4H" -> 4L * 60L * 60L
        "1W" -> 7L * 24L * 60L * 60L
        else -> 24L * 60L * 60L
    }

    private fun formatHorizonSeconds(seconds: Long): String = when {
        seconds < 60L -> "$seconds сек"
        seconds < 3600L -> "${seconds / 60L} мин"
        seconds < 86400L -> "${seconds / 3600L} ч"
        else -> "${seconds / 86400L} дн"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Сканер Market Forecast",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Фоновое сканирование рынка" }
            )
        }
    }

    private fun notification(title: String, text: String, progress: Float): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            9011,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.mfp_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, (progress.coerceIn(0f, 1f) * 100f).toInt(), progress <= 0f)
            .build()
    }

    private fun updateForeground(title: String, text: String, progress: Float) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(title, text, progress))
    }

    private fun stopScanner() {
        running.set(false)
        scanJob?.cancel()
        scanJob = null
        if (::prefs.isInitialized) {
            prefs.edit()
                .putBoolean("scanner_running", false)
                .putBoolean("scanner_priority_active", false)
                .putString("scanner_status", "Сканер остановлен")
                .apply()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running.set(false)
        scanJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val PREFS = "mfprefs"
        const val ACTION_START = "com.marketforecast.prox.action.START_SCANNER"
        const val ACTION_STOP = "com.marketforecast.prox.action.STOP_SCANNER"
        const val EXTRA_SCOPE = "scope"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TF = "tf"
        const val CHANNEL = "mfp_scanner"
        const val NOTIFICATION_ID = 78032
    }
}
