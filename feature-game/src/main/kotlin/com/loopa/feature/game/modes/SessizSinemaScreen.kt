package com.loopa.feature.game.modes

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.VoiceOverOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.loopa.core.game.modes.GuessRoundPublic
import com.loopa.core.game.modes.GuessView
import com.loopa.core.game.modes.ModeTiming
import com.loopa.core.game.modes.SessizSinemaLogic
import com.loopa.core.game.modes.meAs
import com.loopa.core.game.modes.publicAs
import com.loopa.core.model.ModeState
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Sessiz Sinema: a stage with a curtain; the actor mimes, the first correct guess wins the round. */
@Composable
fun SessizSinemaScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val r = mode.round ?: return
    val view = mode.meAs(GuessView())
    val pub = r.publicAs(GuessRoundPublic())
    val isActor = r.actorId == ctx.me
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ScoreStrip(ctx, highlight = r.actorId, badge = { if (it == r.actorId) "🎭" else null })
            if (r.phase == "play") {
                // The stage: curtain gradient, the actor in the spotlight.
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp))
                        .background(Brush.verticalGradient(listOf(LoopaPalette.PeachDeep, LoopaPalette.Peach)))
                        .padding(22.dp).testTag("ss_stage"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.TheaterComedy, null, tint = LoopaPalette.Ink, modifier = Modifier.size(44.dp))
                    PlayerTag(ctx, r.actorId, label = if (isActor) "Sahne senin!" else "Sahnede: ${ctx.name(r.actorId)}")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(pub.category, LoopaPalette.Butter)
                        Pill(SessizSinemaLogic.wordCountLabel(pub.words), LoopaPalette.Cream)
                    }
                    if (isActor) {
                        Spacer(Modifier.height(12.dp))
                        Text(view.secret?.word.orEmpty(), style = MaterialTheme.typography.headlineLarge, color = LoopaPalette.Ink,
                            textAlign = TextAlign.Center, modifier = Modifier.testTag("ss_phrase"))
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.VoiceOverOff, null, tint = LoopaPalette.Ink)
                            Spacer(Modifier.width(6.dp))
                            Text("Konuşmak, ses çıkarmak ve harf göstermek yok!", style = MaterialTheme.typography.bodySmall, color = LoopaPalette.Ink)
                        }
                    }
                }
                if (!isActor && !ctx.isSpectator) {
                    val now = SessizSinemaLogic.pointsAt(ModeTiming.fractionLeft(r, ctx.now))
                    WaitingNote("İlk bilen kazanır: şimdi bilirsen +$now")
                    GuessInput("Ne canlandırıyor?", enabled = ctx.isOpen(r), busy = ui.busy == "guess", accent = LoopaPalette.Peach,
                        onSend = { t -> ui.act("guess", buildJsonObject { put("text", t) }) { res ->
                            if (flagOf(res, "correct")) ui.flash("Bravo! +${pointsOf(res) ?: 0}")
                        } })
                } else if (isActor) {
                    WaitingNote("Arkadaşların ilk doğru tahmini yazdığında sahne kapanır; sen de +${SessizSinemaLogic.ACTOR_BONUS} alırsın.")
                }
            } else {
                GuessReveal(ctx, mode, "Canlandırılan", ui)
            }
            GuessFeed(ctx, view.feed)
        }
        CountdownOverlay(r, ctx.now, if (isActor) "Perde açılıyor" else "${ctx.name(r.actorId)} sahneye çıkıyor")
    }
}
