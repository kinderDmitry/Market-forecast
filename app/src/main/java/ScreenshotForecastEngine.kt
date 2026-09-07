package com.marketforecast.prox

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Visual pre-analysis of a chart screenshot. This is deliberately conservative:
 * an image alone cannot guarantee the next tick/seconds. When the chart signal is
 * ambiguous or the image quality is poor, the engine returns NO TRADE.
 */
data class ScreenshotForecast(
    val direction: String,
    val horizonSeconds: Long,
    val confidence: Int,
    val quality: Int,
    val momentum: Double,
    val trend: Double,
    val volatility: Double,
    val candleBias: Double,
    val explanation: List<String>,
    val suggestedHorizonSeconds: Long = horizonSeconds
)

object ScreenshotForecastEngine {
    private fun autoHorizonSeconds(unit: String, trend: Double, momentum: Double, volatility: Double, quality: Int): Long {
        val strength = (abs(trend) * 0.45 + abs(momentum) * 0.35 + min(1.0, abs(volatility)) * 0.20).coerceIn(0.0, 1.0)
        val qualityFactor = (quality / 100.0).coerceIn(0.35, 1.0)
        val raw = when (unit) {
            "SEC" -> 6.0 + 54.0 * strength * qualityFactor
            "MIN" -> 2.0 + 58.0 * strength * qualityFactor
            "HOUR" -> 1.0 + 23.0 * strength * qualityFactor
            "DAY" -> 1.0 + 13.0 * strength * qualityFactor
            else -> 0.0
        }
        if (unit == "AUTO") {
            // Screenshot mode has no user-selected unit. Pick the scale from
            // persistence strength: micro-momentum -> seconds, sustained impulse
            // -> minutes/hours, very persistent structure -> days.
            return when {
                strength < 0.22 -> (4.0 + 18.0 * strength * qualityFactor).roundToInt().coerceIn(3, 15).toLong()
                strength < 0.42 -> (15.0 + 90.0 * strength * qualityFactor).roundToInt().coerceIn(15, 120).toLong()
                strength < 0.65 -> ((2.0 + 10.0 * strength * qualityFactor) * 60.0).roundToInt().coerceIn(120, 900).toLong()
                strength < 0.84 -> ((15.0 + 105.0 * strength * qualityFactor) * 60.0).roundToInt().coerceIn(900, 7200).toLong()
                else -> ((4.0 + 20.0 * strength * qualityFactor) * 3600.0).roundToInt().coerceIn(14400, 86400).toLong()
            }
        }
        return when (unit) {
            "SEC" -> raw.roundToInt().coerceIn(3, 60).toLong()
            "MIN" -> raw.roundToInt().coerceIn(1, 60).toLong() * 60L
            "HOUR" -> raw.roundToInt().coerceIn(1, 24).toLong() * 3600L
            else -> raw.roundToInt().coerceIn(1, 14).toLong() * 86400L
        }
    }

    fun analyze(bitmap: Bitmap, unit: String): ScreenshotForecast {
        // Unit is chosen by the user; the amount is estimated from visual persistence.
        return analyzeInternal(bitmap, unit.uppercase(), null)
    }

    fun analyze(bitmap: Bitmap, horizonSeconds: Long): ScreenshotForecast =
        analyzeInternal(bitmap, "AUTO", horizonSeconds)

    private fun analyzeInternal(bitmap: Bitmap, unit: String, fixedHorizonSeconds: Long?): ScreenshotForecast {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 120 || h < 120) return ScreenshotForecast("NO TRADE", (fixedHorizonSeconds ?: 60L), 0, 10, 0.0, 0.0, 0.0, 0.0, listOf("Изображение слишком маленькое для анализа."))

        // Ignore the outer UI/axis areas and inspect the central chart body.
        val left = (w * 0.10).toInt().coerceAtLeast(1)
        val right = (w * 0.94).toInt().coerceAtMost(w - 1)
        val top = (h * 0.08).toInt().coerceAtLeast(1)
        val bottom = (h * 0.88).toInt().coerceAtMost(h - 1)
        val bins = 64
        val series = DoubleArray(bins)
        val contrast = DoubleArray(bins)
        for (b in 0 until bins) {
            val x0 = left + (right - left) * b / bins
            val x1 = left + (right - left) * (b + 1) / bins
            var sum = 0.0
            var sum2 = 0.0
            var n = 0
            for (x in x0 until max(x0 + 1, x1)) {
                for (y in top until bottom step max(1, (bottom - top) / 48)) {
                    val p = bitmap.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
                    val lum = (0.2126 * ((p shr 16) and 255) + 0.7152 * ((p shr 8) and 255) + 0.0722 * (p and 255)) / 255.0
                    sum += lum; sum2 += lum * lum; n++
                }
            }
            val mean = if (n == 0) 0.0 else sum / n
            series[b] = mean
            contrast[b] = if (n == 0) 0.0 else max(0.0, sum2 / n - mean * mean)
        }

        fun avg(from: Int, to: Int): Double = series.copyOfRange(from.coerceAtLeast(0), to.coerceAtMost(bins)).average()
        val recent = avg(48, 64)
        val previous = avg(32, 48)
        val older = avg(16, 32)
        val trend = ((recent - older) * 8.0).coerceIn(-1.0, 1.0)
        val momentum = ((recent - previous) * 14.0 + (previous - older) * 7.0).coerceIn(-1.0, 1.0)
        val recentContrast = contrast.copyOfRange(48, 64).average()
        val oldContrast = contrast.copyOfRange(16, 32).average().coerceAtLeast(1e-6)
        val volatility = (recentContrast / oldContrast - 1.0).coerceIn(-1.0, 2.0)

        // Candle-color proxy: compare red/green channel dominance in the rightmost area.
        var green = 0.0; var red = 0.0; var samples = 0
        for (x in (right * 0.72).toInt() until right step max(1, w / 180)) {
            for (y in top until bottom step max(1, h / 100)) {
                val p = bitmap.getPixel(x, y)
                val r = ((p shr 16) and 255).toDouble()
                val g = ((p shr 8) and 255).toDouble()
                val b = (p and 255).toDouble()
                val chroma = max(r, max(g, b)) - min(r, min(g, b))
                if (chroma > 45) {
                    if (g > r * 1.10 && g > b * 1.05) green += 1.0
                    if (r > g * 1.10 && r > b * 1.05) red += 1.0
                    samples++
                }
            }
        }
        val candleBias = if (samples == 0) 0.0 else ((green - red) / samples).coerceIn(-1.0, 1.0)
        // Multi-window visual vote. The shortest window captures immediate momentum,
        // while longer windows reduce noise from a single candle/color artifact.
        val micro = ((recent - previous) * 18.0 + candleBias * 0.55).coerceIn(-1.0, 1.0)
        val combined = trend * 0.34 + momentum * 0.30 + micro * 0.21 + candleBias * 0.15
        val quality = (35 + min(35, (recentContrast * 900).toInt()) + if (samples > 30) 15 else 0 + if (w >= 720) 10 else 0 + if (h >= 500) 5 else 0).coerceIn(10, 95)
        val magnitude = abs(combined)
        val confidence = (50 + magnitude * 38 + min(7.0, max(0.0, quality.toDouble() - 50.0) / 8.0)).toInt().coerceIn(50, 88)
        val direction = when {
            quality < 48 || magnitude < 0.16 -> "NO TRADE"
            combined > 0 -> "LONG"
            combined < 0 -> "SHORT"
            else -> "NO TRADE"
        }
        val autoHorizon = if (fixedHorizonSeconds != null) fixedHorizonSeconds.coerceIn(1, 30L * 86400L)
        else autoHorizonSeconds(unit, trend, momentum, volatility, quality)
        val horizon = autoHorizon
        val notes = listOf(
            "Визуальный тренд: ${"%.2f".format(trend)}; импульс: ${"%.2f".format(momentum)}; краткосрочный импульс: ${"%.2f".format(micro)}.",
            "Баланс цветных свечей: ${"%+.2f".format(candleBias)}; изменение визуальной волатильности: ${"%+.0f%%".format(volatility * 100)}.",
            "Качество изображения/выделения графика: $quality/100.",
            if (direction == "NO TRADE") "Сигнал недостаточно устойчив — приложение не выдаёт направление." else "Сигнал прошёл визуальный фильтр; автоматически оцененный горизонт: ${formatHorizonLocal(horizon)}.",
            "Скриншот анализируется как визуальное подтверждение: направление, импульс, волатильность и свечные признаки. Будущее движение не гарантируется."
        )
        return ScreenshotForecast(direction, horizon, confidence, quality, momentum, trend, volatility, candleBias, notes, horizon)
    }
    private fun formatHorizonLocal(seconds: Long): String = when { seconds < 60 -> "$seconds сек"; seconds < 3600 -> "${seconds/60} мин"; seconds < 86400 -> "${seconds/3600} ч"; else -> "${seconds/86400} дн" }
}
