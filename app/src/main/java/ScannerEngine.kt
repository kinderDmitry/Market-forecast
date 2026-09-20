package com.marketforecast.prox

import kotlinx.coroutines.CancellationException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
        val timeframes: List<String> = listOf("15M", "1H", "4H", "1D"),
        val workers: Int = 4,
        val minimumConfidence: Int = 70,
        val minimumScoreAbs: Double = 55.0
    )

    enum class Universe { ALL, FAVORITES }

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
        val tfs = config.timeframes.distinct().filter { it in setOf("15M", "1H", "4H", "1D") }
        require(tfs.isNotEmpty()) { "Выберите хотя бы один таймфрейм" }

        val queue = Executors.newFixedThreadPool(config.workers.coerceIn(1, 6))
        val futures = java.util.Collections.synchronizedList(mutableListOf<Future<*>>())
        val discovered = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val signals = AtomicInteger(0)
        val submitted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun submit(instrument: SearchResult) {
            if (cancelled.get() || !allowed(instrument)) return
            val key = "${instrument.symbol.uppercase(Locale.US)}@${instrument.classCode.uppercase(Locale.US)}"
            if (!submitted.add(key)) return
            discovered.incrementAndGet()
            onProgress(Progress(discovered.get(), completed.get(), signals.get(), true))
            futures += queue.submit {
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
                    completed.incrementAndGet()
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
                repo.streamScannerUniverse(::submit)
            }
            futures.toList().forEach { future ->
                if (!cancelled.get()) runCatching { future.get() }
            }
        } catch (ce: CancellationException) {
            cancelled.set(true)
            throw ce
        } finally {
            if (cancelled.get()) futures.toList().forEach { it.cancel(true) }
            queue.shutdownNow()
            onProgress(Progress(discovered.get(), completed.get(), signals.get(), false))
        }
    }

    private fun analyzeInstrument(instrument: SearchResult, timeframes: List<String>, config: Config): Result? {
        val canonical = if (instrument.classCode.isBlank()) instrument.symbol else "${instrument.symbol}@${instrument.classCode}"
        val data = ArrayList<TfData>(timeframes.size)
        for (tf in timeframes) {
            val pair = when (tf) {
                "15M" -> "15d" to "15m"
                "1H" -> "90d" to "1h"
                "4H" -> "365d" to "4h"
                else -> "5y" to "1d"
            }
            val candles = repo.load(canonical, pair.first, pair.second)
            if (candles.size < 60) return null
            val forecast = AnalyticsEngine.analyze(candles)
            data += TfData(tf, candles, forecast)
        }
        if (data.isEmpty()) return null

        val direction = consensusDirection(data)
        if (direction == "") return null
        val aligned = data.filter { it.forecast.signal == direction }
        if (aligned.size < requiredConsensus(data.size)) return null

        val latest = data.last().forecast
        val weightedScore = weighted(data) { it.forecast.score }
        val weightedConfidence = weighted(data) { it.forecast.confidence.toDouble() }.toInt().coerceIn(0, 100)
        val confidence = ((weightedConfidence * 0.65) + (consensusStrength(data, direction) * 35.0)).toInt().coerceIn(0, 100)
        val score = (weightedScore * (0.70 + consensusStrength(data, direction) * 0.30)).coerceIn(-100.0, 100.0)
        if (confidence < config.minimumConfidence || abs(score) < config.minimumScoreAbs) return null
        if (latest.signal != direction && data.size > 1) return null
        if (!latest.highConviction && confidence < 78) return null

        return Result(
            instrument = instrument,
            signal = direction,
            confidence = confidence,
            score = score,
            entry = latest.entry,
            stop = latest.stop,
            tp1 = latest.tp1,
            tp2 = latest.tp2,
            tp3 = latest.tp3,
            rr = latest.rr,
            regime = latest.regime,
            timeframeScores = data.associate { it.timeframe to it.forecast.score },
            timeframeConfidence = data.associate { it.timeframe to it.forecast.confidence },
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun consensusDirection(data: List<TfData>): String {
        val long = data.count { it.forecast.signal == "LONG" }
        val short = data.count { it.forecast.signal == "SHORT" }
        return when {
            long > short && long >= requiredConsensus(data.size) -> "LONG"
            short > long && short >= requiredConsensus(data.size) -> "SHORT"
            else -> ""
        }
    }

    private fun requiredConsensus(size: Int): Int = when (size) {
        1 -> 1
        2 -> 2
        3 -> 2
        else -> 3
    }

    private fun consensusStrength(data: List<TfData>, direction: String): Double =
        data.count { it.forecast.signal == direction }.toDouble() / data.size.coerceAtLeast(1)

    private fun weighted(data: List<TfData>, value: (TfData) -> Double): Double {
        val weights = mapOf("15M" to 1.0, "1H" to 1.5, "4H" to 2.2, "1D" to 3.0)
        var sum = 0.0
        var weight = 0.0
        data.forEach { d ->
            val w = weights[d.timeframe] ?: 1.0
            sum += value(d) * w
            weight += w
        }
        return if (weight == 0.0) 0.0 else sum / weight
    }

    private fun allowed(item: SearchResult): Boolean {
        val t = item.type.uppercase(Locale.US)
        return t == "STOCK" || t == "FOREIGN_STOCK" || t.contains("CURRENCY") || t.contains("FOREX")
    }
}
