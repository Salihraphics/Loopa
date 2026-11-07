package com.loopa.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.ui.R

/** LOOPA brand palette. */
object LoopaPalette {
    val Lavender = Color(0xFFB9A5E8)
    val Mint = Color(0xFF9DDCC5)
    val Peach = Color(0xFFFFB9A5)
    val Butter = Color(0xFFFFE39A)
    val Sky = Color(0xFFA9D5F2)
    val Cream = Color(0xFFFFF9F0)
    val Ink = Color(0xFF353344)
    val InkSoft = Color(0xFF777184)
    val Success = Color(0xFF65B995)
    val Error = Color(0xFFE78383)

    val LavenderDeep = Color(0xFF8E74D6)
    val MintDeep = Color(0xFF5FB896)
    val PeachDeep = Color(0xFFF08A6C)
    val ButterDeep = Color(0xFFF2C14E)
    val SkyDeep = Color(0xFF6FB3E0)

    // Dark environment surfaces (keeps the pastel identity on a soft plum night)
    val Night = Color(0xFF1E1B2B)
    val NightCard = Color(0xFF2A2639)
    val NightCardHigh = Color(0xFF35304A)
    val NightText = Color(0xFFF4EFFA)
    val NightTextSoft = Color(0xFFB7B0C6)

    /** Rotating accent colors for players, categories, chips. */
    val accents = listOf(Lavender, Mint, Peach, Butter, Sky)
    fun accent(index: Int): Color = accents[((index % accents.size) + accents.size) % accents.size]
    fun accentFor(key: String): Color = accent(key.hashCode())
}

@Immutable
data class LoopaColors(
    val background: Color,
    val card: Color,
    val cardHigh: Color,
    val text: Color,
    val textSoft: Color,
    val onAccent: Color,
    val outline: Color,
    val isDark: Boolean,
)

private val LightLoopa = LoopaColors(
    background = LoopaPalette.Cream,
    card = Color.White,
    cardHigh = Color(0xFFF6F0FF),
    text = LoopaPalette.Ink,
    textSoft = LoopaPalette.InkSoft,
    onAccent = LoopaPalette.Ink,
    outline = Color(0xFFE9E2F3),
    isDark = false,
)

private val DarkLoopa = LoopaColors(
    background = LoopaPalette.Night,
    card = LoopaPalette.NightCard,
    cardHigh = LoopaPalette.NightCardHigh,
    text = LoopaPalette.NightText,
    textSoft = LoopaPalette.NightTextSoft,
    onAccent = LoopaPalette.Ink,
    outline = Color(0xFF433D59),
    isDark = true,
)

val LocalLoopaColors = staticCompositionLocalOf { LightLoopa }

@OptIn(ExperimentalTextApi::class)
private fun variableFont(res: Int, weight: Int) = Font(
    res, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(ExperimentalTextApi::class)
/** Display face: Baloo 2 (full Turkish coverage: ğ Ğ ş Ş ı İ). */
val DisplayFamily = FontFamily(
    variableFont(R.font.baloo2, 600),
    variableFont(R.font.baloo2, 700),
    variableFont(R.font.baloo2, 800),
)

@OptIn(ExperimentalTextApi::class)
val NunitoFamily = FontFamily(
    variableFont(R.font.nunito, 400),
    variableFont(R.font.nunito, 600),
    variableFont(R.font.nunito, 700),
    variableFont(R.font.nunito, 800),
    variableFont(R.font.nunito, 900),
)

private val LoopaTypography = Typography(
    displayLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(700), fontSize = 64.sp, lineHeight = 68.sp),
    displayMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(700), fontSize = 44.sp, lineHeight = 50.sp),
    displaySmall = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(700), fontSize = 34.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(600), fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(600), fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(600), fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight(600), fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = NunitoFamily, fontWeight = FontWeight(800), fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = NunitoFamily, fontWeight = FontWeight(800), fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = NunitoFamily, fontWeight = FontWeight(600), fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = NunitoFamily, fontWeight = FontWeight(600), fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = NunitoFamily, fontWeight = FontWeight(600), fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = NunitoFamily, fontWeight = FontWeight(900), fontSize = 17.sp, lineHeight = 22.sp),
    labelMedium = TextStyle(fontFamily = NunitoFamily, fontWeight = FontWeight(800), fontSize = 14.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = NunitoFamily, fontWeight = FontWeight(800), fontSize = 12.sp, lineHeight = 16.sp),
)

private val LoopaShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun LoopaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val loopa = if (darkTheme) DarkLoopa else LightLoopa
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = LoopaPalette.Lavender, onPrimary = LoopaPalette.Ink,
            secondary = LoopaPalette.Mint, onSecondary = LoopaPalette.Ink,
            tertiary = LoopaPalette.Peach, onTertiary = LoopaPalette.Ink,
            background = loopa.background, onBackground = loopa.text,
            surface = loopa.card, onSurface = loopa.text,
            surfaceVariant = loopa.cardHigh, onSurfaceVariant = loopa.textSoft,
            surfaceContainer = loopa.card, surfaceContainerHigh = loopa.cardHigh,
            surfaceContainerLow = loopa.card, surfaceContainerHighest = loopa.cardHigh,
            error = LoopaPalette.Error, onError = LoopaPalette.Ink,
            outline = loopa.outline, outlineVariant = loopa.outline,
        )
    } else {
        lightColorScheme(
            primary = LoopaPalette.LavenderDeep, onPrimary = Color.White,
            primaryContainer = LoopaPalette.Lavender, onPrimaryContainer = LoopaPalette.Ink,
            secondary = LoopaPalette.MintDeep, onSecondary = Color.White,
            secondaryContainer = LoopaPalette.Mint, onSecondaryContainer = LoopaPalette.Ink,
            tertiary = LoopaPalette.PeachDeep, onTertiary = Color.White,
            tertiaryContainer = LoopaPalette.Peach, onTertiaryContainer = LoopaPalette.Ink,
            background = loopa.background, onBackground = loopa.text,
            surface = loopa.card, onSurface = loopa.text,
            surfaceVariant = loopa.cardHigh, onSurfaceVariant = loopa.textSoft,
            surfaceContainer = loopa.card, surfaceContainerHigh = loopa.cardHigh,
            surfaceContainerLow = loopa.card, surfaceContainerHighest = loopa.cardHigh,
            error = LoopaPalette.Error, onError = Color.White,
            outline = loopa.outline, outlineVariant = loopa.outline,
        )
    }
    CompositionLocalProvider(LocalLoopaColors provides loopa) {
        MaterialTheme(colorScheme = scheme, typography = LoopaTypography, shapes = LoopaShapes, content = content)
    }
}

object Loopa {
    val colors: LoopaColors @Composable get() = LocalLoopaColors.current
}
