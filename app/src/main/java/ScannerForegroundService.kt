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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import kotlin.math.abs
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
                prefs = prefs,
                context = this@ScannerForegroundService
            )

            while (currentCoroutineContext().isActive && running.get()) {
                val timeframes: List<String> = if (timeframe == "ANY") {
                    listOf("15M", "1H", "4H", "1D", "1W")
                } else {
                    listOf(timeframe)
                }
                val activeRows = loadScanRows(prefs)
                    .filter { it.expiresAt <= 0L || it.expiresAt > System.currentTimeMillis() }
                val previous = activeRows.map { rowKey(it) }.toSet()
                val found = mutableListOf<ScanRow>()
                val completed = AtomicLong(0L)
                val startedAt = System.currentTimeMillis()

                fun acceptRow(candidate: ScanRow) {
                    val old = (activeRows + found).firstOrNull { rowKey(it) == rowKey(candidate) && (it.expiresAt <= 0L || it.expiresAt > System.currentTimeMillis()) }
                    synchronized(found) {
                        found.removeAll { rowKey(it) == rowKey(candidate) }
                        found.add(candidate)
                    }
                    if (old == null && rowKey(candidate) !in previous) notifySignal(candidate)
                }

                suspend fun processInstrument(meta: SearchResult) {
                    if (!running.get()) return
                    val identity = scanIdentity(meta)
                    for (tf in timeframes) {
                        if (!running.get()) break
                        val row = scanOne(repo, identity, tf, meta)
                        val done = completed.incrementAndGet()
                        if (done == 1L || done % 10L == 0L) {
                            val elapsed = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(1L)
                            setStatus("🔎 Проверено: $done • ${elapsed} сек", 0f)
                        }
                        if (row != null) acceptRow(row)
                    }
                }

                if (scopeMode == "SELECTED") {
                    val favorites = prefs.getStringSet("favorites", emptySet()).orEmpty().toList()
                    if (favorites.isEmpty()) {
                        setStatus("Нет инструментов в избранном для сканирования.", 1f)
                    } else {
                        setStatus("БКС: быстрый запуск по избранному…", 0f)
                        coroutineScope {
                            val jobs = favorites.map { favorite ->
                                async(Dispatchers.IO) {
                                    if (!running.get()) return@async
                                    val meta = repo.resolveSelectedInstrument(favorite, instrumentType)
                                    if (meta != null) processInstrument(meta)
                                }
                            }
                            jobs.awaitAll()
                        }
                    }
                } else {
                    // Pipeline scanner: BCS directory pages are streamed into a bounded
                    // queue while a small worker pool performs the full AnalyticsEngine
                    // calculation. This removes the old "one instrument at a time" idle
                    // gaps without hammering BCS with an unbounded number of requests.
                    setStatus("БКС: получаю инструменты и запускаю поток анализа…", 0f)
                    val queue = Channel<SearchResult>(capacity = 24)
                    val producer = launch(Dispatchers.IO) {
                        try {
                            repo.streamScannerInstruments(instrumentType) { meta ->
                                if (running.get()) runBlocking { queue.send(meta) }
                            }
                        } finally {
                            queue.close()
                        }
                    }
                    val workers = (0 until 4).map {
                        launch(Dispatchers.IO) {
                            for (meta in queue) {
                                if (!running.get()) break
                                processInstrument(meta)
                            }
                        }
                    }
                    workers.joinAll()
                    producer.cancelAndJoin()
                }

                val now = System.currentTimeMillis()
                val merged = synchronized(found) {
                    (found + loadScanRows(prefs))
                        .filter { it.expiresAt <= 0L || it.expiresAt > now }
                        .distinctBy { rowKey(it) }
                        .sortedWith(compareByDescending<ScanRow> { it.confidence }.thenByDescending { kotlin.math.abs(it.score) })
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

    private suspend fun scanOne(repo: MarketRepository, symbol: String, timeframe: String, metadata: SearchResult?): ScanRow? {
        val pair = when (timeframe) {
            "15M" -> "60d" to "15m"
            "1H" -> "2y" to "1h"
            "4H" -> "2y" to "4h"
            "1W" -> "10y" to "1wk"
            else -> "2y" to "1d"
        }
        return runCatching {
            withTimeout(60_000L) {
                // Candle history and the canonical live quote are independent BCS
                // requests. Resolve the exact BCS ticker + classCode from metadata so
                // an instrument such as CIAN/CNRU cannot silently jump to another board.
                coroutineScope {
                    val requestSymbol = metadata?.let {
                        if (it.classCode.isNotBlank()) "${it.symbol}@${it.classCode}" else it.symbol
                    } ?: symbol
                    val candlesJob = async(Dispatchers.IO) { repo.load(requestSymbol, pair.first, pair.second) }
                    val quoteJob = async(Dispatchers.IO) { runCatching { repo.quote(requestSymbol) }.getOrNull() }
                    val candles = candlesJob.await()
                    if (candles.size < 30) return@coroutineScope null
                    val quote = quoteJob.await()
                    val live = reconcileLivePrice(requestSymbol, candles, quote)
                    if (!live.isFinite() || live <= 0.0) return@coroutineScope null
                    val merged = mergeRealtimeCandle(candles, live, timeframe, System.currentTimeMillis(), requestSymbol)
                    // Scanner is a strict execution surface of the Forecast engine.
                    // Never manufacture a direction from score/confirmation when the
                    // engine explicitly rejects the setup. This keeps scanner results
                    // identical to instrument forecasts and prevents low-quality trades.
                    val forecast = AnalyticsEngine.analyzeForScanner(merged, live)
                    if (forecast.signal == "NO TRADE") return@coroutineScope null
                    // A scanner card must never fabricate an instrument name. The BCS API result
                    // resolved for this instrument is the source of truth for display metadata.
                    val meta = metadata ?: return@coroutineScope null
                    val created = System.currentTimeMillis()
                    val horizon = timeframeHorizonSeconds(timeframe)
                    ScanRow(
                        result = meta,
                        timeframe = timeframe,
                        signal = if (forecast.signal != "NO TRADE") forecast.signal else if (forecast.score > 0) "LONG" else "SHORT",
                        confidence = forecast.confidence,
                        score = forecast.score,
                        rr = forecast.rr,
                        horizonSeconds = horizon,
                        tp1Probability = forecast.tp1Probability,
                        tp2Probability = forecast.tp2Probability,
                        tp3Probability = forecast.tp3Probability,
                        expectedValueR = forecast.expectedValueR,
                        createdAt = created,
                        expiresAt = created + horizon * 1000L
                    )
                }
            }
        }.getOrNull()
    }

    private fun resolveInstruments(repo: MarketRepository, scopeMode: String, type: String): List<SearchResult> {
        fun matches(item: SearchResult): Boolean {
            val t = item.type.uppercase(Locale.US)
            val isFx = t.contains("CURRENCY") || t.contains("FOREX")
            val isStock = t in setOf("STOCK", "FOREIGN_STOCK", "DEPOSITARY_RECEIPTS")
            return when (type) {
                "FX" -> isFx
                "STOCKS" -> isStock
                "ALL" -> isFx || isStock
                else -> false
            }
        }

        if (scopeMode == "SELECTED") {
            val favorites = prefs.getStringSet("favorites", emptySet()).orEmpty().toList()
            val catalog = runCatching { repo.scannerCatalog(type) }.getOrDefault(emptyList())
            val byCanonical = catalog.associateBy { repo.canonicalSymbol(it.symbol).uppercase(Locale.US) }
            return favorites
                .map { repo.canonicalSymbol(it) }
                .distinct()
                .mapNotNull { key ->
                    byCanonical[key.uppercase(Locale.US)]?.takeIf(::matches)
                        ?: runCatching {
                            val canonical = repo.canonicalSymbol(key)
                            val knownFx = mapOf(
                                "USD000UTSTOM" to "Доллар США",
                                "EUR_RUB__TOM" to "Евро",
                                "CNYRUB_TOM" to "Юань",
                                "GBP_RUB__TOM" to "Фунт стерлингов",
                                "JPY_RUB__TOM" to "Японская иена"
                            )
                            when {
                                type == "ALL" -> SearchResult(canonical, knownFx[canonical] ?: canonical, "БКС", if (canonical in knownFx) "CURRENCY" else "", "БКС")
                                type == "FX" && knownFx.containsKey(canonical) -> SearchResult(canonical, knownFx.getValue(canonical), "БКС", "CURRENCY", "БКС")
                                else -> null
                            }
                        }.getOrNull()
                }
                .distinctBy { scanIdentity(it) }
        }

        val catalog = repo.scannerCatalog(type)
        if (catalog.isEmpty()) throw IllegalStateException("БКС: полный каталог пуст")
        val distinctBoards = catalog.count { it.classCode.isNotBlank() }
        // Full scanner mode must never silently downgrade to a partial BCS API result.
        // Keep every real BCS ticker + classCode pair; the same ticker may exist on
        // multiple boards and collapsing by ticker can drop instruments.
        val resolved = catalog
            .filter(::matches)
            .filter { it.symbol.isNotBlank() }
            .distinctBy { scanIdentity(it) }
        if (resolved.isNotEmpty()) return resolved
        throw IllegalStateException("БКС: каталог не содержит пригодных инструментов (получено ${catalog.size}, board-id: $distinctBoards)")
    }

    private fun loadScanRows(p: android.content.SharedPreferences): List<ScanRow> =
        p.getStringSet("auto_scan_results", emptySet()).orEmpty().mapNotNull { encoded ->
            val x = encoded.split("|", limit = 17)
            if (x.size < 6) return@mapNotNull null
            ScanRow(
                result = SearchResult(
                    x[0],
                    x.getOrNull(13).orEmpty().ifBlank { x[0] },
                    x.getOrNull(14).orEmpty().ifBlank { "БКС" },
                    x.getOrNull(15).orEmpty(),
                    "БКС",
                    x.getOrNull(16).orEmpty()
                ),
                timeframe = x[1],
                signal = x[2],
                confidence = x[3].toIntOrNull() ?: 0,
                score = x[4].toDoubleOrNull() ?: 0.0,
                rr = x[5].toDoubleOrNull() ?: 0.0,
                horizonSeconds = x.getOrNull(6)?.toLongOrNull() ?: 0L,
                createdAt = x.getOrNull(7)?.toLongOrNull() ?: 0L,
                expiresAt = x.getOrNull(8)?.toLongOrNull() ?: 0L,
                tp1Probability = x.getOrNull(9)?.toDoubleOrNull() ?: 0.0,
                tp2Probability = x.getOrNull(10)?.toDoubleOrNull() ?: 0.0,
                tp3Probability = x.getOrNull(11)?.toDoubleOrNull() ?: 0.0,
                expectedValueR = x.getOrNull(12)?.toDoubleOrNull() ?: 0.0
            )
        }

    private fun saveScanRows(p: android.content.SharedPreferences, rows: List<ScanRow>) {
        val encoded = rows.map {
            listOf(
                it.result.symbol, it.timeframe, it.signal, it.confidence,
                it.score, it.rr, it.horizonSeconds, it.createdAt, it.expiresAt,
                it.tp1Probability, it.tp2Probability, it.tp3Probability, it.expectedValueR,
                it.result.name, it.result.exchange, it.result.type, it.result.classCode
            ).joinToString("|")
        }.toSet()
        p.edit().putStringSet("auto_scan_results", encoded).apply()
    }

    private fun scanIdentity(item: SearchResult): String =
        "${repoCanonical(item.symbol)}@${item.classCode.trim().uppercase(Locale.US)}"

    private fun repoCanonical(symbol: String): String = symbol.trim().uppercase(Locale.US)

    private fun rowKey(row: ScanRow): String =
        "${row.result.symbol}@${row.result.classCode}|${row.timeframe}|${row.signal}"

    private fun notifySignal(row: ScanRow) {
        val body = "${row.result.symbol} — ${row.result.name} • ${row.signal} • ${row.confidence}%\n" +
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
