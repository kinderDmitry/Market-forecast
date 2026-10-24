package com.marketforecast.prox

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Central notification delivery for foreground and background monitoring. */
object NotificationHelper {
    const val CHANNEL_TRACKING = "mfp_tracking"
    const val CHANNEL_MARKET = "mfp_market"
    const val CHANNEL_SYSTEM = "mfp_system"
    const val CHANNEL_SCANNER = "mfp_scanner"

    private const val PREFS = "mfprefs"
    private const val SENT_EVENTS = "alerted_events"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fun make(id: String, name: String, description: String, importance: Int = NotificationManager.IMPORTANCE_HIGH) = NotificationChannel(
            id, name, importance
        ).apply {
            this.description = description
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 120, 80, 180)
            enableLights(true)
            lightColor = when (id) {
                CHANNEL_TRACKING -> 0xFF00D084.toInt()
                CHANNEL_MARKET -> 0xFF00A8FF.toInt()
                CHANNEL_SCANNER -> 0xFF4DA3FF.toInt()
                else -> 0xFF0066D6.toInt()
            }
            setShowBadge(true)
        }
        nm.createNotificationChannel(make(CHANNEL_TRACKING, "Отслеживание прогнозов", "TP1, TP2, TP3, Stop Loss и итоговые результаты"))
        nm.createNotificationChannel(make(CHANNEL_MARKET, "Рынок и новости", "Сигналы, новости и рыночные события"))
        nm.createNotificationChannel(make(CHANNEL_SYSTEM, "Системные уведомления", "Состояние приложения и служебные события"))
        nm.createNotificationChannel(make(CHANNEL_SCANNER, "Сканер прогнозов", "Новые прогнозы, найденные сканером рынка", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Every kind of notification checks ITS OWN channel: a muted channel must not swallow (and "consume") an event. */
    fun canNotify(context: Context, channelId: String = CHANNEL_TRACKING): Boolean {
        // The in-app switch is the master switch: the background workers keep running for tracking, but stay silent.
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("notifications_enabled", false)) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        ensureChannels(context)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = nm.getNotificationChannel(channelId)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    /** Returns true only when this logical event was actually posted. */
    fun notifyTracking(context: Context, eventKey: String, ru: Boolean, title: String, body: String, symbol: String? = null): Boolean {
        if (!canNotify(context, CHANNEL_TRACKING)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sent = OrderedKeyStore.load(prefs, SENT_EVENTS)
        if (eventKey in sent) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("mfp_target", "TRACKING")
            putExtra("mfp_from_notification", true)
            if (!symbol.isNullOrBlank()) putExtra("mfp_symbol", symbol)
        }
        return runCatching {
            post(context, CHANNEL_TRACKING, eventKey, title, body, intent)
            sent.add(eventKey)
            OrderedKeyStore.save(prefs, SENT_EVENTS, sent)
            true
        }.getOrDefault(false)
    }

    /** Posts a direct notification. No event is marked as sent unless posting succeeds. */
    fun notifyDirect(context: Context, channel: String, title: String, body: String, target: String = "HOME", symbol: String? = null, url: String? = null, id: Int = 0): Boolean {
        if (!canNotify(context, channel)) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("mfp_target", target)
            putExtra("mfp_from_notification", true)
            if (!symbol.isNullOrBlank()) putExtra("mfp_symbol", symbol)
            if (!url.isNullOrBlank()) putExtra("mfp_news_url", url)
        }
        return runCatching {
            post(context, channel, "direct|$id|$title|$body", title, body, intent)
            true
        }.getOrDefault(false)
    }

    fun notifyMarket(context: Context, eventKey: String, title: String, body: String, target: String = "HOME", symbol: String? = null, url: String? = null): Boolean {
        if (!canNotify(context, CHANNEL_MARKET)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sent = OrderedKeyStore.load(prefs, SENT_EVENTS)
        if (eventKey in sent) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("mfp_target", target)
            putExtra("mfp_from_notification", true)
            if (!symbol.isNullOrBlank()) putExtra("mfp_symbol", symbol)
            if (!url.isNullOrBlank()) putExtra("mfp_news_url", url)
        }
        return runCatching {
            post(context, CHANNEL_MARKET, eventKey, title, body, intent)
            sent.add(eventKey)
            OrderedKeyStore.save(prefs, SENT_EVENTS, sent)
            true
        }.getOrDefault(false)
    }

    /** Scanner results: a separate channel, so they can be muted without touching TP/SL alerts. */
    fun notifyScanner(context: Context, eventKey: String, title: String, body: String, symbol: String? = null): Boolean {
        if (!canNotify(context, CHANNEL_SCANNER)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sent = OrderedKeyStore.load(prefs, SENT_EVENTS)
        if (eventKey in sent) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("mfp_target", if (symbol.isNullOrBlank()) "SCANNER" else "ANALYSIS")
            putExtra("mfp_from_notification", true)
            if (!symbol.isNullOrBlank()) putExtra("mfp_symbol", symbol)
        }
        return runCatching {
            post(context, CHANNEL_SCANNER, eventKey, title, body, intent)
            sent.add(eventKey)
            OrderedKeyStore.save(prefs, SENT_EVENTS, sent)
            true
        }.getOrDefault(false)
    }

    fun sendTest(context: Context, ru: Boolean) {
        if (!canNotify(context, CHANNEL_SYSTEM)) return
        val intent = Intent(context, MainActivity::class.java)
        post(
            context, CHANNEL_SYSTEM, "test", "Market Forecast",
            if (ru) "Уведомления настроены: TP/SL, завершение прогноза и рыночные события будут показываться здесь."
            else "Notifications are configured for TP/SL, forecast completion and market events.", intent
        )
    }

    private fun post(context: Context, channel: String, key: String, title: String, body: String, intent: Intent) {
        ensureChannels(context)
        val requestCode = 10000 + (key.hashCode() and 0x7fffffff) % 100000
        val pi = PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val largeIcon = BitmapFactory.decodeResource(context.resources, R.drawable.mfp_icon)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(notificationIcon(title, body))
            .setLargeIcon(largeIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(notificationSubText(channel))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(notificationCategory(channel, title, body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(notificationColor(channel, title, body))
            .setLights(notificationColor(channel, title, body), 500, 1200)
            .setVibrate(longArrayOf(0, 100, 70, 180))
        NotificationManagerCompat.from(context).notify(requestCode, builder.build())
    }


    private fun notificationIcon(title: String, body: String): Int {
        val text = (title + " " + body).lowercase()
        return when {
            text.contains("stop") || text.contains("стоп") || text.contains("loss") -> R.drawable.mfp_notification_stop
            text.contains("tp1") || text.contains("tp2") || text.contains("tp3") || text.contains("цель") || text.contains("достигнут") -> R.drawable.mfp_notification_target
            text.contains("новост") || text.contains("news") -> R.drawable.mfp_notification_news
            else -> R.drawable.mfp_notification
        }
    }

    private fun notificationColor(channel: String, title: String, body: String): Int {
        val text = (title + " " + body).lowercase()
        return when {
            text.contains("stop") || text.contains("стоп") || text.contains("loss") -> 0xFFFF304F.toInt()
            text.contains("tp1") || text.contains("tp2") || text.contains("tp3") || text.contains("достигнут") -> 0xFF00D084.toInt()
            text.contains("short") || text.contains("шорт") -> 0xFFFF4757.toInt()
            text.contains("long") || text.contains("лонг") -> 0xFF00D084.toInt()
            text.contains("no trade") || text.contains("no-trade") || text.contains("нет сделки") -> 0xFFFFA502.toInt()
            channel == CHANNEL_SYSTEM -> 0xFF0066D6.toInt()
            channel == CHANNEL_MARKET -> 0xFF00A8FF.toInt()
            else -> 0xFF0066D6.toInt()
        }
    }

    private fun notificationSubText(channel: String): String = when (channel) {
        CHANNEL_TRACKING -> "MFP • Отслеживание"
        CHANNEL_MARKET -> "MFP • Рынок"
        CHANNEL_SCANNER -> "MFP • Сканер"
        else -> "MFP • Система"
    }

    private fun notificationCategory(channel: String, title: String, body: String): String {
        val text = (title + " " + body).lowercase()
        return when {
            text.contains("новост") || text.contains("news") -> "news"
            channel == CHANNEL_TRACKING -> NotificationCompat.CATEGORY_PROGRESS
            else -> NotificationCompat.CATEGORY_STATUS
        }
    }

}
