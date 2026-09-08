package com.marketforecast.prox

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

    // ONLY MOEX is accepted for the canonical realtime price on MOEX instruments.
    // The quote is the single canonical "now" price. Do not substitute a previous
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
        private const val QUOTE_CACHE_MS = 500L
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
        val cacheKey = "$clean|$range|$interval"
        val cached = candleCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first <= CANDLE_CACHE_MS && cached.second.isNotEmpty()) {
            return cached.second
        }
        val moex = clean.removeSuffix(".ME")
        // BCS is the optional canonical broker feed. When configured, both live
        // quotes and historical candles come from the same feed, preventing
        // cross-provider/timeframe price drift.
        if (!bcsRefreshToken.isNullOrBlank() && bcsSupported(clean)) {
            runCatching { loadBcs(clean, range, interval) }.getOrNull()?.let { raw ->
                val normalized = normalizeInterval(raw, interval)
                if (normalized.isNotEmpty()) {
                    candleCache[cacheKey] = System.currentTimeMillis() to normalized
                    return normalized
                }
            }
        }
        // SOURCE INTEGRITY: a MOEX instrument must never mix MOEX history with a
        // Yahoo/other-provider history. Mixing feeds is a direct cause of apparent
        // price jumps and of forecasts whose reference price differs from the quote.
        // MOEX is therefore authoritative for Russian exchange instruments.
        val providerInterval = if (interval == "4h") "1h" else interval
        if (isLikelyMoex(clean)) {
            val raw = if (providerInterval == "15m") {
                val oneMinute = runCatching { loadMoex(moex, "1m") }.getOrNull().orEmpty()
                aggregateMinutes(oneMinute, 15)
            } else {
                runCatching { loadMoex(moex, providerInterval) }.getOrNull().orEmpty()
            }
            val normalized = normalizeInterval(raw, interval)
            if (normalized.isNotEmpty()) {
                candleCache[cacheKey] = System.currentTimeMillis() to normalized
                return normalized
            }
            throw IllegalStateException("MOEX не вернул свечи для $symbol ($interval)")
        }
        runCatching { loadYahoo(clean, range, providerInterval) }.getOrNull()?.let { raw ->
            val normalized = normalizeInterval(raw, interval); if (normalized.isNotEmpty()) { candleCache[cacheKey] = System.currentTimeMillis() to normalized; return normalized }
        }
        if (!interval.contains("m") && !interval.contains("h")) {
            runCatching { loadStooq(clean, range) }.getOrNull()?.let { raw ->
                val normalized = normalizeInterval(raw, interval); if (normalized.isNotEmpty()) { candleCache[cacheKey] = System.currentTimeMillis() to normalized; return normalized }
            }
        }
        if (!alphaVantageKey.isNullOrBlank()) {
            runCatching { loadAlpha(clean, providerInterval) }.getOrNull()?.let { raw ->
                val normalized = normalizeInterval(raw, interval); if (normalized.isNotEmpty()) { candleCache[cacheKey] = System.currentTimeMillis() to normalized; return normalized }
            }
        }
        throw IllegalStateException("Нет данных для $symbol. Проверь тикер или доступность поставщиков.")
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
        if (q.length < 2) return emptyList()
        val merged = linkedMapOf<String, SearchResult>()
        val fx = normalizeFx(q)
        if (fx != null) merged[fx.symbol] = fx
        runCatching { searchMoex(q) }.getOrDefault(emptyList()).forEach { merged[it.symbol.uppercase(Locale.US)] = it }
        runCatching { searchYahoo(q) }.getOrDefault(emptyList()).forEach { merged.putIfAbsent(it.symbol.uppercase(Locale.US), it) }
        if (!alphaVantageKey.isNullOrBlank()) runCatching { searchAlpha(q) }.getOrDefault(emptyList()).forEach { merged.putIfAbsent(it.symbol.uppercase(Locale.US), it) }
        return merged.values.take(30)
    }

    /** Full dynamic MOEX catalogue. Pagination is used so the scanner never truncates the universe. */
    fun catalog(limit: Int = Int.MAX_VALUE): List<SearchResult> {
        val now = System.currentTimeMillis()
        val cached = catalogCache
        if (cached.isNotEmpty() && now - catalogCacheAt <= CATALOG_CACHE_MS) {
            return if (limit == Int.MAX_VALUE) cached else cached.take(limit)
        }
        val out = LinkedHashMap<String, SearchResult>()
        var start = 0
        val pageSize = 1000
        while (true) {
            val url = "https://iss.moex.com/iss/securities.json?iss.meta=off&iss.only=securities&securities.columns=secid,shortname,emitent_title&start=$start&limit=$pageSize"
            val root = runCatching { JSONObject(getText(url, 15000)) }.getOrNull() ?: break
            val block = root.optJSONObject("securities") ?: break
            val cols = block.optJSONArray("columns") ?: break
            val data = block.optJSONArray("data") ?: break
            val idx = (0 until cols.length()).associateBy({ cols.getString(it) }, { it })
            if (data.length() == 0) break
            for (i in 0 until data.length()) {
                val r = data.optJSONArray(i) ?: continue
                val symbol = r.optString(idx["secid"] ?: -1).trim()
                val name = r.optString(idx["shortname"] ?: -1).ifBlank { r.optString(idx["emitent_title"] ?: -1) }
                if (symbol.isNotBlank() && symbol.length <= 20 && name.isNotBlank()) {
                    val item = SearchResult("${symbol}.ME", name, "MOEX", "EQUITY", "MOEX")
                    out.putIfAbsent(item.symbol.uppercase(Locale.US), item)
                }
            }
            if (data.length() < pageSize || out.size >= limit) break
            start += pageSize
        }
        popularSeeds().forEach { out.putIfAbsent(it.symbol.uppercase(Locale.US), it) }
        val result = out.values.toList()
        catalogCache = result
        catalogCacheAt = System.currentTimeMillis()
        return if (limit == Int.MAX_VALUE) result else result.take(limit)
    }

    /** Complete practical FX universe supported by Yahoo Finance symbol convention. */
    fun fxCatalog(): List<SearchResult> {
        val fiat = listOf("USD","EUR","GBP","JPY","CHF","CAD","AUD","NZD","CNY","HKD","SGD","SEK","NOK","DKK","PLN","CZK","HUF","TRY","RUB","ZAR","MXN","BRL","INR","KRW","ILS")
        return fiat.flatMap { base -> fiat.filter { it != base }.map { quote ->
            SearchResult("${base}${quote}=X", "$base/$quote", "FOREX", "CURRENCY", "Yahoo Finance")
        }}
    }

    private fun popularSeeds(): List<SearchResult> = listOf("SBER.ME","GAZP.ME","LKOH.ME","ROSN.ME","NVTK.ME","TATN.ME","MGNT.ME","MOEX.ME","YDEX.ME","OZON.ME","AAPL","MSFT","NVDA","AMZN","TSLA","EURUSD=X","GBPUSD=X","USDJPY=X","USDRUB=X").map { SearchResult(it, it.removeSuffix(".ME"), "", "", "seed") }

    private fun normalizeFx(q: String): SearchResult? {
        val x = q.uppercase(Locale.US).replace("/", "").replace("-", "").replace(" ", "")
        if (x.length != 6 || !x.all { it.isLetter() }) return null
        val base = x.take(3); val quote = x.takeLast(3)
        val fiat = setOf("USD","EUR","GBP","JPY","CHF","CAD","AUD","NZD","CNY","HKD","SGD","SEK","NOK","DKK","PLN","CZK","HUF","TRY","RUB","ZAR","MXN","BRL","INR","KRW","ILS")
        if (base !in fiat || quote !in fiat || base == quote) return null
        return SearchResult("${base}${quote}=X", "$base/$quote", "FOREX", "CURRENCY", "Yahoo Finance")
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
        val specs = listOf("^IMOEX" to "IMOEX", "^GSPC" to "S&P 500", "^IXIC" to "NASDAQ")
        return specs.mapNotNull { (symbol, name) ->
            runCatching {
                val candles = loadYahoo(symbol, "1mo", "1d")
                val last = candles.lastOrNull() ?: return@runCatching null
                val prev = candles.dropLast(1).lastOrNull()?.close ?: last.close
                MarketIndex(symbol, name, last.close, if (prev == 0.0) 0.0 else (last.close - prev) / prev * 100.0, "Yahoo Finance")
            }.getOrNull()
        }
    }

    fun quote(symbol: String): Double? {
        val clean = symbol.trim().uppercase(Locale.US)
        val now = System.currentTimeMillis()
        val cached = quoteCache[clean]
        // A quote is the canonical "now" price. It is shared by every timeframe;
        // timeframe candles are never allowed to become the displayed live price.
        if (cached != null && now - cached.first <= QUOTE_CACHE_MS && cached.second.isFinite() && cached.second > 0.0) {
            return cached.second
        }
        val moex = clean.removeSuffix(".ME")
        val value = when {
            !bcsRefreshToken.isNullOrBlank() && bcsSupported(clean) -> runCatching { quoteBcs(clean) }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                ?: when {
                    isLikelyMoex(clean) -> runCatching { quoteMoex(moex) }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                    clean.endsWith("=X") -> runCatching { quoteAlfaForex(clean) }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                        ?: runCatching { quoteYahoo(clean) }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                    else -> runCatching { quoteYahoo(clean) }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                }
            isLikelyMoex(clean) -> runCatching { quoteMoex(moex) }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            clean.endsWith("=X") -> runCatching { quoteAlfaForex(clean) }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                ?: runCatching { quoteYahoo(clean) }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            else -> runCatching { quoteYahoo(clean) }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        }
        if (value != null) quoteCache[clean] = now to value
        return value
    }

    fun instrumentMeta(symbol: String): InstrumentMeta {
        val clean = symbol.trim().uppercase(Locale.US)
        val secid = clean.removeSuffix(".ME")
        if (isLikelyMoex(clean)) runCatching { moexMeta(secid) }.getOrNull()?.let { return it }
        val unit = if (clean.contains("^") || clean.contains("=X")) "points" else "price"
        return InstrumentMeta(clean, clean, if (clean.endsWith("=X")) "" else "USD", 1, unit)
    }

    private fun moexMeta(secid:String):InstrumentMeta {
        val url="https://iss.moex.com/iss/securities/${enc(secid)}.json?iss.meta=off&iss.only=securities&securities.columns=SECID,SHORTNAME,LOTSIZE,CURRENCYID"
        val root=JSONObject(getText(url,9000)); val b=root.optJSONObject("securities")?:return InstrumentMeta(secid)
        val cols=b.optJSONArray("columns")?:return InstrumentMeta(secid); val data=b.optJSONArray("data")?:return InstrumentMeta(secid); if(data.length()==0)return InstrumentMeta(secid)
        val idx=(0 until cols.length()).associateBy({cols.getString(it)},{it}); val r=data.optJSONArray(0)?:return InstrumentMeta(secid)
        fun s(n:String)=r.optString(idx[n]?:-1)
         fun i(n:String)=r.optInt(idx[n]?:-1,1).coerceAtLeast(1)
        return InstrumentMeta(secid,s("SHORTNAME").ifBlank{secid},s("CURRENCYID").ifBlank{"RUB"},i("LOTSIZE"),"price")
    }

    fun dividendCalendar(query: String? = null, limit: Int = 100): List<DividendEvent> {
        val all = mutableListOf<DividendEvent>()
        // Primary machine-readable Russian exchange data: current dividend fields from MOEX ISS.
        runCatching { all += moexBulkDividends(limit * 3) }
        // Secondary Russian sources. BCS is retained as requested, but is no longer the single point of failure.
        runCatching { all += finamDividendCalendar(limit * 2) }
        runCatching { all += bcsDividendCalendar(limit * 2) }
        if (all.size < limit) {
            val symbols = if (!query.isNullOrBlank()) listOf(query.trim().removeSuffix(".ME")) else popularSeeds().map { it.symbol.removeSuffix(".ME") }.distinct()
            for (symbol in symbols) { runCatching { all += moexDividends(symbol) }; if (all.size >= limit * 2) break }
        }
        return all.filter { query.isNullOrBlank() || it.symbol.contains(query, true) }
            .filter { it.date > 0 && it.amount > 0 }
            .distinctBy { "${it.symbol}|${it.date}|${it.amount}" }.sortedBy { it.date }.take(limit)
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

    private fun bcsSupported(symbol: String): Boolean = isLikelyMoex(symbol) || bcsFx(symbol) != null

    private fun bcsInstrument(symbol: String): Pair<String, String> {
        val clean = symbol.uppercase(Locale.US).removeSuffix(".ME")
        val fx = bcsFx(symbol)
        return if (fx != null) fx to "CETS" else clean to "TQBR"
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
        val tf = when (interval) { "1m" -> "M1"; "5m" -> "M5"; "15m" -> "M15"; "30m" -> "M30"; "1h" -> "H1"; "4h" -> "H4"; "1wk" -> "W1"; else -> "D1" }
        val days = when (range) { "60d" -> 60; "2y" -> 730; "10y" -> 3650; else -> 3650 }
        val end = System.currentTimeMillis()
        val start = end - days * 86_400_000L
        // BCS limits a single candle response to 1440 bars. Keep requests bounded.
        val maxSpan = when (tf) { "M1" -> 1; "M5" -> 5; "M15" -> 15; "M30" -> 30; "H1" -> 60; "H4" -> 240; "D1" -> 1440; else -> 10080 }
        val chunks = maxOf(1, ((days * 1440.0) / maxSpan / 1440.0).ceil().toInt())
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

    private fun isLikelyMoex(symbol: String): Boolean = symbol.endsWith(".ME") || symbol in setOf("SBER","GAZP","LKOH","NVTK","TATN","TATNP","MGNT","MOEX","ROSN","PHOR","MTSS","IRAO","TRMK","GMKN","NLMK","HYDR","CHMF","PLZL","ALRS","SNGS","SNGSP","RTKM","VTBR","AFLT","PIKK","RUAL","OZON","YDEX","HEAD")

    private fun loadMoex(secid: String, interval: String): List<Candle> {
        val moexInterval = when (interval) { "1m" -> 1; "1h" -> 60; "1wk" -> 7; else -> 24 }
        val days = if (interval == "1m") 10 else 3650
        val from = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - days * 86400000L))
        val url = "https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/${enc(secid)}/candles.json?interval=$moexInterval&from=$from&iss.meta=off"
        val root = JSONObject(getText(url, 12000)); val candles = root.optJSONObject("candles") ?: return emptyList()
        val cols = candles.optJSONArray("columns") ?: return emptyList(); val data = candles.optJSONArray("data") ?: return emptyList()
        val idx = (0 until cols.length()).associateBy({ cols.getString(it) }, { it })
        fun d(row: JSONArray, name: String): Double = row.optDouble(idx[name] ?: -1, Double.NaN)
        fun s(row: JSONArray, name: String): String = row.optString(idx[name] ?: -1)
        val out = mutableListOf<Candle>()
        for (i in 0 until data.length()) {
            val r = data.optJSONArray(i) ?: continue
            val o=d(r,"open"); val h=d(r,"high"); val l=d(r,"low"); val c=d(r,"close"); if (!o.isFinite()||!h.isFinite()||!l.isFinite()||!c.isFinite()) continue
            val time = parseMoexTime(s(r,"begin"))
            out += Candle(time, o,h,l,c,d(r,"value"))
        }
        return out
    }

    private fun loadYahoo(symbol: String, range: String, interval: String): List<Candle> {
        val bases = listOf("https://query1.finance.yahoo.com/v8/finance/chart/", "https://query2.finance.yahoo.com/v8/finance/chart/")
        var last: Exception? = null
        for (base in bases) try { return parseYahoo(getText("$base${enc(symbol)}?range=$range&interval=$interval&events=history", 12000)) } catch (e: Exception) { last=e }
        throw last ?: IllegalStateException("Yahoo Finance unavailable")
    }

    private fun loadStooq(symbol: String, range: String): List<Candle> {
        val s = when { symbol.endsWith(".ME") -> symbol.removeSuffix(".ME").lowercase(Locale.US)+".ru"; symbol.contains("=") -> return emptyList(); else -> symbol.lowercase(Locale.US)+".us" }
        val days = if (range.contains("5y")) 1900 else if (range.contains("2y")) 800 else 400
        val d1 = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(System.currentTimeMillis()-days*86400000L))
        val d2 = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val lines=getText("https://stooq.com/q/d/l/?s=${enc(s)}&d1=$d1&d2=$d2&i=d",12000).lines().drop(1)
        return lines.mapNotNull { line ->
            val p=line.split(','); if(p.size<6) null else runCatching { Candle(SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(p[0])!!.time,p[1].toDouble(),p[2].toDouble(),p[3].toDouble(),p[4].toDouble(),p[5].toDouble()) }.getOrNull()
        }
    }

    private fun loadAlpha(symbol: String, interval: String): List<Candle> {
        val key=alphaVantageKey ?: return emptyList(); val func=if(interval=="1d"||interval=="1wk") "TIME_SERIES_DAILY" else "TIME_SERIES_INTRADAY"
        val iv=if(func=="TIME_SERIES_INTRADAY") "&interval=60min" else ""
        val root=JSONObject(getText("https://www.alphavantage.co/query?function=$func&symbol=${enc(symbol.removeSuffix(".ME"))}$iv&outputsize=compact&apikey=${enc(key)}",12000))
        val series=root.keys().asSequence().firstOrNull { it.startsWith("Time Series") } ?: return emptyList(); val obj=root.optJSONObject(series) ?: return emptyList(); val out=mutableListOf<Candle>()
        val keys=obj.keys(); while(keys.hasNext()){val k=keys.next(); val r=obj.optJSONObject(k)?:continue; val dt=runCatching{SimpleDateFormat(if(k.length>10)"yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd",Locale.US).parse(k)!!.time}.getOrDefault(0L); out+=Candle(dt,r.optDouble("1. open"),r.optDouble("2. high"),r.optDouble("3. low"),r.optDouble("4. close"),r.optDouble("5. volume"))}; return out.sortedBy{it.time}
    }

    private fun searchMoex(q:String):List<SearchResult>{
        val root=JSONObject(getText("https://iss.moex.com/iss/securities.json?q=${enc(q)}&iss.meta=off&iss.only=securities&securities.columns=secid,shortname,emitent_title",12000)); val block=root.optJSONObject("securities")?:return emptyList(); val cols=block.optJSONArray("columns")?:return emptyList(); val data=block.optJSONArray("data")?:return emptyList(); val idx=(0 until cols.length()).associateBy({cols.getString(it)},{it}); val out=mutableListOf<SearchResult>(); for(i in 0 until data.length()){val r=data.optJSONArray(i)?:continue; val sym=r.optString(idx["secid"]?:-1); val name=r.optString(idx["shortname"]?:-1); val emit=r.optString(idx["emitent_title"]?:-1); if(sym.isNotBlank()) out+=SearchResult("${sym.removeSuffix(".ME")}.ME",if(name.isBlank())emit else name,"MOEX","EQUITY", "MOEX")}; return out
    }

    private fun searchYahoo(q:String):List<SearchResult>{val root=JSONObject(getText("https://query1.finance.yahoo.com/v1/finance/search?q=${enc(q)}&quotesCount=20&newsCount=0",10000)); val arr=root.optJSONArray("quotes")?:return emptyList(); return (0 until arr.length()).mapNotNull{val x=arr.optJSONObject(it)?:return@mapNotNull null; val s=x.optString("symbol"); if(s.isBlank())null else SearchResult(s,x.optString("longname",x.optString("shortname",s)),x.optString("exchange"),x.optString("quoteType"),"Yahoo Finance")}}
    private fun searchAlpha(q:String):List<SearchResult>{val key=alphaVantageKey?:return emptyList(); val root=JSONObject(getText("https://www.alphavantage.co/query?function=SYMBOL_SEARCH&keywords=${enc(q)}&apikey=${enc(key)}",12000)); val arr=root.optJSONArray("bestMatches")?:return emptyList(); return (0 until arr.length()).mapNotNull{val x=arr.optJSONObject(it)?:return@mapNotNull null; val s=x.optString("1. symbol"); if(s.isBlank())null else SearchResult(s,x.optString("2. name",s),x.optString("4. region"),x.optString("3. type"),"Alpha Vantage")}}

    private fun translate(text:String,target:String):String{if(text.isBlank())return text; if(target=="en" && text.all{it.code<128})return text; return runCatching{val root=JSONArray(getText("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$target&dt=t&q=${enc(text)}",9000)); val parts=root.optJSONArray(0)?:return@runCatching text; buildString{for(i in 0 until parts.length()){append(parts.optJSONArray(i)?.optString(0).orEmpty())}}.ifBlank{text}}.getOrDefault(text)}

    private fun parseYahoo(text:String):List<Candle>{val root=JSONObject(text); val r=root.getJSONObject("chart").getJSONArray("result").getJSONObject(0); val ts=r.getJSONArray("timestamp"); val q=r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0); val o=q.getJSONArray("open"); val h=q.getJSONArray("high"); val l=q.getJSONArray("low"); val c=q.getJSONArray("close"); val v=q.optJSONArray("volume"); val out=mutableListOf<Candle>(); for(i in 0 until ts.length()){if(o.isNull(i)||h.isNull(i)||l.isNull(i)||c.isNull(i))continue;out+=Candle(ts.getLong(i)*1000,o.getDouble(i),h.getDouble(i),l.getDouble(i),c.getDouble(i),if(v!=null&&!v.isNull(i))v.getDouble(i)else 0.0)};return out}
    private fun quoteYahoo(symbol: String): Double? {
        val text = getText("https://query1.finance.yahoo.com/v8/finance/chart/${enc(symbol)}?range=1d&interval=1m", 9000)
        val root = JSONObject(text)
        val result = root.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) ?: return null
        val meta = result.optJSONObject("meta")
        // Never label a previous close as LIVE. If the provider has no current quote,
        // return null so the UI can keep the last candle without pretending it is live.
        return meta?.optDouble("regularMarketPrice", Double.NaN)?.takeIf { it.isFinite() && it > 0 }
    }

    private fun quoteAlfaForex(symbol: String): Double? {
        val pair = symbol.removeSuffix("=X").uppercase(Locale.US)
        if (pair.length != 6) return null
        val label = "${pair.take(3)} / ${pair.takeLast(3)}"
        val html = getText("https://alfaforex.ru/analytics/analytics-currencies/", 9000)
        // The public Alfa-Forex page renders the current FX quotes in the HTML.
        // Extract only the numeric value immediately following the requested pair.
        val plain = stripHtml(html)
        val pattern = Pattern.compile(
            Pattern.quote(label) + "\\s+[^0-9]{0,120}([0-9]{1,6}(?:[.,][0-9]{1,6})?)",
            Pattern.CASE_INSENSITIVE
        )
        val m = pattern.matcher(plain)
        if (!m.find()) return null
        return m.group(1).replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }

    private fun quoteMoex(secid: String): Double? {
        val url = "https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/${enc(secid)}.json?iss.meta=off&iss.only=marketdata&marketdata.columns=SECID,LAST,LCURRENTPRICE,PREVPRICE"
        val root = JSONObject(getText(url, 9000))
        val block = root.optJSONObject("marketdata") ?: return null
        val cols = block.optJSONArray("columns") ?: return null
        val data = block.optJSONArray("data") ?: return null
        if (data.length() == 0) return null
        val idx = (0 until cols.length()).associateBy({ cols.getString(it) }, { it })
        val row = data.optJSONArray(0) ?: return null
        fun d(name: String) = row.optDouble(idx[name] ?: -1, Double.NaN)
        val last = d("LAST").takeIf { it.isFinite() && it > 0 }
        val current = d("LCURRENTPRICE").takeIf { it.isFinite() && it > 0 }
        return current ?: last
    }

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

    private fun finamDividendCalendar(limit: Int): List<DividendEvent> {
        val html = getText("https://www.finam.ru/dividends/calendar/rus/", 15000)
        val clean = html.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
        val rowPattern = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val cellPattern = Pattern.compile("<(?:td|th)[^>]*>(.*?)</(?:td|th)>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val out = mutableListOf<DividendEvent>(); val rows = rowPattern.matcher(clean)
        while (rows.find() && out.size < limit * 3) {
            val cells = mutableListOf<String>(); val cm = cellPattern.matcher(rows.group(1) ?: "")
            while (cm.find()) cells += stripHtml(cm.group(1).orEmpty())
            if (cells.size < 3) continue
            val dateIndex = cells.indexOfFirst { it.matches(Regex(".*\\b\\d{1,2}\\.\\d{1,2}\\.\\d{4}\\b.*")) }
            val amountIndex = cells.indexOfFirst { it.replace(',', '.').matches(Regex(".*\\b\\d+[.,]\\d+\\b.*")) }
            if (dateIndex < 0 || amountIndex < 0) continue
            val date = parseDividendDate(cells[dateIndex]) ?: continue
            val amount = Regex("[-+]?\\d+[.,]\\d+").find(cells[amountIndex])?.value?.replace(',', '.')?.toDoubleOrNull() ?: continue
            val symbol = cells.firstOrNull { it.matches(Regex(".*\\([A-Z]{2,6}P?\\).*")) }?.let { Regex("\\b[A-Z]{2,6}P?\\b").find(it)?.value }
                ?: Regex("\\b[A-Z]{2,6}P?\\b").find(cells.joinToString(" "))?.value ?: continue
            out += DividendEvent(symbol, date, amount, "Финам")
        }
        if (out.isEmpty()) {
            // Finam currently renders the Russian calendar as a table/SSR payload. Keep a tolerant
            // text parser as a second path because markup classes can change without notice.
            val text = stripHtml(clean)
            val p2 = Pattern.compile("""([^\n]{0,180})\(([A-Z][A-Z0-9]{1,7})\)(?s).*?(\d{1,4}(?:[.,]\d+)?)\s*(?:Rub|руб).*?(\d{1,2}[./-]\d{1,2}[./-]\d{2,4})""", Pattern.CASE_INSENSITIVE)
            val m2 = p2.matcher(text)
            while (m2.find() && out.size < limit) {
                val date = parseDividendDate(m2.group(4)) ?: continue
                val amount = m2.group(3).replace(',','.').toDoubleOrNull() ?: continue
                out += DividendEvent(m2.group(2).uppercase(Locale.US), date, amount, "Финам")
            }
        }
        return out.distinctBy { "${it.symbol}|${it.date}|${it.amount}" }.take(limit)
    }

    private fun moexBulkDividends(limit: Int): List<DividendEvent> {
        val url = "https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities.json?iss.meta=off&securities.columns=SECID,SHORTNAME,DIVIDENDVALUE,DIVIDENDDATE"
        val root = JSONObject(getText(url, 15000)); val block = root.optJSONObject("securities") ?: return emptyList()
        val cols = block.optJSONArray("columns") ?: return emptyList(); val data = block.optJSONArray("data") ?: return emptyList()
        val idx = (0 until cols.length()).associateBy({ cols.getString(it) }, { it }); val out = mutableListOf<DividendEvent>()
        for (i in 0 until data.length()) {
            val r = data.optJSONArray(i) ?: continue
            val sec = r.optString(idx["SECID"] ?: -1).trim(); val amount = r.optDouble(idx["DIVIDENDVALUE"] ?: -1, Double.NaN); val dateText = r.optString(idx["DIVIDENDDATE"] ?: -1)
            if (sec.isBlank() || !amount.isFinite() || amount <= 0 || dateText.isBlank()) continue
            val date = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateText)?.time ?: 0L }.getOrDefault(0L)
            if (date > 0) out += DividendEvent(sec, date, amount, "MOEX ISS")
            if (out.size >= limit) break
        }
        return out
    }

    private fun moexDividends(secid: String): List<DividendEvent> {
        val url = "https://iss.moex.com/iss/securities/${enc(secid)}/dividends.json?iss.meta=off&dividends.columns=secid,registryclosedate,value,currencyid"
        val root = JSONObject(getText(url, 10000)); val block = root.optJSONObject("dividends") ?: return emptyList()
        val cols = block.optJSONArray("columns") ?: return emptyList(); val data = block.optJSONArray("data") ?: return emptyList()
        val idx = (0 until cols.length()).associateBy({ cols.getString(it) }, { it }); val out = mutableListOf<DividendEvent>()
        for (i in 0 until data.length()) {
            val r = data.optJSONArray(i) ?: continue; val dateText = r.optString(idx["registryclosedate"] ?: -1)
            val value = r.optDouble(idx["value"] ?: -1, Double.NaN); if (dateText.isBlank() || !value.isFinite() || value <= 0) continue
            val date = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateText)?.time ?: 0L }.getOrDefault(0L); if (date <= 0) continue
            out += DividendEvent(secid, date, value, "MOEX")
        }
        return out
    }

    private fun yahooDividends(symbol:String):List<DividendEvent>{val root=JSONObject(getText("https://query1.finance.yahoo.com/v8/finance/chart/${enc(symbol)}?range=2y&interval=1d&events=div",10000)); val r=root.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0)?:return emptyList(); val ev=r.optJSONObject("events")?.optJSONObject("dividends")?:return emptyList(); val out=mutableListOf<DividendEvent>(); val keys=ev.keys();while(keys.hasNext()){val d=ev.optJSONObject(keys.next())?:continue;val ts=d.optLong("date")*1000;val a=d.optDouble("amount",Double.NaN);if(ts>0&&a.isFinite()&&a>0)out+=DividendEvent(symbol,ts,a,"Yahoo Finance")};return out}
    private fun parseMoexTime(s:String):Long=runCatching{SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).apply{timeZone=TimeZone.getTimeZone("Europe/Moscow")}.parse(s)!!.time}.getOrDefault(System.currentTimeMillis())
    private fun parseIso(s:String):Long=runCatching{SimpleDateFormat("yyyyMMdd'T'HHmmss",Locale.US).parse(s)!!.time}.getOrDefault(0L)
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
