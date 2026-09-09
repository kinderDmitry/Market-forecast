package com.marketforecast.prox

import android.content.SharedPreferences
import java.util.Locale
import kotlin.math.abs

/** Display-currency layer. Market data remains in its native trading currency;
 * this layer only converts values shown to the user. Rates are real BCS FX quotes
 * cached in SharedPreferences and are never fabricated. */
object CurrencyDisplay {
    private val symbols = mapOf("RUB" to "₽", "USD" to "$", "EUR" to "€", "CNY" to "¥", "GBP" to "£", "JPY" to "¥")
    val supported = listOf("RUB", "USD", "EUR", "CNY", "GBP", "JPY")

    fun label(code: String): String = symbols[code.uppercase(Locale.US)] ?: code.uppercase(Locale.US)
    fun symbol(code: String): String = symbols[code.uppercase(Locale.US)] ?: code.uppercase(Locale.US)

    fun convert(value: Double, source: String, target: String, rates: Map<String, Double>): Double? {
        if (!value.isFinite()) return null
        val s = source.uppercase(Locale.US)
        val t = target.uppercase(Locale.US)
        if (s == t) return value
        // rates are RUB per one unit of foreign currency.
        fun rubPer(currency: String): Double? = when (currency) {
            "RUB" -> 1.0
            else -> rates[currency]?.takeIf { it.isFinite() && it > 0.0 }
        }
        val sr = rubPer(s) ?: return null
        val tr = rubPer(t) ?: return null
        return value * sr / tr
    }

    fun format(value: Double, source: String, prefs: SharedPreferences): String {
        val target = prefs.getString("display_currency", "RUB") ?: "RUB"
        val rates = rates(prefs)
        val converted = convert(value, source, target, rates)
        if (converted == null) return base(value, source, showCode = true)
        return base(converted, target, showCode = false)
    }

    fun pnl(entry: Double, current: Double, source: String, longSide: Boolean, prefs: SharedPreferences): String {
        if (!entry.isFinite() || !current.isFinite() || entry <= 0.0 || current <= 0.0) return "—"
        val delta = if (longSide) current - entry else entry - current
        val converted = convert(delta, source, prefs.getString("display_currency", "RUB") ?: "RUB", rates(prefs))
            ?: delta
        val sign = if (converted >= 0) "+" else "−"
        return "$sign${base(abs(converted), prefs.getString("display_currency", "RUB") ?: "RUB", false)}"
    }

    fun rates(prefs: SharedPreferences): Map<String, Double> = supported.associateWithNotNull { c ->
        if (c == "RUB") 1.0 else prefs.getString("fx_rate_$c", null)?.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
    }

    private fun base(v: Double, code: String, showCode: Boolean): String {
        val av = abs(v)
        val number = when { av >= 1000 -> String.format(Locale.US, "%,.2f", v); av >= 1 -> String.format(Locale.US, "%.2f", v); else -> String.format(Locale.US, "%.6f", v) }
        return if (showCode) "$number ${code.uppercase(Locale.US)}" else "${symbol(code)}$number"
    }

    private inline fun <K, V> Iterable<K>.associateWithNotNull(transform: (K) -> V?): Map<K, V> {
        val out = LinkedHashMap<K, V>()
        for (k in this) transform(k)?.let { out[k] = it }
        return out
    }
}
