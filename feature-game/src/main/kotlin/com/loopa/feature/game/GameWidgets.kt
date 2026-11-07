package com.loopa.feature.game

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.game.ServerClock
import com.loopa.core.game.Timestamps
import com.loopa.core.model.RoomState
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.darken
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette

/** Server-synchronized "now", re-evaluated every frame (cheap: a long addition). */
@Composable
fun rememberServerNow(clock: ServerClock): State<Long> = produceState(clock.now(), clock) {
    var last = 0L
    while (true) {
        withFrameMillis { }
        val n = clock.now()
        if (n - last >= 50) { value = n; last = n }
    }
}

@Composable
fun LetterBadge(letter: String, size: Dp = 76.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(LoopaPalette.Butter)
            .border(3.dp, LoopaPalette.ButterDeep, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = MaterialTheme.typography.displayMedium.copy(fontSize = (size.value * 0.55f).sp), color = LoopaPalette.Ink)
    }
}

@Composable
fun TimerRing(remainingMs: Long, totalMs: Long, size: Dp = 76.dp, modifier: Modifier = Modifier) {
    val seconds = ((remainingMs + 999) / 1000).toInt()
    val fraction = if (totalMs <= 0) 0f else (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)
    val warning = seconds in 1..10
    val pulse by rememberInfiniteTransition(label = "timer").animateFloat(
        1f, if (warning) 1.08f else 1f, infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "pulse",
    )
    val color = when {
        seconds <= 5 -> LoopaPalette.Error
        seconds <= 10 -> LoopaPalette.PeachDeep
        else -> LoopaPalette.MintDeep
    }
    val track = Loopa.colors.cardHigh
    Box(modifier.size(size).scale(pulse), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.12f
            val inset = stroke / 2
            drawArc(track, 0f, 360f, false, Offset(inset, inset), Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke))
            drawArc(color, -90f, 360f * fraction, false, Offset(inset, inset), Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(
            seconds.toString(), color = if (warning) darken(color, 0.8f) else Loopa.colors.text,
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = (size.value * 0.36f).sp),
        )
    }
}

/** Opponent strip: avatar, submitted check, and their own (individual) remaining time. */
@Composable
fun PlayerStrip(state: RoomState, nowMs: Long, modifier: Modifier = Modifier) {
    val round = state.round ?: return
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        round.players.forEach { rp ->
            val p = state.player(rp.userId)
            val remaining = ((Timestamps.parse(rp.deadlineAt) ?: 0) - nowMs).coerceAtLeast(0)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(58.dp)) {
                Box {
                    AvatarView(p?.avatar, size = 44.dp, modifier = Modifier.clip(CircleShape).then(
                        if (p?.online == false) Modifier.background(Color.Black.copy(alpha = 0.3f)) else Modifier,
                    ))
                    if (rp.submitted) {
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(LoopaPalette.Success),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(13.dp)) }
                    }
                }
                Text(
                    if (rp.userId == state.me.userId) "Sen" else p?.username ?: "?",
                    style = MaterialTheme.typography.labelSmall, color = Loopa.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (rp.submitted) "bitti" else "${(remaining + 999) / 1000} sn",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!rp.submitted && remaining < 10_000) LoopaPalette.PeachDeep else Loopa.colors.textSoft,
                )
            }
        }
    }
}

@Composable
fun OnTick(seconds: Int, onEach: (Int) -> Unit) {
    LaunchedEffect(seconds) { onEach(seconds) }
}
