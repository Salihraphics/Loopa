package com.loopa.feature.game.modes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.loopa.core.game.modes.IkiDogruBirYalanLogic
import com.loopa.core.game.modes.TruthsResults
import com.loopa.core.game.modes.TruthsRoundPublic
import com.loopa.core.game.modes.TruthsView
import com.loopa.core.game.modes.meAs
import com.loopa.core.game.modes.publicAs
import com.loopa.core.game.modes.resultsAs
import com.loopa.core.model.ModeState
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaTextField
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** İki Doğru Bir Yalan: everybody writes three statements, then the room hunts each lie. */
@Composable
fun IkiDogruBirYalanScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val r = mode.round ?: return
    val view = mode.meAs(TruthsView())
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScoreStrip(ctx, highlight = r.actorId)
            when (r.phase) {
                "write" -> WritePhase(ctx, view, ui, ctx.isOpen(r))
                else -> VotePhase(ctx, mode, view, ui)
            }
        }
        CountdownOverlay(r, ctx.now, "${ctx.name(r.actorId)} hakkında oylama")
    }
}

@Composable
private fun WritePhase(ctx: ModeContext, view: TruthsView, ui: ModeUi, open: Boolean) {
    val items = remember { mutableStateListOf("", "", "").also { l -> view.mine?.items?.forEachIndexed { i, s -> if (i < 3) l[i] = s } } }
    var lie by remember { mutableStateOf(view.mine?.lie?.takeIf { it in 0..2 }) }
    var local by remember { mutableStateOf<String?>(null) }
    BigCard(LoopaPalette.Lavender, Modifier.testTag("tl_write")) {
        Text("Kendin hakkında 3 ifade yaz", style = MaterialTheme.typography.titleLarge, color = LoopaPalette.Ink)
        Text("İkisi doğru, biri yalan olsun. Yalanı işaretle; kimse görmeyecek.", style = MaterialTheme.typography.bodySmall,
            color = LoopaPalette.Ink, textAlign = TextAlign.Center)
    }
    if (ctx.isSpectator) { WaitingNote("Oyuncular yazıyor…"); return }
    (0..2).forEach { i ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(lie == i, { lie = i }, colors = RadioButtonDefaults.colors(selectedColor = LoopaPalette.Error), modifier = Modifier.testTag("tl_lie_$i"))
            LoopaTextField(items[i], { items[i] = it.take(IkiDogruBirYalanLogic.MAX_LEN) }, Modifier.weight(1f).testTag("tl_item_$i"),
                placeholder = "${i + 1}. ifade", accent = if (lie == i) LoopaPalette.Error else LoopaPalette.Lavender)
        }
    }
    Text(lie?.let { "${it + 1}. ifade yalan olarak işaretli." } ?: "Yalan olanı soldaki daireden seç.",
        style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
    LoopaButton(if (view.mine != null) "Güncelle" else "Gönder", {
        local = IkiDogruBirYalanLogic.validate(items.toList(), lie)
        if (local == null) ui.act("statements", buildJsonObject {
            put("items", buildJsonArray { items.forEach { add(JsonPrimitive(it.trim())) } }); put("lie", lie!!)
        }) { ui.flash("Kaydedildi") }
    }, icon = Icons.Rounded.Send, enabled = open, loading = ui.busy == "statements", color = LoopaPalette.Mint, modifier = Modifier.fillMaxWidth().testTag("tl_submit"))
    local?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LoopaPalette.PeachDeep) }
    WaitingNote("${view.done.size}/${ctx.participants.size} kişi yazdı" + if (view.mine != null) " · seninki kaydedildi, süre bitene kadar değiştirebilirsin." else "")
}

@Composable
private fun VotePhase(ctx: ModeContext, mode: ModeState, view: TruthsView, ui: ModeUi) {
    val r = mode.round ?: return
    val statements = r.publicAs(TruthsRoundPublic()).statements
    val res = if (r.phase == "reveal") r.resultsAs(TruthsResults()) else null
    val isAuthor = r.actorId == ctx.me
    Row(verticalAlignment = Alignment.CenterVertically) {
        AvatarView(ctx.avatar(r.actorId), size = 56.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(if (isAuthor) "Senin ifadelerin" else "${ctx.name(r.actorId)} diyor ki…", style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text)
            Text(if (res != null) "Yalan ortaya çıktı!" else if (isAuthor) "Arkadaşların yalanı arıyor." else "Hangisi yalan?",
                style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft)
        }
    }
    statements.forEachIndexed { i, st ->
        val isLie = res?.lie == i
        val picked = view.myVote == i
        val votes = res?.votes?.filter { it.choice == i }.orEmpty()
        val bg = when {
            res != null && isLie -> LoopaPalette.Error.copy(alpha = 0.45f)
            res != null -> LoopaPalette.Mint.copy(alpha = 0.55f)
            picked -> LoopaPalette.Lavender
            else -> Loopa.colors.card
        }
        val canVote = res == null && !isAuthor && view.myVote == null && ctx.isOpen(r) && !ctx.isSpectator
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(bg)
                .border(2.dp, Loopa.colors.outline, RoundedCornerShape(22.dp))
                .clickable(enabled = canVote) { ui.act("vote", buildJsonObject { put("choice", i) }) { } }
                .padding(16.dp).testTag("tl_statement_$i"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st, style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text, modifier = Modifier.weight(1f))
                if (res != null) Pill(if (isLie) "YALAN" else "DOĞRU", if (isLie) LoopaPalette.Error else LoopaPalette.Success)
            }
            if (votes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) { votes.forEach { AvatarView(ctx.avatar(it.userId), size = 24.dp, showFrame = false) } }
            }
        }
    }
    if (res != null) {
        BigCard(LoopaPalette.Butter, Modifier.testTag("tl_reveal")) {
            Text("${res.fooled} kişi kandırıldı", style = MaterialTheme.typography.titleLarge, color = LoopaPalette.Ink)
            Text("${ctx.name(r.actorId)} +${res.authorPoints} · bulanlar +${IkiDogruBirYalanLogic.FINDER_POINTS}",
                style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink)
            Spacer(Modifier.height(8.dp))
            HostSkip(ctx, true, ui.skip)
        }
    } else if (!isAuthor) {
        WaitingNote(if (view.myVote != null) "Oyun verildi. Diğerleri bekleniyor…" else "Yalanı bulana +${IkiDogruBirYalanLogic.FINDER_POINTS}; kandırılan her kişi yazana +${IkiDogruBirYalanLogic.FOOL_POINTS}.")
    }
}
