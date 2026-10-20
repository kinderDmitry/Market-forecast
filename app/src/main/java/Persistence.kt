package com.marketforecast.prox

import android.content.SharedPreferences

/**
 * Helpers for state that is written both by the foreground UI loop and by [MarketMonitorWorker].
 *
 * Both of them read the stored list, spend several seconds on network requests, and then write the
 * whole list back. Without merging, a forecast added (or removed) by the user in the meantime was
 * silently overwritten. These helpers re-apply only the changes a long-running evaluation really
 * made on top of the latest stored state.
 */
internal object TrackingMerge {
    /**
     * @param fresh    the list as stored right now
     * @param original the list the evaluation started from
     * @param updated  the list after the evaluation
     */
    fun mergeTracked(
        fresh: List<TrackedForecast>,
        original: List<TrackedForecast>,
        updated: List<TrackedForecast>
    ): List<TrackedForecast> {
        val originalById = original.associateBy { it.id }
        val updatedById = updated.associateBy { it.id }
        return fresh.map { stored ->
            val before = originalById[stored.id]
            val after = updatedById[stored.id]
            if (before != null && after != null && after != before) after else stored
        }
    }

    private fun historyKey(h: HistoryEntry): String = h.symbol + "|" + h.time

    fun mergeHistory(
        fresh: List<HistoryEntry>,
        original: List<HistoryEntry>,
        updated: List<HistoryEntry>
    ): List<HistoryEntry> {
        val originalByKey = original.associateBy { historyKey(it) }
        val out = fresh.toMutableList()
        for (entry in updated) {
            val key = historyKey(entry)
            if (originalByKey[key] == entry) continue
            val index = out.indexOfFirst { historyKey(it) == key }
            if (index >= 0) out[index] = entry else out.add(0, entry)
        }
        return out
    }
}

/**
 * Remembers the most recent N keys IN INSERTION ORDER.
 *
 * The previous implementation stored a Set<String> and trimmed it with takeLast(400); a stored
 * StringSet has no order, so the "oldest" keys that were dropped were effectively random and
 * could make an already-sent news item or alert fire again.
 */
internal object OrderedKeyStore {
    fun load(prefs: SharedPreferences, key: String): MutableSet<String> {
        val out = LinkedHashSet<String>()
        out.addAll(prefs.getStringSet(key, emptySet()).orEmpty()) // legacy, unordered
        prefs.getString(key + "_v2", "").orEmpty().split('\n').filterTo(out) { it.isNotBlank() }
        return out
    }

    fun save(prefs: SharedPreferences, key: String, values: Collection<String>, max: Int = 400) {
        prefs.edit()
            .putString(key + "_v2", values.toList().takeLast(max).joinToString("\n"))
            .remove(key)
            .apply()
    }
}
