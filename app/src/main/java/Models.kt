package com.marketforecast.prox

data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)
data class SearchResult(val symbol: String, val name: String, val exchange: String, val type: String, val source: String = "", val classCode: String = "")
data class DividendEvent(val symbol: String, val date: Long, val amount: Double, val source: String = "БКС Экспресс")
enum class NewsCategory { ALL, STOCKS, FX }
data class NewsItem(val title: String, val publisher: String, val url: String, val publishedAt: Long, val originalTitle: String = title, val category: NewsCategory = NewsCategory.ALL, val body: String = "", val instrument: String = "")
data class InstrumentMeta(val symbol: String, val shortName: String = symbol, val currency: String = "RUB", val lotSize: Int = 1, val unit: String = "price")
data class HistoryEntry(
    val time: Long,
    val symbol: String,
    val signal: String,
    val confidence: Int,
    val price: Double,
    val tp2: Double,
    val result: String = "",
    val timeframe: String = "1D",
    val directionOk: Boolean? = null,
    val closingPrice: Double = 0.0,
    val hitSummary: String = ""
)
data class TrackedForecast(
    val id: String,
    val symbol: String,
    val signal: String,
    val confidence: Int,
    val entry: Double,
    val timeframe: String,
    val createdAt: Long,
    val checkAt: Long,
    val result: String = "PENDING",
    val checkedPrice: Double = 0.0,
    val stop: Double = 0.0,
    val tp1: Double = 0.0,
    val tp2: Double = 0.0,
    val tp3: Double = 0.0,
    val lastLivePrice: Double = 0.0,
    val lastUpdated: Long = 0L,
    val tp1Hit: Boolean = false,
    val tp2Hit: Boolean = false,
    val tp3Hit: Boolean = false,
    val slHit: Boolean = false
)
data class MarketIndex(val symbol: String, val name: String, val price: Double, val changePct: Double, val source: String)