package com.marketforecast.prox

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Visual pre-analysis of a chart screenshot. This is deliberately conservative:
 * an image alone cannot guarantee the next tick/seconds. When the chart signal is
 * ambiguous or the image quality is poor, the engine returns NO TRADE.
 */
data class ScreenshotForecast(
    val direction: String,
    val horizonSeconds: Int,
    val confidence: Int,
    val quality: Int,
    val momentum: Double,
    val trend: Double,
    val volatility: Double,
    val candleBias: Double,
    val explanation: List<String>
)

object ScreenshotForecastEngine {
    fun analyze(bitmap: Bitmap, horizonSeconds: Int): ScreenshotForecast {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 120 || h < 120) return ScreenshotForecast("NO TRADE", horizonSeconds, 0, 10, 0.0, 0.0, 0.0, 0.0, listOf("Изображение слишком маленькое для анализа."))

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
        val combined = trend * 0.42 + momentum * 0.38 + candleBias * 0.20
        val quality = (35 + min(35, (recentContrast * 900).toInt()) + if (samples > 30) 15 else 0 + if (w >= 720) 10 else 0 + if (h >= 500) 5 else 0).coerceIn(10, 95)
        val magnitude = abs(combined)
        val confidence = (50 + magnitude * 38 + min(7.0, max(0.0, quality.toDouble() - 50.0) / 8.0)).toInt().coerceIn(50, 88)
        val direction = when {
            quality < 48 || magnitude < 0.16 -> "NO TRADE"
            combined > 0 -> "LONG"
            combined < 0 -> "SHORT"
            else -> "NO TRADE"
        }
        val horizon = horizonSeconds.coerceIn(5, 900)
        val notes = listOf(
            "Визуальный тренд: ${"%.2f".format(trend)}; импульс: ${"%.2f".format(momentum)}.",
            "Баланс цветных свечей: ${"%+.2f".format(candleBias)}; изменение визуальной волатильности: ${"%+.0f%%".format(volatility * 100)}.",
            "Качество изображения/выделения графика: $quality/100.",
            if (direction == "NO TRADE") "Сигнал недостаточно устойчив — приложение не выдаёт направление." else "Сигнал прошёл консервативный визуальный фильтр для горизонта $horizon сек.",
            "Это вероятностный анализ изображения, а не гарантия движения цены: скриншот не содержит будущих данных."
        )
        return ScreenshotForecast(direction, horizon, confidence, quality, momentum, trend, volatility, candleBias, notes)
    }
}
