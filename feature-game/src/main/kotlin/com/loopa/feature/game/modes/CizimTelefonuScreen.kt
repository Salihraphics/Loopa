package com.loopa.feature.game.modes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.loopa.core.game.modes.CizimTelefonuLogic
import com.loopa.core.game.modes.DrawingCodec
import com.loopa.core.game.modes.Stroke
import com.loopa.core.game.modes.TelephoneChainItem
import com.loopa.core.game.modes.TelephoneResults
import com.loopa.core.game.modes.TelephoneRoundPublic
import com.loopa.core.game.modes.TelephoneView
import com.loopa.core.game.modes.meAs
import com.loopa.core.game.modes.publicAs
import com.loopa.core.game.modes.resultsAs
import com.loopa.core.model.ModeState
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Çizim Telefonu: draw what you read, write what you see; at the end every chain is revealed. */
@Composable
fun CizimTelefonuScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val r = mode.round ?: return
    if (r.phase == "showcase" || r.phase == "done") { Showcase(ctx, mode, ui); return }
    val step = r.publicAs(TelephoneRoundPublic())
    val view = mode.meAs(TelephoneView())
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                (1..step.steps).forEach { s ->
                    Box(Modifier.padding(2.dp).size(if (s == step.step) 14.dp else 10.dp).clip(CircleShape)
                        .background(if (s <= step.step) LoopaPalette.ButterDeep else Loopa.colors.cardHigh))
                }
                Spacer(Modifier.weight(1f))
                Pill(if (step.kind == "draw") "Çizme adımı" else "Tahmin adımı", if (step.kind == "draw") LoopaPalette.Mint else LoopaPalette.Sky)
            }
            val task = view.task
            when {
                ctx.isSpectator || task == null -> WaitingNote("Oyuncular zincirlerini tamamlıyor…")
                view.submitted -> {
                    BigCard(LoopaPalette.Mint, Modifier.testTag("tp_submitted")) {
                        Text("Gönderildi!", style = MaterialTheme.typography.headlineSmall, color = LoopaPalette.Ink)
                        Text("${view.done.size}/${ctx.participants.size} kişi hazır", style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink)
                    }
                }
                step.kind == "draw" -> DrawStep(ctx, task.payload, task.kind, ui, ctx.isOpen(r))
                else -> GuessStep(task.payload, task.kind, ui, ctx.isOpen(r))
            }
        }
        CountdownOverlay(r, ctx.now, if (step.kind == "draw") "Çizme zamanı" else "Tahmin zamanı")
    }
}

@Composable
private fun DrawStep(ctx: ModeContext, payload: kotlinx.serialization.json.JsonObject?, from: String, ui: ModeUi, open: Boolean) {
    val strokes = remember { mutableStateListOf<Stroke>() }
    var color by remember { mutableIntStateOf(1) }
    var width by remember { mutableIntStateOf(8) }
    var eraser by remember { mutableStateOf(false) }
    if (from == "draw") {
        // The guess before you is missing: redraw the last drawing of the chain.
        Text("Önceki tahmin boş kaldı; bu çizimi kendi tarzınla yeniden çiz:", style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
        DrawingView(CizimTelefonuLogic.strokes(payload), Modifier.testTag("tp_prompt"))
    } else BigCard(LoopaPalette.Butter, Modifier.testTag("tp_prompt")) {
        Text(if (from == "start") "Başlangıç cümlen" else "Önceki oyuncunun tahmini", style = MaterialTheme.typography.labelLarge, color = LoopaPalette.Ink)
        Text(CizimTelefonuLogic.text(payload).orEmpty(), style = MaterialTheme.typography.headlineSmall, color = LoopaPalette.Ink, textAlign = TextAlign.Center)
    }
    DrawingCanvas(strokes, color, width, eraser, enabled = open, onStroke = { strokes += it })
    DrawingToolbar(color, width, eraser, { color = it }, { width = it }, { eraser = it }, { strokes.clear() })
    LoopaButton("Çizimi gönder", {
        val json = DrawingCodec.encodeAll(DrawingCodec.visible(strokes.toList()))
        ui.act("step", buildJsonObject { put("strokes", json) }) { }
    }, icon = Icons.Rounded.Send, enabled = open, loading = ui.busy == "step", color = LoopaPalette.Mint, modifier = Modifier.fillMaxWidth().testTag("tp_send_drawing"))
    WaitingNote("Yazı ya da harf kullanma; sıradaki oyuncu yalnızca çizimini görecek.")
}

@Composable
private fun GuessStep(payload: kotlinx.serialization.json.JsonObject?, from: String, ui: ModeUi, open: Boolean) {
    Text(CizimTelefonuLogic.instruction("guess"), style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
    if (from == "start") {
        // The drawing before you was skipped: you see the last text in the chain instead.
        BigCard(LoopaPalette.Butter) { Text(CizimTelefonuLogic.text(payload).orEmpty(), style = MaterialTheme.typography.headlineSmall, color = LoopaPalette.Ink) }
    } else if (from == "guess") {
        BigCard(LoopaPalette.Butter) { Text(CizimTelefonuLogic.text(payload).orEmpty(), style = MaterialTheme.typography.headlineSmall, color = LoopaPalette.Ink) }
    } else {
        DrawingView(CizimTelefonuLogic.strokes(payload))
    }
    GuessInput("Bu ne olabilir?", enabled = open, busy = ui.busy == "step", accent = LoopaPalette.Sky, tag = "tp_guess", maxLength = 60,
        onSend = { t -> ui.act("step", buildJsonObject { put("text", t) }) { } })
}

/** All chains, one page each, from the starting phrase to the last step. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Showcase(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val res = mode.round?.resultsAs(TelephoneResults()) ?: TelephoneResults()
    val pager = rememberPagerState { res.chains.size }
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Zincir ${pager.currentPage + 1} / ${res.chains.size}", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
            Spacer(Modifier.weight(1f))
            HostSkip(ctx, true, ui.skip)
        }
        HorizontalPager(pager, Modifier.weight(1f).testTag("tp_showcase")) { page ->
            val chain = res.chains[page]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (chain.matched) Pill("Zincir sağlam! Herkese +${CizimTelefonuLogic.CHAIN_BONUS}", LoopaPalette.Mint)
                chain.items.forEachIndexed { i, item -> ChainItemCard(ctx, item, i) }
            }
        }
    }
}

@Composable
private fun ChainItemCard(ctx: ModeContext, item: TelephoneChainItem, index: Int) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Loopa.colors.card).padding(14.dp).testTag("tp_item_$index")) {
        Text(when (item.kind) { "start" -> "Başlangıç"; "draw" -> "${ctx.name(item.userId)} çizdi"; else -> "${ctx.name(item.userId)} tahmin etti" },
            style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft)
        Spacer(Modifier.height(6.dp))
        when {
            item.missing -> Text("(süre doldu)", style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft)
            item.kind == "draw" -> DrawingView(CizimTelefonuLogic.strokes(item.payload))
            else -> Text(CizimTelefonuLogic.text(item.payload).orEmpty(), style = MaterialTheme.typography.headlineSmall, color = Loopa.colors.text)
        }
    }
}
