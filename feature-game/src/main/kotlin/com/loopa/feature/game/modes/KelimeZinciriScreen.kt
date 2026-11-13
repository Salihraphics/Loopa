package com.loopa.feature.game.modes

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.game.modes.ChainPublic
import com.loopa.core.game.modes.KelimeZinciriLogic
import com.loopa.core.model.ModeState
import com.loopa.core.game.modes.publicAs
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Kelime Zinciri: a growing chain of word bubbles, the next letter in a big circle, lives as hearts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KelimeZinciriScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val r = mode.round ?: return
    val pub = mode.publicAs(ChainPublic())
    val myTurn = r.actorId == ctx.me && r.phase == "turn"
    val used = pub.chain.map { it.word }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Players with their lives.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("wc_lives")) {
                ctx.participants.forEach { u ->
                    val lives = pub.lives[u] ?: 0
                    val turn = u == r.actorId
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(if (turn) LoopaPalette.Butter else if (lives == 0) Loopa.colors.cardHigh else Loopa.colors.card)
                            .border(if (turn) 2.dp else 1.dp, Loopa.colors.outline, RoundedCornerShape(50)).padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarView(ctx.avatar(u), size = 26.dp, showFrame = false)
                        Spacer(Modifier.width(6.dp))
                        Text(ctx.name(u).take(9), style = MaterialTheme.typography.labelMedium, color = Loopa.colors.text)
                        Spacer(Modifier.width(4.dp))
                        Text(if (lives == 0) "elendi" else "❤".repeat(lives) + "♡".repeat((KelimeZinciriLogic.START_LIVES - lives).coerceAtLeast(0)),
                            fontSize = 12.sp, color = LoopaPalette.Error)
                        Spacer(Modifier.width(4.dp))
                        Text("${ctx.points(u)}", style = MaterialTheme.typography.labelLarge, color = Loopa.colors.text)
                    }
                }
            }
            // The chain, newest last.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().animateContentSize().testTag("wc_chain")) {
                pub.chain.takeLast(14).forEachIndexed { i, link ->
                    if (i > 0) Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Loopa.colors.textSoft, modifier = Modifier.size(16.dp).align(Alignment.CenterVertically))
                    Text(link.word, modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (i == pub.chain.takeLast(14).lastIndex) LoopaPalette.Sky else Loopa.colors.card).padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text)
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (myTurn) "Sıra sende!" else "Sıra: ${ctx.name(r.actorId)}", style = MaterialTheme.typography.titleLarge, color = Loopa.colors.text)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.size(120.dp).clip(CircleShape).background(if (myTurn) LoopaPalette.Mint else LoopaPalette.Lavender).testTag("wc_letter"),
                    contentAlignment = Alignment.Center) {
                    Text(KelimeZinciriLogic.letterLabel(pub.letter), style = MaterialTheme.typography.displaySmall, color = LoopaPalette.Ink, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(6.dp))
                Text("\"${pub.word}\" kelimesinin son harfiyle başla", style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft)
            }
            if (myTurn) {
                GuessInput("Yeni kelime", enabled = ctx.isOpen(r), busy = ui.busy == "word", accent = LoopaPalette.Mint, tag = "wc_input", maxLength = 30,
                    hint = { t -> KelimeZinciriLogic.message(KelimeZinciriLogic.precheck(t, pub.letter, used), pub.letter) },
                    onSend = { t ->
                        val local = KelimeZinciriLogic.message(KelimeZinciriLogic.precheck(t, pub.letter, used), pub.letter)
                        if (local != null) ui.showError(local)
                        else ui.act("word", buildJsonObject { put("text", t) }) { res -> if (flagOf(res, "valid")) ui.flash("Harika! +${pointsOf(res) ?: 0}") }
                    })
                WaitingNote("Süre biterse bir can gider. Uzun kelimeler daha çok puan: 10 – 15.")
            } else {
                WaitingNote("Aynı kelime iki kez kullanılamaz; ş/s, ç/c, ğ/g aynı harf sayılır.")
            }
        }
    }
}
