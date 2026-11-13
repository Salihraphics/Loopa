package com.loopa.feature.game.modes

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.loopa.core.game.modes.GizliKelimeLogic
import com.loopa.core.game.modes.GuessResults
import com.loopa.core.game.modes.GuessRoundPublic
import com.loopa.core.game.modes.GuessView
import com.loopa.core.game.modes.meAs
import com.loopa.core.game.modes.publicAs
import com.loopa.core.game.modes.resultsAs
import com.loopa.core.model.ModeState
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Gizli Kelime: the describer reads the word and explains it aloud; the others type guesses. */
@Composable
fun GizliKelimeScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val r = mode.round ?: return
    val view = mode.meAs(GuessView())
    val pub = r.publicAs(GuessRoundPublic())
    val isActor = r.actorId == ctx.me
    val solvedCount = view.feed.count { it.correct }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ScoreStrip(ctx, highlight = r.actorId, badge = { if (it == r.actorId) "🗣" else null })
            when (r.phase) {
                "play" -> if (isActor) {
                    BigCard(LoopaPalette.Lavender, Modifier.testTag("gk_word_card")) {
                        Icon(Icons.Rounded.RecordVoiceOver, null, tint = LoopaPalette.Ink, modifier = Modifier.size(40.dp))
                        Text("Sen anlatıyorsun!", style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink)
                        Spacer(Modifier.height(8.dp))
                        Text(view.secret?.word.orEmpty(), style = MaterialTheme.typography.displaySmall, color = LoopaPalette.Ink, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Pill(pub.category, LoopaPalette.Butter)
                        Spacer(Modifier.height(10.dp))
                        Text("Kelimeyi söylemeden sesli anlat. Herkes bilince tur biter; her doğru tahmin sana +${GizliKelimeLogic.DESCRIBER_BONUS}.",
                            style = MaterialTheme.typography.bodySmall, color = LoopaPalette.Ink.copy(alpha = 0.8f), textAlign = TextAlign.Center)
                    }
                    WaitingNote("$solvedCount kişi bildi")
                } else {
                    BigCard(LoopaPalette.Sky, Modifier.testTag("gk_guess_card")) {
                        PlayerTag(ctx, r.actorId, label = "${ctx.name(r.actorId)} anlatıyor")
                        Spacer(Modifier.height(8.dp))
                        Text("?", style = MaterialTheme.typography.displayLarge, color = LoopaPalette.Ink)
                        Pill(pub.category, LoopaPalette.Butter)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (view.solved) "Bildin! Diğerlerini bekle." else "Sıradaki doğru tahmin: ${GizliKelimeLogic.pointsForNext(solvedCount)} puan",
                            style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink,
                        )
                    }
                    if (!ctx.isSpectator && !view.solved) {
                        GuessInput("Tahminini yaz", enabled = ctx.isOpen(r), busy = ui.busy == "guess",
                            onSend = { t -> ui.act("guess", buildJsonObject { put("text", t) }) { res ->
                                if (flagOf(res, "correct")) ui.flash("Doğru! +${pointsOf(res) ?: 0}")
                            } })
                    }
                }
                else -> GuessReveal(ctx, mode, "Kelime", ui)
            }
            GuessFeed(ctx, view.feed)
        }
        CountdownOverlay(r, ctx.now, if (isActor) "Kelimen geliyor" else "${ctx.name(r.actorId)} anlatacak")
    }
}

/** Reveal shared by the describe-and-guess games: the word and who got it. */
@Composable
fun GuessReveal(ctx: ModeContext, mode: ModeState, label: String, ui: ModeUi) {
    val r = mode.round ?: return
    val res = r.resultsAs(GuessResults())
    BigCard(LoopaPalette.Butter, Modifier.testTag("guess_reveal")) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink)
        Text(res.answer, style = MaterialTheme.typography.displaySmall, color = LoopaPalette.Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        if (res.actorLeft) Text("${ctx.name(r.actorId)} oyundan ayrıldı.", style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink)
        if (res.solved.isEmpty()) Text("Kimse bilemedi.", style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink)
        res.solved.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerTag(ctx, s.userId, size = 28.dp)
                Spacer(Modifier.weight(1f))
                Text("+${s.points}", style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink)
            }
        }
        if (res.actorPoints > 0) Text("${ctx.name(r.actorId)}: +${res.actorPoints}", style = MaterialTheme.typography.labelLarge, color = LoopaPalette.Ink,
            modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(10.dp))
        HostSkip(ctx, true, ui.skip)
    }
    Text("Sıradaki tur birazdan…", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft,
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
}
