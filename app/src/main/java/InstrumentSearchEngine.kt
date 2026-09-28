package com.marketforecast.prox

import java.util.Locale

/**
 * Fast, UI-first instrument search index. It is deliberately a small learned index:
 * popular instruments + instruments previously returned by authoritative BCS search.
 * It is NOT a downloaded catalogue and is never required for market-data correctness.
 */
class InstrumentSearchEngine(private val seed: List<SearchResult>) {
    private val items = LinkedHashMap<String, SearchResult>()

    init { seed.forEach(::put) }

    fun replaceLearned(values: Collection<SearchResult>) {
        values.forEach(::put)
    }

    fun addAll(values: Collection<SearchResult>) { values.forEach(::put) }

    fun all(): List<SearchResult> = items.values.toList()

    fun search(query: String, filter: String = "ALL", limit: Int = 30): List<SearchResult> {
        val q = normalize(query)
        if (q.isBlank()) return emptyList()
        val compact = q.replace(Regex("[^\\p{L}\\p{Nd}]+"), "")
        val latin = transliterate(q)
        return items.values.asSequence()
            .filter { matchesFilter(it, filter) }
            .mapNotNull { item ->
                val symbol = normalize(item.symbol)
                val name = normalize(item.name)
                val nameCompact = name.replace(Regex("[^\\p{L}\\p{Nd}]+"), "")
                val symbolCompact = symbol.replace(Regex("[^\\p{L}\\p{Nd}]+"), "")
                val nameLatin = transliterate(name)
                val aliases = aliasesFor(item.symbol).map(::normalize)
                val aliasCompact = aliases.map { it.replace(Regex("[^\\p{L}\\p{Nd}]+"), "") }
                val aliasLatin = aliases.map(::transliterate)
                val score = when {
                    symbol == q -> 0
                    name == q -> 1
                    symbol.startsWith(q) -> 2
                    name.startsWith(q) -> 3
                    symbolCompact.startsWith(compact) -> 4
                    nameCompact.startsWith(compact) -> 5
                    symbol.startsWith(latin) -> 6
                    nameLatin.startsWith(latin) -> 7
                    aliases.any { it == q } -> 1
                    aliases.any { it.startsWith(q) } -> 3
                    aliasCompact.any { it.startsWith(compact) } -> 5
                    aliasLatin.any { it.startsWith(latin) } -> 7
                    symbol.contains(q) -> 8
                    name.contains(q) -> 9
                    aliases.any { it.contains(q) } -> 9
                    symbol.contains(compact) || nameCompact.contains(compact) -> 10
                    else -> return@mapNotNull null
                }
                score to item
            }
            .sortedWith(compareBy<Pair<Int, SearchResult>> { it.first }
                .thenBy { it.second.name.length }
                .thenBy { it.second.name.lowercase(Locale.ROOT) })
            .map { it.second }
            .distinctBy { "${it.symbol.uppercase(Locale.US)}@${it.classCode.uppercase(Locale.US)}" }
            .take(limit)
            .toList()
    }

    private fun put(item: SearchResult) {
        val key = "${item.symbol.uppercase(Locale.US)}@${item.classCode.uppercase(Locale.US)}"
        items[key] = item
    }

    private fun matchesFilter(item: SearchResult, filter: String): Boolean = when (filter.uppercase(Locale.US)) {
        "FX" -> item.type.contains("CURRENCY", true) || item.type.contains("FOREX", true) || item.symbol.endsWith("=X")
        "STOCK" -> item.type.uppercase(Locale.US) in setOf("STOCK", "FOREIGN_STOCK", "DEPOSITARY_RECEIPTS")
        else -> item.type.uppercase(Locale.US) in setOf("STOCK", "FOREIGN_STOCK", "DEPOSITARY_RECEIPTS", "CURRENCY")
    }

    companion object {
        private val aliases = mapOf(
            "SBER" to listOf("сбер", "сбербанк", "sberbank", "sber"),
            "GAZP" to listOf("газпром", "gazprom"),
            "LKOH" to listOf("лукойл", "лукоил", "lukoil"),
            "ROSN" to listOf("роснефть", "rosneft"),
            "NVTK" to listOf("новатэк", "новатек", "novatek"),
            "TATN" to listOf("татнефть", "tatneft"),
            "MGNT" to listOf("магнит", "magnit"),
            "MOEX" to listOf("мосбиржа", "московская биржа", "moscow exchange"),
            "YDEX" to listOf("яндекс", "yandex"),
            "OZON" to listOf("озон", "ozon"),
            "CNRU" to listOf("циан", "cian"),
            "AFLT" to listOf("аэрофлот", "aeroflot"),
            "VTBR" to listOf("втб", "втб банк", "vtb"),
            "MTSS" to listOf("мтс", "mts"),
            "GMKN" to listOf("норникель", "норильский никель", "nornickel"),
            "PLZL" to listOf("полюс", "polyus"),
            "PHOR" to listOf("фосагро", "phosagro"),
            "RTKM" to listOf("ростелеком", "rostelecom"),
            "ALRS" to listOf("алроса", "alrosa"),
            "FLOT" to listOf("совкомфлот", "sovcomflot"),
            "IRAO" to listOf("интер рао", "inter rao"),
            "ENPG" to listOf("эн+", "эн плюс", "enplus"),
            "TCSG" to listOf("тинькофф", "т-банк", "т банк", "t-bank", "tbank"),
            "HEAD" to listOf("хедхантер", "headhunter", "hh"),
            "VKCO" to listOf("вк", "вконтакте", "vk"),
            "RUAL" to listOf("русал", "rusal"),
            "SNGS" to listOf("сургутнефтегаз", "сургут", "surgutneftegas"),
            "SNGSP" to listOf("сургутнефтегаз преф", "сургут преф"),
            "CHMF" to listOf("северсталь", "severstal"),
            "NLMK" to listOf("нлмк", "новолипецкий металлургический", "nlmk"),
            "MAGN" to listOf("ммк", "магнитогорский металлургический", "magnitogorsk"),
            "PIKK" to listOf("пик", "пик группа", "pik"),
            "MRKP" to listOf("россети центр", "россети"),
            "TATNP" to listOf("татнефть преф", "татнефть привилегированные"),
            "AQUA" to listOf("инарктив", "аква", "inark"),
            "ASTR" to listOf("астра", "астра групп", "astra"),
            "SOFL" to listOf("софтлайн", "softline"),
            "BELU" to listOf("белуга", "beluga"),
            "MVID" to listOf("мвидео", "m.video"),
            "AFKS" to listOf("система", "ао система", "afk sistema"),
            "HYDR" to listOf("русгидро", "rushydro"),
            "FEES" to listOf("россети", "rosseti"),
            "RTS" to listOf("ртс"),
            "USD000UTSTOM" to listOf("доллар", "доллар рубль", "доллар/рубль", "usd rub", "usd/rub", "usdrub"),
            "EUR_RUB__TOM" to listOf("евро", "евро рубль", "евро/рубль", "eur rub", "eur/rub", "eurrub"),
            "CNYRUB_TOM" to listOf("юань", "юань рубль", "юань/рубль", "cny rub", "cny/rub", "cnyrub"),
            "GBP_RUB__TOM" to listOf("фунт", "фунт рубль", "gbp/rub", "gbprub"),
            "JPY_RUB__TOM" to listOf("иена", "иена рубль", "jpy/rub", "jpyrub")
        )
        private fun aliasesFor(symbol: String): List<String> = aliases[symbol.uppercase(Locale.US)].orEmpty()

        fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("\\s+"), " ")
        fun transliterate(value: String): String {
            val map = mapOf("\u0430" to "a", "\u0431" to "b", "\u0432" to "v", "\u0433" to "g", "\u0434" to "d", "\u0435" to "e", "\u0451" to "e", "\u0436" to "zh", "\u0437" to "z", "\u0438" to "i", "\u0439" to "y", "\u043a" to "k", "\u043b" to "l", "\u043c" to "m", "\u043d" to "n", "\u043e" to "o", "\u043f" to "p", "\u0440" to "r", "\u0441" to "s", "\u0442" to "t", "\u0443" to "u", "\u0444" to "f", "\u0445" to "h", "\u0446" to "c", "\u0447" to "ch", "\u0448" to "sh", "\u0449" to "sch", "\u044a" to "", "\u044b" to "y", "\u044c" to "", "\u044d" to "e", "\u044e" to "yu", "\u044f" to "ya")
            return buildString { normalize(value).forEach { append(map[it.toString()] ?: it) } }
        }
    }
}
