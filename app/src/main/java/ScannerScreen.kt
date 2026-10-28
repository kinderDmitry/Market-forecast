package com.marketforecast.prox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Progress of the instrument catalogue download, shown in search and in the scanner. */
internal object CatalogStatus {
    var size by mutableIntStateOf(0)
    var syncing by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

private fun agoText(ms: Long, ru: Boolean): String {
    val sec = (System.currentTimeMillis() - ms) / 1000
    return when {
        sec < 60 -> if (ru) "только что" else "just now"
        sec < 3600 -> if (ru) "${sec / 60} мин назад" else "${sec / 60} min ago"
        sec < 86400 -> if (ru) "${sec / 3600} ч назад" else "${sec / 3600} h ago"
        else -> SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ms))
    }
}

private fun etaText(seconds: Long, ru: Boolean): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) (if (ru) "${m} мин ${s} с" else "${m} min ${s} s") else (if (ru) "${s} с" else "${s} s")
}

private fun scopeLabel(s: ScanScope, ru: Boolean): String = when (s) {
    ScanScope.MAIN -> if (ru) "Основные акции" else "Main boards"
    ScanScope.ALL -> if (ru) "Весь рынок" else "Whole market"
    ScanScope.RU_STOCKS -> if (ru) "Акции РФ" else "Russian stocks"
    ScanScope.FOREIGN -> if (ru) "Иностранные" else "Foreign"
    ScanScope.FX -> if (ru) "Валюты" else "Currencies"
    ScanScope.FAVORITES -> if (ru) "Избранное" else "Favorites"
}

@Composable
internal fun ScannerScreen(
    c: ScannerController,
    favorites: Set<String>,
    ru: Boolean,
    onOpen: (String, String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onStart: () -> Unit
) {
    var signalFilter by remember { mutableStateOf("ALL") }
    var onlyNew by remember { mutableStateOf(false) }
    var minConf by remember { mutableIntStateOf(0) }
    var goodOnly by remember { mutableStateOf(false) }
    var tradableOnly by remember { mutableStateOf(false) }
    var minAlign by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf("REL") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // One clock for the progress estimate and the countdowns; expired forecasts leave the list on their own.
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            c.pruneExpired(now)
            delay(if (c.running) 1000L else 15_000L)
        }
    }

    // Picking another horizon starts a fresh search for it when the last result of that horizon is stale.
    var firstHorizon by remember { mutableStateOf(true) }
    LaunchedEffect(c.horizon) {
        if (firstHorizon) { firstHorizon = false; return@LaunchedEffect }
        if (c.autoStart && !c.running) {
            c.restore()
            if (System.currentTimeMillis() - c.lastFinishedAt > c.refreshAfterMs()) onStart()
        }
    }

    val visible = remember(c.hits, c.horizon, signalFilter, onlyNew, goodOnly, tradableOnly, minAlign, minConf, sort) {
        val base = c.current
            .filter { signalFilter == "ALL" || it.signal.contains(signalFilter, ignoreCase = true) }
            .filter { !onlyNew || it.isNew }
            .filter { !goodOnly || it.reliability >= 60 }
            .filter { !tradableOnly || !it.watch }
            .filter { minAlign == 0 || it.alignedAgree >= minAlign }
            .filter { it.confidence >= minConf }
        when (sort) {
            "REL" -> base.sortedWith(compareByDescending<ScanHit> { it.reliability }.thenByDescending { it.confidence })
            "ALIGN" -> base.sortedWith(compareByDescending<ScanHit> { it.alignedAgree }.thenByDescending { it.reliability })
            "NEW" -> base.sortedWith(compareByDescending<ScanHit> { it.isNew }.thenByDescending { it.confidence })
            "CHANGE" -> base.sortedByDescending { abs(it.changePct) }
            "RR" -> base.sortedByDescending { it.rr }
            else -> base.sortedWith(compareByDescending<ScanHit> { it.confidence }.thenByDescending { abs(it.score) })
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp)
    ) {
        item {
            GradientCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoGraph, null, tint = Accent, modifier = Modifier.size(30.dp).pulse(c.running))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(if (ru) "Сканер прогнозов" else "Forecast scanner", fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Text(
                            if (CatalogStatus.size > 0)
                                (if (ru) "Каталог БКС: ${CatalogStatus.size} инструментов" else "BCS catalogue: ${CatalogStatus.size} instruments")
                            else (if (ru) "Каталог БКС загружается…" else "BCS catalogue is loading…"),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    if (ru) "Проверяет каждый инструмент выбранной области и показывает активные сигналы LONG и SHORT на выбранный срок. Прогноз действует ${c.horizon.label(true)} и затем исчезает из списка; новые прогнозы отмечаются сравнением с прошлым запуском."
                    else "Checks every instrument of the chosen scope and lists active LONG and SHORT signals for the chosen horizon. A forecast holds for ${c.horizon.label(false)} and then leaves the list; new forecasts are flagged against the previous run.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                )
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanScope.values().forEach { s ->
                        FilterChip(
                            selected = c.scope == s,
                            enabled = !c.running,
                            onClick = { c.updateScope(s) },
                            label = { Text(scopeLabel(s, ru), fontSize = 12.sp) }
                        )
                    }
                }
                Text(
                    if (ru) "На какой срок нужен прогноз" else "How long should the forecast hold",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp)
                )
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanHorizon.values().forEach { hz ->
                        FilterChip(
                            selected = c.horizon == hz,
                            enabled = !c.running,
                            onClick = { c.updateHorizon(hz) },
                            label = { Text(hz.label(ru), fontSize = 12.sp) }
                        )
                    }
                }
                if (c.running) {
                    val fraction = if (c.total > 0) c.done.toFloat() / c.total.toFloat() else 0f
                    GlowProgressBar(fraction, Modifier.fillMaxWidth().padding(top = 12.dp))
                    val elapsed = ((now - c.startedAt) / 1000).coerceAtLeast(1)
                    val eta = if (c.done >= 12 && c.total > c.done) (elapsed * (c.total - c.done) / c.done) else -1L
                    Text(
                        (if (ru) "Проверено ${c.done} из ${c.total} • сигналов: ${c.current.size}" else "Checked ${c.done} of ${c.total} • signals: ${c.current.size}") +
                            (if (eta >= 0) (if (ru) " • осталось ~${etaText(eta, ru)}" else " • ~${etaText(eta, ru)} left") else ""),
                        fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                    )
                    Button(onClick = { c.stop() }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (ru) "Остановить" else "Stop", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(14.dp)) {
                        Icon(if (c.lastFinishedAt > 0L) Icons.Default.Refresh else Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (c.lastFinishedAt > 0L) (if (ru) "Искать новые прогнозы" else "Look for new forecasts")
                            else (if (ru) "Запустить сканер" else "Start scanner"),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (c.lastFinishedAt > 0L) {
                        Text(
                            (if (ru) "Последний запуск: " else "Last run: ") + agoText(c.lastFinishedAt, ru),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (ru) "Искать новые прогнозы при открытии сканера" else "Look for new forecasts when the scanner opens",
                        fontSize = 12.sp, modifier = Modifier.weight(1f)
                    )
                    Switch(checked = c.autoStart, onCheckedChange = { c.changeAutoStart(it) })
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (ru) "Проверять совпадение сроков у найденных прогнозов" else "Check timeframe agreement for found forecasts",
                        fontSize = 12.sp, modifier = Modifier.weight(1f)
                    )
                    Switch(checked = c.confluence, enabled = !c.running, onCheckedChange = { c.changeConfluence(it) })
                }
            }
        }

        c.message?.let { text ->
            item {
                GradientCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { c.dismissMessage() }) { Icon(Icons.Default.Close, if (ru) "Закрыть" else "Dismiss") }
                    }
                }
            }
        }

        c.coverage?.takeIf { !c.running }?.let { cov ->
            item { CoverageCard(cov, c.horizon, ru) }
        }

        if (c.current.isNotEmpty()) {
            item {
                SectionHeader(
                    if (ru) "Результаты: ${visible.size} из ${c.current.size}" else "Results: ${visible.size} of ${c.current.size}",
                    if (ru) "Новых: ${c.newCount}" else "New: ${c.newCount}"
                )
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ALL", "LONG", "SHORT").forEach { f ->
                        FilterChip(
                            selected = signalFilter == f,
                            onClick = { signalFilter = f },
                            label = { Text(if (f == "ALL") (if (ru) "Все" else "All") else f, fontSize = 12.sp) }
                        )
                    }
                    FilterChip(
                        selected = onlyNew,
                        onClick = { onlyNew = !onlyNew },
                        label = { Text(if (ru) "Только новые" else "New only", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = goodOnly,
                        onClick = { goodOnly = !goodOnly },
                        label = { Text(if (ru) "Надёжность B и выше" else "Reliability B+", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = tradableOnly,
                        onClick = { tradableOnly = !tradableOnly },
                        label = { Text(if (ru) "Только с выгодой после издержек" else "Only profitable after costs", fontSize = 12.sp) }
                    )
                    if (c.confluence) {
                        listOf(0 to (if (ru) "Любое совпадение" else "Any agreement"), 2 to "2+", 3 to "3+", 4 to "4+", 5 to (if (ru) "Все 5" else "All 5")).forEach { (value, label) ->
                            FilterChip(
                                selected = minAlign == value,
                                onClick = { minAlign = value },
                                label = { Text(if (value == 0) label else (if (ru) "Сроки $label" else "Horizons $label"), fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "REL" to (if (ru) "По надёжности" else "Reliability"),
                        "ALIGN" to (if (ru) "По совпадению сроков" else "Timeframe agreement"),
                        "CONF" to (if (ru) "По уверенности" else "Confidence"),
                        "NEW" to (if (ru) "Сначала новые" else "New first"),
                        "CHANGE" to (if (ru) "По движению за день" else "Daily move"),
                        "RR" to "R:R"
                    ).forEach { (key, label) ->
                        FilterChip(selected = sort == key, onClick = { sort = key }, label = { Text(label, fontSize = 12.sp) })
                    }
                }
            }
            item {
                Column {
                    Text(
                        (if (ru) "Минимальная уверенность: " else "Minimum confidence: ") + "$minConf%",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(value = minConf.toFloat(), onValueChange = { minConf = it.toInt() }, valueRange = 0f..90f, steps = 8)
                }
            }
            if (visible.isEmpty()) {
                item { EmptyCard(if (ru) "По выбранным фильтрам ничего нет. Ослабьте фильтры." else "Nothing matches the filters. Relax them.") }
            }
            items(visible, key = { it.symbol }) { h ->
                HitCard(
                    h = h,
                    fav = favorites.any { it.equals(h.symbol, ignoreCase = true) },
                    ru = ru,
                    now = now,
                    onOpen = { onOpen(h.symbol, h.timeframe) },
                    onFav = { onToggleFavorite(h.symbol) }
                )
            }
            item {
                Text(
                    if (ru) "Прогноз строится по свечам выбранного срока и действует ровно этот срок, потом карточка исчезает. Это статистическая оценка, а не инвестиционная рекомендация. Откройте инструмент, чтобы увидеть полный анализ с живой ценой и проверкой надёжности."
                    else "A forecast is built on candles of the chosen horizon and holds for exactly that long, then the card disappears. It is a statistical estimate, not investment advice. Open an instrument for the full analysis with the live price and the reliability check.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else if (!c.running) {
            item {
                EmptyCard(
                    if (ru) "Пока нет результатов. Нажмите «Запустить сканер»: приложение проверит каталог БКС и покажет инструменты с активными прогнозами."
                    else "No results yet. Tap Start scanner to check the BCS catalogue for active forecasts."
                )
            }
        }
    }
}

@Composable
private fun HitCard(h: ScanHit, fav: Boolean, ru: Boolean, now: Long, onOpen: () -> Unit, onFav: () -> Unit) {
    val color = signalColor(h.signal)
    GradientCard(Modifier.fillMaxWidth().tapCard(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InstrumentIcon(h.symbol, h.name, h.classCode, 42.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fxPairLabel(h.symbol) ?: h.symbol.removeSuffix(".ME"), fontWeight = FontWeight.Black, fontSize = 16.sp)
                    if (h.isNew) {
                        Spacer(Modifier.width(8.dp))
                        Tag(if (h.prevSignal.isBlank()) "NEW" else (if (ru) "СМЕНА" else "FLIP"), Accent, pulse = true)
                    }
                }
                Text(
                    h.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Tag(h.signal, color)
                Text("${h.confidence}%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(top = 3.dp))
            }
            IconButton(onClick = onFav) {
                Icon(if (fav) Icons.Default.Star else Icons.Default.StarBorder, if (ru) "Избранное" else "Favorite", tint = Warning)
            }
        }
        val lifetime = (h.expiresAt - h.scannedAt).coerceAtLeast(1L)
        val left = (h.expiresAt - now).coerceAtLeast(0L)
        val remaining = (left.toFloat() / lifetime.toFloat()).coerceIn(0f, 1f)
        val urgent = remaining < 0.2f
        val timeColor = if (urgent) Warning else color
        // The countdown owns a full-width line: it can no longer be squeezed out by the tags next to it.
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "⏱ " + (if (ru) "Осталось " else "Left ") + durationText(left, ru),
                fontSize = 14.sp, fontWeight = FontWeight.Black, color = timeColor, modifier = Modifier.weight(1f)
            )
            Text((if (ru) "до " else "until ") + untilText(h.expiresAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(5.dp).clip(RoundedCornerShape(50)).background(timeColor.copy(alpha = 0.16f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(remaining).clip(RoundedCornerShape(50)).background(timeColor))
        }
        val grade = ReliabilityEngine.gradeOf(h.reliability)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag("🕒 ${h.horizon.label(ru)}", Accent)
            Tag((if (ru) "Надёжность " else "Reliability ") + "$grade ${h.reliability}", gradeColor(grade))
            if (h.watch) Tag(if (ru) "Только направление" else "Direction only", Warning)
        }
        if (h.watch) {
            Text(
                if (ru) "После издержек торговой выгоды нет: это ориентир по направлению, а не сигнал на вход."
                else "No edge after costs: a view on direction, not an entry signal.",
                fontSize = 11.sp, color = Warning, modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (h.aligned.isNotBlank()) {
            val known = Confluence.decode(h.aligned).values.count { it != null }
            AlignCells(h.alignedDirections(), h.timeframe, Modifier.padding(top = 8.dp))
            Text(
                if (ru) "Совпадение сроков: ${h.alignedAgree} из $known" else "Timeframe agreement: ${h.alignedAgree} of $known",
                fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = if (h.alignedAgree >= 3) Positive else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        // All three targets with their distance and chance, not only the smallest one.
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LevelMetric(if (ru) "Стоп" else "Stop", h.stop, h.price, h.stopProb, Negative, Modifier.weight(1f))
            LevelMetric("TP1", h.tp1, h.price, h.tp1p, Positive, Modifier.weight(1f))
            LevelMetric("TP2", h.tp2, h.price, h.tp2p, Positive, Modifier.weight(1f))
            if (h.tp3 > 0.0) LevelMetric("TP3", h.tp3, h.price, h.tp3p, Positive, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric(if (ru) "Цена" else "Price", fmt(h.price), MaterialTheme.colorScheme.onSurface)
            Metric(h.horizon.moveLabel(ru), String.format(Locale.US, "%+.2f%%", h.changePct), if (h.changePct >= 0) Positive else Negative)
            Metric(if (ru) "R:R до TP2" else "R:R to TP2", String.format(Locale.US, "%.1f", h.rr), MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun LevelMetric(label: String, level: Double, price: Double, prob: Double, color: Color, modifier: Modifier) {
    val pct = if (price > 0.0 && level > 0.0) (level - price) / price * 100.0 else 0.0
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(fmt(level), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(
            String.format(Locale.US, "%+.2f%%", pct) + (if (prob > 0.0) " · ${(prob * 100).toInt()}%" else ""),
            fontSize = 11.sp, color = color.copy(alpha = 0.85f), maxLines = 1
        )
    }
}

@Composable
private fun Tag(text: String, color: Color, pulse: Boolean = false) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .pulse(pulse, 0.94f)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

@Composable
private fun Metric(label: String, value: String, color: Color) {
    Column {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

/** Compact scanner card for the home screen: status, quick start and the strongest current signals. */
@Composable
internal fun ScannerEntryCard(c: ScannerController?, ru: Boolean, onOpen: () -> Unit, onStart: () -> Unit) {
    if (c == null) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000L); now = System.currentTimeMillis() } }
    GradientCard(Modifier.fillMaxWidth().tapCard(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoGraph, null, tint = Accent, modifier = Modifier.size(28.dp).pulse(c.running))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(if (ru) "Сканер рынка · ${c.horizon.label(true)}" else "Market scanner · ${c.horizon.label(false)}", fontWeight = FontWeight.Black, fontSize = 15.sp)
                val status = when {
                    c.running -> if (ru) "Проверено ${c.done} из ${c.total} • сигналов: ${c.current.size}" else "Checked ${c.done} of ${c.total} • signals: ${c.current.size}"
                    c.lastFinishedAt > 0L -> (if (ru) "Обновлено " else "Updated ") + agoText(c.lastFinishedAt, ru) +
                        (if (ru) " • сигналов: ${c.current.size} • новых: ${c.newCount}" else " • signals: ${c.current.size} • new: ${c.newCount}")
                    else -> if (ru) "Найдёт прогнозы по всему каталогу БКС" else "Finds forecasts across the whole BCS catalogue"
                }
                Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (c.running) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                FilledTonalButton(onClick = onStart, shape = RoundedCornerShape(12.dp)) {
                    Icon(if (c.lastFinishedAt > 0L) Icons.Default.Refresh else Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (c.running && c.total > 0) {
            GlowProgressBar(c.done.toFloat() / c.total.toFloat(), Modifier.fillMaxWidth().padding(top = 10.dp))
        }
        val top = c.current.take(3)
        if (top.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                top.forEach { h ->
                    Tag("${h.symbol.removeSuffix(".ME")} ${h.signal} ${h.confidence}%", signalColor(h.signal))
                }
            }
            c.current.minOfOrNull { it.expiresAt }?.let { soonest ->
                Text(
                    "⏱ " + (if (ru) "Ближайший прогноз истекает через " else "The next forecast expires in ") + durationText((soonest - now).coerceAtLeast(0L), ru),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        c.message?.takeIf { !c.running }?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** One-line catalogue state for the search screen. */
@Composable
internal fun CatalogStatusLine(ru: Boolean, hasToken: Boolean, onRetry: () -> Unit) {
    val text = when {
        !hasToken -> if (ru) "Поиск работает по каталогу БКС. Подключите refresh-токен в настройках, чтобы загрузить все акции и валюты."
        else "Search uses the BCS catalogue. Add your refresh token in settings to download all stocks and currencies."
        CatalogStatus.syncing && CatalogStatus.size == 0 -> if (ru) "Загружаю каталог БКС…" else "Downloading the BCS catalogue…"
        CatalogStatus.syncing -> if (ru) "В каталоге ${CatalogStatus.size} инструментов, загрузка продолжается…" else "${CatalogStatus.size} instruments, still downloading…"
        CatalogStatus.size > 0 && CatalogStatus.error != null -> {
            val why = CatalogStatus.error.orEmpty().take(110)
            if (ru) "В каталоге ${CatalogStatus.size} инструментов, но загрузка не завершена ($why). Нажмите «повторить»." else "${CatalogStatus.size} instruments, but the download is incomplete ($why). Tap retry."
        }
        CatalogStatus.size > 0 -> if (ru) "В каталоге ${CatalogStatus.size} акций и валют БКС" else "${CatalogStatus.size} BCS stocks and currencies"
        CatalogStatus.failed -> {
            val why = CatalogStatus.error?.take(110)?.let { " ($it)" }.orEmpty()
            if (ru) "Каталог не загрузился$why. Проверьте сеть и токен." else "The catalogue could not be downloaded$why. Check network and token."
        }
        else -> ""
    }
    if (text.isBlank()) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (hasToken && !CatalogStatus.syncing && (CatalogStatus.failed || CatalogStatus.error != null)) {
            IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, if (ru) "Повторить" else "Retry") }
        }
    }
}

private fun untilText(ms: Long): String {
    val day = SimpleDateFormat("yyyyDDD", Locale.US)
    val sameDay = day.format(Date(ms)) == day.format(Date())
    return SimpleDateFormat(if (sameDay) "HH:mm" else "dd.MM HH:mm", Locale.getDefault()).format(Date(ms))
}

private fun durationText(ms: Long, ru: Boolean): String {
    val min = ms / 60_000L
    return when {
        min < 1L -> if (ru) "меньше минуты" else "under a minute"
        min < 60L -> if (ru) "$min мин" else "$min min"
        min < 1440L -> if (ru) "${min / 60} ч ${min % 60} мин" else "${min / 60} h ${min % 60} min"
        else -> if (ru) "${min / 1440} д ${(min % 1440) / 60} ч" else "${min / 1440} d ${(min % 1440) / 60} h"
    }
}

private fun leftText(ms: Long, ru: Boolean): String {
    val min = ms / 60_000L
    return when {
        ms <= 0L -> if (ru) "истёк" else "expired"
        min < 1L -> if (ru) "меньше минуты" else "under a minute"
        min < 60L -> if (ru) "ещё $min мин" else "$min min left"
        min < 1440L -> if (ru) "ещё ${min / 60} ч ${min % 60} мин" else "${min / 60} h ${min % 60} min left"
        else -> if (ru) "ещё ${min / 1440} д ${(min % 1440) / 60} ч" else "${min / 1440} d ${(min % 1440) / 60} h left"
    }
}

/** What the last scan really covered, so "the whole market" is a number and not a promise. */
@Composable
private fun CoverageCard(cov: ScanCoverage, h: ScanHorizon, ru: Boolean) {
    GradientCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ru) "Охват рынка" else "Market coverage", fontWeight = FontWeight.Black, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("${cov.percent}%", fontWeight = FontWeight.Black, fontSize = 18.sp, color = if (cov.failed == 0) Positive else Warning)
        }
        Text(
            if (ru) "Проверено ${cov.checked} из ${cov.total} инструментов на срок «${h.label(true)}»"
            else "Checked ${cov.checked} of ${cov.total} instruments for the \"${h.label(false)}\" horizon",
            fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            (if (ru) "Сигналов ${cov.hits} • без сигнала ${cov.noSignal} • неликвидные ${cov.illiquid} • нет торгов ${cov.stale}"
            else "Signals ${cov.hits} • no signal ${cov.noSignal} • illiquid ${cov.illiquid} • not trading ${cov.stale}") +
                (if (cov.failed > 0) (if (ru) " • не проверено ${cov.failed}" else " • not checked ${cov.failed}") else ""),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
        )
    }
}
