package com.marketforecast.prox

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Concrete price events instead of "the trend is up": a squeeze that is being released, a range breakout, a bounce from the
 * edge of a range, a pullback to the average inside a trend, a momentum candle. Every bar is checked in O(1) on precomputed
 * series, so the SAME detector can be run over the whole history of the instrument. That gives each live setup its own
 * statistics ("what happened after this exact event on this exact instrument"), which are what the plan is built on.
 */
internal object SetupDetector {
    const val SQUEEZE = "SQUEEZE"
    const val BREAKOUT = "BREAKOUT"
    const val REVERSION = "REVERSION"
    const val PULLBACK = "PULLBACK"
    const val BURST = "BURST"

    class Setup(val type: String, val direction: Int, val strength: Double)

    class Series(
        val open: DoubleArray, val high: DoubleArray, val low: DoubleArray, val close: DoubleArray, val volume: DoubleArray,
        val atr: DoubleArray, val ema20: DoubleArray, val ema50: DoubleArray, val rsi: DoubleArray,
        val bbUpper: DoubleArray, val bbLower: DoubleArray, val widthPct: DoubleArray,
        val donchHigh: DoubleArray, val donchLow: DoubleArray, val volAvg: DoubleArray, val efficiency: DoubleArray
    )

    private fun ema(x: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(x.size)
        if (x.isEmpty()) return out
        val k = 2.0 / (period + 1.0)
        out[0] = x[0]
        for (i in 1 until x.size) out[i] = x[i] * k + out[i - 1] * (1.0 - k)
        return out
    }

    fun series(c: List<Candle>): Series {
        val n = c.size
        val o = DoubleArray(n) { c[it].open }
        val h = DoubleArray(n) { c[it].high }
        val l = DoubleArray(n) { c[it].low }
        val cl = DoubleArray(n) { c[it].close }
        val v = DoubleArray(n) { c[it].volume }
        val tr = DoubleArray(n)
        if (n > 0) tr[0] = h[0] - l[0]
        for (i in 1 until n) tr[i] = max(h[i] - l[i], max(abs(h[i] - cl[i - 1]), abs(l[i] - cl[i - 1])))
        val atr = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            sum += tr[i]
            if (i >= 14) sum -= tr[i - 14]
            atr[i] = sum / min(i + 1, 14)
        }
        val rsi = DoubleArray(n) { 50.0 }
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1 until n) {
            val d = cl[i] - cl[i - 1]
            val up = max(d, 0.0)
            val down = max(-d, 0.0)
            if (i <= 14) {
                avgGain += up / 14.0
                avgLoss += down / 14.0
                rsi[i] = if (i < 14) 50.0 else if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
            } else {
                avgGain = (avgGain * 13.0 + up) / 14.0
                avgLoss = (avgLoss * 13.0 + down) / 14.0
                rsi[i] = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
            }
        }
        val upper = DoubleArray(n)
        val lower = DoubleArray(n)
        val width = DoubleArray(n)
        for (i in 19 until n) {
            var total = 0.0
            for (j in i - 19..i) total += cl[j]
            val mean = total / 20.0
            var squares = 0.0
            for (j in i - 19..i) {
                val d = cl[j] - mean
                squares += d * d
            }
            val sd = sqrt(squares / 20.0)
            upper[i] = mean + 2.0 * sd
            lower[i] = mean - 2.0 * sd
            width[i] = if (mean > 0.0) 4.0 * sd / mean else 0.0
        }
        // How narrow the bands are compared with their own last 100 values: 0 = the narrowest, 1 = the widest.
        val widthPct = DoubleArray(n) { 0.5 }
        for (i in 40 until n) {
            val from = max(19, i - 100)
            var count = 0
            for (j in from until i) if (width[j] <= width[i]) count += 1
            if (i > from) widthPct[i] = count.toDouble() / (i - from)
        }
        val donchHigh = DoubleArray(n)
        val donchLow = DoubleArray(n)
        val volAvg = DoubleArray(n)
        for (i in 20 until n) {
            var hi = h[i - 20]
            var lo = l[i - 20]
            var vol = 0.0
            for (j in i - 20 until i) {
                if (h[j] > hi) hi = h[j]
                if (l[j] < lo) lo = l[j]
                vol += v[j]
            }
            donchHigh[i] = hi
            donchLow[i] = lo
            volAvg[i] = vol / 20.0
        }
        // Kaufman efficiency over 14 candles: 1 = a straight line (trend), 0 = pure back and forth (range).
        val efficiency = DoubleArray(n) { 0.5 }
        for (i in 14 until n) {
            val net = abs(cl[i] - cl[i - 14])
            var path = 0.0
            for (j in i - 13..i) path += abs(cl[j] - cl[j - 1])
            efficiency[i] = if (path > 0.0) net / path else 0.0
        }
        return Series(o, h, l, cl, v, atr, ema(cl, 20), ema(cl, 50), rsi, upper, lower, widthPct, donchHigh, donchLow, volAvg, efficiency)
    }

    /** The strongest concrete event on candle [i], or null. */
    fun detect(s: Series, i: Int): Setup? {
        if (i < 60 || i >= s.close.size) return null
        val o = s.open[i]
        val h = s.high[i]
        val l = s.low[i]
        val c = s.close[i]
        val v = s.volume[i]
        val a = s.atr[i - 1]
        if (!(a > 0.0)) return null
        val range = h - l
        if (!(range > 0.0)) return null
        val hasVolume = s.volAvg[i] > 0.0 && v > 0.0
        val volRatio = if (hasVolume) v / s.volAvg[i] else 1.0
        val volumeUnknown = s.volAvg[i] <= 0.0
        val closeHigh = (c - l) / range
        val closeLow = (h - c) / range
        var best: Setup? = null
        fun offer(type: String, direction: Int, strength: Double) {
            val b = best
            if (b == null || strength > b.strength) best = Setup(type, direction, strength)
        }

        // 1. Squeeze released: the bands were narrow for several candles and a strong candle closes outside them.
        var narrow = true
        for (j in i - 5 until i) if (s.widthPct[j] > 0.30) narrow = false
        if (narrow && range >= 1.3 * a) {
            if (c > s.bbUpper[i - 1]) offer(SQUEEZE, 1, min(1.0, 0.5 + 0.2 * max(0.0, volRatio - 1.0) + 0.3 * max(0.0, closeHigh - 0.6) / 0.4))
            if (c < s.bbLower[i - 1]) offer(SQUEEZE, -1, min(1.0, 0.5 + 0.2 * max(0.0, volRatio - 1.0) + 0.3 * max(0.0, closeLow - 0.6) / 0.4))
        }
        // 2. Range breakout: a close beyond the 20-candle high / low on a wide candle, before a trend was in place.
        if (s.efficiency[i - 1] < 0.5 && range >= 1.0 * a && (volRatio >= 1.2 || volumeUnknown)) {
            if (c > s.donchHigh[i]) offer(BREAKOUT, 1, min(0.95, 0.5 + 0.15 * max(0.0, volRatio - 1.2) + 0.25 * closeHigh))
            if (c < s.donchLow[i]) offer(BREAKOUT, -1, min(0.95, 0.5 + 0.15 * max(0.0, volRatio - 1.2) + 0.25 * closeLow))
        }
        // 3. Bounce inside a range: the edge of the bands is pierced and rejected, RSI is stretched.
        if (s.efficiency[i] < 0.30) {
            val lowerWick = (min(o, c) - l) / range
            val upperWick = (h - max(o, c)) / range
            if (l <= s.bbLower[i] && c > s.bbLower[i] && s.rsi[i] < 40.0 && lowerWick >= 0.4)
                offer(REVERSION, 1, min(0.9, 0.5 + 0.3 * lowerWick + 0.2 * (40.0 - s.rsi[i]) / 20.0))
            if (h >= s.bbUpper[i] && c < s.bbUpper[i] && s.rsi[i] > 60.0 && upperWick >= 0.4)
                offer(REVERSION, -1, min(0.9, 0.5 + 0.3 * upperWick + 0.2 * (s.rsi[i] - 60.0) / 20.0))
        }
        // 4. Pullback to the 20 average inside a trend, confirmed by a close beyond the previous candle.
        if (s.efficiency[i] >= 0.30) {
            val e20 = s.ema20[i]
            val e50 = s.ema50[i]
            if (e20 > e50 && l <= e20 + 0.2 * a && c > e20 && c > s.high[i - 1] && s.rsi[i] >= 40.0 && s.rsi[i] <= 65.0)
                offer(PULLBACK, 1, 0.55 + 0.25 * closeHigh)
            if (e20 < e50 && h >= e20 - 0.2 * a && c < e20 && c < s.low[i - 1] && s.rsi[i] >= 35.0 && s.rsi[i] <= 60.0)
                offer(PULLBACK, -1, 0.55 + 0.25 * closeLow)
        }
        // 5. Momentum candle: a big body that closes at its extreme, with volume.
        val body = abs(c - o)
        if (body >= 1.4 * a && (volRatio >= 1.3 || volumeUnknown)) {
            if (c > o && closeHigh >= 0.75) offer(BURST, 1, min(0.85, 0.5 + 0.15 * (body / a - 1.4) + 0.1 * max(0.0, volRatio - 1.3)))
            if (c < o && closeLow >= 0.75) offer(BURST, -1, min(0.85, 0.5 + 0.15 * (body / a - 1.4) + 0.1 * max(0.0, volRatio - 1.3)))
        }
        return best
    }

    /** Past occurrences of the same event (same type and direction), one per cluster, with enough candles after them to be judged. */
    fun events(s: Series, type: String, direction: Int, size: Int, horizon: Int, gap: Int = 3): List<ExitPlanner.Sample> {
        val out = ArrayList<ExitPlanner.Sample>()
        var last = -1000
        for (i in 60 until size - horizon - 1) {
            val d = detect(s, i) ?: continue
            if (d.type != type || d.direction != direction) continue
            if (i - last < gap) continue
            last = i
            out.add(ExitPlanner.Sample(i, s.close[i], s.atr[i]))
        }
        return out
    }

    fun label(type: String, direction: Int, ru: Boolean): String = when (type) {
        SQUEEZE -> if (ru) (if (direction > 0) "Выход из сжатия вверх" else "Выход из сжатия вниз") else (if (direction > 0) "Squeeze release up" else "Squeeze release down")
        BREAKOUT -> if (ru) (if (direction > 0) "Пробой диапазона вверх" else "Пробой диапазона вниз") else (if (direction > 0) "Range breakout up" else "Range breakout down")
        REVERSION -> if (ru) (if (direction > 0) "Отскок от нижней границы" else "Откат от верхней границы") else (if (direction > 0) "Bounce off the lower edge" else "Rejection at the upper edge")
        PULLBACK -> if (ru) (if (direction > 0) "Откат к средней в росте" else "Отскок к средней в падении") else (if (direction > 0) "Pullback in an uptrend" else "Pullback in a downtrend")
        BURST -> if (ru) (if (direction > 0) "Импульсная свеча вверх" else "Импульсная свеча вниз") else (if (direction > 0) "Momentum candle up" else "Momentum candle down")
        else -> ""
    }
}
