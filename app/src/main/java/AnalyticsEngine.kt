package com.marketforecast.prox

import java.util.Locale
import kotlin.math.*

/**
 * Single production forecast engine.
 *
 * The engine is deliberately regime-aware: indicators are grouped into partially
 * independent evidence blocks, LONG and SHORT are calculated independently, and
 * the final signal is gated by structure, entry quality, target feasibility,
 * historical conditional edge and signal stability.
 *
 * No future candle is used by analyze(). Historical statistics are calculated
 * only from candles strictly before the decision candle.
 */
object AnalyticsEngine {
    private const val MIN_HISTORY = 80
    private const val CACHE_MAX = 256

    private data class CacheEntry(val key: String, val forecast: Forecast)
    private val cache = LinkedHashMap<String, Forecast>(CACHE_MAX, .75f, true)

    private data class Levels(
        val support1: Double, val support2: Double,
        val resistance1: Double, val resistance2: Double
    )

    private data class Structure(
        val score: Double,
        val bullish: Boolean,
        val bearish: Boolean,
        val hh: Int,
        val hl: Int,
        val lh: Int,
        val ll: Int,
        val bos: Double,
        val choch: Double
    )

    private data class RegimeResult(
        val name: String,
        val confidence: Double,
        val trendStrength: Double,
        val impulse: Double,
        val acceleration: Double,
        val fatigue: Double,
        val continuation: Double,
        val reversal: Double
    )

    private data class Liquidity(
        val score: Double,
        val sweep: Double,
        val equalHighs: Double,
        val equalLows: Double
    )

    private data class Historical(
        val edge: Double,
        val sample: Int,
        val tp1: Double,
        val tp2: Double,
        val tp3: Double,
        val sl: Double,
        val avgMfeR: Double,
        val avgMaeR: Double
    )

    private data class Evidence(
        val long: Double,
        val short: Double,
        val longReasons: List<String>,
        val shortReasons: List<String>,
        val risks: List<String>
    )

    private fun closes(c: List<Candle>) = c.map { it.close }

    private fun ema(x: List<Double>, n: Int): Double? {
        if (x.size < n) return null
        val k = 2.0 / (n + 1.0)
        var e = x.take(n).average()
        for (i in n until x.size) e += (x[i] - e) * k
        return e
    }

    private fun emaSlope(x: List<Double>, n: Int, lookback: Int = 5): Double {
        if (x.size < n + lookback) return 0.0
        val a = ema(x.dropLast(lookback), n) ?: return 0.0
        val b = ema(x, n) ?: return 0.0
        return if (a == 0.0) 0.0 else ((b - a) / abs(a)).coerceIn(-.25, .25)
    }

    private fun rsi(x: List<Double>, n: Int = 14): Double {
        if (x.size <= n) return 50.0
        var gain = 0.0
        var loss = 0.0
        for (i in 1..n) {
            val d = x[i] - x[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        gain /= n
        loss /= n
        for (i in n + 1 until x.size) {
            val d = x[i] - x[i - 1]
            gain = (gain * (n - 1) + max(d, 0.0)) / n
            loss = (loss * (n - 1) + max(-d, 0.0)) / n
        }
        return if (loss <= 1e-12) 100.0 else 100.0 - 100.0 / (1.0 + gain / loss)
    }

    private fun rsiSlope(x: List<Double>): Double {
        if (x.size < 25) return 0.0
        val a = rsi(x.dropLast(5))
        val b = rsi(x)
        return ((b - a) / 20.0).coerceIn(-1.0, 1.0)
    }

    private fun trueRange(c: List<Candle>): List<Double> = if (c.size < 2) emptyList() else buildList {
        for (i in 1 until c.size) {
            val cur = c[i]; val prev = c[i - 1]
            add(max(cur.high - cur.low, max(abs(cur.high - prev.close), abs(cur.low - prev.close))))
        }
    }

    private fun atr(c: List<Candle>, n: Int = 14): Double {
        val tr = trueRange(c)
        return if (tr.isEmpty()) 0.0 else tr.takeLast(min(n, tr.size)).average()
    }

    private fun atrSeries(c: List<Candle>, n: Int = 14): List<Double> {
        val tr = trueRange(c)
        if (tr.size < n) return emptyList()
        var value = tr.take(n).average()
        val out = ArrayList<Double>()
        out += value
        for (i in n until tr.size) {
            value = (value * (n - 1) + tr[i]) / n
            out += value
        }
        return out
    }

    private fun macdHistogram(x: List<Double>): Double {
        if (x.size < 35) return 0.0
        val macd = ArrayList<Double>()
        for (i in 26 until x.size) {
            val e12 = ema(x.subList(0, i + 1), 12) ?: continue
            val e26 = ema(x.subList(0, i + 1), 26) ?: continue
            macd += e12 - e26
        }
        if (macd.size < 10) return 0.0
        val signal = ema(macd, min(9, macd.size)) ?: macd.last()
        return macd.last() - signal
    }

    private fun adx(c: List<Candle>, n: Int = 14): Double {
        if (c.size < n * 2 + 2) return 0.0
        val tr = trueRange(c)
        val plus = DoubleArray(tr.size)
        val minus = DoubleArray(tr.size)
        for (i in 1 until c.size) {
            val up = c[i].high - c[i - 1].high
            val down = c[i - 1].low - c[i].low
            plus[i - 1] = if (up > down && up > 0) up else 0.0
            minus[i - 1] = if (down > up && down > 0) down else 0.0
        }
        var trN = tr.take(n).sum(); var pN = plus.take(n).sum(); var mN = minus.take(n).sum()
        val dx = ArrayList<Double>()
        fun currentDx(): Double {
            val p = if (trN == 0.0) 0.0 else 100.0 * pN / trN
            val m = if (trN == 0.0) 0.0 else 100.0 * mN / trN
            return if (p + m == 0.0) 0.0 else abs(p - m) / (p + m) * 100.0
        }
        dx += currentDx()
        for (i in n until tr.size) {
            trN = trN - trN / n + tr[i]
            pN = pN - pN / n + plus[i]
            mN = mN - mN / n + minus[i]
            dx += currentDx()
        }
        return if (dx.size < n) dx.average() else ema(dx, n) ?: dx.last()
    }

    private fun bollinger(c: List<Double>, n: Int = 20): Triple<Double, Double, Double> {
        if (c.size < n) return Triple(0.0, 0.0, 0.0)
        val w = c.takeLast(n); val m = w.average()
        val sd = sqrt(w.map { (it - m).pow(2) }.average())
        if (sd <= 1e-12) return Triple(0.0, 0.0, 0.0)
        return Triple((c.last() - m) / (2.0 * sd), 2.0 * sd / max(abs(m), 1e-9), m)
    }

    private fun stochastic(c: List<Candle>, n: Int = 14): Double {
        if (c.size < n) return 50.0
        val w = c.takeLast(n); val hi = w.maxOf { it.high }; val lo = w.minOf { it.low }
        return if (hi == lo) 50.0 else ((c.last().close - lo) / (hi - lo) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun vwap(c: List<Candle>, n: Int = 30): Double {
        val w = c.takeLast(min(n, c.size)); var pv = 0.0; var vv = 0.0
        for (x in w) {
            val v = x.volume.coerceAtLeast(0.0)
            pv += ((x.high + x.low + x.close) / 3.0) * v; vv += v
        }
        return if (vv > 0) pv / vv else w.lastOrNull()?.close ?: 0.0
    }

    private fun volumeRatio(c: List<Candle>, n: Int = 20): Double {
        if (c.size < n + 1) return 1.0
        val avg = c.dropLast(1).takeLast(n).map { it.volume }.average()
        return if (avg <= 0) 1.0 else c.last().volume / avg
    }

    private fun pressure(c: Candle): Double {
        val range = (c.high - c.low).coerceAtLeast(1e-12)
        return ((c.close - c.open) / range).coerceIn(-1.0, 1.0)
    }

    private fun structure(c: List<Candle>): Structure {
        if (c.size < 30) return Structure(0.0, false, false, 0, 0, 0, 0, 0.0, 0.0)
        val w = c.takeLast(min(100, c.size))
        val highs = ArrayList<Double>(); val lows = ArrayList<Double>()
        for (i in 2 until w.lastIndex - 1) {
            if (w[i].high > w[i - 1].high && w[i].high >= w[i + 1].high) highs += w[i].high
            if (w[i].low < w[i - 1].low && w[i].low <= w[i + 1].low) lows += w[i].low
        }
        fun countDir(v: List<Double>, up: Boolean): Int {
            if (v.size < 2) return 0
            var n = 0
            for (i in max(1, v.size - 5) until v.size) if (if (up) v[i] > v[i - 1] else v[i] < v[i - 1]) n++
            return n
        }
        val hh = countDir(highs, true); val lh = countDir(highs, false)
        val hl = countDir(lows, true); val ll = countDir(lows, false)
        val range = atr(w).coerceAtLeast(1e-9)
        val last = w.last()
        val priorHigh = w.dropLast(2).takeLast(30).maxOfOrNull { it.high } ?: last.high
        val priorLow = w.dropLast(2).takeLast(30).minOfOrNull { it.low } ?: last.low
        val bos = when {
            last.close > priorHigh + range * .08 -> 1.0
            last.close < priorLow - range * .08 -> -1.0
            else -> 0.0
        }
        val prev = w[w.lastIndex - 1]
        val choch = when {
            bos > 0 && prev.close < prev.open -> .5
            bos < 0 && prev.close > prev.open -> -.5
            else -> 0.0
        }
        val directional = ((hh + hl) - (lh + ll)).toDouble() / 10.0 + bos * .8 + choch * .5
        val score = (directional / 1.8).coerceIn(-1.0, 1.0)
        return Structure(score, score > .25, score < -.25, hh, hl, lh, ll, bos, choch)
    }

    private fun liquidity(c: List<Candle>, a: Double): Liquidity {
        if (c.size < 35 || a <= 0) return Liquidity(.5, 0.0, 0.0, 0.0)
        val w = c.takeLast(min(80, c.size)); val tol = a * .18
        var eqH = 0.0; var eqL = 0.0
        for (i in 3 until w.size - 3) {
            val h = w[i].high; val l = w[i].low
            if ((w[i - 2].high - h).absoluteValue <= tol || (w[i + 2].high - h).absoluteValue <= tol) eqH++
            if ((w[i - 2].low - l).absoluteValue <= tol || (w[i + 2].low - l).absoluteValue <= tol) eqL++
        }
        val prior = w.dropLast(1); val last = w.last()
        val ph = prior.maxOf { it.high }; val pl = prior.minOf { it.low }
        val sweep = when {
            last.high > ph + tol && last.close < ph -> -1.0
            last.low < pl - tol && last.close > pl -> 1.0
            else -> 0.0
        }
        val cluster = ((eqH + eqL) / max(10.0, w.size / 2.0)).coerceIn(0.0, 1.0)
        return Liquidity((.35 + cluster * .35 + if (sweep != 0.0) .30 else 0.0).coerceIn(0.0, 1.0), sweep, (eqH / 10.0).coerceIn(0.0, 1.0), (eqL / 10.0).coerceIn(0.0, 1.0))
    }

    private fun levels(c: List<Candle>): Levels {
        val w = c.takeLast(min(80, c.size)); val p = w.last().close
        val pivH = w.mapIndexedNotNull { i, x -> if (i in 2 until w.lastIndex - 1 && x.high >= w[i - 1].high && x.high >= w[i + 1].high) x.high else null }
        val pivL = w.mapIndexedNotNull { i, x -> if (i in 2 until w.lastIndex - 1 && x.low <= w[i - 1].low && x.low <= w[i + 1].low) x.low else null }
        val supports = (pivL.filter { it < p }.sortedDescending() + listOf(w.minOf { it.low })).distinct()
        val resistances = (pivH.filter { it > p }.sorted() + listOf(w.maxOf { it.high })).distinct()
        return Levels(
            supports.getOrElse(0) { p - atr(c) }, supports.getOrElse(1) { p - atr(c) * 2 },
            resistances.getOrElse(0) { p + atr(c) }, resistances.getOrElse(1) { p + atr(c) * 2 }
        )
    }

    private fun regime(c: List<Candle>, s: Structure, a: Double, adx: Double, bbWidth: Double): RegimeResult {
        val x = closes(c); val p = x.last()
        val slope = emaSlope(x, 20)
        val mom5 = if (x.size > 5) x.last() / x[x.lastIndex - 5] - 1 else 0.0
        val mom20 = if (x.size > 20) x.last() / x[x.lastIndex - 20] - 1 else 0.0
        val recent = c.takeLast(20)
        val path = recent.zipWithNext().sumOf { abs(it.second.close - it.first.close) }.coerceAtLeast(1e-9)
        val efficiency = abs(recent.last().close - recent.first().close) / path
        val ranges = recent.map { it.high - it.low }
        val expansion = ranges.takeLast(5).average() / ranges.average().coerceAtLeast(1e-9)
        val r = rsi(x)
        val fatigue = ((if (r > 72 || r < 28) .35 else 0.0) + (if (expansion < .75 && abs(mom5) > .006) .25 else 0.0) + (if (s.score * slope < 0) .25 else 0.0)).coerceIn(0.0, 1.0)
        val acceleration = ((abs(mom5) / max(abs(mom20), .0005) - 1.0) / 2.0).coerceIn(-1.0, 1.0)
        val impulse = (abs(mom5) / max(a / p, .0005) * .12 + expansion * .35 + efficiency * .35).coerceIn(0.0, 1.0)
        val compression = bbWidth < 0.035 && expansion < .85
        val directional = abs(s.score) > .38 && adx >= 21 && efficiency >= .38
        val breakout = abs(s.bos) > 0.5
        val sweep = liquidity(c, a).sweep
        val reversal = (abs(s.choch) > 0.2 || sweep != 0.0) && fatigue >= .2
        val name = when {
            compression -> "COMPRESSION"
            reversal && s.score > 0 -> "REVERSAL_UP"
            reversal && s.score < 0 -> "REVERSAL_DOWN"
            breakout && s.bos > 0 -> "BREAKOUT_UP"
            breakout && s.bos < 0 -> "BREAKOUT_DOWN"
            impulse > .72 && mom5 > 0 -> if (fatigue > .45) "EXHAUSTION_UP" else "IMPULSE_UP"
            impulse > .72 && mom5 < 0 -> if (fatigue > .45) "EXHAUSTION_DOWN" else "IMPULSE_DOWN"
            directional && s.score > 0 -> "TREND_UP"
            directional && s.score < 0 -> "TREND_DOWN"
            adx < 18 && efficiency < .30 -> "RANGE"
            abs(s.score) < .30 && abs(slope) < .05 && adx < 30 && efficiency < .50 -> "RANGE"
            abs(s.score) < .18 && adx < 23 -> "TRANSITION"
            bbWidth > .09 -> "HIGH_VOLATILITY"
            else -> "UNCERTAIN"
        }
        val confidence = (35 + abs(s.score) * 25 + min(adx, 40.0) * .65 + efficiency * 20 + if (compression) 5 else 0).coerceIn(0.0, 100.0)
        return RegimeResult(name, confidence / 100.0, s.score, impulse, acceleration, fatigue,
            (if (s.score > 0) .5 + impulse * .3 - fatigue * .25 else .5 - impulse * .3 + fatigue * .25).coerceIn(0.02, .98),
            (if (s.score > 0) .5 - impulse * .2 + fatigue * .35 else .5 + impulse * .2 - fatigue * .35).coerceIn(.02, .98))
    }

    private fun setup(reg: RegimeResult, s: Structure, l: Liquidity, price: Double, lv: Levels, a: Double): String {
        return when {
            reg.name.startsWith("BREAKOUT") -> "BREAKOUT_RETEST"
            reg.name.startsWith("REVERSAL") && l.sweep > 0 -> "LIQUIDITY_SWEEP_REVERSAL"
            reg.name.startsWith("REVERSAL") -> "STRUCTURAL_REVERSAL"
            reg.name == "RANGE" && price <= lv.support1 + a * .35 -> "RANGE_LONG"
            reg.name == "RANGE" && price >= lv.resistance1 - a * .35 -> "RANGE_SHORT"
            reg.name == "EXHAUSTION_UP" || reg.name == "EXHAUSTION_DOWN" -> "EXHAUSTION_REVERSAL"
            reg.name == "IMPULSE_UP" || reg.name == "IMPULSE_DOWN" -> "MOMENTUM_CONTINUATION"
            s.bullish && price < lv.resistance1 -> "TREND_PULLBACK"
            s.bearish && price > lv.support1 -> "TREND_PULLBACK"
            else -> "NO_CLEAR_SETUP"
        }
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x.coerceIn(-8.0, 8.0)))

    private fun historical(c: List<Candle>, direction: Int, regimeName: String, horizon: Int = 8): Historical {
        if (c.size < 150) return Historical(.5, 0, .5, .5, .5, .5, 1.0, 1.0)
        val end = c.lastIndex - 2
        var samples = 0; var tp1 = 0; var tp2 = 0; var tp3 = 0; var sl = 0
        var mfe = 0.0; var mae = 0.0
        val step = max(1, min(4, c.size / 250))
        for (i in 90..end step step) {
            val prefix = c.subList(0, i + 1)
            val p = prefix.last().close; val a = atr(prefix); if (a <= 0) continue
            val st = structure(prefix); val ad = adx(prefix); val bw = bollinger(closes(prefix)).second
            val rg = regime(prefix, st, a, ad, bw)
            val same = if (regimeName == rg.name) 1.0 else 0.0
            val dirEvidence = st.score * direction
            if (dirEvidence < -.15 && same == 0.0) continue
            val future = c.subList(i + 1, min(c.size, i + 1 + horizon))
            if (future.isEmpty()) continue
            val r1 = 0.8; val r2 = 1.5; val r3 = 2.2
            var hit1 = false; var hit2 = false; var hit3 = false; var stopped = false
            var localMfe = 0.0; var localMae = 0.0
            for (f in future) {
                val favorable = if (direction > 0) (f.high - p) / a else (p - f.low) / a
                val adverse = if (direction > 0) (p - f.low) / a else (f.high - p) / a
                localMfe = max(localMfe, favorable); localMae = max(localMae, adverse)
                if (adverse >= 1.0) stopped = true
                if (favorable >= r1) hit1 = true
                if (favorable >= r2) hit2 = true
                if (favorable >= r3) hit3 = true
            }
            samples++; if (hit1) tp1++; if (hit2) tp2++; if (hit3) tp3++; if (stopped) sl++
            mfe += localMfe; mae += localMae
        }
        if (samples == 0) return Historical(.5, 0, .5, .5, .5, .5, 1.0, 1.0)
        fun smooth(hits: Int, base: Double): Double = ((hits + base * 12.0) / (samples + 12.0)).coerceIn(.01, .99)
        return Historical(
            smooth(tp1, .5), samples,
            smooth(tp1, .5), smooth(tp2, .38), smooth(tp3, .24), smooth(sl, .32),
            mfe / samples, mae / samples
        )
    }

    private fun stableScore(c: List<Candle>, entry: Double?): Double {
        if (c.size < 45) return .35
        val cuts = listOf(0, 1, 2, 3).mapNotNull { n ->
            val x = c.dropLast(n); if (x.size < 40) null else directionalRaw(x, entry)
        }
        if (cuts.size < 2) return .35
        val mean = cuts.average(); val variance = cuts.map { (it - mean).pow(2) }.average()
        return (1.0 - sqrt(variance) / 1.5).coerceIn(0.0, 1.0)
    }

    private fun directionalRaw(c: List<Candle>, entry: Double?): Double {
        val x = closes(c); val p = entry ?: x.last(); val a = atr(c).coerceAtLeast(p * .001)
        val e20 = ema(x, 20) ?: p; val e50 = ema(x, 50) ?: p
        val s = structure(c); val ad = adx(c); val r = rsi(x)
        val trend = ((e20 - e50) / p * 18.0 + emaSlope(x, 20) * 3.0).coerceIn(-2.0, 2.0)
        val mom = ((p / x[x.lastIndex - min(10, x.lastIndex)] - 1.0) / max(a / p, .001)).coerceIn(-2.0, 2.0)
        val osc = ((r - 50.0) / 35.0).coerceIn(-1.5, 1.5)
        return (trend * .30 + s.score * .38 + mom * .22 + osc * .10 + (ad - 20.0) / 100.0).coerceIn(-3.0, 3.0)
    }

    private fun evidence(c: List<Candle>, reg: RegimeResult, s: Structure, l: Liquidity, lv: Levels, a: Double): Evidence {
        val x = closes(c); val p = x.last()
        val e20 = ema(x, 20) ?: p; val e50 = ema(x, 50) ?: p; val e200 = ema(x, 200)
        val r = rsi(x); val rs = rsiSlope(x); val mh = macdHistogram(x)
        val st = stochastic(c); val bb = bollinger(x).first; val vr = volumeRatio(c)
        val vw = vwap(c); val vwapDir = ((p - vw) / p / max(a / p, .001)).coerceIn(-2.0, 2.0)
        val pressure = pressure(c.last()); val slope = emaSlope(x, 20)
        val longParts = mutableListOf<Pair<Double,String>>(); val shortParts = mutableListOf<Pair<Double,String>>()
        fun both(v: Double, name: String) { if (v > 0) longParts += min(v,1.0) to name else if (v < 0) shortParts += min(-v,1.0) to name }
        both(s.score * .95, if (s.score > 0) "Структура HH/HL и BOS поддерживают покупателей" else "Структура LH/LL и BOS поддерживают продавцов")
        both((e20 - e50) / p / max(a / p, .001) * .5, "EMA-структура и наклон")
        both((slope * 5.0), "Наклон EMA20")
        both(((mh / max(p,1e-9)) / max(a / p,.001) * 1.8).coerceIn(-1.0,1.0), "MACD histogram")
        both(rs * .6, "Изменение RSI")
        both(((st - 50.0) / 50.0) * .55, "Stochastic")
        both(pressure * .55, "Давление последней свечи")
        both(vwapDir * .35, "VWAP-контекст")
        both(((vr - 1.0) / 1.5).coerceIn(-1.0,1.0) * pressure.sign * .55, "Объём относительно среднего")
        if (e200 != null) both(((e50 - e200) / p / max(a / p,.001) * .35).coerceIn(-1.0,1.0), "Долгосрочная структура")
        if (reg.impulse > .65) both(if (x.last() >= x[x.lastIndex - 5]) .45 else -.45, "Импульс")
        if (reg.fatigue > .5) both(if (s.score > 0) -.55 else .55, "Истощение движения")
        if (l.sweep != 0.0) both(l.sweep * .85, "Снятие ликвидности")
        val nearSupport = abs(p - lv.support1) <= a * .45
        val nearResistance = abs(p - lv.resistance1) <= a * .45
        if (nearSupport) longParts += .65 to "Цена у поддержки"
        if (nearResistance) shortParts += .65 to "Цена у сопротивления"
        if (bb > .85) shortParts += .25 to "Верхняя часть Bollinger"
        if (bb < -.85) longParts += .25 to "Нижняя часть Bollinger"
        if (r > 76 && reg.name != "TREND_UP" && reg.name != "IMPULSE_UP") shortParts += .55 to "Перегрев RSI вне импульсного тренда"
        if (r < 24 && reg.name != "TREND_DOWN" && reg.name != "IMPULSE_DOWN") longParts += .55 to "Перепроданность RSI вне импульсного тренда"
        val independentLong = longParts.groupBy { it.second.substringBefore(' ') }.values.sumOf { it.maxOfOrNull { x -> x.first } ?: 0.0 }
        val independentShort = shortParts.groupBy { it.second.substringBefore(' ') }.values.sumOf { it.maxOfOrNull { x -> x.first } ?: 0.0 }
        val long = (independentLong / 7.0).coerceIn(0.0, 1.0)
        val short = (independentShort / 7.0).coerceIn(0.0, 1.0)
        val risks = mutableListOf<String>()
        if (abs(long - short) < .12) risks += "Стороны рынка близки: направленное преимущество слабое"
        if (reg.confidence < .55) risks += "Режим рынка определён неуверенно"
        if (reg.fatigue > .55) risks += "Есть признаки истощения движения"
        if (nearResistance && long > short) risks += "LONG находится близко к сопротивлению"
        if (nearSupport && short > long) risks += "SHORT находится близко к поддержке"
        if (vr > 2.5) risks += "Аномальный объём повышает риск резкой волатильности"
        return Evidence(long, short, longParts.sortedByDescending { it.first }.take(5).map { it.second }, shortParts.sortedByDescending { it.first }.take(5).map { it.second }, risks)
    }

    private fun directionProbability(score: Double, historical: Historical, calibration: Double, stability: Double): Double {
        val raw = sigmoid((score - .5) * 5.2)
        val blended = raw * .45 + historical.edge * .30 + calibration * .15 + stability * .10
        return blended.coerceIn(.03, .97)
    }

    private fun expectedValue(tp1: Double, tp2: Double, tp3: Double, sl: Double): Double {
        return tp1 * .8 + tp2 * 1.5 + tp3 * 2.2 - sl * 1.0
    }

    private fun cacheKey(c: List<Candle>, entry: Double?): String {
        var h = 17L
        val points = intArrayOf(0, c.size / 3, c.size * 2 / 3, c.lastIndex).distinct().filter { it in c.indices }
        for (i in points) { val x = c[i]; h = h * 31 + x.time; h = h * 31 + java.lang.Double.doubleToLongBits(x.close) }
        return "${c.size}:$h:${entry ?: Double.NaN}"
    }

    /** Analyze one timeframe. All probabilities are empirical/model estimates, never guarantees. */
    fun analyze(c: List<Candle>, entryOverride: Double? = null): Forecast {
        require(c.size >= 30) { "Недостаточно исторических данных" }
        val key = cacheKey(c, entryOverride)
        synchronized(cache) { cache[key]?.let { return it } }
        val result = analyzeInternal(c, entryOverride)
        synchronized(cache) {
            cache[key] = result
            while (cache.size > CACHE_MAX) cache.remove(cache.entries.first().key)
        }
        return result
    }

    /** Optional real-MTF entry point. Contexts are expected to contain actual BCS candles per TF. */
    fun analyze(c: List<Candle>, entryOverride: Double?, mtfContexts: Map<String, List<Candle>>): Forecast {
        val base = analyze(c, entryOverride)
        if (mtfContexts.isEmpty()) return base
        val local = mtfContexts.mapNotNull { (tf, candles) ->
            if (candles.size < 40) null else tf to directionalRaw(candles, candles.lastOrNull()?.close)
        }
        if (local.isEmpty()) return base
        val weights = mapOf("15M" to .10, "1H" to .18, "4H" to .27, "1D" to .30, "1W" to .15)
        var bull = 0.0; var total = 0.0
        local.forEach { (tf, v) -> val w = weights[tf] ?: .10; bull += v * w; total += w }
        val agreement = (bull / total).coerceIn(-1.0,1.0)
        val conflict = local.count { it.second.sign != agreement.sign && abs(it.second) > .15 }.toDouble() / local.size
        val penalty = conflict * .12
        val lp = (base.bull / 100.0 + agreement.coerceAtLeast(0.0) * .12 - penalty).coerceIn(.01,.99)
        val sp = (base.bear / 100.0 + (-agreement).coerceAtLeast(0.0) * .12 - penalty).coerceIn(.01,.99)
        val signal = when {
            lp - sp > .12 && base.signal != "NO TRADE" -> "LONG"
            sp - lp > .12 && base.signal != "NO TRADE" -> "SHORT"
            else -> "NO TRADE"
        }
        return base.copy(signal = signal, confidence = (base.confidence * (.82 + abs(agreement)*.18) * (1.0 - conflict*.20)).roundToInt().coerceIn(0,100),
            bull = (lp*100).roundToInt(), bear = (sp*100).roundToInt(), base = (100 - (lp*100).roundToInt() - (sp*100).roundToInt()).coerceAtLeast(0),
            mtfHierarchy = agreement, mtfAgreement = abs(agreement), mtfConflict = conflict,
            explanation = base.explanation + listOf("Реальные MTF-данные: ${local.joinToString { it.first }}; согласование %.0f%%, конфликт %.0f%%.".format(Locale.US, abs(agreement)*100, conflict*100)))
    }

    private fun analyzeInternal(c: List<Candle>, entryOverride: Double?): Forecast {
        require(c.size >= 30) { "Недостаточно исторических данных" }
        val x = closes(c); val price = entryOverride?.takeIf { it.isFinite() && it > 0 } ?: x.last()
        require(price.isFinite() && price > 0) { "Цена должна быть положительной и конечной" }
        val a = atr(c).coerceAtLeast(price * .0005)
        val e20 = ema(x,20) ?: price; val e50 = ema(x,50) ?: price
        val ad = adx(c); val bb = bollinger(x)
        val s = structure(c); val liq = liquidity(c,a); val lv = levels(c)
        val reg = regime(c,s,a,ad,bb.second)
        val ev = evidence(c,reg,s,liq,lv,a)
        val setupType = setup(reg,s,liq,price,lv,a)
        val longHist = historical(c,1,reg.name); val shortHist = historical(c,-1,reg.name)
        val stability = stableScore(c,entryOverride)
        val longDirection = directionProbability(ev.long,longHist,reg.confidence,stability)
        val shortDirection = directionProbability(ev.short,shortHist,reg.confidence,stability)
        val conflict = min(longDirection,shortDirection)
        val longScore = (longDirection*100).coerceIn(0.0,100.0)
        val shortScore = (shortDirection*100).coerceIn(0.0,100.0)
        val dominant = if (longDirection >= shortDirection) 1 else -1
        val directionalGap = abs(longDirection-shortDirection)
        val rangePct = a / price
        val nearSupport = abs(price-lv.support1) / a
        val nearResistance = abs(price-lv.resistance1) / a
        val entryQuality = when {
            dominant > 0 -> (1.0 - min(1.0, nearResistance * .45) + if (nearSupport < 1.0) .25 else 0.0 - reg.fatigue*.35).coerceIn(0.0,1.0)
            else -> (1.0 - min(1.0, nearSupport * .45) + if (nearResistance < 1.0) .25 else 0.0 - reg.fatigue*.35).coerceIn(0.0,1.0)
        }
        val entryZoneLow: Double; val entryZoneHigh: Double
        if (dominant > 0) { entryZoneLow = max(lv.support1, price - a*.55); entryZoneHigh = min(price + a*.20, lv.resistance1) }
        else { entryZoneLow = max(lv.support1, price - a*.20); entryZoneHigh = min(lv.resistance1, price + a*.55) }
        val stopDistance = (a * when {
            reg.name.contains("RANGE") -> 1.05
            reg.name.contains("IMPULSE") -> 1.45
            reg.name.contains("HIGH_VOLATILITY") -> 1.75
            else -> 1.25
        }).coerceAtLeast(price*.004)
        val stop = if (dominant > 0) min(lv.support1 - a*.12, price-stopDistance) else max(lv.resistance1+a*.12, price+stopDistance)
        val risk = abs(price-stop).coerceAtLeast(a*.65)
        val projectedMfe = if (dominant > 0) longHist.avgMfeR else shortHist.avgMfeR
        val targetCap = max(1.8, min(3.8, projectedMfe.coerceAtLeast(2.0)))
        val tp1R = min(.9, targetCap*.42); val tp2R = min(1.8, targetCap*.72); val tp3R = targetCap
        val tp1 = if (dominant > 0) price+risk*tp1R else price-risk*tp1R
        val tp2 = if (dominant > 0) price+risk*tp2R else price-risk*tp2R
        val tp3 = if (dominant > 0) price+risk*tp3R else price-risk*tp3R
        val hist = if (dominant > 0) longHist else shortHist
        val feasibility = (1.0 - max(0.0, (risk / a - 2.0) * .12)).coerceIn(.55,1.0)
        val tp1p = (hist.tp1 * .65 + longDirection.coerceAtLeast(shortDirection)*.35).coerceIn(.05,.95) * feasibility
        val tp2p = min(tp1p * .88, (hist.tp2*.72 + longDirection.coerceAtLeast(shortDirection)*.28).coerceIn(.03,.90))
        val tp3p = min(tp2p * .82, (hist.tp3*.78 + longDirection.coerceAtLeast(shortDirection)*.22).coerceIn(.02,.80))
        val slp = (hist.sl*.55 + (1.0-longDirection.coerceAtLeast(shortDirection))*.45 + reg.fatigue*.12 + conflict*.15).coerceIn(.03,.92)
        val evR = expectedValue(tp1p,tp2p,tp3p,slp)
        val srPenalty = if (dominant > 0) (1.0 / (1.0 + nearResistance)).coerceIn(0.0,1.0)*2.8 else (1.0/(1.0+nearSupport)).coerceIn(0.0,1.0)*2.8
        val calibrationQuality = ((min(longHist.sample,shortHist.sample) / 250.0).coerceIn(0.0,1.0)*.65 + reg.confidence*.35)
        val dataQuality = (55 + min(35, c.size/8) + if (c.all { it.close > 0 && it.high >= it.low }) 10 else 0).coerceIn(0,100)
        val gate = directionalGap >= .10 && entryQuality >= .35 && evR >= .05 && stability >= .35 && dataQuality >= 70 && reg.name !in setOf("UNCERTAIN", "TRANSITION", "COMPRESSION")
        val signal = if (gate && longDirection > shortDirection) "LONG" else if (gate && shortDirection > longDirection) "SHORT" else "NO TRADE"
        val confidence = ((40 + directionalGap*65 + reg.confidence*22 + stability*10 + calibrationQuality*8 - conflict*18 - srPenalty*2).coerceIn(0.0,100.0)).roundToInt()
        val bull = (longDirection*100).roundToInt().coerceIn(0,100)
        val bear = (shortDirection*100).roundToInt().coerceIn(0,100)
        val base = (100-bull-bear).coerceAtLeast(0)
        val trendValue = ((e20-e50)/price*1000).coerceIn(-10.0,10.0)
        val momentumValue = (((price/x[x.lastIndex-min(10,x.lastIndex)])-1.0)/rangePct).coerceIn(-10.0,10.0)
        val volumeValue = ((volumeRatio(c)-1.0)*4.0).coerceIn(-10.0,10.0)*pressure(c.last()).sign
        val levelValue = ((if (dominant>0) price-lv.support1 else lv.resistance1-price)/a).coerceIn(-5.0,5.0)
        val projected = if (dominant>0) tp2 else tp2
        val explanation = buildList {
            add("Режим: ${reg.name}; уверенность классификатора %.0f%%.".format(Locale.US,reg.confidence*100))
            add("Setup: $setupType. Структура %.0f%%, импульс %.0f%%, истощение %.0f%%.".format(Locale.US,abs(s.score)*100,reg.impulse*100,reg.fatigue*100))
            add("LONG %.0f%% / SHORT %.0f%%; разрыв %.1f п.п.; стабильность %.0f%%.".format(Locale.US,longDirection*100,shortDirection*100,directionalGap*100,stability*100))
            add("Историческая выборка: LONG ${longHist.sample}, SHORT ${shortHist.sample}; качество калибровки %.0f%%.".format(Locale.US,calibrationQuality*100))
            add("TP1 %.0f%% / TP2 %.0f%% / TP3 %.0f%% / SL %.0f%%; EV %.2fR.".format(Locale.US,tp1p*100,tp2p*100,tp3p*100,slp*100,evR))
            if (ev.longReasons.isNotEmpty()) add("LONG: ${ev.longReasons.joinToString("; ")}.")
            if (ev.shortReasons.isNotEmpty()) add("SHORT: ${ev.shortReasons.joinToString("; ")}.")
            if (ev.risks.isNotEmpty()) add("Риски: ${ev.risks.joinToString("; ")}.")
        }
        val rr = tp2R
        val expectedProfitPct = abs(tp2-price)/price*100
        val expectedLossPct = risk/price*100
        return Forecast(
            signal, if (dominant>0) longScore-shortScore else -(shortScore-longScore), confidence,
            trendValue, momentumValue, a/price*100, levelValue, volumeValue,
            bull,base,bear,price,stop, if (dominant>0) price-a else price+a, stop, if (dominant>0) price-risk*1.35 else price+risk*1.35,
            tp1,tp2,tp3,rr,projected,lv.support1,lv.support2,lv.resistance1,lv.resistance2,explanation,dataQuality,reg.name,
            expectedProfitPct,expectedLossPct,hist.edge,max(longDirection,shortDirection)-.5, (directionalGap*100).roundToInt(),
            (ev.long-ev.short)*10, signal!="NO TRADE" && evR>.20, (s.score*10), emptyList(),
            tp1p,tp2p,tp3p,slp,evR,hist.edge,abs(s.score),liq.score,srPenalty,entryQuality,entryZoneLow,entryZoneHigh,
            longScore,shortScore,reg.confidence,abs(s.score),conflict,reg.impulse,reg.acceleration,reg.fatigue,stability,hist.sample,calibrationQuality,setupType,
            reg.continuation,reg.reversal,ev.longReasons,ev.shortReasons,ev.risks
        ).let { f -> f.copy(detectedPatterns = listOfNotNull(setupType.takeIf { it != "NO_CLEAR_SETUP" }, reg.name).distinct()) }
    }

    /** Walk-forward validation: every decision sees only the candles before it. */
    fun backtest(c: List<Candle>): Pair<Int, Int> {
        if (c.size < 180) return 0 to 0
        var wins = 0; var total = 0
        var i = 120
        while (i < c.size - 10) {
            val prefix = c.subList(0, i)
            val f = runCatching { analyzeInternal(prefix, null) }.getOrNull()
            if (f != null && f.signal != "NO TRADE") {
                val dir = if (f.signal == "LONG") 1 else -1
                val horizon = c.subList(i, min(i+8,c.size))
                val hit = if (dir>0) horizon.any { it.high >= f.tp1 } else horizon.any { it.low <= f.tp1 }
                val stop = if (dir>0) horizon.any { it.low <= f.stop } else horizon.any { it.high >= f.stop }
                if (hit && !stop) wins++ else if (!hit && !stop) {
                    val end = horizon.last().close
                    if ((dir>0 && end>f.entry) || (dir<0 && end<f.entry)) wins++
                }
                total++
            }
            i += 8
        }
        return wins to total
    }
}
