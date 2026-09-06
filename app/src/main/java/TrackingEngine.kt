package com.marketforecast.prox

import kotlin.math.abs

/** Deterministic tracking evaluation using only market candles/quotes. */
object TrackingEngine {
    data class Evaluation(
        val finalResult: String?,
        val price: Double,
        val events: List<String> = emptyList()
    )

    fun evaluate(item: TrackedForecast, candles: List<Candle>, livePrice: Double?, now: Long): Evaluation? {
        if (item.result != "PENDING") return null

        val live = livePrice?.takeIf { it.isFinite() && it > 0.0 }
        val ordered = candles.filter { it.time > 0L && it.time <= now }.sortedBy { it.time }
        val horizonExpired = now >= item.checkAt
        val longSide = item.signal.contains("LONG", ignoreCase = true)

        // Use only candles belonging to the monitoring interval for TP/SL events.
        // A provider can omit the exact first/last candle, so we do not require the
        // path to be non-empty before allowing a live quote to finalize the horizon.
        val path = ordered.filter { it.time >= item.createdAt && it.time <= item.checkAt }

        fun touched(level: Double, takeProfit: Boolean): Boolean {
            if (level <= 0.0) return false
            return path.any { candle ->
                if (takeProfit) {
                    if (longSide) candle.high >= level else candle.low <= level
                } else {
                    if (longSide) candle.low <= level else candle.high >= level
                }
            }
        }

        fun quoteTouched(level: Double, takeProfit: Boolean): Boolean {
            if (level <= 0.0 || live == null) return false
            return if (takeProfit) {
                if (longSide) live >= level else live <= level
            } else {
                if (longSide) live <= level else live >= level
            }
        }
        val tp1 = touched(item.tp1, true) || quoteTouched(item.tp1, true)
        val tp2 = touched(item.tp2, true) || quoteTouched(item.tp2, true)
        val tp3 = touched(item.tp3, true) || quoteTouched(item.tp3, true)
        val sl = touched(item.stop, false) || quoteTouched(item.stop, false)

        val events = buildList {
            if (tp1) add("SUCCESS_TP1")
            if (tp2) add("SUCCESS_TP2")
            if (tp3) add("SUCCESS_TP3")
            if (sl) add("STOP")
        }

        // If SL and TP3 are both touched in the same candle, OHLC alone cannot prove
        // the intrabar order. Resolve conservatively as STOP instead of inventing order.
        val terminal = when {
            sl && tp3 -> "STOP"
            sl -> "STOP"
            tp3 -> "SUCCESS_TP3"
            else -> null
        }

        // At the horizon, prefer a live quote because it is the closest available
        // market value to the actual completion moment. If no quote exists, use the
        // latest market candle that is not earlier than the monitoring start.
        val horizonCandle = ordered
            .filter { it.time >= item.createdAt && it.time <= item.checkAt }
            .maxByOrNull { it.time }
        val horizonPrice = horizonCandle?.close?.takeIf { it.isFinite() && it > 0.0 }
        val price = if (horizonExpired) live ?: horizonPrice else live ?: horizonPrice
        if (price == null || price <= 0.0) return null

        val flatTolerance = (item.entry * 0.0005).coerceAtLeast(1e-10) // 0.05%
        val unchanged = abs(price - item.entry) <= flatTolerance
        val finalResult = when {
            terminal == "STOP" -> "STOP"
            terminal == "SUCCESS_TP3" -> "SUCCESS_TP3"
            horizonExpired && unchanged -> "FLAT"
            horizonExpired -> if ((longSide && price > item.entry) || (!longSide && price < item.entry)) "DIRECTION_OK" else "FAIL"
            else -> null
        }

        return Evaluation(finalResult, price, events)
    }
}
