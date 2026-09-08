package com.marketforecast.prox

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.abs
import kotlin.math.ceil
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap


internal fun reconcileLivePrice(symbol: String, candles: List<Candle>, quoted: Double?): Double {
    val q = quoted?.takeIf { it.isFinite() && it > 0.0 }
    val last = candles.lastOrNull()?.close?.takeIf { it.isFinite() && it > 0.0 }
    if (q == null) return last ?: 0.0
    if (last == null) return q

    // BCS is the single canonical market-data source. The quote is the single
    // canonical "now" price. Do not substitute a previous candle.
    // candle merely because it is far from the live quote: sessions, corporate
    // actions and stale candles can legitimately create large gaps. A valid quote
    // must remain identical for every timeframe of the same instrument.
    return q
}

/** Market-data repository. Market news/dividends are restricted to Russian financial sources; prices may use market-data fallbacks. */
class MarketRepository(private val alphaVantageKey: String? = null, private val bcsRefreshToken: String? = null) {
    companion object {
        const val BCS_DIVIDEND_CALENDAR_URL = "https://bcs-express.ru/dividednyj-kalendar"
        // Historical candles change slowly compared with live quotes. A short process-wide
        // cache prevents repeated timeframe switches/scans from hammering the same provider.
        private val candleCache = ConcurrentHashMap<String, Pair<Long, List<Candle>>>()
        private val quoteCache = ConcurrentHashMap<String, Pair<Long, Double>>()
        private const val CANDLE_CACHE_MS = 120_000L
        private const val QUOTE_CACHE_MS = 750L
        private const val CATALOG_CACHE_MS = 900_000L
        @Volatile private var catalogCacheAt = 0L
        @Volatile private var catalogCache: List<SearchResult> = emptyList()
        // Reuse worker pools instead of creating/shutting down threads on every refresh.
        private val analysisPool = Executors.newFixedThreadPool(8)
        private val prefetchPool = Executors.newFixedThreadPool(6)
    }
    private val ua = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36 MarketForecastPROX/4.8.44"

    fun load(symbol: String, range: String = "1y", interval: String = "1d"): List<Candle> {
        val clean = symbol.trim().uppercase(Locale.US)
        val cacheKey = "${if (bcsRefreshToken.isNullOrBlank()) "LEGACY" else "BCS"}|$clean|$range|$interval"
        val cached = candleCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first <= CANDLE_CACHE_MS && cached.second.isNotEmpty()) {
            return cached.second
        }
        // Forecast integrity rule: ALL forecast candles come from BCS. Never
        // silently fall back to another provider for a forecast dataset.
        // dataset, otherwise the same instrument can have different prices/levels
        // between screens and timeframes.
        if (!bcsRefreshToken.isNullOrBlank()) {
            if (!bcsSupported(clean)) throw IllegalStateException("БКС не поддерживает инструмент $symbol для прогнозов")
            val raw = runCatching { loadBcs(clean, range, interval) }.getOrElse {
                throw IllegalStateException("БКС: не удалось получить свечи для $symbol: ${it.message ?: "ошибка источника"}")
            }
            val normalized = normalizeInterval(raw, interval)
            if (normalized.isNotEmpty()) {
                candleCache[cacheKey] = System.currentTimeMillis() to normalized
                return normalized
            }
            throw IllegalStateException("БКС не вернул свечи для $symbol ($interval)")
        }
        // Forecasts are intentionally fail-closed: without BCS credentials there is
        // no fallback provider. This prevents a hidden secondary price source from entering
        // a forecast and reintroducing cross-screen/timeframe price divergence.
        throw IllegalStateException("БКС не подключен. Подключите refresh-токен БКС для получения прогнозов.")
    }

    /** Priority prefetch for the Analytics Center. Historical candles are cached
     * process-wide, so cards can render from memory instead of repeating provider calls.
     */
    fun prefetchPriority(symbols: List<String>, timeframes: List<String> = listOf("15M", "1H")) {
        val pool = prefetchPool
        val tasks = symbols.distinct().take(8).flatMap { symbol ->
                timeframes.map { tf ->
                    pool.submit {
                        runCatching {
                            val pair = when (tf) {
                                "15M" -> "60d" to "15m"
                                "1H" -> "2y" to "1h"
                                "4H" -> "2y" to "4h"
                                "1W" -> "10y" to "1wk"
                                else -> "5y" to "1d"
                            }
                            load(symbol, pair.first, pair.second)
                        }
                    }
                }
            }
        tasks.forEach { runCatching { it.get(20, TimeUnit.SECONDS) } }
    }

    fun search(query: String): List<SearchResult> {
        val q = query.trim()
        if (q.length < 2 || !isBcsConfigured()) return emptyList()
        // BCS is the only market-data/catalog provider. Search is performed against
        // the BCS instrument directory, so a symbol cannot silently switch to MOEX,
        // Yahoo or another quote source.
        val universe = runCatching { catalog() }.getOrDefault(emptyList())
        val needle = q.lowercase(Locale.ROOT)
        return universe.filter {
            it.symbol.lowercase(Locale.ROOT).contains(needle) ||
            it.name.lowercase(Locale.ROOT).contains(needle)
        }.take(30)
    }

    /** Dynamic BCS instrument catalogue. No MOEX/Yahoo catalogue is used. */
    fun catalog(limit: Int = Int.MAX_VALUE): List<SearchResult> {
        if (!isBcsConfigured()) return emptyList()
        val now = System.currentTimeMillis()
        val cached = catalogCache
        if (cached.isNotEmpty() && now - catalogCacheAt <= CATALOG_CACHE_MS) {
            return if (limit == Int.MAX_VALUE) cached else cached.take(limit)
        }
        val out = LinkedHashMap<String, SearchResult>()
        // BCS documents these instrument types in the Information Service.
        val types = listOf("STOCK", "FOREIGN_STOCK", "CURRENCY", "ETF", "FUTURES", "INDICES")
        for (type in types) {
            runCatching {
                val root = JSONObject(getTextAuth(
                    "https://be.broker.ru/trade-api-information-service/api/v1/instruments/by-type?type=${enc(type)}",
                    15000, bcsHeaders()
                ))
                val arr = root.optJSONArray("instruments") ?: return@runCatching
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val ticker = o.optString("ticker").trim()
                    if (ticker.isBlank()) continue
                    val boards = o.optJSONArray("boards")
                    val board = boards?.optJSONObject(0)?.optString("classCode").orEmpty()
                    val exchange = boards?.optJSONObject(0)?.optString("exchange").orEmpty().ifBlank { "BCS" }
                    val name = o.optString("displayName")
                        .ifBlank { o.optString("shortName") }
                        .ifBlank { o.optString("issuerName") }
                        .ifBlank { ticker }
                    val typeName = o.optString("instrumentType").ifBlank { type }
                    val item = SearchResult(ticker, name, exchange, typeName, "БКС")
                    out.putIfAbsent(item.symbol.uppercase(Locale.US), item)
                }
            }
        }
        val result = out.values.toList()
        catalogCache = result
        catalogCacheAt = System.currentTimeMillis()
        return if (limit == Int.MAX_VALUE) result else result.take(limit)
    }

    /** BCS-only FX catalogue. */
    fun fxCatalog(): List<SearchResult> = if (!isBcsConfigured()) emptyList() else
        catalog(Int.MAX_VALUE).filter { it.type.contains("CURRENCY", true) }

    private fun popularSeeds(): List<SearchResult> = listOf(
        "SBER", "GAZP", "LKOH", "ROSN", "NVTK", "TATN", "MGNT", "MOEX", "YDEX", "OZON",
        "USDRUB=X", "EURRUB=X", "CNYRUB=X"
    ).map { SearchResult(it, it, "BCS", "", "БКС") }

    private fun normalizeFx(q: String): SearchResult? {
        val x = q.uppercase(Locale.US).replace("/", "").replace("-", "").replace(" ", "")
        if (x.length != 6 || !x.all { it.isLetter() }) return null
        val base = x.take(3); val quote = x.takeLast(3)
        return if (bcsFx("${base}${quote}=X") != null)
            SearchResult("${base}${quote}=X", "$base/$quote", "BCS", "CURRENCY", "БКС")
        else null
    }

    /** Multi-source market news. The UI must still receive a feed if one provider is unavailable. */
    fun marketNews(limit: Int = 30, ru: Boolean = true, category: NewsCategory = NewsCategory.ALL): List<NewsItem> {
        val candidates = mutableListOf<NewsItem>()
        // RSS is preferred because it is structured and cheap. If RBC is blocked/unavailable,
        // HTML feeds provide a real fallback instead of returning an empty UI.
        runCatching { candidates += parseRss(getText("https://rssexport.rbc.ru/rbcnews/news/30/full.rss", 9000), "РБК", category, limit * 3) }
        if (candidates.size < limit) runCatching { candidates += finamMarketNews(limit * 2, category) }
        if (candidates.size < limit) runCatching { candidates += moexNews(limit * 2, category) }

        val unique = LinkedHashMap<String, NewsItem>()
        candidates
            .filter { category == NewsCategory.ALL || it.category == category }
            .sortedWith(compareByDescending<NewsItem> { it.publishedAt > 0L }.thenByDescending { it.publishedAt })
            .forEach { n -> unique.putIfAbsent(n.url.ifBlank { n.originalTitle }, n) }

        return unique.values.take(limit).map { n ->
            // Translation is best-effort; a translation endpoint failure must never hide news.
            val title = runCatching { translate(n.originalTitle, if (ru) "ru" else "en") }.getOrDefault(n.originalTitle).ifBlank { n.originalTitle }
            val body = if (n.body.isBlank()) "" else runCatching { translate(n.body, if (ru) "ru" else "en") }.getOrDefault(n.body)
            n.copy(title = title, body = body)
        }
    }

    fun news(query: String, limit: Int = 12, ru: Boolean = true): List<NewsItem> =
        marketNews(100, ru, NewsCategory.ALL).filter { mentionsInstrument(it, query) }.take(limit)

    private fun parseRss(xml: String, source: String, requested: NewsCategory, maxItems: Int): List<NewsItem> {
        val parser = Xml.newPullParser(); parser.setInput(StringReader(xml)); val out = mutableListOf<NewsItem>()
        var event = parser.eventType; var inItem=false; var tag=""; var title=""; var desc=""; var link=""; var pub=0L
        fun categoryOf(text:String):NewsCategory { val low=text.lowercase(Locale("ru")); val fx=listOf("доллар","евро","юань","рубл","валют","форекс","курс usd","курс eur","usdrub","eurrub","cnyrub","фунт","иена","цб рф").any{low.contains(it)}; val stock=listOf("акци","сбер","газпром","лукойл","роснефт","новатэк","яндекс","озон","циан","татнефт","сургут","мосбирж","втб","магнит","норникел","полюс","ростелеком","алроса","полиметалл","мтс","интер рао","фосагро","совкомфлот","аэрофлот","дивиденд").any{low.contains(it)}; return when { stock&&!fx->NewsCategory.STOCKS; fx&&!stock->NewsCategory.FX; stock&&fx->if(requested==NewsCategory.FX)NewsCategory.FX else NewsCategory.STOCKS; else->NewsCategory.ALL } }
        while(event != XmlPullParser.END_DOCUMENT && out.size < maxItems){
            when(event){
                XmlPullParser.START_TAG->{tag=parser.name.lowercase(Locale.US);if(tag=="item"){inItem=true;title="";desc="";link="";pub=0L}}
                XmlPullParser.TEXT,XmlPullParser.CDSECT->if(inItem)when(tag){"title"->title+=parser.text;"description","summary","encoded"->desc+=parser.text;"link","guid"->if(link.isBlank())link+=parser.text;"pubdate","published","date"->if(pub==0L)pub=parseRssDate(parser.text)}
                XmlPullParser.END_TAG->if(inItem&&parser.name.equals("item",true)){val t=stripHtml(title);val b=stripHtml(desc);val cat=categoryOf("$t $b");if(t.length>=8&&link.isNotBlank()&&(requested==NewsCategory.ALL||cat==requested))out+=NewsItem(t,source,htmlDecode(link.trim()),pub,t,cat,b,extractInstrument(t,b));inItem=false;tag=""}
            };event=parser.next()
        };return out
    }
    private fun parseRssDate(v:String):Long{val fs=listOf("EEE, dd MMM yyyy HH:mm:ss Z","EEE, dd MMM yyyy HH:mm Z","yyyy-MM-dd'T'HH:mm:ssXXX","yyyy-MM-dd'T'HH:mm:ss.SSSXXX");for(f in fs){val x=runCatching{SimpleDateFormat(f,Locale.US).parse(v.trim())?.time}.getOrNull();if(x!=null)return x};return 0L}
    private fun extractInstrument(title:String,body:String):String{val text="$title $body".uppercase(Locale.US);val a=mapOf("SBER" to "SBER","СБЕР" to "SBER","ГАЗПРОМ" to "GAZP","GAZP" to "GAZP","ЛУКОЙЛ" to "LKOH","LKOH" to "LKOH","РОСНЕФТЬ" to "ROSN","НОВАТЭК" to "NVTK","ЯНДЕКС" to "YDEX","ОЗОН" to "OZON","ЦИАН" to "CIAN","CIAN" to "CIAN","ДОЛЛАР" to "USDRUB=X","ЕВРО" to "EURRUB=X","ЮАН" to "CNYRUB=X");return a.entries.firstOrNull{text.contains(it.key)}?.value.orEmpty()}

    private fun finamMarketNews(limit:Int,category:NewsCategory):List<NewsItem>{val html=getText("https://www.finam.ru/publications/section/market/",15000);val out=mutableListOf<NewsItem>();val p=Pattern.compile("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",Pattern.DOTALL or Pattern.CASE_INSENSITIVE);val m=p.matcher(html);while(m.find()&&out.size<limit){val href=htmlDecode(m.group(1).orEmpty());val title=stripHtml(m.group(2).orEmpty());if(title.length<12)continue;val low=title.lowercase(Locale.US);val fx=listOf("рубл","доллар","евро","юань","валют","курс").any{low.contains(it)};val stock=listOf("акци","сбер","газпром","лукойл","роснефт","новатэк","дивиденд","яндекс","озон").any{low.contains(it)};val cat=when{fx&&!stock->NewsCategory.FX;stock->NewsCategory.STOCKS;else->NewsCategory.ALL};if(cat==NewsCategory.ALL||(category!=NewsCategory.ALL&&cat!=category))continue;val url=if(href.startsWith("http"))href else "https://www.finam.ru"+if(href.startsWith("/"))href else "/$href";out+=NewsItem(title,"Финам",url,0L,title,cat,"",extractInstrument(title,""))};return out.distinctBy{it.url}}
    private fun moexNews(limit:Int,category:NewsCategory):List<NewsItem>{val url=when(category){NewsCategory.STOCKS->"https://www.moex.com/ru/news/?ncat=111";NewsCategory.FX->"https://www.moex.com/ru/news/?ncat=118";else->"https://www.moex.com/ru/news/"};val html=runCatching{getText(url,15000)}.getOrNull()?:return emptyList();val p=Pattern.compile("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",Pattern.DOTALL or Pattern.CASE_INSENSITIVE);val out=mutableListOf<NewsItem>();val m=p.matcher(html);while(m.find()&&out.size<limit){val title=stripHtml(m.group(2).orEmpty());val href=htmlDecode(m.group(1).orEmpty());if(title.length<12)continue;val full=if(href.startsWith("http"))href else "https://www.moex.com"+href;out+=NewsItem(title,"Московская биржа",full,0L,title,category,"",extractInstrument(title,""))};return out.distinctBy{it.url}}

    fun marketToday(mode: String, limit: Int = 20): List<MarketPick> {
        // Market Today is latency-sensitive. Each instrument is isolated so one
        // provider failure cannot abort the whole scan.
        val seeds = listOf(
            "SBER.ME", "GAZP.ME", "LKOH.ME", "ROSN.ME", "NVTK.ME", "TATN.ME",
            "MGNT.ME", "MOEX.ME", "YDEX.ME", "OZON.ME", "PHOR.ME", "MTSS.ME",
            "IRAO.ME", "GMKN.ME", "NLMK.ME", "CHMF.ME", "ALRS.ME", "SNGS.ME",
            "RTKM.ME", "VTBR.ME", "AFLT.ME", "RUAL.ME", "USDRUB=X", "EURRUB=X",
            "CNYRUB=X", "EURUSD=X", "GBPUSD=X", "USDJPY=X"
        )
        val stockCandidates = seeds.filter { it.endsWith(".ME") }
        val fxCandidates = seeds.filter { it.endsWith("=X") }
        val candidates = when (mode) {
            "FX" -> fxCandidates
            "STOCKS", "LIQUID", "GROWTH", "FALL" -> stockCandidates
            else -> stockCandidates + fxCandidates
        }.distinct()

        val futures = candidates.map { symbol ->
            // Keep the worker body as a single expression. Besides being simpler, this
            // avoids Kotlin parser edge-cases around labelled returns inside nested
            // ExecutorService lambdas and makes one bad instrument fully isolated.
            analysisPool.submit<MarketPick?> {
                runCatching {
                    val candles = load(symbol, "2y", "1d")
                    if (candles.size < 30) {
                        null
                    } else {
                        val live = reconcileLivePrice(symbol, candles, quote(symbol))
                        if (!live.isFinite() || live <= 0.0) {
                            null
                        } else {
                            val forecast = AnalyticsEngine.analyze(candles, live)
                            val previous = candles.dropLast(1).lastOrNull()?.close ?: candles.last().open
                            val changePct = if (previous > 0.0) {
                                (live - previous) / previous * 100.0
                            } else {
                                0.0
                            }
                            val type = if (symbol.endsWith("=X")) "FX" else "STOCK"

                            MarketPick(
                                symbol = symbol,
                                name = symbol.removeSuffix(".ME"),
                                price = live,
                                signal = forecast.signal,
                                confidence = forecast.confidence,
                                score = forecast.score,
                                type = type,
                                changePct = changePct,
                                volume = candles.last().volume
                            )
                        }
                    }
                }.getOrNull()
            }
        }

        val picks = futures.mapNotNull { future ->
            try {
                future.get(18, TimeUnit.SECONDS)
            } catch (_: Throwable) {
                null
            }
        }

        val filtered = when (mode) {
            "STOCKS" -> picks.filter { it.type == "STOCK" }
            "FX" -> picks.filter { it.type == "FX" }
            "LIQUID" -> picks.filter { it.type == "STOCK" }.sortedByDescending { it.volume }
            "GROWTH" -> picks.filter { it.type == "STOCK" }.sortedByDescending { it.changePct }
            "FALL" -> picks.filter { it.type == "STOCK" }.sortedBy { it.changePct }
            else -> picks
        }

        return if (mode == "LIQUID" || mode == "GROWTH" || mode == "FALL") {
            filtered.take(limit.coerceAtMost(16))
        } else {
            filtered
                .sortedWith(
                    compareByDescending<MarketPick> { it.confidence }
                        .thenByDescending { abs(it.score) }
                )
                .take(limit.coerceAtMost(16))
        }
    }

    fun marketIndices(): List<MarketIndex> {
        if (!isBcsConfigured()) return emptyList()
        return catalog(Int.MAX_VALUE).filter { it.type.contains("INDEX", true) }.take(12).mapNotNull { item ->
            runCatching {
                val candles = load(item.symbol, "1y", "1d")
                val last = candles.lastOrNull() ?: return@runCatching null
                val prev = candles.dropLast(1).lastOrNull()?.close ?: last.close
                val live = quote(item.symbol) ?: last.close
                MarketIndex(item.symbol, item.name, live, if (prev == 0.0) 0.0 else (live - prev) / prev * 100.0, "БКС")
            }.getOrNull()
        }
    }

    fun quote(symbol: String): Double? {
        val clean = symbol.trim().uppercase(Locale.US)
        val now = System.currentTimeMillis()
        val quoteKey = "${if (bcsRefreshToken.isNullOrBlank()) "LEGACY" else "BCS"}|$clean"
        val cached = quoteCache[quoteKey]
        // A quote is the canonical "now" price. It is shared by every timeframe;
        // timeframe candles are never allowed to become the displayed live price.
        if (cached != null && now - cached.first <= QUOTE_CACHE_MS && cached.second.isFinite() && cached.second > 0.0) {
            return cached.second
        }
        val value = if (!bcsRefreshToken.isNullOrBlank()) {
            if (!bcsSupported(clean)) throw IllegalStateException("БКС не поддерживает инструмент $symbol для прогнозов")
            runCatching { quoteBcs(clean) }.getOrElse {
                throw IllegalStateException("БКС: не удалось получить котировку $symbol: ${it.message ?: "ошибка источника"}")
            }?.takeIf { it.isFinite() && it > 0.0 }
                ?: throw IllegalStateException("БКС не вернул актуальную цену для $symbol")
        } else {
            throw IllegalStateException("БКС не подключен. Прогнозы и live-цены доступны только через БКС.")
        }
        if (value != null) quoteCache[quoteKey] = now to value
        return value
    }


    fun isBcsConfigured(): Boolean = !bcsRefreshToken.isNullOrBlank()

    fun forecastSource(symbol: String): String {
        val clean = symbol.trim().uppercase(Locale.US)
        return if (!isBcsConfigured()) "БКС не подключен"
        else if (!bcsSupported(clean)) "БКС: инструмент не поддерживается"
        else "БКС • единственный источник"
    }

    fun instrumentMeta(symbol: String): InstrumentMeta {
        val clean = symbol.trim().uppercase(Locale.US)
        if (!isBcsConfigured()) return InstrumentMeta(clean, clean, "RUB", 1, "price")
        runCatching { bcsInstrumentInfo(clean) }.getOrNull()?.let { o ->
            val ticker = o.optString("ticker").ifBlank { clean.removeSuffix(".ME") }
            val name = o.optString("displayName").ifBlank { o.optString("shortName") }.ifBlank { ticker }
            val currency = o.optString("tradingCurrency").ifBlank { o.optString("currency") }.ifBlank { "RUB" }
            val lot = o.optDouble("lotSize", 1.0).toInt().coerceAtLeast(1)
            return InstrumentMeta(ticker, name, currency, lot, if (clean.contains("=X")) "price" else "price")
        }
        return InstrumentMeta(clean.removeSuffix(".ME"), clean.removeSuffix(".ME"), "RUB", 1, "price")
    }

    fun dividendCalendar(query: String? = null, limit: Int = 100): List<DividendEvent> {
        if (!isBcsConfigured()) return emptyList()
        return runCatching { bcsDividendCalendar(limit * 2) }.getOrDefault(emptyList())
            .filter { query.isNullOrBlank() || it.symbol.contains(query, true) }
            .filter { it.date > 0 && it.amount > 0 }
            .distinctBy { "${it.symbol}|${it.date}|${it.amount}" }
            .sortedBy { it.date }.take(limit)
    }

    fun bcsDividendCalendarUrl(): String = BCS_DIVIDEND_CALENDAR_URL

    private fun aggregateMinutes(candles: List<Candle>, minutes: Int): List<Candle> {
        if (candles.isEmpty()) return emptyList()
        val step = minutes * 60_000L
        val out = mutableListOf<Candle>()
        var bucketStart = Long.MIN_VALUE
        var group = mutableListOf<Candle>()
        fun flush() {
            if (group.isNotEmpty()) {
                val first = group.first(); val last = group.last()
                out += Candle(bucketStart, first.open, group.maxOf { it.high }, group.minOf { it.low }, last.close, group.sumOf { it.volume })
            }
            group = mutableListOf()
        }
        for (c in candles.sortedBy { it.time }) {
            val b = (c.time / step) * step
            if (bucketStart != Long.MIN_VALUE && b != bucketStart) flush()
            if (bucketStart != b) bucketStart = b
            group += c
        }
        flush()
        return out
    }

    private fun normalizeInterval(candles: List<Candle>, interval: String): List<Candle> {
        val sorted = candles.sortedBy { it.time }
        if (interval != "4h" || sorted.size < 4) return sorted
        val out = mutableListOf<Candle>(); var group = mutableListOf<Candle>(); var prevTime = 0L
        fun flush() { if (group.size == 4) out += Candle(group.first().time, group.first().open, group.maxOf{it.high}, group.minOf{it.low}, group.last().close, group.sumOf{it.volume}); group = mutableListOf() }
        for (c in sorted) {
            // Do not merge across market-session gaps; that would manufacture a false 4H candle.
            if (group.isNotEmpty() && c.time - prevTime > 2 * 60 * 60 * 1000L) flush()
            group += c; prevTime = c.time
            if (group.size == 4) flush()
        }
        return out
    }

    private val bcsInstrumentCache = ConcurrentHashMap<String, Pair<String, String>>()

    private fun bcsSupported(symbol: String): Boolean {
        if (!isBcsConfigured()) return false
        if (bcsFx(symbol) != null) return true
        val clean = symbol.uppercase(Locale.US).removeSuffix(".ME").substringBefore("@")
        return runCatching { bcsInstrument(clean) }.isSuccess
    }

    private fun bcsInstrument(symbol: String): Pair<String, String> {
        val clean = symbol.uppercase(Locale.US).removeSuffix(".ME")
        val explicitBoard = clean.substringAfter("@", "")
        val ticker = clean.substringBefore("@")
        val fx = bcsFx(symbol)
        if (fx != null) return fx to "CETS"
        bcsInstrumentCache[ticker]?.let { return it }
        if (explicitBoard.isNotBlank()) return ticker to explicitBoard
        val info = bcsInstrumentInfo(ticker)
        val boards = info.optJSONArray("boards")
        val board = boards?.optJSONObject(0)?.optString("classCode").orEmpty().ifBlank { "TQBR" }
        val pair = ticker to board
        bcsInstrumentCache[ticker] = pair
        return pair
    }

    private fun bcsInstrumentInfo(symbol: String): JSONObject {
        val ticker = symbol.uppercase(Locale.US).removeSuffix(".ME").substringBefore("@")
        val body = JSONObject().put("tickers", JSONArray().put(ticker)).toString()
        val root = JSONObject(postJson("https://be.broker.ru/trade-api-information-service/api/v1/instruments/by-tickers", body, 12000, bcsHeaders()))
        val arr = root.optJSONArray("instruments") ?: throw IllegalStateException("БКС: инструмент $ticker не найден")
        return arr.optJSONObject(0) ?: throw IllegalStateException("БКС: инструмент $ticker не найден")
    }

    private fun bcsFx(symbol: String): String? = when (symbol.uppercase(Locale.US)) {
        "USDRUB=X", "USD/RUB", "USDRUB" -> "USD000UTSTOM"
        "EURRUB=X", "EUR/RUB", "EURRUB" -> "EUR_RUB__TOM"
        "CNYRUB=X", "CNY/RUB", "CNYRUB" -> "CNYRUB_TOM"
        else -> null
    }

    @Volatile private var bcsAccessToken: String? = null
    @Volatile private var bcsAccessExpiresAt: Long = 0L
    @Volatile private var bcsAccessRefreshFingerprint: Int = 0

    private fun bcsAccessToken(): String? {
        val refresh = bcsRefreshToken?.trim().orEmpty()
        if (refresh.isBlank()) return null
        val now = System.currentTimeMillis()
        val fingerprint = refresh.hashCode()
        val cached = bcsAccessToken
        if (bcsAccessRefreshFingerprint == fingerprint && !cached.isNullOrBlank() && now < bcsAccessExpiresAt - 60_000L) return cached
        val body = "client_id=trade-api-read&refresh_token=${enc(refresh)}&grant_type=refresh_token"
        val text = postForm("https://be.broker.ru/trade-api-keycloak/realms/tradeapi/protocol/openid-connect/token", body, 12000)
        val json = JSONObject(text)
        val token = json.optString("access_token").takeIf { it.isNotBlank() } ?: return null
        bcsAccessToken = token
        bcsAccessRefreshFingerprint = fingerprint
        bcsAccessExpiresAt = now + json.optLong("expires_in", 86400L) * 1000L
        return token
    }

    private fun bcsHeaders(): Map<String,String> = mapOf("Authorization" to "Bearer ${bcsAccessToken() ?: throw IllegalStateException("BCS: не удалось получить access token")}", "Accept" to "application/json")

    private fun loadBcs(symbol: String, range: String, interval: String): List<Candle> {
        val (ticker, classCode) = bcsInstrument(symbol)
        val tf = when (interval) { "1m" -> "M1"; "5m" -> "M5"; "15m" -> "M15"; "30m" -> "M30"; "1h" -> "H1"; "4h" -> "H4"; "1wk" -> "W"; else -> "D" }
        val days = when (range) { "60d" -> 60; "2y" -> 730; "10y" -> 3650; else -> 3650 }
        val end = System.currentTimeMillis()
        val start = end - days * 86_400_000L
        // BCS limits a single candle response to 1440 bars. Keep requests bounded.
        val maxSpan = when (tf) { "M1" -> 1; "M5" -> 5; "M15" -> 15; "M30" -> 30; "H1" -> 60; "H4" -> 240; "D" -> 1440; "W" -> 10080; else -> 43200 }
        val out = mutableListOf<Candle>()
        val chunkMs = maxOf(86_400_000L, (1440L * maxSpan) * 60_000L)
        var cursor = start
        while (cursor < end && out.size < 5000) {
            val chunkEnd = minOf(end, cursor + chunkMs)
            val url = "https://be.broker.ru/trade-api-market-data-connector/api/v1/candles-chart?ticker=${enc(ticker)}&classCode=${enc(classCode)}&timeFrame=$tf&startDate=${enc(isoUtc(cursor))}&endDate=${enc(isoUtc(chunkEnd))}"
            val root = JSONObject(getTextAuth(url, 15000, bcsHeaders()))
            out += parseBcsCandles(root)
            cursor = chunkEnd + 1000L
        }
        return out.distinctBy { it.time }.sortedBy { it.time }.takeLast(5000)
    }

    private fun parseBcsCandles(root: JSONObject): List<Candle> {
        val arrays = mutableListOf<JSONArray>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> v.keys().forEach { k -> walk(v.opt(k)) }
                is JSONArray -> { arrays += v; for (i in 0 until v.length()) walk(v.opt(i)) }
            }
        }
        walk(root)
        val arr = arrays.sortedByDescending { it.length() }.firstOrNull() ?: return emptyList()
        val out = mutableListOf<Candle>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val open = num(o, "open", "Open"); val high = num(o, "high", "High"); val low = num(o, "low", "Low"); val close = num(o, "close", "Close")
            if (open == null || high == null || low == null || close == null) continue
            val time = dateValue(o, "time", "dateTime", "begin", "timestamp") ?: continue
            val volume = num(o, "volume", "Volume", "value") ?: 0.0
            out += Candle(time, open, high, low, close, volume)
        }
        return out
    }

    private fun quoteBcs(symbol: String): Double? {
        val (ticker, classCode) = bcsInstrument(symbol)
        val body = "{\"instruments\":[{\"ticker\":\"${ticker.replace("\"", "") }\",\"classCode\":\"${classCode}\"}]}"
        val root = JSONObject(postJson("https://be.broker.ru/trade-api-market-data-connector/api/v1/quotes", body, 10000, bcsHeaders()))
        val candidates = mutableListOf<Double>()
        fun walk(v: Any?, key: String = "") {
            when (v) {
                is JSONObject -> v.keys().forEach { k -> walk(v.opt(k), k.lowercase(Locale.US)) }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), key)
                is Number -> if (key in setOf("last","lastprice","last_price","price","currentprice","current_price")) candidates += v.toDouble()
            }
        }
        walk(root)
        return candidates.firstOrNull { it.isFinite() && it > 0.0 }
    }

    private fun num(o: JSONObject, vararg names: String): Double? = names.firstNotNullOfOrNull { n -> if (o.has(n) && !o.isNull(n)) o.optDouble(n, Double.NaN).takeIf { it.isFinite() } else null }
    private fun dateValue(o: JSONObject, vararg names: String): Long? {
        for (n in names) if (o.has(n) && !o.isNull(n)) {
            val v = o.opt(n)
            if (v is Number) return if (v.toLong() < 10_000_000_000L) v.toLong() * 1000L else v.toLong()
            val s = v.toString()
            runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()?.let { return it }
        }
        return null
    }
    private fun isoUtc(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()

    private fun translate(text:String,target:String):String{if(text.isBlank())return text; if(target=="en" && text.all{it.code<128})return text; return runCatching{val root=JSONArray(getText("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$target&dt=t&q=${enc(text)}",9000)); val parts=root.optJSONArray(0)?:return@runCatching text; buildString{for(i in 0 until parts.length()){append(parts.optJSONArray(i)?.optString(0).orEmpty())}}.ifBlank{text}}.getOrDefault(text)}

    private fun mentionsInstrument(n: NewsItem, q: String): Boolean {
        val clean=q.removeSuffix(".ME").replace("=X","").uppercase(Locale.US)
        if (clean.isBlank()) return false
        val text=(n.originalTitle+" "+n.body).uppercase(Locale.US)
        if (n.instrument.equals(clean, true)) return true
        val aliases=mapOf("SBER" to listOf("СБЕР","СБЕРБАНК","SBER"),"GAZP" to listOf("ГАЗПРОМ","GAZPROM","GAZP"),"LKOH" to listOf("ЛУКОЙЛ","LKOH"),"USDRUB" to listOf("ДОЛЛАР","USD/RUB","USDRUB","РУБЛ"),"EURRUB" to listOf("ЕВРО","EUR/RUB","EURRUB"),"CIAN" to listOf("ЦИАН","CIAN"))
        return (aliases[clean] ?: listOf(clean)).any { text.contains(it) }
    }

    private fun htmlDecode(v:String)=v.replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'").replace("&nbsp;"," ").replace("&lt;","<").replace("&gt;",">")

    private fun bcsDividendCalendar(limit: Int): List<DividendEvent> {
        val html = getText(bcsDividendCalendarUrl(), 15000)
        val clean = html.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
        val rowPattern = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val cellPattern = Pattern.compile("<(?:td|th)[^>]*>(.*?)</(?:td|th)>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val out = mutableListOf<DividendEvent>()
        val rows = rowPattern.matcher(clean)
        while (rows.find() && out.size < limit * 3) {
            val row = rows.group(1) ?: continue
            val cells = mutableListOf<String>()
            val cm = cellPattern.matcher(row)
            while (cm.find()) cells += stripHtml(cm.group(1).orEmpty())
            if (cells.size < 2) continue
            val dateIndex = cells.indexOfFirst { it.matches(Regex(".*\\b\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}\\b.*")) }
            val amountIndex = cells.indexOfFirst { it.replace(',', '.').matches(Regex(".*\\b\\d+[.,]\\d+\\b.*")) }
            if (dateIndex < 0 || amountIndex < 0) continue
            val date = parseDividendDate(cells[dateIndex]) ?: continue
            val amount = Regex("[-+]?\\d+[.,]\\d+").find(cells[amountIndex])?.value?.replace(',', '.')?.toDoubleOrNull() ?: continue
            val name = cells.firstOrNull { it.length >= 2 && it != cells[dateIndex] && it != cells[amountIndex] && it.any(Char::isLetter) } ?: continue
            val symbol = Regex("\\b[A-Z]{2,6}(?:\\.[A-Z]{2})?\\b").find(cells.joinToString(" "))?.value ?: name.take(24)
            out += DividendEvent(symbol, date, amount, "БКС Экспресс")
        }
        if (out.isEmpty()) {
            val text = stripHtml(clean)
            val p = Pattern.compile("([^|]{2,80})\\s*\\(([A-Z]{2,8}P?)\\).*?(\\d{1,3}(?:[.,]\\d+)?)\\s*(?:Rub|руб).*?(\\d{1,2}\\.\\d{1,2}\\.\\d{4})", Pattern.CASE_INSENSITIVE)
            val m = p.matcher(text)
            while (m.find() && out.size < limit) {
                val date=parseDividendDate(m.group(4)) ?: continue; val amount=m.group(3).replace(',','.').toDoubleOrNull() ?: continue; out += DividendEvent(m.group(2).uppercase(Locale.US),date,amount,"БКС Экспресс")
            }
        }
        return out.distinctBy { "${it.symbol}|${it.date}|${it.amount}" }.take(limit)
    }

    private fun stripHtml(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun parseDividendDate(value: String): Long? {
        val m = Regex("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})").find(value) ?: return null
        val day = m.groupValues[1].toIntOrNull() ?: return null
        val month = m.groupValues[2].toIntOrNull() ?: return null
        var year = m.groupValues[3].toIntOrNull() ?: return null
        if (year < 100) year += 2000
        return runCatching { java.util.Calendar.getInstance().apply { set(year, month - 1, day, 12, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis }.getOrNull()
    }

    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8")
    private fun postForm(url: String, body: String, timeout: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = timeout; c.readTimeout = timeout; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return readResponse(c)
    }
    private fun postJson(url: String, body: String, timeout: Int, headers: Map<String,String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = timeout; c.readTimeout = timeout; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json"); headers.forEach { (k,v) -> c.setRequestProperty(k,v) }
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return readResponse(c)
    }
    private fun getTextAuth(url: String, timeout: Int, headers: Map<String,String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = timeout; c.readTimeout = timeout; headers.forEach { (k,v) -> c.setRequestProperty(k,v) }
        return readResponse(c)
    }
    private fun readResponse(c: HttpURLConnection): String {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(240)}")
        return text
    }
    private fun getText(url:String,timeout:Int):String{val c=URL(url).openConnection() as HttpURLConnection; c.requestMethod="GET";c.connectTimeout=timeout;c.readTimeout=timeout;c.setRequestProperty("User-Agent",ua);c.setRequestProperty("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");c.setRequestProperty("Accept-Language","ru-RU,ru;q=0.9,en;q=0.5");c.setRequestProperty("Accept-Encoding","gzip");try{if(c.responseCode !in 200..299)throw IllegalStateException("HTTP ${c.responseCode}");val raw=c.inputStream;val input=if(c.contentEncoding?.contains("gzip",true)==true) GZIPInputStream(raw) else raw;return input.bufferedReader(Charsets.UTF_8).use{it.readText()}}finally{c.disconnect()}}
}
