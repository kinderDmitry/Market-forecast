package com.marketforecast.prox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Timeframe agreement: does the same instrument point the same way on 15M, 1H, 4H, 1D and 1W?
 * A signal confirmed on several horizons at once is the strongest filter against false entries.
 */
internal object Confluence {
    val FRAMES = listOf("15M", "1H", "4H", "1D", "1W")

    /** +1 long, -1 short, 0 no trade on that frame, null = no usable data for it. */
    fun direction(f: Forecast?): Int? = when (f?.signal) {
        null -> null
        "LONG" -> 1
        "SHORT" -> -1
        else -> 0
    }

    fun encode(dirs: Map<String, Int?>): String = FRAMES.joinToString(";") { frame -> frame + "=" + (dirs[frame]?.toString() ?: "?") }

    fun decode(text: String): Map<String, Int?> {
        if (text.isBlank()) return emptyMap()
        val out = HashMap<String, Int?>()
        for (part in text.split(';')) {
            val kv = part.split('=')
            if (kv.size == 2 && kv[0] in FRAMES) out[kv[0]] = kv[1].toIntOrNull()
        }
        return out
    }

    class Summary(val agree: Int, val against: Int, val neutral: Int, val unknown: Int) {
        val known: Int get() = agree + against + neutral
    }

    /** [own] is the direction being judged (+1 / -1); the frame the forecast was built on always agrees with itself. */
    fun summarize(own: Int, dirs: Map<String, Int?>): Summary {
        var agree = 0
        var against = 0
        var neutral = 0
        var unknown = 0
        for (frame in FRAMES) {
            val d = dirs[frame]
            when {
                d == null -> unknown += 1
                d == own -> agree += 1
                d == -own -> against += 1
                else -> neutral += 1
            }
        }
        return Summary(agree, against, neutral, unknown)
    }

    fun verdict(s: Summary, ru: Boolean): String = when {
        s.known < 2 -> if (ru) "Данных по другим срокам пока мало" else "Not enough data on the other horizons yet"
        s.against == 0 && s.agree >= 4 -> if (ru) "Сильное совпадение: направление подтверждено почти на всех сроках" else "Strong agreement: the direction is confirmed on almost every horizon"
        s.against == 0 && s.agree >= 3 -> if (ru) "Хорошее совпадение: нет сроков против сигнала" else "Good agreement: no horizon is against the signal"
        s.against >= 2 -> if (ru) "Сроки расходятся: сигнал идёт против нескольких таймфреймов" else "Horizons disagree: the signal runs against several timeframes"
        s.against == 1 -> if (ru) "Один срок против сигнала: вход с осторожностью" else "One horizon is against the signal: enter with care"
        else -> if (ru) "Подтверждение слабое: другие сроки пока без сигнала" else "Weak confirmation: the other horizons have no signal yet"
    }
}

private fun frameColor(d: Int?): Color = when (d) {
    1 -> Positive
    -1 -> Negative
    0 -> DarkMuted
    else -> DarkMuted.copy(alpha = 0.45f)
}

private fun frameGlyph(d: Int?): String = when (d) {
    1 -> "▲"
    -1 -> "▼"
    0 -> "●"
    else -> "·"
}

/** Five small cells, one per horizon. The horizon the forecast was built on is outlined. */
@Composable
internal fun AlignCells(dirs: Map<String, Int?>, ownFrame: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Confluence.FRAMES.forEach { frame ->
            val d = dirs[frame]
            val c = frameColor(d)
            val own = frame == ownFrame
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(c.copy(alpha = if (d == null) 0.05f else 0.14f))
                    .then(if (own) Modifier.border(1.2.dp, c.copy(alpha = 0.9f), shape) else Modifier)
                    .padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    frame, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (own) FontWeight.Black else FontWeight.Medium, textAlign = TextAlign.Center
                )
                Text(frameGlyph(d), fontSize = 14.sp, color = c, fontWeight = FontWeight.Black)
            }
        }
    }
}

/** Analysis screen: the verdict on agreement across all five horizons. */
@Composable
internal fun ConfluenceCard(tf: String, current: Forecast?, others: Map<String, Forecast?>, ru: Boolean) {
    val dirs = Confluence.FRAMES.associateWith { frame -> Confluence.direction(if (frame == tf) current else others[frame]) }
    val own = Confluence.direction(current) ?: 0
    GradientCard(Modifier.fillMaxWidth()) {
        if (own == 0) {
            Text(if (ru) "Совпадение сроков" else "Timeframe agreement", fontWeight = FontWeight.Black, fontSize = 15.sp)
            Text(
                if (ru) "На сроке $tf сигнала нет, поэтому сравнивать не с чем. Ниже направление на каждом сроке."
                else "There is no signal on $tf, so there is nothing to compare. Below: the direction on every horizon.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
            )
            AlignCells(dirs, tf, Modifier.padding(top = 10.dp))
        } else {
            val s = Confluence.summarize(own, dirs)
            val tone = when {
                s.against == 0 && s.agree >= 3 -> Positive
                s.against >= 2 -> Negative
                else -> Warning
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (ru) "Совпадение сроков" else "Timeframe agreement", fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("${s.agree}/${Confluence.FRAMES.size}", fontWeight = FontWeight.Black, fontSize = 20.sp, color = tone)
            }
            AlignCells(dirs, tf, Modifier.padding(top = 10.dp))
            Text(Confluence.verdict(s, ru), fontSize = 12.sp, color = tone, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Text(
                if (ru) "Совпало: ${s.agree} • против: ${s.against} • без сигнала: ${s.neutral}" + (if (s.unknown > 0) " • нет данных: ${s.unknown}" else "")
                else "Agree: ${s.agree} • against: ${s.against} • no signal: ${s.neutral}" + (if (s.unknown > 0) " • no data: ${s.unknown}" else ""),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
