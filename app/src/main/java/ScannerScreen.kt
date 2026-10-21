package com.marketforecast.prox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
    onOpen: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onStart: () -> Unit
) {
    var signalFilter by remember { mutableStateOf("ALL") }
    var onlyNew by remember { mutableStateOf(false) }
    var minConf by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf("CONF") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.running) {
        while (c.running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val visible = remember(c.hits, signalFilter, onlyNew, minConf, sort) {
        val base = c.hits
            .filter { signalFilter == "ALL" || it.signal.contains(signalFilter, ignoreCase = true) }
            .filter { !onlyNew || it.isNew }
            .filter { it.confidence >= minConf }
        when (sort) {
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
                    Icon(Icons.Default.AutoGraph, null, tint = Accent, modifier = Modifier.size(30.dp))
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
                    if (ru) "Проходит по всему каталогу и показывает инструменты с активным сигналом LONG или SHORT. Новые прогнозы отмечаются сравнением с прошлым запуском."
                    else "Walks the whole catalogue and lists instruments with an active LONG or SHORT signal. New forecasts are flagged against the previous run.",
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
                if (c.running) {
                    val fraction = if (c.total > 0) c.done.toFloat() / c.total.toFloat() else 0f
                    LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    val elapsed = ((now - c.startedAt) / 1000).coerceAtLeast(1)
                    val eta = if (c.done >= 12 && c.total > c.done) (elapsed * (c.total - c.done) / c.done) else -1L
                    Text(
                        (if (ru) "Проверено ${c.done} из ${c.total} • сигналов: ${c.hits.size}" else "Checked ${c.done} of ${c.total} • signals: ${c.hits.size}") +
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
                    Switch(checked = c.autoStart, onCheckedChange = { c.setAutoStart(it) })
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

        if (c.hits.isNotEmpty()) {
            item {
                SectionHeader(
                    if (ru) "Результаты: ${visible.size} из ${c.hits.size}" else "Results: ${visible.size} of ${c.hits.size}",
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
                }
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
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
                    onOpen = { onOpen(h.symbol) },
                    onFav = { onToggleFavorite(h.symbol) }
                )
            }
            item {
                Text(
                    if (ru) "Прогнозы строятся по дневным свечам и цене закрытия последней сессии. Это статистическая оценка, а не инвестиционная рекомендация. Откройте инструмент, чтобы увидеть полный анализ с живой ценой."
                    else "Forecasts use daily candles and the last session close. They are statistical estimates, not investment advice. Open an instrument for the full analysis with the live price.",
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
private fun HitCard(h: ScanHit, fav: Boolean, ru: Boolean, onOpen: () -> Unit, onFav: () -> Unit) {
    val color = signalColor(h.signal)
    GradientCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(h.symbol.removeSuffix(".ME"), fontWeight = FontWeight.Black, fontSize = 16.sp)
                    if (h.isNew) {
                        Spacer(Modifier.width(8.dp))
                        Tag(if (h.prevSignal.isBlank()) "NEW" else (if (ru) "СМЕНА" else "FLIP"), Accent)
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
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric(if (ru) "Цена" else "Price", fmt(h.price), MaterialTheme.colorScheme.onSurface)
            Metric(if (ru) "День" else "Day", String.format(Locale.US, "%+.2f%%", h.changePct), if (h.changePct >= 0) Positive else Negative)
            Metric(if (ru) "Стоп" else "Stop", fmt(h.stop), Negative)
            Metric("TP1", fmt(h.tp1), Positive)
            Metric("R:R", String.format(Locale.US, "%.1f", h.rr), MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier
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
    GradientCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoGraph, null, tint = Accent, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(if (ru) "Сканер рынка" else "Market scanner", fontWeight = FontWeight.Black, fontSize = 15.sp)
                val status = when {
                    c.running -> if (ru) "Проверено ${c.done} из ${c.total} • сигналов: ${c.hits.size}" else "Checked ${c.done} of ${c.total} • signals: ${c.hits.size}"
                    c.lastFinishedAt > 0L -> (if (ru) "Обновлено " else "Updated ") + agoText(c.lastFinishedAt, ru) +
                        (if (ru) " • сигналов: ${c.hits.size} • новых: ${c.newCount}" else " • signals: ${c.hits.size} • new: ${c.newCount}")
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
            LinearProgressIndicator(
                progress = { (c.done.toFloat() / c.total.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )
        }
        val top = c.hits.take(3)
        if (top.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                top.forEach { h ->
                    Tag("${h.symbol.removeSuffix(".ME")} ${h.signal} ${h.confidence}%", signalColor(h.signal))
                }
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
        CatalogStatus.size > 0 -> if (ru) "В каталоге ${CatalogStatus.size} акций и валют БКС" else "${CatalogStatus.size} BCS stocks and currencies"
        CatalogStatus.failed -> if (ru) "Каталог не загрузился. Проверьте сеть и токен." else "The catalogue could not be downloaded. Check network and token."
        else -> ""
    }
    if (text.isBlank()) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (hasToken && !CatalogStatus.syncing && CatalogStatus.failed) {
            IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, if (ru) "Повторить" else "Retry") }
        }
    }
}
