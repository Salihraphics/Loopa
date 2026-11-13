package com.loopa.feature.game.modes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.game.modes.CizTahminLogic
import com.loopa.core.game.modes.GuessRoundPublic
import com.loopa.core.game.modes.GuessView
import com.loopa.core.game.modes.ModeTiming
import com.loopa.core.game.modes.meAs
import com.loopa.core.game.modes.publicAs
import com.loopa.core.model.ModeState
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Çiz ve Tahmin Et: the drawer draws on a shared canvas in real time, the others guess. */
@Composable
fun CizTahminScreen(ctx: ModeContext, mode: ModeState, ui: ModeUi) {
    val r = mode.round ?: return
    val view = mode.meAs(GuessView())
    val pub = r.publicAs(GuessRoundPublic())
    val isDrawer = r.actorId == ctx.me
    var color by remember { mutableIntStateOf(1) }
    var width by remember { mutableIntStateOf(8) }
    var eraser by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScoreStrip(ctx, highlight = r.actorId, badge = { if (it == r.actorId) "✏️" else null })
            if (r.phase == "play") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (isDrawer) {
                        Text("Çiz: ", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.textSoft)
                        Text(view.secret?.word.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = Loopa.colors.text,
                            modifier = Modifier.weight(1f).testTag("ct_word"))
                    } else {
                        Text(CizTahminLogic.hintLabel(pub.hint), style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 2.sp),
                            color = Loopa.colors.text, modifier = Modifier.weight(1f).testTag("ct_hint"))
                    }
                    Pill(pub.category, LoopaPalette.Butter)
                }
                if (isDrawer) {
                    DrawingCanvas(ui.localStrokes, color, width, eraser, enabled = ctx.isOpen(r), onStroke = ui.addStroke)
                    DrawingToolbar(color, width, eraser, { color = it }, { width = it }, { eraser = it }, ui.clearCanvas)
                    WaitingNote("Harf ya da yazı çizme! Her doğru tahmin sana +${CizTahminLogic.DRAWER_BONUS}.")
                } else {
                    DrawingView(ui.remoteStrokes)
                    if (!ctx.isSpectator && !view.solved) {
                        val next = CizTahminLogic.pointsAt(ModeTiming.fractionLeft(r, ctx.now))
                        GuessInput("Ne çiziliyor? (şimdi +$next)", enabled = ctx.isOpen(r), busy = ui.busy == "guess", accent = LoopaPalette.Mint,
                            onSend = { t -> ui.act("guess", buildJsonObject { put("text", t) }) { res ->
                                if (flagOf(res, "correct")) ui.flash("Bildin! +${pointsOf(res) ?: 0}")
                            } })
                    } else if (view.solved) {
                        Text("Bildin! Diğerlerini bekle.", style = MaterialTheme.typography.titleMedium, color = LoopaPalette.MintDeep,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            } else {
                DrawingView(if (isDrawer) ui.localStrokes else ui.remoteStrokes)
                GuessReveal(ctx, mode, "Çizilen", ui)
            }
            GuessFeed(ctx, view.feed)
        }
        CountdownOverlay(r, ctx.now, if (isDrawer) "Kalemini hazırla" else "${ctx.name(r.actorId)} çizecek")
    }
}
