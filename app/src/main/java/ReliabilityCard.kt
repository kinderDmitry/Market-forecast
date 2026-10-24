package com.marketforecast.prox

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun gradeColor(grade: String): Color = when (grade) {
    "A" -> Positive
    "B" -> Color(0xFF4DA3FF)
    "C" -> Warning
    "D" -> Negative
    else -> Color(0xFF8FA7B8)
}

/**
 * "How sure can I be?" in one card: a 0-100 reliability score with a grade, a plain verdict, what supports the
 * forecast, what speaks against it, and the instrument's own walk-forward record with a safety margin.
 */
@Composable
internal fun ReliabilityCard(f: Forecast, candles: List<Candle>, ru: Boolean) {
    val key = candles.size to candles.lastOrNull()?.time
    var backtest by remember(key) { mutableStateOf<Pair<Int, Int>?>(null) }
    var checked by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        backtest = withContext(Dispatchers.Default) { runCatching { AnalyticsEngine.backtest(candles) }.getOrNull() }
        checked = true
    }
    val rel = remember(f, backtest, candles.size, ru) { ReliabilityEngine.assess(f, candles.size, backtest, ru) }
    val color = gradeColor(rel.grade)
    val progress by animateFloatAsState(rel.score / 100f, tween(900, easing = FastOutSlowInEasing), label = "reliability")

    GradientCard(Modifier.fillMaxWidth()) {
        SectionHeader(
            if (ru) "Надёжность прогноза" else "Forecast reliability",
            if (ru) "Сколько независимых подтверждений у сигнала и можно ли ему верить" else "How many independent confirmations back the signal and whether to trust it"
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(78.dp)) {
                    val stroke = 8.dp.toPx()
                    val inset = stroke / 2f
                    val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
                    drawArc(color.copy(alpha = 0.16f), -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(rel.grade, fontSize = 26.sp, fontWeight = FontWeight.Black, color = color)
                    Text("${rel.score}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                val (label, vColor) = when (rel.verdict) {
                    "GO" -> (if (ru) "Вход можно рассматривать" else "Entry can be considered") to Positive
                    "AVOID" -> (if (ru) "Лучше не входить" else "Better not to enter") to Negative
                    else -> (if (ru) "Ждать подтверждения" else "Wait for confirmation") to Warning
                }
                Text(
                    label, fontWeight = FontWeight.Black, fontSize = 15.sp, color = vColor,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(vColor.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 5.dp)
                )
                Text(
                    if (ru) "A — высокая, B — хорошая, C — средняя, D — низкая надёжность."
                    else "A high, B good, C medium, D low reliability.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        if (rel.btTotal >= 12) {
            Text(
                (if (ru) "Backtest по этому инструменту: " else "Walk-forward record: ") +
                    "${rel.btWins * 100 / rel.btTotal}% (${rel.btWins}/${rel.btTotal}), " +
                    (if (ru) "надёжная нижняя граница " else "reliable lower bound ") + String.format(Locale.US, "%.0f%%", rel.btLow * 100),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.padding(top = 12.dp)
            )
        } else if (!checked) {
            Text(if (ru) "Проверяю историю сигналов…" else "Checking the signal history…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
        if (rel.pros.isNotEmpty()) {
            Text(if (ru) "Что подтверждает" else "What supports it", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Positive, modifier = Modifier.padding(top = 12.dp))
            rel.pros.forEach { Text("✓  $it", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
        if (rel.cons.isNotEmpty()) {
            Text(if (ru) "Что настораживает" else "What gives pause", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Warning, modifier = Modifier.padding(top = 12.dp))
            rel.cons.forEach { Text("!  $it", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
        Spacer(Modifier.height(2.dp))
    }
}
