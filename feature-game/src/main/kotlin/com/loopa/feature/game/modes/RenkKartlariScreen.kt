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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.game.modes.CardFace
import com.loopa.core.model.ModeState
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.serialization.json.JsonElement

internal fun renkColor(c: String?): Color = when (c) {
    "r" -> Color(0xFFEF5350); "y" -> Color(0xFFFFCA28); "g" -> Color(0xFF66BB6A); "b" -> Color(0xFF42A5F5); else -> Color(0xFF424242)
}
private fun renkName(c: String?) = when (c) { "r" -> "Kırmızı"; "y" -> "Sarı"; "g" -> "Yeşil"; "b" -> "Mavi"; else -> "—" }

/** Renk Kartları (UNO-style) with the LOOPA cards TEMİZLE, AYNA, GAMBLE, ELİNİ GÖRELİM. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RenkKartlariScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val p = mode.pub
    val order = (p / "order").strs()
    val turn = (p / "turn").s()
    val over = mode.over
    val counts = (p / "counts").o()
    val hand = (mode.me / "hand").a()
    val playable = (mode.me / "playable").strs().toSet()
    val drawn = (mode.me / "drawn").s()
    val pending = (p / "pending").i() ?: 0
    val myTurn = turn == ctx.me && !over && !ctx.isSpectator
    var pickColorFor by remember { mutableStateOf<String?>(null) }
    var sayUno by remember(hand.size) { mutableStateOf(false) }

    fun play(card: JsonElement) {
        val id = (card / "id").s() ?: return
        val black = (card / "c").s() == "k" && (card / "v").s() != "ayna"
        if (black) pickColorFor = id
        else ui.act("play", payload("card" to id, "uno" to (sayUno && hand.size == 2))) {}
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayerChips(ctx, order, turn, value = { u -> "🂠${(counts / u).i() ?: 0}" })
        val winner = (p / "winner").s()
        TurnBanner(ctx, turn, over, doneText = if (winner == ctx.me) "Eli bitirdin! +${(p / "round_points").i() ?: 0}" else "${ctx.name(winner)} eli bitirdi (+${(p / "round_points").i() ?: 0})",
            extra = "Tur ${(p / "round").i() ?: 1}/${(p / "rounds").i() ?: 1} · yön ${if ((p / "dir").i() == -1) "⟲" else "⟳"}")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RenkCard((p / "top"), big = true)
                Text("Renk: ${renkName((p / "color").s())}", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.text)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(70.dp, 100.dp).clip(RoundedCornerShape(12.dp)).background(LoopaPalette.LavenderDeep), contentAlignment = Alignment.Center) {
                    Text("${(p / "deck_count").i() ?: 0}", color = LoopaPalette.Cream, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
                Text("Deste", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.text)
            }
        }
        if (pending > 0) Text("Bekleyen ceza: +$pending kart", color = LoopaPalette.Error, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
        val unoOpen = (p / "uno_open").s()
        if (unoOpen != null && unoOpen != ctx.me && !ctx.isSpectator && !over) {
            GameButton("${ctx.name(unoOpen)} UNO demedi! Yakala", tag = "rk_catch", color = LoopaPalette.Butter) { ui.act("catch", payload("target" to unoOpen)) {} }
        }
        (mode.me / "seen").o().takeIf { it.isNotEmpty() }?.let { seen ->
            SectionTitle("ELİNİ GÖRELİM — ${ctx.name((seen / "of").s())} elinde (yalnız sen görüyorsun):")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { (seen / "hand").a().forEach { RenkCard(it, small = true) } }
        }
        if (!ctx.isSpectator) {
            SectionTitle("Elin (${hand.size})")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("rk_hand")) {
                hand.forEach { c ->
                    val ok = myTurn && (c / "id").s() in playable
                    RenkCard(c, enabled = ok, onClick = if (ok && ui.busy == null) { { play(c) } } else null)
                }
            }
            if (myTurn) {
                if (hand.size == 2) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(sayUno, { sayUno = it }, modifier = Modifier.testTag("rk_uno")); Text("UNO de (sondan bir önceki kart)", color = Loopa.colors.text)
                }
                ActionRow {
                    if (drawn == null) GameButton(if (pending > 0) "Cezayı çek (+$pending)" else "Kart çek", tag = "rk_draw", enabled = ui.busy == null, color = LoopaPalette.Sky) { ui.act("draw", payload()) {} }
                    else GameButton("Pas", tag = "rk_pass", enabled = ui.busy == null, primary = false) { ui.act("pass", payload()) {} }
                }
            }
        }
        if (over) (p / "hands").o().takeIf { it.isNotEmpty() }?.let { hands ->
            order.filter { it != winner }.forEach { u -> Text("${ctx.name(u)}: ${(hands / u).a().size} kart kaldı", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft) }
        }
        WaitingNote("+2 ve +4 üst üste konabilir. AYNA biriken cezayı ilk + kartı atana döndürür. El Joker ile bitirilemez.")
    }
    pickColorFor?.let { id ->
        AlertDialog(onDismissRequest = { pickColorFor = null }, confirmButton = {}, dismissButton = { TextButton({ pickColorFor = null }) { Text("Vazgeç") } },
            title = { Text("Renk seç") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("r", "y", "g", "b").forEach { c ->
                        Box(Modifier.size(52.dp).clip(CircleShape).background(renkColor(c)).border(2.dp, LoopaPalette.Ink, CircleShape)
                            .clickable { pickColorFor = null; ui.act("play", payload("card" to id, "color" to c, "uno" to (sayUno && hand.size == 2))) {} }
                            .testTag("rk_color_$c"))
                    }
                }
            })
    }
}

@Composable
private fun RenkCard(card: JsonElement?, big: Boolean = false, small: Boolean = false, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    val c = (card / "c").s(); val v = (card / "v").s().orEmpty()
    val w = when { big -> 70.dp; small -> 38.dp; else -> 52.dp }
    Box(
        Modifier.size(w, w * 1.42f).alpha(if (enabled || onClick == null && !small) 1f else 0.45f).clip(RoundedCornerShape(10.dp)).background(renkColor(c))
            .border(if (onClick != null) 3.dp else 1.5.dp, if (onClick != null) LoopaPalette.Ink else Color.White.copy(alpha = 0.8f), RoundedCornerShape(10.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.testTag("rk_card_${(card / "id").s()}"),
        contentAlignment = Alignment.Center,
    ) {
        Text(CardFace.renk(v), color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            fontSize = if (v.length > 3) (if (small) 6.sp else 9.sp) else (if (big) 26.sp else if (small) 12.sp else 18.sp), lineHeight = 10.sp)
    }
}

