package com.marketforecast.prox

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Internet-first autocomplete source.
 *
 * This is intentionally metadata-only: it supplies names/tickers for suggestions.
 * Quotes, candles, instrument identity/classCode and forecast data remain BCS-only.
 * The source is MOEX ISS, a public internet reference service for securities.
 */
class InternetInstrumentSearch {
    companion object {
        private const val BASE = "https://iss.moex.com/iss"
        private const val TIMEOUT_MS = 2600
        private const val CACHE_MS = 90_000L
        private const val MAX_RESULTS = 40
        private val cache = ConcurrentHashMap<String, Pair<Long, List<SearchResult>>>()

        fun search(query: String, filter: String = "ALL"): List<SearchResult> {
            val q = query.trim()
            if (q.isBlank()) return emptyList()
            val key = "${filter.uppercase(Locale.US)}|${q.lowercase(Locale.ROOT)}"
            cache[key]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.let { return it.second }

            val encoded = URLEncoder.encode(q, Charsets.UTF_8.name())
            val url = when (filter.uppercase(Locale.US)) {
                "STOCK" -> "$BASE/engines/stock/markets/shares/securities.json?q=$encoded&iss.meta=off&iss.only=securities"
                "FX" -> "$BASE/engines/currency/markets/selt/securities.json?q=$encoded&iss.meta=off&iss.only=securities"
                else -> "$BASE/securities.json?q=$encoded&iss.meta=off&iss.only=securities"
            }

            val direct = runCatching { request(url, filter) }.getOrDefault(emptyList())
            val result = if (direct.isNotEmpty()) direct else {
                // MOEX search is already transliteration-aware for many queries, but a
                // second query helps common Russian-name input without adding latency to
                // the normal successful path.
                val latin = transliterate(q)
                if (latin != q.lowercase(Locale.ROOT)) {
                    val e2 = URLEncoder.encode(latin, Charsets.UTF_8.name())
                    val u2 = when (filter.uppercase(Locale.US)) {
                        "STOCK" -> "$BASE/engines/stock/markets/shares/securities.json?q=$e2&iss.meta=off&iss.only=securities"
                        "FX" -> "$BASE/engines/currency/markets/selt/securities.json?q=$e2&iss.meta=off&iss.only=securities"
                        else -> "$BASE/securities.json?q=$e2&iss.meta=off&iss.only=securities"
                    }
                    runCatching { request(u2, filter) }.getOrDefault(emptyList())
                } else emptyList()
            }

            cache[key] = System.currentTimeMillis() to result
            return result
        }

        private fun request(url: String, filter: String): List<SearchResult> {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("User-Agent", "MarketForecastPROX/4.8.107")
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.7")
            try {
                if (c.responseCode !in 200..299) return emptyList()
                val root = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val block = root.optJSONObject("securities") ?: return emptyList()
                val columns = block.optJSONArray("columns") ?: return emptyList()
                val data = block.optJSONArray("data") ?: JSONArray()
                val indexes = columns.asIndexMap()
                val out = LinkedHashMap<String, SearchResult>()
                for (i in 0 until data.length()) {
                    val row = data.optJSONArray(i) ?: continue
                    val symbol = row.str(indexes, "secid", "id").trim().uppercase(Locale.US)
                    if (symbol.isBlank()) continue
                    val name = row.str(indexes, "shortname", "name", "secname").trim().ifBlank { symbol }
                    val type = row.str(indexes, "type", "group", "market").trim().ifBlank {
                        if (filter.equals("FX", true)) "CURRENCY" else "STOCK"
                    }
                    val group = row.str(indexes, "group", "type", "market").uppercase(Locale.US)
                    val board = row.str(indexes, "primary_boardid", "marketprice_boardid", "boardid")
                    val isCurrency = filter.equals("FX", true) ||
                        type.contains("CURRENCY", true) || type.contains("FOREX", true) ||
                        group.contains("CURRENCY") || group.contains("FOREX") || group.contains("FX")
                    val isStock = filter.equals("STOCK", true) ||
                        type.contains("STOCK", true) || type.contains("SHARE", true) ||
                        group.contains("STOCK") || group.contains("SHARE")
                    if (filter.equals("FX", true) && !isCurrency) continue
                    if (filter.equals("STOCK", true) && !isStock) continue
                    if (filter.equals("ALL", true) && !isCurrency && !isStock) continue
                    val normalizedType = if (isCurrency) "CURRENCY" else "STOCK"
                    val key = "$symbol@${board.uppercase(Locale.US)}"
                    out.putIfAbsent(key, SearchResult(symbol, name, board.ifBlank { "Московская биржа" }, normalizedType, "Интернет"))
                    if (out.size >= MAX_RESULTS) break
                }
                return rank(out.values.toList(), q = urlQuery(url))
            } finally {
                c.disconnect()
            }
        }

        private fun urlQuery(url: String): String = runCatching {
            url.substringAfter("q=").substringBefore('&').let { java.net.URLDecoder.decode(it, "UTF-8") }
        }.getOrDefault("")

        private fun rank(values: List<SearchResult>, q: String): List<SearchResult> {
            val n = normalize(q)
            val latin = transliterate(n)
            return values.mapNotNull { item ->
                val s = normalize(item.symbol)
                val name = normalize(item.name)
                val nl = transliterate(name)
                val score = when {
                    s == n -> 0
                    name == n -> 1
                    s.startsWith(n) -> 2
                    name.startsWith(n) -> 3
                    s.startsWith(latin) -> 4
                    nl.startsWith(latin) -> 5
                    s.contains(n) -> 6
                    name.contains(n) -> 7
                    else -> return@mapNotNull null
                }
                score to item
            }.sortedWith(compareBy<Pair<Int, SearchResult>> { it.first }.thenBy { it.second.name.length }).map { it.second }.take(MAX_RESULTS)
        }

        private fun JSONArray.asIndexMap(): Map<String, Int> = buildMap {
            for (i in 0 until length()) put(optString(i).lowercase(Locale.US), i)
        }

        private fun JSONArray.str(indexes: Map<String, Int>, vararg names: String): String {
            for (name in names) indexes[name.lowercase(Locale.US)]?.let { idx ->
                if (idx < length()) {
                    val v = optString(idx).trim()
                    if (v.isNotBlank() && !v.equals("null", true)) return v
                }
            }
            return ""
        }

        private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("\\s+"), " ")

        private fun transliterate(value: String): String {
            val map = mapOf(
                'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
                'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
                'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
                'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "",
                'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
            )
            return buildString { normalize(value).forEach { append(map[it] ?: it) } }
        }
    }
}
