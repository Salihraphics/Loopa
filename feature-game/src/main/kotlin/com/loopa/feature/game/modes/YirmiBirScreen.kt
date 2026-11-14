package com.loopa.feature.game.modes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.game.modes.CardFace
import com.loopa.core.model.ModeState
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.serialization.json.JsonElement

/** 21 (1v1). No betting of any kind: the only actions are "Kart çek" and "Dur". */
@Composable
fun YirmiBirScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val p = mode.pub
    val players = (p / "players").strs()
    val turn = (p / "turn").s()
    val over = mode.over
    val opp = players.firstOrNull { it != ctx.me } ?: players.getOrNull(1)
    val myHand = (mode.me / "hand").a()
    val myTurn = turn == ctx.me && !over && !ctx.isSpectator
    val wins = (p / "wins").o()
    val need = (p / "need").i() ?: 3
    val stood = (p / "stood").strs()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PlayerChips(ctx, players, turn, value = { u -> "${(wins / u).i() ?: 0}/$need" })
        val winner = (p / "winner").s()
        TurnBanner(ctx, turn, over,
            doneText = when { !over -> null; winner == null -> "Berabere"; winner == ctx.me -> "Eli kazandın!"; else -> "${ctx.name(winner)} eli kazandı" },
            extra = "El ${(p / "hand").i() ?: 1} · ${need} el kazanan maçı alır")
        // opponent: only the first card until the hand is over
        val oppId = if (ctx.isSpectator) players.getOrNull(0) else opp
        HandRow(label = if (ctx.isSpectator) ctx.name(oppId) else "${ctx.name(oppId)}${if (oppId in stood) " · durdu" else ""}",
            cards = (p / "shown").o()[oppId.orEmpty()].a(), hidden = ((p / "counts").o()[oppId.orEmpty()].i() ?: 0) - ((p / "shown").o()[oppId.orEmpty()].a().size),
            total = if (over) ((p / "totals").o()[oppId.orEmpty()]).i() else null)
        if (ctx.isSpectator) {
            val second = players.getOrNull(1)
            HandRow(ctx.name(second), (p / "shown").o()[second.orEmpty()].a(),
                ((p / "counts").o()[second.orEmpty()].i() ?: 0) - (p / "shown").o()[second.orEmpty()].a().size,
                if (over) (p / "totals").o()[second.orEmpty()].i() else null)
        } else {
            val total = (mode.me / "total").i()
            HandRow(label = "Senin elin${if (ctx.me in stood) " · durdun" else ""}", cards = myHand, hidden = 0, total = total, mine = true)
            if (total != null && total > 21 && !over) Text("21'i geçtin.", color = LoopaPalette.Error, style = MaterialTheme.typography.bodyMedium)
        }
        if (myTurn) {
            ActionRow {
                GameButton("Kart çek", enabled = ui.busy == null, tag = "yb_hit", color = LoopaPalette.Mint) { ui.act("hit", payload()) {} }
                GameButton("Dur", enabled = ui.busy == null, tag = "yb_stand", color = LoopaPalette.Peach) { ui.act("stand", payload()) {} }
            }
        }
        WaitingNote("Bahis yok: kaybetmek hiçbir şey eksiltmez. Süre biterse otomatik olarak durursun.")
    }
}

@Composable
private fun HandRow(label: String, cards: List<JsonElement>, hidden: Int, total: Int?, mine: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(label)
            total?.let { Text("  · $it", style = MaterialTheme.typography.labelLarge, color = if (it > 21) LoopaPalette.Error else Loopa.colors.text, fontWeight = FontWeight.Bold) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag(if (mine) "yb_my_hand" else "yb_hand")) {
            cards.forEach { c -> PlayingCard((c / "r").i() ?: 0, (c / "s").s().orEmpty()) }
            repeat(hidden.coerceAtLeast(0)) { CardBack() }
        }
    }
}

@Composable
internal fun PlayingCard(rank: Int, suit: String, width: androidx.compose.ui.unit.Dp = 54.dp) {
    val red = CardFace.isRed(suit)
    Box(
        Modifier.size(width, width * 1.4f).clip(RoundedCornerShape(10.dp)).background(LoopaPalette.Cream)
            .border(1.5.dp, LoopaPalette.Ink.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(CardFace.rank(rank), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (red) Color(0xFFD32F2F) else LoopaPalette.Ink)
            Text(CardFace.suit(suit), fontSize = 20.sp, color = if (red) Color(0xFFD32F2F) else LoopaPalette.Ink)
        }
    }
}

@Composable
internal fun CardBack(width: androidx.compose.ui.unit.Dp = 54.dp) {
    Box(Modifier.size(width, width * 1.4f).clip(RoundedCornerShape(10.dp)).background(LoopaPalette.LavenderDeep)
        .border(1.5.dp, LoopaPalette.Ink.copy(alpha = 0.5f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Text("L", color = LoopaPalette.Cream, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}
