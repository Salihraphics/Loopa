package com.loopa.core.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * LOOPA design tokens. Colours stay in [LoopaPalette] / [LoopaColors]; everything else that used to be
 * typed by hand on each screen (spacing, radii, borders, elevation, motion) lives here.
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    /** Horizontal page gutter on phones. */
    val gutter = 20.dp
    /** Space between page sections. */
    val section = 24.dp
}

object Radii {
    val sm = RoundedCornerShape(12.dp)
    val md = RoundedCornerShape(18.dp)
    val lg = RoundedCornerShape(24.dp)
    val xl = RoundedCornerShape(30.dp)
    val pill = RoundedCornerShape(50)
}

object Borders {
    val hairline = 1.dp
    val regular = 1.5.dp
    val strong = 2.dp
    val selected = 3.dp
}

object Elevation {
    val none = 0.dp
    val low = 2.dp
    val raised = 6.dp
}

/** Minimum touch target (Material accessibility guidance). */
val MinTouch = 48.dp

/** Animation language: short, eased, never in the way of input. */
object Motion {
    const val FAST = 140
    const val MEDIUM = 240
    const val SLOW = 420

    fun <T> standard(reduced: Boolean, ms: Int = MEDIUM): AnimationSpec<T> = if (reduced) snap() else tween(ms, easing = FastOutSlowInEasing)
    fun <T> bouncy(reduced: Boolean): AnimationSpec<T> = if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
    fun <T> press(): AnimationSpec<T> = spring(stiffness = 900f)
}

/** True when the user switched animations off in the system settings (animator scale 0). */
val LocalReducedMotion = compositionLocalOf { false }

@Composable
internal fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

object LoopaMotion {
    val reduced: Boolean @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}
