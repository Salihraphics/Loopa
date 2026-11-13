package com.loopa.feature.game.modes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.game.modes.EnMuhtemelKimLogic
import com.loopa.core.game.modes.LikelyResults
import com.loopa.core.game.modes.LikelyRoundPublic
import com.loopa.core.game.modes.LikelyView
import com.loopa.core.game.modes.meAs
import com.loopa.core.game.modes.publicAs
import com.loopa.core.game.modes.resultsAs
import com.loopa.core.model.ModeState
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** En Muhtemel Kim?: vote for the friend who fits the question best; results as a podium of bars. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnMuhtemelKimScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val r = mode.round ?: return
    val prompt = r.publicAs(LikelyRoundPublic()).prompt
    val view = mode.meAs(LikelyView())
    val res = if (r.phase == "reveal") r.resultsAs(LikelyResults()) else null
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BigCard(LoopaPalette.Lavender, Modifier.testTag("ml_prompt")) {
                Text("En muhtemel kim?", style = MaterialTheme.typography.labelLarge, color = LoopaPalette.Ink.copy(alpha = 0.7f))
                Text(prompt, style = MaterialTheme.typography.headlineSmall, color = LoopaPalette.Ink, textAlign = TextAlign.Center)
            }
            if (res == null) {
                val targets = EnMuhtemelKimLogic.targets(ctx.participants, ctx.me)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    targets.forEach { t ->
                        val picked = view.myVote == t
                        val canVote = view.myVote == null && ctx.isOpen(r) && !ctx.isSpectator
                        Column(
                            Modifier.width(100.dp).clip(RoundedCornerShape(22.dp))
                                .background(if (picked) LoopaPalette.Butter else Loopa.colors.card)
                                .border(if (picked) 3.dp else 1.5.dp, if (picked) LoopaPalette.ButterDeep else Loopa.colors.outline, RoundedCornerShape(22.dp))
                                .clickable(enabled = canVote) { ui.act("vote", buildJsonObject { put("target", t) }) { } }
                                .padding(10.dp).testTag("ml_vote_$t"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AvatarView(ctx.avatar(t), size = 60.dp)
                            Text(ctx.name(t), style = MaterialTheme.typography.labelMedium, color = Loopa.colors.text, textAlign = TextAlign.Center, maxLines = 1)
                        }
                    }
                }
                WaitingNote(if (view.myVote != null) "Oyun gizli. ${view.voted.size}/${ctx.participants.size} kişi oy verdi."
                            else "Kendine oy veremezsin. Odanın seçimine katılırsan +${EnMuhtemelKimLogic.MATCH_POINTS}.")
            } else {
                val total = res.counts.sumOf { it.votes }
                res.counts.sortedByDescending { it.votes }.forEach { c ->
                    val top = c.userId in res.tops
                    val f by animateFloatAsState(EnMuhtemelKimLogic.share(c.votes, total), tween(700), label = "bar")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("ml_bar_${c.userId}")) {
                        AvatarView(ctx.avatar(c.userId), size = 40.dp, showFrame = false)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text((if (top) "👑 " else "") + ctx.name(c.userId), style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
                            Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(50)).background(Loopa.colors.cardHigh)) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(50)).background(if (top) LoopaPalette.ButterDeep else LoopaPalette.Lavender))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("${c.votes}", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
                    }
                }
                val myPick = res.votes.firstOrNull { it.userId == ctx.me }?.target
                BigCard(LoopaPalette.Butter, Modifier.testTag("ml_reveal")) {
                    Text(if (res.tops.isEmpty()) "Kimse oy vermedi." else res.tops.joinToString(", ") { ctx.name(it) } + " seçildi!",
                        style = MaterialTheme.typography.titleLarge, color = LoopaPalette.Ink, textAlign = TextAlign.Center)
                    Text(when {
                        myPick == null -> "Oy vermedin."
                        myPick in res.tops -> "Odayla aynı fikirdesin: +${EnMuhtemelKimLogic.MATCH_POINTS}"
                        else -> "Sen ${ctx.name(myPick)} dedin."
                    }, style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink)
                    Spacer(Modifier.height(8.dp))
                    HostSkip(ctx, true, ui.skip)
                }
            }
            ScoreStrip(ctx)
        }
        CountdownOverlay(r, ctx.now, "Yeni soru")
    }
}
