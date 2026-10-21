package com.marketforecast.prox

import java.util.Locale

/**
 * In-memory index over the COMPLETE BCS instrument catalogue (stocks, foreign stocks,
 * depositary receipts, currencies).
 *
 * There are no hand-written alias tables or "popular" seed lists: every instrument that BCS
 * returns is searchable. Forgiving matching is produced algorithmically instead:
 *  - Russian <-> Latin transliteration ("сбер" finds SBER, "gazprom" finds "Газпром"),
 *  - wrong keyboard layout recovery ("ыиук" -> "sber", "cgth" -> "спер"),
 *  - word-start matching ("нефть" finds "Газпром нефть"),
 *  - phonetic consonant skeleton ("yandex" finds "Яндекс", "sberbnk" finds "Сбербанк"),
 *  - one-character typo tolerance.
 *
 * Every searchable representation is pre-computed when an instrument is added, so a query
 * is a single pass over ready strings: typing is answered immediately even for ~10k entries.
 */
class InstrumentSearchEngine(seed: List<SearchResult> = emptyList()) {

    private class Entry(
        val item: SearchResult,
        val sym: String,
        val symCompact: String,
        val name: String,
        val nameCompact: String,
        val nameLat: String,
        val nameLatCompact: String,
        val tokens: List<String>,
        val tokensLat: List<String>,
        val skeleton: String,
        val tokenSkeletons: List<String>,
        val rank: Int
    )

    private class QueryForm(val text: String, val compact: String, val penalty: Int)

    private val lock = Any()
    private val byKey = LinkedHashMap<String, Entry>()
    @Volatile private var snapshot: List<Entry> = emptyList()

    init { if (seed.isNotEmpty()) addAll(seed) }

    fun size(): Int = snapshot.size

    fun all(): List<SearchResult> = snapshot.map { it.item }

    fun find(symbol: String): SearchResult? {
        val key = symbol.trim().uppercase(Locale.US)
        return synchronized(lock) { byKey[key]?.item }
    }

    /** Adds or updates instruments (one entry per ticker; the most liquid board wins). */
    fun addAll(values: Collection<SearchResult>) {
        if (values.isEmpty()) return
        synchronized(lock) {
            for (item in values) {
                if (item.symbol.isBlank()) continue
                val entry = buildEntry(item)
                val key = item.symbol.trim().uppercase(Locale.US)
                val old = byKey[key]
                if (old == null || entry.rank <= old.rank) byKey[key] = entry
            }
            snapshot = ArrayList(byKey.values)
        }
    }

    /** Replaces the whole index (used after a fully successful catalogue sync). */
    fun replaceAll(values: Collection<SearchResult>) {
        synchronized(lock) {
            byKey.clear()
            snapshot = emptyList()
        }
        addAll(values)
    }

    fun replaceLearned(values: Collection<SearchResult>) = addAll(values)

    fun search(query: String, filter: String = "ALL", limit: Int = 30): List<SearchResult> {
        val q = normalize(query)
        if (q.isBlank()) return emptyList()
        val forms = buildForms(q)
        val phonetic = forms.map { skeleton(phon(transliterate(it.text))) }.filter { it.length >= 3 }.distinct()
        val pool = snapshot
        val scored = ArrayList<Pair<Int, Entry>>()
        for (e in pool) {
            if (!typeMatches(e.item.type, filter)) continue
            var best = 99
            for (f in forms) {
                val s = scoreForm(e, f)
                if (s < best) best = s
                if (best == 0) break
            }
            if (best >= 99 && phonetic.isNotEmpty()) {
                for (pk in phonetic) {
                    if (e.skeleton.startsWith(pk) || e.tokenSkeletons.any { it.startsWith(pk) }) { best = 8; break }
                }
            }
            if (best >= 99) {
                for (f in forms) {
                    if (fuzzyHit(e, f.text)) { best = 9 + f.penalty; break }
                }
            }
            if (best < 99) scored.add(best to e)
        }
        scored.sortWith(
            compareBy<Pair<Int, Entry>> { it.first }
                .thenBy { it.second.rank }
                .thenBy { it.second.item.name.length }
                .thenBy { it.second.item.symbol }
        )
        return scored.asSequence().map { it.second.item }.take(limit).toList()
    }

    // ---------------------------------------------------------------- scoring

    private fun scoreForm(e: Entry, f: QueryForm): Int {
        val t = f.text
        val c = f.compact
        val base = when {
            e.sym == t -> 0
            e.name == t || e.nameLat == t -> 1
            e.sym.startsWith(t) -> 2
            e.name.startsWith(t) || e.nameLat.startsWith(t) -> 3
            e.tokens.any { it.startsWith(t) } || e.tokensLat.any { it.startsWith(t) } -> 4
            c.length >= 2 && (e.symCompact.startsWith(c) || e.nameCompact.startsWith(c) || e.nameLatCompact.startsWith(c)) -> 5
            t.length >= 2 && (e.sym.contains(t) || e.name.contains(t) || e.nameLat.contains(t)) -> 6
            c.length >= 3 && (e.nameCompact.contains(c) || e.nameLatCompact.contains(c) || e.symCompact.contains(c)) -> 7
            else -> 99
        }
        return if (base >= 99) 99 else base + f.penalty
    }

    private fun fuzzyHit(e: Entry, text: String): Boolean {
        val t = text.replace(" ", "")
        if (t.length < 4 || t.length > 20) return false
        if (withinOneEdit(t, e.symCompact.take(t.length + 1))) return true
        for (tok in e.tokens) if (tok.length >= 4 && withinOneEdit(t, tok.take(t.length + 1))) return true
        for (tok in e.tokensLat) if (tok.length >= 4 && withinOneEdit(t, tok.take(t.length + 1))) return true
        return false
    }

    /** True when a and b differ by at most one insertion, deletion, substitution or adjacent swap. */
    private fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > 1) return false
        var i = 0
        while (i < la && i < lb && a[i] == b[i]) i++
        if (i == la || i == lb) return true // one string is a prefix of the other (length differs by <= 1)
        if (la == lb) {
            if (a.substring(i + 1) == b.substring(i + 1)) return true
            if (i + 1 < la && a[i] == b[i + 1] && a[i + 1] == b[i] && a.substring(i + 2) == b.substring(i + 2)) return true
            return false
        }
        return if (la > lb) a.substring(i + 1) == b.substring(i) else a.substring(i) == b.substring(i + 1)
    }

    private fun buildForms(q: String): List<QueryForm> {
        val out = ArrayList<QueryForm>(4)
        val seen = HashSet<String>()
        fun add(text: String, penalty: Int) {
            val t = normalize(text)
            if (t.isBlank() || !seen.add(t)) return
            out.add(QueryForm(t, compact(t), penalty))
        }
        add(q, 0)
        add(transliterate(q), 0)
        val swapped = swapLayout(q)
        if (swapped != q) {
            add(swapped, 1)
            add(transliterate(swapped), 1)
        }
        return out
    }

    private fun typeMatches(type: String, filter: String): Boolean {
        val t = type.uppercase(Locale.US)
        val isFx = t.contains("CURRENCY") || t.contains("FOREX")
        return when (filter.uppercase(Locale.US)) {
            "FX", "CURRENCY" -> isFx
            "STOCK", "STOCKS" -> t == "STOCK" || t == "FOREIGN_STOCK" || t == "DEPOSITARY_RECEIPTS"
            else -> isFx || t == "STOCK" || t == "FOREIGN_STOCK" || t == "DEPOSITARY_RECEIPTS"
        }
    }

    // ---------------------------------------------------------------- indexing

    private fun buildEntry(item: SearchResult): Entry {
        val sym = normalize(item.symbol)
        val name = normalize(item.name)
        val nameLat = transliterate(name)
        val tokens = tokenize(name)
        val tokensLat = tokenize(nameLat)
        val skeletonSource = phon(nameLat)
        return Entry(
            item = item,
            sym = sym,
            symCompact = compact(sym),
            name = name,
            nameCompact = compact(name),
            nameLat = nameLat,
            nameLatCompact = compact(nameLat),
            tokens = tokens,
            tokensLat = tokensLat,
            skeleton = skeleton(skeletonSource),
            tokenSkeletons = tokensLat.map { skeleton(phon(it)) }.filter { it.length >= 3 },
            rank = rankOf(item)
        )
    }

    private fun rankOf(item: SearchResult): Int {
        val type = item.type.uppercase(Locale.US)
        val typeRank = when {
            type == "STOCK" -> 0
            type == "DEPOSITARY_RECEIPTS" -> 1
            type == "FOREIGN_STOCK" -> 2
            else -> 3
        }
        val boardRank = when (item.classCode.uppercase(Locale.US)) {
            "TQBR", "CETS" -> 0
            "TQTF", "TQIF", "TQPI" -> 1
            else -> 2
        }
        return typeRank * 3 + boardRank
    }

    private fun tokenize(value: String): List<String> =
        value.split(NON_ALNUM).filter { it.isNotEmpty() }

    companion object {
        private val NON_ALNUM = Regex("[^\\p{L}\\p{Nd}]+")
        private val SPACES = Regex("\\s+")

        private val RU_TO_LAT: Map<Char, String> = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh",
            'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
            'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f",
            'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y",
            'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
        )

        private const val EN_KEYS = "qwertyuiop[]asdfghjkl;'zxcvbnm,./"
        private const val RU_KEYS = "йцукенгшщзхъфывапролджэячсмитьбю."

        private val EN_TO_RU: Map<Char, Char> = EN_KEYS.indices.associate { EN_KEYS[it] to RU_KEYS[it] }
        private val RU_TO_EN: Map<Char, Char> = RU_KEYS.indices.associate { RU_KEYS[it] to EN_KEYS[it] }

        fun normalize(value: String): String =
            value.trim().lowercase(Locale.ROOT).replace('ё', 'е').replace(SPACES, " ")

        fun compact(value: String): String = value.replace(NON_ALNUM, "")

        /** Russian -> Latin; characters that are not Cyrillic are kept as they are. */
        fun transliterate(value: String): String {
            val src = normalize(value)
            val sb = StringBuilder(src.length + 8)
            for (ch in src) {
                val mapped = RU_TO_LAT[ch]
                if (mapped != null) sb.append(mapped) else sb.append(ch)
            }
            return sb.toString()
        }

        /** Re-types the text as if the keyboard layout (EN <-> RU) had been wrong. */
        fun swapLayout(value: String): String {
            val src = normalize(value)
            if (src.isEmpty()) return src
            val hasCyrillic = src.any { it in RU_TO_EN }
            val sb = StringBuilder(src.length)
            for (ch in src) {
                val mapped = if (hasCyrillic) RU_TO_EN[ch] else EN_TO_RU[ch]
                sb.append(mapped ?: ch)
            }
            return sb.toString()
        }

        /** Collapses Latin spelling variants so that "yandex" and "yandeks" produce the same key. */
        private fun phon(latin: String): String {
            var s = latin.lowercase(Locale.ROOT)
            s = s.replace("shch", "sh").replace("sch", "sh")
            s = s.replace("kh", "h").replace("ks", "x").replace("ts", "c").replace("ph", "f")
            s = s.replace("ck", "k").replace("q", "k").replace("w", "v")
            s = s.replace("yu", "u").replace("ya", "a").replace("yo", "o").replace("ye", "e")
            s = s.replace('y', 'i')
            return s.filter { it.isLetterOrDigit() }
        }

        /** Consonant skeleton: first character plus every non-vowel, repeated letters collapsed. */
        private fun skeleton(value: String): String {
            if (value.isEmpty()) return ""
            val sb = StringBuilder(value.length)
            sb.append(value[0])
            for (i in 1 until value.length) {
                val ch = value[i]
                if (ch in "aeiou") continue
                if (sb.last() == ch) continue
                sb.append(ch)
            }
            return sb.toString()
        }
    }
}
