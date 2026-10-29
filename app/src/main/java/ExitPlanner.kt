package com.marketforecast.prox

import android.content.SharedPreferences
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Chooses the stop distance and the HEIGHT of the targets from what this instrument really did after similar setups,
 * and measures every plan exactly the way it is traded:
 *  - first-passage over the next [horizon] candles, the stop wins a tie inside one candle;
 *  - one third of the position leaves at each of the three targets;
 *  - after TP1 the stop moves to break-even, after TP2 to TP1;
 *  - the round-trip cost is paid on every trade.
 *
 * Trending instruments therefore get higher targets (the ladder is scaled up), choppy ones get lower targets, and a setup
 * whose expected result is not positive after costs does not become a signal at all.
 */
internal object ExitPlanner {
    /** Ladder shape in units of risk: TP1 = 1R, TP2 = 2R, TP3 = 2.8R, all multiplied by the chosen scale. */
    const val K1 = 1.0
    const val K2 = 2.0
    const val K3 = 2.8

    /** Round-trip cost (commission + spread) as a share of the price. */
    const val COST_FRACTION = 0.0008
    const val MIN_SAMPLES = 30

    private val STOPS = doubleArrayOf(1.0, 1.25, 1.5, 1.8, 2.2)
    private val SCALES = doubleArrayOf(0.8, 1.0, 1.3, 1.7, 2.2)
    /** The "safer" style may also pull TP1 closer than the risk: reached more often, but each win is smaller. */
    private val SCALES_SAFE = doubleArrayOf(0.5, 0.65, 0.8, 1.0, 1.3, 1.7, 2.2)

    /** 0 safer (more trades reach the first target), 1 balanced (best expected result), 2 more profit per winning trade. */
    @Volatile var style: Int = 1
    private const val SHRINK = 15.0
    private const val MARGIN = 0.02

    class Sample(val index: Int, val price: Double, val atr: Double)

    /** [r] is the result of one trade in R (risk units), after costs. */
    class Result(val r: Double, val reached1: Boolean, val reached2: Boolean, val reached3: Boolean, val stoppedFirst: Boolean)

    data class Plan(
        val stopMult: Double,
        val scale: Double,
        val p1: Double,
        val p2: Double,
        val p3: Double,
        val pStop: Double,
        /** Average result per trade in R after costs, over [samples] similar situations. */
        val ev: Double,
        val samples: Int,
        /** True when the geometry was changed from the default because the history clearly preferred another one. */
        val optimized: Boolean,
        /** Standard deviation of the result of one trade (R). */
        val sd: Double = 0.0,
        /** Share of trades that ended in profit after costs. */
        val win: Double = 0.0
    )

    /** One trade on explicit price levels. [bars] are the candles AFTER the entry, in order. */
    fun simulateLevels(
        bars: List<Candle>, direction: Int, entry: Double, stop: Double, tp1: Double, tp2: Double, tp3: Double, costFraction: Double = currentCost()
    ): Result {
        val risk = kotlin.math.abs(entry - stop)
        if (!(risk > 0.0) || bars.isEmpty()) return Result(0.0, false, false, false, false)
        val r1 = kotlin.math.abs(tp1 - entry) / risk
        val r2 = kotlin.math.abs(tp2 - entry) / risk
        val r3 = kotlin.math.abs(tp3 - entry) / risk
        val cost = entry * costFraction / risk
        var state = 0
        var banked = 0.0
        var reached1 = false
        var reached2 = false
        var reached3 = false
        var last = entry
        for (bar in bars) {
            last = bar.close
            if (state == 0) {
                val stopHit = if (direction > 0) bar.low <= stop else bar.high >= stop
                val targetHit = if (direction > 0) bar.high >= tp1 else bar.low <= tp1
                if (stopHit) return Result(-1.0 - cost, false, false, false, true)
                if (targetHit) {
                    state = 1
                    banked = r1 / 3.0
                    reached1 = true
                }
            } else if (state == 1) {
                val stopHit = if (direction > 0) bar.low <= entry else bar.high >= entry
                val targetHit = if (direction > 0) bar.high >= tp2 else bar.low <= tp2
                if (stopHit) return Result(banked - cost, reached1, false, false, false)
                if (targetHit) {
                    state = 2
                    banked = (r1 + r2) / 3.0
                    reached2 = true
                }
            } else {
                val stopHit = if (direction > 0) bar.low <= tp1 else bar.high >= tp1
                val targetHit = if (direction > 0) bar.high >= tp3 else bar.low <= tp3
                if (stopHit) return Result((r1 + r2 + r1) / 3.0 - cost, reached1, reached2, false, false)
                if (targetHit) return Result((r1 + r2 + r3) / 3.0 - cost, reached1, reached2, true, false)
            }
        }
        // Not finished inside the horizon: what is left is valued at the last close.
        val open = (last - entry) * direction / risk
        val result = when (state) {
            0 -> open
            1 -> banked + (2.0 / 3.0) * open
            else -> banked + (1.0 / 3.0) * open
        }
        return Result(result - cost, reached1, reached2, reached3, false)
    }

    /** One trade of the geometry (stop distance = [stopMult] ATR, targets scaled by [scale]) from a historical sample. */
    private fun simulate(c: List<Candle>, s: Sample, direction: Int, stopMult: Double, scale: Double, horizon: Int): Result {
        val stopDistance = stopMult * s.atr
        val bars = c.subList(min(s.index + 1, c.size), min(c.size, s.index + 1 + horizon))
        return simulateLevels(
            bars, direction, s.price,
            s.price - direction * stopDistance,
            s.price + direction * scale * K1 * stopDistance,
            s.price + direction * scale * K2 * stopDistance,
            s.price + direction * scale * K3 * stopDistance
        )
    }

    private fun evaluate(c: List<Candle>, samples: List<Sample>, direction: Int, stopMult: Double, scale: Double, horizon: Int): Plan {
        var sum = 0.0
        var sumSq = 0.0
        var n1 = 0
        var n2 = 0
        var n3 = 0
        var stopped = 0
        var wins = 0
        for (s in samples) {
            val r = simulate(c, s, direction, stopMult, scale, horizon)
            sum += r.r
            sumSq += r.r * r.r
            if (r.reached1) n1 += 1
            if (r.reached2) n2 += 1
            if (r.reached3) n3 += 1
            if (r.stoppedFirst) stopped += 1
            if (r.r > 0.0) wins += 1
        }
        val n = max(samples.size, 1).toDouble()
        val mean = sum / n
        return Plan(stopMult, scale, n1 / n, n2 / n, n3 / n, stopped / n, mean, samples.size, false, sqrt(max(0.0, sumSq / n - mean * mean)), wins / n)
    }

    /**
     * The default geometry is kept unless another one is clearly better on the history (after shrinking the estimate
     * towards zero, because the best of 25 noisy estimates is always flattered).
     */
    fun choose(c: List<Candle>, samples: List<Sample>, direction: Int, defaultStop: Double, horizon: Int): Plan {
        val base = evaluate(c, samples, direction, defaultStop, 1.0, horizon)
        if (samples.size < MIN_SAMPLES) return base
        val shrink = samples.size / (samples.size + SHRINK)
        // The chosen style leans the choice: "safer" pays for a higher share of winning trades with some expected result.
        val lambda = when (style) { 0 -> 0.30; 2 -> -0.20; else -> 0.0 }
        val scales = if (style == 0) SCALES_SAFE else SCALES
        val baseScore = base.ev * shrink + lambda * base.win
        var best = base
        var bestScore = baseScore
        for (stop in STOPS) {
            for (scale in scales) {
                val plan = evaluate(c, samples, direction, stop, scale, horizon)
                val score = plan.ev * shrink + lambda * plan.win
                if (score > bestScore) {
                    best = plan
                    bestScore = score
                }
            }
        }
        return if (bestScore >= baseScore + MARGIN) best.copy(optimized = true) else base
    }

    private val costContext = ThreadLocal<Double>()

    /** Round-trip cost in force for the analysis running on this thread (stocks and currencies cost differently). */
    fun currentCost(): Double = costContext.get() ?: COST_FRACTION

    fun <T> withCost(fraction: Double, block: () -> T): T {
        val previous = costContext.get()
        costContext.set(fraction)
        try {
            return block()
        } finally {
            if (previous == null) costContext.remove() else costContext.set(previous)
        }
    }

    /**
     * The same plan played on a RANDOM WALK with this instrument's volatility: real candle shapes (range, wicks, body) in
     * units of their own ATR, drawn at random with the drift removed. It answers "what would this plan earn if the past
     * had no memory at all", so a plan is only credited with the edge it has beyond that.
     */
    fun nullPlan(c: List<Candle>, direction: Int, stopMult: Double, scale: Double, atrNow: Double, horizon: Int, paths: Int = 300): Plan {
        val empty = Plan(stopMult, scale, 0.0, 0.0, 0.0, 0.0, 0.0, 0, false)
        val window = min(c.size - 1, 300)
        if (window < 40 || !(atrNow > 0.0)) return empty
        val highs = DoubleArray(window)
        val lows = DoubleArray(window)
        val closes = DoubleArray(window)
        var k = 0
        for (j in c.size - window until c.size) {
            if (j < 15) continue
            var rangeSum = 0.0
            for (q in j - 14 until j) rangeSum += c[q].high - c[q].low
            val a = rangeSum / 14.0
            if (!(a > 0.0)) continue
            highs[k] = (c[j].high - c[j].open) / a
            lows[k] = (c[j].low - c[j].open) / a
            closes[k] = (c[j].close - c[j].open) / a
            k += 1
        }
        if (k < 30) return empty
        var drift = 0.0
        for (i in 0 until k) drift += closes[i]
        drift /= k
        val random = java.util.Random(c.size * 31L + direction)
        val start = c.last().close
        val risk = stopMult * atrNow
        val stop = start - direction * risk
        val t1 = start + direction * scale * K1 * risk
        val t2 = start + direction * scale * K2 * risk
        val t3 = start + direction * scale * K3 * risk
        val cost = currentCost()
        var sum = 0.0
        var sumSq = 0.0
        var n1 = 0
        var n2 = 0
        var n3 = 0
        var stopped = 0
        repeat(paths) {
            var price = start
            val bars = ArrayList<Candle>(horizon)
            repeat(horizon) {
                val i = random.nextInt(k)
                val open = price
                val close = open + (closes[i] - drift) * atrNow
                val high = max(open + (highs[i] - drift) * atrNow, max(open, close))
                val low = min(open + (lows[i] - drift) * atrNow, min(open, close))
                bars.add(Candle(0L, open, high, low, close, 0.0))
                price = close
            }
            val r = simulateLevels(bars, direction, start, stop, t1, t2, t3, cost)
            sum += r.r
            sumSq += r.r * r.r
            if (r.reached1) n1 += 1
            if (r.reached2) n2 += 1
            if (r.reached3) n3 += 1
            if (r.stoppedFirst) stopped += 1
        }
        val n = paths.toDouble()
        val mean = sum / n
        return Plan(stopMult, scale, n1 / n, n2 / n, n3 / n, stopped / n, mean, paths, false, sqrt(max(0.0, sumSq / n - mean * mean)))
    }

    /**
     * Expected result of the plan in R from the probabilities of reaching each target before the stop.
     * Matches [simulateLevels]: a third at each target, break-even after TP1, stop to TP1 after TP2.
     */
    fun expectedValue(p1: Double, p2: Double, p3: Double, pStop: Double, r1: Double, r2: Double, r3: Double, costR: Double): Double {
        return -pStop + (p1 - p2) * (r1 / 3.0) + (p2 - p3) * ((2.0 * r1 + r2) / 3.0) + p3 * ((r1 + r2 + r3) / 3.0) - costR
    }
}

/** Round-trip trading cost as a share of the price, separately for shares and currency pairs; editable in Settings. */
internal object CostModel {
    @Volatile var stock: Double = 0.0008
    @Volatile var fx: Double = 0.0004

    fun fraction(isFx: Boolean): Double = if (isFx) fx else stock

    fun load(prefs: SharedPreferences) {
        stock = (prefs.getFloat("cost_stock_pct", 0.08f) / 100.0).coerceIn(0.0001, 0.01)
        fx = (prefs.getFloat("cost_fx_pct", 0.04f) / 100.0).coerceIn(0.0001, 0.01)
        ExitPlanner.style = prefs.getInt("plan_style", 1).coerceIn(0, 2)
    }
}
