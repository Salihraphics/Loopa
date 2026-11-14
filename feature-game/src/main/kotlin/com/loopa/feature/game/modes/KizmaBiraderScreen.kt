package com.loopa.feature.game.modes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.loopa.core.model.ModeState
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlin.math.cos
import kotlin.math.sin

/**
 * Kızma Birader. The track is a ring of 40 squares; each seat starts at seat*10 and its four
 * home squares lead towards the centre. The server rolls the die and lists the movable pieces.
 */
@Composable
fun KizmaBiraderScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val p = mode.pub
    val order = (p / "order").strs()
    val seats = (p / "seats").o()
    val pieces = (p / "pieces").o()
    val turn = (p / "turn").s()
    val die = (p / "die").i()
    val movable = (p / "movable").ints()
    val over = mode.over
    val myTurn = turn == ctx.me && !over && !ctx.isSpectator
    val mySeat = (mode.me / "seat").i()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayerChips(ctx, order, turn, value = { u -> "${(pieces / u).ints().count { it >= 40 }}/4" }, color = { u -> seatColor((seats / u).i() ?: 0) })
        val winner = (p / "winner").s()
        TurnBanner(ctx, turn, over, doneText = if (winner == ctx.me) "Kazandın!" else "${ctx.name(winner)} kazandı",
            extra = mySeat?.let { "Senin rengin: ${listOf("kırmızı", "mavi", "yeşil", "sarı")[it % 4]}" })
        Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            DieFace(die, size = 56.dp, accent = (seats / turn.orEmpty()).i()?.let { seatColor(it) } ?: LoopaPalette.Cream)
        }
        KbBoard(order.associateWith { ((seats / it).i() ?: 0) to (pieces / it).ints() }, highlight = if (myTurn) movable.map { mySeat to it } else emptyList())
        if (myTurn) {
            if (movable.isEmpty()) {
                GameButton("Devam", tag = "kb_pass", enabled = ui.busy == null) { ui.act("pass", payload()) {} }
                Text("Oynayabileceğin piyon yok.", color = Loopa.colors.textSoft, style = MaterialTheme.typography.bodySmall)
            } else {
                SectionTitle("Hangi piyon?")
                ActionRow {
                    movable.forEach { k -> GameButton("Piyon ${k + 1}", tag = "kb_piece_$k", enabled = ui.busy == null, color = seatColor(mySeat ?: 0)) { ui.act("move", payload("piece" to k)) {} } }
                }
            }
        }
        WaitingNote("6 atan piyon çıkarır ve tekrar atar. Başlangıç kareleri güvenlidir. Eve tam sayıyla girilir.")
    }
}

@Composable
private fun KbBoard(players: Map<String, Pair<Int, List<Int>>>, highlight: List<Pair<Int?, Int>>) {
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).testTag("kb_board")) {
        val c = Offset(size.width / 2, size.height / 2)
        val rTrack = size.minDimension * 0.42f
        val sq = size.minDimension * 0.028f
        fun trackPos(abs: Int): Offset {
            val ang = Math.toRadians(-90.0 + abs * 9.0)
            return Offset(c.x + rTrack * cos(ang).toFloat(), c.y + rTrack * sin(ang).toFloat())
        }
        fun homePos(seat: Int, k: Int): Offset {
            val ang = Math.toRadians(-90.0 + seat * 90.0)
            val rr = rTrack * (0.78f - k * 0.15f)
            return Offset(c.x + rr * cos(ang).toFloat(), c.y + rr * sin(ang).toFloat())
        }
        fun yardPos(seat: Int, k: Int): Offset {
            val ang = Math.toRadians(-90.0 + seat * 90.0 - 22 + k * 6)
            val rr = rTrack * 1.13f
            return Offset(c.x + rr * cos(ang).toFloat(), c.y + rr * sin(ang).toFloat())
        }
        for (i in 0 until 40) {
            val start = i % 10 == 0
            drawCircle(if (start) seatColor(i / 10).copy(alpha = 0.6f) else Color(0xFFEDE7F6), sq, trackPos(i))
            drawCircle(Color(0x66353344), sq, trackPos(i), style = Stroke(1.5f))
        }
        for (s in 0 until 4) for (k in 0 until 4) {
            drawCircle(seatColor(s).copy(alpha = 0.3f), sq, homePos(s, k))
            drawCircle(seatColor(s).copy(alpha = 0.3f), sq * 0.9f, yardPos(s, k))
        }
        players.values.forEach { (seat, prog) ->
            prog.forEachIndexed { k, v ->
                val at = when {
                    v < 0 -> yardPos(seat, k)
                    v >= 40 -> homePos(seat, (v - 40).coerceIn(0, 3))
                    else -> trackPos((seat * 10 + v) % 40)
                }
                val hl = highlight.any { it.first == seat && it.second == k }
                drawCircle(seatColor(seat), sq * 0.8f, at)
                drawCircle(if (hl) Color(0xFF353344) else Color(0x99353344), sq * 0.8f, at, style = Stroke(if (hl) 4f else 2f))
            }
        }
    }
}
