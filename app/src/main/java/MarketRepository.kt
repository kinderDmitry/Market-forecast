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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger


internal fun reconcileLivePrice(symbol: String, candles: List<Candle>, quoted: Double?): Double {
    // A historical candle close is not a live quote. If BCS did not return a
    // current quote, fail closed instead of presenting stale data as "now".
    return quoted?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
}

/** HTTP error that keeps the status code and the server's Retry-After hint. The message still starts with "HTTP <code>". */
private class BcsHttpException(val code: Int, val retryAfterMs: Long, message: String) : IllegalStateException(message)

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
        // cache prevents repeated timeframe switches from hammering the same provider.
        private val candleCache = ConcurrentHashMap<String, Pair<Long, List<Candle>>>()
        private val quoteCache = ConcurrentHashMap<String, Pair<Long, Double>>()
        private const val CANDLE_CACHE_MS = 120_000L
        private const val LONG_CANDLE_CACHE_MS = 600_000L
        private const val QUOTE_CACHE_MS = 5_000L
        // Only instruments the app can actually search: shares (including
        // foreign shares/DRs) and currencies. Do not download ETFs, indices,
        // bonds, futures, options or other BCS directory types.
        private val TRADING_INSTRUMENT_TYPES = listOf(
            "STOCK", "FOREIGN_STOCK", "DEPOSITARY_RECEIPTS", "CURRENCY"
        )
        // BCS documents 10 RPS for reference and market-data HTTP APIs. Keep a
        // single process-wide limiter because catalog, candles, quotes and order-book
        // requests can run concurrently from UI/workers.
        private val bcsHttpGate = Any()
        private var bcsLastRequestAt = 0L
        private const val BCS_MIN_REQUEST_GAP_MS = 112L
        // Reuse worker pools instead of creating/shutting down threads on every refresh.
        private val analysisPool = Executors.newFixedThreadPool(8)
        private val prefetchPool = Executors.newFixedThreadPool(6)
        // Background work (catalogue download, market scanner) is marked on its thread and always yields the
        // shared 10 RPS budget to interactive requests (analysis, quotes), so the UI never waits for a scan.
        private val backgroundHttp = ThreadLocal<Boolean>()
        private val interactiveWaiting = AtomicInteger(0)
        // Dedicated pools: catalogue pages and candle chunks are fetched in parallel. They never nest.
        private val catalogPool = Executors.newFixedThreadPool(6) { task ->
            Thread({ backgroundHttp.set(true); task.run() }, "mfp-catalog").apply { isDaemon = true }
        }
        private val chunkPool = Executors.newFixedThreadPool(6) { task -> Thread(task, "mfp-candles").apply { isDaemon = true } }
        // Identical candle requests that are in flight at the same moment share one download.
        private val inflightCandles = ConcurrentHashMap<String, FutureTask<List<Candle>>>()
        private const val CATALOG_WINDOW = 5
        private const val CATALOG_DEFAULT_PAGE = 100
        private const val CATALOG_BIG_PAGE = 1000
        private const val CATALOG_PAGE_SIZE_PREF = "bcs_catalog_page_size"
        // The four catalogue types download concurrently; their page requests share catalogPool and the rate limiter.
        private val catalogTypePool = Executors.newFixedThreadPool(4) { task -> Thread(task, "mfp-catalog-type").apply { isDaemon = true } }
        // Adaptive rate limit: the gap between requests grows on HTTP 429 and shrinks back after successes,
        // so the app converges to whatever BCS really allows instead of hammering it.
        @Volatile private var bcsGapMs = BCS_MIN_REQUEST_GAP_MS
        @Volatile private var bcsBlockedUntil = 0L
        // One access token for the whole process: every MarketRepository instance shares it.
        private val tokenLock = Any()
        @Volatile private var sharedAccessToken: String? = null
        @Volatile private var sharedAccessExpiresAt = 0L
        @Volatile private var sharedAccessFingerprint = 0
        @Volatile private var authFailedUntil = 0L
        @Volatile private var authFailure: String? = null
        @Volatile private var lastRequestError: String? = null
        // Russian shares and currencies first: they become searchable within seconds.
        private val CATALOG_SYNC_ORDER = listOf("STOCK", "CURRENCY", "DEPOSITARY_RECEIPTS", "FOREIGN_STOCK")
        // Search results are metadata only; a short TTL makes repeated typing/navigation
        // effectively instant without turning this into a downloaded instrument catalogue.
        private val searchCache = ConcurrentHashMap<String, Pair<Long, List<SearchResult>>>()
        private const val SEARCH_CACHE_MS = 10 * 60_000L
        // Old builds stored up to 100k instruments as one SharedPreferences string, which every
        // getSharedPreferences() call had to parse. It is deleted on first run; the catalogue now
        // lives in a file and in a process-wide in-memory index.
        private const val LEGACY_SEARCH_INDEX_PREFS = "mfp_search_index_v3_full_bcs"
        private const val BCS_CATALOG_FILE = "bcs_catalog_v2.json"
        private const val CATALOG_SYNCED_AT = "bcs_catalog_synced_at"
        private const val SEARCH_LIMIT = 300
        private const val CATALOG_TTL_MS = 12 * 60 * 60_000L
        private const val INDEX_LIST_TTL_MS = 6 * 60 * 60_000L
        private const val PREV_CLOSE_TTL_MS = 30 * 60_000L
        private val searchEngine = InstrumentSearchEngine()
        @Volatile private var catalogLoaded = false
        private val catalogLock = Any()
        private val catalogSyncLock = Any()
        @Volatile private var indexListCache: Pair<Long, List<SearchResult>>? = null
        private val prevCloseCache = ConcurrentHashMap<String, Pair<Long, Double>>()
    }
    private val ua = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/128.0 Mobile Safari/537.36 MarketForecastPROX/4.8.107"
    private fun ensureCatalogLoaded() {
        if (catalogLoaded) return
        synchronized(catalogLock) {
            if (catalogLoaded) return
            if (context == null) return
            prefs?.edit()?.remove(LEGACY_SEARCH_INDEX_PREFS)?.apply()
            val cached = loadCatalogDisk()
            if (cached.isNotEmpty()) searchEngine.addAll(cached)
            catalogLoaded = true
        }
    }

    /** Number of instruments currently searchable (the full BCS catalogue once synced). */
    fun catalogSize(): Int { ensureCatalogLoaded(); return searchEngine.size() }

    /** Every instrument of the catalogue, used by the market scanner as its universe. */
    fun catalogSnapshot(): List<SearchResult> { ensureCatalogLoaded(); return searchEngine.all() }

    fun catalogSyncedAt(): Long = prefs?.getLong(CATALOG_SYNCED_AT, 0L) ?: 0L

    /** Why the last catalogue download was incomplete (null when it succeeded). Shown in the UI. */
    @Volatile var lastCatalogError: String? = null
        private set

    /**
     * Downloads the COMPLETE BCS catalogue (stocks, foreign stocks, depositary receipts, currencies)
     * and keeps it on disk. Instruments become searchable page by page while it downloads.
     * Skipped when the stored copy is fresh unless [force] is set. Returns the index size.
     */
    fun syncCatalog(force: Boolean = false, onProgress: ((Int) -> Unit)? = null): Int {
        ensureCatalogLoaded()
        if (!isBcsConfigured()) return searchEngine.size()
        fun fresh(): Boolean {
            val age = System.currentTimeMillis() - catalogSyncedAt()
            return age in 0 until CATALOG_TTL_MS && searchEngine.size() > 0
        }
        if (!force && fresh()) return searchEngine.size()
        synchronized(catalogSyncLock) {
            if (!force && fresh()) return searchEngine.size()
            val collected = LinkedHashMap<String, SearchResult>()
            val pending = ArrayList<SearchResult>()
            val state = Any()
            val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())
            val pageSize = choosePageSize()
            fun flush() {
                synchronized(state) {
                    if (pending.isNotEmpty()) { searchEngine.addAll(ArrayList(pending)); pending.clear() }
                }
            }
            fun runType(type: String): Boolean {
                return try {
                    loadCatalogType(
                        type, Int.MAX_VALUE, pageSize,
                        onPage = { _, _, _ ->
                            flush()
                            onProgress?.invoke(synchronized(state) { collected.size })
                        },
                        onItem = { item ->
                            if (isSupportedCatalogType(item.type)) synchronized(state) {
                                if (collected.putIfAbsent(catalogIdentity(item), item) == null) pending.add(item)
                            }
                        }
                    )
                    true
                } catch (t: Throwable) {
                    errors.add(t)
                    false
                } finally {
                    flush()
                }
            }
            // All types at once: the small ones (currencies, receipts) are searchable immediately, the big ones stream in.
            val futures = CATALOG_SYNC_ORDER.map { type -> type to catalogTypePool.submit(Callable { runType(type) }) }
            val failedTypes = ArrayList<String>()
            for ((type, future) in futures) {
                val ok = try { future.get(10, TimeUnit.MINUTES) } catch (t: Throwable) { errors.add(t); false }
                if (!ok) failedTypes += type
            }
            // Persist what we have: a killed app resumes from here, not from zero.
            if (collected.isNotEmpty()) persistCatalogDisk(searchEngine.all())
            // A transient rate-limit storm is usually over by now: give failed types one more pass.
            for (type in failedTypes.toList()) if (runType(type)) failedTypes.remove(type)
            val complete = failedTypes.isEmpty()
            val existing = searchEngine.size()
            // A "successful" download that is suspiciously small must not wipe a good catalogue.
            val suspicious = complete && existing > 200 && collected.size < existing / 2
            lastCatalogError = when {
                suspicious -> "БКС вернул подозрительно мало инструментов (${collected.size} вместо ${existing})"
                complete -> null
                else -> errors.lastOrNull()?.let { friendlyBcsError(it) } ?: "неизвестная ошибка"
            }
            if (!complete && pageSize > CATALOG_DEFAULT_PAGE && errors.any { it.message.orEmpty().contains("HTTP 400") }) {
                prefs?.edit()?.remove(CATALOG_PAGE_SIZE_PREF)?.apply() // the big page size was rejected: probe again next time
            }
            if (collected.isNotEmpty()) {
                if (complete && !suspicious) {
                    // A fully successful download is authoritative: delisted instruments disappear.
                    searchEngine.replaceAll(collected.values)
                    persistCatalogDisk(collected.values)
                    prefs?.edit()?.putLong(CATALOG_SYNCED_AT, System.currentTimeMillis())?.apply()
                } else {
                    persistCatalogDisk(searchEngine.all())
                }
            }
        }
        return searchEngine.size()
    }

    /**
     * Instant start. While the index is empty (first launch), the shares of the main board and the currency
     * pairs come straight from the public MOEX ISS: two tiny requests, no token, outside the BCS rate limit.
     * Search, favourites and the scanner work within a second; the full BCS catalogue then replaces this.
     */
    fun bootstrapCatalog(): Int {
        ensureCatalogLoaded()
        if (searchEngine.size() > 0) return searchEngine.size()
        val items = java.util.Collections.synchronizedList(ArrayList<SearchResult>())
        val sources = listOf(
            Triple("https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities.json?iss.meta=off&iss.only=securities&securities.columns=SECID,SHORTNAME,SECNAME", "TQBR", "STOCK"),
            Triple("https://iss.moex.com/iss/engines/currency/markets/selt/boards/CETS/securities.json?iss.meta=off&iss.only=securities&securities.columns=SECID,SHORTNAME,SECNAME", "CETS", "CURRENCY")
        )
        val futures = sources.map { (url, board, type) ->
            catalogTypePool.submit(Callable {
                val data = JSONObject(getText(url, 8000)).optJSONObject("securities")?.optJSONArray("data") ?: return@Callable 0
                var n = 0
                for (i in 0 until data.length()) {
                    val row = data.optJSONArray(i) ?: continue
                    val ticker = row.optString(0).trim()
                    if (ticker.isBlank()) continue
                    val name = row.optString(1).ifBlank { row.optString(2) }.ifBlank { ticker }
                    items.add(SearchResult(ticker, name, "MOEX", type, "MOEX", board))
                    n++
                }
                n
            })
        }
        futures.forEach { runCatching { it.get(12, TimeUnit.SECONDS) } }
        if (items.isNotEmpty() && searchEngine.size() == 0) {
            val copy = ArrayList(items)
            searchEngine.addAll(copy)
            persistCatalogDisk(copy)
        }
        return searchEngine.size()
    }

    /** Big pages are 10x fewer requests. Probed once; the verdict is remembered. */
    private fun choosePageSize(): Int {
        val saved = prefs?.getInt(CATALOG_PAGE_SIZE_PREF, 0) ?: 0
        if (saved > 0) return saved
        return try {
            fetchCatalogPage("STOCK", 0, CATALOG_BIG_PAGE)
            prefs?.edit()?.putInt(CATALOG_PAGE_SIZE_PREF, CATALOG_BIG_PAGE)?.apply()
            CATALOG_BIG_PAGE
        } catch (t: Throwable) {
            val m = t.message.orEmpty()
            if (m.contains("HTTP 400") || m.contains("HTTP 422")) prefs?.edit()?.putInt(CATALOG_PAGE_SIZE_PREF, CATALOG_DEFAULT_PAGE)?.apply()
            CATALOG_DEFAULT_PAGE
        }
    }

    /** Instant local search over the whole catalogue. */
    fun searchLocal(query: String, filter: String = "ALL"): List<SearchResult> {
        ensureCatalogLoaded()
        return searchEngine.search(query, filter, SEARCH_LIMIT)
    }

    private fun loadCatalogDisk(): List<SearchResult> {
        val file = context?.getFileStreamPath(BCS_CATALOG_FILE) ?: return emptyList()
        if (!file.exists() || file.length() == 0L) return emptyList()
        return runCatching {
            val a = JSONArray(file.readText(Charsets.UTF_8))
            buildList {
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val symbol = o.optString("symbol").trim()
                    if (symbol.isBlank()) continue
                    add(SearchResult(symbol, o.optString("name", symbol), o.optString("exchange", "БКС"), o.optString("type", "STOCK"), "БКС", o.optString("classCode")))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistCatalogDisk(values: Collection<SearchResult>) {
        val file = context?.getFileStreamPath(BCS_CATALOG_FILE) ?: return
        val tmp = context.getFileStreamPath("$BCS_CATALOG_FILE.tmp")
        val unique = LinkedHashMap<String, SearchResult>()
        values.asSequence()
            .filter { it.source == "БКС" && isSupportedCatalogType(it.type) }
            .forEach { unique.putIfAbsent(catalogIdentity(it), it) }
        val a = JSONArray()
        unique.values.forEach { r ->
            a.put(JSONObject().apply {
                put("symbol", r.symbol); put("name", r.name); put("exchange", r.exchange); put("type", r.type); put("classCode", r.classCode)
            })
        }
        runCatching {
            tmp.writeText(a.toString(), Charsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    fun load(symbol: String, range: String = "1y", interval: String = "1d"): List<Candle> {
        val clean = symbol.trim().uppercase(Locale.US)
        val cacheKey = "${if (bcsRefreshToken.isNullOrBlank()) "LEGACY" else "BCS"}|$clean|$range|$interval"
        val ttl = if (interval == "1d" || interval == "1wk") LONG_CANDLE_CACHE_MS else CANDLE_CACHE_MS
        val cached = candleCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first <= ttl && cached.second.isNotEmpty()) {
            return cached.second
        }
        // Forecast integrity rule: ALL forecast candles come from BCS. Never silently fall back to another
        // provider, otherwise the same instrument can have different prices/levels between screens.
        if (bcsRefreshToken.isNullOrBlank()) {
            throw IllegalStateException("БКС не подключен. Подключите refresh-токен БКС для получения прогнозов.")
        }
        // Analysis, the favourites feed and the multi-timeframe context often ask for the same series at the
        // same moment: only one request set goes out, the others wait for it.
        val task = FutureTask<List<Candle>>(Callable { fetchAndCache(clean, range, interval, cacheKey, cached?.second) })
        val running = inflightCandles.putIfAbsent(cacheKey, task)
        if (running != null) return awaitCandles(running)
        try { task.run() } finally { inflightCandles.remove(cacheKey, task) }
        return awaitCandles(task)
    }

    private fun awaitCandles(task: FutureTask<List<Candle>>): List<Candle> =
        try { task.get(120, TimeUnit.SECONDS) } catch (e: ExecutionException) { throw e.cause ?: e }

    private fun fetchAndCache(clean: String, range: String, interval: String, cacheKey: String, stale: List<Candle>?): List<Candle> {
        // Refresh = ONE small request for the newest bars, merged into what we already have.
        if (stale != null && stale.size >= 40) {
            val incremental = runCatching { loadBcsIncremental(clean, interval, stale) }
            val merged = incremental.getOrNull()
            if (merged != null) {
                candleCache[cacheKey] = System.currentTimeMillis() to merged
                return merged
            }
            if (incremental.isFailure) {
                // Temporary trouble: keep serving the loaded history and look again in 15 s instead of re-downloading everything.
                val ttl = if (interval == "1d" || interval == "1wk") LONG_CANDLE_CACHE_MS else CANDLE_CACHE_MS
                candleCache[cacheKey] = (System.currentTimeMillis() - ttl + 15_000L) to stale
                return stale
            }
        }
        val fetched = runCatching { loadBcs(clean, range, interval) }
        val normalized = fetched.getOrNull()?.let { normalizeInterval(it, interval) }.orEmpty()
        if (normalized.isNotEmpty()) {
            candleCache[cacheKey] = System.currentTimeMillis() to normalized
            if (candleCache.size > 250) {
                candleCache.entries.minByOrNull { it.value.first }?.let { candleCache.remove(it.key) }
            }
            return normalized
        }
        // Stale-while-revalidate: a temporary BCS timeout/rate-limit must not make a loaded timeframe disappear.
        stale?.takeIf { it.isNotEmpty() }?.let { return it }
        throw IllegalStateException("БКС: не удалось получить свечи для $clean ($interval): ${fetched.exceptionOrNull()?.message ?: "пустой ответ"}")
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

    /**
     * Search over the full BCS catalogue. Matching is local and instant. BCS is asked directly only
     * when an exact ticker was typed that the index does not contain yet (a new listing, or the
     * catalogue is still downloading).
     */
    fun search(query: String, filter: String = "ALL"): List<SearchResult> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        ensureCatalogLoaded()
        val local = searchEngine.search(q, filter, SEARCH_LIMIT)
        val candidate = q.uppercase(Locale.US).replace("/", "").replace("-", "")
            .takeIf { it.matches(Regex("[A-Z0-9_.=]{2,24}")) }
        val direct = candidate
            ?.takeIf { isBcsConfigured() && local.isEmpty() }
            ?.let { runCatching { resolveSelectedInstrument(it, filter) }.getOrNull() }
        if (direct != null) searchEngine.addAll(listOf(direct))
        return buildList {
            if (direct != null) add(direct)
            addAll(local)
        }.distinctBy { it.symbol.uppercase(Locale.US) }.take(SEARCH_LIMIT)
    }

    /**
     * One-request daily history (about 14 months) for the market scanner and index widgets.
     * The board comes from the catalogue, so no extra instrument lookup is needed.
     */
    fun loadScanCandles(symbol: String, classCode: String): List<Candle> {
        if (!isBcsConfigured()) throw IllegalStateException("БКС не подключен")
        backgroundHttp.set(true)
        try {
            val ticker = canonicalSymbol(symbol).substringBefore("@")
            val board = classCode.ifBlank { bcsInstrument(ticker).second }
            val end = System.currentTimeMillis()
            val start = end - 430L * 86_400_000L
            val url = "https://be.broker.ru/trade-api-market-data-connector/api/v1/candles-chart?ticker=${enc(ticker)}&classCode=${enc(board)}&timeFrame=D&startDate=${enc(isoUtc(start))}&endDate=${enc(isoUtc(end))}"
            val root = JSONObject(getTextAuth(url, 15000, bcsHeaders()))
            val raw = parseBcsCandles(root).distinctBy { it.time }.sortedBy { it.time }
            return normalizeInterval(raw, "1d")
        } finally {
            backgroundHttp.set(false)
        }
    }

    private fun previousClose(item: SearchResult): Double {
        val now = System.currentTimeMillis()
        prevCloseCache[item.symbol]?.let { if (now - it.first < PREV_CLOSE_TTL_MS) return it.second }
        val candles = loadScanCandles(item.symbol, item.classCode)
        val prev = candles.dropLast(1).lastOrNull()?.close ?: candles.lastOrNull()?.close ?: 0.0
        if (prev > 0.0) prevCloseCache[item.symbol] = now to prev
        return prev
    }

    /** Index tiles. The list of indices and previous closes are cached; only quotes are refreshed. */
    fun marketIndices(): List<MarketIndex> {
        if (!isBcsConfigured()) return emptyList()
        val now = System.currentTimeMillis()
        val cachedList = indexListCache
        val items = if (cachedList != null && now - cachedList.first < INDEX_LIST_TTL_MS) {
            cachedList.second
        } else {
            val fresh = runCatching { loadCatalogType("INDEX", Int.MAX_VALUE).filter { it.type.contains("INDEX", true) } }.getOrDefault(emptyList())
            if (fresh.isNotEmpty()) indexListCache = now to fresh
            fresh.ifEmpty { cachedList?.second.orEmpty() }
        }
        return items.take(12).mapNotNull { item ->
            runCatching {
                val prev = previousClose(item)
                val live = quote(item.symbol) ?: return@runCatching null
                MarketIndex(item.symbol, item.name, live, if (prev <= 0.0) 0.0 else (live - prev) / prev * 100.0, "БКС")
            }.getOrNull()
        }
    }

    /** BCS instrument universe for market widgets. */
    fun instrumentCatalog(type: String): List<SearchResult> {
        val out = LinkedHashMap<String, SearchResult>()
        instrumentCatalogStream(type) { item -> out.putIfAbsent(catalogIdentity(item), item) }
        return out.values.toList()
    }

    /** Types retained by the BCS identity catalogue used by search and market widgets.
     * This is deliberately independent of the removed mass-market subsystem.
     */
    private fun isSupportedCatalogType(type: String): Boolean {
        val t = type.uppercase(Locale.US)
        return t in setOf("STOCK", "FOREIGN_STOCK", "DEPOSITARY_RECEIPTS", "CURRENCY") ||
            t.contains("FOREX")
    }

    /** Streams the authoritative BCS instrument catalogue for search/market widgets.
     * This serves the search/catalogue layer only; it has no background scanning state or side effects.
     */
    private fun instrumentCatalogStream(type: String, onInstrument: (SearchResult) -> Unit) {
        val types = when (type.uppercase(Locale.US)) {
            "FX", "CURRENCY" -> listOf("CURRENCY")
            "STOCKS", "STOCK" -> listOf("STOCK", "FOREIGN_STOCK", "DEPOSITARY_RECEIPTS")
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

    private class CatalogPage(val items: JSONArray, val totalPages: Int)

    /** One catalogue page. Retries only what is worth retrying. */
    private fun fetchCatalogPage(type: String, page: Int, size: Int = CATALOG_DEFAULT_PAGE): CatalogPage {
        var last: Throwable? = null
        for (attempt in 0..2) {
            try {
                val root = JSONObject(getTextAuth(
                    "https://be.broker.ru/trade-api-information-service/api/v1/instruments/by-type?type=${enc(type)}&page=$page&size=$size",
                    20000, bcsHeaders()
                ))
                val items = root.optJSONArray("instruments") ?: root.optJSONArray("items") ?: JSONArray()
                // Optional paging metadata: when the server reports it, no empty trailing pages are requested.
                var pages = -1
                for (k in listOf("totalPages", "total_pages", "pages")) if (root.has(k)) { pages = root.optInt(k, -1); break }
                return CatalogPage(items, pages)
            } catch (t: Throwable) {
                last = t
                val m = t.message.orEmpty()
                val retryable = m.contains("429") || m.contains("408") || m.contains("HTTP 5") ||
                    m.contains("timeout", true) || m.contains("timed out", true)
                if (!retryable) break
                Thread.sleep(400L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("БКС: не удалось получить страницу каталога")
    }

    /**
     * Downloads one instrument type page by page with a sliding window of [CATALOG_WINDOW] parallel requests:
     * a slow page never stalls the pages behind it. Pages are processed strictly in order, so the result is
     * identical to a sequential download. A failed page is never skipped: it fails the type.
     */
    private fun loadCatalogType(type: String, maxPagesPerType: Int, pageSize: Int = CATALOG_DEFAULT_PAGE, onPage: ((page: Int, items: Int, finished: Boolean) -> Unit)? = null, onItem: ((SearchResult) -> Unit)? = null): List<SearchResult> {
        val out = LinkedHashMap<String, SearchResult>()
        val inflight = ArrayList<Pair<Int, java.util.concurrent.Future<CatalogPage>>>()
        var nextToSubmit = 0
        var limit = maxPagesPerType
        var finished = false
        fun fill() {
            while (inflight.size < CATALOG_WINDOW && (limit == Int.MAX_VALUE || nextToSubmit < limit)) {
                val p = nextToSubmit++
                inflight.add(p to catalogPool.submit(Callable { fetchCatalogPage(type, p, pageSize) }))
            }
        }
        try {
            fill()
            while (!finished && inflight.isNotEmpty()) {
                val (page, future) = inflight.removeAt(0)
                val result = try {
                    future.get(90, TimeUnit.SECONDS)
                } catch (e: ExecutionException) {
                    throw IllegalStateException("БКС: не удалось загрузить страницу $page типа $type: ${e.cause?.message ?: e.message}")
                }
                val arr = result.items
                if (result.totalPages > 0) limit = minOf(limit, result.totalPages + 1)
                if (arr.length() == 0) {
                    // Page 0 may be empty on one-based deployments: keep going, page 1 decides.
                    if (page == 0) { onPage?.invoke(0, 0, false); fill(); continue }
                    onPage?.invoke(page, 0, true)
                    finished = true
                    break
                }
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
                // A short non-empty page is still a page: some deployments cap the size below the requested one.
                onPage?.invoke(page, arr.length(), false)
                if (nextToSubmit >= 3000) throw IllegalStateException("БКС: превышен безопасный предел страниц каталога")
                fill()
            }
        } finally {
            inflight.forEach { it.second.cancel(true) }
        }
        return out.values.toList()
    }

    /** BCS-only FX catalogue. No local catalogue/cache is used. */
    fun fxCatalog(): List<SearchResult> = if (!isBcsConfigured()) emptyList() else
        instrumentCatalog("FX")


    private fun catalogIdentity(item: SearchResult): String =
        "${item.symbol.trim().uppercase(Locale.US)}@${item.classCode.trim().uppercase(Locale.US)}"

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
        // The catalogue index already knows the trading board of every instrument: no request needed.
        val indexedBoard = runCatching { ensureCatalogLoaded(); searchEngine.find(ticker)?.classCode }.getOrNull().orEmpty()
        val info = if (indexedBoard.isBlank()) runCatching { bcsInstrumentInfo(ticker) }.getOrNull() else null
        val infoBoard = info?.optJSONArray("boards")?.let { chooseBoard(it)?.optString("classCode").orEmpty() }.orEmpty()
        val board = indexedBoard.ifBlank { infoBoard }.ifBlank {
            throw IllegalStateException("БКС: инструмент $ticker не найден в каталоге")
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

    private fun bcsAccessToken(): String? {
        val refresh = bcsRefreshToken?.trim().orEmpty()
        if (refresh.isBlank()) return null
        val fingerprint = refresh.hashCode()
        synchronized(tokenLock) {
            val now = System.currentTimeMillis()
            val cached = sharedAccessToken
            if (sharedAccessFingerprint == fingerprint && !cached.isNullOrBlank() && now < sharedAccessExpiresAt - 60_000L) return cached
            // A rejected token is not retried by every parallel request: fail fast during a short cool-down.
            if (sharedAccessFingerprint == fingerprint && now < authFailedUntil) {
                throw IllegalStateException(authFailure ?: "БКС: не удалось получить access token")
            }
            try {
                val body = "client_id=trade-api-read&refresh_token=${enc(refresh)}&grant_type=refresh_token"
                val text = postForm("https://be.broker.ru/trade-api-keycloak/realms/tradeapi/protocol/openid-connect/token", body, 12000)
                val json = JSONObject(text)
                val token = json.optString("access_token").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("БКС: в ответе авторизации нет access_token")
                sharedAccessToken = token
                sharedAccessFingerprint = fingerprint
                sharedAccessExpiresAt = now + json.optLong("expires_in", 86400L) * 1000L
                authFailure = null
                authFailedUntil = 0L
                return token
            } catch (t: Throwable) {
                val m = t.message.orEmpty()
                authFailure = when {
                    m.contains("HTTP 400") || m.contains("HTTP 401") || m.contains("invalid_grant") ->
                        "Токен БКС недействителен или истёк. Создайте новый refresh-токен и вставьте его в настройках."
                    m.contains("timeout", true) || m.contains("timed out", true) || m.contains("Unable to resolve", true) ->
                        "Нет связи с авторизацией БКС (сеть или таймаут)."
                    else -> "Не удалось получить доступ к БКС: ${m.take(120)}"
                }
                sharedAccessFingerprint = fingerprint
                authFailedUntil = System.currentTimeMillis() + 20_000L
                throw IllegalStateException(authFailure)
            }
        }
    }

    private fun bcsHeaders(): Map<String,String> = mapOf("Authorization" to "Bearer ${bcsAccessToken() ?: throw IllegalStateException("BCS: не удалось получить access token")}", "Accept" to "application/json")

    private fun invalidateBcsAccessToken() {
        synchronized(tokenLock) {
            sharedAccessToken = null
            sharedAccessExpiresAt = 0L
            sharedAccessFingerprint = 0
            authFailedUntil = 0L
        }
    }

    private fun loadBcs(symbol: String, range: String, interval: String): List<Candle> {
        val (ticker, classCode) = bcsInstrument(symbol)
        val tf = when (interval) { "1m" -> "M1"; "5m" -> "M5"; "15m" -> "M15"; "30m" -> "M30"; "1h" -> "H1"; "4h" -> "H4"; "1wk" -> "W"; else -> "D" }
        // History per timeframe: plenty for every indicator, the multi-timeframe context and the backtest,
        // but few requests (M15 4, H1 4, H4 3, D 1, W 1 instead of 25 in total before).
        val days = when (tf) {
            "M15" -> 60; "H1" -> 240; "H4" -> 720; "D" -> 1400; "W" -> 3650
            else -> when (range) { "60d" -> 60; "2y" -> 730; else -> 3650 }
        }
        // BCS limits a single candle response to 1440 bars. Keep requests bounded.
        val maxSpan = when (tf) { "M1" -> 1; "M5" -> 5; "M15" -> 15; "M30" -> 30; "H1" -> 60; "H4" -> 240; "D" -> 1440; "W" -> 10080; else -> 43200 }
        val end = System.currentTimeMillis()
        val total = days * 86_400_000L
        val chunkMs = maxOf(86_400_000L, (1440L * maxSpan) * 60_000L)
        val chunks = ((total + chunkMs - 1) / chunkMs).toInt().coerceIn(1, 8)
        // Newest chunk first. All chunks are requested in parallel.
        val futures = (0 until chunks).map { i ->
            val b = end - chunkMs * i
            val a = maxOf(end - total, b - chunkMs)
            val url = "https://be.broker.ru/trade-api-market-data-connector/api/v1/candles-chart?ticker=${enc(ticker)}&classCode=${enc(classCode)}&timeFrame=$tf&startDate=${enc(isoUtc(a))}&endDate=${enc(isoUtc(b))}"
            chunkPool.submit(Callable { parseBcsCandles(JSONObject(getTextAuth(url, 15000, bcsHeaders()))) })
        }
        val out = ArrayList<Candle>()
        futures.forEachIndexed { i, future ->
            try {
                out += future.get(60, TimeUnit.SECONDS)
            } catch (e: Throwable) {
                // The newest chunk is mandatory. An older chunk that failed only shortens the history.
                if (i == 0) {
                    futures.forEach { it.cancel(true) }
                    throw (if (e is ExecutionException) (e.cause ?: e) else e)
                }
            }
        }
        return out.distinctBy { it.time }.sortedBy { it.time }.takeLast(5000)
    }

    private fun bcsTimeFrame(interval: String): String =
        when (interval) { "1m" -> "M1"; "5m" -> "M5"; "15m" -> "M15"; "30m" -> "M30"; "1h" -> "H1"; "4h" -> "H4"; "1wk" -> "W"; else -> "D" }

    /** Only the bars newer than the cached series (with a small overlap for the still-forming bar). Null = a full download is needed. */
    private fun loadBcsIncremental(symbol: String, interval: String, stale: List<Candle>): List<Candle>? {
        val barMs = when (interval) {
            "1m" -> 60_000L; "5m" -> 300_000L; "15m" -> 900_000L; "30m" -> 1_800_000L
            "1h" -> 3_600_000L; "4h" -> 14_400_000L; "1wk" -> 7 * 86_400_000L; else -> 86_400_000L
        }
        val now = System.currentTimeMillis()
        val from = stale.last().time - barMs * 3
        // One response holds at most 1440 bars: a longer gap needs the full download.
        if ((now - from) / barMs > 1300L) return null
        val (ticker, classCode) = bcsInstrument(symbol)
        val url = "https://be.broker.ru/trade-api-market-data-connector/api/v1/candles-chart?ticker=${enc(ticker)}&classCode=${enc(classCode)}&timeFrame=${bcsTimeFrame(interval)}&startDate=${enc(isoUtc(from))}&endDate=${enc(isoUtc(now))}"
        val fresh = parseBcsCandles(JSONObject(getTextAuth(url, 15000, bcsHeaders())))
        if (fresh.isEmpty()) return stale // market closed: nothing new
        val firstNew = fresh.minOf { it.time }
        return (stale.filter { it.time < firstNew } + fresh).distinctBy { it.time }.sortedBy { it.time }.takeLast(5000)
    }

    /** Step-by-step connection check shown in Settings: token -> catalogue -> candles -> live price. */
    fun diagnose(): List<String> {
        val out = ArrayList<String>()
        fun step(name: String, block: () -> String) {
            val t0 = System.currentTimeMillis()
            val result = runCatching(block)
            val ms = System.currentTimeMillis() - t0
            out += result.fold(
                onSuccess = { "✓ $name: $it ($ms мс)" },
                onFailure = { "✗ $name: ${friendlyBcsError(it)} ($ms мс)" }
            )
        }
        if (!isBcsConfigured()) return listOf("✗ Токен БКС не задан. Вставьте refresh-токен выше и нажмите «Сохранить».")
        invalidateBcsAccessToken()
        step("Авторизация") { if (bcsAccessToken().isNullOrBlank()) "access token не получен" else "access token получен" }
        step("Каталог инструментов") {
            val size = catalogSize()
            val age = catalogSyncedAt().takeIf { it > 0 }?.let { ", обновлён ${(System.currentTimeMillis() - it) / 60_000} мин назад" }.orEmpty()
            "$size инструментов$age" + (lastCatalogError?.let { ". Проблема: $it" }.orEmpty())
        }
        step("Свечи SBER (день)") {
            val c = loadScanCandles("SBER", "")
            if (c.isEmpty()) "пустой ответ" else "${c.size} свечей, последняя ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(java.util.Date(c.last().time))}"
        }
        step("Цена SBER") { quoteBcs("SBER")?.let { "$it" } ?: "цена не получена (торги закрыты?)" }
        out += "Интервал между запросами сейчас ${bcsGapMs} мс" + if (bcsGapMs > BCS_MIN_REQUEST_GAP_MS) " (БКС ограничивает частоту, приложение подстроилось)" else ""
        return out
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
    private fun bcsRequestOk() {
        if (bcsGapMs > BCS_MIN_REQUEST_GAP_MS) bcsGapMs = maxOf(BCS_MIN_REQUEST_GAP_MS, bcsGapMs - 8L)
        lastRequestError = null
    }

    private fun bcsRateLimited(retryAfterMs: Long) {
        bcsGapMs = minOf(bcsGapMs * 3 / 2 + 25L, 1500L)
        bcsBlockedUntil = System.currentTimeMillis() + retryAfterMs.coerceIn(300L, 8000L)
    }

    /** Human-readable reason for the last BCS problem (token rejected, rate limit, network...), or null. */
    val lastBcsError: String? get() = authFailure ?: lastRequestError

    private fun friendlyBcsError(t: Throwable): String {
        val m = t.message.orEmpty()
        return when {
            m.contains("HTTP 429") -> "Лимит запросов БКС (HTTP 429): приложение замедляет запросы автоматически"
            m.contains("HTTP 401") || m.contains("HTTP 403") -> "Доступ отклонён (${m.take(12)}): проверьте токен БКС"
            m.contains("HTTP 5") -> "Сервер БКС временно недоступен (${m.take(12)})"
            m.contains("timeout", true) || m.contains("timed out", true) -> "БКС не отвечает (таймаут)"
            m.contains("Unable to resolve", true) || m.contains("UnknownHost", true) -> "Нет подключения к интернету"
            m.isBlank() -> t.javaClass.simpleName
            else -> m.take(160)
        }
    }

    private fun bcsPace() {
        val background = backgroundHttp.get() == true
        if (background) {
            // Yield to interactive requests (at most 3 s), so scans never slow the screen the user is looking at.
            var waited = 0
            while (interactiveWaiting.get() > 0 && waited < 3000) { Thread.sleep(20); waited += 20 }
        } else interactiveWaiting.incrementAndGet()
        try {
            synchronized(bcsHttpGate) {
                val now = System.currentTimeMillis()
                val wait = maxOf(bcsGapMs - (now - bcsLastRequestAt), bcsBlockedUntil - now)
                if (wait > 0) Thread.sleep(wait)
                bcsLastRequestAt = System.currentTimeMillis()
            }
        } finally {
            if (!background) interactiveWaiting.decrementAndGet()
        }
    }
    private fun postJson(url: String, body: String, timeout: Int, headers: Map<String,String>): String {
        var last: Throwable? = null
        var authRefreshUsed = false
        repeat(5) { attempt ->
            try {
                bcsPace()
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = timeout; c.readTimeout = timeout; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                headers.forEach { (k,v) -> c.setRequestProperty(k,v) }
                if (authRefreshUsed && headers.containsKey("Authorization")) {
                    c.setRequestProperty("Authorization", "Bearer ${bcsAccessToken() ?: throw IllegalStateException("BCS: не удалось обновить access token")}")
                }
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                return readResponse(c).also { bcsRequestOk() }
            } catch (t: Throwable) {
                last = t
                if (t is BcsHttpException && t.code == 429) bcsRateLimited(t.retryAfterMs)
                val message = t.message.orEmpty()
                when {
                    message.contains("HTTP 401") || message.contains("HTTP 403") -> {
                        if (authRefreshUsed) throw t
                        invalidateBcsAccessToken()
                        authRefreshUsed = true
                    }
                    message.contains("HTTP 429") || message.contains("HTTP 408") || message.contains("HTTP 5") || message.contains("timeout", true) || message.contains("timed out", true) -> {
                        Thread.sleep((350L shl attempt.coerceAtMost(3)).coerceAtMost(5000L))
                    }
                    else -> throw t
                }
            }
        }
        lastRequestError = last?.let { friendlyBcsError(it) }
        throw last ?: IllegalStateException("БКС HTTP: неизвестная ошибка")
    }
    private fun getTextAuth(url: String, timeout: Int, headers: Map<String,String>): String {
        var last: Throwable? = null
        var authRefreshUsed = false
        repeat(5) { attempt ->
            try {
                bcsPace()
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "GET"; c.connectTimeout = timeout; c.readTimeout = timeout
                headers.forEach { (k,v) -> c.setRequestProperty(k,v) }
                if (authRefreshUsed && headers.containsKey("Authorization")) {
                    c.setRequestProperty("Authorization", "Bearer ${bcsAccessToken() ?: throw IllegalStateException("BCS: не удалось обновить access token")}")
                }
                return readResponse(c).also { bcsRequestOk() }
            } catch (t: Throwable) {
                last = t
                if (t is BcsHttpException && t.code == 429) bcsRateLimited(t.retryAfterMs)
                val message = t.message.orEmpty()
                when {
                    message.contains("HTTP 401") || message.contains("HTTP 403") -> {
                        if (authRefreshUsed) throw t
                        invalidateBcsAccessToken()
                        authRefreshUsed = true
                    }
                    message.contains("HTTP 429") || message.contains("HTTP 408") || message.contains("HTTP 5") || message.contains("timeout", true) || message.contains("timed out", true) -> {
                        Thread.sleep((350L shl attempt.coerceAtMost(3)).coerceAtMost(5000L))
                    }
                    else -> throw t
                }
            }
        }
        lastRequestError = last?.let { friendlyBcsError(it) }
        throw last ?: IllegalStateException("БКС HTTP: неизвестная ошибка")
    }
    private fun readResponse(c: HttpURLConnection): String {
        val code = c.responseCode
        val retryAfterMs = (c.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 0L) * 1000L
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) throw BcsHttpException(code, retryAfterMs, "HTTP $code: ${text.take(240)}")
        return text
    }
    private fun getText(url:String,timeout:Int):String{val c=URL(url).openConnection() as HttpURLConnection; c.requestMethod="GET";c.connectTimeout=timeout;c.readTimeout=timeout;c.setRequestProperty("User-Agent",ua);c.setRequestProperty("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");c.setRequestProperty("Accept-Language","ru-RU,ru;q=0.9,en;q=0.5");c.setRequestProperty("Accept-Encoding","gzip");try{if(c.responseCode !in 200..299)throw IllegalStateException("HTTP ${c.responseCode}");val raw=c.inputStream;val input=if(c.contentEncoding?.contains("gzip",true)==true) GZIPInputStream(raw) else raw;return input.bufferedReader(Charsets.UTF_8).use{it.readText()}}finally{c.disconnect()}}
}
