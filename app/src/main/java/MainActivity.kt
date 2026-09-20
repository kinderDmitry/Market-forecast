package com.marketforecast.prox

import android.Manifest
import android.content.Context
import android.graphics.Paint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.log10
import kotlin.math.pow

private val Accent = Color(0xFF0066D6)
private val Accent2 = Color(0xFF003A82)
private val Blue = Color(0xFF145EA8)
private val Positive = Color(0xFF00D084)
private val Negative = Color(0xFFFF4757)
private val Warning = Color(0xFFFFA502)
private val DarkBg = Color(0xFF000000)
private val DarkCard = Color(0xFF05080C)
private val DarkLine = Color(0xFF0059C7)
private val LightBg = Color(0xFFF5F7FA)
private val LightText = Color(0xFF15121F)
private val DarkText = Color(0xFFFFFFFF)
private val LightMuted = Color(0xFF5E5A68)
private val DarkMuted = Color(0xFF8FA7B8)

private enum class Screen { HOME, SEARCH, FAVORITES, HISTORY, SETTINGS, ANALYSIS, SCANNER, NEWS, NEWS_DETAIL, DIVIDENDS, FINANCE, STATS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MarketForecastApp(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketForecastApp(ctx: Context) {
    val prefs = remember { ctx.getSharedPreferences("mfprefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val bcsToken = prefs.getString("bcs_refresh_token", "") ?: ""
    val repo = remember(bcsToken) { MarketRepository(bcsRefreshToken = bcsToken.ifBlank { null }, prefs = prefs, context = ctx) }
    var ru by remember { mutableStateOf(prefs.getBoolean("ru", true)) }
    var interval by remember { mutableIntStateOf(prefs.getInt("notify_interval", 15).coerceAtLeast(15)) }
    var notifications by remember { mutableStateOf(prefs.getBoolean("notifications_enabled", false)) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()) }
    var favoriteOrder by remember { mutableStateOf(loadFavoriteOrder(prefs, favorites)) }
    var tracked by remember { mutableStateOf(loadTracked(prefs)) }
    var history by remember { mutableStateOf(loadHistory(prefs)) }
    var historyTab by remember { mutableStateOf("TRACKING") }
    var newsDetailUrl by remember { mutableStateOf<String?>(null) }
    val launchIntent = (ctx as? android.app.Activity)?.intent
    // A normal launcher start must always land on HOME. Deep-link navigation is
    // reserved for an actual notification tap; this prevents Android from reopening
    // the last forecast screen after an update/restart.
    val fromNotification = launchIntent?.getBooleanExtra("mfp_from_notification", false) == true
    val launchSymbol = launchIntent?.getStringExtra("mfp_symbol")?.takeIf { it.isNotBlank() }
    val launchTarget = launchIntent?.getStringExtra("mfp_target")?.takeIf { fromNotification }
    var selected by remember { mutableStateOf(launchSymbol ?: prefs.getString("selected", "SBER") ?: "SBER") }
    var tf by remember { mutableStateOf(launchIntent?.getStringExtra("mfp_timeframe") ?: "1D") }
    var screen by remember { mutableStateOf(when(launchTarget){"NEWS"->Screen.NEWS;"NEWS_DETAIL"->Screen.NEWS_DETAIL;"TRACKING"->Screen.HISTORY;"HISTORY"->Screen.HISTORY;"DIVIDENDS"->Screen.DIVIDENDS;"ANALYSIS"->Screen.ANALYSIS;else->Screen.HOME}) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchFilter by remember { mutableStateOf(prefs.getString("search_filter", "ALL") ?: "ALL") }
    var state by remember { mutableStateOf(MarketState(selected, loading = true, dataSource = repo.forecastSource(selected))) }
    var news by remember { mutableStateOf(loadCachedNews(prefs)) }
    var marketNews by remember { mutableStateOf(loadCachedNews(prefs)) }
    var newsCategory by remember { mutableStateOf(NewsCategory.ALL) }
    var marketMode by remember { mutableStateOf(prefs.getString("market_today_mode", if(ru) "ПРОГНОЗЫ" else "FORECASTS") ?: if(ru) "ПРОГНОЗЫ" else "FORECASTS") }
    var marketPicks by remember { mutableStateOf<List<MarketPick>>(emptyList()) }
    var marketLoading by remember { mutableStateOf(true) }
    var refreshValue by remember { mutableIntStateOf(prefs.getInt("refresh_value", 15).coerceAtLeast(1)) }
    var refreshUnit by remember { mutableStateOf(prefs.getString("refresh_unit", "MIN") ?: "MIN") }
    var displayCurrency by remember { mutableStateOf(prefs.getString("display_currency", "RUB") ?: "RUB") }
    var indices by remember { mutableStateOf<List<MarketIndex>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var lastDataError by remember { mutableStateOf<String?>(null) }
    var pendingScanTrack by remember { mutableStateOf<Pair<String,String>?>(null) }
    var scannerTimeframes by remember { mutableStateOf(listOf("15M", "1H", "4H", "1D")) }
    var scannerUniverse by remember { mutableStateOf(ScannerEngine.Universe.ALL) }
    var refreshTick by remember { mutableLongStateOf(0L) }
    val trackingCandleCache = remember { mutableStateMapOf<String, List<Candle>>() }
    // Canonical live quote per instrument. All timeframes in the same session use
    // exactly the same current price; only the historical context changes.
    val canonicalQuotes = remember { mutableStateMapOf<String, Double>() }

    var showPopupPermissionPrompt by remember { mutableStateOf(false) }

    fun saveFav(v: Set<String>) {
        favorites = v
        val current = favoriteOrder.filter { it in v }
        val added = v.filter { it !in current }
        favoriteOrder = (current + added).distinct()
        prefs.edit().putStringSet("favorites", v).putString("favorite_order", favoriteOrder.joinToString("\n")).apply()
    }
    fun saveTracked(v: List<TrackedForecast>) { tracked = v; prefs.edit().putString("tracked", encodeTracked(v)).apply() }
    fun schedule() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "market_monitor", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<MarketMonitorWorker>(interval.toLong().coerceAtLeast(15), TimeUnit.MINUTES).setConstraints(constraints).build()
        )
    }
    fun runMonitorNow() {
        message = if (ru) "Проверка уведомлений запущена…" else "Notification check started…"
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("market_monitor_now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<MarketMonitorWorker>().setConstraints(constraints).build())
        scope.launch { delay(500); message = if (ru) "Проверка поставлена в очередь. Результат появится после выполнения фоновой проверки." else "Check queued. The result will appear after the background check runs." }
    }
    fun load(symbol: String, time: String = tf, navigateToAnalysis: Boolean = true) {
        selected = symbol.uppercase(Locale.US); tf = time; prefs.edit().putString("selected", selected).apply(); if (navigateToAnalysis) screen = Screen.ANALYSIS
        state = MarketState(selected, loading = true, timeframe = time, dataSource = repo.forecastSource(selected))
        scope.launch {
            val pair = when (time) { "15M" -> "60d" to "15m"; "1H" -> "2y" to "1h"; "4H" -> "2y" to "4h"; "1W" -> "10y" to "1wk"; else -> "5y" to "1d" }
            val candlesRaw = withContext(Dispatchers.IO) {
                runCatching { repo.load(selected, pair.first, pair.second) }.getOrElse {
                    // Never blank the chart merely because BCS timed out while switching
                    // timeframes. The repository already keeps a stale-while-revalidate
                    // cache; if a refresh still fails, retain the currently displayed
                    // dataset for this instrument until a valid replacement arrives.
                    state.candles.takeIf { state.symbol.equals(selected, true) && state.candles.isNotEmpty() }.orEmpty()
                }
            }
            val meta = withContext(Dispatchers.IO) { repo.instrumentMeta(selected) }
            val quoted = withContext(Dispatchers.IO) { runCatching { repo.quote(selected) }.getOrNull() }
            val live = reconcileLivePrice(selected, candlesRaw, quoted)
            if (live > 0.0 && live.isFinite()) {
                canonicalQuotes[selected] = live
                prefs.edit().putString("canonical_quote_$selected", live.toString())
                    .putLong("canonical_quote_ts_$selected", System.currentTimeMillis()).apply()
            }
            // The realtime candle is part of the exact dataset used by the forecast.
            // Entry/levels use the same canonical live price across timeframes.
            val candles = if (live > 0.0) mergeRealtimeCandle(candlesRaw, live, time, System.currentTimeMillis(), selected) else candlesRaw
            val f = if (live > 0.0 && candles.size >= 30) withContext(Dispatchers.Default) { runCatching { AnalyticsEngine.analyze(candles, live) }.getOrNull() } else null
            state = MarketState(selected, candles, f, false, if (f == null) "Актуальная цена БКС недоступна или недостаточно рыночных данных" else null, System.currentTimeMillis(), time, news, live, meta, repo.forecastSource(selected))
            val fresh = withContext(Dispatchers.IO) { runCatching { repo.news(selected, 12, ru) }.getOrDefault(emptyList()) }
            if (fresh.isNotEmpty()) { news = fresh; saveCachedNews(prefs, fresh); state = state.copy(news = fresh) }
        }
    }
    fun addTracked(): Boolean {
        val f = state.forecast ?: return false
        if (f.signal == "NO TRADE") { message = if (ru) "NO TRADE нельзя поставить на отслеживание." else "NO TRADE cannot be tracked."; return false }
        if (tracked.any { it.symbol == state.symbol && it.timeframe == tf && it.result == "PENDING" }) {
            message = if (ru) "Этот инструмент и таймфрейм уже отслеживаются." else "This instrument and timeframe are already tracked."; return false
        }
        val entry = state.livePrice.takeIf { it > 0 } ?: f.entry
        val now = System.currentTimeMillis()
        val id = "${state.symbol}_${tf}_${now}_${java.util.UUID.randomUUID()}"
        val t = TrackedForecast(id, state.symbol, f.signal, f.confidence, entry, tf, now, now + tfMillis(tf), stop = f.stop, tp1 = f.tp1, tp2 = f.tp2, tp3 = f.tp3, lastLivePrice = entry, lastUpdated = now)
        saveTracked((listOf(t) + tracked).take(150))
        val resultConstraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("forecast_result_${t.id}", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<MarketMonitorWorker>()
            .setConstraints(resultConstraints)
            .setInitialDelay(tfMillis(tf), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build())
        // IMPORTANT: a newly tracked forecast is NOT a result yet.
        // Keep it exclusively in the Tracking section until its horizon is reached
        // and TrackingEngine produces a final result.
        schedule()
        message = if (ru) "Добавлено в отслеживание: ${t.symbol} • $tf" else "Added to monitoring: ${t.symbol} • $tf"
        return true
    }

    LaunchedEffect(state.updated, pendingScanTrack) {
        val pending = pendingScanTrack
        if (pending != null && state.symbol == pending.first && tf == pending.second && state.forecast != null && !state.loading) {
            addTracked()
            pendingScanTrack = null
        }
    }

    LaunchedEffect(Unit) {
        // Priority 0: selected instrument and Analytics Center data.
        // Priority 1: the first visible favorites (15M + 1H), which are the most
        // useful timeframes for the home feed.
        // Priority 2: indices/news/ranking feeds. Slow secondary providers must never
        // delay the primary forecast UI.
        load(selected, "1D", navigateToAnalysis = false)
        schedule()
        withContext(Dispatchers.IO) {
            repo.prefetchPriority(favoriteOrder.take(8), listOf("15M", "1H"))
        }
        launch {
            val secondary = withContext(Dispatchers.IO) {
                val indexJob = async { runCatching { repo.marketIndices() }.getOrDefault(emptyList()) }
                val newsJob = async { runCatching { repo.marketNews(30, ru, newsCategory) }.getOrDefault(emptyList()) }
                val picksJob = async { runCatching { repo.marketToday(marketMode, 12) }.getOrDefault(emptyList()) }
                Triple(indexJob.await(), newsJob.await(), picksJob.await())
            }
            indices = secondary.first
            if (secondary.second.isNotEmpty()) { marketNews = secondary.second; saveCachedNews(prefs, secondary.second) }
            else if (marketNews.isEmpty()) lastDataError = if (ru) "Новости временно не подгрузились" else "News could not be loaded"
            marketPicks = secondary.third
            marketLoading = false
        }
    }
    // Opening History always triggers an immediate monitor pass. This is intentionally
    // independent from the 15-minute periodic schedule, because WorkManager may delay
    // periodic work on Android. Existing expired forecasts therefore get a catch-up
    // attempt as soon as the user opens the result screen.
    LaunchedEffect(screen) {
        if (screen == Screen.HISTORY) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "market_monitor_history_catchup",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<MarketMonitorWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .build()
            )
        }
    }

    LaunchedEffect(query, searchFilter) {
        val q = query.trim()
        if (q.isEmpty()) { results = emptyList(); searching = false; return@LaunchedEffect }
        val requested = q
        val requestedFilter = searchFilter

        // Modern-search behaviour: render local suggestions immediately, then let
        // the authoritative BCS resolver refresh them in the same coroutine.
        // LaunchedEffect cancellation prevents an older query from overwriting a newer one.
        val local = withContext(Dispatchers.Default) { repo.searchLocal(requested, requestedFilter) }
        if (query.trim().equals(requested, true) && searchFilter == requestedFilter && local.isNotEmpty()) {
            results = local
        }

        delay(260)
        searching = true
        val found = withContext(Dispatchers.IO) { runCatching { repo.search(requested, requestedFilter) }.getOrDefault(emptyList()) }
        if (query.trim().equals(requested, true) && searchFilter == requestedFilter) {
            if (found.isNotEmpty()) results = found
            message = if (ru) "Поиск БКС обновлён: найдено ${if (found.isNotEmpty()) found.size else results.size}" else "BCS search updated: ${if (found.isNotEmpty()) found.size else results.size} found"
        }
        searching = false
    }
    LaunchedEffect(ru) {
        prefs.edit().putBoolean("ru", ru).apply()
        if (selected.isNotBlank()) {
            val fresh = withContext(Dispatchers.IO) { runCatching { repo.news(selected, 12, ru) }.getOrDefault(emptyList()) }
            if (fresh.isNotEmpty()) { news = fresh; saveCachedNews(prefs, fresh); state = state.copy(news = fresh) }
        }
        marketNews = withContext(Dispatchers.IO) { runCatching { repo.marketNews(30, ru, newsCategory) }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(newsCategory) { marketNews = withContext(Dispatchers.IO) { runCatching { repo.marketNews(30, ru, newsCategory) }.getOrDefault(emptyList()) } }
    LaunchedEffect(marketMode) { marketLoading = true; marketPicks = withContext(Dispatchers.IO) { runCatching { repo.marketToday(marketMode, 20) }.getOrDefault(emptyList()) }; marketLoading = false; prefs.edit().putString("market_today_mode", marketMode).apply() }

    // One foreground refresh clock is the source of truth. Fast live work (quote/tracking)
    // is deliberately executed before slower news/market-ranking feeds so a slow source can
    // never make the chart appear frozen. Tracking candles are cached; every tick refreshes
    // the live quote and re-evaluates the forecast against the cached market path.
    LaunchedEffect(refreshValue, refreshUnit, selected, tf, displayCurrency) {
        var secondaryJob: Job? = null
        withContext(Dispatchers.IO) { runCatching { repo.refreshDisplayCurrencyRates() } }
        prefs.edit().putInt("refresh_value", refreshValue).putString("refresh_unit", refreshUnit).apply()
        while (true) {
            val ms = if (refreshUnit == "SEC") refreshValue * 1000L else refreshValue * 60_000L
            delay(ms.coerceAtLeast(1000L))
            val now = System.currentTimeMillis()

            // 1) LIVE analysis first.
            if (screen == Screen.ANALYSIS) {
                val live = withContext(Dispatchers.IO) { runCatching { repo.quote(selected) }.getOrNull() }
                if (live != null && live > 0.0 && live.isFinite()) {
                    canonicalQuotes[selected] = live
                    val reconciled = reconcileLivePrice(selected, state.candles, live)
                    val merged = mergeRealtimeCandle(state.candles, reconciled, tf, now, selected)
                    val refreshed = if (merged.size >= 30) withContext(Dispatchers.Default) { runCatching { AnalyticsEngine.analyze(merged, reconciled) }.getOrNull() } else state.forecast
                    state = state.copy(candles = merged, forecast = refreshed, livePrice = reconciled, updated = now)
                }
            }

            // 2) TRACKING on exactly the same configured clock. Network calls are parallel.
            val latest = loadTracked(prefs)
            val evaluated = withContext(Dispatchers.IO) {
                val gate = Semaphore(8)
                latest.map { item ->
                    async { gate.withPermit {
                        if (item.result != "PENDING") return@withPermit item to null
                        val pair = timeframePair(item.timeframe)
                        val key = "${item.symbol}|${item.timeframe}"
                        val cached = trackingCandleCache[key]
                        val cs = if (now >= item.checkAt || cached.isNullOrEmpty()) {
                            runCatching { repo.load(item.symbol, pair.first, pair.second) }.getOrDefault(cached ?: emptyList()).also { trackingCandleCache[key] = it }
                        } else cached
                        val lp = runCatching { repo.quote(item.symbol) }.getOrNull()
                        val reconciled = reconcileLivePrice(item.symbol, cs, lp)
                        val ev = if (reconciled > 0.0) TrackingEngine.evaluate(item, cs, reconciled, now) else null
                        if (ev == null) item to null else item.copy(
                            lastLivePrice = ev.price, lastUpdated = now,
                            tp1Hit = item.tp1Hit || ev.events.contains("SUCCESS_TP1"),
                            tp2Hit = item.tp2Hit || ev.events.contains("SUCCESS_TP2"),
                            tp3Hit = item.tp3Hit || ev.events.contains("SUCCESS_TP3"),
                            slHit = item.slHit || ev.events.contains("STOP"),
                            result = ev.finalResult ?: "PENDING",
                            checkedPrice = if (ev.finalResult != null) ev.price else item.checkedPrice
                        ) to ev
                    } }
                }.awaitAll()
            }
            val updatedTracked = evaluated.map { it.first }
            if (updatedTracked != latest) {
                saveTracked(updatedTracked); tracked = updatedTracked
                val nextHistory = loadHistory(prefs).toMutableList()
                evaluated.forEach { (newItem, evNullable) ->
                    val ev = evNullable ?: return@forEach
                    val result = ev.finalResult ?: return@forEach
                    val ok = result.startsWith("SUCCESS") || result == "DIRECTION_OK" || result == "FLAT"
                    val mark = when { result == "FLAT" -> "≈"; ok -> "✓"; else -> "✕" }
                    val idx = nextHistory.indexOfFirst { it.symbol == newItem.symbol && it.time == newItem.createdAt }
                    val entry = HistoryEntry(newItem.createdAt, newItem.symbol, newItem.signal, newItem.confidence, newItem.entry, newItem.tp2, mark, newItem.timeframe, if (result == "FLAT") null else ok, ev.price, hitSummary(newItem))
                    if (idx >= 0) nextHistory[idx] = entry else nextHistory.add(0, entry)
                }
                saveHistory(prefs, nextHistory); history = nextHistory
                // Foreground tracking must deliver the same terminal/milestone notifications
                // as the background worker. A shared event key prevents duplicates.
                evaluated.forEach { (newItem, evNullable) ->
                    val ev = evNullable ?: return@forEach
                    ev.events.forEach { event ->
                        val enabled = when (event) {
                            "SUCCESS_TP1" -> prefs.getBoolean("alert_tp1", true)
                            "SUCCESS_TP2" -> prefs.getBoolean("alert_tp2", true)
                            "SUCCESS_TP3" -> prefs.getBoolean("alert_tp3", true)
                            "STOP" -> prefs.getBoolean("alert_sl", true)
                            else -> false
                        }
                        if (!enabled) return@forEach
                        val label = when (event) {
                            "SUCCESS_TP1" -> "🎯 TP1 достигнут"
                            "SUCCESS_TP2" -> "🎯 TP2 достигнут"
                            "SUCCESS_TP3" -> "🏆 TP3 достигнут"
                            "STOP" -> "🛑 Stop Loss достигнут"
                            else -> event
                        }
                        NotificationHelper.notifyTracking(ctx, "${newItem.id}|$event", ru, label, "${newItem.symbol} • ${newItem.timeframe} • ${formatPrice(ev.price)}", newItem.symbol)
                    }
                    ev.finalResult?.let { result ->
                        if (prefs.getBoolean("alert_result", true)) {
                            val ok = result.startsWith("SUCCESS") || result == "DIRECTION_OK" || result == "FLAT"
                            val label = if (ok) "✅ Прогноз завершён" else "❌ Прогноз завершён"
                            val body = if (ru) {
                                "${newItem.symbol} • ${newItem.timeframe}\nРезультат: ${if (ok) "подтверждён" else "не подтверждён"}\nВход: ${formatPrice(newItem.entry)} • Закрытие: ${formatPrice(ev.price)}"
                            } else {
                                "${newItem.symbol} • ${newItem.timeframe}\nResult: ${if (ok) "confirmed" else "failed"}\nOpen: ${formatPrice(newItem.entry)} • Close: ${formatPrice(ev.price)}"
                            }
                            NotificationHelper.notifyTracking(ctx, "${newItem.id}|RESULT|$result", ru, label, body, newItem.symbol)
                        }
                    }
                }
            }

            // 3) Secondary feeds are launched as a replaceable child job. They use the same
            // configured tick, but never hold up the next live quote/tracking tick.
            secondaryJob?.cancel()
            secondaryJob = launch {
                val refreshResults = withContext(Dispatchers.IO) {
                    val newsJob = async { runCatching { repo.marketNews(30, ru, newsCategory) }.getOrNull() }
                    val indicesJob = async { runCatching { repo.marketIndices() }.getOrDefault(indices) }
                    val picksJob = async { runCatching { repo.marketToday(marketMode, 20) }.getOrDefault(marketPicks) }
                    Triple(newsJob.await(), indicesJob.await(), picksJob.await())
                }
                refreshResults.first?.let { if (it.isNotEmpty()) { marketNews = it; saveCachedNews(prefs, it) } }
                indices = refreshResults.second
                marketPicks = refreshResults.third
                if (screen == Screen.ANALYSIS) {
                    val instrumentNews = withContext(Dispatchers.IO) { runCatching { repo.news(selected, 12, ru) }.getOrNull() }
                    if (!instrumentNews.isNullOrEmpty()) { news = instrumentNews; saveCachedNews(prefs, instrumentNews); state = state.copy(news = instrumentNews) }
                }
            }
            refreshTick = now
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        notifications = ok; prefs.edit().putBoolean("notifications_enabled", ok).apply()
        if (ok) { createNotificationChannel(ctx); showPopupPermissionPrompt = !prefs.getBoolean("popup_prompt_done", false); schedule(); runMonitorNow(); sendTestNotification(ctx, ru) }
    }
    fun toggleNotifications(enable: Boolean) {
        if (enable && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else { notifications = enable; prefs.edit().putBoolean("notifications_enabled", enable).apply(); if (enable) { createNotificationChannel(ctx); schedule(); runMonitorNow(); sendTestNotification(ctx, ru) } }
    }
    fun requestStartupNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else { notifications = true; prefs.edit().putBoolean("notifications_enabled", true).apply(); createNotificationChannel(ctx); if (!prefs.getBoolean("popup_prompt_done", false)) showPopupPermissionPrompt = true }
    }
    LaunchedEffect(Unit) { requestStartupNotifications() }

    val scheme = darkColorScheme(
        primary = Accent,
        onPrimary = Color.Black,
        secondary = Accent2,
        onSecondary = Color.White,
        tertiary = Blue,
        background = DarkBg,
        surface = DarkCard,
        surfaceVariant = Color(0xFF03070D),
        onBackground = DarkText,
        onSurface = DarkText,
        onSurfaceVariant = DarkMuted,
        outline = DarkLine,
        outlineVariant = DarkLine.copy(alpha = 0.45f)
    )
    if (showPopupPermissionPrompt) {
        AlertDialog(
            onDismissRequest = { showPopupPermissionPrompt = false; prefs.edit().putBoolean("popup_prompt_done", true).apply() },
            title = { Text(if (ru) "Всплывающие уведомления" else "Pop-up notifications") },
            text = { Text(if (ru) "Для TP/SL, новостей и отслеживания нужен канал с высокой важностью. Android не имеет отдельного системного разрешения на всплывающие уведомления: они настраиваются внутри канала уведомлений." else "TP/SL, news and tracking alerts use a high-importance channel. Android has no separate runtime pop-up permission; it is controlled in the notification channel settings.") },
            confirmButton = { TextButton(onClick = {
                createNotificationChannel(ctx)
                showPopupPermissionPrompt = false
                prefs.edit().putBoolean("popup_prompt_done", true).apply()
                if (Build.VERSION.SDK_INT >= 26) {
                    val intent = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName)
                    }
                    ctx.startActivity(intent)
                }
            }) { Text(if (ru) "Открыть настройки" else "Open settings") } },
            dismissButton = { TextButton(onClick = { showPopupPermissionPrompt = false; prefs.edit().putBoolean("popup_prompt_done", true).apply() }) { Text(if (ru) "Позже" else "Later") } }
        )
    }
    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                if (screen != Screen.HOME && screen != Screen.NEWS_DETAIL) {
                    TopAppBar(
                        title = { BrandWordmark(screenTitle(screen, ru)) },
                        navigationIcon = { IconButton(onClick = { screen = Screen.HOME }) { Icon(Icons.Default.ArrowBack, contentDescription = null, tint = Accent) } },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )
                }
            },
            bottomBar = { if (screen in setOf(Screen.HOME, Screen.SEARCH, Screen.FAVORITES, Screen.HISTORY, Screen.SETTINGS)) BottomNav(screen, ru) { screen = it } }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                CosmicBackground()
                Box(Modifier.fillMaxSize()) {
                AnimatedContent(targetState = screen, label = "screen") { s ->
                    when (s) {
                        Screen.HOME -> Home(state, indices, favorites, favoriteOrder, marketNews, marketPicks, marketLoading, marketMode, newsCategory, ru, repo, { screen = Screen.SEARCH }, { screen = Screen.NEWS }, { screen = Screen.DIVIDENDS }, { screen = Screen.FINANCE }, { screen = Screen.STATS }, { screen = Screen.SCANNER }, { marketMode = it }, { newsCategory = it }, ::saveFav, ::load, { url -> newsDetailUrl = url; screen = Screen.NEWS_DETAIL })
                        Screen.SEARCH -> SearchScreen(query, { query = it }, results, searching, ru, favorites, searchFilter, { searchFilter = it; prefs.edit().putString("search_filter", it).apply() }, ::saveFav) { load(it.symbol) }
                        Screen.FAVORITES -> Favorites(favoriteOrder, ru, repo, ::saveFav, ::load, refreshTick)
                        Screen.HISTORY -> History(history, tracked, ru, repo, prefs, ::load, { h -> history = history.filterNot { it.time == h.time && it.symbol == h.symbol }; saveHistory(prefs, history); tracked = tracked.filterNot { it.createdAt == h.time && it.symbol == h.symbol }; saveTracked(tracked) }, { screen = Screen.STATS }, historyTab, { historyTab = it }, refreshTick)
                        Screen.SETTINGS -> Settings(ru, notifications, interval, prefs, refreshValue, refreshUnit, displayCurrency, { ru = !ru }, ::toggleNotifications, { interval = it; prefs.edit().putInt("notify_interval", it).apply(); schedule(); runMonitorNow() }, { refreshValue = it }, { refreshUnit = it }, { displayCurrency = it; prefs.edit().putString("display_currency", it).apply(); scope.launch(Dispatchers.IO) { repo.refreshDisplayCurrencyRates() } }, { history = emptyList(); tracked = emptyList(); prefs.edit().remove("forecast_history").remove("tracked").remove("history_stats").apply(); message = if (ru) "История и статистика очищены" else "History and statistics cleared" }, ::runMonitorNow, repo)
                        Screen.ANALYSIS -> Analysis(state, ru, tf, favorites.contains(selected), favorites, ::saveFav, { load(selected, it) }, { load(selected, tf) }, ::addTracked, tracked, prefs)
                        Screen.SCANNER -> ScannerScreen(repo, favorites, scannerUniverse, scannerTimeframes, ru, { scannerUniverse = it }, { scannerTimeframes = it }, { symbol -> load(symbol, scannerTimeframes.lastOrNull() ?: "1D") })
                        Screen.NEWS -> NewsScreen(marketNews, ru, refreshTick) { newsDetailUrl = it; screen = Screen.NEWS_DETAIL }
                        Screen.NEWS_DETAIL -> NewsDetailScreen(newsDetailUrl.orEmpty(), ru) { screen = Screen.NEWS }
                        Screen.DIVIDENDS -> DividendScreen(ru, repo, refreshTick) { screen = Screen.HOME }
                        Screen.FINANCE -> FinanceScreen(state.forecast, ru)
                        Screen.STATS -> Stats(history, tracked, ru)
                    }
                }
                message?.let { notice ->
                    InAppNotice(notice, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                    LaunchedEffect(notice) { delay(15000); if (message == notice) message = null }
                }
                lastDataError?.let { notice ->
                    InAppNotice(notice, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                    LaunchedEffect(notice) { delay(15000); if (lastDataError == notice) lastDataError = null }
                }
                }
            }
        }
    }
}

@Composable
private fun CosmicBackground() {
    val bg = MaterialTheme.colorScheme.background
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color.Black)
        val w = size.width
        val h = size.height
        drawCircle(Accent.copy(alpha = .035f), w * .40f, Offset(w * .08f, h * .12f))
        drawCircle(Accent2.copy(alpha = .025f), w * .34f, Offset(w * .92f, h * .35f))
        drawCircle(Accent.copy(alpha = .018f), w * .48f, Offset(w * .50f, h * .95f))
        // Deterministic star field: no animation loop, no battery drain.
        for (i in 0 until 70) {
            val x = ((i * 97) % 1000) / 1000f * w
            val y = ((i * 173 + 31) % 1000) / 1000f * h
            val r = if (i % 9 == 0) 1.6f else 0.75f
            drawCircle(Color.White.copy(alpha = if (i % 7 == 0) .42f else .18f), r, Offset(x, y))
        }
    }
}

private fun screenTitle(s: Screen, ru: Boolean) = when (s) {
    Screen.SEARCH -> if (ru) "🔎 Поиск" else "🔎 Search"; Screen.FAVORITES -> if (ru) "⭐ Избранное" else "⭐ Favorites"; Screen.HISTORY -> if (ru) "🕘 История прогнозов" else "🕘 Forecast history"; Screen.SETTINGS -> if (ru) "Настройки" else "Settings"; Screen.ANALYSIS -> if (ru) "Прогноз" else "Forecast"; Screen.SCANNER -> if (ru) "Сканер" else "Scanner"; Screen.NEWS -> if (ru) "📰 Новости" else "📰 News"; Screen.NEWS_DETAIL -> if (ru) "Новость" else "Article"; Screen.DIVIDENDS -> if (ru) "Дивиденды" else "Dividends"; Screen.FINANCE -> if (ru) "Финансы и прибыль" else "Finance & profit"; Screen.STATS -> if (ru) "Статистика" else "Statistics"; else -> "Market Forecast"
}

@Composable
private fun BrandWordmark(subtitle: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.mfp_icon),
            contentDescription = "App logo",
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                style = LocalTextStyle.current.copy(shadow = Shadow(Accent.copy(alpha = 0.72f), blurRadius = 10f)),
                modifier = Modifier.padding(start = 10.dp),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun BrandHero(ru: Boolean) {
    // Minimal home header: logo only. The old "Market Forecast" title is intentionally removed.
    Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.mfp_icon),
            contentDescription = "App logo",
            modifier = Modifier.size(86.dp).clip(RoundedCornerShape(24.dp)),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun Home(state: MarketState, indices: List<MarketIndex>, favs: Set<String>, favOrder: List<String>, news: List<NewsItem>, picks: List<MarketPick>, marketLoading: Boolean, marketMode: String, newsCategory: NewsCategory, ru: Boolean, repo: MarketRepository, search: () -> Unit, openNews: () -> Unit, openDiv: () -> Unit, openFinance: () -> Unit, openStats: () -> Unit, openScanner: () -> Unit, setMarketMode: (String) -> Unit, setNewsCategory: (NewsCategory) -> Unit, save: (Set<String>) -> Unit, load: (String, String) -> Unit, openArticle: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp)) {
        item { BrandHero(ru) }
        item { SearchLauncher(ru, search) }
        item {
            SectionHeader(
                if (ru) "АНАЛИТИЧЕСКИЙ ЦЕНТР" else "ANALYTICS CENTER",
                if (ru) "Лента избранных акций и валют • актуальное движение • сигналы по каждому таймфрейму" else "Favorites stocks and FX feed • live movement • signals by timeframe"
            )
        }
        item {
            if (favOrder.isEmpty()) {
                GradientCard(Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AutoGraph, null, tint = Accent, modifier = Modifier.size(30.dp))
                    Text(if (ru) "Добавьте акции или валюты в Избранное" else "Add stocks or currencies to Favorites", fontWeight = FontWeight.Black, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(if (ru) "Здесь появятся строгие прогнозы 15M / 1H / 4H / 1D / 1W и текущая цена из единого источника." else "Strict 15M / 1H / 4H / 1D / 1W forecasts and the canonical live price will appear here.", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
                    OutlinedButton(onClick = search, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(7.dp)); Text(if (ru) "Найти инструмент" else "Find instrument", fontWeight = FontWeight.Bold) }
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 4.dp)) {
                    items(favOrder, key = { it }) { symbol ->
                        FavoriteAnalyticsCard(symbol, ru, repo, onOpen = { tf -> load(symbol, tf) })
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction(Icons.Default.Newspaper, if (ru) "📰 Новости" else "📰 News", openNews, Modifier.weight(1f))
                QuickAction(Icons.Default.CalendarMonth, if (ru) "Дивиденды" else "Dividends", openDiv, Modifier.weight(1f))
                QuickAction(Icons.Default.Calculate, if (ru) "Прибыль" else "Profit", openFinance, Modifier.weight(1f))
                QuickAction(Icons.Default.BarChart, if (ru) "Статистика" else "Stats", openStats, Modifier.weight(1f))
                QuickAction(Icons.Default.Search, if (ru) "Сканер" else "Scanner", openScanner, Modifier.weight(1f))
            }
        }
        item { SectionHeader(if (ru) "Рынок" else "Market", if (ru) "Индексы и дополнительные рыночные данные" else "Indices and additional market data") }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) { indices.forEach { IndexCard(it) } } }
        item { SectionHeader(if (ru) "📰 Новости" else "📰 News", if (ru) "Акции и валюты" else "Stocks and FX") }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(NewsCategory.ALL,NewsCategory.STOCKS,NewsCategory.FX).forEach { c -> FilterChip(selected=newsCategory==c,onClick={setNewsCategory(c)},label={Text(if(ru) when(c){NewsCategory.ALL->"Все";NewsCategory.STOCKS->"Акции";NewsCategory.FX->"Валюты"} else c.name,fontSize=8.sp)}) } } }
        if (news.isEmpty()) { item { EmptyCard(if (ru) "Новости временно недоступны. Повторите обновление через несколько секунд." else "News are temporarily unavailable. Refresh in a few seconds.") } }
        items(news.take(20), key = { it.url.ifBlank { it.title } }) { NewsCard(it, ru, openArticle = openArticle) }
    }
}


private fun scannerRegimeRu(value: String): String = when (value.uppercase(Locale.US)) {
    "TREND" -> "Тренд"
    "RANGE" -> "Диапазон"
    "IMPULSE" -> "Импульс"
    "REVERSAL" -> "Разворот"
    else -> value.replace("_", " ").lowercase(Locale("ru")).replaceFirstChar { it.titlecase(Locale("ru")) }
}

@Composable
private fun ScannerScreen(
    repo: MarketRepository,
    favorites: Set<String>,
    universe: ScannerEngine.Universe,
    timeframes: List<String>,
    ru: Boolean,
    setUniverse: (ScannerEngine.Universe) -> Unit,
    setTimeframes: (List<String>) -> Unit,
    openInstrument: (String) -> Unit
) {
    var running by remember { mutableStateOf(false) }
    var discovered by remember { mutableIntStateOf(0) }
    var completed by remember { mutableIntStateOf(0) }
    var signals by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<ScannerEngine.Result>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var runId by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val cancelled = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    DisposableEffect(Unit) { onDispose { cancelled.set(true) } }
    LaunchedEffect(runId) {
        if (runId == 0) return@LaunchedEffect
        cancelled.set(false)
        running = true; error = null; results = emptyList(); discovered = 0; completed = 0; signals = 0
        val engine = ScannerEngine(repo)
        runCatching {
            withContext(Dispatchers.IO) {
                engine.scan(
                    ScannerEngine.Config(universe = universe, timeframes = timeframes, workers = if (universe == ScannerEngine.Universe.ALL) 4 else 3),
                    favorites = favorites,
                    cancelled = cancelled,
                    onProgress = { p -> scope.launch { discovered = p.discovered; completed = p.completed; signals = p.signals; running = p.active } },
                    onResult = { r -> scope.launch { results = (results + r).distinctBy { it.instrument.symbol + "@" + it.instrument.classCode }.sortedByDescending { it.confidence.toDouble() + abs(it.score) * 0.35 }.take(200); signals = results.size } }
                )
            }
        }.onFailure { if (it !is CancellationException) error = it.message ?: if (ru) "Ошибка сканирования" else "Scanner error" }
        running = false
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { SectionHeader(if (ru) "СКАНЕР РЫНКА" else "MARKET SCANNER", if (ru) "Единая модель прогноза • БКС • потоковая выдача" else "One forecast model • BCS • streaming results") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = universe == ScannerEngine.Universe.ALL, onClick = { if (!running) setUniverse(ScannerEngine.Universe.ALL) }, label = { Text(if (ru) "Весь рынок" else "Whole market", fontSize = 10.sp) })
                FilterChip(selected = universe == ScannerEngine.Universe.FAVORITES, onClick = { if (!running) setUniverse(ScannerEngine.Universe.FAVORITES) }, label = { Text(if (ru) "Избранные" else "Favorites", fontSize = 10.sp) })
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("15M", "1H", "4H", "1D").forEach { tf ->
                    FilterChip(selected = tf in timeframes, onClick = {
                        if (!running) {
                            val next = if (tf in timeframes) timeframes - tf else timeframes + tf
                            setTimeframes(next.sortedBy { listOf("15M", "1H", "4H", "1D").indexOf(it) })
                        }
                    }, label = { Text(tf, fontSize = 10.sp) })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (running) cancelled.set(true) else runId++ }, modifier = Modifier.weight(1f)) {
                    Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (running) if (ru) "Остановить" else "Stop" else if (ru) "Начать сканирование" else "Start scan", fontWeight = FontWeight.Black)
                }
                OutlinedButton(onClick = { results = emptyList(); completed = 0; discovered = 0; signals = 0 }, enabled = !running) { Text(if (ru) "Очистить" else "Clear") }
            }
        }
        item {
            GradientCard(Modifier.fillMaxWidth()) {
                Text(if (ru) "Обработано: $completed / $discovered" else "Processed: $completed / $discovered", fontWeight = FontWeight.Black)
                Text(if (ru) "Сигналов: $signals" else "Signals: $signals", color = Accent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                if (running && discovered > 0) LinearProgressIndicator(progress = { (completed.toFloat() / discovered.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(if (ru) "Каждый инструмент проходит выбранные таймфреймы последовательно; итог считается тем же движком прогноза, что и обычный прогноз." else "Each instrument passes the selected timeframes in order; the same forecast engine as the normal forecast produces the result.", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
            }
        }
        error?.let { msg -> item { EmptyCard(msg) } }
        if (results.isEmpty() && !running && error == null) item { EmptyCard(if (ru) "Запустите сканирование. Показываются только сигналы, прошедшие quality gates." else "Start scanning. Only signals that pass the quality gates are shown.") }
        items(results, key = { it.instrument.symbol + "@" + it.instrument.classCode }) { r ->
            GradientCard(Modifier.fillMaxWidth().clickable { openInstrument(r.instrument.symbol) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.instrument.name, fontWeight = FontWeight.Black, fontSize = 14.sp)
                        Text("${r.instrument.symbol}${if (r.instrument.classCode.isNotBlank()) " • ${r.instrument.classCode}" else ""}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(r.signal, color = if (r.signal == "LONG") Positive else Negative, fontWeight = FontWeight.Black)
                }
                Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (ru) "Оценка ${"%.1f".format(Locale.US, r.score)}" else "Score ${"%.1f".format(Locale.US, r.score)}", fontSize = 9.sp)
                    Text("${r.confidence}%", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(if (ru) "Р/Р ${"%.2f".format(Locale.US, r.rr)}" else "R/R ${"%.2f".format(Locale.US, r.rr)}", fontSize = 9.sp)
                    Text(if (ru) scannerRegimeRu(r.regime) else r.regime, fontSize = 9.sp, color = Accent)
                }
                Text(r.timeframeScores.entries.joinToString("  •  ") { "${it.key}: ${"%.0f".format(Locale.US, it.value)}" }, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

private fun formatPrice(price: Double): String = fmt(price)

private fun formatHorizon(seconds: Long, ru: Boolean): String = when {
    seconds < 60L -> if (ru) "$seconds сек" else "$seconds sec"
    seconds < 3600L -> { val m = seconds / 60L; if (ru) "$m мин" else "$m min" }
    seconds < 86400L -> { val h = seconds / 3600L; if (ru) "$h ч" else "$h h" }
    else -> { val d = seconds / 86400L; if (ru) "$d дн" else "$d d" }
}

private data class FavoriteTfResult(val forecast: Forecast?, val candles: List<Candle>)

@Composable
private fun FavoriteAnalyticsCard(symbol: String, ru: Boolean, repo: MarketRepository, onOpen: (String) -> Unit) {
    val tfs = listOf("15M", "1H", "4H", "1D", "1W")
    var price by remember(symbol) { mutableStateOf(0.0) }
    var results by remember(symbol) { mutableStateOf<Map<String, FavoriteTfResult>>(emptyMap()) }
    var loading by remember(symbol) { mutableStateOf(true) }
    var updatedAt by remember(symbol) { mutableLongStateOf(0L) }

    suspend fun refreshAnalysis() {
        loading = true
        val quote = withContext(Dispatchers.IO) { runCatching { repo.quote(symbol) }.getOrNull() }
        val now = System.currentTimeMillis()
        val computed: List<Pair<String, FavoriteTfResult>> = coroutineScope {
            tfs.map { timeframe ->
                async(Dispatchers.IO) {
                    val pair = timeframePair(timeframe)
                    val raw = runCatching { repo.load(symbol, pair.first, pair.second) }.getOrDefault(emptyList())
                    val live = quote?.takeIf { it.isFinite() && it > 0.0 } ?: raw.lastOrNull()?.close ?: 0.0
                    val merged = mergeRealtimeCandle(raw, live, timeframe, now, symbol)
                    val forecast = if (merged.size >= 30 && live > 0.0) {
                        runCatching { withContext(Dispatchers.Default) { AnalyticsEngine.analyze(merged, live) } }.getOrNull()
                    } else null
                    timeframe to FavoriteTfResult(forecast, merged)
                }
            }.awaitAll()
        }
        results = computed.toMap()
        price = quote?.takeIf { it.isFinite() && it > 0.0 } ?: computed.lastOrNull()?.second?.candles?.lastOrNull()?.close ?: price
        updatedAt = now
        loading = false
    }
    LaunchedEffect(symbol) { refreshAnalysis() }
    LaunchedEffect(symbol) {
        while (true) {
            delay(10_000)
            val live = withContext(Dispatchers.IO) { runCatching { repo.quote(symbol) }.getOrNull() }
            if (live != null && live > 0.0) {
                price = live
                updatedAt = System.currentTimeMillis()
            }
        }
    }
    LaunchedEffect(symbol) {
        while (true) {
            delay(60_000)
            refreshAnalysis()
        }
    }

    val mini = results["15M"]?.candles.orEmpty()
    val previous = mini.dropLast(1).lastOrNull()?.close ?: mini.lastOrNull()?.open ?: price
    val changePct = if (previous > 0.0 && price > 0.0) (price - previous) / previous * 100.0 else 0.0
    val metaName = remember(symbol) { symbol.removeSuffix(".ME").removeSuffix("=X") }
    GradientCard(Modifier.width(326.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(metaName, fontSize = 17.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                Text(symbol, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (price > 0) fmt(price) else "—", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Accent)
                Text(String.format(Locale.US, "%+.2f%%", changePct), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = if (changePct >= 0) Positive else Negative)
            }
        }
        if (loading && mini.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(86.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = Accent) }
        } else {
        }
        Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (ru) "ПРОГНОЗ • ВЕРОЯТНОСТЬ" else "FORECAST • PROBABILITY", fontSize = 7.sp, fontWeight = FontWeight.Black, color = Accent, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            if (updatedAt > 0L) Text(if (ru) "АКТУАЛЬНО" else "LIVE", fontSize = 7.sp, fontWeight = FontWeight.Black, color = Positive)
        }
        Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            tfs.forEach { tf ->
                val f = results[tf]?.forecast
                val c = forecastTimeframeColor(f)
                val label = when {
                    f == null -> "$tf • …"
                    f.signal.contains("SHORT") -> "$tf • S ${f.confidence}%"
                    f.signal.contains("LONG") -> "$tf • L ${f.confidence}%"
                    else -> "$tf • ${f.confidence}%"
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.copy(alpha = if (f == null) .06f else .13f))
                        .border(1.dp, c.copy(alpha = if (f == null) .25f else .90f), RoundedCornerShape(10.dp))
                        .clickable { onOpen(tf) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = 7.sp, fontWeight = FontWeight.Black, color = c, textAlign = TextAlign.Center)
                }
            }
        }
        val best = results.entries.mapNotNull { (tf, x) -> x.forecast?.takeIf { it.signal != "NO TRADE" }?.let { tf to it } }.maxByOrNull { it.second.confidence }
        if (best != null) {
            Text(
                if (ru) "Лучший подтверждённый сценарий: ${best.first} • ${best.second.signal} • вероятность ${best.second.confidence}%" else "Best confirmed scenario: ${best.first} • ${best.second.signal} • probability ${best.second.confidence}%",
                fontSize = 8.sp, fontWeight = FontWeight.Bold, color = signalColor(best.second.signal), modifier = Modifier.padding(top = 8.dp)
            )
        } else if (!loading) {
            Text(if (ru) "Нет подтверждённого входа — NO TRADE" else "No confirmed entry — NO TRADE", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Warning, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun forecastTimeframeColor(f: Forecast?): Color = when {
    f == null -> Blue
    f.signal.contains("SHORT") -> Negative
    f.signal.contains("LONG") -> Positive
    else -> Warning
}

@Composable private fun GradientIcon() { Box(Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Accent, Accent2))), contentAlignment = Alignment.Center) { Icon(Icons.Default.AutoGraph, null, tint = Color.White) } }
@Composable private fun SourceBadge(label: String, connected: Boolean) {
    Row(Modifier.padding(top = 5.dp).clip(RoundedCornerShape(8.dp)).background((if (connected) Positive else Negative).copy(alpha = .12f)).border(1.dp, (if (connected) Positive else Negative).copy(alpha = .45f), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (connected) Positive else Negative))
        Text("  $label", fontSize = 7.sp, fontWeight = FontWeight.Black, color = if (connected) Positive else Negative)
    }
}

@Composable
private fun AnalyticsHeroCard(state: MarketState, ru: Boolean, onOpen: () -> Unit) {
    val f = state.forecast
    GradientCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (ru) "АНАЛИТИЧЕСКИЙ ЦЕНТР" else "ANALYTICS CENTER", fontSize = 8.sp, fontWeight = FontWeight.Black, color = Accent, letterSpacing = 1.4.sp)
                Text(state.meta.shortName.ifBlank { state.symbol }, fontSize = 19.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 3.dp))
                Text(state.symbol, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SourceBadge(state.dataSource.ifBlank { "БКС • единственный источник" }, state.dataSource.startsWith("БКС •"))
            }
            if (f != null) {
                SignalPill(f.signal, signalColor(f.signal))
                Spacer(Modifier.width(9.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${f.confidence}%", fontSize = 25.sp, fontWeight = FontWeight.Black, color = Accent)
                    Text(if (ru) "уверенность" else "confidence", fontSize = 7.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (f != null) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                MetricCard(if (ru) "Цена" else "Price", fmt(state.livePrice), Accent, Modifier.weight(1f))
                MetricCard(if (ru) "Риск/прибыль" else "R/R", "%.2f".format(Locale.US, f.rr), Warning, Modifier.weight(1f))
                MetricCard(if (ru) "Данные" else "Data", "${f.dataQuality}/100", Positive, Modifier.weight(1f))
                MetricCard(if (ru) "Режим рынка" else "Regime", f.regime.replace("TREND_", ""), Blue, Modifier.weight(1f))
            }
            Text(if (ru) "Открыть полный анализ →" else "Open full analysis →", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.padding(top = 11.dp))
        } else {
            Loading(ru)
        }
    }
}

@Composable private fun MarketPickCard(x: MarketPick, ru:Boolean, onOpen:()->Unit) { GradientCard(Modifier.width(190.dp).clickable(onClick=onOpen)) { Text(x.name.ifBlank{x.symbol},fontWeight=FontWeight.Black,fontSize=12.sp); Text(x.symbol.removeSuffix(".ME"),fontSize=8.sp,color=MaterialTheme.colorScheme.onSurfaceVariant); Text(fmt(x.price),fontSize=18.sp,fontWeight=FontWeight.Black,color=Accent,modifier=Modifier.padding(top=4.dp)); Text(String.format(Locale.US, "%+.2f%%", x.changePct), color = if (x.changePct >= 0) Positive else Negative, fontSize = 9.sp, fontWeight = FontWeight.Bold); Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){SignalPill(x.signal,signalColor(x.signal));Text("${x.confidence}%",color=Accent,fontWeight=FontWeight.Black,fontSize=10.sp)} } }
@Composable private fun IndexCard(x: MarketIndex) { GradientCard(Modifier.width(150.dp)) { Text(x.name, fontWeight = FontWeight.Bold, fontSize = 11.sp); Text(fmt(x.price), fontSize = 17.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 4.dp)); Text(String.format(Locale.US, "%+.2f%%", x.changePct), color = if (x.changePct >= 0) Positive else Negative, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(x.source, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun FavoriteChip(sym: String, repo: MarketRepository, ru: Boolean, onOpen: () -> Unit) { var p by remember(sym) { mutableStateOf<Double?>(null) }; LaunchedEffect(sym) { p = withContext(Dispatchers.IO) { runCatching { repo.quote(sym) }.getOrNull() } }; GradientCard(Modifier.width(150.dp).clickable(onClick = onOpen)) { Text(sym.removeSuffix(".ME"), fontWeight = FontWeight.Black); Text(if (p != null) fmt(p!!) else "—", fontSize = 17.sp, color = Accent, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 4.dp)); Text(if (ru) "Открыть прогноз →" else "Open forecast →", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun MarketCard(state: MarketState, ru: Boolean, prefs: android.content.SharedPreferences, onOpen: () -> Unit) { GradientCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(state.meta.shortName.ifBlank { state.symbol }, fontWeight = FontWeight.Black); Text(state.symbol, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(if (state.livePrice > 0) CurrencyDisplay.format(state.livePrice, state.meta.currency, prefs) else "—", fontSize = 27.sp, fontWeight = FontWeight.Black, color = Accent) }; state.forecast?.let { SignalPill(it.signal, signalColor(it.signal)) } }; state.forecast?.let { Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) { StatBlock(if (ru) "Уверенность" else "Confidence", "${it.confidence}%", Accent); StatBlock(if (ru) "Оценка" else "Score", "%.1f".format(Locale.US, it.score), Blue); StatBlock(if (ru) "Риск/прибыль" else "R/R", "%.2f".format(Locale.US, it.rr), Warning); StatBlock(if (ru) "Режим рынка" else "Regime", it.regime, Positive) } } } }

@Composable private fun Analysis(state: MarketState, ru: Boolean, tf: String, favorite: Boolean, favs: Set<String>, save: (Set<String>) -> Unit, onTf: (String) -> Unit, refresh: () -> Unit, track: () -> Boolean, tracked: List<TrackedForecast>, prefs: android.content.SharedPreferences) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf("FORECAST") }
    var tfForecasts by remember(state.symbol) { mutableStateOf<Map<String, Forecast?>>(emptyMap()) }
    LaunchedEffect(state.symbol, state.updated) {
        if (state.symbol.isBlank()) return@LaunchedEffect
        val currentLive = state.livePrice.takeIf { it > 0.0 }
        val tfs = listOf("15M", "1H", "4H", "1D", "1W")
        tfForecasts = withContext(Dispatchers.IO) {
            tfs.mapNotNull { timeframe ->
                val pair = timeframePair(timeframe)
                val c = runCatching { MarketRepository(bcsRefreshToken = prefs.getString("bcs_refresh_token", "")?.ifBlank { null }, prefs = prefs, context = ctx).load(state.symbol, pair.first, pair.second) }.getOrNull()
                val live = currentLive ?: c?.lastOrNull()?.close
                val merged = if (c != null && live != null && live > 0.0) mergeRealtimeCandle(c, live, timeframe, System.currentTimeMillis(), state.symbol) else c.orEmpty()
                val f = if (merged.size >= 30) runCatching { AnalyticsEngine.analyze(merged, live) }.getOrNull() else null
                timeframe to f
            }.toMap()
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(11.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(state.meta.shortName.ifBlank { state.symbol }, fontSize = 22.sp, fontWeight = FontWeight.Black); Text("${state.symbol} • ${state.meta.currency}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(if (state.livePrice > 0) CurrencyDisplay.format(state.livePrice, state.meta.currency, prefs) else "—", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Accent); SourceBadge(state.dataSource.ifBlank { "БКС • единственный источник" }, state.dataSource.startsWith("БКС •")) }; IconButton({ save(if (favorite) favs - state.symbol else favs + state.symbol) }) { Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder, null, tint = Warning) }; IconButton(refresh) { Icon(Icons.Default.Refresh, null, tint = Accent) } } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("OVERVIEW", "FORECAST", "FINANCE").forEach { FilterChip(selected = tab == it, onClick = { tab = it }, label = { Text(if (ru) when (it) { "OVERVIEW" -> "Обзор"; "FORECAST" -> "Прогноз"; else -> "Финансы" } else it, fontSize = 8.sp) }) } } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("15M", "1H", "4H", "1D", "1W").forEach { timeframe ->
                val tfForecast = if (timeframe == tf && state.forecast != null) state.forecast else tfForecasts[timeframe]
                val tc = forecastTimeframeColor(tfForecast)
                val label = when (tfForecast?.signal) {
                    "LONG" -> "$timeframe • L ${tfForecast.confidence}%"
                    "SHORT" -> "$timeframe • S ${tfForecast.confidence}%"
                    "NO TRADE" -> "$timeframe • N ${tfForecast.confidence}%"
                    else -> timeframe
                }
                FilterChip(selected = tf == timeframe, onClick = { onTf(timeframe) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = tc.copy(alpha = .18f), selectedLabelColor = tc, containerColor = Color.Transparent, labelColor = tc.copy(alpha = .82f), disabledContainerColor = Color.Transparent), label = { Text(label, fontSize = 8.sp, fontWeight = FontWeight.Black) })
            }
        } }
        if (state.loading) item { Loading(ru) } else if (state.forecast != null) {
            val f = state.forecast
            when (tab) {
                "OVERVIEW" -> { item { ForecastSummary(f, ru) }; item { MaxIntelligenceCard(f, ru) }; item { Scenario(f, ru) }; item { DataQuality(f, ru) }; item { Explanation(f, ru) }; item { IndicatorCoverage(f, ru) } }
                "FINANCE" -> { item { ProfitCalculatorInline(f, ru, prefs) }; item { RiskCard(f, ru, prefs) } }
                else -> {
                    item { Button({ track() }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Icon(Icons.Default.NotificationsActive, null); Spacer(Modifier.width(8.dp)); Text(if (ru) "Отслеживать прогноз" else "Track forecast", fontWeight = FontWeight.Black) } }
                    item { Levels(f, ru, prefs, state.meta.currency) }; item { MaxIntelligenceCard(f, ru) }; item { Scenario(f, ru) }; item { RiskCard(f, ru, prefs) }; item { ProfitPreview(f, ru) }; item { Explanation(f, ru) }; item { DataQuality(f, ru) }; item { AccuracyCard(state.candles, ru) }; item { IndicatorCoverage(f, ru) }; item { TrackedForSymbol(tracked, state.symbol, ru) }
                }
            }
        } else item { ErrorCard(state.error ?: "Нет данных", ru) }
    }
}

private data class PriceMapper(val minPrice: Double, val maxPrice: Double, val top: Float, val bottom: Float) {
    private val span = (maxPrice - minPrice).coerceAtLeast(1e-12)
    fun priceToY(price: Double): Float = top + ((maxPrice - price) / span).coerceIn(0.0, 1.0).toFloat() * (bottom - top)
    fun yToPrice(y: Float): Double = maxPrice - ((y - top) / (bottom - top).coerceAtLeast(1f)).coerceIn(0f, 1f) * span
}

private fun priceText(price: Double): String {
    if (!price.isFinite()) return "—"
    val absPrice = kotlin.math.abs(price)
    val decimals = when {
        absPrice >= 1.0 -> 2
        absPrice >= 0.01 -> 4
        else -> 6
    }
    return String.format(Locale.US, "%.${decimals}f", price)
}

private fun niceStep(raw: Double): Double {
    if (!raw.isFinite() || raw <= 0.0) return 1.0
    val p = 10.0.pow(kotlin.math.floor(log10(raw)))
    val n = raw / p
    val base = when { n <= 1.0 -> 1.0; n <= 2.0 -> 2.0; n <= 5.0 -> 5.0; else -> 10.0 }
    return base * p
}

@Composable private fun ForecastSummary(f: Forecast, ru: Boolean) {
    GradientCard(Modifier.fillMaxWidth()) {
        SectionHeader(if (ru) "Итоговый прогноз" else "Final forecast", if (ru) "Сводка ансамбля технических сигналов" else "Ensemble technical-signal summary")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SignalPill(f.signal, signalColor(f.signal))
            Text("${f.confidence}%", fontSize = 25.sp, fontWeight = FontWeight.Black, color = Accent)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard(if (ru) "Тренд" else "Trend", "%.1f".format(Locale.US, f.trend), Blue, Modifier.weight(1f))
            MetricCard(if (ru) "Импульс" else "Momentum", "%.1f".format(Locale.US, f.momentum), Accent, Modifier.weight(1f))
            MetricCard(if (ru) "Качество" else "Quality", "${f.dataQuality}/100", Positive, Modifier.weight(1f))
        }
    }
}

@Composable private fun Levels(f: Forecast, ru: Boolean, prefs: android.content.SharedPreferences, sourceCurrency: String) { GradientCard(Modifier.fillMaxWidth()) { SectionHeader(if (ru) "Ключевые уровни" else "Key levels", if (ru) "SL ограничен движком риска; TP построены по соотношению риск/прибыль и структуре" else "SL is risk-engine constrained; TP uses R/R and structure"); LevelRow(if (ru) "Вход" else "Entry", CurrencyDisplay.convert(f.entry, sourceCurrency, prefs.getString("display_currency", "RUB") ?: "RUB", CurrencyDisplay.rates(prefs)) ?: f.entry, Accent); LevelRow("SL", CurrencyDisplay.convert(f.stopAggressive, sourceCurrency, prefs.getString("display_currency", "RUB") ?: "RUB", CurrencyDisplay.rates(prefs)) ?: f.stopAggressive, Negative); LevelRow("SL", CurrencyDisplay.convert(f.stopOptimal, sourceCurrency, prefs.getString("display_currency", "RUB") ?: "RUB", CurrencyDisplay.rates(prefs)) ?: f.stopOptimal, Negative); LevelRow("SL", CurrencyDisplay.convert(f.stopConservative, sourceCurrency, prefs.getString("display_currency", "RUB") ?: "RUB", CurrencyDisplay.rates(prefs)) ?: f.stopConservative, Negative); LevelRow("TP1", CurrencyDisplay.convert(f.tp1, sourceCurrency, prefs.getString("display_currency", "RUB") ?: "RUB", CurrencyDisplay.rates(prefs)) ?: f.tp1, Positive); LevelRow("TP2", CurrencyDisplay.convert(f.tp2, sourceCurrency, prefs.getString("display_currency", "RUB") ?: "RUB", CurrencyDisplay.rates(prefs)) ?: f.tp2, Positive); LevelRow("TP3", CurrencyDisplay.convert(f.tp3, sourceCurrency, prefs.getString("display_currency", "RUB") ?: "RUB", CurrencyDisplay.rates(prefs)) ?: f.tp3, Positive); LevelRow(if (ru) "Поддержки" else "Support", CurrencyDisplay.convert(f.support1, sourceCurrency, prefs.getString("display_currency", "RUB") ?: "RUB", CurrencyDisplay.rates(prefs)) ?: f.support1, Blue); LevelRow(if (ru) "Сопротивление" else "Resistance", CurrencyDisplay.convert(f.resistance1, sourceCurrency, prefs.getString("display_currency", "RUB") ?: "RUB", CurrencyDisplay.rates(prefs)) ?: f.resistance1, Warning) } }
@Composable private fun Scenario(f: Forecast, ru: Boolean) { GradientCard(Modifier.fillMaxWidth()) { SectionHeader(if (ru) "Вероятность сценариев" else "Scenario probability", "LONG / NO TRADE / SHORT"); ScenarioRow(if (ru) "Бычий" else "Bullish", f.bull, Positive); ScenarioRow(if (ru) "Нейтральный" else "Neutral", f.base, Warning); ScenarioRow(if (ru) "Медвежий" else "Bearish", f.bear, Negative) } }
@Composable private fun ScenarioRow(label: String, value: Int, color: Color) { Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), fontSize = 10.sp); LinearProgressIndicator(progress = { value / 100f }, modifier = Modifier.width(100.dp), color = color); Text("$value%", Modifier.width(42.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold) } }
@Composable private fun RiskCard(f: Forecast, ru: Boolean, prefs: android.content.SharedPreferences) { var risk by remember { mutableStateOf(prefs.getString("risk_pct", "1.0") ?: "1.0") }; val riskPct = risk.replace(',', '.').toDoubleOrNull()?.coerceIn(.1, 5.0) ?: 1.0; val capital = (prefs.getString("capital", "10000") ?: "10000").replace(',', '.').toDoubleOrNull() ?: 10000.0; val loss = capital * riskPct / 100.0; val distancePct = f.expectedLossPct; val qty = if (f.entry > 0 && distancePct > 0) loss / (f.entry * distancePct / 100.0) else 0.0; GradientCard(Modifier.fillMaxWidth()) { SectionHeader(if (ru) "Движок риска" else "Risk Engine", if (ru) "Размер позиции рассчитывается от допустимого денежного риска" else "Position size is based on allowed monetary risk"); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { MetricCard(if (ru) "Риск" else "Risk", "%.2f%%".format(Locale.US, riskPct), Warning, Modifier.weight(1f)); MetricCard("SL", "%.2f%%".format(Locale.US, distancePct), Negative, Modifier.weight(1f)); MetricCard(if (ru) "Риск" else "Loss", fmt(loss), Negative, Modifier.weight(1f)); MetricCard(if (ru) "Количество" else "Qty", "%.2f".format(Locale.US, qty), Accent, Modifier.weight(1f)) } } }
@Composable private fun ProfitPreview(f: Forecast, ru: Boolean) { GradientCard(Modifier.fillMaxWidth()) { SectionHeader(if (ru) "Потенциальная прибыль" else "Potential profit", if (ru) "Расчёт относительно входа, TP2 и SL" else "Calculated from Entry, TP2 and SL"); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { StatBlock("TP2", "+%.2f%%".format(Locale.US, f.expectedProfitPct), Positive); StatBlock("SL", "-%.2f%%".format(Locale.US, f.expectedLossPct), Negative); StatBlock(if (ru) "Риск/прибыль" else "R/R", "%.2f".format(Locale.US, f.rr), Warning) } } }
@Composable private fun MaxIntelligenceCard(f: Forecast, ru: Boolean) {
    GradientCard(Modifier.fillMaxWidth()) {
        SectionHeader(if (ru) "Глубокая аналитика" else "MAX INTELLIGENCE", if (ru) "Режим рынка, структура, таймфреймы, ликвидность, цели и математическое ожидание" else "Market regime, structure, timeframes, liquidity, targets and expected value")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            MetricCard("LONG", "${f.bull}%", Positive, Modifier.weight(1f))
            MetricCard("SHORT", "${f.bear}%", Negative, Modifier.weight(1f))
            MetricCard(if (ru) "Преимущество" else "Edge", String.format(Locale.US, "%+.0f", f.edgeGap * 100) + " п.п.", Accent, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricCard("TP1", "${(f.tp1Probability * 100).roundToInt()}%", Positive, Modifier.weight(1f))
            MetricCard("TP2", "${(f.tp2Probability * 100).roundToInt()}%", Positive, Modifier.weight(1f))
            MetricCard("TP3", "${(f.tp3Probability * 100).roundToInt()}%", Positive, Modifier.weight(1f))
            MetricCard("SL", "${(f.stopProbability * 100).roundToInt()}%", Negative, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(if (ru) "Режим рынка: ${regimeRu(f.regime)}" else "Market regime: ${f.regime}", fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(if (ru) "Согласованность таймфреймов: ${String.format(Locale.US, "%.0f", f.mtfHierarchy * 100)}%" else "Timeframe consensus: ${String.format(Locale.US, "%.0f", f.mtfHierarchy * 100)}%", fontSize = 9.sp)
        Text(if (ru) "Ликвидность: ${String.format(Locale.US, "%.0f", f.liquidityScore * 100)}%" else "Liquidity: ${String.format(Locale.US, "%.0f", f.liquidityScore * 100)}%", fontSize = 9.sp)
        Text(if (ru) "Риск у поддержки/сопротивления: ${String.format(Locale.US, "%.0f", f.srRiskPenalty)}" else "Support/resistance risk: ${String.format(Locale.US, "%.0f", f.srRiskPenalty)}", fontSize = 9.sp)
        Text(if (ru) "Качество входа: ${String.format(Locale.US, "%.0f", f.entryQuality * 100)}%" else "Entry quality: ${String.format(Locale.US, "%.0f", f.entryQuality * 100)}%", fontSize = 9.sp)
        Text(if (ru) "Математическое ожидание: ${String.format(Locale.US, "%+.2fR", f.expectedValueR)}" else "Expected value: ${String.format(Locale.US, "%+.2fR", f.expectedValueR)}", fontSize = 10.sp, fontWeight = FontWeight.Black, color = if (f.expectedValueR >= 0) Positive else Negative, modifier = Modifier.padding(top = 3.dp))
        Text(if (ru) "Зона входа: ${fmt(f.entryZoneLow)} — ${fmt(f.entryZoneHigh)}" else "Entry zone: ${fmt(f.entryZoneLow)} — ${fmt(f.entryZoneHigh)}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun regimeRu(regime: String): String = when (regime) {
    "TREND_UP" -> "восходящий тренд"
    "TREND_DOWN" -> "нисходящий тренд"
    "RANGE" -> "боковой диапазон"
    "TRANSITION" -> "переходный режим"
    "HIGH_VOLATILITY" -> "высокая волатильность"
    else -> "не определён"
}

@Composable private fun Explanation(f: Forecast, ru: Boolean) { GradientCard(Modifier.fillMaxWidth()) { SectionHeader(if (ru) "Почему такой прогноз" else "Why this forecast", if (ru) "EMA • RSI • MACD • ATR • ADX • Стохастик • Объём • Структура • Таймфреймы" else "EMA • RSI • MACD • ATR • ADX • Stochastic • Volume • Structure • MTF"); f.explanation.forEach { Text("• $it", fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp)) } } }
@Composable private fun DataQuality(f: Forecast, ru: Boolean) { GradientCard(Modifier.fillMaxWidth()) { Text(if (ru) "Качество данных" else "Data quality", fontWeight = FontWeight.Black); LinearProgressIndicator(progress = { f.dataQuality / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 9.dp), color = Positive); Text("${f.dataQuality}/100", fontWeight = FontWeight.Black, color = Positive, modifier = Modifier.padding(top = 5.dp)) } }
@Composable private fun AccuracyCard(c: List<Candle>, ru: Boolean) { var r by remember(c) { mutableStateOf<Pair<Int, Int>?>(null) }; LaunchedEffect(c) { r = withContext(Dispatchers.Default) { AnalyticsEngine.backtest(c) } }; GradientCard(Modifier.fillMaxWidth()) { SectionHeader(if (ru) "Backtest" else "Backtest", if (ru) "Только на доступной фактический истории" else "Only on available market history"); r?.let { Text(if (it.second == 0) "—" else "${it.first * 100 / it.second}%", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Accent); Text("${it.first} / ${it.second}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun IndicatorCoverage(f: Forecast, ru: Boolean) { GradientCard(Modifier.fillMaxWidth()) { SectionHeader(if (ru) "Состав модели" else "Model coverage", if (ru) "Ансамбль + адаптивные веса" else "Ensemble + adaptive weights"); Text("EMA20/50/200 • RSI • MACD • ATR • ADX/DMI • Stochastic • Stoch RSI • Momentum • ROC • Bollinger • VWAP • Volume • OBV • MFI • CMF • CCI • Williams %%R • Ichimoku • Donchian • Volatility • Price Structure • Multi-TF • Walk-forward Edge • Regime", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant);
        Text("${if (ru) "Адаптивный режим" else "Adaptive regime"}: ${f.regime} • Edge ${String.format(Locale.US, "%.0f", f.historicalEdge * 100)}% • Gap ${String.format(Locale.US, "%.1f", f.edgeGap * 100)} п.п. • Confirm ${f.confirmation}/5", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (f.highConviction) Positive else Warning, modifier = Modifier.padding(top = 5.dp)) } }
@Composable private fun NewsPreview(news: List<NewsItem>, ru: Boolean) { GradientCard(Modifier.fillMaxWidth()) { SectionHeader(if (ru) "Новости инструмента" else "Instrument news", ""); news.take(5).forEach { NewsRow(it) } } }
@Composable private fun TrackedForSymbol(tracked: List<TrackedForecast>, symbol: String, ru: Boolean) { val items = tracked.filter { it.symbol == symbol }.take(6); if (items.isEmpty()) return; GradientCard(Modifier.fillMaxWidth()) { SectionHeader(if (ru) "Отслеживание" else "Monitoring", "${items.size} • ${if (ru) "без дублей по TF" else "unique by timeframe"}"); items.forEach { t -> Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("${t.signal} • ${t.timeframe}", fontWeight = FontWeight.Bold, fontSize = 10.sp); Text(if (ru) "Вход ${fmt(t.entry)} • SL ${fmt(t.stop)} • TP1 ${fmt(t.tp1)}" else "Entry ${fmt(t.entry)} • SL ${fmt(t.stop)} • TP1 ${fmt(t.tp1)}", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Text(t.result, fontWeight = FontWeight.Black, fontSize = 9.sp, color = when(t.result){"PENDING"->Warning;"FLAT"->Warning;"FAIL"->Negative;else->Positive}) } } } }

@Composable private fun SearchScreen(q: String, setQ: (String) -> Unit, results: List<SearchResult>, loading: Boolean, ru: Boolean, favs: Set<String>, searchFilter: String, onFilterChange: (String) -> Unit, save: (Set<String>) -> Unit, pick: (SearchResult) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("mfprefs", Context.MODE_PRIVATE) }
    var searchHistory by remember { mutableStateOf(loadSearchHistory(prefs)) }
    val filtered = results.filter { matchesSearchFilter(it, searchFilter) }
    val prefix = q.trim()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            GradientCard(Modifier.fillMaxWidth()) {
                Text(if (ru) "🔎 Поиск инструмента" else "🔎 Instrument search", fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(
                    if (ru) "Начните с первой буквы — акции и валюты появятся сразу. Можно вводить русское или английское название/тикер."
                    else "Type the first letter — stocks and currencies appear immediately. Russian or English names/tickers are supported.",
                    fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                OutlinedTextField(
                    value = q,
                    onValueChange = setQ,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    singleLine = true,
                    placeholder = { Text(if (ru) "Например: с, s, циан, cian, usd…" else "For example: s, c, cian, usd…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (q.isNotEmpty()) IconButton({ setQ("") }) { Icon(Icons.Default.Close, if (ru) "Очистить" else "Clear") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                        if (q.trim().isNotEmpty()) searchHistory = saveSearchHistory(prefs, q.trim())
                    })
                )
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL","STOCK","FX").forEach { f ->
                    FilterChip(selected = searchFilter == f, onClick = { onFilterChange(f) }, label = { Text(if (ru) when(f){"STOCK"->"Акции";"FX"->"Валюты";else->"Все"} else when(f){"STOCK"->"Stocks";"FX"->"Currencies";else->"All"},fontSize=8.sp) })
                }
            }
        }
        if (prefix.isBlank() && searchHistory.isNotEmpty()) {
            item { SectionHeader(if (ru) "История поиска" else "Search history", if (ru) "Последние запросы" else "Recent searches") }
            items(searchHistory) { h ->
                Row(Modifier.fillMaxWidth().clickable { setQ(h) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Text(h, Modifier.padding(start = 10.dp), fontSize = 11.sp)
                }
            }
        }
        if (prefix.isNotBlank()) {
            item {
                SectionHeader(
                    if (ru) "Подсказки по «$prefix»" else "Suggestions for “$prefix”",
                    if (ru) "${filtered.size} найдено • совпадение с начала названия или тикера" else "${filtered.size} found • prefix match on name or ticker"
                )
            }
        }
        if (loading) item { Loading(ru) }
        if (!loading && prefix.isNotBlank() && filtered.isEmpty()) {
            item { EmptyCard(if (ru) "Ничего не найдено. Попробуйте другую букву, русское название или тикер." else "Nothing found. Try another letter, Russian name, or ticker.") }
        }
        items(filtered.take(50), key = { "${it.symbol}|${it.type}" }) { r ->
            val isFav = favs.contains(r.symbol)
            GradientCard(Modifier.fillMaxWidth().clickable { searchHistory = saveSearchHistory(prefs, prefix); pick(r) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(signalColor(if (r.type.contains("CURRENCY", true)) "LONG" else "NO TRADE").copy(alpha=.12f)),
                        contentAlignment = Alignment.Center
                    ) { Text(if (r.type.contains("CURRENCY", true)) "₽" else "▣", color = Accent, fontWeight = FontWeight.Black, fontSize = 16.sp) }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(r.name, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text(r.symbol.removeSuffix(".ME"), fontWeight = FontWeight.Black, fontSize = 10.sp, color = Accent)
                        Text("${r.exchange} • ${r.type} • ${r.source}", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ if (isFav) save(favs - r.symbol) else save(favs + r.symbol) }) { Icon(if (isFav) Icons.Default.Star else Icons.Default.StarBorder, null, tint = Warning) }
                }
            }
        }
    }
}

@Composable private fun Favorites(favs: List<String>, ru: Boolean, repo: MarketRepository, save: (Set<String>) -> Unit, load: (String) -> Unit, refreshTick: Long = 0L) {
    var sort by remember { mutableStateOf("PRICE_ASC") }
    var rows by remember(favs) { mutableStateOf(favs.map { FavoriteQuoteItem(it, null, 0) }) }
    LaunchedEffect(favs, refreshTick) {
        rows = withContext(Dispatchers.IO) { favs.map { s -> FavoriteQuoteItem(s, runCatching { repo.quote(s) }.getOrNull(), 0) }.toMutableList() }
        rows = rows.map { r ->
            val c = runCatching { repo.load(r.symbol, "5y", "1d") }.getOrNull()
            val live = r.price ?: c?.lastOrNull()?.close
            val merged = if (c != null && live != null && live > 0.0) mergeRealtimeCandle(c, live, "1D", System.currentTimeMillis(), r.symbol) else c
            val conf = if (merged != null && merged.size >= 30 && live != null && live > 0.0) runCatching { AnalyticsEngine.analyze(merged, live).confidence }.getOrDefault(0) else 0
            r.copy(price = live, confidence = conf)
        }
    }
    val sorted = when(sort) { "PRICE_DESC" -> rows.sortedByDescending { it.price ?: Double.NEGATIVE_INFINITY }; "CONF_DESC" -> rows.sortedByDescending { it.confidence }; else -> rows.sortedBy { it.price ?: Double.POSITIVE_INFINITY } }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { SectionHeader(if (ru) "⭐ Избранное" else "⭐ Favorites", if (ru) "Сортировка по цене и уверенности" else "Sort by price and confidence") }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("PRICE_ASC","PRICE_DESC","CONF_DESC").forEach { k -> FilterChip(selected=sort==k,onClick={sort=k},label={Text(if(ru) when(k){"PRICE_ASC"->"Цена ↑";"PRICE_DESC"->"Цена ↓";else->"Уверенность ↓"} else k,fontSize=8.sp)}) } } }
        if (sorted.isEmpty()) item { EmptyCard(if (ru) "Избранное пусто. Найди инструмент через Поиск." else "Favorites are empty. Find an instrument in Search.") }
        items(sorted, key={it.symbol}) { r ->
            GradientCard(Modifier.fillMaxWidth().clickable { load(r.symbol) }) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(r.symbol.removeSuffix(".ME"), fontWeight=FontWeight.Black, fontSize=16.sp); Text(if(ru) "Live цена • уверенность ${r.confidence}%" else "Live price • confidence ${r.confidence}%", fontSize=9.sp, color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(r.price?.let(::fmt) ?: "—", fontSize=18.sp, fontWeight=FontWeight.Black, color=Accent, modifier=Modifier.padding(end=6.dp))
                    IconButton({ save(favs.toSet()-r.symbol) }) { Icon(Icons.Default.Delete,null,tint=Negative) }
                }
            }
        }
    }
}




@Composable private fun History(history: List<HistoryEntry>, tracked: List<TrackedForecast>, ru: Boolean, repo: MarketRepository, prefs: android.content.SharedPreferences, load: (String) -> Unit, remove: (HistoryEntry) -> Unit, stats: () -> Unit, initialTab: String = "TRACKING", onTab: (String) -> Unit = {}, refreshTick: Long = 0L) {
    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    val pending=tracked.filter{it.result=="PENDING"}.sortedByDescending{it.createdAt}
    val done=history.count{it.directionOk!=null}; val wins=history.count{it.directionOk==true}
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
        item{SectionHeader(if(ru)"История и отслеживание" else "History & monitoring",if(ru)"Каждый прогноз получает отдельный горизонт и фиксированный результат" else "Every forecast gets its own horizon and final result")}
        // Tabs are intentionally not sticky: they scroll away with Tracking content.
        item{Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical=2.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("TRACKING","HISTORY","STATS").forEach{k->FilterChip(selected=tab==k,onClick={tab=k; onTab(k)},label={Text(if(ru)when(k){"TRACKING"->"Отслеживание";"HISTORY"->"Результаты";else->"Статистика"}else when(k){"TRACKING"->"Tracking";"HISTORY"->"Results";else->"Stats"},fontSize=9.sp)})}}}
        if(tab=="TRACKING"){
            item{GradientCard(Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){StatBlock(if(ru)"Активные" else "Active",pending.size.toString(),Accent);StatBlock(if(ru)"Завершены" else "Completed",tracked.count{it.result!="PENDING"}.toString(),Positive);StatBlock(if(ru)"Всего" else "Total",tracked.size.toString(),Blue)}}}
            if(pending.isEmpty()) item{EmptyCard(if(ru)"Активных отслеживаний нет. Добавь прогноз через кнопку «Отслеживать прогноз»." else "No active tracking. Add a forecast with Track forecast.")}
            items(pending,key={it.id}){t->TrackingRow(t,ru,repo,prefs,{load(t.symbol)},refreshTick)}
        } else if(tab=="HISTORY"){
            item{GradientCard(Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){StatBlock(if(ru)"Всего" else "Total",history.size.toString(),Accent);StatBlock(if(ru)"Завершено" else "Completed",done.toString(),Warning);StatBlock(if(ru)"Точность" else "Accuracy",if(done==0)"—" else "${wins*100/done}%",Positive)}}}
            items(history,key={"${it.symbol}|${it.time}"}){h->HistoryRow(h,ru,repo,tracked,{load(h.symbol)},{remove(h)})}
        } else {
            item{Button(onClick=stats,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Accent)){Text(if(ru)"Открыть подробную статистику" else "Open detailed statistics",fontWeight=FontWeight.Black)}}
            item{Text(if(ru)"Точность считается только по завершённым прогнозам. Ожидающие не искажают статистику." else "Accuracy uses completed forecasts only. Pending items do not distort statistics.",fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}

@Composable private fun TrackingRow(t: TrackedForecast, ru: Boolean, repo: MarketRepository, prefs: android.content.SharedPreferences, open: () -> Unit, refreshTick: Long = 0L) {
    var live by remember(t.id) { mutableStateOf(t.lastLivePrice.takeIf { it > 0.0 }) }
    var now by remember(t.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(t.id, t.lastUpdated, refreshTick) {
        live = t.lastLivePrice.takeIf { it > 0.0 }
        now = if (t.lastUpdated > 0L) t.lastUpdated else System.currentTimeMillis()
    }
    val current = live?.takeIf { it > 0.0 }
    val longSide = t.signal.contains("LONG", ignoreCase = true)
    val elapsed = (now - t.createdAt).coerceAtLeast(0L)
    val remaining = (t.checkAt - now).coerceAtLeast(0L)
    val movePct = if (current != null && t.entry > 0) {
        if (longSide) (current - t.entry) / t.entry * 100.0 else (t.entry - current) / t.entry * 100.0
    } else null
    val sourceCurrency = if (t.symbol.endsWith("=X", ignoreCase = true)) "RUB" else "RUB"
    val pnlText = current?.let { CurrencyDisplay.pnl(t.entry, it, sourceCurrency, longSide, prefs) }
    val riskDistance = abs(t.entry - t.stop).coerceAtLeast(1e-9)
    val tp1Distance = abs(t.tp1 - t.entry).coerceAtLeast(1e-9)
    val progressToTp1 = if (current != null) {
        val raw = if (longSide) (current - t.entry) / tp1Distance else (t.entry - current) / tp1Distance
        raw.coerceIn(0.0, 1.0)
    } else 0.0
    val slReached = current != null && if (longSide) current <= t.stop else current >= t.stop
    val tp1Reached = current != null && if (longSide) current >= t.tp1 else current <= t.tp1
    val status = when {
        slReached -> if (ru) "⚠ SL достигнут" else "⚠ SL reached"
        tp1Reached -> if (ru) "✓ TP1 достигнут" else "✓ TP1 reached"
        movePct != null && movePct > 0 -> if (ru) "В направлении прогноза" else "Moving with forecast"
        movePct != null && movePct < 0 -> if (ru) "Против прогноза" else "Against forecast"
        else -> if (ru) "Ожидание движения" else "Waiting for move"
    }
    val statusColor = when {
        slReached -> Negative
        tp1Reached || (movePct != null && movePct > 0) -> Positive
        movePct != null && movePct < 0 -> Negative
        else -> Warning
    }
    GradientCard(Modifier.fillMaxWidth().clickable(onClick = open)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.symbol.removeSuffix(".ME"), fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text("${t.signal} • ${t.confidence}% • ${t.timeframe}", fontSize = 9.sp, color = signalColor(t.signal))
                Text(if (ru) "Добавлено: ${dateTime(t.createdAt)}" else "Added: ${dateTime(t.createdAt)}", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (remaining > 0) {
                        if (ru) "Прошло ${formatElapsed(elapsed)} • до результата ${formatElapsed(remaining)}" else "Elapsed ${formatElapsed(elapsed)} • result in ${formatElapsed(remaining)}"
                    } else {
                        if (ru) "Горизонт достигнут • фиксируем результат" else "Horizon reached • finalizing result"
                    },
                    fontSize = 9.sp, color = if (remaining > 0) Warning else Accent
                )
                Spacer(Modifier.height(5.dp))
                Text(status, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = statusColor)
                if (current != null) {
                    LinearProgressIndicator(progress = { progressToTp1.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text(
                        if (ru) "До TP1: ${(progressToTp1 * 100).roundToInt()}% • движение ${if (movePct!! >= 0) "+" else ""}${"%.2f".format(Locale.US, movePct)}%"
                        else "To TP1: ${(progressToTp1 * 100).roundToInt()}% • move ${if (movePct!! >= 0) "+" else ""}${"%.2f".format(Locale.US, movePct)}%",
                        fontSize = 8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(if (pnlText == null) "—" else if (ru && pnlText.startsWith("+")) "Прибыль: $pnlText" else if (ru && (pnlText.startsWith("−") || pnlText.startsWith("-"))) "Убыток: $pnlText" else pnlText, fontSize = 10.sp, fontWeight = FontWeight.Black, color = if (pnlText?.startsWith("+") == true) Positive else if (pnlText?.startsWith("−") == true || pnlText?.startsWith("-") == true) Negative else Warning)
                    Text(
                        if (ru) "Entry ${CurrencyDisplay.format(t.entry, sourceCurrency, prefs)} • SL ${CurrencyDisplay.format(t.stop, sourceCurrency, prefs)} • TP1 ${CurrencyDisplay.format(t.tp1, sourceCurrency, prefs)} • TP2 ${CurrencyDisplay.format(t.tp2, sourceCurrency, prefs)} • TP3 ${CurrencyDisplay.format(t.tp3, sourceCurrency, prefs)}"
                        else "Entry ${fmt(t.entry)} • SL ${fmt(t.stop)} • TP1 ${fmt(t.tp1)} • TP2 ${fmt(t.tp2)} • TP3 ${fmt(t.tp3)}",
                        fontSize = 8.sp
                    )
                    val hitText = hitSummary(t).ifBlank { if (ru) "нет достигнутых уровней" else "no levels reached" }
                    Text(if (ru) "События: $hitText" else "Events: $hitText", fontSize = 8.sp, color = if (hitSummary(t).isNotBlank()) Positive else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (ru) "Запас до SL: ${"%.2f".format(Locale.US, abs(current - t.stop) / riskDistance * 100.0)}% от дистанции риска"
                        else "SL buffer: ${"%.2f".format(Locale.US, abs(current - t.stop) / riskDistance * 100.0)}% of risk distance",
                        fontSize = 7.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(if (ru) "АКТУАЛЬНО: ожидание текущей котировки…" else "LIVE: waiting for a live quote…", fontSize = 8.sp, color = Warning)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(current?.let(::fmt) ?: "—", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Accent)
                Text(if (ru) "АКТУАЛЬНО" else "LIVE", fontSize = 7.sp, color = Positive)
            }
        }
    }
}

private data class HistoryPnl(val amount: Double, val percent: Double)

private fun historyPnl(h: HistoryEntry): HistoryPnl? {
    val entry = h.price
    val close = h.closingPrice
    if (!entry.isFinite() || !close.isFinite() || entry <= 0.0 || close <= 0.0) return null
    val longSide = h.signal.contains("LONG", ignoreCase = true)
    val amount = if (longSide) close - entry else entry - close
    return HistoryPnl(amount, amount / entry * 100.0)
}

@Composable private fun HistoryPnlCard(h: HistoryEntry, ru: Boolean, prefs: android.content.SharedPreferences) {
    val pnl = historyPnl(h)
    val sourceCurrency = "RUB"
    val amountText = pnl?.let { CurrencyDisplay.pnl(h.price, h.closingPrice, sourceCurrency, h.signal.contains("LONG", true), prefs) } ?: "—"
    val percentText = pnl?.let { "${if (it.percent >= 0) "+" else "−"}${String.format(Locale.US, "%.2f", abs(it.percent))}%" } ?: "—"
    val positive = pnl?.amount?.let { it > 1e-12 } == true
    val negative = pnl?.amount?.let { it < -1e-12 } == true
    val tone = when { positive -> Positive; negative -> Negative; else -> Warning }
    val title = when { positive -> if (ru) "Прибыль" else "Profit"; negative -> if (ru) "Убыток" else "Loss"; else -> if (ru) "Без изменения" else "Flat" }
    val icon = when { positive -> Icons.Default.TrendingUp; negative -> Icons.Default.TrendingDown; else -> Icons.Default.Remove }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = tone.copy(alpha = 0.10f),
        border = androidx.compose.foundation.BorderStroke(1.3.dp, tone.copy(alpha = 0.72f)),
        shadowElevation = 8.dp
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = tone.copy(alpha = 0.18f)) {
                Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.padding(8.dp).size(22.dp))
            }
            Column(Modifier.padding(start = 11.dp).weight(1f)) {
                Text(title, color = tone, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                Text(if (pnl == null) "—" else if (positive) "${if (ru) "Прибыль: " else "Profit: "}$amountText" else if (negative) "${if (ru) "Убыток: " else "Loss: "}$amountText" else "${if (ru) "Без изменения: " else "Flat: "}$amountText", color = tone, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(if (ru) "$percentText • ${fmt(h.price)} → ${h.closingPrice.takeIf { it > 0 }?.let(::fmt) ?: "—"}" else "$percentText • ${fmt(h.price)} → ${h.closingPrice.takeIf { it > 0 }?.let(::fmt) ?: "—"}", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(percentText, color = tone, fontSize = 15.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable private fun HistoryRow(h: HistoryEntry, ru: Boolean, repo: MarketRepository, tracked: List<TrackedForecast>, open: () -> Unit, remove: () -> Unit) {
    // History is an immutable audit record. It must NEVER replace the recorded
    // closing price with today's live quote; live quotes belong only to Tracking.
    val elapsedEnd = if (h.result.isBlank()) System.currentTimeMillis() else (tracked.firstOrNull { it.symbol == h.symbol && it.createdAt == h.time }?.checkAt ?: h.time)
    val elapsed = (elapsedEnd - h.time).coerceAtLeast(0L)
    GradientCard(Modifier.fillMaxWidth().clickable(onClick = open)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(h.symbol.removeSuffix(".ME"), fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(h.time)), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${h.signal} • ${h.confidence}% • ${if (ru) "Цена при добавлении" else "Added price"}: ${fmt(h.price)} • ${h.timeframe}", fontSize = 9.sp)
                val close = h.closingPrice.takeIf { it > 0.0 }
                if (h.result.isNotBlank() && close != null) HistoryPnlCard(h, ru, LocalContext.current.getSharedPreferences("mfprefs", Context.MODE_PRIVATE))
                Text(
                    if (h.result.isBlank()) {
                        if (ru) "Ожидание результата • прошло ${formatElapsed(elapsed)}" else "Waiting • elapsed ${formatElapsed(elapsed)}"
                    } else {
                        val hits = h.hitSummary.ifBlank { "—" }
                        val pnl = historyPnl(h)
                        val pnlLabel = when {
                            pnl == null -> if (ru) "—" else "—"
                            pnl.amount > 1e-12 -> if (ru) "✓ Прибыль ${if (pnl.percent >= 0) "+" else "−"}${String.format(Locale.US, "%.2f", pnl.percent)}%" else "✓ Profit ${String.format(Locale.US, "%+.2f", pnl.percent)}%"
                            pnl.amount < -1e-12 -> if (ru) "✕ Убыток −${String.format(Locale.US, "%.2f", abs(pnl.percent))}%" else "✕ Loss −${String.format(Locale.US, "%.2f", abs(pnl.percent))}%"
                            else -> if (ru) "≈ Без изменения 0.00%" else "≈ Flat 0.00%"
                        }
                        if (ru) "$pnlLabel • события: $hits • прошло ${formatElapsed(elapsed)} • открытие ${fmt(h.price)} • закрытие ${close?.let(::fmt) ?: "—"}"
                        else "$pnlLabel • events: $hits • elapsed ${formatElapsed(elapsed)} • open ${fmt(h.price)} • close ${close?.let(::fmt) ?: "—"}"
                    },
                    fontSize=9.sp,
                    fontWeight = if (h.result.isNotBlank()) FontWeight.Bold else FontWeight.Normal,
                    color=if(historyPnl(h)?.amount?.let { it > 1e-12 } == true || h.result=="✓") Positive else if(historyPnl(h)?.amount?.let { it < -1e-12 } == true || h.result=="✕") Negative else Warning
                )
                if (close != null) Text(if (ru) "Зафиксированная цена завершения: ${fmt(close)}" else "Recorded closing price: ${fmt(close)}", fontSize = 8.sp, color = Accent)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (h.result=="✓") "✓" else if (h.result=="✕") "✕" else "•", color = if(h.result=="✓") Positive else if(h.result=="✕") Negative else Warning, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text(if (ru) "АРХИВ" else "ARCHIVE", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 7.sp)
            }
            IconButton(remove) { Icon(Icons.Default.Delete, if (ru) "Удалить запись" else "Delete record", tint = Negative) }
        }
    }
}

@Composable private fun NewsScreen(news: List<NewsItem>, ru: Boolean, refreshTick: Long = 0L, openArticle: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { SectionHeader(if (ru) "📰 Новости" else "📰 News", if (ru) "Открой новость внутри приложения: фото, текст и оригинальная страница без выхода из приложения" else "Open the full article inside the app with images and text") }
        if (news.isEmpty()) { item { EmptyCard(if (ru) "Лента новостей пока пуста. Источники будут повторно проверены автоматически." else "The news feed is empty. Sources will be retried automatically.") } }
        items(news, key = { it.url.ifBlank { it.title } }) { NewsCard(it, ru, openArticle) }
    }
}

@Composable private fun NewsCard(n: NewsItem, ru: Boolean, openArticle: (String) -> Unit) {
    GradientCard(Modifier.fillMaxWidth().clickable { if (n.url.isNotBlank()) openArticle(n.url) }) {
        Text(n.title, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        if (n.instrument.isNotBlank()) Text("${if (ru) "Инструмент" else "Instrument"}: ${n.instrument.removeSuffix(".ME")}", color = Accent, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
        if (n.body.isNotBlank()) Text(n.body.take(420), fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(n.publisher, color = Accent, fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))
        if (n.publishedAt > 0) Text(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(n.publishedAt)), fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (ru) "Открыть полностью →" else "Open full article →", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable private fun NewsRow(n: NewsItem) { Text(n.title, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp)) }

@Composable private fun NewsDetailScreen(url: String, ru: Boolean, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, null) }
            Text(if (ru) "Полная новость" else "Full article", fontWeight = FontWeight.Black)
        }
        if (url.isBlank()) {
            EmptyCard(if (ru) "Ссылка на новость недоступна." else "Article URL is unavailable.")
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = WebViewClient()
                    loadUrl(url)
                } }
            )
        }
    }
}

@Composable private fun DividendScreen(ru: Boolean, repo: MarketRepository, refreshTick: Long = 0L, back: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, null) }
            Column(Modifier.weight(1f)) {
                Text(if (ru) "Дивиденды" else "Dividends", fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("БКС Экспресс • интерактивный календарь", fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { loadFailed = false; webView?.reload() }) { Icon(Icons.Default.Refresh, null, tint = Accent) }
        }
        if (loadFailed) {
            GradientCard(Modifier.fillMaxWidth().padding(10.dp)) {
                Text(if (ru) "Не удалось загрузить страницу БКС" else "BCS page could not be loaded", color = Negative, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { loadFailed = false; webView?.loadUrl(MarketRepository.BCS_DIVIDEND_CALENDAR_URL) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(if (ru) "Повторить" else "Retry") }
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = false
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) { loadFailed = false }
                        override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) { if (request?.isForMainFrame != false) loadFailed = true }
                    }
                    loadUrl(MarketRepository.BCS_DIVIDEND_CALENDAR_URL)
                    webView = this
                }
                },
                update = { view: WebView -> webView = view }
            )
        }
    }
}

@Composable private fun ProfitCalculatorInline(f: Forecast, ru: Boolean, prefs: android.content.SharedPreferences) {
    val riskPct = (prefs.getString("risk_pct", "1.0") ?: "1.0").replace(',', '.').toDoubleOrNull()?.coerceIn(.1, 5.0) ?: 1.0
    val capital = (prefs.getString("capital", "10000") ?: "10000").replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 10000.0
    val cashRisk = capital * riskPct / 100.0; val unitRisk = abs(f.entry - f.stop); val qty = if (unitRisk > 0) cashRisk / unitRisk else 0.0; val profit = abs(f.tp2 - f.entry) * qty
    GradientCard(Modifier.fillMaxWidth()) { SectionHeader(if (ru) "Расчёт по текущему прогнозу" else "Current forecast calculation", if (ru) "Капитал ${fmt(capital)} • Риск ${"%.2f".format(Locale.US, riskPct)}%" else "Капитал ${fmt(capital)} • Риск ${"%.2f".format(Locale.US, riskPct)}%"); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { StatBlock(if (ru) "Количество" else "Qty", "%.2f".format(Locale.US, qty), Accent); StatBlock(if (ru) "Риск" else "Risk", fmt(cashRisk), Negative); StatBlock(if (ru) "Прибыль TP2" else "TP2 profit", fmt(profit), Positive); StatBlock(if (ru) "Риск/прибыль" else "R/R", "%.2f".format(Locale.US, f.rr), Warning) } }
}

@Composable private fun FinanceScreen(f: Forecast?, ru: Boolean) { val ctx=LocalContext.current; val prefs=remember{ctx.getSharedPreferences("mfprefs",Context.MODE_PRIVATE)}; var capital by remember { mutableStateOf(prefs.getString("capital", "10000") ?: "10000") }; var risk by remember { mutableStateOf(prefs.getString("risk_pct", "1") ?: "1") }; var entry by remember(f) { mutableStateOf(f?.entry?.takeIf { it.isFinite() && it > 0.0 }?.toString() ?: "") }; var tp by remember(f) { mutableStateOf(f?.tp2?.takeIf { it.isFinite() && it > 0.0 }?.toString() ?: "") }; var sl by remember(f) { mutableStateOf(f?.stop?.takeIf { it.isFinite() && it > 0.0 }?.toString() ?: "") }; val cap=capital.toDoubleOrNull()?:0.0; val rp=risk.replace(',','.').toDoubleOrNull()?.coerceIn(.1,5.0)?:1.0; val e=entry.replace(',','.').toDoubleOrNull()?:0.0; val t=tp.replace(',','.').toDoubleOrNull()?:0.0; val s=sl.replace(',','.').toDoubleOrNull()?:0.0; val loss=cap*rp/100; val riskPerUnit=abs(e-s); val qty=if(riskPerUnit>0)loss/riskPerUnit else 0.0; val profit=abs(t-e)*qty; val rr=if(riskPerUnit>0)abs(t-e)/riskPerUnit else 0.0; LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){item{SectionHeader(if(ru)"Расчёт прибыли и риска" else "Profit & risk calculator",if(ru)"Не инвестиционная гарантия; расчёт зависит от введённых параметров" else "Not an investment guarantee; depends on inputs")};item{CalcField(if(ru)"Капитал" else "Capital",capital){capital=it;prefs.edit().putString("capital",it).apply()}};item{CalcField(if(ru)"Риск на сделку, %" else "Risk per trade, %",risk){risk=it;prefs.edit().putString("risk_pct",it).apply()}};item{CalcField(if (ru) "Вход" else "Entry",entry){entry=it}};item{CalcField("SL",sl){sl=it}};item{CalcField("TP2",tp){tp=it}};item{GradientCard(Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){StatBlock(if(ru)"Денежный риск" else "Cash risk",fmt(loss),Negative);StatBlock(if(ru)"Количество" else "Quantity","%.2f".format(Locale.US,qty),Accent);StatBlock(if(ru)"Прибыль" else "Profit",fmt(profit),Positive);StatBlock(if (ru) "Риск/прибыль" else "R/R","%.2f".format(Locale.US,rr),Warning)}}}}
}
@Composable private fun CalcField(label:String,value:String,on:(String)->Unit){OutlinedTextField(value,on,Modifier.fillMaxWidth(),singleLine=true,label={Text(label)})}

@Composable private fun Stats(history: List<HistoryEntry>, tracked: List<TrackedForecast>, ru: Boolean) { val done=history.filter{it.directionOk!=null};val wins=done.count{it.directionOk==true};val longs=history.count{it.signal.contains("LONG")};val shorts=history.count{it.signal.contains("SHORT")};val avg=if(history.isEmpty())0 else history.map{it.confidence}.average().roundToInt();LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){MetricCard(if(ru) "ПРОГНОЗЫ" else "FORECASTS",history.size.toString(),Accent,Modifier.weight(1f));MetricCard(if(ru) "УСПЕШНЫЕ" else "WINS",wins.toString(),Positive,Modifier.weight(1f));MetricCard(if(ru) "ТОЧНОСТЬ" else "ACCURACY",if(done.isEmpty())"—" else "${wins*100/done.size}%",Warning,Modifier.weight(1f))}};item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){MetricCard("LONG",longs.toString(),Positive,Modifier.weight(1f));MetricCard("SHORT",shorts.toString(),Negative,Modifier.weight(1f));MetricCard(if(ru) "СРЕДНЯЯ УВЕРЕННОСТЬ" else "AVG CONF","$avg%",Blue,Modifier.weight(1f))}};item{GradientCard(Modifier.fillMaxWidth()){Text(if(ru)"Активные отслеживания" else "Active monitoring",fontWeight=FontWeight.Black);Text(tracked.count{it.result=="PENDING"}.toString(),fontSize=28.sp,fontWeight=FontWeight.Black,color=Accent,modifier=Modifier.padding(top=5.dp));Text(if(ru)"Уникальность обеспечивается парой инструмент + таймфрейм." else "Uniqueness is enforced by instrument + timeframe.",fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
}

@Composable private fun BcsConnectionSettings(ru:Boolean,prefs:android.content.SharedPreferences){
    var token by remember { mutableStateOf(prefs.getString("bcs_refresh_token","").orEmpty()) }
    var saved by remember { mutableStateOf(token.isNotBlank()) }
    SettingGroup(if(ru)"БКС" else "BCS"){
        Text(if(ru)"Поиск акций и валют работает напрямую через API БКС. Каталог инструментов в приложении не загружается и не кэшируется." else "Stock and currency search uses the BCS API directly. No instrument catalogue is downloaded or cached in the app.",fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value=token,onValueChange={token=it;saved=false},modifier=Modifier.fillMaxWidth().padding(top=8.dp),singleLine=true,label={Text(if(ru)"Refresh-токен БКС" else "BCS refresh token")},visualTransformation=PasswordVisualTransformation())
        Button(onClick={prefs.edit().putString("bcs_refresh_token",token.trim()).apply();saved=token.isNotBlank()},enabled=token.isNotBlank(),modifier=Modifier.fillMaxWidth().padding(top=7.dp),shape=RoundedCornerShape(13.dp)){Text(if(ru)if(saved)"БКС подключён" else "Сохранить токен" else if(saved)"BCS connected" else "Save token",fontWeight=FontWeight.Black)}
        Text(if(token.isNotBlank()&&saved)if(ru)"Источник инструментов и рыночных данных: БКС API" else "Instrument and market-data source: BCS API" else if(ru)"Введите refresh-токен БКС с правами только для чтения." else "Enter a BCS read-only refresh token.",fontSize=8.sp,color=if(token.isNotBlank()&&saved)Positive else Warning,modifier=Modifier.padding(top=5.dp))
    }
}
@Composable private fun Settings(ru:Boolean,notifications:Boolean,interval:Int,prefs:android.content.SharedPreferences,refreshValue:Int,refreshUnit:String,displayCurrency:String,toggleLang:()->Unit,toggleNotif:(Boolean)->Unit,setInterval:(Int)->Unit,setRefreshValue:(Int)->Unit,setRefreshUnit:(String)->Unit,setDisplayCurrency:(String)->Unit,clearHistory:()->Unit,runNow:()->Unit,repo:MarketRepository){
    val ctx = LocalContext.current
    var alertSignal by remember{mutableStateOf(prefs.getBoolean("alert_signal",true))}; var alertNews by remember{mutableStateOf(prefs.getBoolean("alert_news",true))}; var alertDividends by remember{mutableStateOf(prefs.getBoolean("alert_dividends",true))}; var alertResult by remember{mutableStateOf(prefs.getBoolean("alert_result",true))}; var alertTp1 by remember{mutableStateOf(prefs.getBoolean("alert_tp1",true))}; var alertTp2 by remember{mutableStateOf(prefs.getBoolean("alert_tp2",true))}; var alertTp3 by remember{mutableStateOf(prefs.getBoolean("alert_tp3",true))}; var alertSl by remember{mutableStateOf(prefs.getBoolean("alert_sl",true))}; var alertPrice by remember{mutableStateOf(prefs.getBoolean("alert_price",false))}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(top=12.dp,bottom=30.dp)){
        item{Column{Text(if(ru)"Настройки" else "Settings",fontSize=28.sp,fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.onSurface,style=LocalTextStyle.current.copy(shadow=Shadow(Accent.copy(alpha=.68f),blurRadius=10f)));Text(if(ru)"Управление рынком, обновлением, уведомлениями и внешним видом" else "Market, refresh, notifications and appearance",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=3.dp))}}
        item{SettingGroup(if(ru)"Основные" else "General"){SettingCard(Icons.Default.Language,if(ru)"Язык" else "Language",if(ru)"Русский" else "English",toggleLang)}}
        item{BcsConnectionSettings(ru,prefs)}
        item{SettingGroup(if(ru)"Валюта отображения" else "Display currency"){Text(if(ru)"Все денежные значения и прибыль/убыток показываются в выбранной валюте. Конвертация выполняется по актуальным FX-котировкам БКС." else "All monetary values and P/L are shown in the selected currency using live BCS FX rates.",fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Row(Modifier.horizontalScroll(rememberScrollState()).padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){CurrencyDisplay.supported.forEach{c->FilterChip(selected=displayCurrency==c,onClick={setDisplayCurrency(c)},label={Text("${CurrencyDisplay.symbol(c)} $c",fontSize=8.sp)})}};Text(if(ru)"Текущая: ${CurrencyDisplay.symbol(displayCurrency)} $displayCurrency" else "Selected: ${CurrencyDisplay.symbol(displayCurrency)} $displayCurrency",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Accent,modifier=Modifier.padding(top=7.dp))}}
        item{GradientCard(Modifier.fillMaxWidth()){Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Speed,null,tint=Accent);Column(Modifier.weight(1f).padding(start=11.dp)){Text(if(ru)"Обновление данных" else "Data refresh",fontWeight=FontWeight.Black);Text(if(ru)"Единый интервал для открытых разделов" else "One interval for open sections",fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}};Spacer(Modifier.height(10.dp));Row(verticalAlignment=Alignment.CenterVertically){OutlinedTextField(refreshValue.toString(),{setRefreshValue(it.filter(Char::isDigit).toIntOrNull()?.coerceAtLeast(1)?:refreshValue)},Modifier.weight(1f),singleLine=true,label={Text(if(ru)"Интервал" else "Interval")});Spacer(Modifier.width(8.dp));FilterChip(selected=refreshUnit=="SEC",onClick={setRefreshUnit("SEC")},label={Text(if(ru)"сек" else "sec")});Spacer(Modifier.width(4.dp));FilterChip(selected=refreshUnit=="MIN",onClick={setRefreshUnit("MIN")},label={Text(if(ru)"мин" else "min")})};Spacer(Modifier.height(7.dp));Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf(1,5,10,30,60).forEach{v->FilterChip(selected=refreshValue==v&&refreshUnit=="SEC",onClick={setRefreshValue(v);setRefreshUnit("SEC")},label={Text("${v}с",fontSize=8.sp)})};listOf(1,5,15,30).forEach{v->FilterChip(selected=refreshValue==v&&refreshUnit=="MIN",onClick={setRefreshValue(v);setRefreshUnit("MIN")},label={Text("${v}м",fontSize=8.sp)})}};Text(if(ru)"Активно: каждые ${refreshValue}${if(refreshUnit=="SEC")" сек" else " мин"}. График, текущая цена, прогноз и отслеживание используют этот же цикл." else "Active: every ${refreshValue}${if(refreshUnit=="SEC")" sec" else " min"}. Live price, forecast and tracking use the same cycle.",fontSize=9.sp,color=Accent,modifier=Modifier.padding(top=7.dp))}}
        item{SettingGroup(if(ru)"Уведомления" else "Notifications"){AlertToggle(if(ru)"Разрешить уведомления" else "Allow notifications",notifications){toggleNotif(it)};Spacer(Modifier.height(5.dp));AlertToggle(if(ru)"Новая новость" else "New news",alertNews){alertNews=it;prefs.edit().putBoolean("alert_news",it).apply()};AlertToggle(if(ru)"Новый дивиденд" else "New dividend",alertDividends){alertDividends=it;prefs.edit().putBoolean("alert_dividends",it).apply()};AlertToggle(if(ru)"Новый/изменившийся сигнал" else "New/changed signal",alertSignal){alertSignal=it;prefs.edit().putBoolean("alert_signal",it).apply()};AlertToggle("TP1",alertTp1){alertTp1=it;prefs.edit().putBoolean("alert_tp1",it).apply()};AlertToggle("TP2",alertTp2){alertTp2=it;prefs.edit().putBoolean("alert_tp2",it).apply()};AlertToggle("TP3",alertTp3){alertTp3=it;prefs.edit().putBoolean("alert_tp3",it).apply()};AlertToggle("Stop Loss",alertSl){alertSl=it;prefs.edit().putBoolean("alert_sl",it).apply()};AlertToggle(if(ru)"Результат прогноза" else "Forecast result",alertResult){alertResult=it;prefs.edit().putBoolean("alert_result",it).apply()};AlertToggle(if(ru)"Резкое изменение цены" else "Large price move",alertPrice){alertPrice=it;prefs.edit().putBoolean("alert_price",it).apply()};Spacer(Modifier.height(8.dp));OutlinedButton(onClick={val i=Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).apply{putExtra(AndroidSettings.EXTRA_APP_PACKAGE,ctx.packageName)};ctx.startActivity(i)},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Notifications,null);Spacer(Modifier.width(6.dp));Text(if(ru)"Открыть системные настройки уведомлений" else "Open system notification settings")}}}
        item { SettingGroup(if (ru) "Фоновый мониторинг" else "Background monitoring") {
            Text(if (ru) "Для фоновой работы Android ограничивает минимальный период фонового планировщика. Эта настройка относится только к фоновой проверке уведомлений." else "Android limits the minimum WorkManager period in background. This setting controls background alert checks only.", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.padding(top = 7.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15, 30, 60, 120, 240).forEach { v ->
                    FilterChip(selected = interval == v, onClick = { setInterval(v) }, label = { Text(if (v < 60) "$v ${if (ru) "мин" else "min"}" else "${v / 60} ${if (ru) "ч" else "h"}", fontSize = 8.sp) })
                }
            }
        } }
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick=runNow,modifier=Modifier.weight(1f),shape=RoundedCornerShape(15.dp)){Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(5.dp));Text(if(ru)"Проверить" else "Check",fontWeight=FontWeight.Bold)};Button(onClick=clearHistory,modifier=Modifier.weight(1f),shape=RoundedCornerShape(15.dp),colors=ButtonDefaults.buttonColors(containerColor=Negative)){Icon(Icons.Default.Delete,null);Spacer(Modifier.width(5.dp));Text(if(ru)"Очистить" else "Clear",fontWeight=FontWeight.Bold)}}}
    }
}
@Composable private fun AlertToggle(label:String,value:Boolean,on:(Boolean)->Unit){Row(Modifier.fillMaxWidth().padding(vertical=2.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),fontSize=10.sp);Switch(checked = value, onCheckedChange = on)}}

@Composable private fun BottomNav(s:Screen,ru:Boolean,on:(Screen)->Unit){NavigationBar{listOf(Screen.HOME to Icons.Default.Home,Screen.SEARCH to Icons.Default.Search,Screen.FAVORITES to Icons.Default.Star,Screen.HISTORY to Icons.Default.History,Screen.SETTINGS to Icons.Default.Settings).forEach{(scr,icon)->NavigationBarItem(s==scr,{on(scr)},icon={Icon(icon,null)},label={Text(if(ru)when(scr){Screen.HOME->"Главная";Screen.SEARCH->"Поиск";Screen.FAVORITES->"Избранное";Screen.HISTORY->"История";else->"Настройки"}else when(scr){Screen.HOME->"Home";Screen.SEARCH->"Search";Screen.FAVORITES->"Favorites";Screen.HISTORY->"History";else->"Settings"},fontSize=7.sp)})}}}
@Composable private fun SearchLauncher(ru:Boolean,on:()->Unit){Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(Color.Black).border(1.dp,DarkLine.copy(.9f),RoundedCornerShape(17.dp)).clickable(onClick=on).padding(15.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Search,null,tint=Accent);Text(if(ru)"Поиск акций и валют…" else "Search stocks and currencies…",Modifier.padding(start=10.dp),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=11.sp)}}
@Composable private fun QuickAction(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,on:()->Unit,modifier:Modifier){GradientCard(modifier.clickable(onClick=on)){Column(Modifier.padding(2.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(icon,null,tint=Accent,modifier=Modifier.size(22.dp));Text(title,fontWeight=FontWeight.Bold,fontSize=8.sp,modifier=Modifier.padding(top=5.dp))}}}
@Composable private fun SectionHeader(t:String,s:String){
    Column {
        Text(t,fontSize=17.sp,fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.onSurface,style=LocalTextStyle.current.copy(shadow=Shadow(Accent.copy(alpha=.68f),blurRadius=10f)))
        if(s.isNotBlank()) Text(s,fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun GradientCard(modifier:Modifier,content:@Composable ColumnScope.()->Unit){Card(modifier,shape=RoundedCornerShape(21.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),border=androidx.compose.foundation.BorderStroke(1.2.dp,DarkLine.copy(.90f))){Column(Modifier.background(Brush.linearGradient(listOf(Accent.copy(.025f),Accent2.copy(.018f),Color.Transparent))).padding(15.dp),content=content)}}
@Composable private fun SettingCard(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,value:String,on:()->Unit){GradientCard(Modifier.fillMaxWidth().clickable(onClick=on)){Row(verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=Accent);Column(Modifier.weight(1f).padding(start=11.dp)){Text(title,fontWeight=FontWeight.Bold);Text(value,fontSize=9.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}
@Composable private fun SettingGroup(title:String,content:@Composable ColumnScope.()->Unit){GradientCard(Modifier.fillMaxWidth()){Text(title,fontWeight=FontWeight.Bold,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurface);Spacer(Modifier.height(7.dp));content()}}
@Composable private fun MetricCard(title:String,value:String,color:Color,modifier:Modifier){
    Card(modifier,shape=RoundedCornerShape(17.dp),colors=CardDefaults.cardColors(containerColor=Color.Black),border=androidx.compose.foundation.BorderStroke(1.2.dp,DarkLine.copy(.82f))){
        Column(Modifier.padding(10.dp)){
            Text(title,fontSize=7.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,fontWeight=FontWeight.Bold)
            Text(value,fontSize=18.sp,fontWeight=FontWeight.Black,color=color)
        }
    }
}
@Composable private fun EmptyCard(text:String){GradientCard(Modifier.fillMaxWidth()){Text(text,Modifier.padding(4.dp),fontSize=10.sp)}}
@Composable private fun StatBlock(l:String,v:String,c:Color){Column{Text(l,fontSize=7.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(v,fontSize=11.sp,fontWeight=FontWeight.Black,color=c)}}
@Composable private fun SignalPill(s:String,c:Color){Box(Modifier.clip(RoundedCornerShape(50)).background(c.copy(.12f)).border(1.dp,c.copy(.35f),RoundedCornerShape(50)).padding(horizontal=9.dp,vertical=5.dp)){Text(s,color=c,fontSize=8.sp,fontWeight=FontWeight.Black)}}
@Composable private fun PriceBadge(l:String,v:Double,c:Color){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(l,fontSize=7.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(fmt(v),fontSize=9.sp,fontWeight=FontWeight.Black,color=c)}}
@Composable private fun LevelRow(l:String,v:Double,c:Color){Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(l,fontSize=9.sp);Text(fmt(v),fontWeight=FontWeight.Bold,color=c,fontSize=10.sp)}}
@Composable private fun Loading(ru:Boolean){GradientCard(Modifier.fillMaxWidth()){Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(22.dp),color=Accent);Text(if(ru)"Загрузка актуальных данных…" else "Loading live data…",Modifier.padding(start=10.dp),fontSize=10.sp)}}}
@Composable private fun ErrorCard(s:String,ru:Boolean){GradientCard(Modifier.fillMaxWidth()){Text(if(ru)"Данные временно недоступны" else "Data temporarily unavailable",color=Negative,fontWeight=FontWeight.Bold);Text(s,fontSize=9.sp,modifier=Modifier.padding(top=5.dp))}}

@Composable private fun InAppNotice(text: String, modifier: Modifier = Modifier) { Surface(modifier, shape = RoundedCornerShape(12.dp), color = Color.Black, contentColor = Color.White, tonalElevation = 0.dp, shadowElevation = 8.dp, border = androidx.compose.foundation.BorderStroke(1.2.dp, DarkLine.copy(.92f))) { Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) } }
private fun loadFavoriteOrder(p: android.content.SharedPreferences, favs: Set<String>): List<String> = (p.getString("favorite_order", "").orEmpty().split("\n").filter { it.isNotBlank() }.filter { it in favs } + favs.filter { it !in p.getString("favorite_order", "").orEmpty().split("\n") }).distinct()
private fun loadSearchHistory(p: android.content.SharedPreferences): List<String> = p.getString("search_history", "").orEmpty().split("\n").filter { it.isNotBlank() }.take(20)
private fun saveSearchHistory(p: android.content.SharedPreferences, q: String): List<String> { val out = (listOf(q) + loadSearchHistory(p).filterNot { it.equals(q, true) }).take(20); p.edit().putString("search_history", out.joinToString("\n")).apply(); return out }

private fun matchesSearchFilter(r: SearchResult, filter:String):Boolean=when(filter){
    "STOCK"->r.type.uppercase(Locale.US) in setOf("STOCK","FOREIGN_STOCK","DEPOSITARY_RECEIPTS")
    "FX"->r.type.contains("CURRENCY",true)||r.exchange.contains("FOREX",true)||r.symbol.endsWith("=X")
    else->r.type.uppercase(Locale.US) in setOf("STOCK","FOREIGN_STOCK","DEPOSITARY_RECEIPTS","CURRENCY")
}

private fun signalColor(s:String)=when{ s.contains("LONG")->Positive; s.contains("SHORT")->Negative; else->Warning }
private fun fmt(v:Double):String=when{abs(v)>=1000->String.format(Locale.US,"%,.2f",v);abs(v)>=1->String.format(Locale.US,"%.2f",v);else->String.format(Locale.US,"%.6f",v)}
private fun formatElapsed(ms: Long): String { val s=ms/1000; val d=s/86400; val h=(s%86400)/3600; val m=(s%3600)/60; val sec=s%60; return when { d>0 -> "${d}д ${h}ч"; h>0 -> "${h}ч ${m}м"; m>0 -> "${m}м ${sec}с"; else -> "${sec}с" } }
private fun dateTime(t:Long):String=SimpleDateFormat("dd.MM.yyyy HH:mm:ss",Locale.getDefault()).format(Date(t))
private fun tfMillis(tf:String)=when(tf){"15M"->15*60_000L;"1H"->60*60_000L;"4H"->4*60*60_000L;"1W"->7*24*60*60_000L;else->24*60*60_000L}
private fun timeframePair(tf:String): Pair<String,String> = when(tf.uppercase(Locale.US)){"15M"->"60d" to "15m";"1H"->"2y" to "1h";"4H"->"2y" to "4h";"1W"->"10y" to "1wk";else->"5y" to "1d"}
private fun encodeTracked(v:List<TrackedForecast>)=v.joinToString("\n"){listOf(it.id,it.symbol,it.signal,it.confidence,it.entry,it.timeframe,it.createdAt,it.checkAt,it.result,it.checkedPrice,it.stop,it.tp1,it.tp2,it.tp3,it.lastLivePrice,it.lastUpdated,it.tp1Hit,it.tp2Hit,it.tp3Hit,it.slHit).joinToString("|")}
private fun loadCachedNews(p:Context)=loadCachedNews(p.getSharedPreferences("mfprefs",Context.MODE_PRIVATE))
private fun loadCachedNews(p:android.content.SharedPreferences)=p.getString("news_cache","").orEmpty().split("\n").mapNotNull{val a=it.split("|",limit=7);if(a.size>=5)NewsItem(a[0],a[1],a[2],a[3].toLongOrNull()?:0,a[4],body=a.getOrNull(5).orEmpty(),instrument=a.getOrNull(6).orEmpty())else null}
private fun saveCachedNews(p:android.content.SharedPreferences,v:List<NewsItem>){p.edit().putString("news_cache",v.take(80).joinToString("\n"){listOf(it.title,it.publisher,it.url,it.publishedAt,it.originalTitle,it.body,it.instrument).joinToString("|")}).apply()}
private fun hitSummary(t: TrackedForecast): String = buildList {
    if (t.tp1Hit) add("TP1")
    if (t.tp2Hit) add("TP2")
    if (t.tp3Hit) add("TP3")
    if (t.slHit) add("SL")
}.joinToString("/")
private fun loadHistory(p:android.content.SharedPreferences)=p.getString("forecast_history","").orEmpty().split("\n").mapNotNull{val a=it.split("|",limit=11);if(a.size>=7)HistoryEntry(a[0].toLongOrNull()?:0,a[1],a[2],a[3].toIntOrNull()?:0,a[4].toDoubleOrNull()?:0.0,a[5].toDoubleOrNull()?:0.0,a[6],a.getOrNull(7)?:"1D",a.getOrNull(8)?.toBooleanStrictOrNull(),a.getOrNull(9)?.toDoubleOrNull()?:0.0,a.getOrNull(10).orEmpty())else null}
private fun encodeHistoryEntries(v:List<HistoryEntry>)=v.joinToString("\n"){listOf(it.time,it.symbol,it.signal,it.confidence,it.price,it.tp2,it.result,it.timeframe,it.directionOk?.toString() ?: "",it.closingPrice,it.hitSummary).joinToString("|")}
private fun saveHistory(p:android.content.SharedPreferences,v:List<HistoryEntry>){p.edit().putString("forecast_history",encodeHistoryEntries(v)).apply()}
private fun loadTracked(p:android.content.SharedPreferences)=p.getString("tracked","").orEmpty().split("\n").mapNotNull{val a=it.split("|",limit=20);when{a.size>=20->TrackedForecast(a[0],a[1],a[2],a[3].toIntOrNull()?:0,a[4].toDoubleOrNull()?:0.0,a[5],a[6].toLongOrNull()?:0,a[7].toLongOrNull()?:0,a[8],a[9].toDoubleOrNull()?:0.0,a[10].toDoubleOrNull()?:0.0,a[11].toDoubleOrNull()?:0.0,a[12].toDoubleOrNull()?:0.0,a[13].toDoubleOrNull()?:0.0,a[14].toDoubleOrNull()?:0.0,a[15].toLongOrNull()?:0,a[16].toBooleanStrictOrNull()?:false,a[17].toBooleanStrictOrNull()?:false,a[18].toBooleanStrictOrNull()?:false,a[19].toBooleanStrictOrNull()?:false);a.size>=14->TrackedForecast(a[0],a[1],a[2],a[3].toIntOrNull()?:0,a[4].toDoubleOrNull()?:0.0,a[5],a[6].toLongOrNull()?:0,a[7].toLongOrNull()?:0,a[8],a[9].toDoubleOrNull()?:0.0,a[10].toDoubleOrNull()?:0.0,a[11].toDoubleOrNull()?:0.0,a[12].toDoubleOrNull()?:0.0,a[13].toDoubleOrNull()?:0.0);a.size==10->TrackedForecast(a[0],a[1],a[2],a[3].toIntOrNull()?:0,a[4].toDoubleOrNull()?:0.0,a[5],a[6].toLongOrNull()?:0,a[7].toLongOrNull()?:0,a[8],a[9].toDoubleOrNull()?:0.0);else->null}}.distinctBy{it.id}

private fun loadCachedDividends(p:android.content.SharedPreferences)=p.getString("dividend_cache","").orEmpty().split("\n").mapNotNull{val a=it.split("|",limit=4);if(a.size==4)DividendEvent(a[0],a[1].toLongOrNull()?:0,a[2].toDoubleOrNull()?:0.0,a[3])else null}
private fun saveCachedDividends(p:android.content.SharedPreferences,v:List<DividendEvent>){p.edit().putString("dividend_cache",v.take(100).joinToString("\n"){listOf(it.symbol,it.date,it.amount,it.source).joinToString("|")}).apply()}
private fun createNotificationChannel(ctx:Context){ NotificationHelper.ensureChannels(ctx) }
private fun sendTrackedNotification(ctx: Context, text: String) {
    NotificationHelper.notifyTracking(ctx, "legacy|${text.hashCode()}", true, "Market Forecast", text)
}
private fun sendTestNotification(ctx:Context,ru:Boolean){ NotificationHelper.sendTest(ctx, ru) }
