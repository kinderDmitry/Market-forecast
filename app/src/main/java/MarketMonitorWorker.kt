package com.marketforecast.prox

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import kotlin.math.abs

class MarketMonitorWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val p = applicationContext.getSharedPreferences("mfprefs", Context.MODE_PRIVATE)
        val ru = p.getBoolean("ru", true)
        val repo = MarketRepository(
            bcsRefreshToken = p.getString("bcs_refresh_token", "")?.ifBlank { null },
            prefs = p,
            context = applicationContext
        )
        val tracked = readTracked(p).toMutableList()
        val history = readHistory(p).toMutableList()
        val now = System.currentTimeMillis()
        var retryNeeded = false

        val alertSignal = p.getBoolean("alert_signal", true)
        val monitorMinConf = p.getInt("alert_min_confidence", 60).coerceIn(0, 100)
        // Compatibility alias: older generated workflow patches referred to minConf.
        val minConf = monitorMinConf
        val alertTp1 = p.getBoolean("alert_tp1", p.getBoolean("alert_tp", true))
        val alertTp2 = p.getBoolean("alert_tp2", p.getBoolean("alert_tp", true))
        val alertTp3 = p.getBoolean("alert_tp3", p.getBoolean("alert_tp", true))
        val alertSl = p.getBoolean("alert_sl", true)
        val alertPrice = p.getBoolean("alert_price", false)
        val alertResult = p.getBoolean("alert_result", true)
        val alertNews = p.getBoolean("alert_news", true)
        val alertDividends = p.getBoolean("alert_dividends", true)

        // News: first run seeds the cache, later runs notify only for unseen articles.
        if (alertNews) {
            val latest = runCatching { repo.marketNews(30, ru, NewsCategory.ALL) }.getOrDefault(emptyList())
            if (latest.isNotEmpty()) p.edit().putString("news_cache", encodeNews(latest)).apply()
            val seen = p.getStringSet("seen_news", emptySet()).orEmpty().toMutableSet()
            if (seen.isEmpty()) {
                latest.forEach { if (it.url.isNotBlank()) seen += it.url }
            } else {
                latest.filter { it.url.isNotBlank() && it.url !in seen }.take(3).forEach { n ->
                    val key = "NEWS|${n.url}"
                    val body = if (ru) "${n.title}${if (n.instrument.isNotBlank()) " • ${n.instrument}" else ""}" else n.title
                    NotificationHelper.notifyMarket(applicationContext, key, if (ru) "📰 Новая новость" else "📰 New article", body, "NEWS_DETAIL", n.instrument.ifBlank { null }, n.url)
                    seen += n.url
                }
            }
            p.edit().putStringSet("seen_news", seen.toList().takeLast(400).toSet()).apply()
        }

        // Dividend alerts use the same cache-first rule, so enabling the switch does not
        // generate a burst of notifications for the whole existing calendar.
        if (alertDividends) {
            val latest = runCatching { repo.dividendCalendar(limit = 80) }.getOrDefault(emptyList())
            val seen = p.getStringSet("seen_dividends", emptySet()).orEmpty().toMutableSet()
            if (seen.isEmpty()) {
                latest.forEach { seen += "${it.symbol}|${it.date}|${it.amount}" }
            } else {
                latest.filter { "${it.symbol}|${it.date}|${it.amount}" !in seen }.take(3).forEach { d ->
                    val key = "DIVIDEND|${d.symbol}|${d.date}|${d.amount}"
                    val body = if (ru) "${d.symbol} • ${format(d.amount)} • ${formatDate(d.date)}" else "${d.symbol} • ${format(d.amount)} • ${formatDate(d.date)}"
                    NotificationHelper.notifyMarket(applicationContext, key, if (ru) "💰 Новый дивиденд" else "💰 New dividend", body, "DIVIDENDS", d.symbol)
                    seen += "${d.symbol}|${d.date}|${d.amount}"
                }
            }
            p.edit().putStringSet("seen_dividends", seen.toList().takeLast(400).toSet()).apply()
        }

        // Each tracked forecast is evaluated independently. TP1/TP2/TP3/SL milestones
        // are persisted before the terminal result and therefore survive app restarts.
        for (i in tracked.indices) {
            val t = tracked[i]
            if (t.result != "PENDING") continue
            val pair = timeframePair(t.timeframe)
            val candles = runCatching { repo.load(t.symbol, pair.first, pair.second) }.getOrDefault(emptyList())
            // At the exact horizon boundary bypass the short quote cache. This prevents
            // History from closing a forecast at the same stale price used when it was added.
            val livePrice = if (now >= t.checkAt) {
                reconcileLivePrice(t.symbol, candles, runCatching { repo.quoteFresh(t.symbol) }.getOrNull())
            } else {
                reconcileLivePrice(t.symbol, candles, runCatching { repo.quote(t.symbol) }.getOrNull())
            }
            android.util.Log.d("MFP_TRACK", "id=${t.id} now=$now checkAt=${t.checkAt} candles=${candles.size} live=$livePrice")
            if (livePrice <= 0.0) {
                retryNeeded = true
                continue
            }
            val evaluation = TrackingEngine.evaluate(t, candles, livePrice, now)
            if (evaluation == null && now >= t.checkAt) {
                retryNeeded = true
                continue
            }
            if (evaluation == null) continue

            val hitTp1 = t.tp1Hit || evaluation.events.contains("SUCCESS_TP1")
            val hitTp2 = t.tp2Hit || evaluation.events.contains("SUCCESS_TP2")
            val hitTp3 = t.tp3Hit || evaluation.events.contains("SUCCESS_TP3")
            val hitSl = t.slHit || evaluation.events.contains("STOP")
            val current = t.copy(tp1Hit = hitTp1, tp2Hit = hitTp2, tp3Hit = hitTp3, slHit = hitSl)
            val finalResult = evaluation.finalResult
            tracked[i] = current.copy(
                lastLivePrice = evaluation.price,
                lastUpdated = now,
                result = finalResult ?: "PENDING",
                checkedPrice = if (finalResult != null) evaluation.price else t.checkedPrice
            )

            evaluation.events.forEach { event ->
                when (event) {
                    "SUCCESS_TP1" -> if (alertTp1) NotificationHelper.notifyTracking(applicationContext, "${t.id}|TP1", ru, "🎯 TP1 достигнут", "${t.symbol} • ${t.timeframe} • ${format(evaluation.price)}", t.symbol)
                    "SUCCESS_TP2" -> if (alertTp2) NotificationHelper.notifyTracking(applicationContext, "${t.id}|TP2", ru, "🎯 TP2 достигнут", "${t.symbol} • ${t.timeframe} • ${format(evaluation.price)}", t.symbol)
                    "SUCCESS_TP3" -> if (alertTp3) NotificationHelper.notifyTracking(applicationContext, "${t.id}|TP3", ru, "🏆 TP3 достигнут", "${t.symbol} • ${t.timeframe} • ${format(evaluation.price)}", t.symbol)
                    "STOP" -> if (alertSl) NotificationHelper.notifyTracking(applicationContext, "${t.id}|SL", ru, "🛑 Stop Loss достигнут", "${t.symbol} • ${t.timeframe} • ${format(evaluation.price)}", t.symbol)
                }
            }

            finalResult?.let { result ->
                val ok = result.startsWith("SUCCESS") || result == "DIRECTION_OK" || result == "FLAT"
                val mark = when { result == "FLAT" -> "≈"; ok -> "✓"; else -> "✕" }
                val hits = hitSummary(current)
                val idx = history.indexOfFirst { it.symbol == t.symbol && it.time == t.createdAt }
                val entry = HistoryEntry(t.createdAt, t.symbol, t.signal, t.confidence, t.entry, t.tp2, mark, t.timeframe, if (result == "FLAT") null else ok, evaluation.price, hits)
                if (idx >= 0) history[idx] = history[idx].copy(result = mark, directionOk = if (result == "FLAT") null else ok, closingPrice = evaluation.price, hitSummary = hits)
                else history.add(0, entry)

                if (alertResult) {
                    val hitsText = hits.ifBlank { "—" }
                    val title = if (ok) "✅ Прогноз завершён" else "❌ Прогноз завершён"
                    val body = if (ru) {
                        "${t.symbol} • ${t.timeframe}\nРезультат: ${if (ok) "подтверждён" else "не подтверждён"}\nВход: ${format(t.entry)} • Закрытие: ${format(evaluation.price)}\nСобытия: $hitsText\nSL: ${format(t.stop)} • TP1: ${format(t.tp1)} • TP2: ${format(t.tp2)} • TP3: ${format(t.tp3)}"
                    } else {
                        if (ru) "${t.symbol} • ${t.timeframe}\nРезультат: ${if (ok) "подтверждён" else "не подтверждён"}\nОткрытие: ${format(t.entry)} • Закрытие: ${format(evaluation.price)}\nСобытия: $hitsText\nSL: ${format(t.stop)} • TP1: ${format(t.tp1)} • TP2: ${format(t.tp2)} • TP3: ${format(t.tp3)}" else "${t.symbol} • ${t.timeframe}\nResult: ${if (ok) "confirmed" else "failed"}\nOpen: ${format(t.entry)} • Close: ${format(evaluation.price)}\nEvents: $hitsText\nSL: ${format(t.stop)} • TP1: ${format(t.tp1)} • TP2: ${format(t.tp2)} • TP3: ${format(t.tp3)}"
                    }
                    NotificationHelper.notifyTracking(applicationContext, "${t.id}|RESULT|$result", ru, title, body, t.symbol)
                }
            }
        }

        // Background worker is reserved for favorites/tracking.

        val favorites = p.getStringSet("favorites", emptySet()).orEmpty().toList()
        val previous = readSignalMap(p).toMutableMap()
        val previousPrices = readPriceMap(p).toMutableMap()

        // Analyze independent favorites concurrently, but cap concurrency so a large
        // favorites list cannot create an unbounded CPU/network storm. This is not an
        // instrument limit: every favorite is processed; the semaphore only controls
        // how many are actively executing at the same instant.
        val slots = Semaphore(4)
        data class FavoriteResult(val symbol: String, val signal: String, val confidence: Int, val live: Double)
        val results = favorites.map { symbol ->
            async(Dispatchers.IO) {
                slots.withPermit {
                    val candles = runCatching { repo.load(symbol, "5y", "1d") }.getOrNull()
                    if (candles == null || candles.size < 30) return@withPermit null
                    val live = runCatching { repo.quote(symbol) }.getOrNull() ?: 0.0
                    val f = runCatching { AnalyticsEngine.analyze(candles, live) }.getOrNull() ?: return@withPermit null
                    FavoriteResult(symbol, f.signal, f.confidence, live)
                }
            }
        }.awaitAll().filterNotNull()

        for (r in results) {
            val symbol = r.symbol
            val live = r.live
            val old = previous[symbol]
            if (alertSignal && r.confidence >= monitorMinConf && r.signal != "NO TRADE" && old != null && old != r.signal) {
                NotificationHelper.notifyMarket(applicationContext, "$symbol|SIGNAL|${r.signal}", if (ru) "📈 Изменение сигнала" else "📈 Signal changed", if (ru) "$symbol • новый сигнал ${r.signal} • ${r.confidence}%" else "$symbol • new signal ${r.signal} • ${r.confidence}%", "ANALYSIS", symbol)
            }
            if (alertPrice) {
                val oldPrice = previousPrices[symbol]
                if (oldPrice != null && oldPrice > 0) {
                    val move = abs(live - oldPrice) / oldPrice * 100.0
                    if (move >= 3.0) {
                        NotificationHelper.notifyMarket(applicationContext, "$symbol|PRICE|${live.toLong()}", if (ru) "⚡ Резкое изменение цены" else "⚡ Large price move", if (ru) "$symbol • движение цены ${"%.2f".format(Locale.US, move)}%" else "$symbol • price move ${"%.2f".format(Locale.US, move)}%", "ANALYSIS", symbol)
                    }
                }
                previousPrices[symbol] = live
            }
            previous[symbol] = r.signal
        }

        p.edit()
            .putString("tracked", encodeTracked(tracked))
            .putString("forecast_history", encodeHistory(history))
            .putString("favorite_signals", encodeSignalMap(previous))
            .putString("favorite_prices", encodePriceMap(previousPrices))
            .putLong("monitor_last_run", now)
            .apply()

        if (retryNeeded) Result.retry() else Result.success()
    }

    private fun timeframePair(tf: String): Pair<String, String> = when (tf.uppercase(Locale.US)) {
        "15M" -> "60d" to "15m"
        "1H" -> "2y" to "1h"
        "4H" -> "2y" to "4h"
        "1W" -> "10y" to "1wk"
        else -> "5y" to "1d"
    }

    private fun format(v: Double) = if (abs(v) >= 1000) String.format(Locale.US, "%,.2f", v) else String.format(Locale.US, "%.2f", v)
    private fun formatDate(v: Long) = java.text.SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(java.util.Date(v))
    private fun hitSummary(t: TrackedForecast): String = buildList {
        if (t.tp1Hit) add("TP1")
        if (t.tp2Hit) add("TP2")
        if (t.tp3Hit) add("TP3")
        if (t.slHit) add("SL")
    }.joinToString("/")

    private fun encodeNews(v: List<NewsItem>): String = v.take(80).joinToString("\n") { news ->
        listOf(news.title, news.publisher, news.url, news.publishedAt, news.originalTitle, news.body, news.instrument)
            .map { it.toString().replace("|", " ").replace("\n", " ").replace("\r", " ") }
            .joinToString("|")
    }

    private fun encodeTracked(v: List<TrackedForecast>) = v.joinToString("\n") { listOf(it.id,it.symbol,it.signal,it.confidence,it.entry,it.timeframe,it.createdAt,it.checkAt,it.result,it.checkedPrice,it.stop,it.tp1,it.tp2,it.tp3,it.lastLivePrice,it.lastUpdated,it.tp1Hit,it.tp2Hit,it.tp3Hit,it.slHit).joinToString("|") }
    private fun encodeHistory(v: List<HistoryEntry>) = v.joinToString("\n") { listOf(it.time,it.symbol,it.signal,it.confidence,it.price,it.tp2,it.result,it.timeframe,it.directionOk?.toString() ?: "",it.closingPrice,it.hitSummary).joinToString("|") }
    private fun readTracked(p: android.content.SharedPreferences): List<TrackedForecast> = p.getString("tracked", "").orEmpty().split("\n").mapNotNull { val a=it.split("|",limit=20); when { a.size>=20 -> TrackedForecast(a[0],a[1],a[2],a[3].toIntOrNull()?:0,a[4].toDoubleOrNull()?:0.0,a[5],a[6].toLongOrNull()?:0,a[7].toLongOrNull()?:0,a[8],a[9].toDoubleOrNull()?:0.0,a[10].toDoubleOrNull()?:0.0,a[11].toDoubleOrNull()?:0.0,a[12].toDoubleOrNull()?:0.0,a[13].toDoubleOrNull()?:0.0,a[14].toDoubleOrNull()?:0.0,a[15].toLongOrNull()?:0,a[16].toBooleanStrictOrNull()?:false,a[17].toBooleanStrictOrNull()?:false,a[18].toBooleanStrictOrNull()?:false,a[19].toBooleanStrictOrNull()?:false); a.size>=14 -> TrackedForecast(a[0],a[1],a[2],a[3].toIntOrNull()?:0,a[4].toDoubleOrNull()?:0.0,a[5],a[6].toLongOrNull()?:0,a[7].toLongOrNull()?:0,a[8],a[9].toDoubleOrNull()?:0.0,a[10].toDoubleOrNull()?:0.0,a[11].toDoubleOrNull()?:0.0,a[12].toDoubleOrNull()?:0.0,a[13].toDoubleOrNull()?:0.0); a.size==10 -> TrackedForecast(a[0],a[1],a[2],a[3].toIntOrNull()?:0,a[4].toDoubleOrNull()?:0.0,a[5],a[6].toLongOrNull()?:0,a[7].toLongOrNull()?:0,a[8],a[9].toDoubleOrNull()?:0.0); else -> null } }.distinctBy { it.id }
    private fun readHistory(p: android.content.SharedPreferences): List<HistoryEntry> = p.getString("forecast_history", "").orEmpty().split("\n").mapNotNull { val a=it.split("|",limit=11); if(a.size>=7) HistoryEntry(a[0].toLongOrNull()?:0,a[1],a[2],a[3].toIntOrNull()?:0,a[4].toDoubleOrNull()?:0.0,a[5].toDoubleOrNull()?:0.0,a[6],a.getOrNull(7) ?: "1D",a.getOrNull(8)?.toBooleanStrictOrNull(),a.getOrNull(9)?.toDoubleOrNull()?:0.0,a.getOrNull(10).orEmpty()) else null }
    private fun readSignalMap(p: android.content.SharedPreferences): Map<String,String> = p.getString("favorite_signals", "").orEmpty().split("\n").mapNotNull { val a=it.split("|",limit=2); if(a.size==2)a[0] to a[1] else null }.toMap()
    private fun encodeSignalMap(v: Map<String,String>) = v.entries.joinToString("\n") { "${it.key}|${it.value}" }
    private fun readPriceMap(p: android.content.SharedPreferences): MutableMap<String,Double> = p.getString("favorite_prices", "").orEmpty().split("\n").mapNotNull { val a=it.split("|",limit=2); if(a.size==2)a[0] to (a[1].toDoubleOrNull()?:return@mapNotNull null) else null }.toMap().toMutableMap()
    private fun encodePriceMap(v: Map<String,Double>) = v.entries.joinToString("\n") { "${it.key}|${it.value}" }
}
