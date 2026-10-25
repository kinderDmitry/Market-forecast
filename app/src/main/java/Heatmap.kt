package com.marketforecast.prox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** One instrument on the market map. [change] is in percent against the previous close. */
internal class HeatTile(
    val symbol: String,
    val name: String,
    val last: Double,
    val change: Double,
    /** Market capitalisation (0 when unknown). */
    val cap: Double,
    /** Turnover of the current session. */
    val turnover: Double
)

internal class HeatRect(val tile: HeatTile, val x: Float, val y: Float, val w: Float, val h: Float)

/** Squarified treemap (Bruls, Huizing, van Wijk): tile area is proportional to the weight, tiles stay close to squares. */
internal object Treemap {
    fun layout(tiles: List<HeatTile>, weightOf: (HeatTile) -> Double, width: Float, height: Float): List<HeatRect> {
        val items = tiles
            .map { it to weightOf(it) }
            .filter { it.second > 0.0 && it.second.isFinite() }
            .sortedByDescending { it.second }
        if (items.isEmpty() || width <= 0f || height <= 0f) return emptyList()
        val total = items.sumOf { it.second }
        val scale = width.toDouble() * height.toDouble() / total
        val areas = DoubleArray(items.size) { items[it].second * scale }
        val out = ArrayList<HeatRect>(items.size)
        var rx = 0.0
        var ry = 0.0
        var rw = width.toDouble()
        var rh = height.toDouble()
        var i = 0
        while (i < areas.size) {
            val side = min(rw, rh)
            var j = i
            var sum = 0.0
            var rowMax = 0.0
            var rowMin = Double.MAX_VALUE
            var worst = Double.MAX_VALUE
            // grow the row while the worst aspect ratio keeps improving
            while (j < areas.size) {
                val a = areas[j]
                val newSum = sum + a
                val newMax = max(rowMax, a)
                val newMin = min(rowMin, a)
                val newWorst = max(side * side * newMax / (newSum * newSum), (newSum * newSum) / (side * side * newMin))
                if (j > i && newWorst > worst) break
                sum = newSum
                rowMax = newMax
                rowMin = newMin
                worst = newWorst
                j += 1
            }
            if (rw >= rh) {
                val colW = sum / rh
                var cy = ry
                for (k in i until j) {
                    val hh = areas[k] / colW
                    out.add(HeatRect(items[k].first, rx.toFloat(), cy.toFloat(), colW.toFloat(), hh.toFloat()))
                    cy += hh
                }
                rx += colW
                rw -= colW
            } else {
                val rowH = sum / rw
                var cx = rx
                for (k in i until j) {
                    val ww = areas[k] / rowH
                    out.add(HeatRect(items[k].first, cx.toFloat(), ry.toFloat(), ww.toFloat(), rowH.toFloat()))
                    cx += ww
                }
                ry += rowH
                rh -= rowH
            }
            i = j
        }
        return out.filter { it.x.isFinite() && it.y.isFinite() && it.w.isFinite() && it.h.isFinite() }
    }
}

private val NeutralTile = Color(0xFF1C2635)

/** Grey at 0%, full red / green at +-3%. The palette is the app's own: Negative and Positive. */
internal fun heatColor(change: Double): Color {
    val t = (change / 3.0).coerceIn(-1.0, 1.0).toFloat()
    return if (t >= 0f) lerp(NeutralTile, Positive, t * 0.92f) else lerp(NeutralTile, Negative, -t * 0.92f)
}

private fun heatTextColor(change: Double): Color = if (change >= 1.8) Color(0xFF04140D) else Color.White

private fun heatWeight(t: HeatTile, sizeBy: String, currency: Boolean): Double =
    if (currency || sizeBy != "CAP") t.turnover else if (t.cap > 0.0) t.cap else t.turnover

private fun signedPct(v: Double): String = String.format(Locale.US, "%+.2f%%", v)

/** Home screen entry. */
@Composable
internal fun HeatmapEntryCard(ru: Boolean, onOpen: () -> Unit) {
    GradientCard(Modifier.fillMaxWidth().tapCard(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GridView, null, tint = Accent, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(if (ru) "Карта рынка" else "Market map", fontWeight = FontWeight.Black, fontSize = 15.sp)
                Text(
                    if (ru) "Что растёт и что падает сейчас: цвет — изменение за день, размер — капитализация"
                    else "What is rising and falling now: colour is the day change, size is the market cap",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun HeatmapScreen(repo: MarketRepository, favorites: Set<String>, ru: Boolean, onOpen: (String) -> Unit) {
    var kind by remember { mutableStateOf("SHARES") } // SHARES, FAV, FX
    var sizeBy by remember { mutableStateOf("CAP") } // CAP, TURNOVER
    var tiles by remember { mutableStateOf<List<HeatTile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var updated by remember { mutableLongStateOf(0L) }
    val currency = kind == "FX"

    // One request returns the whole board, so the map can refresh every 30 seconds without a rate-limit worry.
    LaunchedEffect(currency) {
        tiles = emptyList()
        loading = true
        error = null
        while (true) {
            val result = withContext(Dispatchers.IO) { runCatching { repo.heatmapSnapshot(currency) } }
            result
                .onSuccess { tiles = it; error = null; updated = System.currentTimeMillis() }
                .onFailure { error = it.message ?: (if (ru) "Не удалось загрузить данные" else "Could not load the data") }
            loading = false
            delay(30_000L)
        }
    }

    val shown = remember(tiles, kind, favorites) {
        if (kind == "FAV") tiles.filter { t -> favorites.any { it.equals(t.symbol, ignoreCase = true) } } else tiles
    }
    val gainers = remember(shown) { shown.sortedByDescending { it.change }.take(5) }
    val losers = remember(shown) { shown.sortedBy { it.change }.take(5) }
    val up = shown.count { it.change > 0.0 }
    val down = shown.count { it.change < 0.0 }
    val totalWeight = shown.sumOf { heatWeight(it, sizeBy, currency) }
    val average = if (totalWeight > 0.0) shown.sumOf { it.change * heatWeight(it, sizeBy, currency) } / totalWeight else 0.0

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
        contentPadding = PaddingValues(bottom = 30.dp)
    ) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("SHARES" to (if (ru) "Акции" else "Stocks"), "FAV" to (if (ru) "Избранное" else "Favorites"), "FX" to (if (ru) "Валюты" else "Currencies")).forEach { (key, label) ->
                    FilterChip(selected = kind == key, onClick = { kind = key }, label = { Text(label, fontSize = 12.sp) })
                }
            }
        }
        if (!currency) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (ru) "Размер плитки:" else "Tile size:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, end = 4.dp))
                    FilterChip(selected = sizeBy == "CAP", onClick = { sizeBy = "CAP" }, label = { Text(if (ru) "Капитализация" else "Market cap", fontSize = 12.sp) })
                    FilterChip(selected = sizeBy == "TURNOVER", onClick = { sizeBy = "TURNOVER" }, label = { Text(if (ru) "Оборот за день" else "Day turnover", fontSize = 12.sp) })
                }
            }
        }
        item {
            GradientCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (ru) "Настроение рынка" else "Market mood", fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(signedPct(average), fontWeight = FontWeight.Black, fontSize = 18.sp, color = if (average >= 0.0) Positive else Negative)
                }
                Text(
                    if (ru) "Растут: $up • падают: $down • всего: ${shown.size}" else "Rising: $up • falling: $down • total: ${shown.size}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                )
                if (updated > 0L) {
                    Text(
                        (if (ru) "Обновлено " else "Updated ") + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(updated)) +
                            (if (ru) " • данные Московской биржи (ISS), задержка до 15 минут" else " • Moscow Exchange (ISS) data, up to 15 minutes delayed"),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        item {
            when {
                loading && tiles.isEmpty() -> GradientCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(Accent, 8.dp)
                        Text(if (ru) "Загружаю карту рынка…" else "Loading the market map…", Modifier.padding(start = 10.dp), fontSize = 12.sp)
                    }
                    SkeletonLine(0.9f, 160.dp)
                }
                tiles.isEmpty() -> GradientCard(Modifier.fillMaxWidth()) {
                    Text(
                        (error ?: if (ru) "Нет данных" else "No data") + (if (ru) ". Проверьте сеть: карта берёт данные у Московской биржи." else ". Check the network: the map takes its data from the Moscow Exchange."),
                        fontSize = 12.sp, color = Negative
                    )
                }
                shown.isEmpty() -> GradientCard(Modifier.fillMaxWidth()) {
                    Text(if (ru) "В избранном пока нет акций Мосбиржи. Добавьте их через поиск." else "No Moscow Exchange stocks in your favorites yet. Add some through the search.", fontSize = 12.sp)
                }
                else -> HeatCanvas(shown, sizeBy, currency, onOpen)
            }
        }
        if (shown.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("−3%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(
                        Modifier.weight(1f).padding(horizontal = 8.dp).height(8.dp).clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(heatColor(-3.0), heatColor(0.0), heatColor(3.0))))
                    )
                    Text("+3%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionHeader(if (ru) "Лидеры роста" else "Top gainers", "") }
            items(gainers, key = { "g" + it.symbol }) { t -> HeatRow(t, ru, onOpen) }
            item { SectionHeader(if (ru) "Лидеры падения" else "Top losers", "") }
            items(losers, key = { "l" + it.symbol }) { t -> HeatRow(t, ru, onOpen) }
        }
    }
}

@Composable
private fun HeatRow(t: HeatTile, ru: Boolean, onOpen: (String) -> Unit) {
    val color = if (t.change >= 0.0) Positive else Negative
    GradientCard(Modifier.fillMaxWidth().tapCard { onOpen(t.symbol) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.symbol, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Text(t.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmt(t.last), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(signedPct(t.change), fontWeight = FontWeight.Black, fontSize = 13.sp, color = color)
            }
        }
    }
}

@Composable
private fun HeatCanvas(tiles: List<HeatTile>, sizeBy: String, currency: Boolean, onOpen: (String) -> Unit) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val textMeasurer = rememberTextMeasurer()
    val rects = remember(tiles, sizeBy, currency, boxSize) {
        Treemap.layout(tiles, { heatWeight(it, sizeBy, currency) }, boxSize.width.toFloat(), boxSize.height.toFloat())
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(460.dp)
            .clip(RoundedCornerShape(14.dp))
            .onSizeChanged { boxSize = it }
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(rects) {
                    detectTapGestures { pos ->
                        rects.firstOrNull { r -> pos.x >= r.x && pos.x <= r.x + r.w && pos.y >= r.y && pos.y <= r.y + r.h }
                            ?.let { onOpen(it.tile.symbol) }
                    }
                }
        ) {
            val gap = 1.5.dp.toPx()
            for (r in rects) {
                val w = r.w - gap
                val h = r.h - gap
                if (w <= 0f || h <= 0f) continue
                val left = r.x + gap / 2f
                val top = r.y + gap / 2f
                drawRoundRect(heatColor(r.tile.change), Offset(left, top), Size(w, h), CornerRadius(4.dp.toPx(), 4.dp.toPx()))
                val minSide = min(w, h)
                if (minSide < 24.dp.toPx() || w < 34.dp.toPx()) continue
                val fontSize = (minSide / 4.3f).coerceIn(9.dp.toPx(), 20.dp.toPx())
                val tone = heatTextColor(r.tile.change)
                val main = textMeasurer.measure(r.tile.symbol, TextStyle(fontSize = fontSize.toSp(), fontWeight = FontWeight.Black, color = tone))
                if (main.size.width > w - 2.dp.toPx()) continue
                val showChange = h >= main.size.height * 2.1f && w >= 44.dp.toPx()
                val second = if (showChange) {
                    textMeasurer.measure(signedPct(r.tile.change), TextStyle(fontSize = (fontSize * 0.78f).toSp(), fontWeight = FontWeight.Bold, color = tone))
                } else null
                val blockH = main.size.height + (second?.size?.height ?: 0)
                val ty = top + (h - blockH) / 2f
                drawText(main, topLeft = Offset(left + (w - main.size.width) / 2f, ty))
                if (second != null && second.size.width <= w - 2.dp.toPx()) {
                    drawText(second, topLeft = Offset(left + (w - second.size.width) / 2f, ty + main.size.height))
                }
            }
        }
    }
}
