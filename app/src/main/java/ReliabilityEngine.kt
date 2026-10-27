package com.marketforecast.prox

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One readable verdict on how far a forecast can be trusted, built from the model's own evidence. */
internal data class Reliability(
    val score: Int,
    val grade: String,
    /** GO = entry can be considered, WAIT = wait for confirmation, AVOID = do not enter. */
    val verdict: String,
    val pros: List<String>,
    val cons: List<String>,
    val btWins: Int,
    val btTotal: Int,
    /** Lower 90% bound of the walk-forward hit rate: what the instrument can be trusted for at minimum. */
    val btLow: Double
)

/**
 * Reliability = how many independent pieces of evidence agree: factor alignment, confirmations, reward/risk,
 * expected value, timeframe agreement, signal stability, calibration sample, historical edge and data quality,
 * corrected by the instrument's own walk-forward record (lower confidence bound, so a lucky streak on few
 * trades does not look like skill).
 */
internal object ReliabilityEngine {
    private val WEIGHTS = doubleArrayOf(0.20, 0.15, 0.12, 0.12, 0.12, 0.08, 0.09, 0.07, 0.05)

    fun gradeOf(score: Int): String = when {
        score >= 75 -> "A"
        score >= 60 -> "B"
        score >= 45 -> "C"
        else -> "D"
    }

    fun wilsonLower(wins: Int, total: Int, z: Double = 1.64): Double {
        if (total <= 0) return 0.0
        val p = wins.toDouble() / total
        val z2 = z * z
        val denom = 1.0 + z2 / total
        val centre = p + z2 / (2.0 * total)
        val margin = z * sqrt((p * (1.0 - p) + z2 / (4.0 * total)) / total)
        return ((centre - margin) / denom).coerceIn(0.0, 1.0)
    }

    private fun unit(v: Double): Double = if (v.isFinite()) v.coerceIn(0.0, 1.0) else 0.0
    private fun direction(f: Forecast): Double = if (f.signal.contains("SHORT", ignoreCase = true)) -1.0 else 1.0

    private fun agreement(f: Forecast): Double {
        val d = direction(f)
        val parts = doubleArrayOf(f.trend, f.momentum, f.levels, f.volume, f.advancedTechnical)
        var s = 0.0
        for (v in parts) {
            val x = v * d
            s += if (!x.isFinite()) 0.5 else if (x > 0.03) 1.0 else if (x < -0.03) 0.0 else 0.5
        }
        return s / parts.size
    }

    private fun components(f: Forecast, bars: Int): DoubleArray = doubleArrayOf(
        agreement(f),
        unit(f.confirmation / 5.0),
        unit((f.rr - 0.8) / 1.7),
        unit(f.expectedValueR / 0.25),
        unit(0.5 + 0.5 * unit(f.mtfAgreement) - 0.5 * unit(f.mtfConflict)),
        unit(f.signalStability),
        unit(f.calibrationQuality) * unit(f.sampleSize / 200.0),
        unit((f.historicalEdge - 0.45) / 0.25),
        unit(bars / 250.0) * unit(f.dataQuality / 100.0)
    )

    private fun penalty(f: Forecast): Double =
        (if (unit(f.impulseFatigue) > 0.6) 8.0 else 0.0) +
            (if (unit(f.srRiskPenalty) > 0.5) 6.0 else 0.0) +
            (if (f.liquidityScore > 0.0 && f.liquidityScore < 0.3) 6.0 else 0.0)

    /** Cheap numeric score (no backtest, no text): used for every instrument the scanner checks. */
    fun score(f: Forecast, bars: Int): Int {
        val x = components(f, bars)
        var s = 0.0
        for (i in x.indices) s += WEIGHTS[i] * x[i]
        return (100.0 * s - penalty(f)).roundToInt().coerceIn(0, 100)
    }

    fun assess(f: Forecast, bars: Int, backtest: Pair<Int, Int>?, ru: Boolean): Reliability {
        if (f.signal == "NO TRADE") {
            return Reliability(
                0, "–", "WAIT", emptyList(),
                listOf(if (ru) "Сейчас нет подтверждённого входа: модель советует ждать." else "No confirmed entry right now: the model advises to wait."),
                0, 0, 0.0
            )
        }
        val x = components(f, bars)
        var raw = 0.0
        for (i in x.indices) raw += WEIGHTS[i] * x[i]
        var score = 100.0 * raw - penalty(f)
        val wins = backtest?.first ?: 0
        val total = backtest?.second ?: 0
        val low = wilsonLower(wins, total)
        if (total >= 12) score += ((low - 0.5) * 40.0).coerceIn(-15.0, 15.0)
        val s = score.roundToInt().coerceIn(0, 100)

        val pros = ArrayList<String>()
        val cons = ArrayList<String>()
        fun judge(i: Int, proRu: String, proEn: String, conRu: String, conEn: String) {
            if (x[i] >= 0.7) pros += if (ru) proRu else proEn
            else if (x[i] <= 0.35) cons += if (ru) conRu else conEn
        }
        val rr = String.format(java.util.Locale.US, "%.1f", f.rr)
        val ev = String.format(java.util.Locale.US, "%.2f", f.expectedValueR)
        judge(0, "Тренд, импульс, уровни и объём указывают в одну сторону", "Trend, momentum, levels and volume point the same way",
            "Индикаторы расходятся: часть факторов против сигнала", "Indicators disagree: some factors are against the signal")
        judge(1, "Подтверждено ${f.confirmation} из 5 независимых групп индикаторов", "${f.confirmation} of 5 independent indicator groups confirm it",
            "Мало подтверждений: ${f.confirmation} из 5", "Few confirmations: ${f.confirmation} of 5")
        judge(2, "Выгодное соотношение риск/прибыль $rr", "Attractive reward/risk $rr",
            "Низкое соотношение риск/прибыль $rr", "Poor reward/risk $rr")
        judge(3, "Положительное математическое ожидание ${ev}R", "Positive expected value ${ev}R",
            "Математическое ожидание около нуля или отрицательное", "Expected value is near zero or negative")
        judge(4, "Старшие и младшие таймфреймы согласованы", "Higher and lower timeframes agree",
            "Таймфреймы противоречат друг другу", "Timeframes contradict each other")
        judge(5, "Сигнал устойчив и не меняется от свечи к свече", "The signal is stable from bar to bar",
            "Сигнал нестабилен и может быстро смениться", "The signal is unstable and may flip quickly")
        judge(6, "Вероятности откалиброваны на большой выборке (${f.sampleSize})", "Probabilities are calibrated on a large sample (${f.sampleSize})",
            "Мало данных для калибровки вероятностей (${f.sampleSize})", "Little data to calibrate probabilities (${f.sampleSize})")
        judge(7, "Исторически такие сигналы работали (${(f.historicalEdge * 100).roundToInt()}%)", "Such signals worked historically (${(f.historicalEdge * 100).roundToInt()}%)",
            "Исторического преимущества у сигнала нет", "No historical edge for this signal")
        judge(8, "Достаточно истории и данные хорошего качества", "Enough history and good data quality",
            "Короткая история или данные низкого качества", "Short history or low data quality")
        if (total >= 12) {
            val rate = wins * 100 / total
            val lowPct = (low * 100).roundToInt()
            if (low >= 0.55) pros += if (ru) "Backtest: $rate% верных сигналов на $total сделках, не ниже $lowPct% с запасом надёжности" else "Backtest: $rate% hit rate over $total trades, at least $lowPct% with a safety margin"
            else if (low < 0.45) cons += if (ru) "Backtest слабый: $rate% на $total сделках, нижняя граница $lowPct%" else "Weak backtest: $rate% over $total trades, lower bound $lowPct%"
        } else if (total > 0) {
            cons += if (ru) "Backtest: слишком мало сделок ($total), вывод ненадёжен" else "Backtest: too few trades ($total) to conclude"
        }
        if (unit(f.impulseFatigue) > 0.6) cons += if (ru) "Импульс выдыхается: риск отката" else "The impulse is fading: pullback risk"
        if (unit(f.srRiskPenalty) > 0.5) cons += if (ru) "Рядом сильный уровень против сделки" else "A strong level sits against the trade"
        if (f.liquidityScore > 0.0 && f.liquidityScore < 0.3) cons += if (ru) "Низкая ликвидность: возможно проскальзывание" else "Low liquidity: slippage possible"
        f.risks.filter { it.isNotBlank() }.take(2).forEach { if (it !in cons) cons += it }

        val verdict = when {
            f.expectedValueR <= 0.0 || f.rr < 1.0 -> "AVOID"
            s >= 60 && f.rr >= 1.5 -> "GO"
            s >= 45 -> "WAIT"
            else -> "AVOID"
        }
        return Reliability(s, gradeOf(s), verdict, pros.take(5), cons.take(5), wins, total, low)
    }
}
