package com.loopa.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.HowToVote
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush as GradientBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.loopa.core.model.PlanTier
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette

/** The signature LOOPA Plus gradient (pastel lavender → peach → butter). */
val PlusGradient: GradientBrush
    get() = GradientBrush.linearGradient(listOf(LoopaPalette.Lavender, LoopaPalette.Peach, LoopaPalette.Butter))

/** Plan label shown on the profile, in settings and on the Plus screen. Pass null while unknown. */
@Composable
fun PlanBadge(tier: PlanTier?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    val base = modifier.testTag("plan_badge").clip(shape)
    val (bgMod, text, fg, icon) = when (tier) {
        PlanTier.PLUS -> Quad(Modifier.background(PlusGradient), "LOOPA Plus", LoopaPalette.Ink, Icons.Rounded.AutoAwesome)
        PlanTier.ADMIN -> Quad(Modifier.background(LoopaPalette.Ink), "Admin", Color.White, Icons.Rounded.AdminPanelSettings)
        PlanTier.FREE -> Quad(Modifier.background(Loopa.colors.cardHigh), "Ücretsiz", Loopa.colors.text, null)
        null -> Quad(Modifier.background(Loopa.colors.cardHigh), "…", Loopa.colors.textSoft, null)
    }
    Row(
        base.then(bgMod).border(1.5.dp, darken(LoopaPalette.Lavender).copy(alpha = 0.35f), shape).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

private data class Quad(val a: Modifier, val b: String, val c: Color, val d: ImageVector?)

/** Icon and accent colour of each game. */
data class GameVisual(val icon: ImageVector, val color: Color)

fun gameVisual(modeId: String): GameVisual = when (modeId) {
    "isim_sehir" -> GameVisual(Icons.Rounded.TextFields, LoopaPalette.Mint)
    "gizli_kelime" -> GameVisual(Icons.Rounded.VpnKey, LoopaPalette.Lavender)
    "kelime_zinciri" -> GameVisual(Icons.Rounded.Link, LoopaPalette.Sky)
    "bilgi_arenasi" -> GameVisual(Icons.Rounded.Quiz, LoopaPalette.Butter)
    "iki_dogru_bir_yalan" -> GameVisual(Icons.Rounded.FactCheck, LoopaPalette.Peach)
    "ciz_tahmin" -> GameVisual(Icons.Rounded.Brush, LoopaPalette.Mint)
    "en_muhtemel_kim" -> GameVisual(Icons.Rounded.HowToVote, LoopaPalette.Lavender)
    "anlat_bakalim" -> GameVisual(Icons.Rounded.RecordVoiceOver, LoopaPalette.Sky)
    "cizim_telefonu" -> GameVisual(Icons.Rounded.Gesture, LoopaPalette.Butter)
    "sessiz_sinema" -> GameVisual(Icons.Rounded.TheaterComedy, LoopaPalette.Peach)
    else -> GameVisual(Icons.Rounded.TextFields, LoopaPalette.Lavender)
}

/** Rounded tile with the game's icon. */
@Composable
fun GameIcon(modeId: String, modifier: Modifier = Modifier, size: Dp = 52.dp, dimmed: Boolean = false) {
    val v = gameVisual(modeId)
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f))
            .background(if (dimmed) v.color.copy(alpha = 0.45f) else v.color)
            .border(2.dp, darken(v.color).copy(alpha = 0.4f), RoundedCornerShape(size * 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(v.icon, null, tint = LoopaPalette.Ink.copy(alpha = if (dimmed) 0.55f else 1f), modifier = Modifier.size(size * 0.5f))
    }
}
