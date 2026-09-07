package com.marketforecast.prox
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

import kotlin.math.*

object AnalyticsEngine {
    private const val CACHE_MAX = 128
    private val analysisCache = object : LinkedHashMap<String, Forecast>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Forecast>?): Boolean = size > CACHE_MAX
    }
    private val cacheLock = Any()

    private fun cacheKey(c: List<Candle>, entryOverride: Double?): String {
        val last = c.lastOrNull()
        return "${c.size}:${last?.time ?: 0L}:${last?.close ?: 0.0}:${entryOverride ?: Double.NaN}"
    }

    private fun ema(x: List<Double>, n: Int): Double? {
        if (x.size < n) return null
        val k = 2.0 / (n + 1.0)
        var e = x.take(n).average()
        for (i in n until x.size) e = x[i] * k + e * (1 - k)
        return e
    }

    private fun rsi(x: List<Double>, n: Int = 14): Double? {
        if (x.size <= n) return null
        var gain = 0.0; var loss = 0.0
        for (i in 1..n) { val d = x[i] - x[i - 1]; if (d >= 0) gain += d else loss -= d }
        gain /= n; loss /= n
        for (i in n + 1 until x.size) {
            val d = x[i] - x[i - 1]
            gain = (gain * (n - 1) + max(d, 0.0)) / n
            loss = (loss * (n - 1) + max(-d, 0.0)) / n
        }
        return if (loss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + gain / loss)
    }

    private fun atr(c: List<Candle>, n: Int = 14): Double? {
        if (c.size <= n) return null
        val tr = (1 until c.size).map { i ->
            max(c[i].high - c[i].low, max(abs(c[i].high - c[i - 1].close), abs(c[i].low - c[i - 1].close)))
        }
        return tr.takeLast(n).average()
    }

    private fun macd(x: List<Double>): Double? {
        if (x.size < 26) return null
        val k12 = 2.0 / 13.0
        val k26 = 2.0 / 27.0
        var e12 = x.take(12).average()
        var e26 = x.take(26).average()
        for (i in 12 until 26) e12 = x[i] * k12 + e12 * (1.0 - k12)
        for (i in 26 until x.size) {
            e12 = x[i] * k12 + e12 * (1.0 - k12)
            e26 = x[i] * k26 + e26 * (1.0 - k26)
        }
        return e12 - e26
    }

    private fun bollingerPosition(x: List<Double>, n: Int = 20): Double? {
        if (x.size < n) return null
        val a = x.takeLast(n); val m = a.average(); val sd = sqrt(a.map { (it - m).pow(2) }.average())
        return if (sd == 0.0) 0.0 else (x.last() - m) / (2 * sd)
    }

    private fun adx(c: List<Candle>, n: Int = 14): Double {
        if (c.size < n * 2 + 1) return 0.0
        var trSum = 0.0
        var plusSum = 0.0
        var minusSum = 0.0
        for (i in 1..n) {
            val prev = c[i - 1]; val cur = c[i]
            trSum += max(cur.high - cur.low, max(abs(cur.high - prev.close), abs(cur.low - prev.close)))
            val up = cur.high - prev.high
            val down = prev.low - cur.low
            plusSum += if (up > down && up > 0.0) up else 0.0
            minusSum += if (down > up && down > 0.0) down else 0.0
        }
        var dxSum = 0.0
        var dxCount = 0
        var atrN = trSum
        var plusN = plusSum
        var minusN = minusSum
        fun dx(): Double {
            if (atrN <= 1e-12) return 0.0
            val pdi = plusN / atrN * 100.0
            val mdi = minusN / atrN * 100.0
            return if (pdi + mdi <= 1e-12) 0.0 else abs(pdi - mdi) / (pdi + mdi) * 100.0
        }
        dxSum += dx(); dxCount++
        var adxValue = 0.0
        for (i in n + 1 until c.size) {
            val prev = c[i - 1]; val cur = c[i]
            val tr = max(cur.high - cur.low, max(abs(cur.high - prev.close), abs(cur.low - prev.close)))
            val up = cur.high - prev.high
            val down = prev.low - cur.low
            val plus = if (up > down && up > 0.0) up else 0.0
            val minus = if (down > up && down > 0.0) down else 0.0
            atrN = atrN - atrN / n + tr
            plusN = plusN - plusN / n + plus
            minusN = minusN - minusN / n + minus
            val d = dx()
            if (dxCount < n) { dxSum += d; dxCount++ } else {
                adxValue = if (adxValue == 0.0) dxSum / n else (adxValue * (n - 1) + d) / n
            }
        }
        return adxValue.coerceIn(0.0, 100.0)
    }
    private fun stochastic(c: List<Candle>, n: Int = 14): Double {
        if (c.size < n) return 50.0
        val w=c.takeLast(n); val hi=w.maxOf{it.high}; val lo=w.minOf{it.low}; return if(hi==lo)50.0 else ((c.last().close-lo)/(hi-lo)*100.0).coerceIn(0.0,100.0)
    }
    private fun momentum(x: List<Double>, n: Int = 10): Double = if (x.size <= n || x[x.size-n] == 0.0) 0.0 else x.last()/x[x.size-n]-1.0
    private fun roc(x: List<Double>, n: Int = 12): Double = if (x.size <= n || x[x.size-n] == 0.0) 0.0 else (x.last()-x[x.size-n])/x[x.size-n]*100.0
    private fun obv(c: List<Candle>): Double {
        if (c.size < 2) return 0.0
        var v=0.0; for(i in 1 until c.size) v += when { c[i].close>c[i-1].close -> c[i].volume; c[i].close<c[i-1].close -> -c[i].volume; else -> 0.0 }; return v
    }
    private fun structureScore(c: List<Candle>): Double {
        if (c.size < 12) return 0.0
        val a=c.takeLast(6); val b=c.dropLast(6).takeLast(6)
        val ah=a.maxOf{it.high}; val al=a.minOf{it.low}; val bh=b.maxOf{it.high}; val bl=b.minOf{it.low}
        return ((ah-bh)/(abs(bh).coerceAtLeast(1e-9))*5.0 + (al-bl)/(abs(bl).coerceAtLeast(1e-9))*5.0).coerceIn(-5.0,5.0)
    }
    private fun mtfTrend(c: List<Candle>): Double {
        if (c.size < 80) return 0.0
        fun part(start:Int):Double { val x=c.subList(start,c.size).map{it.close}; val e20=ema(x,minOf(20,x.size))?:x.last(); val e50=ema(x,minOf(50,x.size))?:x.last(); return ((x.last()-e20)/x.last()*5.0 + (e20-e50)/x.last()*5.0).coerceIn(-5.0,5.0) }
        return part(0)*0.5 + part(c.size/3)*0.3 + part(c.size/2)*0.2
    }

    private fun vwap(c: List<Candle>, n: Int = 30): Double {
        val w=c.takeLast(minOf(n,c.size)); var pv=0.0; var vv=0.0
        for(x in w){ val typical=(x.high+x.low+x.close)/3.0; val vol=x.volume.coerceAtLeast(0.0); pv+=typical*vol; vv+=vol }
        return if(vv>0) pv/vv else w.lastOrNull()?.close ?: 0.0
    }
    private fun emaSlope(x: List<Double>, n:Int=20, lookback:Int=5):Double{
        if(x.size<n+lookback) return 0.0
        val a=ema(x.dropLast(lookback),n)?:return 0.0; val b=ema(x,n)?:return 0.0
        return if(b==0.0) 0.0 else ((b-a)/b*100.0).coerceIn(-10.0,10.0)
    }
    private fun candlePressure(c:Candle):Double{
        val range=(c.high-c.low).coerceAtLeast(1e-12); return ((c.close-c.open)/range).coerceIn(-1.0,1.0)
    }
    private data class PatternResult(val score: Double, val names: List<String>)

    /**
     * Price-action pattern engine. It works only from OHLCV, so the scanner can
     * recognize formations instead of relying on one oscillator. Patterns are
     * confirmations, not guarantees; conflicting formations reduce the score.
     */
    private fun detectPatterns(c: List<Candle>, atrValue: Double): PatternResult {
        if (c.size < 8 || atrValue <= 0.0) return PatternResult(0.0, emptyList())
        val last = c.last()
        val prev = c[c.lastIndex - 1]
        val p2 = c[c.lastIndex - 2]
        val range = (last.high - last.low).coerceAtLeast(1e-9)
        val body = abs(last.close - last.open)
        val upper = last.high - max(last.open, last.close)
        val lower = min(last.open, last.close) - last.low
        val bodyFrac = body / range
        val out = mutableListOf<Pair<Double,String>>()
        if (bodyFrac <= 0.12) out += 1.2 to "Doji"
        if (lower >= body * 2.2 && upper <= body * 0.9 && last.close > last.open) out += 2.4 to "Молот / бычий pin-bar"
        if (upper >= body * 2.2 && lower <= body * 0.9 && last.close < last.open) out += -2.4 to "Падающая звезда / медвежий pin-bar"
        if (last.close > last.open && prev.close < prev.open && last.open <= prev.close && last.close >= prev.open) out += 3.0 to "Бычье поглощение"
        if (last.close < last.open && prev.close > prev.open && last.open >= prev.close && last.close <= prev.open) out += -3.0 to "Медвежье поглощение"
        if (bodyFrac >= 0.82 && last.close > last.open) out += 1.8 to "Бычий marubozu"
        if (bodyFrac >= 0.82 && last.close < last.open) out += -1.8 to "Медвежий marubozu"
        val prevRange = (prev.high - prev.low).coerceAtLeast(1e-9)
        if (range < prevRange * 0.72 && last.high <= prev.high && last.low >= prev.low) {
            val prior = p2.close
            out += if (last.close >= prior) 0.9 to "Inside bar / сжатие" else -0.9 to "Inside bar / сжатие"
        }
        if (c.size >= 4) {
            val a = c[c.lastIndex - 3]; val b = c[c.lastIndex - 2]; val d = c[c.lastIndex - 1]; val e = c.last()
            if (a.close < a.open && b.close > b.open && d.close > d.open && e.close > e.open &&
                b.close > a.high && d.close >= b.close * 0.997 && e.close >= d.close * 0.997) out += 2.0 to "Серия бычьего импульса"
            if (a.close > a.open && b.close < b.open && d.close < d.open && e.close < e.open &&
                b.close < a.low && d.close <= b.close * 1.003 && e.close <= d.close * 1.003) out += -2.0 to "Серия медвежьего импульса"
        }
        val w = c.takeLast(min(30, c.size))
        val hi = w.dropLast(1).maxOf { it.high }
        val lo = w.dropLast(1).minOf { it.low }
        if (last.close > hi + atrValue * 0.08 && last.close > last.open) out += 2.8 to "Пробой сопротивления"
        if (last.close < lo - atrValue * 0.08 && last.close < last.open) out += -2.8 to "Пробой поддержки"
        // Double top/bottom: two pivots with similar prices followed by rejection.
        if (w.size >= 12) {
            val left = w.dropLast(4).takeLast(8)
            val right = w.takeLast(4)
            val leftHi = left.maxOf { it.high }; val rightHi = right.maxOf { it.high }
            val leftLo = left.minOf { it.low }; val rightLo = right.minOf { it.low }
            val tolerance = max(atrValue * 0.65, last.close * 0.003)
            if (abs(leftHi - rightHi) <= tolerance && last.close < rightHi - atrValue * 0.15) out += -2.2 to "Двойная вершина"
            if (abs(leftLo - rightLo) <= tolerance && last.close > rightLo + atrValue * 0.15) out += 2.2 to "Двойное дно"
        }
        val weighted = out.sumOf { it.first }.coerceIn(-8.0, 8.0)
        return PatternResult(weighted, out.sortedByDescending { abs(it.first) }.take(5).map { it.second })
    }

    private fun atrSeries(c: List<Candle>, n: Int = 14, count: Int = 20): List<Double> {
        if (c.size <= n) return emptyList()
        // Wilder-style rolling ATR in O(N), avoiding a fresh N-element allocation
        // for every candle.
        val tr = DoubleArray(c.size - 1)
        for (i in 1 until c.size) {
            val cur = c[i]
            val prev = c[i - 1]
            tr[i - 1] = max(cur.high - cur.low, max(abs(cur.high - prev.close), abs(cur.low - prev.close)))
        }
        var sum = 0.0
        for (i in 0 until n) sum += tr[i]
        var atrValue = sum / n
        val out = ArrayList<Double>(min(count, c.size))
        out += atrValue
        for (i in n until tr.size) {
            atrValue = (atrValue * (n - 1) + tr[i]) / n
            out += atrValue
            if (out.size > count) out.removeAt(0)
        }
        return out
    }

    private data class StructuralLevels(val support1: Double, val support2: Double, val resistance1: Double, val resistance2: Double)

    private fun structuralLevels(c: List<Candle>, price: Double): StructuralLevels {
        val w = c.takeLast(180)
        val supports = mutableListOf<Double>()
        val resistances = mutableListOf<Double>()
        for (i in 2 until w.lastIndex - 1) {
            val x = w[i]
            if (x.low <= w[i-1].low && x.low <= w[i-2].low && x.low <= w[i+1].low && x.low <= w[i+2].low) supports += x.low
            if (x.high >= w[i-1].high && x.high >= w[i-2].high && x.high >= w[i+1].high && x.high >= w[i+2].high) resistances += x.high
        }
        val s = supports.filter { it < price }.distinct().sortedDescending()
        val r = resistances.filter { it > price }.distinct().sorted()
        val fallbackS = w.filter { it.low < price }.maxByOrNull { it.low }?.low ?: price
        val fallbackR = w.filter { it.high > price }.minByOrNull { it.high }?.high ?: price
        val s1 = s.getOrElse(0) { fallbackS }
        val s2 = s.getOrElse(1) { w.minOf { it.low } }
        val r1 = r.getOrElse(0) { fallbackR }
        val r2 = r.getOrElse(1) { w.maxOf { it.high } }
        return StructuralLevels(s1, s2, r1, r2)
    }

    private fun macdHistogram(x: List<Double>): Double {
        if (x.size < 35) return 0.0
        val line = mutableListOf<Double>()
        val k12 = 2.0 / 13.0; val k26 = 2.0 / 27.0
        var e12 = x.take(12).average(); var e26 = x.take(26).average()
        for (i in 26 until x.size) {
            e12 = x[i] * k12 + e12 * (1.0 - k12)
            e26 = x[i] * k26 + e26 * (1.0 - k26)
            line += e12 - e26
        }
        if (line.size < 10) return 0.0
        var signal = line.take(9).average()
        val ks = 2.0 / 10.0
        for (i in 9 until line.size) signal = line[i] * ks + signal * (1.0 - ks)
        return line.last() - signal
    }

    private fun bollingerWidth(x: List<Double>, n: Int = 20): Double {
        if (x.size < n) return 0.0
        val w = x.takeLast(n); val m = w.average(); val sd = sqrt(w.map { (it - m).pow(2) }.average())
        return if (m == 0.0) 0.0 else (4.0 * sd / abs(m)).coerceIn(0.0, 2.0)
    }

    private fun rsiSlope(x: List<Double>, n: Int = 14): Double {
        if (x.size < n * 2 + 1) return 0.0
        val a = rsi(x.dropLast(5), n) ?: 50.0; val b = rsi(x, n) ?: 50.0
        return ((b - a) / 20.0).coerceIn(-1.0, 1.0)
    }

    private fun cci(c: List<Candle>, n: Int = 20): Double {
        if (c.size < n) return 0.0
        val w = c.takeLast(n); val tp = w.map { (it.high + it.low + it.close) / 3.0 }; val mean = tp.average()
        val dev = tp.map { abs(it - mean) }.average().coerceAtLeast(1e-12)
        return ((tp.last() - mean) / (0.015 * dev)).coerceIn(-300.0, 300.0)
    }

    private fun williamsR(c: List<Candle>, n: Int = 14): Double {
        if (c.size < n) return -50.0
        val w = c.takeLast(n); val hi = w.maxOf { it.high }; val lo = w.minOf { it.low }
        return if (hi == lo) -50.0 else ((hi - c.last().close) / (hi - lo) * -100.0).coerceIn(-100.0, 0.0)
    }

    private fun mfi(c: List<Candle>, n: Int = 14): Double {
        if (c.size <= n) return 50.0
        var pos = 0.0; var neg = 0.0
        for (i in c.size - n until c.size) {
            val cur = c[i]; val prev = c[i - 1]
            val tp = (cur.high + cur.low + cur.close) / 3.0
            val prevTp = (prev.high + prev.low + prev.close) / 3.0
            val flow = tp * cur.volume.coerceAtLeast(0.0)
            if (tp >= prevTp) pos += flow else neg += flow
        }
        return if (neg <= 1e-12) 100.0 else (100.0 - 100.0 / (1.0 + pos / neg)).coerceIn(0.0, 100.0)
    }

    private fun cmf(c: List<Candle>, n: Int = 20): Double {
        if (c.size < n) return 0.0
        var mfv = 0.0; var vol = 0.0
        c.takeLast(n).forEach { x ->
            val range = (x.high - x.low).coerceAtLeast(1e-12)
            mfv += ((2.0 * x.close - x.high - x.low) / range) * x.volume.coerceAtLeast(0.0)
            vol += x.volume.coerceAtLeast(0.0)
        }
        return if (vol <= 1e-12) 0.0 else (mfv / vol).coerceIn(-1.0, 1.0)
    }

    private fun ichimokuBias(c: List<Candle>): Double {
        if (c.size < 52) return 0.0
        fun mid(n: Int, offset: Int = 0): Double {
            val end = c.size - offset; val start = end - n
            if (start < 0) return c.last().close
            val w = c.subList(start, end); return (w.maxOf { it.high } + w.minOf { it.low }) / 2.0
        }
        val conversion = mid(9); val base = mid(26); val spanA = (conversion + base) / 2.0; val spanB = mid(52)
        val price = c.last().close; val cloudTop = max(spanA, spanB); val cloudBottom = min(spanA, spanB)
        return when { price > cloudTop && conversion > base -> 2.5; price < cloudBottom && conversion < base -> -2.5; price > cloudTop -> 1.25; price < cloudBottom -> -1.25; else -> 0.0 }
    }

    private fun donchianPosition(c: List<Candle>, n: Int = 20): Double {
        if (c.size < n) return 0.0
        val w = c.takeLast(n); val hi = w.maxOf { it.high }; val lo = w.minOf { it.low }; val span = (hi - lo).coerceAtLeast(1e-12)
        return ((c.last().close - lo) / span * 2.0 - 1.0).coerceIn(-1.0, 1.0)
    }

    private fun stochasticRsi(x: List<Double>, n: Int = 14): Double {
        // O(N) RSI stream instead of recalculating RSI over every prefix (O(N²)).
        if (x.size < n * 2) return 0.5
        var gain = 0.0
        var loss = 0.0
        for (i in 1..n) {
            val d = x[i] - x[i - 1]
            if (d >= 0.0) gain += d else loss -= d
        }
        gain /= n
        loss /= n
        val values = ArrayList<Double>(x.size - n)
        values += if (loss <= 1e-12) 100.0 else 100.0 - 100.0 / (1.0 + gain / loss)
        for (i in n + 1 until x.size) {
            val d = x[i] - x[i - 1]
            gain = (gain * (n - 1) + max(d, 0.0)) / n
            loss = (loss * (n - 1) + max(-d, 0.0)) / n
            values += if (loss <= 1e-12) 100.0 else 100.0 - 100.0 / (1.0 + gain / loss)
        }
        val from = max(0, values.size - n)
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (i in from until values.size) {
            val v = values[i]
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val last = values.lastOrNull() ?: 50.0
        return if (hi - lo <= 1e-12) 0.5 else ((last - lo) / (hi - lo)).coerceIn(0.0, 1.0)
    }

    /**
     * Fast walk-forward directional edge. It never calls analyze() recursively, so it
     * can be used inside the live model without the old backtest recursion/performance trap.
     * The current ATR is used only as a normalized movement unit; no future candle is
     * used for the current prediction itself.
     */
    private fun historicalEdge(c: List<Candle>, direction: Int, horizon: Int = 8): Double {
        if (c.size < 90) return 0.5
        val start = max(30, c.size - 110)
        val end = c.size - horizon - 1
        if (end <= start) return 0.5
        var wins = 0.0
        var neutral = 0.0
        var total = 0
        val step = 5
        var i = start
        while (i <= end) {
            val entry = c[i].close
            val local = c.subList(0, i + 1)
            val unit = (atr(local) ?: entry * 0.01).coerceAtLeast(entry * 0.002)
            if (!entry.isFinite() || entry <= 0.0) { i += step; continue }
            val future = c.subList(i + 1, min(c.size, i + 1 + horizon))
            if (future.isEmpty()) { i += step; continue }
            val favorable = if (direction > 0) future.maxOf { it.high } - entry else entry - future.minOf { it.low }
            val adverse = if (direction > 0) entry - future.minOf { it.low } else future.maxOf { it.high } - entry
            val target = unit * 0.75
            when {
                favorable >= target && adverse < target -> wins += 1.0
                favorable < target && adverse < target -> {
                    val finalMove = direction * (future.last().close - entry)
                    if (finalMove > 0) wins += 1.0 else neutral += 1.0
                }
                favorable >= target && adverse >= target -> neutral += 1.0
            }
            total++
            i += step
        }
        return if (total == 0) 0.5 else ((wins + neutral * 0.5) / total).coerceIn(0.05, 0.95)
    }

    private fun multiHorizonEdge(c: List<Candle>, direction: Int): Double {
        // Calibrate the direction on several horizons. A signal is considered robust
        // only when it has positive walk-forward edge across more than one horizon.
        val horizons = intArrayOf(3, 8, 16)
        val weights = doubleArrayOf(.25, .50, .25)
        var sum = 0.0
        var used = 0.0
        horizons.forEachIndexed { idx, h ->
            if (c.size >= 70 + h) {
                val e = historicalEdge(c, direction, h)
                sum += e * weights[idx]
                used += weights[idx]
            }
        }
        return if (used == 0.0) 0.5 else (sum / used).coerceIn(.05, .95)
    }

    /** Measures whether price and volume confirm the same directional move. */
    private fun volumePriceConfirmation(c: List<Candle>): Double {
        if (c.size < 20) return 0.0
        val w = c.takeLast(20)
        val avg = w.map { it.volume }.average().coerceAtLeast(1e-9)
        var score = 0.0
        w.forEachIndexed { i, x ->
            val body = (x.close - x.open) / x.open.coerceAtLeast(1e-9)
            val relVol = (x.volume / avg).coerceIn(0.25, 3.0)
            val decay = 0.55 + i / w.size.toDouble()
            score += body * relVol * decay
        }
        return (score * 35.0).coerceIn(-3.0, 3.0)
    }

    /** Detects momentum divergence against price over two recent pivots. */
    private fun momentumDivergence(c: List<Candle>): Double {
        if (c.size < 45) return 0.0
        val closes = c.map { it.close }
        val rsiNow = rsi(closes) ?: 50.0
        val rsiPast = rsi(closes.dropLast(12), 14) ?: 50.0
        val priceNow = closes.last()
        val pricePast = closes[closes.size - 13]
        val priceMove = (priceNow - pricePast) / pricePast.coerceAtLeast(1e-9)
        val rsiMove = (rsiNow - rsiPast) / 100.0
        return when {
            priceMove > 0.012 && rsiMove < -0.025 -> -2.0
            priceMove < -0.012 && rsiMove > 0.025 -> 2.0
            else -> 0.0
        }
    }

    /** Quality of the current trend: EMA alignment plus slope, normalized by ATR. */
    private fun trendQuality(c: List<Candle>, price: Double, atrValue: Double): Double {
        if (c.size < 60 || atrValue <= 0.0) return 0.0
        val x = c.map { it.close }
        val e20 = ema(x, 20) ?: price
        val e50 = ema(x, 50) ?: price
        val gap = ((e20 - e50) / atrValue).coerceIn(-4.0, 4.0)
        val slope = emaSlope(x, 20, 8).coerceIn(-1.0, 1.0)
        return (gap * 0.45 + slope * 2.0).coerceIn(-3.0, 3.0)
    }

    private fun trendAgreement(c: List<Candle>): Double {
        if (c.size < 60) return 0.0
        val windows = listOf(30, 60, minOf(120, c.size))
        var sum = 0.0
        for (n in windows) {
            val x = c.takeLast(n).map { it.close }
            val e = ema(x, minOf(20, x.size)) ?: x.last()
            sum += when { x.last() > e -> 1.0; x.last() < e -> -1.0; else -> 0.0 }
        }
        return (sum / windows.size * 3.0).coerceIn(-3.0, 3.0)
    }

    fun analyze(c: List<Candle>, entryOverride: Double? = null): Forecast {
        require(c.size >= 30) { "Недостаточно исторических данных" }
        val key = cacheKey(c, entryOverride)
        synchronized(cacheLock) { analysisCache[key]?.let { return it } }
        val result = analyzeInternal(c, entryOverride, true)
        synchronized(cacheLock) { analysisCache[key] = result }
        return result
    }

    private fun analyzeInternal(c: List<Candle>, entryOverride: Double? = null, calibrate: Boolean): Forecast {
        require(c.size >= 30) { "Недостаточно исторических данных" }
        val closes = c.map { it.close }; val price = entryOverride?.takeIf { it.isFinite() && it > 0.0 } ?: closes.last(); require(price.isFinite() && price > 0.0) { "Цена должна быть положительной и конечной" }
        val e20 = ema(closes, 20) ?: price; val e50 = ema(closes, 50) ?: price; val e200 = ema(closes, 200)
        val r = rsi(closes) ?: 50.0; val m = macd(closes) ?: 0.0; val mh = macdHistogram(closes); val a = atr(c) ?: price * 0.01
        val patterns = detectPatterns(c, a)
        val bb = bollingerPosition(closes) ?: 0.0; val bbWidth = bollingerWidth(closes); val rsiTrend = rsiSlope(closes)
        val adxV = adx(c); val stoch = stochastic(c); val mom = momentum(closes); val rocV = roc(closes)
        val cciV = cci(c); val williamsV = williamsR(c); val mfiV = mfi(c); val cmfV = cmf(c)
        val ichimoku = ichimokuBias(c); val donchian = donchianPosition(c); val stochRsi = stochasticRsi(closes)
        val obvV = obv(c); val obvPrev = obv(c.dropLast(minOf(10,c.size-1))); val obvTrend = if (abs(obvPrev) < 1e-9) 0.0 else ((obvV-obvPrev)/abs(obvPrev)).coerceIn(-2.0,2.0)
        val structure = structureScore(c); val mtf = mtfTrend(c); val agreement = trendAgreement(c)
        val vwapV = vwap(c); val vwapBase = ((price-vwapV)/price*8.0).coerceIn(-4.0,4.0)
        val slope = emaSlope(closes); val slopeBase=(slope*1.8).coerceIn(-4.0,4.0)
        val pressure = candlePressure(c.last())
        val volumePrice = volumePriceConfirmation(c)
        val divergence = momentumDivergence(c)
        val trendQuality = trendQuality(c, price, a)
        val atrs = atrSeries(c); val atrPctile = if(atrs.isEmpty()) .5 else atrs.count{it<=a}.toDouble()/atrs.size
        val volatilityRegimeBase = ((atrPctile-.5)*4.0).coerceIn(-2.0,2.0)
        // Higher-order price-action features. These use only the available OHLCV path
        // and are intentionally bounded to reduce overreaction to a single candle.
        val recent20 = c.takeLast(minOf(20, c.size))
        val bodyStrength = recent20.map {
            val range = (it.high - it.low).coerceAtLeast(1e-12)
            ((it.close - it.open) / range).coerceIn(-1.0, 1.0)
        }.average()
        val rangeEfficiency = run {
            val path = recent20.zipWithNext().sumOf { abs(it.second.close - it.first.close) }.coerceAtLeast(1e-9)
            val net = abs((recent20.lastOrNull()?.close ?: price) - (recent20.firstOrNull()?.close ?: price))
            (net / path).coerceIn(0.0, 1.0)
        }
        val rangeExpansion = run {
            val short = recent20.takeLast(minOf(5, recent20.size)).map { it.high - it.low }.average()
            val long = recent20.map { it.high - it.low }.average().coerceAtLeast(1e-9)
            (short / long - 1.0).coerceIn(-1.0, 2.0)
        }
        val localAvgVolume = recent20.map { it.volume }.average().coerceAtLeast(1e-9)
        val volumeAnomaly = ((c.last().volume / localAvgVolume - 1.0) / 1.5).coerceIn(-1.0, 1.0)
        val priceActionQuality = (bodyStrength * 2.2 + rangeEfficiency * agreement.sign * 1.4 + rangeExpansion * pressure.sign * 0.8 + volumeAnomaly * pressure.sign * 0.7).coerceIn(-5.0, 5.0)

        val advancedTechnical = (
            (cciV / 100.0).coerceIn(-2.5, 2.5) * 0.9 +
            ((williamsV + 50.0) / 25.0).coerceIn(-2.0, 2.0) * 0.45 +
            ((mfiV - 50.0) / 10.0).coerceIn(-2.5, 2.5) * 0.65 +
            cmfV * 2.5 + ichimoku + donchian * 1.8 + (stochRsi - 0.5) * 3.0
        ).coerceIn(-10.0, 10.0)
        val avgVol = c.takeLast(20).map { it.volume }.average(); val volRatio = if (avgVol > 0) c.last().volume / avgVol else 1.0
        val recentHigh = c.takeLast(40).maxOf { it.high }; val recentLow = c.takeLast(40).minOf { it.low }
        val sr = structuralLevels(c, price)
        val support1 = sr.support1
        val support2 = sr.support2
        val resistance1 = sr.resistance1
        val resistance2 = sr.resistance2

        val trendBase = when {
            price > e20 && e20 > e50 && (e200 == null || e50 > e200) -> 10.0
            price < e20 && e20 < e50 && (e200 == null || e50 < e200) -> -10.0
            price > e20 && e20 > e50 -> 6.0
            price < e20 && e20 < e50 -> -6.0
            else -> 0.0
        }
        // Contextual momentum: oscillators confirm a strong trend instead of blindly
        // fading every overbought/oversold reading. In a range the behavior is reversed.
        val rsiContext = when {
            trendBase >= 6.0 -> ((r - 50.0) / 10.0).coerceIn(-3.0, 3.0)
            trendBase <= -6.0 -> ((r - 50.0) / 10.0).coerceIn(-3.0, 3.0)
            r > 70.0 -> -2.5
            r < 30.0 -> 2.5
            else -> (r - 50.0) / 12.0
        }
        val stochContext = when {
            trendBase >= 6.0 -> ((stoch - 50.0) / 25.0).coerceIn(-2.0, 2.0)
            trendBase <= -6.0 -> ((stoch - 50.0) / 25.0).coerceIn(-2.0, 2.0)
            stoch > 80.0 -> -1.5
            stoch < 20.0 -> 1.5
            else -> ((stoch - 50.0) / 35.0).coerceIn(-1.5, 1.5)
        }
        val momentumBase = rsiContext + (if (m > 0) 2.0 else -2.0) +
            mh.coerceIn(-price * 0.02, price * 0.02) / price * 35.0 + rsiTrend
        val levelBase = when {
            price >= recentHigh * 0.995 -> 4.0
            price <= recentLow * 1.005 -> -4.0
            else -> -bb.coerceIn(-2.0, 2.0)
        }
        val stochasticBase = stochContext
        val momentumExtended = (mom*18.0 + rocV/8.0).coerceIn(-4.0,4.0)
        val adxBase = if (adxV >= 20.0) trendBase.sign * min(4.0, adxV/20.0) else 0.0
        val volumeBase = ((volRatio - 1.0) * 4.0 + obvTrend*1.5).coerceIn(-4.0, 4.0)
        val volatilityPct = abs(a / price) * 100.0
        // Historical edge is deliberately computed in both directions and only the
        // selected direction is used after signal formation. This makes confidence
        // sensitive to what this market has actually rewarded recently.
        val longEdge = if (calibrate) multiHorizonEdge(c, 1) else 0.5
        val shortEdge = if (calibrate) multiHorizonEdge(c, -1) else 0.5
        val edgeGap = abs(longEdge - shortEdge)
        val efficiencyWindow = c.takeLast(min(20, c.size)).map { it.close }
        val pathNoise = efficiencyWindow.zipWithNext().sumOf { abs(it.second - it.first) }.coerceAtLeast(1e-9)
        val efficiency = if (efficiencyWindow.size > 1) abs(efficiencyWindow.last() - efficiencyWindow.first()) / pathNoise else 0.0
        val breakout = when { price > recentHigh * 0.998 -> 2.5; price < recentLow * 1.002 -> -2.5; else -> 0.0 }
        val volumeImpulse = ((volRatio - 1.0) * if (c.last().close >= c.last().open) 2.0 else -2.0).coerceIn(-3.0, 3.0)
        val adaptiveTrendW = if (volatilityPct > 5.0) .30 else .38
        val adaptiveMomentumW = if (volatilityPct > 5.0) .24 else .28
        val compressionBias = if (bbWidth < 0.035) 0.0 else 0.15 * bb.coerceIn(-2.0, 2.0)
        // Independent confirmation blocks are weighted by regime.  Trend-following
        // blocks dominate directional markets; momentum/mean-reversion blocks gain
        // influence in ranges. This reduces single-indicator overfitting.
        val trendRegime = (adxV / 35.0).coerceIn(0.0, 1.0)
        val rangeRegime = 1.0 - trendRegime
        val confirmationBoost = trendQuality * (0.08 + 0.12 * trendRegime) +
            volumePrice * 0.07 + divergence * (0.05 + 0.08 * rangeRegime)
        val raw = trendBase * adaptiveTrendW + (momentumBase + stochasticBase + momentumExtended) * adaptiveMomentumW/2.2 + levelBase * .14 + volumeBase * .11 + adxBase*.06 + structure*.05 + mtf*.05 + agreement*.05 + vwapBase*.06 + slopeBase*.06 + pressure*.04 + volatilityRegimeBase*.025 + compressionBias + breakout*.06 + efficiency*agreement*.12 + volumeImpulse*.04 + advancedTechnical*.10 + priceActionQuality*.10 + patterns.score * .16 + confirmationBoost
        val score = raw.coerceIn(-10.0, 10.0)
        // Directional confirmation is deliberately conservative: a high raw score is
        // not enough when trend, momentum and higher-timeframe structure disagree.
        val confirmation = (trendBase.sign + agreement.sign + mtf.sign + momentumBase.sign + structure.sign + trendQuality.sign + volumePrice.sign + patterns.score.sign).roundToInt()
        val directionalConflict = abs(score) >= 2.2 && confirmation * score.sign < 2.0
        val confirmationStrength = (abs(confirmation) / 7.0).coerceIn(0.0, 1.0)
        val divergenceConflict = divergence * score.sign < -1.0
        // Probability-first gating.  NO TRADE is reserved for genuinely ambiguous
        // markets; ordinary directional setups are allowed when several independent
        // blocks agree.  This prevents the previous over-filtering from turning the
        // application into a permanent NO TRADE dashboard.
        // Adaptive gates: thresholds depend on market regime instead of a single
        // hard NO-TRADE wall. This preserves the precision filters while allowing
        // strong, well-confirmed trends through when the recent walk-forward sample
        // is simply inconclusive.
        val trendRegimeStrong = adxV >= 25.0 && efficiency >= 0.16
        val rangeMarketRegime = adxV < 18.0
        val edgeThreshold = when {
            trendRegimeStrong -> 0.018
            rangeMarketRegime -> 0.030
            else -> 0.022
        }
        val scoreThreshold = when {
            trendRegimeStrong -> 3.0
            rangeMarketRegime -> 3.8
            else -> 3.3
        }
        val weakEdge = edgeGap < edgeThreshold && abs(score) < scoreThreshold + 0.9
        val precisionLong = longEdge >= 0.515 && longEdge - shortEdge >= edgeThreshold
        val precisionShort = shortEdge >= 0.515 && shortEdge - longEdge >= edgeThreshold
        val efficiencyGate = if (trendRegimeStrong) efficiency >= 0.12 else efficiency >= 0.055
        val scoreGate = abs(score) >= scoreThreshold
        val confirmationGate = abs(confirmation) >= 2
        val rrGate = run {
            val provisionalRisk = maxOf(price * 0.006, a * 0.8)
            val provisionalDirection = if (score >= 0) 1.0 else -1.0
            val provisionalStop = if (provisionalDirection > 0) price - provisionalRisk else price + provisionalRisk
            val provisionalTp = if (provisionalDirection > 0) price + provisionalRisk * 1.5 else price - provisionalRisk * 1.5
            abs(provisionalTp - price) / abs(price - provisionalStop) >= 1.35
        }
        val signal = when {
            directionalConflict || divergenceConflict || !efficiencyGate || !scoreGate || !confirmationGate || !rrGate -> "NO TRADE"
            score > 0 && precisionLong && (!weakEdge || trendRegimeStrong || abs(score) >= scoreThreshold + 0.9) -> "LONG"
            score < 0 && precisionShort && (!weakEdge || trendRegimeStrong || abs(score) >= scoreThreshold + 0.9) -> "SHORT"
            // If historical edge is inconclusive, a very strong current ensemble
            // can still produce a directional call. Confidence is capped below.
            score >= scoreThreshold + 1.5 && confirmation >= 3 && !divergenceConflict -> "LONG"
            score <= -(scoreThreshold + 1.5) && confirmation <= -3 && !divergenceConflict -> "SHORT"
            else -> "NO TRADE"
        }
        val direction = when { signal.contains("LONG") -> 1.0; signal.contains("SHORT") -> -1.0; else -> if (score >= 0) 1.0 else -1.0 }

        // Risk/target engine: every level is derived from observed swing structure plus ATR.
        // No arbitrary percentage projection is used. If structure is too far away, the
        // fallback is an explicit R-multiple of ATR-derived risk, not a fabricated market price.
        val minRisk = price * 0.006
        val maxRisk = price * 0.04
        val structuralStop = if (direction > 0) support1 - a * 0.20 else resistance1 + a * 0.20
        val structuralDistance = abs(price - structuralStop)
        val optimalRisk = if (structuralStop > 0.0 && structuralDistance in minRisk..maxRisk) structuralDistance else (a * 1.35).coerceIn(minRisk, maxRisk)
        val aggressiveRisk = (a * 0.85).coerceIn(minRisk, maxRisk)
        val conservativeRisk = (a * 1.80).coerceIn(minRisk, maxRisk)
        val stopAggressive = if (direction > 0) price - aggressiveRisk else price + aggressiveRisk
        val stopOptimal = if (direction > 0) price - optimalRisk else price + optimalRisk
        val stopConservative = if (direction > 0) price - conservativeRisk else price + conservativeRisk
        val stop = stopOptimal
        val riskDistance = abs(price - stop).coerceAtLeast(1e-9)
        val directionalRes1 = if (direction > 0) resistance1.takeIf { it > price } else support1.takeIf { it < price }
        val directionalRes2 = if (direction > 0) resistance2.takeIf { it > price && it > (directionalRes1 ?: price) } else support2.takeIf { it < price && it < (directionalRes1 ?: price) }
        fun rTarget(r: Double): Double = if (direction > 0) price + riskDistance * r else (price - riskDistance * r).coerceAtLeast(1e-9)
        var safeTp1 = directionalRes1 ?: rTarget(1.0)
        var safeTp2 = directionalRes2 ?: rTarget(2.0)
        var safeTp3 = rTarget(3.0)
        val minStep = max(price * 0.001, a * 0.10)
        if (direction > 0) {
            safeTp1 = max(safeTp1, price + minStep)
            safeTp2 = max(safeTp2, safeTp1 + minStep)
            safeTp3 = max(safeTp3, safeTp2 + minStep)
        } else {
            safeTp1 = min(safeTp1, price - minStep).coerceAtLeast(1e-9)
            safeTp2 = min(safeTp2, safeTp1 - minStep).coerceAtLeast(1e-9)
            safeTp3 = min(safeTp3, safeTp2 - minStep).coerceAtLeast(1e-9)
        }
        val rr = abs(safeTp2 - price) / riskDistance
        val projected = if (signal == "NO TRADE") price else if (direction > 0) safeTp2 else safeTp2
        val regime = when {
            adxV >= 28 && trendBase >= 6 -> "TREND_UP"
            adxV >= 28 && trendBase <= -6 -> "TREND_DOWN"
            volatilityPct >= 4.0 -> "HIGH_VOLATILITY"
            adxV < 18 -> "RANGE"
            else -> "TRANSITION"
        }
        val rrPenalty = if (rr < 1.5) 10 else 0
        val agreementBonus = abs(agreement) * 1.5
        val qualityBase = (55 + min(25, c.size / 8) + (if (avgVol > 0) 5 else 0) + (if (a > 0) 5 else 0) + (if (e200 != null) 5 else 0)).coerceIn(55, 95)
        val volatilityPenalty = when { volatilityPct >= 8.0 -> 14.0; volatilityPct >= 5.0 -> 8.0; volatilityPct >= 3.0 -> 3.0; else -> 0.0 }
        val conflictPenalty = if (directionalConflict) 18.0 else 0.0
        val selectedEdge = if (score >= 0) longEdge else shortEdge
        val edgeAdjustment = (selectedEdge - 0.50) * 34.0
        val robustnessBonus = (edgeGap * 20.0).coerceIn(0.0, 5.0)
        val rawConfidence = (qualityBase + abs(score) * 3.8 + min(10.0, abs(trendBase)) + min(7.0, abs(volumeBase)) + agreementBonus + efficiency * 4.0 + edgeAdjustment + robustnessBonus + confirmationStrength * 4.0 - rrPenalty - volatilityPenalty - conflictPenalty - if (divergenceConflict) 5.0 else 0.0)
        // Confidence is calibrated by the observed walk-forward edge. A setup cannot
        // display a very high confidence merely because many technical factors agree.
        // This keeps the UI honest when historical directional edge is mediocre.
        // Confidence is a calibrated directional probability proxy, not a promise.
        // It combines recent walk-forward edge, model score, agreement and efficiency.
        // The value is intentionally useful even for borderline setups, while trade
        // execution still requires the separate signal gates above.
        val probabilityFromEdge = ((selectedEdge - 0.50) / 0.25).coerceIn(0.0, 1.0)
        val probabilityFromScore = (abs(score) / 10.0).coerceIn(0.0, 1.0)
        val probabilityFromConfirmation = (abs(confirmation) / 5.0).coerceIn(0.0, 1.0)
        val calibratedProbability = (50.0 +
            probabilityFromEdge * 24.0 +
            probabilityFromScore * 10.0 +
            probabilityFromConfirmation * 7.0 +
            efficiency * 5.0 +
            edgeGap.coerceIn(0.0, 0.20) * 20.0).coerceIn(50.0, 94.0)
        val edgeCeiling = (58.0 + (selectedEdge - 0.50) * 105.0).coerceIn(58.0, 94.0)
        val edgeInformative = abs(selectedEdge - 0.50) >= 0.025
        val confidenceCeiling = if (edgeInformative) edgeCeiling else 78.0
        val confidence = min(max(rawConfidence, calibratedProbability), confidenceCeiling).roundToInt().coerceIn(50, 94)
        val wLong = exp(score / 3.0); val wShort = exp(-score / 3.0); val wBase = 1.35
        val wSum = wLong + wBase + wShort
        var bull = (wLong / wSum * 100.0).roundToInt().coerceIn(5, 90)
        var bear = (wShort / wSum * 100.0).roundToInt().coerceIn(5, 90)
        var base = 100 - bull - bear
        if (base < 5) { if (bull >= bear) bull -= (5 - base) else bear -= (5 - base); base = 5 }
        if (base > 90) base = 90

        val quality = (60 + min(24, c.size / 10) + (if (avgVol > 0) 5 else 0) + (if (a > 0) 5 else 0) + (if (e200 != null) 4 else 0)).coerceIn(55, 99)
        val explanation = listOf(
            "Тренд: цена ${if (price >= e20) "выше" else "ниже"} EMA20; EMA20 ${if (e20 >= e50) "выше" else "ниже"} EMA50.",
            "RSI: %.1f (наклон %.2f); MACD: %s; histogram %.5f.".format(Locale.US, r, rsiTrend, if (m >= 0) "положительный" else "отрицательный", mh),
            "ATR: %.4f; волатильность учитывается в SL/TP.".format(Locale.US, a),
            "Объём: %.2fx от среднего за 20 свечей; OBV-тренд %.2f.".format(Locale.US, volRatio, obvTrend),
            "ADX: %.1f; Stochastic: %.1f; Momentum: %.2f%%; ROC: %.2f%%.".format(Locale.US, adxV, stoch, mom*100.0, rocV),
            "Структура цены: %.2f; мульти-ТФ: %.2f; согласованность тренда: %.2f.".format(Locale.US, structure, mtf, agreement),
            "VWAP: %.4f; отклонение цены %.2f%%; наклон EMA20 %.2f%%.".format(Locale.US, vwapV, (price-vwapV)/price*100.0, slope),
            "Давление последней свечи: %.2f; ATR-перцентиль: %.0f%%; эффективность движения %.2f.".format(Locale.US, pressure, atrPctile*100.0, efficiency),
            "Пробойный импульс: %.2f; объёмный импульс: %.2f; режим волатильности: %.2f%%.".format(Locale.US, breakout, volumeImpulse, volatilityPct),
            "Walk-forward edge по горизонтам: LONG %.0f%% / SHORT %.0f%%; разрыв %.1f п.п.; слабые и конфликтные направления отсекаются.".format(Locale.US, longEdge * 100.0, shortEdge * 100.0, edgeGap * 100.0),
            "Новая перекрёстная проверка: качество тренда %.2f; цена+объём %.2f; дивергенция RSI %.2f; сила подтверждения %.0f%%.".format(Locale.US, trendQuality, volumePrice, divergence, confirmationStrength * 100.0),
            "Price Action Engine: сила тела %.2f; эффективность диапазона %.2f; расширение диапазона %.2f; аномалия объёма %.2f.".format(Locale.US, bodyStrength, rangeEfficiency, rangeExpansion, volumeAnomaly),
            "Pattern Engine: score %.2f; распознано: %s.".format(Locale.US, patterns.score, if (patterns.names.isEmpty()) "нет устойчивой формации" else patterns.names.joinToString(", ")),
            "Ключевые уровни: поддержка %.2f / %.2f; сопротивление %.2f / %.2f.".format(Locale.US, support1, support2, resistance1, resistance2),
            "Расширенный теханализ: CCI %.1f; Williams %%R %.1f; MFI %.1f; CMF %.2f; Ichimoku %.2f; Donchian %.2f; Stoch RSI %.2f.".format(Locale.US, cciV, williamsV, mfiV, cmfV, ichimoku, donchian, stochRsi),
            "Вероятность направления: %.0f%%; edge %.0f%%, gap %.1f п.п., ADX %.1f, R/R %.2f, подтверждение %d/5.".format(Locale.US, calibratedProbability, selectedEdge * 100.0, edgeGap * 100.0, adxV, rr, confirmation)
        )
        val robustEdge = selectedEdge >= 0.62 && edgeGap >= 0.08
        val robustTrend = abs(confirmation) >= 3 && adxV >= 22.0
        val robustStructure = rr >= 1.60 && abs(advancedTechnical) >= 0.90
        val highConviction = signal != "NO TRADE" && robustEdge && robustTrend && robustStructure
        val finalSignal = signal
        // For NO TRADE, confidence describes directional certainty only as a
        // probability estimate; it is never presented as permission to trade.
        val finalConfidence = confidence
        val expectedProfitPct = if (price == 0.0) 0.0 else abs(safeTp2 - price) / price * 100.0
        val expectedLossPct = if (price == 0.0) 0.0 else abs(price - stop) / price * 100.0
        val finalExplanation = explanation + listOf(
            "Режим рынка: $regime; ширина Bollinger %.2f%%.".format(Locale.US, bbWidth * 100.0),
            String.format(Locale.US, "Risk Engine: SL ограничен %.2f%% цены; R/R по TP2 %.2f.", expectedLossPct, rr)
        )
        return Forecast(finalSignal, score, finalConfidence, trendBase, momentumBase, abs(a / price) * 100.0, levelBase, volumeBase, bull, base, bear,
            price, stop, stopAggressive, stopOptimal, stopConservative, safeTp1, safeTp2, safeTp3, rr, projected, support1, support2, resistance1, resistance2, finalExplanation, quality, regime, expectedProfitPct, expectedLossPct, selectedEdge, edgeGap, confirmation, advancedTechnical, highConviction, patterns.score, patterns.names)
    }

    fun backtest(c: List<Candle>): Pair<Int, Int> {
        if (c.size < 120) return 0 to 0
        var wins = 0; var total = 0; var i = 80
        while (i < c.size - 8) {
            val f = runCatching { analyzeInternal(c.subList(0, i), null, false) }.getOrNull()
            if (f != null && f.signal != "NO TRADE") {
                val dir = if (f.signal.contains("LONG")) 1 else -1
                val entry = c[i - 1].close
                val horizon = c.subList(i, min(i + 8, c.size))
                val hit = if (dir > 0) horizon.any { it.high >= f.tp1 } else horizon.any { it.low <= f.tp1 }
                val stopped = if (dir > 0) horizon.any { it.low <= f.stop } else horizon.any { it.high >= f.stop }
                if (hit && !stopped) wins++ else if (!hit && !stopped) {
                    val final = horizon.last().close
                    if ((dir > 0 && final > entry) || (dir < 0 && final < entry)) wins++
                }
                total++
            }
            i += 8
        }
        return wins to total
    }
}
