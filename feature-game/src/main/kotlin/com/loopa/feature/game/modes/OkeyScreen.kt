package com.loopa.feature.game.modes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.game.modes.CardFace
import com.loopa.core.model.ModeState
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette

private fun okeyColor(c: String?): Color = when (c) {
    "r" -> Color(0xFFD32F2F); "y" -> Color(0xFFF9A825); "b" -> Color(0xFF1565C0); "k" -> Color(0xFF212121); else -> Color(0xFF6A1B9A)
}

/**
 * 101 Okey. Tiles are selected on the rack and grouped into melds (or pairs) before opening;
 * the server checks every meld, the 101 points / 5 pairs rule and all penalties.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OkeyScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val p = mode.pub
    val order = (p / "order").strs()
    val turn = (p / "turn").s()
    val stage = (p / "stage").s()
    val over = mode.over
    val okey = (p / "okey")
    val okeyC = (okey / "c").s(); val okeyN = (okey / "n").i()
    val opened = (p / "opened").o()
    val myOpen = (opened / ctx.me).s()
    val hand = (mode.me / "hand").strs()
    val took = (mode.me / "took").s()
    val myTurn = turn == ctx.me && !over && !ctx.isSpectator
    val idx = order.indexOf(ctx.me)
    val prev = if (idx >= 0) order[(idx + 3) % 4] else null
    val prevTop = (p / "discard_tops" / prev.orEmpty()).s()

    val selected = remember(mode.round?.id) { mutableStateListOf<String>() }
    val staged = remember(mode.round?.id) { mutableStateListOf<List<String>>() }
    val stagedIds = staged.flatten().toSet()
    // tiles that left the hand on the server are dropped from the local selection
    androidx.compose.runtime.LaunchedEffect(hand) { selected.retainAll { it in hand }; staged.removeAll { g -> g.any { it !in hand } } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayerChips(ctx, order, turn, value = { u -> "${(p / "counts" / u).i() ?: 0}${if ((opened / u).s() != null) " ✓" else ""}" })
        val winner = (p / "winner").s()
        TurnBanner(ctx, turn, over,
            doneText = if (winner == null) "Taşlar bitti — el kapandı" else if (winner == ctx.me) "Elini bitirdin!" else "${ctx.name(winner)} elini bitirdi",
            extra = if (myTurn) (if (stage == "draw") "Önce taş çek" else "Aç, işle ya da bir taş at") else "Tur ${(p / "round").i() ?: 1}/${(p / "rounds").i() ?: 1} · destede ${(p / "deck_count").i() ?: 0}")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Gösterge", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft)
            OkeyTile((p / "indicator").s().orEmpty(), okeyC, okeyN, small = true)
            Text("Okey", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft)
            Box(Modifier.size(30.dp, 42.dp).clip(RoundedCornerShape(6.dp)).background(LoopaPalette.Cream).border(2.dp, LoopaPalette.ButterDeep, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Text("${okeyN ?: "?"}", color = okeyColor(okeyC), fontWeight = FontWeight.Bold)
            }
        }
        // table
        val table = (p / "table").a()
        if (table.isNotEmpty()) {
            SectionTitle("Masa (işlemek için bir taş seçip pere dokun)")
            table.forEachIndexed { mi, meld ->
                val canAdd = myTurn && stage == "discard" && myOpen != null && selected.size == 1 && (meld / "kind").s() != "pair"
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (canAdd) LoopaPalette.Mint.copy(alpha = 0.4f) else Loopa.colors.card)
                    .clickable(enabled = canAdd && ui.busy == null) { ui.act("add", payload("meld" to mi, "tile" to selected.first())) { selected.clear() } }
                    .padding(6.dp).testTag("ok_meld_$mi"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(ctx.name((meld / "owner").s()).take(6), style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft, modifier = Modifier.padding(end = 4.dp))
                    (meld / "ids").strs().forEach { OkeyTile(it, okeyC, okeyN, small = true) }
                }
            }
        }
        if (!ctx.isSpectator) {
            if (staged.isNotEmpty()) {
                SectionTitle("Hazırlanan perler")
                staged.forEachIndexed { gi, g ->
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(LoopaPalette.Lavender.copy(alpha = 0.4f)).clickable { staged.removeAt(gi) }.padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)) { g.forEach { OkeyTile(it, okeyC, okeyN, small = true) }; Text(" ✕", color = Loopa.colors.textSoft) }
                }
            }
            SectionTitle("Istakan (${hand.size})${if (myOpen != null) " · açtın (${if (myOpen == "pairs") "çift" else "per"})" else ""}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("ok_hand")) {
                hand.filter { it !in stagedIds }.forEach { id ->
                    OkeyTile(id, okeyC, okeyN, selected = id in selected, took = id == took, onClick = { if (id in selected) selected.remove(id) else selected.add(id) })
                }
            }
        }
        if (myTurn) {
            if (stage == "draw") {
                ActionRow {
                    GameButton("Desteden çek", tag = "ok_draw_deck", enabled = ui.busy == null, color = LoopaPalette.Sky) { ui.act("draw", payload("from" to "deck")) {} }
                    if (prevTop != null) GameButton("Yerdekini al", tag = "ok_draw_discard", enabled = ui.busy == null, primary = false) { ui.act("draw", payload("from" to "discard")) {} }
                }
                prevTop?.let { Row(verticalAlignment = Alignment.CenterVertically) { Text("Yerde: ", color = Loopa.colors.textSoft); OkeyTile(it, okeyC, okeyN, small = true) } }
            } else {
                ActionRow {
                    GameButton("Per yap", primary = false, enabled = selected.size >= 3) { staged.add(selected.toList()); selected.clear() }
                    GameButton("Çift yap", primary = false, enabled = selected.size == 2) { staged.add(selected.toList()); selected.clear() }
                }
                val allPairs = staged.isNotEmpty() && staged.all { it.size == 2 }
                ActionRow {
                    if (myOpen == null) {
                        GameButton(if (allPairs) "Çift aç" else "Elini aç", tag = "ok_open", enabled = staged.isNotEmpty() && ui.busy == null, color = LoopaPalette.Mint) {
                            ui.act("open", if (allPairs) payload("pairs" to jsonList(staged.map { jsonStrs(it) })) else payload("melds" to jsonList(staged.map { jsonStrs(it) }))) { staged.clear() }
                        }
                    } else {
                        GameButton("Yere ser", tag = "ok_lay", enabled = staged.isNotEmpty() && ui.busy == null, color = LoopaPalette.Mint) {
                            ui.act("lay", if (myOpen == "pairs") payload("pairs" to jsonList(staged.map { jsonStrs(it) })) else payload("melds" to jsonList(staged.map { jsonStrs(it) }))) { staged.clear() }
                        }
                    }
                    GameButton("Seçili taşı at", tag = "ok_discard", enabled = selected.size == 1 && ui.busy == null, color = LoopaPalette.Peach) {
                        ui.act("discard", payload("tile" to selected.first())) { selected.clear() }
                    }
                }
                if (took != null) GameButton("Aldığın taşı geri ver (+101 ceza)", primary = false, tag = "ok_return") { ui.act("return_taken", payload()) {} }
            }
        }
        (p / "round_scores").o().takeIf { it.isNotEmpty() }?.let { sc ->
            SectionTitle("El sonucu")
            order.forEach { u -> Text("${ctx.name(u)}: ${(sc / u).i() ?: 0}", color = Loopa.colors.text, style = MaterialTheme.typography.bodyMedium) }
        }
        WaitingNote("Açmak için 101 puanlık per ya da 5 çift gerekir. Yerden aldığın taşı aynı el kullanmalısın. Masaya işlenebilecek taşı atmak +101 cezadır.")
    }
}

@Composable
private fun OkeyTile(id: String, okeyC: String?, okeyN: Int?, small: Boolean = false, selected: Boolean = false, took: Boolean = false, onClick: (() -> Unit)? = null) {
    val t = CardFace.okeyTile(id)
    val wild = t.color != null && t.color == okeyC && t.number == okeyN
    val w: Dp = if (small) 24.dp else 34.dp
    Box(
        Modifier.size(w, w * 1.4f).clip(RoundedCornerShape(5.dp))
            .background(if (selected) LoopaPalette.Butter else LoopaPalette.Cream)
            .border(if (selected || took) 2.5.dp else 1.dp, if (took) LoopaPalette.PeachDeep else if (wild) LoopaPalette.ButterDeep else LoopaPalette.Ink.copy(alpha = 0.4f), RoundedCornerShape(5.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.testTag("ok_tile_$id"),
        contentAlignment = Alignment.Center,
    ) {
        if (t.color == null) Text("★", color = okeyColor(null), fontSize = if (small) 11.sp else 16.sp)
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${t.number}", color = okeyColor(t.color), fontWeight = FontWeight.Bold, fontSize = if (small) 11.sp else 16.sp)
            if (wild) Text("OK", fontSize = 7.sp, color = LoopaPalette.ButterDeep, fontWeight = FontWeight.Bold)
        }
    }
}
