package com.marketforecast.prox

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
            if (ru) "Чем выше издержки, тем меньше сигналов проходит порог выгоды. На коротких сроках и у спокойных инструментов (валюты) издержки могут съедать всё ожидаемое движение: такие случаи показываются как «Только направление»."
            else "The higher the costs, the fewer signals clear the profitability bar. On short horizons and with calm instruments (currencies) costs can eat the whole expected move: those cases are shown as \"Direction only\".",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
        )
    }
}
