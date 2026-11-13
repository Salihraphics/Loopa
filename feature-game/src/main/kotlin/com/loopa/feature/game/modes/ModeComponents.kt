package com.loopa.feature.game.modes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoFixNormal
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.game.GameModes
import com.loopa.core.game.modes.DrawingCodec
import com.loopa.core.game.modes.GuessFeedItem
import com.loopa.core.game.modes.ModeTiming
import com.loopa.core.game.modes.Stroke
import com.loopa.core.model.ModeRound
import com.loopa.core.model.RoomState
import kotlinx.serialization.json.JsonObject
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.GameIcon
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaTextField
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.components.darken
import com.loopa.core.ui.components.gameVisual
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette

/** Everything a game screen needs from the room. */
data class ModeContext(
    val room: RoomState,
    val now: Long,
    val me: String,
    val isHost: Boolean,
    val isSpectator: Boolean,
) {
    fun name(userId: String?): String = room.players.firstOrNull { it.userId == userId }?.username
        ?: room.match?.scoreboard?.firstOrNull { it.userId == userId }?.username ?: "Oyuncu"
    fun avatar(userId: String?) = room.players.firstOrNull { it.userId == userId }?.avatar
        ?: room.match?.scoreboard?.firstOrNull { it.userId == userId }?.avatar
    val participants: List<String> get() = room.match?.scoreboard?.map { it.userId }.orEmpty()
    fun points(userId: String?): Int = room.match?.scoreboard?.firstOrNull { it.userId == userId }?.totalPoints ?: 0
}

/** Header with the game, progress, the phase timer and a leave button. */
@Composable
fun ModeHeader(modeId: String, progress: String, round: ModeRound?, now: Long, onLeave: () -> Unit, timed: Boolean = true) {
    val visual = gameVisual(modeId)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onLeave) { Icon(Icons.AutoMirrored.Rounded.Logout, "Odadan çık", tint = Loopa.colors.text) }
            GameIcon(modeId, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(GameModes.name(modeId), style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
                Text(progress, style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
            }
            if (timed && round != null && round.deadlineAt != null) {
                val secs = ModeTiming.seconds(ModeTiming.remainingMs(round, now))
                val warn = secs <= 5
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(if (warn) LoopaPalette.Error.copy(alpha = 0.35f) else visual.color)
                        .testTag("mode_timer"),
                    contentAlignment = Alignment.Center,
                ) { Text("$secs", style = MaterialTheme.typography.titleLarge, color = LoopaPalette.Ink) }
            }
        }
        if (timed && round != null && round.deadlineAt != null) {
            val f by animateFloatAsState(ModeTiming.fractionLeft(round, now), tween(300), label = "timer")
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(Loopa.colors.cardHigh)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(50)).background(darken(visual.color, 0.9f)))
            }
        }
    }
}

/** Big 3-2-1 before a round opens. */
@Composable
fun BoxScope.CountdownOverlay(round: ModeRound?, now: Long, title: String) {
    val left = round?.let { ModeTiming.untilStart(it, now) } ?: 0
    AnimatedVisibility(left > 0, Modifier.align(Alignment.Center), enter = fadeIn() + scaleIn(), exit = fadeOut()) {
        Column(
            Modifier.clip(RoundedCornerShape(32.dp)).background(Loopa.colors.card.copy(alpha = 0.96f)).padding(28.dp).testTag("countdown"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Loopa.colors.textSoft, textAlign = TextAlign.Center)
            Text("${ModeTiming.seconds(left)}", style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp), color = Loopa.colors.text)
        }
    }
}

/** Scores of everyone in the match, highest first; [highlight] e.g. the describer. */
@Composable
fun ScoreStrip(ctx: ModeContext, highlight: String? = null, badge: (String) -> String? = { null }) {
    val board = ctx.room.match?.scoreboard.orEmpty()
    LazyRow(Modifier.fillMaxWidth().testTag("score_strip"), horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
        items(board, key = { it.userId }) { e ->
            val mine = e.userId == ctx.me
            Row(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (e.userId == highlight) LoopaPalette.Butter else if (mine) LoopaPalette.Lavender.copy(alpha = 0.6f) else Loopa.colors.card)
                    .border(1.5.dp, Loopa.colors.outline, RoundedCornerShape(50)).padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarView(e.avatar, size = 28.dp, showFrame = false)
                Spacer(Modifier.width(6.dp))
                Text(e.username.take(10), style = MaterialTheme.typography.labelMedium, color = Loopa.colors.text)
                Spacer(Modifier.width(6.dp))
                Text("${e.totalPoints}", style = MaterialTheme.typography.labelLarge, color = Loopa.colors.text)
                badge(e.userId)?.let { Spacer(Modifier.width(4.dp)); Text(it, fontSize = 13.sp) }
            }
        }
    }
}

/** Text input with a send button, used for guesses, words and answers. */
@Composable
fun GuessInput(
    placeholder: String,
    enabled: Boolean,
    busy: Boolean,
    onSend: (String) -> Unit,
    accent: Color = LoopaPalette.Lavender,
    tag: String = "guess_input",
    maxLength: Int = 40,
    hint: (String) -> String? = { null },
) {
    var text by remember { mutableStateOf("") }
    val local = hint(text)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LoopaTextField(
                value = text, onValueChange = { text = it.take(maxLength) }, placeholder = placeholder,
                modifier = Modifier.weight(1f).testTag(tag), enabled = enabled, accent = accent,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank() && enabled) { onSend(text.trim()); text = "" } }),
            )
            Spacer(Modifier.width(8.dp))
            LoopaButton("", { if (text.isNotBlank()) { onSend(text.trim()); text = "" } }, icon = Icons.AutoMirrored.Rounded.Send,
                enabled = enabled && text.isNotBlank(), loading = busy, color = accent, compact = true, modifier = Modifier.testTag("${tag}_send"))
        }
        if (local != null && text.isNotBlank()) {
            Text(local, style = MaterialTheme.typography.bodySmall, color = LoopaPalette.PeachDeep, modifier = Modifier.padding(top = 4.dp, start = 6.dp))
        }
    }
}

/** Chat-like list of guesses. Someone else's correct guess shows only "bildi!". */
@Composable
fun GuessFeed(ctx: ModeContext, feed: List<GuessFeedItem>, modifier: Modifier = Modifier) {
    Column(modifier.testTag("guess_feed"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        feed.takeLast(8).forEach { g ->
            val correct = g.correct
            Row(
                Modifier.clip(RoundedCornerShape(16.dp)).background(if (correct) LoopaPalette.Mint.copy(alpha = 0.7f) else Loopa.colors.card)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ctx.name(g.userId), style = MaterialTheme.typography.labelMedium, color = Loopa.colors.text)
                Spacer(Modifier.width(8.dp))
                if (correct) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = LoopaPalette.MintDeep, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (g.text != null) "${g.text} · +${g.points}" else "bildi! +${g.points}", style = MaterialTheme.typography.bodyMedium, color = LoopaPalette.Ink)
                } else {
                    Text(g.text.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft)
                }
            }
        }
    }
}

/** Host-only button to skip a reveal / showcase. */
@Composable
fun HostSkip(ctx: ModeContext, visible: Boolean, onSkip: () -> Unit) {
    if (ctx.isHost && visible) {
        LoopaButton("Devam", onSkip, icon = Icons.Rounded.SkipNext, color = Color.White, compact = true, modifier = Modifier.testTag("mode_skip"))
    }
}

/** Card used for reveals and secrets. */
@Composable
fun BigCard(color: Color, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(color)
            .border(2.dp, darken(color).copy(alpha = 0.4f), RoundedCornerShape(30.dp)).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
fun PlayerTag(ctx: ModeContext, userId: String?, size: Dp = 36.dp, label: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AvatarView(ctx.avatar(userId), size = size, showFrame = false)
        Spacer(Modifier.width(8.dp))
        Text(label ?: ctx.name(userId), style = MaterialTheme.typography.titleSmall, color = Loopa.colors.text, fontWeight = FontWeight.Bold)
    }
}

// ================================================================== drawing
fun paletteColor(i: Int): Color = Color(DrawingCodec.PALETTE.getOrElse(i) { DrawingCodec.PALETTE[1] })

fun DrawScope.drawStrokes(strokes: List<Stroke>) {
    val w = size.width; val h = size.height
    DrawingCodec.visible(strokes).forEach { s ->
        if (s.points.size < 2) return@forEach
        val color = if (s.eraser) Color.White else paletteColor(s.color)
        val px = s.width * w / 400f
        if (s.points.size == 2) {
            drawCircle(color, px / 2, Offset(s.points[0] * w / 1000f, s.points[1] * h / 1000f))
            return@forEach
        }
        val path = Path()
        path.moveTo(s.points[0] * w / 1000f, s.points[1] * h / 1000f)
        var i = 2
        while (i + 1 < s.points.size) { path.lineTo(s.points[i] * w / 1000f, s.points[i + 1] * h / 1000f); i += 2 }
        drawPath(path, color, style = DrawStroke(width = px, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Read-only drawing (square, white paper). */
@Composable
fun DrawingView(strokes: List<Stroke>, modifier: Modifier = Modifier) {
    Canvas(
        modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Color.White)
            .border(2.dp, Loopa.colors.outline, RoundedCornerShape(24.dp)).testTag("drawing_view"),
    ) { drawStrokes(strokes) }
}

/** Interactive canvas: every finished finger stroke is reported in grid coordinates. */
@Composable
fun DrawingCanvas(strokes: List<Stroke>, color: Int, width: Int, eraser: Boolean, enabled: Boolean, onStroke: (Stroke) -> Unit, modifier: Modifier = Modifier) {
    val current = remember { mutableStateListOf<Int>() }
    var canvasW by remember { mutableStateOf(1f) }
    var canvasH by remember { mutableStateOf(1f) }
    Canvas(
        modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Color.White)
            .border(2.dp, if (enabled) LoopaPalette.LavenderDeep else Loopa.colors.outline, RoundedCornerShape(24.dp))
            .testTag("drawing_canvas")
            .pointerInput(enabled, color, width, eraser) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { o -> current.clear(); current += DrawingCodec.quantize(o.x, canvasW); current += DrawingCodec.quantize(o.y, canvasH) },
                    onDrag = { change, _ ->
                        current += DrawingCodec.quantize(change.position.x, canvasW); current += DrawingCodec.quantize(change.position.y, canvasH)
                    },
                    onDragEnd = {
                        if (current.size >= 2) onStroke(Stroke(if (eraser) 0 else color, width, eraser, DrawingCodec.simplify(current.toList())))
                        current.clear()
                    },
                    onDragCancel = { current.clear() },
                )
            },
    ) {
        canvasW = size.width; canvasH = size.height
        drawStrokes(strokes + listOfNotNull(current.toList().takeIf { it.size >= 2 }?.let { Stroke(if (eraser) 0 else color, width, eraser, it) }))
    }
}

/** Colours, brush sizes, eraser and clear. */
@Composable
fun DrawingToolbar(color: Int, width: Int, eraser: Boolean, onColor: (Int) -> Unit, onWidth: (Int) -> Unit, onEraser: (Boolean) -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("drawing_tools"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items((1 until DrawingCodec.PALETTE.size).toList()) { i ->
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(paletteColor(i))
                        .border(if (!eraser && color == i) 3.dp else 1.dp, if (!eraser && color == i) LoopaPalette.Ink else Loopa.colors.outline, CircleShape)
                        .clickable { onColor(i); onEraser(false) }.testTag("color_$i"),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DrawingCodec.WIDTHS.forEach { w ->
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(if (width == w) LoopaPalette.Lavender else Loopa.colors.card)
                        .border(1.dp, Loopa.colors.outline, CircleShape).clickable { onWidth(w) },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size((4 + w / 2).dp).clip(CircleShape).background(LoopaPalette.Ink)) }
            }
            Spacer(Modifier.weight(1f))
            Pill(if (eraser) "Silgi" else "Kalem", if (eraser) LoopaPalette.Peach else LoopaPalette.Mint)
            IconButton(onClick = { onEraser(!eraser) }, modifier = Modifier.testTag("eraser")) {
                Icon(if (eraser) Icons.Rounded.Brush else Icons.Rounded.AutoFixNormal, if (eraser) "Kalem" else "Silgi", tint = Loopa.colors.text)
            }
            IconButton(onClick = onClear, modifier = Modifier.testTag("clear_canvas")) { Icon(Icons.Rounded.DeleteSweep, "Temizle", tint = Loopa.colors.text) }
        }
    }
}

@Composable
fun WaitingNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(8.dp))
}


/** Green "Doğru! +30" confirmation. */
@Composable
fun FlashBanner(text: String?) {
    AnimatedVisibility(text != null, enter = fadeIn() + scaleIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(text.orEmpty(), modifier = Modifier.clip(RoundedCornerShape(50)).background(LoopaPalette.Mint).padding(horizontal = 18.dp, vertical = 8.dp)
                .testTag("flash"), style = MaterialTheme.typography.titleMedium, color = LoopaPalette.Ink)
        }
    }
}

/** True while players may act in this round (open and before the deadline). */
fun ModeContext.isOpen(round: ModeRound?): Boolean =
    round != null && ModeTiming.untilStart(round, now) == 0L && ModeTiming.remainingMs(round, now) > 0

fun pointsOf(r: JsonObject): Int? = (r["points"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
fun flagOf(r: JsonObject, key: String): Boolean = (r[key] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
