package com.loopa.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.loopa.core.game.GameCardKind
import com.loopa.core.game.GameCardStatus
import com.loopa.core.game.GameModeInfo
import com.loopa.core.game.GameModes
import com.loopa.core.model.GameCatalog
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import com.loopa.core.ui.theme.Radii
import com.loopa.core.ui.theme.Spacing

// Shared, data-driven catalogue components (Home and Oyunlar). Everything comes from
// GameModes (static info) + GameCards.status (server access); nothing is game-specific here.

/** The game shown large at the top: this week's free game, else the always-free one. */
fun featuredGame(catalog: GameCatalog?): GameModeInfo {
    val weekly = catalog?.rotation?.takeIf { it.active }?.freeModes?.firstNotNullOfOrNull { id -> GameModes.all.firstOrNull { it.id == id } }
    return weekly ?: GameModes.info(GameModes.ISIM_SEHIR)
}

/** Large card: artwork banner, name, description, players and the main action. */
@Composable
fun FeaturedGame(info: GameModeInfo, status: GameCardStatus, creating: Boolean, onCreate: () -> Unit, onOpenPlus: () -> Unit,
                 showPlusAction: Boolean, weekly: Boolean, modifier: Modifier = Modifier) {
    LoopaSurface(modifier.fillMaxWidth().testTag("featured_game"), padding = PaddingValues(0.dp)) {
        Box {
            GameArtwork(info.id, Modifier.fillMaxWidth().height(150.dp), label = "${info.name} görseli")
            Pill(if (weekly) "Bu haftanın oyunu" else "Öne çıkan", Color.White, Modifier.padding(Spacing.md))
        }
        Column(Modifier.padding(Spacing.lg)) {
            Text(info.name, style = MaterialTheme.typography.headlineSmall, color = Loopa.colors.text)
            Text(info.tagline, style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft)
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                MetaPill(Icons.Rounded.Groups, info.players)
                Pill(info.category.label, gameVisual(info.id).color.copy(alpha = 0.6f))
                StatusBadge(status)
            }
            Spacer(Modifier.height(Spacing.md))
            when {
                status.canStart -> LoopaButton("Oda kur", onCreate, icon = Icons.Rounded.AddCircle, color = LoopaPalette.Mint, loading = creating,
                    modifier = Modifier.fillMaxWidth().testTag("featured_create"), sfx = Sfx.JOIN)
                status.kind == GameCardKind.LOCKED_PLUS && showPlusAction -> LoopaButton("LOOPA Plus ile aç", onOpenPlus, icon = Icons.Rounded.AutoAwesome,
                    color = LoopaPalette.Lavender, modifier = Modifier.fillMaxWidth())
                else -> Text(status.explanation, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
            }
        }
    }
}

@Composable
fun MetaPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.clip(Radii.pill).background(Loopa.colors.cardHigh).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Loopa.colors.textSoft, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Loopa.colors.text)
    }
}

/** Balanced illustrated card: artwork, name, category, players, access status and the action. */
@Composable
fun GameCard(
    info: GameModeInfo,
    status: GameCardStatus,
    creating: Boolean,
    onCreate: () -> Unit,
    onOpenPlus: () -> Unit,
    showPlusAction: Boolean,
    modifier: Modifier = Modifier,
) {
    val dim = status.kind != GameCardKind.PLAYABLE
    LoopaSurface(modifier.fillMaxWidth().testTag("game_card_${info.id}"), padding = PaddingValues(Spacing.md)) {
        Row {
            Box {
                GameArtwork(info.id, Modifier.size(92.dp).clip(Radii.md), label = "${info.name} görseli")
                if (dim) Box(Modifier.size(92.dp).clip(Radii.md).background(Loopa.colors.background.copy(alpha = 0.35f)))
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(info.name, style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text, modifier = Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(Spacing.xs))
                    StatusBadge(status)
                }
                Text("${info.category.label} · ${info.players}", style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
                Spacer(Modifier.height(Spacing.xs))
                Text(status.explanation, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("game_status_${info.id}"))
            }
        }
        if (status.freeThisWeek || status.freeNextWeek) {
            Spacer(Modifier.height(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status.freeThisWeek) Pill("Bu hafta ücretsiz", LoopaPalette.Butter)
                if (status.freeNextWeek) Pill("Gelecek hafta ücretsiz", LoopaPalette.Sky)
            }
        }
        when {
            status.canStart -> {
                Spacer(Modifier.height(Spacing.md))
                LoopaButton("Oda kur", onCreate, icon = Icons.Rounded.AddCircle, color = gameVisual(info.id).color, loading = creating, compact = true,
                    modifier = Modifier.fillMaxWidth().testTag("game_create_${info.id}"), sfx = Sfx.JOIN)
            }
            status.kind == GameCardKind.LOCKED_PLUS && showPlusAction -> {
                Spacer(Modifier.height(Spacing.md))
                LoopaButton("LOOPA Plus'ı incele", onOpenPlus, icon = Icons.Rounded.AutoAwesome, color = LoopaPalette.Lavender, compact = true,
                    modifier = Modifier.fillMaxWidth().testTag("game_plus_${info.id}"))
            }
        }
    }
}

@Composable
fun StatusBadge(status: GameCardStatus) {
    val (bg, icon) = when (status.kind) {
        GameCardKind.PLAYABLE -> Modifier.background(LoopaPalette.Mint) to null
        GameCardKind.LOCKED_PLUS -> Modifier.background(PlusGradient) to Icons.Rounded.Lock
        GameCardKind.COMING_SOON -> Modifier.background(Loopa.colors.cardHigh) to Icons.Rounded.Schedule
        GameCardKind.UNAVAILABLE -> Modifier.background(Loopa.colors.cardHigh) to null
    }
    Row(Modifier.clip(Radii.pill).then(bg).border(1.dp, Loopa.colors.outline.copy(alpha = 0.5f), Radii.pill).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = LoopaPalette.Ink, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(3.dp)) }
        Text(status.badge, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = if (status.kind == GameCardKind.PLAYABLE || status.kind == GameCardKind.LOCKED_PLUS) LoopaPalette.Ink else Loopa.colors.text)
    }
}
