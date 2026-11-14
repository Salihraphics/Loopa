package com.loopa.feature.game.modes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.game.modes.TavlaLogic
import com.loopa.core.model.ModeState
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette

private val MineColor = Color(0xFFFFF3E0)
private val TheirsColor = Color(0xFF4E342E)

/**
 * Tavla. The board is shown from the player's own side (home = points 1-6, bottom right).
 * The player picks checker + die for each move; the whole turn is sent at once and the
 * server checks it (must use as many dice as possible, the larger die rule).
 */
@Composable
fun TavlaScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val p = mode.pub
    val players = (p / "players").strs()
    val side = (mode.me / "side").i() ?: 1
    val turn = (p / "turn").s()
    val over = mode.over
    val board = (p / "b").ints().takeIf { it.size == 24 } ?: List(24) { 0 }
    val bars = (p / "bar").ints(); val offs = (p / "off").ints()
    val dice = (p / "dice").ints()
    val maxUse = (p / "max_use").i() ?: 0
    val blocked = (p / "blocked").b()
    val myTurn = turn == ctx.me && !over && !ctx.isSpectator
    val score = (p / "score").o(); val target = (p / "target").i() ?: 3

    val start = TavlaLogic.Pos(TavlaLogic.relative(board, side), bars.getOrElse(side - 1) { 0 }, bars.getOrElse(2 - side) { 0 }, offs.getOrElse(side - 1) { 0 })
    val moves = remember(mode.round?.id, turn, dice) { mutableStateListOf<Pair<Int, Int>>() }
    var selected by remember(mode.round?.id, turn, dice) { mutableStateOf<Int?>(null) }
    // apply the planned moves locally (display only)
    var pos = start
    val left = dice.toMutableList()
    moves.forEach { (f, d) -> TavlaLogic.step(pos, f, d)?.let { pos = it }; left.remove(d) }
    val sources = if (myTurn && moves.size < maxUse) TavlaLogic.sources(pos, left) else emptyList()
    LaunchedEffect(sources.size, selected) { if (selected != null && selected !in sources) selected = null }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayerChips(ctx, players, turn, value = { u -> "${(score / u).i() ?: 0}/$target" })
        val winner = (p / "winner").s()
        TurnBanner(ctx, turn, over,
            doneText = if (winner == ctx.me) "Oyunu kazandın${if ((p / "mars").b()) " — mars!" else ""}" else "${ctx.name(winner)} kazandı${if ((p / "mars").b()) " — mars" else ""}",
            extra = if (blocked && myTurn) "Oynayacak hamlen yok." else "Oyun ${(p / "game").i() ?: 1} · $target puana ilk ulaşan kazanır")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            val used = dice.toMutableList().also { d -> left.forEach { d.remove(it) } }
            val usedFlags = dice.indices.map { false }.toMutableList()
            used.forEach { u -> val k = dice.indices.firstOrNull { dice[it] == u && !usedFlags[it] }; if (k != null) usedFlags[k] = true }
            dice.forEachIndexed { k, d -> DieFace(d, used = usedFlags[k]) }
        }
        TavlaBoard(pos, sources = sources, selected = selected, onPoint = { pt -> if (pt in sources) selected = pt })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Bar: sen ${pos.bar} · rakip ${pos.obar}", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft,
                modifier = if (25 in sources) Modifier.clip(RoundedCornerShape(8.dp)).background(LoopaPalette.Butter).clickable { selected = 25 }.padding(4.dp) else Modifier)
            Text("Toplanan: sen ${pos.off} · rakip ${offs.getOrElse(2 - side) { 0 }}", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
        }
        if (myTurn) {
            if (blocked) {
                GameButton("Pas geç", tag = "tv_pass", enabled = ui.busy == null) { ui.act("pass", payload()) {} }
            } else {
                selected?.let { from ->
                    ActionRow {
                        Text(if (from == 25) "Bardan:" else "$from. haneden:", color = Loopa.colors.text)
                        TavlaLogic.diceFor(pos, from, left).forEach { d ->
                            GameButton("$d", tag = "tv_die_$d", color = LoopaPalette.Sky) { moves.add(from to d); selected = null }
                        }
                    }
                }
                Text("Hamle ${moves.size}/$maxUse", style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.text, modifier = Modifier.align(Alignment.CenterHorizontally))
                if (moves.size < maxUse && sources.isEmpty()) {
                    Text("Bu sırayla tüm zarlar oynanamıyor; geri alıp başka bir sıra dene.", color = LoopaPalette.Error, style = MaterialTheme.typography.bodySmall)
                }
                ActionRow {
                    GameButton("Geri al", primary = false, enabled = moves.isNotEmpty()) { moves.removeAt(moves.lastIndex) }
                    GameButton("Hamleyi oyna", tag = "tv_send", enabled = moves.size == maxUse && ui.busy == null, color = LoopaPalette.Mint) {
                        ui.act("move", payload("moves" to jsonList(moves.map { (f, d) -> payload("from" to f, "die" to d) }))) { moves.clear() }
                    }
                }
                WaitingNote("Önce pulu, sonra zarı seç. Zarlardan olabildiğince çoğunu oynamalısın; yalnızca biri oynanabiliyorsa büyüğü.")
            }
        }
    }
}

@Composable
private fun TavlaBoard(pos: TavlaLogic.Pos, sources: List<Int>, selected: Int?, onPoint: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth().aspectRatio(1.15f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF8D6E63))
            .border(3.dp, Color(0xFF5D4037), RoundedCornerShape(16.dp)).padding(6.dp).testTag("tv_board"),
    ) {
        Row(Modifier.weight(1f).fillMaxWidth()) { (13..24).forEach { PointColumn(it, pos, top = true, it in sources, it == selected, onPoint) ; if (it == 18) BarGap() } }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) { (12 downTo 1).forEach { PointColumn(it, pos, top = false, it in sources, it == selected, onPoint); if (it == 7) BarGap() } }
    }
}

@Composable
private fun RowScope.BarGap() { Spacer(Modifier.width(6.dp).fillMaxHeight().background(Color(0xFF5D4037))) }

@Composable
private fun RowScope.PointColumn(point: Int, pos: TavlaLogic.Pos, top: Boolean, source: Boolean, selected: Boolean, onPoint: (Int) -> Unit) {
    val n = pos.b[point - 1]
    val shade = if (point % 2 == 0) Color(0xFFD7CCC8) else Color(0xFFA1887F)
    Column(
        Modifier.weight(1f).fillMaxHeight().padding(horizontal = 1.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) LoopaPalette.Butter else if (source) LoopaPalette.Mint.copy(alpha = 0.7f) else shade.copy(alpha = 0.55f))
            .clickable(enabled = source) { onPoint(point) }.testTag("tv_point_$point"),
        verticalArrangement = if (top) Arrangement.Top else Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!top) Spacer(Modifier.weight(1f))
        if (top) Text("$point", fontSize = 8.sp, color = LoopaPalette.Ink.copy(alpha = 0.6f))
        val count = kotlin.math.abs(n)
        repeat(minOf(count, 5)) { k ->
            Box(Modifier.padding(vertical = 0.5.dp).fillMaxWidth(0.9f).aspectRatio(1f).clip(CircleShape)
                .background(if (n > 0) MineColor else TheirsColor).border(1.dp, LoopaPalette.Ink.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center) {
                if (k == 4 && count > 5) Text("$count", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (n > 0) LoopaPalette.Ink else LoopaPalette.Cream)
            }
        }
        if (!top) Text("$point", fontSize = 8.sp, color = LoopaPalette.Ink.copy(alpha = 0.6f))
        if (top) Spacer(Modifier.weight(1f))
    }
}
