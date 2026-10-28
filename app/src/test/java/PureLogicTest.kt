package com.marketforecast.prox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/** Pure logic only (no Android, no network): the pieces whose maths must not drift silently. */
class PureLogicTest {
    private fun candle(open: Double, close: Double, volume: Double = 1000.0) =
        Candle(0L, open, max(open, close) * 1.001, min(open, close) * 0.999, close, volume)

    private fun decline(n: Int = 40): MutableList<Candle> {
        val out = ArrayList<Candle>()
        var price = 100.0
        repeat(n) {
            val next = price * 0.996
            out.add(candle(price, next))
            price = next
        }
        return out
    }

    @Test
    fun treemapKeepsAreaProportionAndStaysInsideTheFrame() {
        val weights = listOf(900.0, 400.0, 250.0, 120.0, 60.0, 30.0, 10.0, 5.0, 1.0)
        val tiles = weights.indices.map { HeatTile("T$it", "Name $it", 100.0, 0.0, 0.0, 0.0) }
        val rects = Treemap.layout(tiles, { weights[it.symbol.removePrefix("T").toInt()] }, 360f, 440f)
        assertEquals(weights.size, rects.size)
        val frame = 360.0 * 440.0
        assertEquals(frame, rects.sumOf { (it.w * it.h).toDouble() }, frame * 1e-3)
        val total = weights.sum()
        for (r in rects) {
            assertTrue(r.x >= -0.01f && r.y >= -0.01f && r.x + r.w <= 360.01f && r.y + r.h <= 440.01f)
            val expected = weights[r.tile.symbol.removePrefix("T").toInt()] / total * frame
            assertEquals(expected, (r.w * r.h).toDouble(), frame * 1e-3)
        }
    }

    @Test
    fun confluenceRoundTripAndSummary() {
        val dirs = mapOf("15M" to 1, "1H" to 1, "4H" to 0, "1D" to -1, "1W" to null)
        val decoded = Confluence.decode(Confluence.encode(dirs))
        assertEquals(dirs, decoded)
        val s = Confluence.summarize(1, decoded)
        assertEquals(2, s.agree)
        assertEquals(1, s.against)
        assertEquals(1, s.neutral)
        assertEquals(1, s.unknown)
    }

    @Test
    fun currencyPairsAreReadable() {
        assertEquals("USD/RUB", fxPairLabel("USD000UTSTOM"))
        assertEquals("CNY/RUB", fxPairLabel("CNYRUB_TOM"))
        assertEquals("EUR/RUB", fxPairLabel("EUR_RUB__TOM"))
        assertNull(fxPairLabel("SBER"))
    }

    @Test
    fun bullishEngulfingAfterADeclineIsDetected() {
        val c = decline()
        val last = c.last().close
        val open = last * 0.999
        c.add(candle(open, open * 1.03, 3000.0))
        val signal = TechnicalSignals.candlePattern(c)
        assertNotNull(signal)
        assertTrue(signal!!.value > 0.0)
        assertTrue(signal.name.contains("поглощение"))
    }

    @Test
    fun noPatternWithoutAPriorMove() {
        val flat = (0 until 40).map { candle(100.0, 100.0 + (it % 2) * 0.01) }
        assertNull(TechnicalSignals.candlePattern(flat))
    }

    @Test
    fun moneyFlowFollowsTheCloseWithinTheRange() {
        val strong = (0 until 25).map { Candle(0L, 10.0, 11.0, 9.0, 10.9, 100.0) }
        val weak = (0 until 25).map { Candle(0L, 10.0, 11.0, 9.0, 9.1, 100.0) }
        assertTrue(TechnicalSignals.moneyFlow(strong) > 0.5)
        assertTrue(TechnicalSignals.moneyFlow(weak) < -0.5)
        assertEquals(0.0, TechnicalSignals.moneyFlow(strong.map { it.copy(volume = 0.0) }), 1e-9)
    }

    private fun bar(open: Double, high: Double, low: Double, close: Double) = Candle(0L, open, high, low, close, 1000.0)

    @Test
    fun exitPlanStopWinsATieInsideOneCandle() {
        val result = ExitPlanner.simulateLevels(listOf(bar(100.0, 101.2, 98.9, 100.5)), 1, 100.0, 99.0, 101.0, 102.0, 102.8, 0.0)
        assertEquals(-1.0, result.r, 1e-9)
        assertTrue(result.stoppedFirst)
    }

    @Test
    fun exitPlanScalesOutAndMovesToBreakeven() {
        // TP1 is reached, then price falls back to the entry: one third banked (1R / 3), the rest leaves at break-even.
        val bars = listOf(bar(100.0, 101.5, 99.6, 101.2), bar(101.2, 101.3, 99.9, 100.1))
        val result = ExitPlanner.simulateLevels(bars, 1, 100.0, 99.0, 101.0, 102.0, 102.8, 0.0)
        assertEquals(1.0 / 3.0, result.r, 1e-9)
        assertTrue(result.reached1)
    }

    @Test
    fun exitPlanPaysAllThreeTargets() {
        val bars = listOf(bar(100.0, 101.1, 99.8, 101.0), bar(101.0, 102.1, 100.9, 102.0), bar(102.0, 102.9, 101.9, 102.8))
        val result = ExitPlanner.simulateLevels(bars, 1, 100.0, 99.0, 101.0, 102.0, 102.8, 0.0)
        assertEquals((1.0 + 2.0 + 2.8) / 3.0, result.r, 1e-9)
        assertTrue(result.reached3)
    }

    @Test
    fun exitPlanWorksForShortsAndPaysCosts() {
        val bars = listOf(bar(100.0, 100.2, 98.9, 99.0))
        val free = ExitPlanner.simulateLevels(bars, -1, 100.0, 101.0, 99.0, 98.0, 97.2, 0.0)
        val costly = ExitPlanner.simulateLevels(bars, -1, 100.0, 101.0, 99.0, 98.0, 97.2, 0.001)
        assertTrue(free.reached1)
        assertTrue(costly.r < free.r)
    }

    @Test
    fun expectedValueMatchesTheScaleOutRules() {
        // Everything reaches TP3: (1 + 2 + 2.8) / 3 = 1.933R; everything stops: -1R.
        assertEquals(5.8 / 3.0, ExitPlanner.expectedValue(1.0, 1.0, 1.0, 0.0, 1.0, 2.0, 2.8, 0.0), 1e-9)
        assertEquals(-1.0, ExitPlanner.expectedValue(0.0, 0.0, 0.0, 1.0, 1.0, 2.0, 2.8, 0.0), 1e-9)
    }

    @Test
    fun costContextIsScopedToTheAnalysis() {
        assertEquals(ExitPlanner.COST_FRACTION, ExitPlanner.currentCost(), 0.0)
        ExitPlanner.withCost(0.01) { assertEquals(0.01, ExitPlanner.currentCost(), 0.0) }
        assertEquals(ExitPlanner.COST_FRACTION, ExitPlanner.currentCost(), 0.0)
    }

    @Test
    fun randomWalkBaselineIsStableAndDoesNotReward() {
        val random = java.util.Random(7)
        val c = ArrayList<Candle>()
        var p = 100.0
        repeat(320) {
            val n = p * (1.0 + random.nextGaussian() * 0.006)
            c.add(Candle(0L, p, max(p, n) * 1.002, min(p, n) * 0.998, n, 1000.0))
            p = n
        }
        val atr = c.takeLast(14).map { it.high - it.low }.average()
        val a = ExitPlanner.nullPlan(c, 1, 1.25, 1.0, atr, 8)
        val b = ExitPlanner.nullPlan(c, 1, 1.25, 1.0, atr, 8)
        assertEquals(a.ev, b.ev, 0.0)
        assertTrue(a.ev < 0.1)
        assertTrue(a.p1 >= a.p2 && a.p2 >= a.p3)
    }

    @Test
    fun supertrendFollowsTheTrend() {
        val up = ArrayList<Candle>()
        var p = 100.0
        repeat(80) { val n = p * 1.004; up.add(candle(p, n)); p = n }
        val down = ArrayList<Candle>()
        p = 100.0
        repeat(80) { val n = p * 0.996; down.add(candle(p, n)); p = n }
        assertEquals(1, TechnicalSignals.supertrend(up).first)
        assertEquals(-1, TechnicalSignals.supertrend(down).first)
    }
}
