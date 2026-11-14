package com.loopa.feature.game.modes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.loopa.core.model.ModeState
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette

private data class Ship(val x: Int, val y: Int, val len: Int, val h: Boolean) {
    fun cells(): List<Pair<Int, Int>> = (0 until len).map { if (h) (x + it) to y else x to (y + it) }
}

private val Sea = Color(0xFFB3E5FC)
private val ShipC = Color(0xFF78909C)

/** Amiral Battı: hidden placement, then shots. The server answers hit / miss / sunk. */
@Composable
fun AmiralBattiScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val p = mode.pub
    val phase = (p / "phase").s()
    val size = (p / "size").i() ?: 10
    val ships = (p / "ships").ints()
    val teams = (p / "teams").a().map { it.strs() }
    val mySide = (mode.me / "side").i()
    val over = mode.over
    val turn = (p / "turn").s()
    val shots = (p / "shots").a().map { it.o() }
    val placed = (p / "placed").a().map { it.b() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayerChips(ctx, teams.flatten(), turn, value = { u -> if (teams.getOrNull(0)?.contains(u) == true) "A" else "B" },
            color = { u -> if (teams.getOrNull(0)?.contains(u) == true) LoopaPalette.SkyDeep else LoopaPalette.PeachDeep })
        val winSide = (p / "winner_side").i()
        when {
            over -> TurnBanner(ctx, null, true, doneText = if (winSide == mySide) "Filon kazandı!" else "Filon battı")
            phase == "placement" -> TurnBanner(ctx, null, false, doneText = null, extra = "Gemilerini yerleştir. Süre biterse rastgele yerleştirilir.")
            else -> TurnBanner(ctx, turn, false, extra = if ((p / "format").s() == "2v2") "Takım her tur 2 atış yapar (${(p / "shots_left").i() ?: 0} kaldı)" else null)
        }
        if (mySide == null) {
            // spectator: both boards, shots only
            teams.indices.forEach { side -> SectionTitle("Takım ${if (side == 0) "A" else "B"}"); Grid(size, shots.getOrNull(side).o(), emptySet(), sunkCells(p, side), null) }
            return@Column
        }
        if (phase == "placement" && !over) {
            if (placed.getOrElse(mySide) { false }) {
                Text("Filon yerleştirildi. Rakip bekleniyor…", color = Loopa.colors.text)
                Grid(size, null, fleetCells(mode), emptySet(), null)
            } else Placement(size, ships, ui)
            return@Column
        }
        val enemy = 1 - mySide
        val canFire = !over && turn == ctx.me && phase == "battle"
        SectionTitle(if (canFire) "Rakip sular — atış yapmak için dokun" else "Rakip sular")
        Grid(size, shots.getOrNull(enemy).o(), if (over) fleetCellsOf(p, enemy) else emptySet(), sunkCells(p, enemy), if (canFire && ui.busy == null) { x, y ->
            ui.act("fire", payload("x" to x, "y" to y)) { r -> }
        } else null, tag = "ab_enemy")
        SectionTitle("Senin filon")
        Grid(size, shots.getOrNull(mySide).o(), fleetCells(mode), sunkCells(p, mySide), null, tag = "ab_mine")
        GameLog((p / "log").a().takeLast(6).mapNotNull { e ->
            val r = (e / "r").s() ?: return@mapNotNull null
            "${ctx.name((e / "u").s())}: ${('A' + ((e / "x").i() ?: 0))}${((e / "y").i() ?: 0) + 1} → ${when (r) { "hit" -> "isabet"; "sunk" -> "BATTI!"; else -> "karavana" }}"
        })
    }
}

private fun fleetCells(mode: ModeState): Set<Pair<Int, Int>> =
    (mode.me / "fleet").a().flatMap { s -> Ship((s / "x").i() ?: 0, (s / "y").i() ?: 0, (s / "len").i() ?: 0, (s / "dir").s() == "h").cells() }.toSet()

private fun fleetCellsOf(p: kotlinx.serialization.json.JsonObject, side: Int): Set<Pair<Int, Int>> =
    (p / "fleets").a().getOrNull(side).a().flatMap { s -> Ship((s / "x").i() ?: 0, (s / "y").i() ?: 0, (s / "len").i() ?: 0, (s / "dir").s() == "h").cells() }.toSet()

private fun sunkCells(p: kotlinx.serialization.json.JsonObject, side: Int): Set<Pair<Int, Int>> =
    (p / "sunk").a().getOrNull(side).a().flatMap { s -> Ship((s / "x").i() ?: 0, (s / "y").i() ?: 0, (s / "len").i() ?: 0, (s / "dir").s() == "h").cells() }.toSet()

@Composable
private fun Grid(size: Int, shots: kotlinx.serialization.json.JsonObject?, ships: Set<Pair<Int, Int>>, sunk: Set<Pair<Int, Int>>,
                 onCell: ((Int, Int) -> Unit)?, tag: String = "ab_grid", preview: Set<Pair<Int, Int>> = emptySet()) {
    Column(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Sea).border(2.dp, LoopaPalette.SkyDeep, RoundedCornerShape(12.dp))
        .padding(2.dp).testTag(tag)) {
        for (y in 0 until size) Row(Modifier.weight(1f).fillMaxWidth()) {
            for (x in 0 until size) {
                val shot = shots?.get("$x,$y").s()
                val cell = x to y
                val bg = when {
                    cell in sunk -> Color(0xFFB71C1C)
                    shot == "hit" -> Color(0xFFEF5350)
                    cell in preview -> LoopaPalette.Mint
                    cell in ships -> ShipC
                    shot == "miss" -> Color(0xFFE1F5FE)
                    else -> Sea
                }
                Box(Modifier.weight(1f).fillMaxSize().padding(0.5.dp).background(bg)
                    .clickable(enabled = onCell != null && shot == null) { onCell?.invoke(x, y) }, contentAlignment = Alignment.Center) {
                    if (shot == "miss") Text("•", fontSize = 10.sp, color = LoopaPalette.SkyDeep)
                    if (shot == "hit" || cell in sunk) Text("✕", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Placement(size: Int, ships: List<Int>, ui: ModeUi) {
    val placedShips = remember { mutableStateListOf<Ship>() }
    var horizontal by remember { mutableStateOf(true) }
    val next = ships.getOrNull(placedShips.size)
    val occupied = placedShips.flatMap { it.cells() }.toSet()
    Text(if (next != null) "Sıradaki gemi: $next kare. Başlangıç karesine dokun." else "Tüm gemiler hazır.",
        style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.text)
    Grid(size, null, occupied, emptySet(), if (next != null) { x, y ->
        val s = Ship(x, y, next, horizontal)
        val ok = s.cells().all { (cx, cy) -> cx in 0 until size && cy in 0 until size && (cx to cy) !in occupied }
        if (ok) placedShips.add(s) else ui.showError("Gemi oraya sığmıyor.")
    } else null, tag = "ab_place")
    ActionRow {
        GameButton(if (horizontal) "Yatay" else "Dikey", primary = false) { horizontal = !horizontal }
        GameButton("Geri al", primary = false, enabled = placedShips.isNotEmpty()) { placedShips.removeAt(placedShips.lastIndex) }
    }
    ActionRow {
        GameButton("Rastgele", tag = "ab_random", color = LoopaPalette.Sky, enabled = ui.busy == null) { ui.act("place", payload("random" to true)) {} }
        GameButton("Onayla", tag = "ab_confirm", color = LoopaPalette.Mint, enabled = next == null && ui.busy == null) {
            ui.act("place", payload("fleet" to jsonList(placedShips.map { payload("x" to it.x, "y" to it.y, "len" to it.len, "dir" to if (it.h) "h" else "v") }))) {}
        }
    }
}
