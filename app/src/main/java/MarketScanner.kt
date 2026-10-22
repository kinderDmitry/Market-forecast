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
import kotlin.math.abs

/** What part of the BCS catalogue the scanner walks through. MAIN = the liquid main boards (a minute); ALL = every instrument. */
internal enum class ScanScope { MAIN, ALL, RU_STOCKS, FOREIGN, FX, FAVORITES }

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
    /** True when this symbol had no signal (or another signal) in the previous scan. */
    val isNew: Boolean,
    val prevSignal: String
)

/** Last scan result kept in a private file so the scanner opens instantly with the previous picture. */
internal object ScannerStore {
    private const val FILE = "scanner_last_v1.json"

    class Snapshot(val time: Long, val scope: String, val hits: List<ScanHit>)

    private fun fin(v: Double): Double = if (v.isFinite()) v else 0.0

    fun load(ctx: Context): Snapshot? = runCatching {
        val file = ctx.getFileStreamPath(FILE)
        if (!file.exists() || file.length() == 0L) return@runCatching null
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val arr = root.optJSONArray("hits") ?: JSONArray()
        val list = ArrayList<ScanHit>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val symbol = o.optString("symbol")
            if (symbol.isBlank()) continue
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
                    scannedAt = o.optLong("scannedAt"),
                    isNew = o.optBoolean("isNew", false),
                    prevSignal = o.optString("prevSignal")
                )
            )
        }
        Snapshot(root.optLong("time"), root.optString("scope", "ALL"), list)
    }.getOrNull()

    fun save(ctx: Context, time: Long, scope: String, hits: List<ScanHit>) {
        runCatching {
            val arr = JSONArray()
            hits.forEach { h ->
                arr.put(
                    JSONObject().apply {
                        put("symbol", h.symbol); put("name", h.name); put("type", h.type); put("classCode", h.classCode)
                        put("signal", h.signal); put("confidence", h.confidence); put("score", fin(h.score))
                        put("price", fin(h.price)); put("changePct", fin(h.changePct)); put("stop", fin(h.stop))
                        put("tp1", fin(h.tp1)); put("tp2", fin(h.tp2)); put("rr", fin(h.rr)); put("regime", h.regime)
                        put("scannedAt", h.scannedAt); put("isNew", h.isNew); put("prevSignal", h.prevSignal)
                    }
                )
            }
            val root = JSONObject().put("time", time).put("scope", scope).put("hits", arr)
            val file = ctx.getFileStreamPath(FILE)
            val tmp = ctx.getFileStreamPath("$FILE.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }
}

/**
 * Walks the complete BCS catalogue, builds a forecast for every tradable instrument and reports the
 * ones with an actionable signal (LONG / SHORT). Results stream into [hits] while the scan runs.
 *
 * - One BCS request per instrument (daily history), shared 10 RPS limiter, 6 parallel workers.
 * - Instruments are ordered favourites -> main boards -> the rest, so the useful part arrives first.
 * - Delisted / halted / zero-volume instruments are skipped instead of producing noise.
 * - A hit is marked NEW when the previous scan had no signal, or a different one, for that symbol.
 */
internal class ScannerController(
    private val appContext: Context,
    private val repo: MarketRepository,
    private val prefs: SharedPreferences
) {
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
    var lastFinishedAt by mutableLongStateOf(0L)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    /** True when a previous scan existed, so the NEW flags are meaningful. */
    var hasBaseline by mutableStateOf(false)
        private set

    var scope by mutableStateOf(loadScope())
    var autoStart by mutableStateOf(prefs.getBoolean(PREF_AUTO, true))
        private set

    private var job: Job? = null

    /** Set by the UI: language of status messages and whether the app is currently visible. */
    var ru: Boolean = true
    var isForeground: () -> Boolean = { true }

    private fun tr(ruText: String, enText: String): String = if (ru) ruText else enText

    val newCount: Int get() = hits.count { it.isNew }

    fun changeAutoStart(value: Boolean) {
        autoStart = value
        prefs.edit().putBoolean(PREF_AUTO, value).apply()
    }

    fun updateScope(value: ScanScope) {
        if (running) return
        scope = value
        prefs.edit().putString(PREF_SCOPE, value.name).apply()
    }

    fun dismissMessage() { message = null }

    /** Loads the previous scan from disk. */
    suspend fun restore() {
        val snap = withContext(Dispatchers.IO) { ScannerStore.load(appContext) } ?: return
        if (running) return
        hits = snap.hits
        lastFinishedAt = snap.time
        hasBaseline = true
    }

    fun start(cs: CoroutineScope, favorites: Set<String>) {
        if (running) return
        if (!repo.isBcsConfigured()) {
            message = tr("Подключите refresh-токен БКС в настройках: без него сканер не получает рыночные данные.", "Add your BCS refresh token in settings: the scanner needs it to get market data.")
            return
        }
        val selected = scope
        running = true
        done = 0
        failed = 0
        total = 0
        message = null
        startedAt = System.currentTimeMillis()
        job = cs.launch {
            try {
                runScan(selected, favorites)
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

    private class ScanResult(val status: Int, val hit: ScanHit?)

    private suspend fun runScan(selected: ScanScope, favorites: Set<String>) {
        // A stored catalogue is used right away (it refreshes in the background); only a first launch has to wait.
        if (repo.catalogSize() == 0) {
            message = tr("Загружаю каталог инструментов БКС…", "Downloading the BCS instrument catalogue…")
            withContext(Dispatchers.IO) { runCatching { repo.bootstrapCatalog() }; if (repo.catalogSize() == 0) runCatching { repo.syncCatalog() } }
            message = null
        }
        val universe = withContext(Dispatchers.IO) { buildUniverse(selected, favorites) }
        if (universe.isEmpty()) {
            message = tr("Каталог инструментов пуст. ${repo.lastCatalogError ?: repo.lastBcsError ?: "Проверьте токен БКС и подключение к сети."}", "The catalogue is empty. ${repo.lastCatalogError ?: repo.lastBcsError ?: "Check your BCS token and the network."}")
            return
        }
        total = universe.size

        val previousHits = hits
        val previous = previousHits.associate { it.symbol to it.signal }
        val baseline = hasBaseline
        val found = LinkedHashMap<String, ScanHit>()
        val scanned = HashSet<String>()
        val failedItems = ArrayList<SearchResult>()
        val gate = Semaphore(PARALLEL)

        fun publish() {
            val merged = ArrayList<ScanHit>(found.size + previousHits.size)
            merged.addAll(found.values)
            // Instruments that were not re-checked yet (or are outside this scope) keep their old result.
            for (old in previousHits) if (old.symbol !in scanned) merged.add(old.copy(isNew = false))
            merged.sortWith(compareByDescending<ScanHit> { it.confidence }.thenByDescending { abs(it.score) })
            hits = merged
        }

        try {
            coroutineScope {
                universe.map { item ->
                    async {
                        gate.withPermit {
                            val result = scanOne(item, previous, baseline)
                            scanned.add(item.symbol)
                            done += 1
                            when (result.status) {
                                STATUS_FAILED -> { failed += 1; failedItems.add(item) }
                                STATUS_HIT -> result.hit?.let { found[item.symbol] = it }
                            }
                            if (result.status == STATUS_HIT || done % 10 == 0) publish()
                        }
                    }
                }.awaitAll()
            }
            // Second chance for requests that lost a rate-limit race (only when the failure is not systemic).
            if (failedItems.isNotEmpty() && failedItems.size * 2 < total) {
                delay(1500)
                val retry = failedItems.toList()
                failedItems.clear()
                failed -= retry.size
                coroutineScope {
                    retry.map { item ->
                        async {
                            gate.withPermit {
                                val r = scanOne(item, previous, baseline)
                                when (r.status) {
                                    STATUS_FAILED -> failed += 1
                                    STATUS_HIT -> r.hit?.let { found[item.symbol] = it }
                                }
                                if (r.status == STATUS_HIT) publish()
                            }
                        }
                    }.awaitAll()
                }
            }
        } finally {
            publish()
            if (done > 0) {
                val now = System.currentTimeMillis()
                lastFinishedAt = now
                hasBaseline = true
                val snapshot = hits
                withContext(NonCancellable + Dispatchers.IO) {
                    ScannerStore.save(appContext, now, selected.name, snapshot)
                }
            }
        }

        val newOnes = found.values.count { it.isNew }
        if (baseline && newOnes > 0 && !isForeground() && prefs.getBoolean("alert_signal", true)) {
            val best = found.values.filter { it.isNew }.maxByOrNull { it.confidence }
            NotificationHelper.notifyMarket(
                appContext, "SCAN|${System.currentTimeMillis()}",
                tr("📡 Новые прогнозы: $newOnes", "📡 New forecasts: $newOnes"),
                best?.let { "${it.symbol} • ${it.signal} • ${it.confidence}%" }.orEmpty(),
                "SCANNER"
            )
        }
        message = when {
            failed > 0 && failed >= total / 2 -> tr("Не удалось получить данные по $failed из $total инструментов. ${repo.lastBcsError ?: "Проверьте токен и сеть."}", "Could not get data for $failed of $total instruments. ${repo.lastBcsError ?: "Check the token and the network."}")
            !baseline -> tr("Первое сканирование завершено: ${found.size} сигналов. Со следующего запуска новые прогнозы будут отмечены.", "First scan finished: ${found.size} signals. New forecasts will be flagged from the next run.")
            newOnes > 0 -> tr("Найдено новых прогнозов: $newOnes.", "New forecasts found: $newOnes.")
            else -> tr("Новых прогнозов нет. Активных сигналов: ${found.size}.", "No new forecasts. Active signals: ${found.size}.")
        }
    }

    private suspend fun scanOne(item: SearchResult, previous: Map<String, String>, baseline: Boolean): ScanResult {
        val candles = withContext(Dispatchers.IO) {
            runCatching { repo.loadScanCandles(item.symbol, item.classCode) }.getOrNull()
        } ?: return ScanResult(STATUS_FAILED, null)
        if (!isTradable(item, candles)) return ScanResult(STATUS_SKIPPED, null)
        val f = withContext(Dispatchers.Default) {
            runCatching { AnalyticsEngine.analyze(candles, null) }.getOrNull()
        } ?: return ScanResult(STATUS_FAILED, null)
        if (f.signal == "NO TRADE") return ScanResult(STATUS_NO_SIGNAL, null)

        val last = candles.last()
        val prevClose = candles.getOrNull(candles.size - 2)?.close ?: last.close
        val change = if (prevClose > 0.0) (last.close - prevClose) / prevClose * 100.0 else 0.0
        val old = previous[item.symbol].orEmpty()
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
            scannedAt = System.currentTimeMillis(),
            isNew = baseline && old != f.signal,
            prevSignal = old
        )
        return ScanResult(STATUS_HIT, hit)
    }

    private fun isTradable(item: SearchResult, c: List<Candle>): Boolean {
        if (c.size < MIN_BARS) return false
        val last = c.last()
        if (last.close <= 0.0) return false
        if (System.currentTimeMillis() - last.time > STALE_MS) return false // delisted or halted
        val isFx = item.type.contains("CURRENCY", true) || item.type.contains("FOREX", true)
        if (!isFx && c.takeLast(10).sumOf { it.volume } <= 0.0) return false
        return true
    }

    private fun buildUniverse(selected: ScanScope, favorites: Set<String>): List<SearchResult> {
        val favKeys = favorites.map { repo.canonicalSymbol(it).substringBefore("@").uppercase(Locale.US) }.toSet()
        val catalog = repo.catalogSnapshot()
        val picked = catalog.filter { r ->
            when (selected) {
                ScanScope.MAIN -> boardRank(r) == 0
                ScanScope.ALL -> true
                ScanScope.RU_STOCKS -> r.type.equals("STOCK", true)
                ScanScope.FOREIGN -> r.type.equals("FOREIGN_STOCK", true) || r.type.equals("DEPOSITARY_RECEIPTS", true)
                ScanScope.FX -> isFxType(r.type)
                ScanScope.FAVORITES -> r.symbol.uppercase(Locale.US) in favKeys
            }
        }.toMutableList()
        if (selected == ScanScope.FAVORITES) {
            val have = picked.map { it.symbol.uppercase(Locale.US) }.toSet()
            for (key in favKeys) if (key !in have) picked.add(SearchResult(key, key, "БКС", "STOCK", "БКС", ""))
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

    private fun isFxType(type: String) = type.contains("CURRENCY", true) || type.contains("FOREX", true)

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

    private fun loadScope(): ScanScope =
        runCatching { ScanScope.valueOf(prefs.getString(PREF_SCOPE, ScanScope.MAIN.name).orEmpty()) }.getOrDefault(ScanScope.MAIN)

    companion object {
        private const val PREF_SCOPE = "scanner_scope"
        private const val PREF_AUTO = "scanner_auto"
        private const val PARALLEL = 6
        private const val MIN_BARS = 60
        private const val STALE_MS = 14L * 86_400_000L
        private const val STATUS_FAILED = 0
        private const val STATUS_SKIPPED = 1
        private const val STATUS_NO_SIGNAL = 2
        private const val STATUS_HIT = 3

        /** Auto-start on app launch only when the previous scan is older than this. */
        const val AUTO_START_AFTER_MS = 30L * 60_000L
    }
}
