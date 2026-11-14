package com.loopa.feature.game.modes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.loopa.core.model.ModeState
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette

private fun groupColor(g: Int): Color = when (g) {
    1 -> Color(0xFF8D6E63); 2 -> Color(0xFF81D4FA); 3 -> Color(0xFFF48FB1); 4 -> Color(0xFFFFB74D)
    5 -> Color(0xFFE57373); 6 -> Color(0xFFFFF176); 7 -> Color(0xFF81C784); 8 -> Color(0xFF7986CB); else -> Color(0xFFBDBDBD)
}

/** Milyoner: original property board game. All money is in-game only (no real value). */
@Composable
fun MilyonerScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val p = mode.pub
    val order = (p / "order").strs()
    val players = (p / "players")
    val owner = (p / "owner").o()
    val houses = (p / "houses").o()
    val mortgaged = (p / "mortgaged").strs().toSet()
    val turn = (p / "turn").s()
    val stage = (p / "stage").s()
    val over = mode.over
    val myTurn = turn == ctx.me && !over && !ctx.isSpectator
    val me = players / ctx.me
    val board = MilyonerBoard.squares
    val ownerIdx: (String?) -> Int = { u -> order.indexOf(u) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayerChips(ctx, order, turn, value = { u -> if ((players / u / "out").b()) "iflas" else "₺${(players / u / "cash").i() ?: 0}" },
            color = { u -> seatColor(ownerIdx(u)) })
        val winner = (p / "winner").s()
        TurnBanner(ctx, turn, over, doneText = if (winner == ctx.me) "Milyoner sensin!" else "${ctx.name(winner)} kazandı",
            extra = "Tur ${(p / "round").i() ?: 1}/${(p / "max_rounds").i() ?: 30}" +
                ((p / "dice").ints().takeIf { it.size == 2 }?.let { " · zar ${it[0]}+${it[1]}" } ?: ""))
        if (!ctx.isSpectator && me != null) {
            val pos = (me / "pos").i() ?: 0
            Text("Konumun: ${board.getOrNull(pos)?.name ?: "-"}${if (((me / "jail").i() ?: 0) > 0) " (nezarette)" else ""} · net değer ₺${(mode.me / "net_worth").i() ?: 0}",
                style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.text)
        }
        if (myTurn) {
            val inJail = ((me / "jail").i() ?: 0) > 0
            when (stage) {
                "roll" -> ActionRow {
                    GameButton("Zar at", tag = "my_roll", enabled = ui.busy == null, color = LoopaPalette.Mint) { ui.act("roll", payload()) {} }
                    if (inJail) GameButton("₺50 öde", primary = false, tag = "my_pay_jail") { ui.act("pay_jail", payload()) {} }
                    if (inJail && ((me / "free").i() ?: 0) > 0) GameButton("Kartı kullan", primary = false) { ui.act("use_free", payload()) {} }
                }
                "buy" -> {
                    val sq = (p / "offer").i() ?: 0
                    val b = board.getOrNull(sq)
                    BigCardMini(b?.name ?: "", "Fiyat ₺${b?.price ?: 0}", b?.group ?: 0)
                    ActionRow {
                        GameButton("Satın al", tag = "my_buy", enabled = ui.busy == null, color = LoopaPalette.Mint) { ui.act("buy", payload()) {} }
                        GameButton("Alma", tag = "my_decline", primary = false) { ui.act("decline", payload()) {} }
                    }
                }
                "act" -> GameButton("Turu bitir", tag = "my_end", enabled = ui.busy == null, color = LoopaPalette.Peach) { ui.act("end_turn", payload()) {} }
            }
        }
        // own properties with build / sell / mortgage / redeem
        if (!ctx.isSpectator) {
            val mine = board.indices.filter { (owner / it.toString()).s() == ctx.me }
            if (mine.isNotEmpty()) SectionTitle("Mülklerin")
            mine.forEach { i ->
                val b = board[i]; val h = (houses / i.toString()).i() ?: 0; val m = i.toString() in mortgaged
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Loopa.colors.card).border(1.dp, Loopa.colors.outline, RoundedCornerShape(12.dp)).padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(groupColor(b.group)))
                        Spacer(Modifier.width(6.dp))
                        Text(b.name, fontWeight = FontWeight.Bold, color = Loopa.colors.text)
                        Spacer(Modifier.width(6.dp))
                        Text(when { m -> "ipotekli"; h == 5 -> "otel"; h > 0 -> "$h ev"; else -> "" }, style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft)
                    }
                    if (myTurn) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (b.type == "prop" && !m && h < 5) TextButton({ ui.act("build", payload("sq" to i)) {} }) { Text("Ev (₺${b.house})") }
                        if (b.type == "prop" && h > 0) TextButton({ ui.act("sell", payload("sq" to i)) {} }) { Text("Sat") }
                        if (!m && h == 0) TextButton({ ui.act("mortgage", payload("sq" to i)) {} }) { Text("İpotek") }
                        if (m) TextButton({ ui.act("redeem", payload("sq" to i)) {} }) { Text("Kurtar") }
                    }
                }
            }
        }
        SectionTitle("Tahta")
        Column(Modifier.fillMaxWidth().testTag("my_board"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            board.forEachIndexed { i, b ->
                val here = order.filter { (players / it / "pos").i() == i && !(players / it / "out").b() }
                val own = (owner / i.toString()).s()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (here.isNotEmpty()) LoopaPalette.Butter.copy(alpha = 0.5f) else Loopa.colors.card).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (b.group > 0) groupColor(b.group) else Color.Transparent))
                    Spacer(Modifier.width(6.dp))
                    Text("${i}. ${b.name}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Loopa.colors.text)
                    if (b.price > 0 && own == null) Text("₺${b.price}", style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
                    own?.let {
                        val h = (houses / i.toString()).i() ?: 0
                        Text(if (h == 5) "🏨" else "🏠".repeat(h), style = MaterialTheme.typography.labelSmall)
                        Box(Modifier.padding(start = 4.dp).size(12.dp).clip(CircleShape).background(seatColor(ownerIdx(it))))
                    }
                    here.forEach { u -> Box(Modifier.padding(start = 3.dp).size(10.dp).clip(CircleShape).background(seatColor(ownerIdx(u))).border(1.dp, LoopaPalette.Ink, CircleShape)) }
                }
            }
        }
        GameLog((p / "log").a().takeLast(8).mapNotNull { e ->
            val who = ctx.name((e / "u").s())
            when {
                e / "rent" != null -> "$who kira ödedi: ₺${(e / "rent").i()} → ${ctx.name((e / "to").s())}"
                e / "bought" != null -> "$who satın aldı: ${board.getOrNull((e / "bought").i() ?: -1)?.name}"
                e / "card" != null -> "$who kart çekti: ${(e / "card").s()}"
                e / "roll" != null -> "$who zar attı: ${(e / "roll").ints().joinToString("+")}"
                e / "build" != null -> "$who ev dikti: ${board.getOrNull((e / "build").i() ?: -1)?.name}"
                else -> null
            }
        })
        WaitingNote("Oyun parası yalnızca bu maçta geçerlidir; gerçek değeri yoktur. Ev dikmek için rengin tamamına sahip olmalısın.")
    }
}

@Composable
private fun BigCardMini(title: String, sub: String, group: Int) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LoopaPalette.Cream).border(3.dp, groupColor(group), RoundedCornerShape(16.dp)).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink, fontWeight = FontWeight.Bold)
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink)
    }
}
