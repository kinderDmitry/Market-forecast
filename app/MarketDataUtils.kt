package com.marketforecast.prox

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Shared real-time candle merge used by the screen and the scanner. */
internal fun mergeRealtimeCandle(raw: List<Candle>, live: Double, timeframe: String, now: Long, symbol: String = ""): List<Candle> {
    if (raw.isEmpty() || !live.isFinite() || live <= 0.0) return raw
    val sorted = raw.sortedBy { it.time }
    val step = when (timeframe.uppercase(Locale.US)) {
        "15M" -> 15L * 60_000L
        "1H" -> 60L * 60_000L
        "4H" -> 4L * 60L * 60_000L
        "1W" -> 7L * 24L * 60L * 60_000L
        else -> 24L * 60L * 60_000L
    }
    val bucket = (now / step) * step
    val last = sorted.last()
    if (last.time >= bucket && last.time < bucket + step) {
        return sorted.dropLast(1) + last.copy(
            high = max(last.high, live),
            low = min(last.low, live),
            close = live
        )
    }
    val open = last.close.takeIf { it.isFinite() && it > 0.0 } ?: live
    return sorted + Candle(bucket, open, max(open, live), min(open, live), live, 0.0)
}

/**
 * Rejects obvious provider glitches rather than presenting an impossible price as live.
 * The check is deliberately wide: genuine session gaps are still accepted when they are
 * supported by the recent candle range/ATR. A rejected quote is never fabricated; the
 * latest validated candle close is returned instead.
 */
internal fun validateLiveQuote(candles: List<Candle>, quote: Double?): Double {
    val q = quote?.takeIf { it.isFinite() && it > 0.0 } ?: return candles.lastOrNull()?.close ?: 0.0
    val last = candles.lastOrNull()?.close?.takeIf { it.isFinite() && it > 0.0 } ?: return q
    if (candles.size < 8) return q
    val recent = candles.takeLast(min(40, candles.size))
    val highs = recent.map { it.high }.filter { it.isFinite() && it > 0.0 }
    val lows = recent.map { it.low }.filter { it.isFinite() && it > 0.0 }
    if (highs.isEmpty() || lows.isEmpty()) return q
    val range = max(highs.maxOrNull()!! - lows.minOrNull()!!, last * 0.002)
    val tr = recent.drop(1).mapIndexed { i, c ->
        val p = recent[i]
        max(c.high - c.low, max(abs(c.high - p.close), abs(c.low - p.close)))
    }.filter { it.isFinite() && it >= 0.0 }
    val atr = if (tr.isNotEmpty()) tr.takeLast(min(14, tr.size)).average() else range / 14.0
    val tolerance = max(atr * 4.0, last * 0.025)
    val outside = q < lows.minOrNull()!! - tolerance || q > highs.maxOrNull()!! + tolerance
    return if (outside) last else q
}
