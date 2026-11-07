package com.loopa.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette

/**
 * The LOOPA L/O mark, drawn from the supplied brand reference
 * (docs/brand/loopa-logo-reference.png) on a 1932-unit grid:
 *  - L stem 298..671 x 298..1260, foot 298..1634 x 1260..1634 with a rounded outer corner
 *  - O ring centred at (1155, 777), outer radius 479, inner radius 240
 */
object LoopaMark {
    const val GRID = 1932f

    fun lPath(s: Float, origin: Offset = Offset.Zero): Path = Path().apply {
        fun x(v: Float) = origin.x + v * s
        fun y(v: Float) = origin.y + v * s
        moveTo(x(298f), y(298f))
        lineTo(x(671f), y(298f))
        lineTo(x(671f), y(1260f))
        lineTo(x(1634f), y(1260f))
        lineTo(x(1634f), y(1634f))
        lineTo(x(671f), y(1634f))
        // rounded outer corner (quarter circle, radius 373)
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(x(298f), y(887f), x(1044f), y(1633f)),
            startAngleDegrees = 90f, sweepAngleDegrees = 90f, forceMoveTo = false,
        )
        close()
    }

    fun oPath(s: Float, origin: Offset = Offset.Zero): Path = Path().apply {
        fillType = PathFillType.EvenOdd
        val c = Offset(origin.x + 1155f * s, origin.y + 777f * s)
        addOval(androidx.compose.ui.geometry.Rect(c, 479f * s))
        addOval(androidx.compose.ui.geometry.Rect(c, 240f * s))
    }
}

/**
 * Draws the mark. [lProgress]/[oProgress] in 0..1 drive the splash animation
 * (L rises in, O rolls in); [oRotation] spins the ring.
 */
fun DrawScope.drawLoopaMark(
    markColor: Color = LoopaPalette.Mint,
    background: Color? = LoopaPalette.Lavender,
    cornerRadiusFraction: Float = 0.22f,
    lProgress: Float = 1f,
    oProgress: Float = 1f,
    oRotation: Float = 0f,
) {
    val side = size.minDimension
    val s = side / LoopaMark.GRID
    val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
    if (background != null) {
        drawPath(
            Path().apply {
                addRoundRect(RoundRect(origin.x, origin.y, origin.x + side, origin.y + side, CornerRadius(side * cornerRadiusFraction)))
            },
            background,
        )
    }
    if (lProgress > 0f) {
        val dy = (1f - lProgress) * side * 0.25f
        drawPath(LoopaMark.lPath(s, origin.copy(y = origin.y + dy)), markColor.copy(alpha = markColor.alpha * lProgress.coerceIn(0f, 1f)))
    }
    if (oProgress > 0f) {
        val center = Offset(origin.x + 1155f * s, origin.y + 777f * s)
        scale(oProgress.coerceIn(0f, 1.2f), pivot = center) {
            rotate(oRotation, pivot = center) {
                drawPath(LoopaMark.oPath(s, origin), markColor)
            }
        }
    }
}

@Composable
fun LoopaLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    withBackground: Boolean = true,
    markColor: Color = LoopaPalette.Mint,
) {
    Canvas(modifier.size(size)) {
        drawLoopaMark(markColor = markColor, background = if (withBackground) LoopaPalette.Lavender else null)
    }
}

/** Animated logo used on the splash/loading screens. */
@Composable
fun AnimatedLoopaLogo(modifier: Modifier = Modifier, size: Dp = 140.dp, onFinished: () -> Unit = {}) {
    val l = remember { Animatable(0f) }
    val o = remember { Animatable(0f) }
    val spin = remember { Animatable(-180f) }
    LaunchedEffect(Unit) {
        l.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        o.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
        onFinished()
    }
    LaunchedEffect(Unit) { spin.animateTo(0f, tween(900, delayMillis = 300, easing = FastOutSlowInEasing)) }
    Canvas(modifier.size(size)) {
        drawLoopaMark(lProgress = l.value, oProgress = o.value, oRotation = spin.value)
    }
}

@Composable
fun LoopaWordmark(modifier: Modifier = Modifier, showSlogan: Boolean = true) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("LOOPA", style = MaterialTheme.typography.displayMedium, color = Loopa.colors.text)
        if (showSlogan) {
            Text(
                "Play together. Think faster.",
                style = MaterialTheme.typography.bodyMedium,
                color = Loopa.colors.textSoft,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Suppress("unused")
private val previewSize = Size(1932f, 1932f)
