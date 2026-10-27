package com.marketforecast.prox

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Four additional pieces of technical evidence for the forecast: candle patterns, RSI divergence, money flow and Supertrend.
 * Each one is a pure function of the candles. [Signal.value] is in -1..1 (positive = favours longs).
 */
internal object TechnicalSignals {
    class Signal(val value: Double, val name: String)

    /** Reversal patterns on the last 1-3 candles. A pattern only counts after a move in the opposite direction. */
    fun candlePattern(c: List<Candle>): Signal? {
        val n = c.size
        if (n < 8) return null
        val avgRange = c.takeLast(14).map { it.high - it.low }.average()
        if (!(avgRange > 0.0)) return null
        val last = c[n - 1]
        val p1 = c[n - 2]
        val p2 = c[n - 3]
        fun body(x: Candle) = abs(x.close - x.open)
        fun span(x: Candle) = x.high - x.low
        fun upperWick(x: Candle) = x.high - max(x.open, x.close)
        fun lowerWick(x: Candle) = min(x.open, x.close) - x.low
        fun bullish(x: Candle) = x.close > x.open
        fun bearish(x: Candle) = x.close < x.open
        val afterDecline = c[n - 2].close < c[n - 6].close
        val afterRise = c[n - 2].close > c[n - 6].close
        if (span(last) < 0.5 * avgRange) return null
        var best: Signal? = null
        fun offer(value: Double, name: String) {
            val b = best
            if (b == null || abs(value) > abs(b.value)) best = Signal(value, name)
        }
        if (bearish(p1) && bullish(last) && last.open <= p1.close && last.close >= p1.open && body(last) > body(p1) * 1.1 && afterDecline)
            offer(0.9, "Свечной паттерн: бычье поглощение")
        if (bullish(p1) && bearish(last) && last.open >= p1.close && last.close <= p1.open && body(last) > body(p1) * 1.1 && afterRise)
            offer(-0.9, "Свечной паттерн: медвежье поглощение")
        if (body(last) >= 0.05 * span(last)) {
            if (lowerWick(last) >= 2 * body(last) && lowerWick(last) >= 0.55 * span(last) && upperWick(last) <= 0.35 * body(last) + 0.05 * span(last) && afterDecline)
                offer(0.7, "Свечной паттерн: молот")
            if (upperWick(last) >= 2 * body(last) && upperWick(last) >= 0.55 * span(last) && lowerWick(last) <= 0.35 * body(last) + 0.05 * span(last) && afterRise)
                offer(-0.7, "Свечной паттерн: падающая звезда")
        }
        if (bearish(p2) && body(p2) > 0.6 * avgRange && body(p1) < 0.35 * body(p2) && bullish(last) && last.close > (p2.open + p2.close) / 2.0 && afterDecline)
            offer(0.85, "Свечной паттерн: утренняя звезда")
        if (bullish(p2) && body(p2) > 0.6 * avgRange && body(p1) < 0.35 * body(p2) && bearish(last) && last.close < (p2.open + p2.close) / 2.0 && afterRise)
            offer(-0.85, "Свечной паттерн: вечерняя звезда")
        return best
    }

    private fun rsiSeries(x: List<Double>, n: Int = 14): DoubleArray {
        val out = DoubleArray(x.size) { Double.NaN }
        if (x.size <= n) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..n) {
            val d = x[i] - x[i - 1]
            gain += max(d, 0.0)
            loss += max(-d, 0.0)
        }
        var avgGain = gain / n
        var avgLoss = loss / n
        out[n] = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
        for (i in n + 1 until x.size) {
            val d = x[i] - x[i - 1]
            avgGain = (avgGain * (n - 1) + max(d, 0.0)) / n
            avgLoss = (avgLoss * (n - 1) + max(-d, 0.0)) / n
            out[i] = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
        }
        return out
    }

    /** Price makes a new extreme that RSI does not confirm: the move is running out of force. Recent (last 12 bars) only. */
    fun divergence(c: List<Candle>): Signal? {
        val n = c.size
        if (n < 40) return null
        val rsi = rsiSeries(c.map { it.close })
        val k = 3
        val first = max(k, n - 60)
        val lows = ArrayList<Int>()
        val highs = ArrayList<Int>()
        for (i in first until n - k) {
            var isLow = true
            var isHigh = true
            for (j in i - k until i) {
                if (c[i].low >= c[j].low) isLow = false
                if (c[i].high <= c[j].high) isHigh = false
            }
            for (j in i + 1..i + k) {
                if (c[i].low > c[j].low) isLow = false
                if (c[i].high < c[j].high) isHigh = false
            }
            if (isLow) lows.add(i)
            if (isHigh) highs.add(i)
        }
        fun lastTwo(idx: List<Int>): Pair<Int, Int>? {
            if (idx.size < 2) return null
            val b = idx.last()
            val a = idx.dropLast(1).lastOrNull { b - it >= 4 } ?: return null
            return a to b
        }
        lastTwo(lows)?.let { (a, b) ->
            if (n - 1 - b <= 12 && c[b].low < c[a].low && !rsi[a].isNaN() && !rsi[b].isNaN() && rsi[b] > rsi[a] + 2.0 && rsi[a] < 45.0)
                return Signal(0.85, "Дивергенция RSI: бычья (цена обновила минимум, RSI нет)")
        }
        lastTwo(highs)?.let { (a, b) ->
            if (n - 1 - b <= 12 && c[b].high > c[a].high && !rsi[a].isNaN() && !rsi[b].isNaN() && rsi[b] < rsi[a] - 2.0 && rsi[a] > 55.0)
                return Signal(-0.85, "Дивергенция RSI: медвежья (цена обновила максимум, RSI нет)")
        }
        return null
    }

    /** Chaikin money flow over 20 candles, -1..1. Zero when the instrument has no volume (most currency pairs). */
    fun moneyFlow(c: List<Candle>, n: Int = 20): Double {
        if (c.size < n) return 0.0
        var flow = 0.0
        var volume = 0.0
        for (x in c.takeLast(n)) {
            val span = x.high - x.low
            val multiplier = if (span <= 0.0) 0.0 else ((x.close - x.low) - (x.high - x.close)) / span
            flow += multiplier * x.volume
            volume += x.volume
        }
        return if (volume <= 0.0) 0.0 else (flow / volume).coerceIn(-1.0, 1.0)
    }

    /** Supertrend (ATR 10, x3): direction (+1 up, -1 down, 0 unknown) and the number of candles since it last flipped. */
    fun supertrend(c: List<Candle>, period: Int = 10, mult: Double = 3.0): Pair<Int, Int> {
        val n = c.size
        if (n < period + 2) return 0 to 0
        val tr = DoubleArray(n)
        tr[0] = c[0].high - c[0].low
        for (i in 1 until n) {
            tr[i] = max(c[i].high - c[i].low, max(abs(c[i].high - c[i - 1].close), abs(c[i].low - c[i - 1].close)))
        }
        var finalUpper = Double.NaN
        var finalLower = Double.NaN
        var direction = 1
        var since = 0
        var started = false
        for (i in period until n) {
            var sum = 0.0
            for (j in i - period + 1..i) sum += tr[j]
            val atr = sum / period
            val mid = (c[i].high + c[i].low) / 2.0
            val basicUpper = mid + mult * atr
            val basicLower = mid - mult * atr
            if (!started) {
                finalUpper = basicUpper
                finalLower = basicLower
                direction = if (c[i].close >= mid) 1 else -1
                since = 0
                started = true
                continue
            }
            val previousClose = c[i - 1].close
            finalUpper = if (basicUpper < finalUpper || previousClose > finalUpper) basicUpper else finalUpper
            finalLower = if (basicLower > finalLower || previousClose < finalLower) basicLower else finalLower
            if (direction == -1 && c[i].close > finalUpper) {
                direction = 1
                since = 0
            } else if (direction == 1 && c[i].close < finalLower) {
                direction = -1
                since = 0
            } else {
                since += 1
            }
        }
        return direction to since
    }
}
