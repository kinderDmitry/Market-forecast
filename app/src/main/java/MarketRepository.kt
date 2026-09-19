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
import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.abs
import kotlin.math.ceil
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap


internal fun reconcileLivePrice(symbol: String, candles: List<Candle>, quoted: Double?): Double {
    // A historical candle close is not a live quote. If BCS did not return a
    // current quote, fail closed instead of presenting stale data as "now".
    return quoted?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
}

/** Market-data repository. BCS is the sole market-data provider; news/dividends are separate informational feeds. */
class MarketRepository(
    private val alphaVantageKey: String? = null,
    private val bcsRefreshToken: String? = null,
    private val prefs: android.content.SharedPreferences? = null,
    private val context: Context? = null
) {
    companion object {
        const val BCS_DIVIDEND_CALENDAR_URL = "https://bcs-express.ru/dividednyj-kalendar"
        // Historical candles change slowly compared with live quotes. A short process-wide
        // cache prevents repeated timeframe switches/scans from hammering the same provider.
        private val candleCache = ConcurrentHashMap<String, Pair<Long, List<Candle>>>()
        private val quoteCache = ConcurrentHashMap<String, Pair<Long, Double>>()
        private const val CANDLE_CACHE_MS = 120_000L
        private const val QUOTE_CACHE_MS = 5_000L
        // Only instruments the app can actually search/scan: shares (including
        // foreign shares/DRs) and currencies. Do not download ETFs, indices,
        // bonds, futures, options or other BCS directory types.
        private val TRADING_INSTRUMENT_TYPES = listOf(
            "STOCK", "FOREIGN_STOCK", "DEPOSITARY_RECEIPTS", "CURRENCY"
        )
        // BCS documents 10 RPS for reference and market-data HTTP APIs. Keep a
        // single process-wide limiter because catalog, candles, quotes and order-book
        // requests can run concurrently from scanner/UI/workers.
        private val bcsHttpGate = Any()
        private var bcsLastRequestAt = 0L
        private const val BCS_MIN_REQUEST_GAP_MS = 115L
        // Reuse worker pools instead of creating/shutting down threads on every refresh.
        private val analysisPool = Executors.newFixedThreadPool(8)
        private val prefetchPool = Executors.newFixedThreadPool(6)
        // Search results are metadata only; a short TTL makes repeated typing/navigation
        // effectively instant without turning this into a downloaded instrument catalogue.
        private val searchCache = ConcurrentHashMap<String, Pair<Long, List<SearchResult>>>()
        private const val SEARCH_CACHE_MS = 10 * 60_000L
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
            val fetched = runCatching { loadBcs(clean, range, interval) }
            val normalized = fetched.getOrNull()?.let { normalizeInterval(it, interval) }.orEmpty()
            if (normalized.isNotEmpty()) {
                candleCache[cacheKey] = System.currentTimeMillis() to normalized
                return normalized
            }
            // Stale-while-revalidate: temporary BCS timeout/rate-limit must not
            // make an already loaded timeframe disappear from the UI.
            cached?.second?.takeIf { it.isNotEmpty() }?.let { return it }
            throw IllegalStateException("БКС: не удалось получить свечи для $symbol ($interval): ${fetched.exceptionOrNull()?.message ?: "пустой ответ"}")
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

    /** Search always resolves against BCS API. No local catalogue/cache is used as the source of search results. */
    fun search(query: String, filter: String = "ALL"): List<SearchResult> {
        val q = query.trim()
        if (q.isBlank() || !isBcsConfigured()) return emptyList()
        val cacheKey = "${filter.uppercase(Locale.US)}|${normalizeSearchText(q)}"
        searchCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.first <= SEARCH_CACHE_MS && cached.second.isNotEmpty()) return cached.second
            searchCache.remove(cacheKey)
        }
        val wanted = when (filter.uppercase(Locale.US)) {
            "FX" -> listOf("CURRENCY")
            "STOCK" -> listOf("STOCK", "FOREIGN_STOCK", "DEPOSITARY_RECEIPTS")
            else -> TRADING_INSTRUMENT_TYPES
        }
        val aliases = mapOf(
            "сбер" to "SBER", "сбербанк" to "SBER", "газпром" to "GAZP", "лукойл" to "LKOH",
            "роснефть" to "ROSN", "новатэк" to "NVTK", "татнефть" to "TATN", "магнит" to "MGNT",
            "мосбиржа" to "MOEX", "яндекс" to "YDEX", "озон" to "OZON", "циан" to "CNRU",
            "аэрофлот" to "AFLT", "втб" to "VTBR", "мтс" to "MTSS", "норникель" to "GMKN",
            "полюс" to "PLZL", "фосагро" to "PHOR", "ростелеком" to "RTKM", "алроса" to "ALRS",
            "совкомфлот" to "FLOT", "доллар" to "USD000UTSTOM", "евро" to "EUR_RUB__TOM",
            "юань" to "CNYRUB_TOM", "фунт" to "GBP_RUB__TOM", "иена" to "JPY_RUB__TOM"
        )
        fun matchesType(item: SearchResult): Boolean {
            val t = item.type.uppercase(Locale.US)
            return when (filter.uppercase(Locale.US)) {
                "FX" -> t.contains("CURRENCY") || t.contains("FOREX")
                "STOCK" -> t in setOf("STOCK", "FOREIGN_STOCK", "DEPOSITARY_RECEIPTS")
                else -> t in setOf("STOCK", "FOREIGN_STOCK", "DEPOSITARY_RECEIPTS", "CURRENCY")
            }
        }
        // Common Russian/global instruments are resolved through the authoritative BCS
        // by-ticker endpoint immediately. This avoids a directory walk for the searches
        // people make most often (and still verifies the instrument against BCS).
        val seedTicker = aliases[q.lowercase(Locale.ROOT)] ?: popularSeeds()
            .firstOrNull { normalizeSearchText(it.second) == normalizeSearchText(q) }?.first
        val directTicker = seedTicker ?: q.uppercase(Locale.US)
            .replace("/", "").replace("-", "")
            .takeIf { it.matches(Regex("[A-Z0-9_.=]{2,24}")) }
        if (!directTicker.isNullOrBlank()) {
            runCatching { bcsInstrumentInfo(directTicker) }.getOrNull()?.let { o ->
                val ticker = o.optString("ticker").ifBlank { directTicker }
                val board = o.optJSONArray("boards")?.let { chooseBoard(it) }
                val item = SearchResult(
                    ticker,
                    o.optString("displayName").ifBlank { o.optString("shortName") }.ifBlank { ticker },
                    board?.optString("exchange").orEmpty().ifBlank { "БКС" },
                    o.optString("instrumentType").ifBlank { if (ticker.endsWith("=X")) "CURRENCY" else "STOCK" },
                    "БКС", board?.optString("classCode").orEmpty()
                )
                if (matchesType(item)) {
                    val result = listOf(item)
                    searchCache[cacheKey] = System.currentTimeMillis() to result
                    return result
                }
            }
        }

        // Name search is also API-only: walk only the selected BCS instrument types,
        // page by page, and match the returned BCS display/short names.
        val needle = normalizeSearchText(q)
        val compact = compactSearch(needle)
        val transliterated = transliterateRuToLat(needle)
        val out = mutableListOf<Pair<Int, SearchResult>>()
        for (type in wanted) {
            val items = runCatching { loadCatalogType(type, 4) }.getOrElse { emptyList() }
            for (item in items) {
                if (!matchesType(item)) continue
                val symbol = normalizeSearchText(item.symbol)
                val name = normalizeSearchText(item.name)
                val nameCompact = compactSearch(name)
                val rank = when {
                    symbol == needle || name == needle -> 0
                    symbol.startsWith(needle) || name.startsWith(needle) -> 1
                    compact.isNotBlank() && (symbol.startsWith(compact) || nameCompact.startsWith(compact)) -> 2
                    transliterated.isNotBlank() && (symbol.startsWith(transliterated) || transliterateRuToLat(name).startsWith(transliterated)) -> 2
                    symbol.contains(needle) || name.contains(needle) -> 3
                    else -> continue
                }
                out += rank to item
            }
            if (out.any { it.first == 0 }) break
        }
        val result = out.sortedWith(compareBy<Pair<Int, SearchResult>> { it.first }.thenBy { it.second.name.lowercase(Locale.ROOT) })
            .map { it.second }
            .distinctBy { "${it.symbol.uppercase(Locale.US)}@${it.classCode.uppercase(Locale.US)}" }
            .take(50)
        if (result.isNotEmpty()) searchCache[cacheKey] = System.currentTimeMillis() to result
        return result
    }

    /** Scanner universe comes directly from BCS, page-by-page, with no catalogue cache. */
    fun scannerCatalog(type: String): List<SearchResult> {
        val out = LinkedHashMap<String, SearchResult>()
        streamScannerInstruments(type) { item -> out.putIfAbsent(catalogIdentity(item), item) }
        return out.values.toList()
    }

    /**
     * Streaming scanner source. The previous implementation downloaded the entire
     * BCS directory before the first instrument could be analysed. That made the
     * scanner look frozen for several minutes. Now each BCS page is delivered to the
     * scanner immediately and the next page is requested only after the current page
     * has been processed. No local catalogue is created.
     */
    fun streamScannerInstruments(type: String, onInstrument: (SearchResult) -> Unit) {
        val types = when (type.uppercase(Locale.US)) {
            "FX" -> listOf("CURRENCY")
            "STOCKS" -> listOf("STOCK", "FOREIGN_STOCK", "DEPOSITARY_RECEIPTS")
            else -> TRADING_INSTRUMENT_TYPES
        }
        for (instrumentType in types) {
            loadCatalogType(instrumentType, Int.MAX_VALUE, onItem = onInstrument)
        }
    }

    /** Resolves selected favourites directly through BCS, without downloading a catalogue. */
    fun resolveSelectedInstrument(symbol: String, filter: String): SearchResult? {
        if (!isBcsConfigured()) return null
        val clean = canonicalSymbol(symbol).substringBefore("@")
        val info = runCatching { bcsInstrumentInfo(clean) }.getOrNull() ?: return null
        val board = info.optJSONArray("boards")?.let { chooseBoard(it) }
        val type = info.optString("instrumentType").ifBlank {
            if (clean in setOf("USD000UTSTOM", "EUR_RUB__TOM", "CNYRUB_TOM", "GBP_RUB__TOM", "JPY_RUB__TOM")) "CURRENCY" else "STOCK"
        }
        val item = SearchResult(
            info.optString("ticker").ifBlank { clean },
            info.optString("displayName").ifBlank { info.optString("shortName") }.ifBlank { clean },
            board?.optString("exchange").orEmpty().ifBlank { "БКС" },
            type, "БКС", board?.optString("classCode").orEmpty()
        )
        val t = type.uppercase(Locale.US)
        val ok = when (filter.uppercase(Locale.US)) {
            "FX" -> t.contains("CURRENCY") || t.contains("FOREX")
            "STOCKS" -> t in setOf("STOCK", "FOREIGN_STOCK", "DEPOSITARY_RECEIPTS")
            else -> t in setOf("STOCK", "FOREIGN_STOCK", "DEPOSITARY_RECEIPTS", "CURRENCY")
        }
        return item.takeIf { ok }
    }

    private fun loadCatalogType(type: String, maxPagesPerType: Int, onPage: ((page: Int, items: Int, finished: Boolean) -> Unit)? = null, onItem: ((SearchResult) -> Unit)? = null): List<SearchResult> {
        val out = LinkedHashMap<String, SearchResult>()
        var page = 0
        var consecutiveFailures = 0
        while (maxPagesPerType == Int.MAX_VALUE || page < maxPagesPerType) {
            val arr = runCatching {
                var last: Throwable? = null
                for (attempt in 0..7) {
                    try {
                        val root = JSONObject(getTextAuth(
                            "https://be.broker.ru/trade-api-information-service/api/v1/instruments/by-type?type=${enc(type)}&page=$page&size=100",
                            20000, bcsHeaders()
                        ))
                        return@runCatching root.optJSONArray("instruments") ?: JSONArray()
                    } catch (t: Throwable) {
                        last = t
                        val message = t.message.orEmpty()
                        val retryable = message.contains("429") || message.contains("408") ||
                            message.contains("500") || message.contains("502") ||
                            message.contains("503") || message.contains("504") ||
                            message.contains("timeout", true) || message.contains("timed out", true)
                        if (!retryable) break
                        Thread.sleep((500L shl attempt.coerceAtMost(3)).coerceAtMost(6000L))
                    }
                }
                throw last ?: IllegalStateException("БКС: пустой ответ каталога")
            }.getOrNull()

            if (arr == null) {
                // Do not advance the page after a transient error. Advancing would
                // silently skip a whole page and produce a fake partial universe.
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    throw IllegalStateException("БКС: не удалось загрузить страницу $page типа $type")
                }
                continue
            }
            consecutiveFailures = 0
            if (arr.length() == 0) { onPage?.invoke(page, 0, true); break }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ticker = o.optString("ticker").trim()
                if (ticker.isBlank()) continue
                val boardObj = o.optJSONArray("boards")?.let { chooseBoard(it) }
                val exchange = boardObj?.optString("exchange").orEmpty().ifBlank { "БКС" }
                val name = o.optString("displayName")
                    .ifBlank { o.optString("shortName") }
                    .ifBlank { o.optString("issuerName") }
                    .ifBlank { ticker }
                val typeName = o.optString("instrumentType").ifBlank { type }
                val classCode = boardObj?.optString("classCode").orEmpty()
                val item = SearchResult(ticker, name, exchange, typeName, "БКС", classCode)
                out.putIfAbsent(catalogIdentity(item), item)
                onItem?.invoke(item)
            }
            onPage?.invoke(page, arr.length(), arr.length() < 100)
            // BCS documents that a full page requires requesting page + 1.
            if (arr.length() < 100) break
            page++
            Thread.sleep(40L)
        }
        return out.values.toList()
    }

    /** BCS-only FX catalogue. No local catalogue/cache is used. */
    fun fxCatalog(): List<SearchResult> = if (!isBcsConfigured()) emptyList() else
        scannerCatalog("FX")

    private fun normalizeSearchText(value: String): String =
        value.trim().lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")

    private fun compactSearch(value: String): String =
        normalizeSearchText(value).replace(Regex("[^\\p{L}\\p{Nd}]+"), "")

    private fun catalogIdentity(item: SearchResult): String =
        "${item.symbol.trim().uppercase(Locale.US)}@${item.classCode.trim().uppercase(Locale.US)}"

    private fun transliterateRuToLat(value: String): String {
        val map = mapOf('а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya")
        return buildString { value.forEach { append(map[it] ?: it) } }
    }

    private fun popularSeeds(): List<SearchResult> = listOf(
        "SBER" to "Сбербанк", "GAZP" to "Газпром", "LKOH" to "Лукойл", "ROSN" to "Роснефть",
        "NVTK" to "Новатэк", "TATN" to "Татнефть", "MGNT" to "Магнит", "MOEX" to "Московская биржа",
        "YDEX" to "Яндекс", "OZON" to "Ozon", "CNRU" to "ЦИАН", "AFLT" to "Аэрофлот",
        "VTBR" to "ВТБ", "MTSS" to "МТС", "GMKN" to "Норникель", "PLZL" to "Полюс",
        "PHOR" to "ФосАгро", "RTKM" to "Ростелеком", "ALRS" to "АЛРОСА", "FLOT" to "Совкомфлот",
        "IRAO" to "Интер РАО", "ENPG" to "Эн+", "USD000UTSTOM" to "Доллар / Рубль",
        "EUR_RUB__TOM" to "Евро / Рубль", "CNYRUB_TOM" to "Юань / Рубль"
    ).map { (symbol, name) ->
        SearchResult(symbol, name, "БКС", if (symbol in setOf("USD000UTSTOM", "EUR_RUB__TOM", "CNYRUB_TOM")) "CURRENCY" else "STOCK", "БКС")
    }

    private fun normalizeFx(q: String): SearchResult? {
        val x = q.uppercase(Locale.US).replace("/", "").replace("-", "").replace(" ", "")
        if (x.length != 6 || !x.all { it.isLetter() }) return null
        val base = x.take(3); val quote = x.takeLast(3)
        return if (bcsFx("${base}${quote}=X") != null)
            SearchResult(bcsFx("${base}${quote}=X")!!, "$base/$quote", "БКС", "CURRENCY", "БКС", "CETS")
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
    private fun extractInstrument(title:String,body:String):String{val text="$title $body".uppercase(Locale.US);val a=mapOf("SBER" to "SBER","СБЕР" to "SBER","ГАЗПРОМ" to "GAZP","GAZP" to "GAZP","ЛУКОЙЛ" to "LKOH","LKOH" to "LKOH","РОСНЕФТЬ" to "ROSN","НОВАТЭК" to "NVTK","ЯНДЕКС" to "YDEX","ОЗОН" to "OZON","ЦИАН" to "CNRU","CNRU" to "CNRU","ДОЛЛАР" to "USD000UTSTOM","ЕВРО" to "EUR_RUB__TOM","ЮАН" to "CNYRUB_TOM");return a.entries.firstOrNull{text.contains(it.key)}?.value.orEmpty()}

    private fun finamMarketNews(limit:Int,category:NewsCategory):List<NewsItem>{val html=getText("https://www.finam.ru/publications/section/market/",15000);val out=mutableListOf<NewsItem>();val p=Pattern.compile("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",Pattern.DOTALL or Pattern.CASE_INSENSITIVE);val m=p.matcher(html);while(m.find()&&out.size<limit){val href=htmlDecode(m.group(1).orEmpty());val title=stripHtml(m.group(2).orEmpty());if(title.length<12)continue;val low=title.lowercase(Locale.US);val fx=listOf("рубл","доллар","евро","юань","валют","курс").any{low.contains(it)};val stock=listOf("акци","сбер","газпром","лукойл","роснефт","новатэк","дивиденд","яндекс","озон").any{low.contains(it)};val cat=when{fx&&!stock->NewsCategory.FX;stock->NewsCategory.STOCKS;else->NewsCategory.ALL};if(cat==NewsCategory.ALL||(category!=NewsCategory.ALL&&cat!=category))continue;val url=if(href.startsWith("http"))href else "https://www.finam.ru"+if(href.startsWith("/"))href else "/$href";out+=NewsItem(title,"Финам",url,0L,title,cat,"",extractInstrument(title,""))};return out.distinctBy{it.url}}
    private fun moexNews(limit:Int,category:NewsCategory):List<NewsItem>{val url=when(category){NewsCategory.STOCKS->"https://www.moex.com/ru/news/?ncat=111";NewsCategory.FX->"https://www.moex.com/ru/news/?ncat=118";else->"https://www.moex.com/ru/news/"};val html=runCatching{getText(url,15000)}.getOrNull()?:return emptyList();val p=Pattern.compile("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",Pattern.DOTALL or Pattern.CASE_INSENSITIVE);val out=mutableListOf<NewsItem>();val m=p.matcher(html);while(m.find()&&out.size<limit){val title=stripHtml(m.group(2).orEmpty());val href=htmlDecode(m.group(1).orEmpty());if(title.length<12)continue;val full=if(href.startsWith("http"))href else "https://www.moex.com"+href;out+=NewsItem(title,"Московская биржа",full,0L,title,category,"",extractInstrument(title,""))};return out.distinctBy{it.url}}

    fun marketToday(mode: String, limit: Int = 20): List<MarketPick> {
        // Market Today is latency-sensitive. Each instrument is isolated so one
        // provider failure cannot abort the whole scan.
        val catalog = runCatching { scannerCatalog("ALL") }.getOrDefault(emptyList())
        val normalizedCatalog = catalog
            .map { it.copy(symbol = canonicalSymbol(it.symbol)) }
            .distinctBy { it.symbol.uppercase(Locale.US) }
        val stockCandidates = normalizedCatalog.filterNot {
            it.type.contains("CURRENCY", true) || it.type.contains("FOREX", true)
        }.map { it.symbol }
        val fxCandidates = normalizedCatalog.filter {
            it.type.contains("CURRENCY", true) || it.type.contains("FOREX", true)
        }.map { it.symbol }
        val candidates = when (mode) {
            "FX" -> fxCandidates
            "STOCKS", "LIQUID", "GROWTH", "FALL" -> stockCandidates
            else -> stockCandidates + fxCandidates
        }.distinct().take(32)
        val metaBySymbol = normalizedCatalog.associateBy { it.symbol.uppercase(Locale.US) }

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
                            val meta = metaBySymbol[symbol.uppercase(Locale.US)]
                            val type = if (meta?.type.orEmpty().contains("CURRENCY", true) || meta?.type.orEmpty().contains("FOREX", true)) "FX" else "STOCK"

                            MarketPick(
                                symbol = symbol,
                                name = meta?.name?.ifBlank { symbol } ?: symbol,
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
        return loadCatalogType("INDEX", Int.MAX_VALUE).filter { it.type.contains("INDEX", true) }.take(12).mapNotNull { item ->
            runCatching {
                val candles = load(item.symbol, "1y", "1d")
                val last = candles.lastOrNull() ?: return@runCatching null
                val prev = candles.dropLast(1).lastOrNull()?.close ?: last.close
                val live = quote(item.symbol) ?: return@runCatching null
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
        if (bcsRefreshToken.isNullOrBlank()) throw IllegalStateException("БКС не подключен. Прогнозы и live-цены доступны только через БКС.")
        val value = runCatching { quoteBcs(clean) }
            .getOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: throw IllegalStateException("БКС не вернул актуальную цену для $symbol")
        if (value != null) {
            // Never freeze a valid BCS quote because it differs sharply from the
            // previous value. A large move can be a real market move (or a
            // session transition). The old 15% clamp was exactly the kind of
            // hidden state that made tracking appear stuck on one price.
            quoteCache[quoteKey] = now to value
            prefs?.edit()
                ?.putString("canonical_quote_$clean", value.toString())
                ?.putLong("canonical_quote_ts_$clean", now)
                ?.apply()
            return value
        }
        return value
    }


    /** Fetch a quote without the normal 5-second UI cache. Used at a tracking
     * horizon boundary so History records the actual completion price, not the
     * price that happened to be cached at entry. */
    fun quoteFresh(symbol: String): Double? {
        val clean = symbol.trim().uppercase(Locale.US)
        if (!isBcsConfigured()) throw IllegalStateException("БКС не подключен")
        val value = runCatching { quoteBcs(clean) }.getOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: throw IllegalStateException("БКС не вернул актуальную цену для $symbol")
        val now = System.currentTimeMillis()
        val key = "BCS|$clean"
        quoteCache[key] = now to value
        prefs?.edit()
            ?.putString("canonical_quote_$clean", value.toString())
            ?.putLong("canonical_quote_ts_$clean", now)
            ?.apply()
        return value
    }

    fun isBcsConfigured(): Boolean = !bcsRefreshToken.isNullOrBlank()

    fun forecastSource(symbol: String): String {
        // Never perform a network/catalog lookup from the Compose/UI thread.
        // A temporary BCS catalog timeout must not be rendered as
        // "instrument unsupported". Actual support is resolved when candles/quote
        // are requested, and the resulting error is shown in the data-status card.
        return if (!isBcsConfigured()) "БКС не подключен" else "БКС • единственный источник"
    }

    /** Refresh real FX rates used only for display-currency conversion. */
    fun refreshDisplayCurrencyRates() {
        val p = prefs ?: return
        val pairs = mapOf("USD" to "USD000UTSTOM", "EUR" to "EUR_RUB__TOM", "CNY" to "CNYRUB_TOM", "GBP" to "GBP_RUB__TOM", "JPY" to "JPY_RUB__TOM")
        pairs.forEach { (ccy, pair) ->
            runCatching { quoteFresh(pair) }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }?.let {
                p.edit().putString("fx_rate_$ccy", it.toString()).putLong("fx_rate_ts_$ccy", System.currentTimeMillis()).apply()
            }
        }
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
        // BCS already returns the requested timeframe. In particular, H4 is an H4
        // candle, not four 1H candles. Aggregating it again used to collapse valid
        // 4H history and made that timeframe intermittently disappear.
        return candles.sortedBy { it.time }.distinctBy { it.time }
    }

    private val bcsInstrumentCache = ConcurrentHashMap<String, Pair<String, String>>()

    // BCS identifies Russian shares by ticker + classCode.  Older versions of the
    // app stored them as Yahoo-style symbols (for example SBER.ME).  The BCS API
    // itself uses SBER/TQBR.  Do not let the legacy .ME suffix make a valid BCS
    // instrument look unsupported.  TQBR is the documented class for the normal
    // Russian-equity board; the information service is still preferred whenever
    // it can provide a more specific class.
    private val bcsRuEquityTickers = setOf(
        "SBER", "GAZP", "LKOH", "ROSN", "NVTK", "TATN", "TATNP", "MGNT", "CNRU",
        "MOEX", "YDEX", "OZON", "PHOR", "MTSS", "IRAO", "GMKN", "NLMK",
        "CHMF", "ALRS", "SNGS", "SNGSP", "RTKM", "RTKMP", "VTBR", "AFLT",
        "RUAL", "PLZL", "HYDR", "ENPG", "PIKK", "MAGN", "CBOM", "AFKS",
        "FEES", "LSRG", "TRMK", "UPRO", "VKCO", "HEAD", "X5", "BELU",
        "SMLT", "MTLR", "MTLRP", "SELG", "FLOT", "POSI", "ASTR", "SVCB",
        "SIBN", "T", "FIXP", "HHRU", "RENI", "SOFL", "GECO", "BSPB", "ROLO"
    )

    /**
     * Canonical internal symbol normalization. Legacy Yahoo-style suffixes are
     * accepted only at the boundary; the rest of the app uses one identifier.
     * For legacy RUB FX aliases we use the real BCS instrument identifier.
     */
    fun canonicalSymbol(symbol: String): String {
        val raw = symbol.trim().uppercase(Locale.US)
        return when (raw) {
            "USDRUB=X", "USD/RUB", "USDRUB" -> "USD000UTSTOM"
            "EURRUB=X", "EUR/RUB", "EURRUB" -> "EUR_RUB__TOM"
            "CNYRUB=X", "CNY/RUB", "CNYRUB" -> "CNYRUB_TOM"
            "GBPRUB=X", "GBP/RUB", "GBPRUB" -> "GBP_RUB__TOM"
            "JPYRUB=X", "JPY/RUB", "JPYRUB" -> "JPY_RUB__TOM"
            else -> raw.removeSuffix(".ME")
        }
    }

    private fun bcsSupported(symbol: String): Boolean {
        if (!isBcsConfigured()) return false
        if (bcsFx(symbol) != null) return true
        val clean = canonicalSymbol(symbol).substringBefore("@")
        return runCatching { bcsInstrument(clean) }.isSuccess
    }

    private fun bcsInstrument(symbol: String): Pair<String, String> {
        val clean = canonicalSymbol(symbol)
        val explicitBoard = clean.substringAfter("@", "")
        val ticker = clean.substringBefore("@")
        val fx = bcsFx(symbol)
        if (fx != null) return fx to "CETS"
        if (explicitBoard.isNotBlank()) {
            val pair = ticker to explicitBoard
            bcsInstrumentCache[clean] = pair
            return pair
        }
        bcsInstrumentCache[ticker]?.let { return it }

        // First use the authoritative BCS instrument directory.  If that request
        // is temporarily unavailable (for example HTTP 429), use the deterministic
        // class for known Russian equities instead of reporting a valid instrument
        // as "not supported".  This is still 100% BCS data; there is no fallback
        // quote provider here.
        val info = runCatching { bcsInstrumentInfo(ticker) }.getOrNull()
        val infoBoard = info?.optJSONArray("boards")?.let { chooseBoard(it)?.optString("classCode").orEmpty() }.orEmpty()
        val board = infoBoard.ifBlank {
            if (ticker in bcsRuEquityTickers) "TQBR"
            else throw IllegalStateException("БКС: инструмент $ticker не найден в каталоге")
        }
        val pair = ticker to board
        bcsInstrumentCache[ticker] = pair
        bcsInstrumentCache[clean] = pair
        return pair
    }

    private fun chooseBoard(boards: JSONArray): JSONObject? {
        if (boards.length() == 0) return null
        val preferred = listOf("TQBR", "TQPI", "TQIF", "TQTF", "CETS", "SPB")
        for (code in preferred) {
            for (i in 0 until boards.length()) {
                val o = boards.optJSONObject(i) ?: continue
                if (o.optString("classCode").equals(code, true)) return o
            }
        }
        return (0 until boards.length()).asSequence()
            .mapNotNull { boards.optJSONObject(it) }
            .firstOrNull { it.optString("classCode").isNotBlank() }
            ?: boards.optJSONObject(0)
    }

    private fun bcsInstrumentInfo(symbol: String): JSONObject {
        val ticker = canonicalSymbol(symbol).substringBefore("@")
        val body = JSONObject().put("tickers", JSONArray().put(ticker)).toString()
        val root = JSONObject(postJson("https://be.broker.ru/trade-api-information-service/api/v1/instruments/by-tickers", body, 12000, bcsHeaders()))
        val arr = root.optJSONArray("instruments") ?: throw IllegalStateException("БКС: инструмент $ticker не найден")
        return arr.optJSONObject(0) ?: throw IllegalStateException("БКС: инструмент $ticker не найден")
    }

    private fun bcsFx(symbol: String): String? = when (symbol.uppercase(Locale.US)) {
        "USDRUB=X", "USD/RUB", "USDRUB" -> "USD000UTSTOM"
        "EURRUB=X", "EUR/RUB", "EURRUB" -> "EUR_RUB__TOM"
        "CNYRUB=X", "CNY/RUB", "CNYRUB" -> "CNYRUB_TOM"
        "GBPRUB=X", "GBP/RUB", "GBPRUB" -> "GBP_RUB__TOM"
        "JPYRUB=X", "JPY/RUB", "JPYRUB" -> "JPY_RUB__TOM"
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
        val body = JSONObject().put(
            "instruments", JSONArray().put(
                JSONObject().put("ticker", ticker).put("classCode", classCode)
            )
        ).toString()
        val root = JSONObject(postJson(
            "https://be.broker.ru/trade-api-market-data-connector/api/v1/quotes",
            body, 10000, bcsHeaders()
        ))

        // IMPORTANT: select the quote belonging to the requested ticker/classCode.
        // The old recursive parser returned the first object containing `last`;
        // if BCS wrapped several quote objects in one response that could make one
        // instrument inherit another instrument's price.
        fun numeric(v: JSONObject, vararg keys: String): Double? =
            keys.firstNotNullOfOrNull { key ->
                if (!v.has(key) || v.isNull(key)) null
                else v.optDouble(key, Double.NaN).takeIf { it.isFinite() && it > 0.0 }
            }

        fun collect(v: Any?, out: MutableList<JSONObject>) {
            when (v) {
                is JSONObject -> {
                    val t = v.optString("ticker").uppercase(Locale.US)
                    val c = v.optString("classCode").uppercase(Locale.US)
                    if ((t == ticker.uppercase(Locale.US) && c == classCode.uppercase(Locale.US)) ||
                        (t == ticker.uppercase(Locale.US) && (c.isBlank() || classCode.isBlank()))) out += v
                    val keys = v.keys()
                    while (keys.hasNext()) collect(v.opt(keys.next()), out)
                }
                is JSONArray -> for (i in 0 until v.length()) collect(v.opt(i), out)
            }
        }

        val exact = mutableListOf<JSONObject>()
        collect(root, exact)
        val candidates = if (exact.isNotEmpty()) exact else buildList {
            fun walk(v: Any?) {
                when (v) {
                    is JSONObject -> {
                        if (numeric(v, "last", "lastPrice", "price") != null) add(v)
                        val keys = v.keys(); while (keys.hasNext()) walk(v.opt(keys.next()))
                    }
                    is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                }
            }
            walk(root)
        }
        val q = candidates.firstOrNull() ?: return null
        return numeric(q, "last", "lastPrice", "price")
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
        val aliases=mapOf("SBER" to listOf("СБЕР","СБЕРБАНК","SBER"),"GAZP" to listOf("ГАЗПРОМ","GAZPROM","GAZP"),"LKOH" to listOf("ЛУКОЙЛ","LKOH"),"USDRUB" to listOf("ДОЛЛАР","USD/RUB","USDRUB","РУБЛ"),"EURRUB" to listOf("ЕВРО","EUR/RUB","EURRUB"),"CNRU" to listOf("ЦИАН","CIAN","CNRU"))
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
    private fun bcsPace() {
        synchronized(bcsHttpGate) {
            val now = System.currentTimeMillis()
            val wait = BCS_MIN_REQUEST_GAP_MS - (now - bcsLastRequestAt)
            if (wait > 0) Thread.sleep(wait)
            bcsLastRequestAt = System.currentTimeMillis()
        }
    }
    private fun postJson(url: String, body: String, timeout: Int, headers: Map<String,String>): String {
        var last: Throwable? = null
        repeat(4) { attempt ->
            try {
                bcsPace()
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = timeout; c.readTimeout = timeout; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json"); headers.forEach { (k,v) -> c.setRequestProperty(k,v) }
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                return readResponse(c)
            } catch (t: Throwable) {
                last = t
                if (!t.message.orEmpty().contains("HTTP 429")) throw t
                Thread.sleep((400L shl attempt).coerceAtMost(5000L))
            }
        }
        throw last ?: IllegalStateException("БКС HTTP: неизвестная ошибка")
    }
    private fun getTextAuth(url: String, timeout: Int, headers: Map<String,String>): String {
        var last: Throwable? = null
        repeat(4) { attempt ->
            try {
                bcsPace()
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "GET"; c.connectTimeout = timeout; c.readTimeout = timeout; headers.forEach { (k,v) -> c.setRequestProperty(k,v) }
                return readResponse(c)
            } catch (t: Throwable) {
                last = t
                if (!t.message.orEmpty().contains("HTTP 429")) throw t
                Thread.sleep((400L shl attempt).coerceAtMost(5000L))
            }
        }
        throw last ?: IllegalStateException("БКС HTTP: неизвестная ошибка")
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
