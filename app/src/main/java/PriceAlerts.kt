package com.marketforecast.prox

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** "Tell me when SBER reaches 300": one level, one direction (above or below the price at the moment it was set). */
data class PriceAlert(
    val id: String,
    val symbol: String,
    val name: String,
    val target: Double,
    val above: Boolean,
    val createdAt: Long,
    val startPrice: Double,
    val active: Boolean = true,
    val triggeredAt: Long = 0L,
    val triggeredPrice: Double = 0.0
)

internal object PriceAlertStore {
    private const val KEY = "price_alerts_v1"

    /** Bumped on every change so the lists on screen refresh. */
    var version by mutableIntStateOf(0)
        private set

    @Synchronized
    fun load(prefs: SharedPreferences): List<PriceAlert> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                PriceAlert(
                    o.getString("id"), o.getString("symbol"), o.optString("name"), o.getDouble("target"), o.getBoolean("above"),
                    o.optLong("createdAt"), o.optDouble("startPrice"), o.optBoolean("active", true), o.optLong("triggeredAt"), o.optDouble("triggeredPrice")
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun save(prefs: SharedPreferences, list: List<PriceAlert>) {
        val array = JSONArray()
        for (a in list) {
            array.put(
                JSONObject()
                    .put("id", a.id).put("symbol", a.symbol).put("name", a.name)
                    .put("target", a.target).put("above", a.above).put("createdAt", a.createdAt)
                    .put("startPrice", a.startPrice.takeIf { it.isFinite() } ?: 0.0)
                    .put("active", a.active).put("triggeredAt", a.triggeredAt)
                    .put("triggeredPrice", a.triggeredPrice.takeIf { it.isFinite() } ?: 0.0)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        version += 1
    }

    @Synchronized
    fun add(prefs: SharedPreferences, alert: PriceAlert) {
        save(prefs, (load(prefs) + alert).takeLast(200))
    }

    @Synchronized
    fun remove(prefs: SharedPreferences, id: String) {
        save(prefs, load(prefs).filterNot { it.id == id })
    }

    @Synchronized
    fun markTriggered(prefs: SharedPreferences, id: String, price: Double, now: Long) {
        save(prefs, load(prefs).map { if (it.id == id) it.copy(active = false, triggeredAt = now, triggeredPrice = price) else it })
    }

    fun hasActive(prefs: SharedPreferences): Boolean = load(prefs).any { it.active }
}

internal object PriceAlertEngine {
    /**
     * Looks at the current price of every instrument that has an active alert and notifies about the ones that reached
     * their level. Called by the app while it is open, by the background monitor and by the periodic worker.
     */
    fun check(context: Context, prefs: SharedPreferences, quote: (String) -> Double?, ru: Boolean): Int {
        val active = PriceAlertStore.load(prefs).filter { it.active }
        if (active.isEmpty()) return 0
        // No point in consuming an alert that nobody could see: it stays active until notifications are possible.
        if (!NotificationHelper.canNotify(context, NotificationHelper.CHANNEL_TRACKING)) return 0
        var fired = 0
        for ((symbol, list) in active.groupBy { it.symbol.uppercase(Locale.US) }) {
            val price = runCatching { quote(symbol) }.getOrNull()?.takeIf { it > 0.0 } ?: continue
            for (a in list) {
                val reached = if (a.above) price >= a.target else price <= a.target
                if (!reached) continue
                PriceAlertStore.markTriggered(prefs, a.id, price, System.currentTimeMillis())
                val label = a.name.ifBlank { symbol }
                val title = if (ru) "🔔 $label: цена дошла до ${plain(a.target)}" else "🔔 $label reached ${plain(a.target)}"
                val body = if (ru) "$symbol сейчас ${plain(price)} (${if (a.above) "выше" else "ниже"} вашего уровня ${plain(a.target)})"
                else "$symbol is now ${plain(price)} (${if (a.above) "above" else "below"} your level ${plain(a.target)})"
                NotificationHelper.notifyTracking(context, "ALERT|${a.id}", ru, title, body, symbol)
                fired += 1
            }
        }
        return fired
    }
}

internal fun plain(v: Double): String = String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')

/** Analysis screen: set a price level and get a notification the moment it is reached. */
@Composable
internal fun PriceAlertCard(symbol: String, name: String, price: Double, f: Forecast?, ru: Boolean, prefs: SharedPreferences) {
    val version = PriceAlertStore.version
    val alerts = remember(version, symbol) { PriceAlertStore.load(prefs).filter { it.symbol.equals(symbol, ignoreCase = true) } }
    var input by remember(symbol) { mutableStateOf("") }
    var notice by remember(symbol) { mutableStateOf<String?>(null) }
    val notificationsOn = prefs.getBoolean("notifications_enabled", false)
    GradientCard(Modifier.fillMaxWidth()) {
        SectionHeader(
            if (ru) "Уведомление по цене" else "Price alert",
            if (ru) "Сообщу, когда цена дойдёт до нужного уровня" else "I will tell you when the price reaches the level"
        )
        if (price > 0.0) {
            Text(
                (if (ru) "Сейчас: " else "Now: ") + plain(price),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
            )
        }
        val quick = ArrayList<Pair<String, Double>>()
        if (price > 0.0) {
            quick.add("−3%" to price * 0.97); quick.add("−1%" to price * 0.99); quick.add("+1%" to price * 1.01); quick.add("+3%" to price * 1.03)
        }
        if (f != null && f.signal != "NO TRADE") {
            quick.add("TP1" to f.tp1); quick.add("TP2" to f.tp2)
            quick.add((if (ru) "Стоп" else "Stop") to f.stop)
        }
        if (quick.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                quick.filter { it.second > 0.0 }.forEach { (label, value) ->
                    FilterChip(selected = false, onClick = { input = plain(value) }, label = { Text(label, fontSize = 12.sp) })
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
            singleLine = true,
            label = { Text(if (ru) "Цена, при которой уведомить" else "Price to be notified at") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
        val target = input.replace(',', '.').toDoubleOrNull() ?: 0.0
        if (target > 0.0 && price > 0.0) {
            Text(
                if (target > price) (if (ru) "Сработает, когда цена поднимется до $input" else "Fires when the price rises to $input")
                else (if (ru) "Сработает, когда цена опустится до $input" else "Fires when the price falls to $input"),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
            )
        }
        Button(
            onClick = {
                when {
                    target <= 0.0 -> notice = if (ru) "Введите цену" else "Enter a price"
                    price <= 0.0 -> notice = if (ru) "Пока нет текущей цены, попробуйте через пару секунд" else "No current price yet, try again in a moment"
                    kotlin.math.abs(target - price) / price < 0.0005 -> notice = if (ru) "Цена уже на этом уровне" else "The price is already at that level"
                    else -> {
                        PriceAlertStore.add(prefs, PriceAlert(UUID.randomUUID().toString(), symbol.uppercase(Locale.US), name, target, target > price, System.currentTimeMillis(), price))
                        notice = if (ru) "Готово: уведомлю на уровне ${plain(target)}" else "Done: I will notify you at ${plain(target)}"
                        input = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text(if (ru) "Установить уведомление" else "Set the alert", fontWeight = FontWeight.Black) }
        notice?.let { Text(it, fontSize = 12.sp, color = Accent, modifier = Modifier.padding(top = 6.dp)) }
        if (!notificationsOn) {
            Text(
                if (ru) "Уведомления выключены: включите их в Настройках, иначе сообщение не придёт."
                else "Notifications are off: turn them on in Settings, otherwise nothing will arrive.",
                fontSize = 11.sp, color = Warning, modifier = Modifier.padding(top = 6.dp)
            )
        }
        alerts.forEach { a -> AlertRow(a, ru) { PriceAlertStore.remove(prefs, a.id) } }
    }
}

@Composable
private fun AlertRow(a: PriceAlert, ru: Boolean, onDelete: () -> Unit) {
    val time = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (a.above) "▲ " else "▼ ") + plain(a.target) + "  " +
                if (a.active) (if (ru) "ждёт" else "waiting")
                else (if (ru) "сработало ${time.format(Date(a.triggeredAt))} на ${plain(a.triggeredPrice)}" else "fired ${time.format(Date(a.triggeredAt))} at ${plain(a.triggeredPrice)}"),
            fontSize = 12.sp, fontWeight = if (a.active) FontWeight.Bold else FontWeight.Normal,
            color = if (a.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp)) }
    }
}

/** Settings: every alert in one place. */
@Composable
internal fun PriceAlertsSettings(ru: Boolean, prefs: SharedPreferences) {
    val version = PriceAlertStore.version
    val alerts = remember(version) { PriceAlertStore.load(prefs).sortedWith(compareByDescending<PriceAlert> { it.active }.thenByDescending { it.createdAt }) }
    GradientCard(Modifier.fillMaxWidth()) {
        SectionHeader(
            if (ru) "Уведомления по цене" else "Price alerts",
            if (ru) "Все ваши уровни. Новый уровень задаётся на экране инструмента" else "All your levels. Set a new one on the instrument screen"
        )
        if (alerts.isEmpty()) {
            Text(
                if (ru) "Пока нет ни одного. Откройте акцию или валюту и задайте цену в карточке «Уведомление по цене»."
                else "None yet. Open a stock or a currency and set a price in the \"Price alert\" card.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
            )
        }
        alerts.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    a.symbol + "  " + (if (a.above) "▲ " else "▼ ") + plain(a.target) + if (a.active) "" else (if (ru) "  (сработало)" else "  (fired)"),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (a.active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { PriceAlertStore.remove(prefs, a.id) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}
