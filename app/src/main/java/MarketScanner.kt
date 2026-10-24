package com.marketforecast.prox

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** What part of the BCS catalogue the scanner walks through. MAIN = the liquid main boards (a minute); ALL = every instrument. */
internal enum class ScanScope { MAIN, ALL, RU_STOCKS, FOREIGN, FX, FAVORITES }

/**
 * How long the forecast should hold. The horizon is the candle timeframe the forecast is built on, and it is
 * also the lifetime of the result: a scanner card disappears when its horizon has passed.
 */
internal enum class ScanHorizon(
    val tf: String,
    private val ruName: String,
    private val enName: String,
    private val ruMove: String,
    private val enMove: String,
    /** How long a forecast of this horizon stays valid. */
    val validMs: Long,
    /** A last candle older than this means the instrument is not trading now (or is halted). */
    val staleMs: Long
) {
    M15("15M", "15 минут", "15 min", "15 мин", "15 min", 15 * 60_000L, 90 * 60_000L),
    H1("1H", "1 час", "1 hour", "Час", "Hour", 60 * 60_000L, 5 * 3_600_000L),
    H4("4H", "4 часа", "4 hours", "4 часа", "4 hours", 4 * 3_600_000L, 40 * 3_600_000L),
    D1("1D", "1 день", "1 day", "День", "Day", 24 * 3_600_000L, 10 * 86_400_000L),
    W1("1W", "1 неделя", "1 week", "Неделя", "Week", 7 * 86_400_000L, 30 * 86_400_000L);

    fun label(isRu: Boolean): String = if (isRu) ruName else enName
    fun moveLabel(isRu: Boolean): String = if (isRu) ruMove else enMove
    val intraday: Boolean get() = this == M15 || this == H1 || this == H4

    companion object {
        fun of(tf: String): ScanHorizon = values().firstOrNull { it.tf.equals(tf, ignoreCase = true) } ?: D1
    }
}

/** One actionable forecast found by the scanner. */
internal data class ScanHit(
    val symbol: String,
    val name: String,
    val type: String,
    val classCode: String,
    val signal: String,
    val confidence: Int,
    val score: Double,
    val price: Double,
    val changePct: Double,
    val stop: Double,
    val tp1: Double,
    val tp2: Double,
    val rr: Double,
    val regime: String,
    val scannedAt: Long,
    /** True when this symbol had no signal (or another signal) in the previous scan of the same horizon. */
    val isNew: Boolean,
    val prevSignal: String,
    /** Timeframe the forecast was built on = how long it should hold. */
    val timeframe: String = "1D",
    /** The forecast is removed from the list at this moment. */
    val expiresAt: Long = 0L,
    /** 0-100 reliability score (agreement of independent evidence), see [ReliabilityEngine]. */
    val reliability: Int = 0
) {
    val horizon: ScanHorizon get() = ScanHorizon.of(timeframe)
}

/** What the last scan really covered. "Checked" = answered with data, whether or not it produced a signal. */
internal class ScanCoverage(
    val total: Int,
    val hits: Int,
    val noSignal: Int,
    val illiquid: Int,
    val stale: Int,
    val failed: Int
) {
    val checked: Int get() = hits + noSignal + illiquid + stale
    val percent: Int get() = if (total <= 0) 0 else (total - failed) * 100 / total
}

/** Last scan results kept in a private file so the scanner opens instantly with the previous picture. */
internal object ScannerStore {
    private const val FILE = "scanner_last_v2.json"
    private const val LEGACY_FILE = "scanner_last_v1.json"

    class Snapshot(val finished: Map<String, Long>, val scope: String, val hits: List<ScanHit>)

    private fun fin(v: Double): Double = if (v.isFinite()) v else 0.0

    private fun parseHits(arr: JSONArray, defaultTf: String): List<ScanHit> {
        val list = ArrayList<ScanHit>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val symbol = o.optString("symbol")
            if (symbol.isBlank()) continue
            val scannedAt = o.optLong("scannedAt")
            val tf = o.optString("timeframe").ifBlank { defaultTf }
            val expires = o.optLong("expiresAt", 0L).takeIf { it > 0L } ?: (scannedAt + ScanHorizon.of(tf).validMs)
            list.add(
                ScanHit(
                    symbol = symbol,
                    name = o.optString("name", symbol),
                    type = o.optString("type"),
                    classCode = o.optString("classCode"),
                    signal = o.optString("signal"),
                    confidence = o.optInt("confidence"),
                    score = o.optDouble("score", 0.0),
                    price = o.optDouble("price", 0.0),
                    changePct = o.optDouble("changePct", 0.0),
                    stop = o.optDouble("stop", 0.0),
                    tp1 = o.optDouble("tp1", 0.0),
                    tp2 = o.optDouble("tp2", 0.0),
                    rr = o.optDouble("rr", 0.0),
                    regime = o.optString("regime"),
                    scannedAt = scannedAt,
                    isNew = o.optBoolean("isNew", false),
                    prevSignal = o.optString("prevSignal"),
                    timeframe = tf,
                    expiresAt = expires,
                    reliability = o.optInt("reliability", 0)
                )
            )
        }
        return list
    }

    @Synchronized
    fun load(ctx: Context): Snapshot? = runCatching {
        val file = ctx.getFileStreamPath(FILE)
        if (file.exists() && file.length() > 0L) {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val finishedMap = HashMap<String, Long>()
            root.optJSONObject("finished")?.let { f -> f.keys().forEach { k -> finishedMap[k] = f.optLong(k) } }
            return@runCatching Snapshot(finishedMap, root.optString("scope", "MAIN"), parseHits(root.optJSONArray("hits") ?: JSONArray(), "1D"))
        }
        val legacy = ctx.getFileStreamPath(LEGACY_FILE)
        if (legacy.exists() && legacy.length() > 0L) {
            val root = JSONObject(legacy.readText(Charsets.UTF_8))
            return@runCatching Snapshot(mapOf("1D" to root.optLong("time")), root.optString("scope", "MAIN"), parseHits(root.optJSONArray("hits") ?: JSONArray(), "1D"))
        }
        null
    }.getOrNull()

    private fun write(ctx: Context, finished: Map<String, Long>, scope: String, hits: List<ScanHit>) {
        val arr = JSONArray()
        hits.forEach { h ->
            arr.put(
                JSONObject().apply {
                    put("symbol", h.symbol); put("name", h.name); put("type", h.type); put("classCode", h.classCode)
                    put("signal", h.signal); put("confidence", h.confidence); put("score", fin(h.score))
                    put("price", fin(h.price)); put("changePct", fin(h.changePct)); put("stop", fin(h.stop))
                    put("tp1", fin(h.tp1)); put("tp2", fin(h.tp2)); put("rr", fin(h.rr)); put("regime", h.regime)
                    put("scannedAt", h.scannedAt); put("isNew", h.isNew); put("prevSignal", h.prevSignal)
                    put("timeframe", h.timeframe); put("expiresAt", h.expiresAt); put("reliability", h.reliability)
                }
            )
        }
        val fobj = JSONObject()
        finished.forEach { (k, v) -> fobj.put(k, v) }
        val root = JSONObject().put("scope", scope).put("finished", fobj).put("hits", arr)
        val file = ctx.getFileStreamPath(FILE)
        val tmp = ctx.getFileStreamPath("$FILE.tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    /**
     * Stores the outcome of one run. Hits of the scanned horizon are replaced for the symbols this run really
     * re-checked; hits of other horizons, symbols outside the run and anything the background worker added in
     * the meantime are kept. Expired forecasts are dropped.
     */
    @Synchronized
    fun saveRun(ctx: Context, tf: String, scope: String, finishedAt: Long, scannedSymbols: Set<String>, newHits: List<ScanHit>, markFinished: Boolean = true) {
        runCatching {
            val now = System.currentTimeMillis()
            val current = load(ctx)
            val finished = HashMap<String, Long>(current?.finished ?: emptyMap<String, Long>())
            if (markFinished) finished[tf] = finishedAt
            val keep = current?.hits.orEmpty().filter { it.expiresAt > now && (it.timeframe != tf || it.symbol !in scannedSymbols) }
            write(ctx, finished, scope, (newHits + keep).filter { it.expiresAt > now })
        }
    }
}

/** The part of a scan that does not depend on the screen: one instrument -> one verdict. Also used by the background worker. */
internal object ScanCore {
    const val FAILED = 0
    const val ILLIQUID = 1
    const val STALE = 2
    const val NO_SIGNAL = 3
    const val HIT = 4

    class Outcome(val status: Int, val hit: ScanHit?)

    private fun minBars(h: ScanHorizon): Int = if (h == ScanHorizon.W1) 40 else 60

    /** An answer that retrying will not change (unknown instrument, bad request). */
    private fun permanent(t: Throwable): Boolean {
        val m = t.message.orEmpty()
        return m.contains("HTTP 400") || m.contains("HTTP 404") || m.contains("HTTP 422") || m.contains("не найден", ignoreCase = true)
    }

    private fun isFx(type: String): Boolean = type.contains("CURRENCY", ignoreCase = true) || type.contains("FOREX", ignoreCase = true)

    /** Blocking: network + analysis. Call from a background dispatcher. */
    fun evaluate(repo: MarketRepository, item: SearchResult, h: ScanHorizon, previousSignal: String, baseline: Boolean): Outcome {
        val candles = try {
            repo.loadScanCandles(item.symbol, item.classCode, h.tf)
        } catch (t: Throwable) {
            return Outcome(if (permanent(t)) ILLIQUID else FAILED, null)
        }
        if (candles.size < minBars(h)) return Outcome(ILLIQUID, null)
        val last = candles.last()
        if (last.close <= 0.0) return Outcome(ILLIQUID, null)
        val now = System.currentTimeMillis()
        if (now - last.time > h.staleMs) return Outcome(STALE, null)
        if (!isFx(item.type) && candles.takeLast(10).sumOf { it.volume } <= 0.0) return Outcome(ILLIQUID, null)
        val f = try {
            AnalyticsEngine.analyze(candles, null)
        } catch (t: Throwable) {
            return Outcome(ILLIQUID, null)
        }
        if (f.signal == "NO TRADE") return Outcome(NO_SIGNAL, null)
        val prevClose = candles[candles.size - 2].close
        val change = if (prevClose > 0.0) (last.close - prevClose) / prevClose * 100.0 else 0.0
        val hit = ScanHit(
            symbol = item.symbol,
            name = item.name,
            type = item.type,
            classCode = item.classCode,
            signal = f.signal,
            confidence = f.confidence,
            score = f.score,
            price = last.close,
            changePct = change,
            stop = f.stop,
            tp1 = f.tp1,
            tp2 = f.tp2,
            rr = f.rr,
            regime = f.regime,
            scannedAt = now,
            isNew = baseline && previousSignal != f.signal,
            prevSignal = previousSignal,
            timeframe = h.tf,
            expiresAt = now + h.validMs,
            reliability = ReliabilityEngine.score(f, candles.size)
        )
        return Outcome(HIT, hit)
    }

    /** Full-market scopes need the whole BCS catalogue, the main board and favourites do not. */
    fun needsFullCatalog(scope: ScanScope): Boolean = scope != ScanScope.MAIN && scope != ScanScope.FAVORITES

    fun buildUniverse(repo: MarketRepository, selected: ScanScope, favorites: Set<String>): List<SearchResult> {
        val favKeys = favorites.map { repo.canonicalSymbol(it).substringBefore("@").uppercase(Locale.US) }.toSet()
        val catalog = repo.catalogSnapshot()
        val picked = catalog.filter { r ->
            when (selected) {
                ScanScope.MAIN -> boardRank(r) == 0
                ScanScope.ALL -> true
                ScanScope.RU_STOCKS -> r.type.equals("STOCK", true)
                ScanScope.FOREIGN -> r.type.equals("FOREIGN_STOCK", true) || r.type.equals("DEPOSITARY_RECEIPTS", true)
                ScanScope.FX -> isFx(r.type)
                ScanScope.FAVORITES -> r.symbol.uppercase(Locale.US) in favKeys
            }
        }.toMutableList()
        // Favourites are always part of the walk, even when they are not in the chosen board.
        val have = picked.map { it.symbol.uppercase(Locale.US) }.toMutableSet()
        for (key in favKeys) {
            if (key in have) continue
            val known = catalog.firstOrNull { it.symbol.equals(key, true) }
            if (known != null) picked.add(known) else if (selected == ScanScope.FAVORITES) picked.add(SearchResult(key, key, "БКС", "STOCK", "БКС", ""))
            have.add(key)
        }
        return picked.sortedWith(
            compareBy<SearchResult>(
                { if (it.symbol.uppercase(Locale.US) in favKeys) 0 else 1 },
                { boardRank(it) },
                { typeRank(it) },
                { it.symbol }
            )
        )
    }

    private fun boardRank(r: SearchResult): Int = when (r.classCode.uppercase(Locale.US)) {
        "TQBR", "CETS" -> 0
        "TQTF", "TQIF", "TQPI" -> 1
        else -> 2
    }

    private fun typeRank(r: SearchResult): Int = when (r.type.uppercase(Locale.US)) {
        "STOCK" -> 0
        "DEPOSITARY_RECEIPTS" -> 1
        "FOREIGN_STOCK" -> 2
        else -> 3
    }
}

/**
 * Walks the chosen part of the BCS catalogue, builds a forecast for the chosen horizon for every tradable
 * instrument and reports the ones with an actionable signal (LONG / SHORT). Results stream into [hits] while
 * the scan runs.
 *
 * - One BCS request per instrument, shared 10 RPS limiter, 6 parallel workers.
 * - Coverage is accounted for: every instrument ends as hit / no signal / illiquid / not trading / failed,
 *   failed ones are retried, and the final message says exactly how much of the scope was covered.
 * - A hit lives for its horizon and is removed when it expires.
 * - A hit is marked NEW when the previous scan of the same horizon had no signal, or a different one.
 */
internal class ScannerController(
    private val appContext: Context,
    private val repo: MarketRepository,
    private val prefs: SharedPreferences
) {
    /** Hits of ALL horizons; use [current] for the selected one. */
    var hits by mutableStateOf<List<ScanHit>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set
    var total by mutableIntStateOf(0)
        private set
    var done by mutableIntStateOf(0)
        private set
    var failed by mutableIntStateOf(0)
        private set
    var startedAt by mutableLongStateOf(0L)
        private set
    var finishedAt by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var coverage by mutableStateOf<ScanCoverage?>(null)
        private set

    var scope by mutableStateOf(loadScope())
    var horizon by mutableStateOf(loadHorizon())
    var autoStart by mutableStateOf(prefs.getBoolean(PREF_AUTO, true))
        private set

    private var job: Job? = null

    /** Set by the UI: language of status messages and whether the app is currently visible. */
    var ru: Boolean = true
    var isForeground: () -> Boolean = { true }

    private fun tr(ruText: String, enText: String): String = if (ru) ruText else enText

    /** When the selected horizon was last scanned (0 = never). */
    val lastFinishedAt: Long get() = finishedAt[horizon.tf] ?: 0L

    /** True when a previous scan of this horizon existed, so the NEW flags are meaningful. */
    val hasBaseline: Boolean get() = lastFinishedAt > 0L

    /** Live forecasts of the selected horizon. */
    val current: List<ScanHit> get() = hits.filter { it.timeframe == horizon.tf }

    val newCount: Int get() = current.count { it.isNew }

    fun changeAutoStart(value: Boolean) {
        autoStart = value
        prefs.edit().putBoolean(PREF_AUTO, value).apply()
    }

    fun updateScope(value: ScanScope) {
        if (running) return
        scope = value
        prefs.edit().putString(PREF_SCOPE, value.name).apply()
    }

    fun updateHorizon(value: ScanHorizon) {
        if (running) return
        horizon = value
        coverage = null
        message = null
        prefs.edit().putString(PREF_HORIZON, value.name).apply()
        ScanScheduler.apply(appContext, prefs)
    }

    fun dismissMessage() { message = null }

    /** How old the last scan of the selected horizon may get before opening the scanner starts a new one. */
    fun refreshAfterMs(): Long = (horizon.validMs / 3L).coerceIn(5L * 60_000L, 6L * 3_600_000L)

    /** Removes the forecasts whose horizon has passed. Cheap; the screen calls it on a timer. */
    fun pruneExpired(now: Long = System.currentTimeMillis()) {
        val kept = hits.filter { it.expiresAt > now }
        if (kept.size != hits.size) hits = kept
    }

    /** Loads the previous scan from disk (also picks up what the background worker found). */
    suspend fun restore() {
        val snap = withContext(Dispatchers.IO) { ScannerStore.load(appContext) } ?: return
        if (running) return
        val now = System.currentTimeMillis()
        hits = snap.hits.filter { it.expiresAt > now }
        finishedAt = snap.finished
    }

    fun start(cs: CoroutineScope, favorites: Set<String>) {
        if (running) return
        if (!repo.isBcsConfigured()) {
            message = tr("Подключите refresh-токен БКС в настройках: без него сканер не получает рыночные данные.", "Add your BCS refresh token in settings: the scanner needs it to get market data.")
            return
        }
        val selected = scope
        val chosen = horizon
        running = true
        done = 0
        failed = 0
        total = 0
        coverage = null
        message = null
        startedAt = System.currentTimeMillis()
        job = cs.launch {
            try {
                runScan(selected, chosen, favorites)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                message = tr("Сканирование прервано: ${t.message ?: "неизвестная ошибка"}", "Scan interrupted: ${t.message ?: "unknown error"}")
            } finally {
                running = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        message = tr("Сканирование остановлено. Найденные сигналы сохранены.", "Scan stopped. The signals found so far are saved.")
    }

    // ------------------------------------------------------------------ scan

    private suspend fun runScan(selected: ScanScope, h: ScanHorizon, favorites: Set<String>) {
        // 1. Catalogue. Whole-market scopes must see the COMPLETE catalogue, otherwise "full coverage" would be a lie.
        if (ScanCore.needsFullCatalog(selected)) {
            if (CatalogStatus.syncing) {
                message = tr("Дожидаюсь полной загрузки каталога БКС, чтобы охватить весь рынок…", "Waiting for the full BCS catalogue so the whole market is covered…")
                var waited = 0
                while (CatalogStatus.syncing && waited < 240_000) { delay(500); waited += 500 }
                message = null
            }
            if (repo.catalogSyncedAt() == 0L || repo.lastCatalogError != null) {
                message = tr("Догружаю каталог БКС…", "Completing the BCS catalogue…")
                withContext(Dispatchers.IO) { runCatching { repo.syncCatalog() } }
                message = null
            }
        }
        if (repo.catalogSize() == 0) {
            message = tr("Загружаю каталог инструментов БКС…", "Downloading the BCS instrument catalogue…")
            withContext(Dispatchers.IO) { runCatching { repo.bootstrapCatalog() }; if (repo.catalogSize() == 0) runCatching { repo.syncCatalog() } }
            message = null
        }
        val universe = withContext(Dispatchers.IO) { ScanCore.buildUniverse(repo, selected, favorites) }
        if (universe.isEmpty()) {
            val why = repo.lastCatalogError ?: repo.lastBcsError
            message = tr("Каталог инструментов пуст. ${why ?: "Проверьте токен БКС и подключение к сети."}", "The catalogue is empty. ${why ?: "Check your BCS token and the network."}")
            return
        }
        total = universe.size

        val tf = h.tf
        val previousAll = hits
        val previous = previousAll.filter { it.timeframe == tf }.associate { it.symbol to it.signal }
        val baseline = hasBaseline
        val found = LinkedHashMap<String, ScanHit>()
        val scanned = HashSet<String>()
        val failedItems = ArrayList<SearchResult>()
        var noSignal = 0
        var illiquid = 0
        var stale = 0
        var answered = 0
        var abort = false
        val gate = Semaphore(PARALLEL)

        fun publish() {
            val now = System.currentTimeMillis()
            val merged = ArrayList<ScanHit>(found.size + previousAll.size)
            merged.addAll(found.values.filter { it.expiresAt > now })
            // Anything not re-checked yet keeps its old result until it expires; other horizons are untouched.
            for (old in previousAll) {
                if (old.expiresAt <= now) continue
                if (old.timeframe == tf && old.symbol in scanned) continue
                merged.add(if (old.timeframe == tf) old.copy(isNew = false) else old)
            }
            merged.sortWith(compareByDescending<ScanHit> { it.reliability }.thenByDescending { it.confidence })
            hits = merged
            failed = failedItems.size
            coverage = ScanCoverage(total, found.size, noSignal, illiquid, stale, failedItems.size)
        }

        fun record(item: SearchResult, r: ScanCore.Outcome) {
            when (r.status) {
                ScanCore.FAILED -> failedItems.add(item)
                ScanCore.ILLIQUID -> { illiquid += 1; scanned.add(item.symbol) }
                ScanCore.STALE -> { stale += 1; scanned.add(item.symbol) }
                ScanCore.NO_SIGNAL -> { noSignal += 1; scanned.add(item.symbol) }
                ScanCore.HIT -> { r.hit?.let { found[item.symbol] = it }; scanned.add(item.symbol) }
            }
        }

        try {
            // 2. First pass over the whole universe.
            coroutineScope {
                universe.map { item ->
                    async {
                        gate.withPermit {
                            if (abort) return@withPermit
                            val r = withContext(Dispatchers.IO) { ScanCore.evaluate(repo, item, h, previous[item.symbol].orEmpty(), baseline) }
                            record(item, r)
                            done += 1
                            if (r.status != ScanCore.FAILED) answered += 1
                            // Intraday horizon while the exchange is closed: nothing has a fresh candle, stop wasting requests.
                            if (h.intraday && answered >= 24 && stale * 10 >= answered * 9) abort = true
                            if (r.status == ScanCore.HIT || done % 10 == 0) publish()
                        }
                    }
                }.awaitAll()
            }
            // 3. Everything that lost a rate-limit race or hit a network error gets up to three more passes.
            var pass = 0
            while (!abort && failedItems.isNotEmpty() && pass < 3) {
                pass += 1
                val systemic = failedItems.size * 2 >= total
                message = tr("Повторяю запросы, не прошедшие из-за лимита или сети: ${failedItems.size}…", "Retrying requests that failed on the rate limit or the network: ${failedItems.size}…")
                delay(if (systemic) 8_000L * pass else 1_500L * pass)
                val retry = failedItems.toList()
                failedItems.clear()
                coroutineScope {
                    retry.map { item ->
                        async {
                            gate.withPermit {
                                val r = withContext(Dispatchers.IO) { ScanCore.evaluate(repo, item, h, previous[item.symbol].orEmpty(), baseline) }
                                record(item, r)
                                if (r.status == ScanCore.HIT) publish()
                            }
                        }
                    }.awaitAll()
                }
                publish()
            }
            message = null
        } finally {
            publish()
            if (scanned.isNotEmpty() && !abort) {
                val now = System.currentTimeMillis()
                finishedAt = finishedAt + (tf to now)
                val newHits = found.values.toList()
                val scannedCopy = HashSet(scanned)
                withContext(NonCancellable + Dispatchers.IO) {
                    ScannerStore.saveRun(appContext, tf, selected.name, now, scannedCopy, newHits)
                }
            }
        }

        if (abort) {
            message = tr(
                "Торги на сроке «${h.label(true)}» сейчас закрыты: у большинства инструментов нет свежих свечей. Выберите «1 день» или «1 неделя» либо запустите сканер во время торгов.",
                "The market is closed for the \"${h.label(false)}\" horizon: most instruments have no fresh candles. Pick 1 day or 1 week, or scan during trading hours."
            )
            return
        }

        // 4. Report: what was covered, what is new.
        val fresh = found.values.filter { it.isNew }
        val newOnes = fresh.size
        if (baseline && newOnes > 0 && !isForeground() && prefs.getBoolean("alert_signal", true)) {
            ScanNotify.post(appContext, h, fresh, ru, prefs.getInt("alert_min_confidence", 60))
        }
        val failedNow = failedItems.size
        val percent = if (total > 0) (total - failedNow) * 100 / total else 0
        val catalogNote = if (ScanCore.needsFullCatalog(selected) && repo.lastCatalogError != null)
            tr(" Каталог БКС загружен не полностью: часть инструментов могла не попасть в охват.", " The BCS catalogue is incomplete: some instruments may be missing from the coverage.")
        else ""
        val hz = h.label(ru)
        message = when {
            failedNow > 0 && failedNow * 2 >= total ->
                tr("Не удалось получить данные по $failedNow из $total инструментов. ${repo.lastBcsError ?: "Проверьте токен и сеть."}", "Could not get data for $failedNow of $total instruments. ${repo.lastBcsError ?: "Check the token and the network."}")
            failedNow > 0 ->
                tr("Охват $percent%: не удалось проверить $failedNow из $total инструментов (лимит или сеть). Запустите сканер ещё раз.$catalogNote", "Coverage $percent%: could not check $failedNow of $total instruments (rate limit or network). Run the scanner again.$catalogNote")
            !baseline ->
                tr("Первое сканирование на срок «$hz» завершено: охват 100% ($total из $total), сигналов: ${found.size}. Со следующего запуска новые прогнозы будут отмечены.$catalogNote", "First \"$hz\" scan finished: coverage 100% ($total of $total), signals: ${found.size}. New forecasts will be flagged from the next run.$catalogNote")
            newOnes > 0 ->
                tr("Охват 100% ($total из $total). Новых прогнозов на срок «$hz»: $newOnes.$catalogNote", "Coverage 100% ($total of $total). New \"$hz\" forecasts: $newOnes.$catalogNote")
            else ->
                tr("Охват 100% ($total из $total). Новых прогнозов нет, активных сигналов: ${found.size}.$catalogNote", "Coverage 100% ($total of $total). No new forecasts, active signals: ${found.size}.$catalogNote")
        }
    }

    private fun loadScope(): ScanScope =
        runCatching { ScanScope.valueOf(prefs.getString(PREF_SCOPE, ScanScope.MAIN.name).orEmpty()) }.getOrDefault(ScanScope.MAIN)

    private fun loadHorizon(): ScanHorizon =
        runCatching { ScanHorizon.valueOf(prefs.getString(PREF_HORIZON, ScanHorizon.D1.name).orEmpty()) }.getOrDefault(ScanHorizon.D1)

    companion object {
        private const val PREF_SCOPE = "scanner_scope"
        private const val PREF_HORIZON = "scanner_horizon"
        private const val PREF_AUTO = "scanner_auto"
        private const val PARALLEL = 6

        /** Auto-start when the scanner opens only if the previous scan of that horizon is older than this. */
        const val AUTO_START_AFTER_MS = 30L * 60_000L
    }
}
