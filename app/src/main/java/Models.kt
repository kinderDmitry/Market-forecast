package com.marketforecast.prox

data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)
data class SearchResult(val symbol: String, val name: String, val exchange: String, val type: String, val source: String = "")
data class ScanRow(val result: SearchResult, val timeframe: String, val signal: String, val confidence: Int, val score: Double, val rr: Double)
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
data class MarketPick(val symbol: String, val name: String, val price: Double, val signal: String, val confidence: Int, val score: Double, val type: String, val changePct: Double = 0.0, val volume: Double = 0.0)
data class Forecast(
    val signal: String,
    val score: Double,
    val confidence: Int,
    val trend: Double,
    val momentum: Double,
    val volatility: Double,
    val levels: Double,
    val volume: Double,
    val bull: Int,
    val base: Int,
    val bear: Int,
    val entry: Double,
    val stop: Double,
    val stopAggressive: Double,
    val stopOptimal: Double,
    val stopConservative: Double,
    val tp1: Double,
    val tp2: Double,
    val tp3: Double,
    val rr: Double,
    val projected: Double,
    val support1: Double,
    val support2: Double,
    val resistance1: Double,
    val resistance2: Double,
    val explanation: List<String>,
    val dataQuality: Int = 95,
    val regime: String = "UNKNOWN",
    val expectedProfitPct: Double = 0.0,
    val expectedLossPct: Double = 0.0,
    val historicalEdge: Double = 0.5,
    val edgeGap: Double = 0.0,
    val confirmation: Int = 0,
    val advancedTechnical: Double = 0.0,
    val highConviction: Boolean = false,
    val patternScore: Double = 0.0,
    val detectedPatterns: List<String> = emptyList()
)
data class MarketState(
    val symbol: String,
    val candles: List<Candle> = emptyList(),
    val forecast: Forecast? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val updated: Long = 0L,
    val timeframe: String = "1D",
    val news: List<NewsItem> = emptyList(),
    val livePrice: Double = 0.0,
    val meta: InstrumentMeta = InstrumentMeta(symbol)
)
