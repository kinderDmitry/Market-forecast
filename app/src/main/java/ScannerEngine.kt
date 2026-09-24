package com.marketforecast.prox

import java.util.concurrent.CancellationException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.Phaser
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Streaming whole-market scanner. Search/autocomplete and this engine never share a
 * fake/secondary price source: instrument identity and market data are BCS-only.
 *
 * Each instrument is processed through the selected timeframes in order. Different
 * instruments may run concurrently through a small worker pool, which keeps the UI
 * responsive without creating thousands of requests/threads.
 */
class ScannerEngine(private val repo: MarketRepository) {
    data class Config(
        val universe: Universe = Universe.ALL,
        val timeframes: List<String> = listOf("15M", "1H", "4H", "1D", "1W"),
        val workers: Int = 6,
        val minimumConfidence: Int = 68,
        // AnalyticsEngine score is normalized to -10..+10, not -100..+100.
        // The old 55 threshold made every scanner result impossible.
        val minimumScoreAbs: Double = 4.0
    )

    enum class Universe { ALL, RUSSIAN_STOCKS, CURRENCIES, FAVORITES }

    data class Result(
        val instrument: SearchResult,
        val signal: String,
        val confidence: Int,
        val score: Double,
        val entry: Double,
        val stop: Double,
        val tp1: Double,
        val tp2: Double,
        val tp3: Double,
        val rr: Double,
        val regime: String,
        val timeframeScores: Map<String, Double>,
        val timeframeConfidence: Map<String, Int>,
        val updatedAt: Long
    )

    data class Progress(
        val discovered: Int,
        val completed: Int,
        val signals: Int,
        val active: Boolean
    )

    private data class TfData(val timeframe: String, val candles: List<Candle>, val forecast: Forecast)

    fun scan(
        config: Config,
        favorites: Set<String> = emptySet(),
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: (Progress) -> Unit = {},
        onResult: (Result) -> Unit = {}
    ) {
        require(repo.isBcsConfigured()) { "БКС не подключен" }
        val tfs = config.timeframes.distinct().filter { it in setOf("15M", "1H", "4H", "1D", "1W") }
        require(tfs.isNotEmpty()) { "Выберите хотя бы один таймфрейм" }

        val queue = Executors.newFixedThreadPool(config.workers.coerceIn(1, 6))
        val completion = Phaser(1)
        val maxInFlight = config.workers.coerceIn(1, 6) * 2
        val permits = Semaphore(maxInFlight)
        val discovered = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val signals = AtomicInteger(0)
        val submitted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun submit(instrument: SearchResult) {
            if (cancelled.get() || !allowed(instrument)) return
            val key = "${instrument.symbol.uppercase(Locale.US)}@${instrument.classCode.uppercase(Locale.US)}"
            if (!submitted.add(key)) return
            // Backpressure: never enqueue the entire BCS universe into an unbounded
            // executor queue. At most workers*2 analyses are in flight/queued; the
            // catalogue API naturally pauses until capacity is available.
            permits.acquireUninterruptibly()
            if (cancelled.get()) {
                permits.release()
                return
            }
            discovered.incrementAndGet()
            onProgress(Progress(discovered.get(), completed.get(), signals.get(), true))
            completion.register()
            queue.submit {
                try {
                    if (!cancelled.get()) {
                        runCatching { analyzeInstrument(instrument, tfs, config) }.getOrNull()?.let { result ->
                            if (!cancelled.get()) {
                                signals.incrementAndGet()
                                onResult(result)
                            }
                        }
                    }
                } finally {
                    permits.release()
                    completed.incrementAndGet()
                    completion.arriveAndDeregister()
                    onProgress(Progress(discovered.get(), completed.get(), signals.get(), !cancelled.get()))
                }
            }
        }

        try {
            if (config.universe == Universe.FAVORITES) {
                favorites.mapNotNull { runCatching { repo.resolveSelectedInstrument(it, "ALL") }.getOrNull() }
                    .forEach(::submit)
            } else {
                // Page-by-page BCS streaming: workers start as soon as the first
                // directory page arrives. The full universe never has to be held
                // in memory before analysis begins.
                val apiErrors = java.util.Collections.synchronizedList(mutableListOf<String>())
                repo.streamScannerUniverse(config.universe, ::submit) { apiErrors += it }
                if (discovered.get() == 0 && !cancelled.get()) {
                    val detail = apiErrors.joinToString("; ").ifBlank { "пустой ответ каталога" }
                    throw IllegalStateException("БКС: не удалось получить инструменты для сканирования. $detail")
                }
                // Non-fatal directory errors are intentionally not promoted to a global
                // scanner failure when another BCS API stream already supplied instruments.
                if (apiErrors.isNotEmpty()) onProgress(Progress(discovered.get(), completed.get(), signals.get(), true))
            }
            // Wait without retaining one Future object per instrument. The Phaser keeps
            // completion tracking bounded even for a full multi-thousand-instrument scan.
            completion.arriveAndAwaitAdvance()
        } catch (ce: CancellationException) {
            cancelled.set(true)
            throw ce
        } finally {
            queue.shutdownNow()
            onProgress(Progress(discovered.get(), completed.get(), signals.get(), false))
        }
    }

    private fun analyzeInstrument(instrument: SearchResult, timeframes: List<String>, config: Config): Result? {
        val canonical = if (instrument.classCode.isBlank()) instrument.symbol else "${instrument.symbol}@${instrument.classCode}"
        // Coverage is never silently reduced. The scanner always evaluates every selected
        // timeframe in the configured order; higher timeframes are not dropped just because
        // a lower timeframe looks weak. This is critical for true MTF consensus.
        val ordered = timeframes.distinct().filter { it in setOf("15M", "1H", "4H", "1D", "1W") }
        val data = ArrayList<TfData>(ordered.size)

        fun history(tf: String): Pair<String, String> = when (tf) {
            "15M" -> "7d" to "15m"
            "1H" -> "30d" to "1h"
            "4H" -> "120d" to "4h"
            "1D" -> "4y" to "1d"
            "1W" -> "8y" to "1wk"
            else -> "4y" to "1d"
        }

        ordered.forEach { tf ->
            val pair = history(tf)
            runCatching {
                val candles = repo.load(canonical, pair.first, pair.second)
                if (candles.size >= 60) data += TfData(tf, candles, AnalyticsEngine.analyze(candles))
            }
        }
        if (data.isEmpty()) return null

        val direction = consensusDirection(data)
        if (direction.isBlank()) return null
        val aligned = data.count { it.forecast.signal == direction }
        if (aligned < requiredConsensus(data.size)) return null

        val weightedScore = weighted(data) { it.forecast.score }
        val weightedConfidence = weighted(data) { it.forecast.confidence.toDouble() }.toInt().coerceIn(0, 100)
        val confidence = ((weightedConfidence * 0.68) + (consensusStrength(data, direction) * 32.0)).toInt().coerceIn(0, 100)
        val score = (weightedScore * (0.72 + consensusStrength(data, direction) * 0.28)).coerceIn(-100.0, 100.0)
        if (confidence < config.minimumConfidence || abs(score) < config.minimumScoreAbs) return null
        if (higherTimeframeConflict(data, direction)) return null

        // Execution is selected from the fastest available timeframe, while direction is
        // validated against the complete MTF set. This preserves responsive entries without
        // sacrificing daily/weekly context.
        val execution = data.firstOrNull { it.timeframe == "15M" }?.forecast
            ?: data.firstOrNull { it.timeframe == "1H" }?.forecast
            ?: data.firstOrNull { it.timeframe == "4H" }?.forecast
            ?: data.minByOrNull { timeframeWeight(it.timeframe) }?.forecast
            ?: data.last().forecast
        if (!execution.highConviction && confidence < 72) return null
        if (execution.rr < 1.35 || execution.tp2Probability < 0.35 || execution.expectedValueR < 0.03) return null

        return Result(
            instrument = instrument,
            signal = direction,
            confidence = confidence,
            score = score,
            entry = execution.entry,
            stop = execution.stop,
            tp1 = execution.tp1,
            tp2 = execution.tp2,
            tp3 = execution.tp3,
            rr = execution.rr,
            regime = execution.regime,
            timeframeScores = data.associate { it.timeframe to it.forecast.score },
            timeframeConfidence = data.associate { it.timeframe to it.forecast.confidence },
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun consensusDirection(data: List<TfData>): String {
        val long = data.count { it.forecast.signal == "LONG" }
        val short = data.count { it.forecast.signal == "SHORT" }
        val weightedLong = data.filter { it.forecast.signal == "LONG" }.sumOf { timeframeWeight(it.timeframe) }
        val weightedShort = data.filter { it.forecast.signal == "SHORT" }.sumOf { timeframeWeight(it.timeframe) }
        val required = requiredConsensus(data.size)
        return when {
            long >= required && weightedLong > weightedShort -> "LONG"
            short >= required && weightedShort > weightedLong -> "SHORT"
            else -> ""
        }
    }

    private fun requiredConsensus(size: Int): Int = when (size) {
        1 -> 1
        2 -> 2
        3 -> 2
        else -> 2
    }

    private fun consensusStrength(data: List<TfData>, direction: String): Double =
        data.count { it.forecast.signal == direction }.toDouble() / data.size.coerceAtLeast(1)

    private fun timeframeWeight(tf: String): Double = when (tf) {
        "15M" -> 1.0
        "1H" -> 1.5
        "4H" -> 2.2
        "1D" -> 3.0
        "1W" -> 4.2
        else -> 1.0
    }

    private fun higherTimeframeConflict(data: List<TfData>, direction: String): Boolean {
        val higher = data.filter { it.timeframe == "4H" || it.timeframe == "1D" || it.timeframe == "1W" }
        return higher.any {
            (direction == "LONG" && it.forecast.signal == "SHORT" && it.forecast.confidence >= 70) ||
            (direction == "SHORT" && it.forecast.signal == "LONG" && it.forecast.confidence >= 70)
        }
    }

    private fun weighted(data: List<TfData>, value: (TfData) -> Double): Double {
        var sum = 0.0
        var weight = 0.0
        data.forEach { d ->
            val w = timeframeWeight(d.timeframe)
            sum += value(d) * w
            weight += w
        }
        return if (weight == 0.0) 0.0 else sum / weight
    }

    private fun allowed(item: SearchResult): Boolean {
        val t = item.type.uppercase(Locale.US)
        return t == "STOCK" || t == "FOREIGN_STOCK" || t == "DEPOSITARY_RECEIPTS" || t.contains("CURRENCY") || t.contains("FOREX")
    }
}
