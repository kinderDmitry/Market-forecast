package com.marketforecast.prox

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import android.content.SharedPreferences
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Settings: what a round trip costs. The forecast engine charges it to every plan before it calls anything a signal. */
@Composable
internal fun CostSettings(ru: Boolean, prefs: SharedPreferences) {
    var stock by remember { mutableFloatStateOf(prefs.getFloat("cost_stock_pct", 0.08f)) }
    var fx by remember { mutableFloatStateOf(prefs.getFloat("cost_fx_pct", 0.04f)) }
    var style by remember { mutableIntStateOf(prefs.getInt("plan_style", 1)) }
    GradientCard(Modifier.fillMaxWidth()) {
        SectionHeader(
            if (ru) "Издержки сделки" else "Trading costs",
            if (ru) "Комиссия и спред за вход и выход вместе" else "Commission and spread for entry and exit together"
        )
        Text(
            (if (ru) "Акции: " else "Stocks: ") + String.format(Locale.US, "%.2f%%", stock),
            fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)
        )
        Slider(
            value = stock,
            onValueChange = { stock = it },
            onValueChangeFinished = { prefs.edit().putFloat("cost_stock_pct", stock).apply(); CostModel.load(prefs) },
            valueRange = 0.02f..0.30f
        )
        Text(
            (if (ru) "Валюты: " else "Currencies: ") + String.format(Locale.US, "%.2f%%", fx),
            fontWeight = FontWeight.Bold, fontSize = 13.sp
        )
        Slider(
            value = fx,
            onValueChange = { fx = it },
            onValueChangeFinished = { prefs.edit().putFloat("cost_fx_pct", fx).apply(); CostModel.load(prefs) },
            valueRange = 0.01f..0.15f
        )
        Text(
            if (ru) "Стиль плана" else "Plan style",
            fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to (if (ru) "Надёжнее" else "Safer"), 1 to (if (ru) "Баланс" else "Balanced"), 2 to (if (ru) "Прибыльнее" else "More profit")).forEach { (value, label) ->
                FilterChip(
                    selected = style == value,
                    onClick = { style = value; prefs.edit().putInt("plan_style", value).apply(); CostModel.load(prefs) },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }
        Text(
            if (ru) "«Надёжнее» ставит первую цель ближе: она достигается заметно чаще (на моделируемых рынках около 54% прибыльных сделок против 46% при «Балансе»), но каждая победа меньше, и среднее ожидание ниже. «Прибыльнее» наоборот. Повысить шанс бесплатно нельзя: за каждый процент вероятности платит размер результата."
            else "\"Safer\" puts the first target closer: it is reached noticeably more often (on simulated markets about 54% winning trades against 46% for \"Balanced\"), but each win is smaller and the average expectation is lower. \"More profit\" is the opposite. A higher chance is never free: every point of probability is paid for with the size of the result.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
        )
        Text(
            if (ru) "Чем выше издержки, тем меньше сигналов проходит порог выгоды. На коротких сроках и у спокойных инструментов (валюты) издержки могут съедать всё ожидаемое движение: такие случаи показываются как «Только направление»."
            else "The higher the costs, the fewer signals clear the profitability bar. On short horizons and with calm instruments (currencies) costs can eat the whole expected move: those cases are shown as \"Direction only\".",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
        )
    }
}
