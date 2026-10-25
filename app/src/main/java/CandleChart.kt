package com.marketforecast.prox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private class ChartLevel(val label: String, val price: Double, val color: Color, val dashed: Boolean)

private val SupportColor = Color(0xFF7FA6D6)
private val Ema20Color = Color(0xFF4DA3FF)
private val Ema50Color = Color(0xFFFFA502)

private fun chartLevels(f: Forecast?, ru: Boolean): List<ChartLevel> {
    if (f == null) return emptyList()
    val out = ArrayList<ChartLevel>()
    if (f.support1 > 0.0) out.add(ChartLevel(if (ru) "Поддержка" else "Support", f.support1, SupportColor, true))
    if (f.resistance1 > 0.0) out.add(ChartLevel(if (ru) "Сопротивл." else "Resistance", f.resistance1, SupportColor, true))
    if (f.signal == "LONG" || f.signal == "SHORT") {
        out.add(ChartLevel(if (ru) "Вход" else "Entry", f.entry, Accent, false))
        out.add(ChartLevel(if (ru) "Стоп" else "Stop", f.stop, Negative, false))
        out.add(ChartLevel("TP1", f.tp1, Positive, true))
        out.add(ChartLevel("TP2", f.tp2, Positive, true))
        out.add(ChartLevel("TP3", f.tp3, Positive, true))
    }
    return out.filter { it.price.isFinite() && it.price > 0.0 }
}

private fun emaSeries(candles: List<Candle>, period: Int): DoubleArray {
    val out = DoubleArray(candles.size)
    if (candles.isEmpty()) return out
    val k = 2.0 / (period + 1.0)
    var prev = candles[0].close
    for (i in candles.indices) {
        prev = if (i == 0) candles[0].close else candles[i].close * k + prev * (1.0 - k)
        out[i] = prev
    }
    return out
}

/** [start, end, visible] of the window: [end] is exclusive, [offset] counts bars scrolled back from the newest one. */
private fun chartWindow(n: Int, visibleF: Float, offsetF: Float): IntArray {
    val vis = visibleF.roundToInt().coerceIn(min(20, n), max(n, 1))
    val maxOffset = (n - vis).coerceAtLeast(0)
    val off = offsetF.roundToInt().coerceIn(0, maxOffset)
    val end = n - off
    val start = (end - vis).coerceAtLeast(0)
    return intArrayOf(start, end, end - start)
}

/** Chart card for the analysis screen: candles, volume, EMA 20 / 50 and the levels of the forecast. */
@Composable
internal fun PriceChartCard(candles: List<Candle>, f: Forecast?, timeframe: String, ru: Boolean) {
    GradientCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ru) "График и уровни" else "Chart and levels", fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(timeframe, fontSize = 12.sp, fontWeight = FontWeight.Black, color = Accent)
        }
        if (candles.size < 5) {
            Text(
                if (ru) "Для графика пока мало свечей" else "Not enough candles to draw the chart yet",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            CandleChart(candles, f, timeframe, ru, Modifier.fillMaxWidth().height(340.dp).padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("━ EMA 20", fontSize = 11.sp, color = Ema20Color, fontWeight = FontWeight.Bold)
                Text("  ━ EMA 50", fontSize = 11.sp, color = Ema50Color, fontWeight = FontWeight.Bold)
                Text(
                    if (ru) "  ▮ объём" else "  ▮ volume", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                if (ru) "Два пальца: масштаб. Свайп вбок: прокрутка. Касание: данные свечи."
                else "Two fingers: zoom. Swipe sideways: scroll. Tap: candle details.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun CandleChart(candles: List<Candle>, f: Forecast?, timeframe: String, ru: Boolean, modifier: Modifier) {
    val n = candles.size
    val nState = rememberUpdatedState(n)
    var visibleF by remember(timeframe) { mutableFloatStateOf(min(90, n).toFloat()) }
    var offsetF by remember(timeframe) { mutableFloatStateOf(0f) }
    var selected by remember(timeframe) { mutableStateOf<Int?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val axisPx = with(density) { 58.dp.toPx() }
    val timeBandPx = with(density) { 18.dp.toPx() }
    val ema20 = remember(candles) { emaSeries(candles, 20) }
    val ema50 = remember(candles) { emaSeries(candles, 50) }
    val levels = remember(f, ru) { chartLevels(f, ru) }
    val dateFormat = remember(timeframe) {
        SimpleDateFormat(if (timeframe == "1D" || timeframe == "1W") "dd.MM.yy" else "dd.MM HH:mm", Locale.getDefault())
    }
    val active = f != null && (f.signal == "LONG" || f.signal == "SHORT")
    val signalTone = if (f?.signal == "SHORT") Negative else Positive

    Column(modifier) {
        val picked = selected?.takeIf { it in candles.indices }?.let { candles[it] }
        Text(
            if (picked != null) {
                dateFormat.format(Date(picked.time)) + "  O " + fmt(picked.open) + "  H " + fmt(picked.high) + "  L " + fmt(picked.low) + "  C " + fmt(picked.close)
            } else if (ru) "Касание: данные свечи" else "Tap: candle details",
            fontSize = 11.sp,
            color = if (picked != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (picked != null) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.height(18.dp)
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                // Pan and pinch. A vertical drag is left alone so the page keeps scrolling.
                .pointerInput(timeframe) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        var totalX = 0f
                        var totalY = 0f
                        var mode = 0 // 0 undecided, 1 chart gesture, 2 vertical scroll of the page
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            val count = nState.value
                            val plotW = (size.width - axisPx).coerceAtLeast(1f)
                            if (pressed.size >= 2) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val newVisible = (visibleF / zoom).coerceIn(min(20, count).toFloat(), max(count, 1).toFloat())
                                visibleF = newVisible
                                offsetF = (offsetF + pan.x / (plotW / newVisible)).coerceIn(0f, (count - newVisible).coerceAtLeast(0f))
                                event.changes.forEach { it.consume() }
                                mode = 1
                            } else if (pressed.size == 1) {
                                val change = pressed[0]
                                val dx = change.position.x - change.previousPosition.x
                                val dy = change.position.y - change.previousPosition.y
                                if (mode == 0) {
                                    totalX += dx
                                    totalY += dy
                                    if (abs(totalX) > slop || abs(totalY) > slop) mode = if (abs(totalX) > abs(totalY)) 1 else 2
                                }
                                if (mode == 1) {
                                    offsetF = (offsetF + dx / (plotW / visibleF)).coerceIn(0f, (count - visibleF).coerceAtLeast(0f))
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(timeframe) {
                    detectTapGestures(onTap = { pos ->
                        val count = nState.value
                        val w = chartWindow(count, visibleF, offsetF)
                        val plotW = (size.width - axisPx).coerceAtLeast(1f)
                        if (pos.x <= plotW) {
                            val idx = (w[0] + (pos.x / (plotW / w[2])).toInt()).coerceIn(0, count - 1)
                            selected = if (selected == idx) null else idx
                        }
                    })
                }
        ) {
            val plotW = size.width - axisPx
            val plotH = size.height - timeBandPx
            val priceH = plotH * 0.84f
            val win = chartWindow(n, visibleF, offsetF)
            val start = win[0]
            val end = win[1]
            val vis = win[2]
            if (vis <= 0 || plotW <= 0f) return@Canvas
            val barW = plotW / vis

            // price range of what is on screen, widened only by levels that are reasonably close
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            var maxVol = 0.0
            for (i in start until end) {
                val c = candles[i]
                if (c.low < lo) lo = c.low
                if (c.high > hi) hi = c.high
                if (c.volume > maxVol) maxVol = c.volume
            }
            if (hi <= lo) hi = lo + max(abs(lo) * 0.001, 1e-6)
            val span = hi - lo
            var scaleLo = lo
            var scaleHi = hi
            val extLo = lo - span * 1.2
            val extHi = hi + span * 1.2
            for (lv in levels) if (lv.price in extLo..extHi) {
                if (lv.price < scaleLo) scaleLo = lv.price
                if (lv.price > scaleHi) scaleHi = lv.price
            }
            val pad = (scaleHi - scaleLo) * 0.06
            scaleLo -= pad
            scaleHi += pad
            val scale = scaleHi - scaleLo
            fun yOf(p: Double): Float = (((scaleHi - p) / scale) * priceH).toFloat()
            fun xOf(i: Int): Float = (i - start + 0.5f) * barW

            val labelStyle = TextStyle(fontSize = 10.sp, color = Color(0xFF8FA7B8))
            val tagStyle = TextStyle(fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)

            // grid and price axis
            for (g in 0..4) {
                val p = scaleLo + scale * g / 4.0
                val y = yOf(p)
                drawLine(Color(0x22FFFFFF), Offset(0f, y), Offset(plotW, y), strokeWidth = 1f)
                drawText(textMeasurer, fmt(p), Offset(plotW + 6f, (y - 7.dp.toPx()).coerceIn(0f, priceH - 12.dp.toPx())), labelStyle)
            }

            // entry zone
            if (active && f != null && f.entryZoneLow > 0.0 && f.entryZoneHigh > f.entryZoneLow) {
                val top = yOf(f.entryZoneHigh).coerceIn(0f, priceH)
                val bottom = yOf(f.entryZoneLow).coerceIn(0f, priceH)
                if (bottom > top) drawRect(signalTone.copy(alpha = 0.10f), Offset(0f, top), Size(plotW, bottom - top))
            }

            // volume band under the price area
            if (maxVol > 0.0) {
                val bandTop = priceH + 4.dp.toPx()
                val bandH = plotH - bandTop
                for (i in start until end) {
                    val c = candles[i]
                    val h = (c.volume / maxVol * bandH).toFloat()
                    val tone = if (c.close >= c.open) Positive else Negative
                    drawRect(tone.copy(alpha = 0.28f), Offset(xOf(i) - barW * 0.34f, plotH - h), Size(barW * 0.68f, h))
                }
            }

            // level lines first, so candles stay readable on top
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
            val tags = ArrayList<Triple<Float, String, Color>>()
            for (lv in levels) {
                val inScale = lv.price >= scaleLo && lv.price <= scaleHi
                val y = if (inScale) yOf(lv.price) else if (lv.price > scaleHi) 2.dp.toPx() else priceH - 2.dp.toPx()
                val tone = lv.color.copy(alpha = if (inScale) 0.9f else 0.35f)
                drawLine(tone, Offset(0f, y), Offset(plotW, y), strokeWidth = 1.4f, pathEffect = if (lv.dashed) dash else null)
                val mark = if (inScale) "" else if (lv.price > scaleHi) " ▲" else " ▼"
                tags.add(Triple(y, lv.label + " " + fmt(lv.price) + mark, lv.color))
            }

            // candles
            for (i in start until end) {
                val c = candles[i]
                val tone = if (c.close >= c.open) Positive else Negative
                val x = xOf(i)
                drawLine(tone, Offset(x, yOf(c.high)), Offset(x, yOf(c.low)), strokeWidth = max(1f, barW * 0.12f), cap = StrokeCap.Round)
                val top = yOf(max(c.open, c.close))
                val bottom = yOf(min(c.open, c.close))
                drawRect(tone, Offset(x - barW * 0.34f, top), Size(barW * 0.68f, max(bottom - top, 1.5f)))
            }

            // moving averages
            fun drawEma(series: DoubleArray, tone: Color) {
                val path = Path()
                var started = false
                for (i in start until end) {
                    val x = xOf(i)
                    val y = yOf(series[i])
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                if (started) drawPath(path, tone, style = Stroke(width = 2f, cap = StrokeCap.Round))
            }
            drawEma(ema20, Ema20Color.copy(alpha = 0.9f))
            drawEma(ema50, Ema50Color.copy(alpha = 0.85f))

            // last price
            val lastIdx = end - 1
            if (lastIdx in candles.indices && end == n) {
                val last = candles[lastIdx]
                val tone = if (last.close >= last.open) Positive else Negative
                val y = yOf(last.close)
                drawLine(tone.copy(alpha = 0.7f), Offset(0f, y), Offset(plotW, y), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
                tags.add(Triple(y, fmt(last.close), tone))
            }

            // tags on the right axis, pushed apart so they never overlap
            val tagH = 15.dp.toPx()
            var prevBottom = -1000f
            for ((rawY, text, tone) in tags.sortedBy { it.first }) {
                val top = max(rawY - tagH / 2f, prevBottom + 1f).coerceAtMost(priceH - tagH)
                val width = textMeasurer.measure(text, tagStyle).size.width.toFloat() + 8.dp.toPx()
                val left = (size.width - width).coerceAtLeast(plotW * 0.45f)
                drawRoundRect(tone.copy(alpha = 0.92f), Offset(left, top), Size(width, tagH), CornerRadius(5.dp.toPx(), 5.dp.toPx()))
                drawText(textMeasurer, text, Offset(left + 4.dp.toPx(), top + 1.dp.toPx()), tagStyle)
                prevBottom = top + tagH
            }

            // crosshair of the tapped candle
            val pick = selected
            if (pick != null && pick in start until end) {
                val c = candles[pick]
                val x = xOf(pick)
                val y = yOf(c.close)
                drawLine(Color(0x88FFFFFF), Offset(x, 0f), Offset(x, plotH), strokeWidth = 1f)
                drawLine(Color(0x88FFFFFF), Offset(0f, y), Offset(plotW, y), strokeWidth = 1f)
            }

            // time axis
            val ticks = if (vis >= 4) 4 else 1
            for (k in 0 until ticks) {
                val idx = (start + (vis - 1) * k / max(ticks - 1, 1)).coerceIn(0, n - 1)
                val text = dateFormat.format(Date(candles[idx].time))
                val w = textMeasurer.measure(text, labelStyle).size.width.toFloat()
                val x = (xOf(idx) - w / 2f).coerceIn(0f, max(plotW - w, 0f))
                drawText(textMeasurer, text, Offset(x, plotH + 2.dp.toPx()), labelStyle)
            }
        }
    }
}
