package com.marketforecast.prox

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

internal class Sketch(val name: String, val caption: String)

internal class Question(val text: String, val options: List<String>, val answer: Int, val explain: String)

internal class Lesson(
    val id: String,
    val module: String,
    val title: String,
    val minutes: Int,
    val intro: String,
    val points: List<String>,
    val sketches: List<Sketch>,
    val note: String?,
    val quiz: List<Question>
)

/** The course: from what a candle is to how to read the forecast of this very app. Plain education, not investment advice. */
internal object LearnContent {
    val lessons: List<Lesson> = listOf(
        Lesson(
            "candle", "1. Основы", "Свеча и таймфрейм", 5,
            "Свеча показывает, что делала цена за один отрезок времени: где открылась, как высоко и низко ходила и где закрылась.",
            listOf(
                "Тело свечи лежит между ценой открытия и закрытия. Зелёная (растущая) свеча закрылась выше открытия, красная (падающая) ниже.",
                "Тени, или фитили, показывают крайние значения за период. Длинная нижняя тень говорит, что падение выкупили покупатели. Длинная верхняя: рост отбили продавцы.",
                "Таймфрейм это длительность одной свечи: 15 минут, час, 4 часа, день, неделя, месяц. Чем он больше, тем значимее движение, но тем реже появляются сигналы.",
                "Старший таймфрейм задаёт контекст (куда идёт рынок), младший помогает выбрать момент входа.",
                "В приложении срок прогноза равен таймфрейму свечей, по которым он построен."
            ),
            listOf(Sketch("anatomy", "Слева растущая свеча, справа падающая. Тонкая линия: тени, широкая часть: тело.")),
            null,
            listOf(
                Question("Что означает длинная нижняя тень свечи?", listOf("Продавцы удержали цену внизу", "Цена падала, но покупатели вернули её вверх", "Торги были остановлены", "Объём был нулевым"), 1, "Нижняя тень это уровень, до которого цена доходила и откуда её вытолкнули. Покупатели были сильнее на минимумах."),
                Question("Закрытие выше открытия. Какая это свеча?", listOf("Растущая (зелёная)", "Падающая (красная)", "Нейтральная", "Зависит от объёма"), 0, "Цвет определяется только соотношением закрытия и открытия."),
                Question("Зачем смотреть старший таймфрейм?", listOf("Чтобы найти точный вход", "Чтобы понять общий контекст и направление", "Чтобы посчитать комиссию", "Он не нужен"), 1, "Старший таймфрейм показывает, в какую сторону дует ветер. Вход ищут на младшем.")
            )
        ),
        Lesson(
            "trend", "1. Основы", "Тренд, диапазон и уровни", 6,
            "Рынок почти всегда находится в одном из двух состояний: движется в одну сторону (тренд) или ходит туда-обратно (диапазон). От этого зависит, какие приёмы работают.",
            listOf(
                "Восходящий тренд: каждый следующий максимум и минимум выше предыдущего. Нисходящий: каждый ниже.",
                "Диапазон: цена ходит между нижней и верхней границей. В нём работают отскоки от границ, а выход за границу может начать новое движение.",
                "Поддержка и сопротивление это зоны, где цена уже разворачивалась. Это не точная линия: допускайте отклонение порядка 0,3 ATR.",
                "Пробитое сопротивление часто становится поддержкой, и наоборот.",
                "Чем чаще уровень проверяли и чем больше объём на реакции, тем он важнее."
            ),
            listOf(Sketch("range", "Цена зажата между двумя уровнями: до пробоя это диапазон.")),
            "Трендовые приёмы в диапазоне дают много ложных сигналов, а приёмы отскока в сильном тренде приводят к серии стопов. Сначала определите состояние рынка.",
            listOf(
                Question("Какой признак восходящего тренда?", listOf("Максимумы и минимумы растут", "Цена стоит на месте", "Объём падает", "Свечи красные"), 0, "Серия растущих максимумов и минимумов и есть определение восходящего тренда."),
                Question("Где чаще работают отскоки от границ?", listOf("В сильном тренде", "В диапазоне", "Только на дневках", "Нигде"), 1, "В диапазоне цена возвращается к середине, поэтому границы работают как опора."),
                Question("Как относиться к уровню?", listOf("Как к зоне", "Как к точной линии до копейки", "Игнорировать", "Только круглые числа"), 0, "Цена редко разворачивается ровно от одного значения: уровень это область.")
            )
        ),
        Lesson(
            "engulf", "2. Свечные паттерны", "Поглощение", 5,
            "Поглощение это две свечи: вторая своим телом полностью перекрывает тело первой и показывает смену контроля.",
            listOf(
                "Бычье поглощение: после снижения маленькую красную свечу перекрывает большая зелёная. Покупатели забрали инициативу.",
                "Медвежье поглощение: после роста маленькую зелёную свечу перекрывает большая красная.",
                "Работает после движения в обратную сторону. В середине бокового диапазона паттерн почти ничего не значит.",
                "Тело второй свечи должно быть заметно больше первой (приложение требует не меньше чем в 1,1 раза). Плюс, если объём выше обычного.",
                "Стоп обычно ставят за минимум паттерна (для покупки) или за максимум (для продажи)."
            ),
            listOf(
                Sketch("engulf_bull", "Бычье поглощение после снижения: последняя зелёная свеча перекрывает предыдущую красную."),
                Sketch("engulf_bear", "Медвежье поглощение после роста.")
            ),
            "Один паттерн не повод входить. Это подтверждение вместе с уровнем, трендом старшего таймфрейма и разумным стопом.",
            listOf(
                Question("После чего бычье поглощение имеет смысл?", listOf("После снижения", "После роста", "В любое время", "Только в пятницу"), 0, "Разворотный паттерн должен разворачивать что-то: бычий нужен после падения."),
                Question("Что должна сделать вторая свеча?", listOf("Быть меньше первой", "Перекрыть тело первой", "Иметь такой же размер", "Не иметь теней"), 1, "Поглощение значит, что тело второй свечи включает тело первой."),
                Question("Где обычно ставят стоп после бычьего поглощения?", listOf("Над максимумом", "За минимумом паттерна", "Не ставят", "В середине свечи"), 1, "Если цена уходит ниже минимума паттерна, идея разворота не сработала.")
            )
        ),
        Lesson(
            "hammer", "2. Свечные паттерны", "Молот и падающая звезда", 4,
            "Это одиночные свечи с длинной тенью. Они показывают, что цену резко толкнули в одну сторону, но её вернули назад.",
            listOf(
                "Молот: маленькое тело вверху и длинная нижняя тень (не короче двух тел). Появляется после снижения и говорит об отказе продавцов идти ниже.",
                "Падающая звезда: маленькое тело внизу и длинная верхняя тень. Появляется после роста и показывает, что покупателей на вершине не хватило.",
                "Цвет тела вторичен. Важны форма и место появления.",
                "Подтверждением служит следующая свеча в нужную сторону.",
                "Длинная тень должна занимать больше половины диапазона свечи, иначе это просто обычная свеча."
            ),
            listOf(Sketch("hammer", "Молот: после снижения свеча с длинной нижней тенью, затем рост.")),
            null,
            listOf(
                Question("Где появляется молот?", listOf("После роста", "После снижения", "В середине диапазона", "Не имеет значения"), 1, "Молот это сигнал отказа от дальнейшего снижения."),
                Question("Что важнее в молоте?", listOf("Цвет", "Длинная тень и место появления", "Объём торгов за год", "Число свечей до него"), 1, "Форма и контекст важнее цвета тела."),
                Question("Что считают подтверждением?", listOf("Следующую свечу в сторону сигнала", "Любую свечу", "Новость", "Ничего"), 0, "Без подтверждения одиночная свеча остаётся лишь предположением.")
            )
        ),
        Lesson(
            "stars", "2. Свечные паттерны", "Утренняя и вечерняя звезда", 4,
            "Трёхсвечные развороты. Первая свеча продолжает движение, вторая показывает сомнение, третья разворачивает.",
            listOf(
                "Утренняя звезда: длинная красная свеча, затем маленькая (нерешительность), затем длинная зелёная, закрывшаяся выше середины первой.",
                "Вечерняя звезда: зеркальный вариант после роста.",
                "Чем больше тело первой свечи и чем глубже закрытие третьей, тем надёжнее фигура.",
                "Паттерну нужен предшествующий тренд: звезда в боковике ничего не значит.",
                "Цель обычно ближайший уровень, а стоп за экстремум средней свечи."
            ),
            listOf(Sketch("morning", "Утренняя звезда: падение, пауза, уверенный рост.")),
            null,
            listOf(
                Question("Сколько свечей в утренней звезде?", listOf("Одна", "Две", "Три", "Пять"), 2, "Длинная, маленькая и разворотная длинная."),
                Question("Что показывает маленькая средняя свеча?", listOf("Нерешительность и затухание движения", "Ошибку данных", "Рост объёма", "Выходной"), 0, "Сильное движение остановилось, это и готовит разворот.")
            )
        ),
        Lesson(
            "indicators", "3. Индикаторы", "EMA, RSI и MACD", 7,
            "Индикаторы это формулы поверх цены. Они ничего не знают о будущем, но помогают увидеть направление, силу движения и перегрев.",
            listOf(
                "EMA (экспоненциальная средняя) сглаживает цену и сильнее учитывает последние свечи. Цена выше EMA 20 и EMA 20 выше EMA 50: восходящая структура.",
                "RSI меряет силу движения от 0 до 100. Выше 70 перегрев, ниже 30 перепроданность. В сильном тренде RSI может долго оставаться на экстремуме, поэтому сам по себе он не сигнал.",
                "Дивергенция RSI: цена обновила максимум, а RSI нет (или наоборот). Движение теряет силу. Приложение ищет её на последних 12 свечах.",
                "MACD показывает схождение и расхождение двух EMA. Пересечение нулевой линии и рост гистограммы говорят об усилении импульса.",
                "Все эти индикаторы запаздывают: они подтверждают уже случившееся. Поэтому приложение использует их вместе, а не по отдельности."
            ),
            emptyList(),
            "Не набирайте десять похожих индикаторов. Они почти все выводятся из одной и той же цены, и вы просто десять раз смотрите на одно и то же.",
            listOf(
                Question("Что такое дивергенция RSI?", listOf("Цена обновила экстремум, RSI не подтвердил", "RSI равен 50", "RSI выше 70", "Совпадение цены и RSI"), 0, "Расхождение показывает слабеющий импульс."),
                Question("Почему индикаторы нельзя считать прогнозом?", listOf("Они запаздывают и считаются по прошлой цене", "Они всегда врут", "Их придумали недавно", "Они платные"), 0, "Они описывают прошлое. Прогноз получается из их сочетания и статистики."),
                Question("RSI 80 в сильном росте означает?", listOf("Обязательный разворот", "Перегрев, но тренд может продолжаться", "Ошибку", "Пора покупать больше"), 1, "В тренде перегрев может длиться долго.")
            )
        ),
        Lesson(
            "volatility", "3. Индикаторы", "ATR и полосы Боллинджера", 6,
            "Волатильность это размах движения. Она определяет, где ставить стоп и какие цели реалистичны.",
            listOf(
                "ATR это средний диапазон свечи за 14 периодов. Стоп в 1–2 ATR от входа обычно выдерживает рыночный шум, стоп в полатра выбивает случайно.",
                "Цели тоже меряют в ATR: цель в 10 ATR на 15-минутках за пару часов нереальна.",
                "Полосы Боллинджера это средняя и две линии на расстоянии двух стандартных отклонений. Цена большую часть времени внутри полос.",
                "Сжатие: полосы сужаются, рынок «копит энергию». После сжатия часто идёт резкое движение, направление которого заранее неизвестно.",
                "Выход свечи за полосу в сжатии с большим телом и объёмом это сигнал начала движения. В диапазоне касание полосы наоборот часто даёт возврат."
            ),
            listOf(Sketch("squeeze", "Сжатие: свечи становятся всё меньше, затем сильная свеча вырывается вверх.")),
            null,
            listOf(
                Question("Для чего чаще всего нужен ATR?", listOf("Для расчёта стопа и целей", "Для прогноза новостей", "Для расчёта дивидендов", "Ни для чего"), 0, "ATR показывает типичный размах цены, от него считают расстояние до стопа и цели."),
                Question("Что происходит в сжатии?", listOf("Волатильность падает, полосы сужаются", "Объём всегда растёт", "Цена всегда падает", "Торги останавливаются"), 0, "Сжатие это период низкой волатильности, за которым нередко идёт расширение.")
            )
        ),
        Lesson(
            "volume", "3. Индикаторы", "Объём, VWAP, CMF и Supertrend", 6,
            "Цена показывает, что произошло, объём показывает, сколько денег за этим стояло.",
            listOf(
                "Движение на большом объёме надёжнее движения на малом. Пробой без объёма чаще оказывается ложным.",
                "VWAP это средняя цена, взвешенная по объёму. Цена выше VWAP: в среднем покупатели в плюсе и сильнее.",
                "CMF (денежный поток Чайкина) от -1 до +1. Положительный: закрытия ближе к максимумам с объёмом, идёт приток. Отрицательный: давление продавцов.",
                "У валютных пар на бирже объём часто нулевой, и CMF там не считается.",
                "Supertrend строится на ATR и показывает направление тренда и точку, где он «ломается». Свежий разворот Supertrend сильнее давнего."
            ),
            emptyList(),
            null,
            listOf(
                Question("Почему важен объём при пробое?", listOf("Подтверждает интерес рынка к движению", "Он не важен", "Показывает дивиденды", "Заменяет стоп"), 0, "Большой объём значит, что в движение вложены реальные деньги."),
                Question("CMF ниже нуля означает", listOf("Давление продавцов", "Приток покупателей", "Ошибку данных", "Выходной"), 0, "Отрицательное значение: закрытия ближе к минимумам.")
            )
        ),
        Lesson(
            "breakout", "4. Конкретные движения", "Пробой диапазона и выход из сжатия", 7,
            "Это самые «конкретные» события: цена закрылась за границей, которую раньше не покидала. Приложение ищет их на каждой свече и проверяет на истории инструмента.",
            listOf(
                "Пробой диапазона: закрытие выше максимума последних 20 свечей (или ниже минимума) на широкой свече и с объёмом выше обычного.",
                "Выход из сжатия: несколько свечей подряд полосы Боллинджера были узкими, затем сильная свеча закрылась за полосой.",
                "Закрытие важнее прокола: ложный пробой часто имеет длинную тень, а закрытие возвращается внутрь.",
                "Вход можно искать сразу по закрытию или после отката к пробитому уровню (выгоднее, но можно не дождаться).",
                "Стоп под пробитый уровень или внутрь диапазона. Если цена вернулась в диапазон, идея сломана."
            ),
            listOf(
                Sketch("breakout", "Пробой диапазона: сильная свеча закрывается выше сопротивления."),
                Sketch("squeeze", "Сжатие перед пробоем: размах свечей падает.")
            ),
            "Пробои на малых таймфреймах часто ложные. Приложение показывает, сколько таких событий было у этого инструмента и чем они закончились: смотрите это число, а не только название сетапа.",
            listOf(
                Question("Что важнее для пробоя?", listOf("Закрытие за уровнем, а не прокол", "Цвет свечи вчера", "Номер недели", "Название акции"), 0, "Цена должна закрепиться за уровнем."),
                Question("Что обычно предшествует сильному движению?", listOf("Сжатие волатильности", "Рост объёма в 10 раз всегда", "Новости", "Закрытие биржи"), 0, "После периода низкой волатильности часто идёт расширение."),
                Question("Что значит возврат цены в диапазон после пробоя?", listOf("Пробой, вероятно, ложный", "Подтверждение пробоя", "Нужно усреднять", "Ничего"), 0, "Если цена снова внутри, закрепления нет, идея сломана.")
            )
        ),
        Lesson(
            "pullback", "4. Конкретные движения", "Откат к средней и отскок от границы", 7,
            "Два приёма «по возвращению»: войти в тренд на откате или сыграть возврат от края диапазона.",
            listOf(
                "Откат в тренде: в восходящем тренде цена касается EMA 20 и закрывается выше предыдущего максимума. Вход по тренду с коротким стопом под откат.",
                "Условие тренда: цена идёт прямолинейно (эффективность движения не ниже 0,3), EMA 20 выше EMA 50.",
                "Отскок от границы: в диапазоне цена прокалывает нижнюю полосу, закрывается обратно внутри, RSI ниже 40, нижняя тень не меньше 40% свечи.",
                "Цель отскока середина диапазона или противоположная граница. Стоп за экстремум прокола.",
                "Эти приёмы противоположны по духу: один работает в тренде, второй в диапазоне. Сначала определите режим."
            ),
            listOf(
                Sketch("pullback", "Откат к средней в росте и продолжение."),
                Sketch("reversion", "Отскок от нижней границы диапазона.")
            ),
            null,
            listOf(
                Question("Где работает откат к EMA?", listOf("В тренде", "В любом рынке", "Только в диапазоне", "Только на новостях"), 0, "Откат это продолжение уже идущего тренда."),
                Question("Что нужно для отскока от границы?", listOf("Диапазон и признак отказа (тень, RSI)", "Сильный тренд", "Дивиденды", "Нулевой объём"), 0, "Отскок это возврат внутрь диапазона после прокола.")
            )
        ),
        Lesson(
            "burst", "4. Конкретные движения", "Импульсная свеча", 4,
            "Большая свеча с телом заметно выше обычного размаха, закрытая у своего экстремума.",
            listOf(
                "Условие: тело не меньше 1,4 ATR, закрытие в верхней (или нижней) четверти свечи, объём выше среднего.",
                "Такая свеча показывает агрессивных участников. Продолжение вероятно, но не гарантировано: часть импульсов заканчивается возвратом.",
                "Входить поздно опасно: стоп получается длинным. Лучше ждать отката части свечи или брать вход с уменьшенным размером.",
                "Новостные импульсы чаще возвращаются, чем технические."
            ),
            listOf(Sketch("burst", "Импульсная свеча: спокойные свечи, затем большое тело у максимума.")),
            "Статистика по импульсам у каждого инструмента своя. Приложение считает её отдельно и показывает число похожих событий.",
            listOf(
                Question("Как определяют импульсную свечу?", listOf("Большое тело и закрытие у экстремума с объёмом", "Любая зелёная свеча", "Самая маленькая свеча", "Свеча без теней обязательно"), 0, "Размер тела, закрытие у края и объём вместе дают импульс.")
            )
        ),
        Lesson(
            "risk", "5. Риск и деньги", "Стоп, цели и R", 7,
            "Сначала решают, сколько вы готовы потерять, и только потом сколько хотите заработать.",
            listOf(
                "Стоп это цена, на которой вы признаёте идею неверной. Он ставится за уровень или экстремум, а не «где не жалко».",
                "R это ваш риск на сделку: расстояние от входа до стопа. Цель в 2R значит, что вы зарабатываете вдвое больше, чем рискуете.",
                "Размер позиции = допустимая потеря в деньгах / расстояние до стопа. Пример: риск 1000 рублей, стоп 5 рублей на акцию: покупаете 200 акций.",
                "Разумный риск на сделку 0,5–1% от счёта. Серия из 10 проигрышей при 1% съест лишь около 10%.",
                "План приложения: треть позиции на каждой из трёх целей, после первой стоп переносится в безубыток, после второй на первую цель."
            ),
            emptyList(),
            "Никогда не отодвигайте стоп, когда цена к нему подошла. Это превращает маленький убыток в большой.",
            listOf(
                Question("Что такое 1R?", listOf("Ваш риск на сделку (вход минус стоп)", "Размер комиссии", "Один пункт цены", "Прибыль за день"), 0, "R это единица риска: от неё считают цели и результат."),
                Question("Риск 2000 рублей, стоп 10 рублей на акцию. Сколько акций?", listOf("20", "200", "2000", "10"), 1, "2000 / 10 = 200 акций."),
                Question("Что делать, когда цена подошла к стопу?", listOf("Отодвинуть стоп", "Принять убыток по плану", "Удвоить позицию", "Закрыть приложение"), 1, "Стоп существует, чтобы убыток был заранее известным.")
            )
        ),
        Lesson(
            "ev", "5. Риск и деньги", "Издержки и ожидание (EV)", 7,
            "Прибыльность определяется не долей выигрышных сделок, а тем, сколько в среднем остаётся на одну сделку после всех расходов.",
            listOf(
                "Ожидание (EV) в R = шанс выигрыша умноженный на средний выигрыш, минус шанс проигрыша умноженный на проигрыш, минус издержки.",
                "Можно выигрывать в 70% сделок и терять деньги, если выигрыши мелкие, а проигрыши крупные. И наоборот: выигрывать в 40%, но зарабатывать на целях 2–3R.",
                "Издержки это комиссия и спред за вход и выход. На коротких таймфреймах движение свечи мало, и издержки могут съесть почти всё ожидание.",
                "Поэтому приложение пересчитывает ожидание после издержек и показывает «Только направление», если оно не выше порога.",
                "Чем ближе первая цель, тем чаще она достигается, но тем меньше средняя прибыль. Бесплатно повысить шанс нельзя: за каждый процент вероятности вы платите размером результата."
            ),
            emptyList(),
            null,
            listOf(
                Question("Можно ли быть в минусе при 70% выигрышных сделок?", listOf("Да, если проигрыши крупнее выигрышей", "Нет", "Только на валюте", "Только в пятницу"), 0, "Важно произведение вероятности на размер результата."),
                Question("Почему издержки критичны на коротких таймфреймах?", listOf("Движение свечи мало, и издержки съедают заметную долю", "Комиссия растёт ночью", "Их нет", "Они только на дневках"), 0, "Издержки в процентах одинаковы, а ход цены на малом таймфрейме меньше.")
            )
        ),
        Lesson(
            "app", "6. Как читать прогноз приложения", "Надёжность, проверка на случайность, совпадение сроков", 8,
            "Приложение не обещает прибыль: оно показывает, насколько сильны доводы и что говорит история именно этого инструмента.",
            listOf(
                "Надёжность A–D собирается из десяти проверок: согласие индикаторов, ожидание, риск к прибыли, устойчивость, качество данных и других. Класс B и выше стоит рассматривать, C и D лучше пропускать.",
                "Проверка на случайность (z): тот же план прогоняется на случайном блуждании с такой же волатильностью. Если z не выше 1, преимущество может быть шумом.",
                "Совпадение сроков: направление на 15 минутах, часе, 4 часах, дне и неделе. Чем больше совпадений и чем меньше сроков против, тем лучше.",
                "Конкретное движение (сетап) и число таких событий на истории: чем их больше, тем больше доверия к статистике.",
                "«Только направление» значит, что направление видно, но после издержек сделка невыгодна. «Рынок закрыт» значит, что прогноз построен по последней сессии. «★ ТОП» это самый строгий отбор.",
                "Шансы целей показаны в процентах. Это оценки по истории, а не обещания."
            ),
            emptyList(),
            "Ни один показатель в приложении не гарантирует результат. Они помогают отсеивать слабые идеи, а не угадывать будущее.",
            listOf(
                Question("Что означает низкое z в проверке на случайность?", listOf("Преимущество плана может быть шумом", "План идеален", "Рынок закрыт", "Нужно увеличить позицию"), 0, "Если план не лучше случайного блуждания, доверять ему нельзя."),
                Question("Что значит «Только направление»?", listOf("Направление видно, но выгоды после издержек нет", "Лучший сигнал", "Ошибка", "Сигнал на вход"), 0, "Это ориентир, а не сигнал."),
                Question("Что такое ★ ТОП?", listOf("Самый строгий отбор, но не гарантия", "Гарантированная прибыль", "Совет брокера", "Новость"), 0, "Топ-сигналы проходят все проверки, но прибыль всё равно не гарантирована.")
            )
        ),
        Lesson(
            "mistakes", "6. Как читать прогноз приложения", "Ошибки новичков и дисциплина", 5,
            "Большинство потерь приносит не плохой анализ, а нарушение собственных правил.",
            listOf(
                "Слишком частые сделки: каждая сделка стоит издержек, а хороших сетапов немного.",
                "Отодвигание стопа и усреднение убытка: так маленькая потеря превращается в большую.",
                "Месть рынку: вход сразу после стопа, чтобы «отыграться».",
                "Погоня за ушедшей ценой: входить в уже сделавшую большое движение свечу значит брать худший вход с самым длинным стопом.",
                "Один таймфрейм: смотрите старший для контекста.",
                "Ведите журнал сделок: что видели, почему вошли, чем закончилось. Через месяц это лучший учитель."
            ),
            emptyList(),
            null,
            listOf(
                Question("Что лучше сделать после стопа?", listOf("Сразу войти обратно, чтобы отыграться", "Остановиться и разобрать сделку", "Удвоить размер", "Отодвинуть стоп"), 1, "Эмоциональный вход после убытка частая причина серии потерь."),
                Question("Зачем вести журнал сделок?", listOf("Чтобы видеть свои повторяющиеся ошибки", "Для налоговой", "Это обязательно по закону", "Незачем"), 0, "Журнал показывает закономерности, которые не видны в моменте.")
            )
        )
    )
}

/** Tiny candle drawings for the lessons: each candle is (open, high, low, close) on a 0..1 scale, 1 is the top. */
private fun sketchCandles(name: String): Pair<List<FloatArray>, List<Float>> = when (name) {
    "anatomy" -> listOf(floatArrayOf(0.30f, 0.90f, 0.15f, 0.75f), floatArrayOf(0.75f, 0.90f, 0.15f, 0.30f)) to emptyList()
    "range" -> listOf(
        floatArrayOf(0.35f, 0.60f, 0.30f, 0.55f), floatArrayOf(0.55f, 0.72f, 0.45f, 0.50f), floatArrayOf(0.50f, 0.75f, 0.42f, 0.70f),
        floatArrayOf(0.70f, 0.74f, 0.38f, 0.42f), floatArrayOf(0.42f, 0.50f, 0.28f, 0.45f), floatArrayOf(0.45f, 0.72f, 0.40f, 0.68f)
    ) to listOf(0.76f, 0.27f)
    "engulf_bull" -> listOf(
        floatArrayOf(0.85f, 0.87f, 0.72f, 0.74f), floatArrayOf(0.74f, 0.76f, 0.62f, 0.64f), floatArrayOf(0.64f, 0.66f, 0.52f, 0.54f),
        floatArrayOf(0.56f, 0.58f, 0.46f, 0.49f), floatArrayOf(0.46f, 0.76f, 0.44f, 0.74f)
    ) to emptyList()
    "engulf_bear" -> listOf(
        floatArrayOf(0.15f, 0.28f, 0.13f, 0.26f), floatArrayOf(0.26f, 0.38f, 0.24f, 0.36f), floatArrayOf(0.36f, 0.48f, 0.34f, 0.46f),
        floatArrayOf(0.44f, 0.56f, 0.42f, 0.54f), floatArrayOf(0.57f, 0.58f, 0.24f, 0.26f)
    ) to emptyList()
    "hammer" -> listOf(
        floatArrayOf(0.85f, 0.87f, 0.72f, 0.74f), floatArrayOf(0.74f, 0.76f, 0.60f, 0.62f), floatArrayOf(0.62f, 0.64f, 0.50f, 0.52f),
        floatArrayOf(0.52f, 0.54f, 0.22f, 0.50f), floatArrayOf(0.50f, 0.66f, 0.48f, 0.64f), floatArrayOf(0.64f, 0.80f, 0.62f, 0.78f)
    ) to emptyList()
    "morning" -> listOf(
        floatArrayOf(0.85f, 0.87f, 0.74f, 0.76f), floatArrayOf(0.76f, 0.78f, 0.50f, 0.52f), floatArrayOf(0.50f, 0.52f, 0.32f, 0.34f),
        floatArrayOf(0.30f, 0.36f, 0.24f, 0.32f), floatArrayOf(0.34f, 0.66f, 0.32f, 0.64f)
    ) to emptyList()
    "squeeze" -> listOf(
        floatArrayOf(0.40f, 0.70f, 0.30f, 0.62f), floatArrayOf(0.62f, 0.66f, 0.42f, 0.46f), floatArrayOf(0.46f, 0.58f, 0.40f, 0.54f),
        floatArrayOf(0.54f, 0.58f, 0.48f, 0.50f), floatArrayOf(0.50f, 0.56f, 0.47f, 0.53f), floatArrayOf(0.53f, 0.85f, 0.52f, 0.84f)
    ) to listOf(0.60f, 0.43f)
    "breakout" -> listOf(
        floatArrayOf(0.45f, 0.58f, 0.40f, 0.52f), floatArrayOf(0.52f, 0.60f, 0.44f, 0.48f), floatArrayOf(0.48f, 0.59f, 0.42f, 0.56f),
        floatArrayOf(0.56f, 0.61f, 0.46f, 0.50f), floatArrayOf(0.50f, 0.60f, 0.45f, 0.58f), floatArrayOf(0.58f, 0.92f, 0.56f, 0.90f)
    ) to listOf(0.62f)
    "pullback" -> listOf(
        floatArrayOf(0.20f, 0.36f, 0.18f, 0.34f), floatArrayOf(0.34f, 0.52f, 0.32f, 0.50f), floatArrayOf(0.50f, 0.68f, 0.48f, 0.66f),
        floatArrayOf(0.66f, 0.68f, 0.52f, 0.54f), floatArrayOf(0.54f, 0.56f, 0.46f, 0.50f), floatArrayOf(0.50f, 0.74f, 0.48f, 0.72f),
        floatArrayOf(0.72f, 0.90f, 0.70f, 0.88f)
    ) to listOf(0.47f)
    "reversion" -> listOf(
        floatArrayOf(0.55f, 0.70f, 0.50f, 0.66f), floatArrayOf(0.66f, 0.72f, 0.48f, 0.52f), floatArrayOf(0.52f, 0.56f, 0.34f, 0.38f),
        floatArrayOf(0.38f, 0.40f, 0.18f, 0.36f), floatArrayOf(0.36f, 0.58f, 0.34f, 0.56f), floatArrayOf(0.56f, 0.70f, 0.52f, 0.66f)
    ) to listOf(0.30f, 0.74f)
    "burst" -> listOf(
        floatArrayOf(0.45f, 0.52f, 0.40f, 0.48f), floatArrayOf(0.48f, 0.54f, 0.42f, 0.44f), floatArrayOf(0.44f, 0.52f, 0.40f, 0.50f),
        floatArrayOf(0.50f, 0.55f, 0.45f, 0.47f), floatArrayOf(0.47f, 0.92f, 0.46f, 0.90f)
    ) to emptyList()
    else -> emptyList<FloatArray>() to emptyList()
}

@Composable
private fun CandleSketch(name: String, modifier: Modifier = Modifier) {
    val (candles, levels) = sketchCandles(name)
    if (candles.isEmpty()) return
    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        val w = size.width / candles.size
        val h = size.height
        fun y(v: Float) = h * (1f - v)
        for (lv in levels) {
            drawLine(Color(0x997FA6D6), Offset(0f, y(lv)), Offset(size.width, y(lv)), strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f)))
        }
        candles.forEachIndexed { i, c ->
            val x = w * (i + 0.5f)
            val tone = if (c[3] >= c[0]) Positive else Negative
            drawLine(tone, Offset(x, y(c[1])), Offset(x, y(c[2])), strokeWidth = 5f)
            val top = y(max(c[0], c[3]))
            val bottom = y(min(c[0], c[3]))
            drawRect(tone, Offset(x - w * 0.22f, top), Size(w * 0.44f, max(bottom - top, 5f)))
        }
    }
}

@Composable
internal fun LearnEntryCard(ru: Boolean, onOpen: () -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("mfprefs", android.content.Context.MODE_PRIVATE)
    val done = prefs.getStringSet("learn_done", emptySet()).orEmpty().size
    GradientCard(Modifier.fillMaxWidth().tapCard(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.School, null, tint = Accent, modifier = Modifier.size(28.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(if (ru) "Обучение" else "Learn", fontWeight = FontWeight.Black, fontSize = 15.sp)
                Text(
                    if (ru) "Курс по свечам, паттернам, индикаторам, риску и чтению прогноза. Пройдено $done из ${LearnContent.lessons.size}"
                    else "A course on candles, patterns, indicators, risk and reading the forecast. Done $done of ${LearnContent.lessons.size}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun LearnScreen(ru: Boolean, prefs: SharedPreferences) {
    var openId by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    BackHandler(enabled = openId != null) { openId = null }
    val lesson = openId?.let { id -> LearnContent.lessons.firstOrNull { it.id == id } }
    if (lesson == null) {
        val done = remember(version) { prefs.getStringSet("learn_done", emptySet()).orEmpty().toSet() }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
            contentPadding = PaddingValues(bottom = 30.dp)
        ) {
            item {
                GradientCard(Modifier.fillMaxWidth()) {
                    Text(if (ru) "Курс по анализу рынка" else "Market analysis course", fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Text(
                        (if (ru) "Пройдено ${done.size} из ${LearnContent.lessons.size} уроков. " else "Completed ${done.size} of ${LearnContent.lessons.size} lessons. ") +
                            (if (ru) "Каждый урок короткий, с рисунком и проверочными вопросами. Это обучение, а не инвестиционная рекомендация." else "The course is written in Russian. Education, not investment advice."),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            LearnContent.lessons.groupBy { it.module }.forEach { (module, items) ->
                item { SectionHeader(module, "") }
                items.forEach { l ->
                    item(key = l.id) {
                        val score = prefs.getInt("learn_score_" + l.id, -1)
                        GradientCard(Modifier.fillMaxWidth().tapCard { openId = l.id }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                                    Text(l.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text(l.minutes.toString() + " мин • " + l.quiz.size + " вопр.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (l.id in done) Text("✓ " + (if (score >= 0) "$score%" else ""), color = Positive, fontWeight = FontWeight.Black, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }
    } else {
        LessonView(lesson, ru, prefs, onProgress = { version += 1 }, onClose = { openId = null })
    }
}

@Composable
private fun LessonView(lesson: Lesson, ru: Boolean, prefs: SharedPreferences, onProgress: () -> Unit, onClose: () -> Unit) {
    val answers = remember(lesson.id) { mutableStateMapOf<Int, Int>() }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
        contentPadding = PaddingValues(bottom = 30.dp)
    ) {
        item {
            GradientCard(Modifier.fillMaxWidth()) {
                Text(lesson.title, fontWeight = FontWeight.Black, fontSize = 19.sp)
                Text(lesson.module + " • " + lesson.minutes + " мин", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                Text(lesson.intro, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
        lesson.sketches.forEach { sk ->
            item {
                GradientCard(Modifier.fillMaxWidth()) {
                    CandleSketch(sk.name)
                    Text(sk.caption, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item {
            GradientCard(Modifier.fillMaxWidth()) {
                SectionHeader("Главное", "")
                lesson.points.forEach { point ->
                    Text("• $point", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        lesson.note?.let { note ->
            item {
                GradientCard(Modifier.fillMaxWidth()) {
                    Text("⚠ $note", fontSize = 12.sp, color = Warning, fontWeight = FontWeight.Bold)
                }
            }
        }
        item { SectionHeader("Проверьте себя", answers.size.toString() + " из " + lesson.quiz.size) }
        lesson.quiz.forEachIndexed { index, q ->
            item {
                GradientCard(Modifier.fillMaxWidth()) {
                    Text(q.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    val chosen = answers[index]
                    q.options.forEachIndexed { oi, option ->
                        val tone = when {
                            chosen == null -> Accent
                            oi == q.answer -> Positive
                            oi == chosen -> Negative
                            else -> DarkMuted
                        }
                        OutlinedButton(
                            onClick = {
                                if (answers[index] == null) {
                                    answers[index] = oi
                                    if (answers.size == lesson.quiz.size) {
                                        val correct = lesson.quiz.indices.count { answers[it] == lesson.quiz[it].answer }
                                        val pct = correct * 100 / lesson.quiz.size
                                        val done = prefs.getStringSet("learn_done", emptySet()).orEmpty().toMutableSet()
                                        done.add(lesson.id)
                                        prefs.edit().putStringSet("learn_done", done).putInt("learn_score_" + lesson.id, pct).apply()
                                        onProgress()
                                    }
                                }
                            },
                            border = BorderStroke(1.dp, tone),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = tone),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) { Text(option, fontSize = 13.sp) }
                    }
                    if (chosen != null) {
                        Text(
                            (if (chosen == q.answer) "Верно. " else "Неверно. ") + q.explain,
                            fontSize = 12.sp, color = if (chosen == q.answer) Positive else Warning, modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
        if (answers.size == lesson.quiz.size) {
            item {
                val correct = lesson.quiz.indices.count { answers[it] == lesson.quiz[it].answer }
                GradientCard(Modifier.fillMaxWidth()) {
                    Text("Результат: $correct из ${lesson.quiz.size}", fontWeight = FontWeight.Black, fontSize = 16.sp, color = if (correct == lesson.quiz.size) Positive else Warning)
                    Text("Урок отмечен пройденным. Можно вернуться к нему в любой момент.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item { Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("К списку уроков", fontWeight = FontWeight.Black) } }
    }
}
