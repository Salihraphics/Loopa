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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.loopa.core.game.modes.BilgiArenasiLogic
import com.loopa.core.game.modes.ModeTiming
import com.loopa.core.game.modes.QuizResults
import com.loopa.core.game.modes.QuizRoundPublic
import com.loopa.core.game.modes.QuizView
import com.loopa.core.game.modes.meAs
import com.loopa.core.game.modes.publicAs
import com.loopa.core.game.modes.resultsAs
import com.loopa.core.model.ModeState
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.components.darken
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val OPTION_COLORS = listOf(LoopaPalette.Lavender, LoopaPalette.Mint, LoopaPalette.Butter, LoopaPalette.Peach)

/** Bilgi Arenası: same question for everyone, four big answer tiles, speed bonus. */
@Composable
fun BilgiArenasiScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val r = mode.round ?: return
    val q = r.publicAs(QuizRoundPublic())
    val view = mode.meAs(QuizView())
    val reveal = r.phase != "answer"
    val res = if (reveal) r.resultsAs(QuizResults()) else null
    val my = view.myChoice ?: res?.answers?.firstOrNull { it.userId == ctx.me }?.choice
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScoreStrip(ctx)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Loopa.colors.card)
                    .border(2.dp, Loopa.colors.outline, RoundedCornerShape(28.dp)).padding(20.dp).testTag("qa_question"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Pill(q.category, LoopaPalette.Sky)
                Spacer(Modifier.height(10.dp))
                Text(q.question, style = MaterialTheme.typography.headlineSmall, color = Loopa.colors.text, textAlign = TextAlign.Center)
            }
            q.options.forEachIndexed { i, opt ->
                val base = OPTION_COLORS[i % 4]
                val isRight = res != null && res.answer == i
                val picked = my == i
                val bg = when {
                    res != null && isRight -> LoopaPalette.Success
                    res != null && picked -> LoopaPalette.Error.copy(alpha = 0.55f)
                    res != null -> base.copy(alpha = 0.35f)
                    picked -> darken(base, 0.85f)
                    my != null -> base.copy(alpha = 0.45f)
                    else -> base
                }
                val enabled = !reveal && my == null && ctx.isOpen(r) && !ctx.isSpectator
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(bg)
                        .border(if (picked) 3.dp else 0.dp, LoopaPalette.Ink.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
                        .clickable(enabled = enabled) { ui.act("answer", buildJsonObject { put("choice", i) }) { } }
                        .padding(16.dp).testTag("qa_option_$i"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                        Text(BilgiArenasiLogic.LETTERS[i % 4], style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(opt, style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink, modifier = Modifier.weight(1f))
                    if (res != null) {
                        val pickers = res.answers.filter { it.choice == i }
                        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) { pickers.forEach { AvatarView(ctx.avatar(it.userId), size = 24.dp, showFrame = false) } }
                    }
                }
            }
            if (!reveal) {
                WaitingNote(if (my != null) "Cevabın kilitlendi. ${view.answered.size}/${ctx.participants.size} kişi cevapladı."
                            else "Doğru cevap ${BilgiArenasiLogic.BASE} puan + hız bonusu (şimdi ${BilgiArenasiLogic.pointsAt(ModeTiming.fractionLeft(r, ctx.now))})")
            } else if (res != null) {
                val mine = res.answers.firstOrNull { it.userId == ctx.me }
                BigCard(LoopaPalette.Butter, Modifier.testTag("qa_reveal")) {
                    Icon(Icons.Rounded.Lightbulb, null, tint = LoopaPalette.Ink)
                    Text(res.explanation, style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(when {
                        mine == null -> "Cevap vermedin."
                        mine.correct -> "Doğru! +${mine.points}"
                        else -> "Bu sefer olmadı."
                    }, style = MaterialTheme.typography.titleLarge, color = LoopaPalette.Ink)
                    Spacer(Modifier.height(8.dp))
                    HostSkip(ctx, true, ui.skip)
                }
            }
        }
        CountdownOverlay(r, ctx.now, "Soru geliyor")
    }
}
