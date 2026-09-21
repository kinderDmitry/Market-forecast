package com.marketforecast.prox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns a scanner run independently of MainActivity. Closing the app/task must not
 * cancel an active market scan. Progress/results are persisted so the next UI session
 * can reconnect to the same run instead of starting from zero.
 */
class ScannerForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.marketforecast.prox.SCANNER_START"
        const val ACTION_STOP = "com.marketforecast.prox.SCANNER_STOP"
        const val EXTRA_UNIVERSE = "universe"
        const val EXTRA_TIMEFRAMES = "timeframes"
        const val PREFS = "mfp_scanner_service"
        const val KEY_RUNNING = "running"
        const val KEY_UNIVERSE = "universe"
        const val KEY_TIMEFRAMES = "timeframes"
        const val KEY_DISCOVERED = "discovered"
        const val KEY_COMPLETED = "completed"
        const val KEY_SIGNALS = "signals"
        const val KEY_ERROR = "error"
        const val KEY_RESULTS = "results"
        const val CHANNEL = "mfp_scanner"
        const val NOTIFICATION_ID = 4711

        fun start(context: android.content.Context, universe: ScannerEngine.Universe, timeframes: List<String>) {
            val i = Intent(context, ScannerForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_UNIVERSE, universe.name)
                putExtra(EXTRA_TIMEFRAMES, timeframes.joinToString(","))
            }
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }


        fun isRunning(context: android.content.Context): Boolean =
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY_RUNNING, false)

        fun readProgress(context: android.content.Context): Triple<Int, Int, Int> {
            val p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            return Triple(p.getInt(KEY_DISCOVERED, 0), p.getInt(KEY_COMPLETED, 0), p.getInt(KEY_SIGNALS, 0))
        }

        fun readError(context: android.content.Context): String? =
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(KEY_ERROR, null)

        fun readResults(context: android.content.Context): List<ScannerEngine.Result> {
            val p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            val a = runCatching { JSONArray(p.getString(KEY_RESULTS, "[]")) }.getOrElse { JSONArray() }
            return buildList {
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val tfScores = mutableMapOf<String, Double>()
                    val ts = o.optJSONObject("timeframeScores")
                    if (ts != null) ts.keys().forEach { k -> tfScores[k] = ts.optDouble(k, 0.0) }
                    val tfConf = mutableMapOf<String, Int>()
                    val tc = o.optJSONObject("timeframeConfidence")
                    if (tc != null) tc.keys().forEach { k -> tfConf[k] = tc.optInt(k, 0) }
                    val instrument = SearchResult(o.optString("symbol"), o.optString("name"), o.optString("exchange"), o.optString("type"), o.optString("provider"), o.optString("classCode"))
                    add(ScannerEngine.Result(instrument, o.optString("signal"), o.optInt("confidence"), o.optDouble("score"), o.optDouble("entry"), o.optDouble("stop"), o.optDouble("tp1"), o.optDouble("tp2"), o.optDouble("tp3"), o.optDouble("rr"), o.optString("regime"), tfScores, tfConf, o.optLong("updatedAt")))
                }
            }
        }

        fun clearResults(context: android.content.Context) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .remove(KEY_RESULTS).remove(KEY_ERROR)
                .putInt(KEY_DISCOVERED, 0).putInt(KEY_COMPLETED, 0).putInt(KEY_SIGNALS, 0).apply()
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, ScannerForegroundService::class.java).setAction(ACTION_STOP))
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private var started = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        restoreConfigIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cancelled.set(true)
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val u = intent.getStringExtra(EXTRA_UNIVERSE).orEmpty().ifBlank { "ALL" }
                val tfs = intent.getStringExtra(EXTRA_TIMEFRAMES).orEmpty().ifBlank { "15M,1H,4H,1D" }
                val p = getSharedPreferences(PREFS, MODE_PRIVATE)
                p.edit().putString(KEY_UNIVERSE, u).putString(KEY_TIMEFRAMES, tfs)
                    .putBoolean(KEY_RUNNING, true).remove(KEY_ERROR).apply()
                startRunIfNeeded(u, tfs)
            }
            null -> {
                val p = getSharedPreferences(PREFS, MODE_PRIVATE)
                if (p.getBoolean(KEY_RUNNING, false)) startRunIfNeeded(p.getString(KEY_UNIVERSE, "ALL") ?: "ALL", p.getString(KEY_TIMEFRAMES, "15M,1H,4H,1D") ?: "15M,1H,4H,1D")
            }
        }
        return START_STICKY
    }

    private fun startRunIfNeeded(universeName: String, tfString: String) {
        if (started) return
        started = true
        cancelled.set(false)
        startForeground(NOTIFICATION_ID, notification("Сканер рынка запускается…"))
        executor.execute {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val universe = runCatching { ScannerEngine.Universe.valueOf(universeName) }.getOrDefault(ScannerEngine.Universe.ALL)
            val timeframes = tfString.split(',').map { it.trim() }.filter { it in setOf("15M", "1H", "4H", "1D") }.ifEmpty { listOf("15M", "1H", "4H", "1D") }
            val token = getSharedPreferences("mfprefs", MODE_PRIVATE).getString("bcs_refresh_token", "").orEmpty()
            val repo = MarketRepository(bcsRefreshToken = token.ifBlank { null }, prefs = getSharedPreferences("mfprefs", MODE_PRIVATE), context = this)
            val favorites = getSharedPreferences("mfprefs", MODE_PRIVATE).getStringSet("favorites", emptySet()).orEmpty()
            try {
                ScannerEngine(repo).scan(
                    ScannerEngine.Config(universe = universe, timeframes = timeframes, workers = if (universe == ScannerEngine.Universe.ALL) 4 else 3),
                    favorites = favorites,
                    cancelled = cancelled,
                    onProgress = { pr ->
                        prefs.edit().putInt(KEY_DISCOVERED, pr.discovered).putInt(KEY_COMPLETED, pr.completed).putInt(KEY_SIGNALS, pr.signals).apply()
                        updateNotification("Сканирование: ${pr.completed}/${pr.discovered} • сигналов ${pr.signals}")
                    },
                    onResult = { result ->
                        persistResult(result)
                    }
                )
            } catch (t: Throwable) {
                val message = t.message?.takeIf { it.isNotBlank() } ?: "Неизвестная ошибка сканера"
                prefs.edit().putString(KEY_ERROR, message).putBoolean(KEY_RUNNING, false).apply()
                updateNotification("Ошибка сканера: $message")
            } finally {
                prefs.edit().putBoolean(KEY_RUNNING, false).apply()
                updateNotification("Сканирование завершено")
                started = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun persistResult(r: ScannerEngine.Result) {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val old = runCatching { JSONArray(p.getString(KEY_RESULTS, "[]")) }.getOrElse { JSONArray() }
        val key = r.instrument.symbol + "@" + r.instrument.classCode
        val next = JSONArray()
        var replaced = false
        for (i in 0 until old.length()) {
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("key") == key) { next.put(resultJson(r, key)); replaced = true } else next.put(o)
        }
        if (!replaced) next.put(resultJson(r, key))
        while (next.length() > 200) {
            // Keep the newest set; scanner UI is a result stream, not an unbounded history.
            val trimmed = JSONArray()
            for (i in 1 until next.length()) trimmed.put(next.get(i))
            p.edit().putString(KEY_RESULTS, trimmed.toString()).apply()
            return
        }
        p.edit().putString(KEY_RESULTS, next.toString()).apply()
    }

    private fun resultJson(r: ScannerEngine.Result, key: String) = JSONObject().apply {
        put("key", key); put("symbol", r.instrument.symbol); put("name", r.instrument.name); put("exchange", r.instrument.exchange); put("type", r.instrument.type); put("provider", r.instrument.source); put("classCode", r.instrument.classCode)
        put("signal", r.signal); put("confidence", r.confidence); put("score", r.score); put("entry", r.entry); put("stop", r.stop); put("tp1", r.tp1); put("tp2", r.tp2); put("tp3", r.tp3); put("rr", r.rr); put("regime", r.regime); put("updatedAt", r.updatedAt)
        put("timeframeScores", JSONObject(r.timeframeScores as Map<*, *>)); put("timeframeConfidence", JSONObject(r.timeframeConfidence as Map<*, *>))
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_popup_sync)
        .setContentTitle("Market Forecast — Сканер")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun updateNotification(text: String) = getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Сканер рынка", NotificationManager.IMPORTANCE_LOW))
    }

    private fun restoreConfigIfNeeded() {}

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Deliberately do not stop the service: the scan must continue after the app task is swiped away.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
