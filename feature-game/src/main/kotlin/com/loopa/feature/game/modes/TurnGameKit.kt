package com.loopa.feature.game.modes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.model.ModeState
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// ------------------------------------------------------------------ JSON access (server snapshots)
internal fun JsonElement?.o(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonElement?.a(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()
internal fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
internal fun JsonElement?.i(): Int? = s()?.toDoubleOrNull()?.toInt()
internal fun JsonElement?.d(): Double? = s()?.toDoubleOrNull()
internal fun JsonElement?.b(): Boolean = s() == "true"
internal fun JsonElement?.ints(): List<Int> = a().mapNotNull { it.i() }
internal fun JsonElement?.strs(): List<String> = a().mapNotNull { it.s() }
internal operator fun JsonElement?.div(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** Public part of the current game (mode_rounds.public). */
internal val ModeState.pub: JsonObject get() = round?.public ?: JsonObject(emptyMap())
internal val ModeState.over: Boolean get() = round?.phase == "done"

internal fun payload(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) ->
        when (v) {
            null -> put(k, JsonNull)
            is JsonElement -> put(k, v)
            is Number -> put(k, JsonPrimitive(v))
            is Boolean -> put(k, JsonPrimitive(v))
            else -> put(k, JsonPrimitive(v.toString()))
        }
    }
}

internal fun jsonList(items: List<JsonElement>): JsonArray = JsonArray(items)
internal fun jsonInts(items: List<Int>): JsonArray = JsonArray(items.map { JsonPrimitive(it) })
internal fun jsonStrs(items: List<String>): JsonArray = JsonArray(items.map { JsonPrimitive(it) })

// ------------------------------------------------------------------ shared pieces
/** Big "Sıra sende / Sıra: X" banner. */
@Composable
internal fun TurnBanner(ctx: ModeContext, turn: String?, over: Boolean, doneText: String? = null, extra: String? = null) {
    val mine = turn == ctx.me && !ctx.isSpectator
    val text = when {
        over -> doneText ?: "Oyun bitti"
        turn == null -> "Bekleniyor…"
        mine -> "Sıra sende!"
        else -> "Sıra: ${ctx.name(turn)}"
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(if (mine) LoopaPalette.Mint else if (over) LoopaPalette.Butter else Loopa.colors.card)
            .border(1.5.dp, Loopa.colors.outline, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("turn_banner"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = if (mine || over) LoopaPalette.Ink else Loopa.colors.text, textAlign = TextAlign.Center)
        extra?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (mine || over) LoopaPalette.Ink else Loopa.colors.textSoft, textAlign = TextAlign.Center) }
    }
}

/** Row of players with a value under each (cards in hand, cash, pieces home …). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerChips(ctx: ModeContext, players: List<String>, turn: String?, value: (String) -> String?, color: (String) -> Color? = { null }) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("player_chips")) {
        players.forEach { u ->
            val isTurn = u == turn
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (isTurn) LoopaPalette.Butter else Loopa.colors.card)
                    .border(if (isTurn) 2.dp else 1.dp, color(u) ?: Loopa.colors.outline, RoundedCornerShape(50))
                    .padding(start = 4.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarView(ctx.avatar(u), size = 24.dp, showFrame = false)
                Spacer(Modifier.width(5.dp))
                Text((if (u == ctx.me) "Sen" else ctx.name(u)).take(10), style = MaterialTheme.typography.labelMedium,
                    color = if (isTurn) LoopaPalette.Ink else Loopa.colors.text)
                value(u)?.let {
                    Spacer(Modifier.width(5.dp))
                    Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                        color = if (isTurn) LoopaPalette.Ink else Loopa.colors.text)
                }
            }
        }
    }
}

/** A die face with pips. */
@Composable
internal fun DieFace(value: Int?, size: Dp = 44.dp, used: Boolean = false, accent: Color = LoopaPalette.Cream) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(if (used) accent.copy(alpha = 0.35f) else accent)
            .border(2.dp, LoopaPalette.Ink.copy(alpha = if (used) 0.25f else 0.7f), RoundedCornerShape(size * 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(when (value) { 1 -> "⚀"; 2 -> "⚁"; 3 -> "⚂"; 4 -> "⚃"; 5 -> "⚄"; 6 -> "⚅"; else -> "?" },
            fontSize = (size.value * 0.78f).sp, color = LoopaPalette.Ink.copy(alpha = if (used) 0.35f else 1f))
    }
}

/** Primary / secondary buttons in one row. */
@Composable
internal fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
internal fun GameButton(text: String, enabled: Boolean = true, primary: Boolean = true, tag: String? = null, color: Color = LoopaPalette.Lavender, onClick: () -> Unit) {
    val m = if (tag != null) Modifier.testTag(tag) else Modifier
    if (primary) {
        Button(onClick, enabled = enabled, modifier = m, colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = LoopaPalette.Ink)) {
            Text(text, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(onClick, enabled = enabled, modifier = m, border = BorderStroke(1.5.dp, Loopa.colors.outline)) {
            Text(text, color = Loopa.colors.text)
        }
    }
}

/** Small section title. */
@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Loopa.colors.textSoft)
}

/** Last lines of a game log, already turned into text by the game. */
@Composable
internal fun GameLog(lines: List<String>, max: Int = 6) {
    if (lines.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Loopa.colors.card).padding(12.dp).testTag("game_log"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.takeLast(max).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft) }
    }
}

/** Colour of a seat / side in board games. */
internal fun seatColor(i: Int): Color = when (((i % 4) + 4) % 4) { 0 -> Color(0xFFE57373); 1 -> Color(0xFF64B5F6); 2 -> Color(0xFF81C784); else -> Color(0xFFFFD54F) }

internal fun parseColor(hex: String?, fallback: Color = LoopaPalette.Lavender): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
