package com.marketforecast.prox

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

private val FLAGS = mapOf(
    "USD" to "🇺🇸", "EUR" to "🇪🇺", "CNY" to "🇨🇳", "GBP" to "🇬🇧", "JPY" to "🇯🇵", "CHF" to "🇨🇭",
    "TRY" to "🇹🇷", "HKD" to "🇭🇰", "KZT" to "🇰🇿", "BYN" to "🇧🇾", "AED" to "🇦🇪", "INR" to "🇮🇳",
    "AMD" to "🇦🇲", "GEL" to "🇬🇪", "UZS" to "🇺🇿", "KGS" to "🇰🇬", "AZN" to "🇦🇿"
)

/** "USD" for USD000UTSTOM, "CNY" for CNYRUB_TOM and so on; null for everything that is not a ruble currency pair. */
internal fun currencyBase(symbol: String): String? {
    val s = symbol.uppercase(Locale.US)
    if (s.length < 6) return null
    val code = s.substring(0, 3)
    return if (code in FLAGS && (s.contains("RUB") || s.startsWith("USD000"))) code else null
}

/** Currency pairs are shown the way people read them: "USD/RUB", not "USD000UTSTOM". */
internal fun fxPairLabel(symbol: String): String? = currencyBase(symbol)?.let { "$it/RUB" }

/**
 * Round icon of an instrument. Currencies get the flag with a ruble badge, companies get their logo when one can be
 * loaded and a coloured avatar with the ticker letters otherwise (also while the logo is loading or when offline).
 */
@Composable
internal fun InstrumentIcon(symbol: String, name: String, kind: String = "", size: Dp = 40.dp) {
    val base = currencyBase(symbol)
    if (base != null || kind.contains("CURRENCY", ignoreCase = true) || kind.equals("CETS", ignoreCase = true)) {
        CurrencyIcon(base, size)
    } else {
        CompanyIcon(symbol, size)
    }
}

@Composable
private fun CurrencyIcon(base: String?, size: Dp) {
    Box(Modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Color(0xFF16233A)),
            contentAlignment = Alignment.Center
        ) {
            Text(FLAGS[base ?: ""] ?: "¤", fontSize = (size.value * 0.52f).sp)
        }
        Box(
            Modifier.align(Alignment.BottomEnd).size(size * 0.42f).clip(CircleShape).background(Accent),
            contentAlignment = Alignment.Center
        ) {
            Text("₽", color = Color.White, fontWeight = FontWeight.Black, fontSize = (size.value * 0.26f).sp)
        }
    }
}

@Composable
private fun CompanyIcon(symbol: String, size: Dp) {
    val appContext = LocalContext.current.applicationContext
    val logo by produceState<ImageBitmap?>(null, symbol, UiPrefs.logos) {
        value = if (UiPrefs.logos) withContext(Dispatchers.IO) { LogoStore.load(appContext, symbol) } else null
    }
    Box(Modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        val image = logo
        if (image != null) {
            Box(Modifier.matchParentSize().background(Color.White))
            Image(
                bitmap = image, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize().padding(size * 0.08f)
            )
        } else {
            val hue = ((symbol.hashCode() and 0x7fffffff) % 360).toFloat()
            Box(
                Modifier.matchParentSize().background(
                    Brush.linearGradient(listOf(Color.hsv(hue, 0.55f, 0.62f), Color.hsv((hue + 28f) % 360f, 0.6f, 0.42f)))
                ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    symbol.filter { it.isLetter() }.take(2).uppercase(Locale.US),
                    color = Color.White, fontWeight = FontWeight.Black, fontSize = (size.value * 0.34f).sp
                )
            }
        }
    }
}

private fun httpBytes(url: String, readTimeout: Int, limit: Int): ByteArray? {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 6000
        connection.readTimeout = readTimeout
        connection.setRequestProperty("User-Agent", "MarketForecast/4")
        if (connection.responseCode != 200) return null
        val bytes = connection.inputStream.use { it.readBytes() }
        if (bytes.isEmpty() || bytes.size > limit) null else bytes
    } finally {
        connection.disconnect()
    }
}

/**
 * Company logos by ISIN, cached on the device. The broker does not publish logos, so this uses a public brand CDN;
 * when it has nothing for an instrument (or there is no network) the coloured avatar is shown, nothing breaks.
 * It can be switched off in Settings.
 */
internal object LogoStore {
    private const val CDN = "https://invest-brands.cdn-tinkoff.ru/"
    private const val RETRY_AFTER_MS = 6L * 3_600_000L
    private val memory = LruCache<String, ImageBitmap>(120)
    private val misses = ConcurrentHashMap<String, Long>()
    private val gate = Semaphore(4)

    fun load(context: Context, symbol: String): ImageBitmap? {
        val isin = IsinDirectory.get(context, symbol) ?: return null
        memory.get(isin)?.let { return it }
        val dir = File(context.cacheDir, "logos").apply { mkdirs() }
        val file = File(dir, "$isin.png")
        if (file.exists() && file.length() > 0L) {
            decode(file)?.let { memory.put(isin, it); return it }
            file.delete()
        }
        val failedAt = misses[isin]
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) return null
        gate.acquire()
        try {
            val bytes = httpBytes(CDN + isin + "x160.png", 8000, 400_000)
            if (bytes == null) {
                misses[isin] = System.currentTimeMillis()
                return null
            }
            file.writeBytes(bytes)
            val image = decode(file)
            if (image == null) {
                file.delete()
                misses[isin] = System.currentTimeMillis()
                return null
            }
            memory.put(isin, image)
            return image
        } catch (t: Throwable) {
            misses[isin] = System.currentTimeMillis()
            return null
        } finally {
            gate.release()
        }
    }

    private fun decode(file: File): ImageBitmap? = BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()

    /** Step-by-step check of the whole chain, so "why is there no logo" has a concrete answer. */
    fun diagnose(context: Context): String {
        val lines = ArrayList<String>()
        IsinDirectory.forceRetry()
        val count = IsinDirectory.size(context)
        lines.add(if (count > 0) "✓ Справочник ISIN: $count тикеров" else "✗ Справочник ISIN не загрузился (Московская биржа недоступна из этой сети?)")
        val isin = IsinDirectory.get(context, "SBER")
        lines.add(if (isin != null) "✓ ISIN SBER: $isin" else "✗ ISIN для SBER не найден")
        if (isin != null) {
            val url = CDN + isin + "x160.png"
            val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
            if (connection == null) {
                lines.add("✗ Не удалось открыть адрес сервиса логотипов")
            } else {
                try {
                    connection.connectTimeout = 6000
                    connection.readTimeout = 8000
                    connection.setRequestProperty("User-Agent", "MarketForecast/4")
                    val code = connection.responseCode
                    lines.add(if (code == 200) "✓ Сервис логотипов отвечает: HTTP 200" else "✗ Сервис логотипов ответил HTTP $code")
                } catch (t: Throwable) {
                    lines.add("✗ Сервис логотипов недоступен: " + (t.message ?: t.javaClass.simpleName))
                } finally {
                    connection.disconnect()
                }
            }
        }
        val cached = File(context.cacheDir, "logos").listFiles()?.size ?: 0
        lines.add("Логотипов в кэше на устройстве: $cached")
        lines.add("Если шаги выше зелёные, а логотипов нет, откройте экран с акциями заново: значки подгружаются по мере показа.")
        return lines.joinToString("\n")
    }
}

/** Ticker to ISIN for the main board of the Moscow Exchange: one small request, kept on the device for a week. */
internal object IsinDirectory {
    private const val FILE = "isin_tqbr.json"
    private const val URL_ISIN =
        "https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities.json?iss.meta=off&iss.only=securities&securities.columns=SECID,ISIN"
    @Volatile private var map: Map<String, String>? = null
    @Volatile private var nextTry = 0L

    fun get(context: Context, symbol: String): String? {
        ensure(context)
        return map?.get(symbol.uppercase(Locale.US))
    }

    fun size(context: Context): Int {
        ensure(context)
        return map?.size ?: 0
    }

    /** Lets the next lookup try the network again right away (used by the diagnostics button). */
    fun forceRetry() {
        synchronized(this) { if (map == null) nextTry = 0L }
    }

    private fun ensure(context: Context) {
        if (map != null) return
        synchronized(this) {
            if (map != null) return
            if (System.currentTimeMillis() < nextTry) return
            val file = File(context.filesDir, FILE)
            var loaded = read(file)
            val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > 7L * 24 * 3_600_000L
            if (loaded.isEmpty() || stale) {
                val fresh = fetch(file)
                if (fresh.isNotEmpty()) loaded = fresh
            }
            if (loaded.isNotEmpty()) map = loaded else nextTry = System.currentTimeMillis() + 10L * 60_000L
        }
    }

    private fun read(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            val o = JSONObject(file.readText())
            val out = HashMap<String, String>(o.length())
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.optString(k)
            }
            out as Map<String, String>
        }.getOrDefault(emptyMap())
    }

    private fun fetch(file: File): Map<String, String> = runCatching {
        val bytes = httpBytes(URL_ISIN, 12000, 2_000_000) ?: return@runCatching emptyMap<String, String>()
        val data = JSONObject(String(bytes, Charsets.UTF_8)).optJSONObject("securities")?.optJSONArray("data")
            ?: return@runCatching emptyMap<String, String>()
        val out = HashMap<String, String>(data.length())
        val json = JSONObject()
        for (i in 0 until data.length()) {
            val row = data.optJSONArray(i) ?: continue
            val id = row.optString(0).trim().uppercase(Locale.US)
            val isin = row.optString(1).trim()
            if (id.isNotBlank() && isin.length == 12) {
                out[id] = isin
                json.put(id, isin)
            }
        }
        if (out.isNotEmpty()) file.writeText(json.toString())
        out as Map<String, String>
    }.getOrDefault(emptyMap())
}

/** Settings row: checks the whole logo chain and shows the result. */
@Composable
internal fun LogoDiagnosticsRow(ru: Boolean) {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        OutlinedButton(onClick = {
            if (!busy) {
                busy = true
                scope.launch {
                    report = withContext(Dispatchers.IO) { LogoStore.diagnose(appContext) }
                    busy = false
                }
            }
        }) { Text(if (busy) (if (ru) "Проверяю…" else "Checking…") else (if (ru) "Проверить логотипы" else "Check logos")) }
        report?.let {
            Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
