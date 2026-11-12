package com.marketforecast.prox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What the monitor is doing right now, for the scanner screen. */
internal object MonitorState {
    var running by mutableStateOf(false)
    var pass by mutableIntStateOf(0)
    var checked by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var found by mutableIntStateOf(0)
    var note by mutableStateOf("")
}

/**
 * Background monitor: walks through the whole list, then does it again, and again, until it is switched off. Works with the
 * screen locked (foreground service + partial wake lock). Only NEW forecasts are announced: one that is already on the list
 * stays quiet. Price alerts are checked on the side every half minute.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var alerts: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }
        val ru = prefs().getBoolean("ru", true)
        startInForeground(notification(if (ru) "Запускаю…" else "Starting…", null))
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
        return START_STICKY
    }

    /** Android 15 limits data-sync services to a few hours a day and then asks them to stop; do it cleanly instead of crashing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val ru = prefs().getBoolean("ru", true)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        nm.notify(
            ID + 1,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.mfp_notification)
                .setContentTitle(if (ru) "Фоновый мониторинг остановлен системой" else "Background monitoring was stopped by Android")
                .setContentText(if (ru) "Android ограничивает такие службы по времени. Откройте сканер и включите мониторинг снова." else "Android limits such services in time. Open the scanner and switch monitoring on again.")
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
        )
        stopMonitoring()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        MonitorState.running = false
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("mfprefs", Context.MODE_PRIVATE)

    private fun stopMonitoring() {
        loop?.cancel()
        alerts?.cancel()
        releaseWakeLock()
        MonitorState.running = false
        prefs().edit().putBoolean("monitor_on", false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mfp:monitor").also { it.setReferenceCounted(false); wakeLock = it }
        // Renewed on every step: a forgotten lock can never outlive the work by more than ten minutes.
        lock.acquire(10 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            val ru = prefs().getBoolean("ru", true)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, if (ru) "Фоновый мониторинг" else "Background monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                    description = if (ru) "Показывает, что сканер ищет прогнозы в фоне" else "Shows that the scanner is looking for forecasts in the background"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("mfp_target", "SCANNER")
            putExtra("mfp_from_notification", true)
        }
        return PendingIntent.getActivity(this, 20, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notification(text: String, progress: Pair<Int, Int>?): Notification {
        val ru = prefs().getBoolean("ru", true)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        val stop = PendingIntent.getService(
            this, 21, Intent(this, MonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.mfp_notification)
            .setContentTitle(if (ru) "Фоновый мониторинг включён" else "Background monitoring is on")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, if (ru) "Остановить" else "Stop", stop)
        if (progress != null && progress.second > 0) builder.setProgress(progress.second, progress.first.coerceAtMost(progress.second), false)
        return builder.build()
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
    }

    private fun show(text: String, progress: Pair<Int, Int>? = null) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification(text, progress))
    }

    private suspend fun runLoop() {
        val p = prefs()
        val ru = p.getBoolean("ru", true)
        CostModel.load(p)
        val token = SecureTokenStore.get(p)
        if (token.isBlank()) {
            show(if (ru) "Нужен токен БКС: добавьте его в Настройках." else "A BCS token is needed: add it in Settings.")
            stopMonitoring()
            return
        }
        val repo = MarketRepository(bcsRefreshToken = token, prefs = p, context = applicationContext)
        MonitorState.running = true
        MonitorState.pass = 0
        p.edit().putBoolean("monitor_on", true).apply()
        alerts = scope.launch {
            while (isActive) {
                runCatching { PriceAlertEngine.check(applicationContext, p, { repo.quote(it) }, ru) }
                delay(30_000L)
            }
        }
        try {
            while (scope.isActive) {
                acquireWakeLock()
                val closed = scanPass(repo, p, ru)
                MonitorState.note = if (closed) (if (ru) "Торги закрыты, проверю позже" else "The market is closed, will check later") else ""
                delay(if (closed) 5 * 60_000L else 15_000L)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            show((if (ru) "Остановлено из-за ошибки: " else "Stopped because of an error: ") + (t.message ?: t.javaClass.simpleName))
            stopMonitoring()
        }
    }

    /** One full walk through the list. Returns true when the horizon is intraday and the exchange is closed. */
    private suspend fun scanPass(repo: MarketRepository, p: android.content.SharedPreferences, ru: Boolean): Boolean {
        val horizon = ScanScheduler.horizonOf(p)
        val scanScope = runCatching { ScanScope.valueOf(p.getString("scanner_scope", ScanScope.MAIN.name).orEmpty()) }.getOrDefault(ScanScope.MAIN)
        val favorites = p.getStringSet("favorites", emptySet()).orEmpty().toSet()
        val universe = withContext(Dispatchers.IO) {
            if (repo.catalogSize() == 0) runCatching { repo.bootstrapCatalog() }
            ScanCore.buildUniverse(repo, scanScope, favorites)
        }
        if (universe.isEmpty()) {
            show(if (ru) "Список для проверки пуст: выберите область в сканере." else "The list to check is empty: choose a scope in the scanner.")
            return false
        }
        val tf = horizon.tf
        val snapshot = withContext(Dispatchers.IO) { ScannerStore.load(applicationContext) }
        val now0 = System.currentTimeMillis()
        // What is already on the list (and still valid) stays quiet: only a forecast that is not there yet is "new".
        val previous = snapshot?.hits.orEmpty().filter { it.timeframe == tf && it.expiresAt > now0 }.associate { it.symbol to it.signal }
        val baseline = MonitorState.pass > 0 || (snapshot?.finished?.get(tf) ?: 0L) > 0L
        MonitorState.pass += 1
        MonitorState.total = universe.size
        MonitorState.checked = 0
        MonitorState.found = 0
        val gate = Semaphore(4)
        val outcomes = ArrayList<Pair<SearchResult, ScanCore.Outcome>>()
        var answered = 0
        var stale = 0
        for (chunk in universe.chunked(24)) {
            if (!scope.isActive) return false
            val part = coroutineScope {
                chunk.map { item ->
                    async(Dispatchers.IO) {
                        gate.withPermit { item to ScanCore.evaluate(repo, item, horizon, previous[item.symbol].orEmpty(), baseline) }
                    }
                }.awaitAll()
            }
            outcomes.addAll(part)
            for ((_, o) in part) {
                if (o.status != ScanCore.FAILED) {
                    answered += 1
                    if (o.status == ScanCore.STALE) stale += 1
                }
            }
            MonitorState.checked = outcomes.size
            MonitorState.found = outcomes.count { it.second.hit != null }
            show(
                (if (ru) "Круг ${MonitorState.pass}: проверено ${outcomes.size} из ${universe.size} • найдено ${MonitorState.found}" else "Pass ${MonitorState.pass}: checked ${outcomes.size} of ${universe.size} • found ${MonitorState.found}") +
                    " • " + horizon.label(ru),
                outcomes.size to universe.size
            )
            if (horizon.intraday && answered >= minOf(24, maxOf(6, universe.size / 3)) && stale * 10 >= answered * 9) return true
        }
        val now = System.currentTimeMillis()
        val scanned = outcomes.filter { it.second.status != ScanCore.FAILED }.map { it.first.symbol }.toSet()
        val hits = outcomes.mapNotNull { it.second.hit }
        withContext(Dispatchers.IO) { ScannerStore.saveRun(applicationContext, tf, scanScope.name, now, scanned, hits, markFinished = true) }
        val fresh = hits.filter { it.isNew && !it.watch }
        if (baseline && fresh.isNotEmpty() && p.getBoolean("alert_signal", true)) {
            ScanNotify.post(applicationContext, horizon, fresh, ru, p.getInt("alert_min_confidence", 60).coerceIn(0, 100))
        }
        return false
    }

    companion object {
        const val ACTION_STOP = "com.marketforecast.prox.action.STOP_MONITOR"
        private const val CHANNEL = "mfp_monitor"
        private const val ID = 4107

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MonitorService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** Scanner screen: one switch for the background monitor and a plain status line. */
@Composable
internal fun MonitorControl(ru: Boolean) {
    val ctx = LocalContext.current
    GradientCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (ru) "Фоновый мониторинг" else "Background monitoring", fontWeight = FontWeight.Black, fontSize = 15.sp)
                Text(
                    if (MonitorState.running) {
                        (if (ru) "Круг ${MonitorState.pass}: проверено ${MonitorState.checked} из ${MonitorState.total} • найдено ${MonitorState.found}" else "Pass ${MonitorState.pass}: checked ${MonitorState.checked} of ${MonitorState.total} • found ${MonitorState.found}") +
                            (if (MonitorState.note.isNotBlank()) " • " + MonitorState.note else "")
                    } else (if (ru) "Выключен" else "Off"),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (MonitorState.running) PulseDot(Positive, 8.dp)
        }
        Button(
            onClick = { if (MonitorState.running) MonitorService.stop(ctx) else MonitorService.start(ctx) },
            colors = ButtonDefaults.buttonColors(containerColor = if (MonitorState.running) Negative else Accent),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        ) {
            Text(
                if (MonitorState.running) (if (ru) "Остановить мониторинг" else "Stop monitoring") else (if (ru) "Включить фоновый мониторинг" else "Start background monitoring"),
                fontWeight = FontWeight.Black
            )
        }
        Text(
            if (ru) "Сканирует выбранную область и срок круг за кругом, пока вы его не выключите, в том числе при заблокированном экране. Уведомляет только о новых прогнозах: те, что уже есть в списке, молчат. Заодно проверяет ваши цены."
            else "Scans the chosen scope and horizon pass after pass until you switch it off, also with the screen locked. It only announces NEW forecasts: the ones already on the list stay quiet. It also checks your price alerts.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
        )
        TextButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
            Text(if (ru) "Разрешить работу без ограничений батареи" else "Allow unrestricted battery use", fontSize = 12.sp)
        }
    }
}
