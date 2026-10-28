package com.marketforecast.prox

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs

/**
 * The trade as it is meant to be played: stop, three targets with their distance, size in R and chance of being reached,
 * and the honest expected result of the whole plan. Nothing here is a guarantee, and the card says so.
 */
@Composable
internal fun TradePlanCard(f: Forecast, ru: Boolean) {
    if (f.signal != "LONG" && f.signal != "SHORT") return
    val risk = abs(f.entry - f.stop).coerceAtLeast(1e-9)
    fun pct(level: Double): Double = if (f.entry > 0.0) (level - f.entry) / f.entry * 100.0 else 0.0
    GradientCard(Modifier.fillMaxWidth()) {
        SectionHeader(
            if (ru) "План сделки" else "Trade plan",
            if (ru) "Стоп и цели подобраны по тому, что инструмент отрабатывал в похожих ситуациях" else "Stop and targets are chosen from what the instrument did in similar situations"
        )
        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Text("", Modifier.weight(1.1f))
            Text(if (ru) "Цена" else "Price", Modifier.weight(1.3f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (ru) "Путь" else "Move", Modifier.weight(1.1f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("R", Modifier.weight(0.7f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (ru) "Шанс" else "Odds", Modifier.weight(0.8f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PlanRow(if (ru) "Стоп" else "Stop", f.stop, pct(f.stop), -1.0, f.stopProbability, Negative)
        PlanRow("TP1", f.tp1, pct(f.tp1), abs(f.tp1 - f.entry) / risk, f.tp1Probability, Positive)
        PlanRow("TP2", f.tp2, pct(f.tp2), abs(f.tp2 - f.entry) / risk, f.tp2Probability, Positive)
        PlanRow("TP3", f.tp3, pct(f.tp3), abs(f.tp3 - f.entry) / risk, f.tp3Probability, Positive)
        val ev = f.expectedValueR
        Text(
            (if (ru) "Ожидание по всему плану: " else "Expectation of the whole plan: ") + String.format(Locale.US, "%+.2f R", ev) +
                (if (ru) " на сделку после издержек" else " per trade after costs"),
            fontSize = 13.sp, fontWeight = FontWeight.Black, color = if (ev > 0.0) Positive else Warning, modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            if (ru) "Как играть: треть позиции закрывается на каждой цели; после TP1 стоп переносится в безубыток, после TP2 на TP1. Если тренд продолжается, стоп можно подтягивать за Supertrend."
            else "How to play it: a third of the position leaves at each target; after TP1 the stop moves to break-even, after TP2 to TP1. If the trend carries on, the stop can follow Supertrend.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            if (ru) "Это статистическая оценка, а не гарантия: любой прогноз может не сработать. Шансы посчитаны по истории и меняются с рынком."
            else "This is a statistical estimate, not a guarantee: any forecast can fail. The odds come from history and move with the market.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun PlanRow(label: String, level: Double, move: Double, r: Double, probability: Double, color: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1.1f), fontSize = 13.sp, fontWeight = FontWeight.Black, color = color)
        Text(fmt(level), Modifier.weight(1.3f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(String.format(Locale.US, "%+.2f%%", move), Modifier.weight(1.1f), fontSize = 12.sp, color = color)
        Text(if (r < 0.0) "−1" else String.format(Locale.US, "%.1f", r), Modifier.weight(0.7f), fontSize = 12.sp)
        Text(String.format(Locale.US, "%.0f%%", probability * 100.0), Modifier.weight(0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Shown instead of a plan when the model has no entry signal; says what it does see and why it is not a trade. */
@Composable
internal fun LeanCard(f: Forecast, ru: Boolean) {
    GradientCard(Modifier.fillMaxWidth()) {
        SectionHeader(if (ru) "Сейчас нет сигнала на вход" else "No entry signal right now", "")
        if (f.lean.isNotEmpty()) {
            val tone = if (f.lean == "LONG") Positive else Negative
            Text(
                (if (ru) "Лёгкий перекос: " else "A lean towards: ") + f.lean,
                color = tone, fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                if (ru) "Направление просматривается, но ожидание плана ${String.format(Locale.US, "%+.2f", f.expectedValueR)}R после издержек ниже порога +0,03R, либо рынок в сжатии. Это ориентир, а не сигнал: уровни ниже показаны для справки."
                else "A direction is visible, but the plan's expectation of ${String.format(Locale.US, "%+.2f", f.expectedValueR)}R after costs is below the +0.03R bar, or the market is squeezing. A view, not a signal: the levels below are for reference.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            Text(
                if (ru) "Подтверждённого направления нет: сигналы расходятся или рынок без режима. Лучше подождать."
                else "There is no confirmed direction: the signals disagree or the market has no regime. Better to wait.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** Placebo test: does the plan beat a random walk with the same volatility and the same targets? */
@Composable
internal fun EdgeCard(f: Forecast, ru: Boolean) {
    if (f.sampleSize < ExitPlanner.MIN_SAMPLES) return
    val z = f.edgeZ
    val tone = if (z >= 1.5) Positive else if (z >= 0.5) Warning else Negative
    GradientCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ru) "Проверка на случайность" else "Randomness check", fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("z = " + String.format(Locale.US, "%.1f", z), fontWeight = FontWeight.Black, fontSize = 18.sp, color = tone)
        }
        Text(
            when {
                z >= 1.5 -> if (ru) "План на истории заметно лучше случайного блуждания с теми же целями и волатильностью: преимущество не похоже на шум."
                else "On history the plan is clearly better than a random walk with the same targets and volatility: the edge does not look like noise."
                z >= 0.5 -> if (ru) "Преимущество над случайным блужданием слабое: возможно, это просто шум."
                else "The edge over a random walk is weak: it may just be noise."
                else -> if (ru) "На истории план не лучше случайного блуждания: сигнал может оказаться случайным."
                else "On history the plan is no better than a random walk: the signal may be random."
            },
            fontSize = 12.sp, color = tone, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            (if (ru) "План на истории: " else "Plan on history: ") + String.format(Locale.US, "%+.2fR", f.planEv) +
                (if (ru) " • случайное блуждание при тех же целях: " else " • random walk with the same targets: ") + String.format(Locale.US, "%+.2fR", f.nullEv),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
        )
    }
}
