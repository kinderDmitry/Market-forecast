package com.marketforecast.prox

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Visual language of the app. The palette is unchanged (black canvas, blue accent, green / red signals);
 * everything here is about shape, depth, typography and motion.
 *
 * All decorative motion respects [UiPrefs.animations], so it can be switched off in Settings.
 */
internal object UiPrefs {
    var animations by mutableStateOf(true)

    /** Cards fade in only while a screen is opening (set on every screen change), never while a list scrolls. */
    var entranceUntil: Long = 0L
}

internal val MfpTypography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

internal val MfpShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val CardShape = RoundedCornerShape(22.dp)
private val BrightAccent = Color(0xFF4DA3FF)

// ------------------------------------------------------------------ interaction

/** Springy "pressed" feedback. Observes touches without consuming them, so clicks keep working. */
@Composable
internal fun Modifier.pressScale(pressedScale: Float = 0.965f): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(
        targetValue = if (pressed && UiPrefs.animations) pressedScale else 1f,
        animationSpec = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }) pressed = false
                } while (event.changes.any { it.pressed })
                pressed = false
            }
        }
}

/** Clickable card: ripple + springy press. */
@Composable
internal fun Modifier.tapCard(onClick: () -> Unit): Modifier = this.pressScale().clickable(onClick = onClick)

/** Soft fade + rise the first time a block appears on screen. */
@Composable
internal fun Modifier.entrance(delayMs: Int = 0): Modifier {
    val enabled = UiPrefs.animations && System.currentTimeMillis() < UiPrefs.entranceUntil
    val p = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (enabled) {
            if (delayMs > 0) delay(delayMs.toLong())
            p.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        }
    }
    return this.graphicsLayer {
        alpha = p.value
        translationY = (1f - p.value) * 12.dp.toPx()
    }
}

/** Gentle breathing for "something is happening" icons and tags. */
@Composable
internal fun Modifier.pulse(enabled: Boolean, minScale: Float = 0.9f): Modifier {
    if (!enabled || !UiPrefs.animations) return this
    val t = rememberInfiniteTransition(label = "pulse")
    val s = t.animateFloat(
        initialValue = minScale,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(850, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale"
    )
    return this.graphicsLayer {
        val v = s.value
        scaleX = v
        scaleY = v
        alpha = (0.62f + 0.38f * ((v - minScale) / (1.06f - minScale))).coerceIn(0f, 1f)
    }
}

/** Moving highlight for skeleton placeholders. */
@Composable
internal fun Modifier.shimmer(): Modifier {
    if (!UiPrefs.animations) return this
    val t = rememberInfiniteTransition(label = "shimmer")
    val x = t.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "shimmerX"
    )
    return this.drawWithContent {
        drawContent()
        val start = x.value * size.width
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.Transparent, Accent.copy(alpha = 0.22f), Color.Transparent),
                startX = start,
                endX = start + size.width * 0.6f
            )
        )
    }
}

// ------------------------------------------------------------------ surfaces

/** Base surface of every card: deep gradient, hairline gradient border, rounded clip (ripple stays inside). */
@Composable
internal fun CardSurface(modifier: Modifier, contentPadding: Dp, content: @Composable ColumnScope.() -> Unit) {
    // Cards used to come from Material's Card, which also supplied the default text colour; keep doing that.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            Modifier
                .entrance()
                .clip(CardShape)
                .then(modifier)
                .background(Brush.verticalGradient(listOf(Color(0xFF07101B), Color(0xFF05080C))))
                .background(Brush.linearGradient(listOf(Accent.copy(alpha = 0.08f), Color.Transparent, Accent2.copy(alpha = 0.05f))))
                .border(
                    1.dp,
                    Brush.linearGradient(listOf(DarkLine.copy(alpha = 0.95f), DarkLine.copy(alpha = 0.28f), Accent.copy(alpha = 0.6f))),
                    CardShape
                )
                .padding(contentPadding),
            content = content
        )
    }
}

@Composable
internal fun GradientCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    CardSurface(modifier, 16.dp, content)
}

@Composable
internal fun SectionHeader(t: String, s: String) {
    Row(Modifier.entrance(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(4.dp)
                .height(if (s.isNotBlank()) 34.dp else 20.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.verticalGradient(listOf(BrightAccent, Accent2)))
        )
        Column(Modifier.padding(start = 10.dp)) {
            Text(t, fontSize = 17.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
            if (s.isNotBlank()) Text(s, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ ambience

/** Two slow blue glows drifting behind the content. Only layer transforms change, so it is cheap. */
@Composable
internal fun AuroraGlow() {
    if (!UiPrefs.animations) return
    val t = rememberInfiniteTransition(label = "aurora")
    val a = t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(15000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "auroraA"
    )
    val b = t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(21000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "auroraB"
    )
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .size(420.dp)
                .graphicsLayer {
                    translationX = -150.dp.toPx() + a.value * 100.dp.toPx()
                    translationY = -130.dp.toPx() + a.value * 80.dp.toPx()
                }
                .background(Brush.radialGradient(listOf(Accent.copy(alpha = 0.20f), Color.Transparent)))
        )
        Box(
            Modifier
                .size(380.dp)
                .align(Alignment.BottomEnd)
                .graphicsLayer {
                    translationX = 130.dp.toPx() - b.value * 100.dp.toPx()
                    translationY = 150.dp.toPx() - b.value * 90.dp.toPx()
                }
                .background(Brush.radialGradient(listOf(Accent2.copy(alpha = 0.32f), Color.Transparent)))
        )
    }
}

/** Breathing halo behind the logo. */
@Composable
internal fun GlowHalo(modifier: Modifier = Modifier) {
    if (!UiPrefs.animations) {
        Box(modifier.background(Brush.radialGradient(listOf(Accent.copy(alpha = 0.3f), Color.Transparent)), CircleShape))
        return
    }
    val t = rememberInfiniteTransition(label = "halo")
    val g = t.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "haloGlow"
    )
    Box(
        modifier
            .graphicsLayer {
                val v = g.value
                alpha = v
                scaleX = 0.9f + 0.1f * v
                scaleY = 0.9f + 0.1f * v
            }
            .background(Brush.radialGradient(listOf(Accent.copy(alpha = 0.42f), Color.Transparent)), CircleShape)
    )
}

// ------------------------------------------------------------------ indicators

@Composable
internal fun PulseDot(color: Color, size: Dp = 8.dp, animate: Boolean = true) {
    if (animate && UiPrefs.animations) PulseDotAnimated(color, size) else Box(Modifier.size(size).background(color, CircleShape))
}

@Composable
private fun PulseDotAnimated(color: Color, size: Dp) {
    val t = rememberInfiniteTransition(label = "dot")
    val p = t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "dotP"
    )
    Canvas(Modifier.size(size * 2.2f)) {
        val outer = this.size.minDimension / 2f
        val base = outer / 2.2f
        drawCircle(color, base)
        val q = p.value
        drawCircle(color.copy(alpha = ((1f - q) * 0.45f).coerceIn(0f, 1f)), base + (outer - base) * q)
    }
}

/** Progress bar with an animated fill and a moving shine. */
@Composable
internal fun GlowProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "progress"
    )
    val shape = RoundedCornerShape(50)
    val shine = if (UiPrefs.animations) {
        rememberInfiniteTransition(label = "shine").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "shineX"
        )
    } else null
    Box(modifier.height(8.dp).clip(shape).background(Accent.copy(alpha = 0.14f))) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(f)
                .clip(shape)
                .background(Brush.horizontalGradient(listOf(Accent2, Accent, BrightAccent)))
                .drawWithContent {
                    drawContent()
                    val s = shine
                    if (s != null) {
                        val x = s.value * (size.width + 80f) - 80f
                        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent), startX = x, endX = x + 80f))
                    }
                }
        )
    }
}

@Composable
internal fun SkeletonLine(widthFraction: Float, height: Dp = 12.dp) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .shimmer()
    )
}

/** Price that flashes green / red when it ticks up / down. */
@Composable
internal fun FlashPrice(value: Double, text: String, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(value) }
    var up by remember { mutableStateOf(true) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(value) {
        if (UiPrefs.animations && last > 0.0 && value > 0.0 && value != last) {
            up = value > last
            flash.snapTo(1f)
            flash.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
        }
        last = value
    }
    val tint = if (up) Positive else Negative
    val k = flash.value.coerceIn(0f, 1f)
    Text(
        text,
        color = lerp(color, tint, k),
        fontSize = fontSize,
        fontWeight = FontWeight.Black,
        modifier = modifier
            .background(tint.copy(alpha = 0.16f * k), RoundedCornerShape(6.dp))
            .padding(horizontal = 2.dp)
    )
}

// ------------------------------------------------------------------ navigation

internal class NavItem<T>(val key: T, val icon: ImageVector, val label: String)

/** Floating bottom bar with a springy selection pill. */
@Composable
internal fun <T> MfpBottomBar(items: List<NavItem<T>>, selected: T, onSelect: (T) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFF0A1424), Color(0xFF050A12))))
                .border(1.dp, Brush.horizontalGradient(listOf(DarkLine.copy(alpha = 0.9f), Accent.copy(alpha = 0.4f), DarkLine.copy(alpha = 0.9f))), shape)
                .padding(horizontal = 2.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            items.forEach { item ->
                NavBarItem(item.icon, item.label, item.key == selected, { onSelect(item.key) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NavBarItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val sel by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (UiPrefs.animations) spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium) else tween<Float>(1),
        label = "navSel"
    )
    val k = sel.coerceIn(0f, 1f)
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .width(50.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Accent.copy(alpha = 0.28f * k)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, null,
                tint = lerp(DarkMuted, Color.White, k),
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        val z = 1f + 0.16f * sel
                        scaleX = z
                        scaleY = z
                    }
            )
        }
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
            color = lerp(DarkMuted, BrightAccent, k),
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

// ------------------------------------------------------------------ screen transitions

internal fun screenTransition(forward: Boolean): ContentTransform {
    if (!UiPrefs.animations) return EnterTransition.None togetherWith ExitTransition.None
    val dir = if (forward) 1 else -1
    return (fadeIn(tween(280, delayMillis = 70, easing = FastOutSlowInEasing)) +
        slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { full -> dir * full / 12 }) togetherWith
        (fadeOut(tween(140)) + slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { full -> -dir * full / 16 })
}

// ------------------------------------------------------------------ logo

/**
 * The app mark drawn in code (same geometry as the launcher icon): a rising line that ends in a glowing
 * "forecast" node and an arrow. On first appearance the line draws itself, then the node and arrow pop in.
 */
@Composable
internal fun MfpLogo(modifier: Modifier = Modifier, animate: Boolean = true, corner: Dp = 24.dp) {
    val intro = remember { Animatable(if (animate && UiPrefs.animations) 0f else 1f) }
    LaunchedEffect(Unit) { if (intro.value < 1f) intro.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    Canvas(modifier.clip(RoundedCornerShape(corner))) {
        val p = intro.value
        val k = this.size.minDimension / 108f
        val z = 1.3f
        fun pt(x: Float, y: Float) = Offset((54f + (x - 54f) * z) * k, (54f + (y - 54f) * z) * k)
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFF0B2A5C), Color(0xFF040E22), Color(0xFF010309)),
                center = Offset(this.size.width / 2f, this.size.height / 2f),
                radius = this.size.minDimension * 0.72f
            )
        )
        val barsA = (p / 0.35f).coerceIn(0f, 1f)
        for ((x, top) in listOf(28f to 78f, 41f to 72f, 54f to 76f, 67f to 66f)) {
            val a = pt(x, top)
            val b = pt(x + 6f, 84f)
            drawRect(BrightAccent.copy(alpha = 0.24f * barsA), topLeft = a, size = Size(b.x - a.x, b.y - a.y))
        }
        val nodeP = ((p - 0.55f) / 0.25f).coerceIn(0f, 1f)
        val arrowP = ((p - 0.75f) / 0.25f).coerceIn(0f, 1f)
        val a0 = pt(27f, 76f)
        val b0 = pt(39f, 50f)
        val c0 = pt(52f, 68f)
        val d0 = pt(64f, 42f)
        val glowR = 17f * z * k
        drawCircle(
            Brush.radialGradient(listOf(BrightAccent.copy(alpha = 0.5f * nodeP), Color.Transparent), center = d0, radius = glowR),
            radius = glowR, center = d0
        )
        val path = Path().apply { moveTo(a0.x, a0.y); lineTo(b0.x, b0.y); lineTo(c0.x, c0.y); lineTo(d0.x, d0.y) }
        val measure = PathMeasure()
        measure.setPath(path, false)
        val seg = Path()
        measure.getSegment(0f, measure.length * (p / 0.6f).coerceIn(0f, 1f), seg, true)
        drawPath(
            seg,
            Brush.linearGradient(listOf(Accent, BrightAccent), start = a0, end = d0),
            style = Stroke(width = 7.5f * z * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawLine(BrightAccent.copy(alpha = 0.7f * arrowP), d0, pt(71.12f, 35.43f), strokeWidth = 3.6f * z * k, cap = StrokeCap.Round)
        val head = Path().apply {
            val e = pt(77f, 30f)
            val h1 = pt(74.51f, 39.1f)
            val h2 = pt(67.73f, 31.75f)
            moveTo(e.x, e.y); lineTo(h1.x, h1.y); lineTo(h2.x, h2.y); close()
        }
        drawPath(head, Color(0xFF9CCBFF).copy(alpha = arrowP))
        drawCircle(BrightAccent.copy(alpha = 0.5f * nodeP), radius = 6.6f * z * k, center = d0, style = Stroke(width = 1.6f * z * k))
        drawCircle(Color.White.copy(alpha = nodeP), radius = 4.2f * z * k * (0.6f + 0.4f * nodeP), center = d0)
    }
}
