package com.loopa.feature.game.modes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.loopa.core.game.modes.AnlatBakalimLogic
import com.loopa.core.game.modes.AnlatPublic
import com.loopa.core.game.modes.AnlatResults
import com.loopa.core.game.modes.AnlatRoundPublic
import com.loopa.core.game.modes.AnlatView
import com.loopa.core.game.modes.meAs
import com.loopa.core.game.modes.publicAs
import com.loopa.core.game.modes.resultsAs
import com.loopa.core.model.ModeState
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val TEAM_COLORS = listOf(LoopaPalette.Sky, LoopaPalette.Peach)

/** Anlat Bakalım: two teams; the narrator explains without the forbidden words, the opponents watch. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnlatBakalimScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val r = mode.round ?: return
    val view = mode.meAs(AnlatView())
    val match = mode.publicAs(AnlatPublic())
    val turn = r.publicAs(AnlatRoundPublic())
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Team banners with scores.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("ab_teams")) {
                match.teams.forEachIndexed { t, members ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(TEAM_COLORS[t].copy(alpha = if (turn.team == t) 1f else 0.45f))
                            .border(if (turn.team == t) 3.dp else 0.dp, LoopaPalette.Ink.copy(alpha = 0.3f), RoundedCornerShape(22.dp)).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(AnlatBakalimLogic.teamName(t), style = MaterialTheme.typography.titleSmall, color = LoopaPalette.Ink)
                        Text("${match.teamScores.getOrElse(t) { 0 }}", style = MaterialTheme.typography.displaySmall, color = LoopaPalette.Ink)
                        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) { members.forEach { AvatarView(ctx.avatar(it), size = 26.dp, showFrame = false) } }
                    }
                }
            }
            if (r.phase == "play") {
                val tc = TEAM_COLORS.getOrElse(turn.team) { LoopaPalette.Lavender }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayerTag(ctx, r.actorId, label = "${ctx.name(r.actorId)} anlatıyor")
                    Spacer(Modifier.weight(1f))
                    Pill("Bu tur: ${turn.turnScore}", tc)
                }
                when (view.role) {
                    "narrator", "watcher" -> view.secret?.let { card ->
                        // The card with the forbidden words.
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color.White)
                                .border(3.dp, tc, RoundedCornerShape(28.dp)).padding(18.dp).testTag("ab_card"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(card.word, style = MaterialTheme.typography.headlineLarge, color = LoopaPalette.Ink)
                            Spacer(Modifier.height(8.dp))
                            Text("YASAKLI", style = MaterialTheme.typography.labelSmall, color = LoopaPalette.Error)
                            card.taboo.forEach { w ->
                                Text(w, style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink.copy(alpha = 0.75f),
                                    textDecoration = TextDecoration.LineThrough)
                            }
                        }
                    }
                }
                when (view.role) {
                    "narrator" -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LoopaButton("Bildi", { ui.act("correct", JsonObject(emptyMap())) { } }, icon = Icons.Rounded.CheckCircle, color = LoopaPalette.Mint,
                            enabled = ctx.isOpen(r), loading = ui.busy == "correct", modifier = Modifier.weight(1f).testTag("ab_correct"))
                        LoopaButton("Pas (${turn.passesLeft})", { ui.act("pass", JsonObject(emptyMap())) { } }, icon = Icons.Rounded.SkipNext, color = LoopaPalette.Butter,
                            enabled = ctx.isOpen(r) && turn.passesLeft > 0, loading = ui.busy == "pass", modifier = Modifier.weight(1f).testTag("ab_pass"))
                    }
                    "watcher" -> {
                        WaitingNote("Rakip anlatıcı yasaklı kelimelerden birini söylerse bas!")
                        LoopaButton("Yasak kelime!", { ui.act("taboo", JsonObject(emptyMap())) { } }, icon = Icons.Rounded.Block, color = LoopaPalette.Error,
                            enabled = ctx.isOpen(r), loading = ui.busy == "taboo", modifier = Modifier.fillMaxWidth().testTag("ab_taboo"))
                    }
                    "guesser" -> {
                        BigCard(tc, Modifier.testTag("ab_guesser")) {
                            Text("Takım arkadaşın anlatıyor", style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink)
                            Text("Bildiğini yaz ya da yüksek sesle söyle; anlatıcı da onaylayabilir.", style = MaterialTheme.typography.bodySmall,
                                color = LoopaPalette.Ink, textAlign = TextAlign.Center)
                        }
                        GuessInput("Tahminin", enabled = ctx.isOpen(r), busy = ui.busy == "guess", accent = tc,
                            onSend = { t -> ui.act("guess", buildJsonObject { put("text", t) }) { res -> if (flagOf(res, "correct")) ui.flash("Doğru! Takımına +1") } })
                    }
                    else -> WaitingNote("İzliyorsun.")
                }
                CardLog(view.feed.map { it.word to it.result })
            } else {
                val res = r.resultsAs(AnlatResults())
                BigCard(TEAM_COLORS.getOrElse(res.team) { LoopaPalette.Lavender }, Modifier.testTag("ab_reveal")) {
                    Text("${AnlatBakalimLogic.teamName(res.team)} bu turda", style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink)
                    Text("${if (res.turnScore > 0) "+" else ""}${res.turnScore}", style = MaterialTheme.typography.displayMedium, color = LoopaPalette.Ink)
                    Spacer(Modifier.height(6.dp))
                    CardLog(res.cards.map { it.word to it.result })
                    Spacer(Modifier.height(8.dp))
                    HostSkip(ctx, true, ui.skip)
                }
            }
        }
        CountdownOverlay(r, ctx.now, "${AnlatBakalimLogic.teamName(turn.team)} hazırlansın")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardLog(items: List<Pair<String, String>>) {
    if (items.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("ab_log")) {
        items.forEach { (word, result) ->
            Pill("$word · ${AnlatBakalimLogic.resultLabel(result)}", when (result) {
                "correct" -> LoopaPalette.Mint; "taboo" -> LoopaPalette.Error.copy(alpha = 0.5f); else -> Loopa.colors.cardHigh
            }, textColor = LoopaPalette.Ink)
        }
    }
    Spacer(Modifier.width(1.dp))
}
