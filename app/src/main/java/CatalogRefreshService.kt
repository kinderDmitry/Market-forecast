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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * Owns the long-running BCS directory refresh independently from Compose screens.
 * Leaving Settings or navigating elsewhere therefore never cancels the download.
 */
class CatalogRefreshService : Service() {
    companion object {
        const val ACTION_START = "com.marketforecast.prox.catalog.START"
        const val ACTION_STOP = "com.marketforecast.prox.catalog.STOP"
        private const val CHANNEL = "mfp_catalog"
        private const val NOTIFICATION_ID = 41095
        private const val PREFS = "mfprefs"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRefresh(true)
            return START_NOT_STICKY
        }
        if (job?.isActive == true || prefs.getBoolean("catalog_refresh_running", false)) {
            if (job?.isActive != true) startJob()
            return START_STICKY
        }
        startJob()
        return START_STICKY
    }

    private fun startJob() {
        val token = prefs.getString("bcs_refresh_token", "")?.trim().orEmpty()
        if (token.isBlank()) {
            prefs.edit().putBoolean("catalog_refresh_running", false)
                .putString("catalog_refresh_error", "БКС не подключён")
                .apply()
            stopSelf()
            return
        }
        prefs.edit()
            .putBoolean("catalog_refresh_running", true)
            .putFloat("catalog_refresh_progress", 0f)
            .putInt("catalog_refresh_types_total", 4)
            .putInt("catalog_refresh_types_done", 0)
            .putInt("catalog_refresh_current_page", 0)
            .putInt("catalog_refresh_items", 0)
            .putString("catalog_refresh_current_type", "Подготовка")
            .putString("catalog_refresh_status", "Подготовка полного каталога БКС…")
            .putString("catalog_refresh_error", "")
            .putLong("catalog_refresh_started_at", System.currentTimeMillis())
            .apply()

        startForeground(NOTIFICATION_ID, notification("Каталог БКС", "Подготовка…", 0))
        job = scope.launch {
            val repo = MarketRepository(bcsRefreshToken = token, prefs = prefs, context = this@CatalogRefreshService)
            runCatching {
                repo.refreshFullCatalog()
            }.onSuccess { items ->
                prefs.edit()
                    .putBoolean("catalog_refresh_running", false)
                    .putFloat("catalog_refresh_progress", 1f)
                    .putInt("catalog_refresh_items", items.size)
                    .putString("catalog_refresh_current_type", "Готово")
                    .putString("catalog_refresh_status", "Каталог сохранён: ${items.size} инструментов")
                    .putString("catalog_refresh_error", "")
                    .putLong("catalog_refresh_finished_at", System.currentTimeMillis())
                    .apply()
                updateNotification("Каталог БКС", "Готово: ${items.size} инструментов", 100, false)
            }.onFailure { error ->
                val message = error.message.orEmpty().ifBlank { "Неизвестная ошибка" }.take(240)
                prefs.edit()
                    .putBoolean("catalog_refresh_running", false)
                    .putString("catalog_refresh_error", message)
                    .putString("catalog_refresh_status", "Загрузка прервана: $message")
                    .apply()
                updateNotification("Каталог БКС", "Загрузка прервана", 0, false)
            }
            stopSelf()
        }
    }

    private fun stopRefresh(userStopped: Boolean) {
        job?.cancel()
        prefs.edit().putBoolean("catalog_refresh_running", false)
            .putString("catalog_refresh_status", if (userStopped) "Загрузка остановлена пользователем" else "")
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Обновление каталога", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Прогресс загрузки полного каталога БКС"
                setShowBadge(false)
            })
        }
    }

    private fun notification(title: String, text: String, progress: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .setContentIntent(PendingIntent.getActivity(this, 41095, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun updateNotification(title: String, text: String, progress: Int, ongoing: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .setContentIntent(PendingIntent.getActivity(this, 41095, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build())
    }
}
