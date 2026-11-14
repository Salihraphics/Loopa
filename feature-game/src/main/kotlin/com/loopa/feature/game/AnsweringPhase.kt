package com.loopa.feature.game

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.AddAlarm
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopa.core.game.TurkishText
import com.loopa.core.game.Timestamps
import com.loopa.core.model.JokerType
import com.loopa.core.model.OwnedJoker
import com.loopa.core.model.RoomState
import com.loopa.core.model.Round
import com.loopa.core.ui.avatar.AvatarView
import com.loopa.core.ui.components.LoopaButton
import com.loopa.core.ui.components.LoopaTextField
import com.loopa.core.ui.components.Pill
import com.loopa.core.ui.components.darken
import com.loopa.core.ui.feedback.Haptic
import com.loopa.core.ui.feedback.LocalFeedback
import com.loopa.core.ui.feedback.Sfx
import com.loopa.core.ui.theme.Loopa
import com.loopa.core.ui.theme.LoopaPalette

fun jokerColor(t: JokerType): Color = when (t) {
    JokerType.EXTRA_TIME -> LoopaPalette.Mint
    JokerType.TIME_THIEF -> LoopaPalette.Lavender
    JokerType.SLOWDOWN -> LoopaPalette.Sky
    JokerType.DOUBLE_POINTS -> LoopaPalette.Butter
}

fun jokerIcon(t: JokerType): ImageVector = when (t) {
    JokerType.EXTRA_TIME -> Icons.Rounded.AddAlarm
    JokerType.TIME_THIEF -> Icons.Rounded.TimerOff
    JokerType.SLOWDOWN -> Icons.Rounded.AcUnit
    JokerType.DOUBLE_POINTS -> Icons.Rounded.WorkspacePremium
}

/** Slot-machine letter reveal before answering opens (server `started_at`). */
@Composable
fun LetterReveal(round: Round, startsInMs: Long) {
    val feedback = LocalFeedback.current
    val landed = startsInMs < 1_600
    val spinIndex = ((System.currentTimeMillis() / 70) % TurkishText.LETTERS.size).toInt()
    val shown = if (landed) round.letter else TurkishText.LETTERS[spinIndex]
    LaunchedEffect(landed) { if (landed) { feedback.play(Sfx.LETTER); feedback.haptic(Haptic.SUCCESS) } }
    val scale by animateFloatAsState(if (landed) 1.15f else 1f, spring(dampingRatio = Spring.DampingRatioHighBouncy), label = "letter")
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Tur ${round.number}", style = MaterialTheme.typography.headlineMedium, color = Loopa.colors.text)
        Spacer(Modifier.height(8.dp))
        Text(if (landed) "Harfin hazır!" else "Harf seçiliyor…", style = MaterialTheme.typography.bodyLarge, color = Loopa.colors.textSoft)
        Spacer(Modifier.height(28.dp))
        LetterBadge(shown, size = 170.dp, modifier = Modifier.scale(scale))
        Spacer(Modifier.height(28.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            round.categories.forEachIndexed { i, c -> Pill(c.name, LoopaPalette.accent(i)) }
        }
        Spacer(Modifier.height(24.dp))
        Text("${(startsInMs + 999) / 1000}", style = MaterialTheme.typography.displayLarge, color = LoopaPalette.LavenderDeep)
    }
}

@Composable
fun AnsweringView(
    state: RoomState,
    round: Round,
    nowMs: Long,
    remainingMs: Long,
    answers: Map<String, String>,
    submitting: Boolean,
    busyJoker: Boolean,
    onAnswer: (String, String) -> Unit,
    onSubmit: () -> Unit,
    onFinalSave: () -> Unit,
    onUseJoker: (OwnedJoker, String?, String?) -> Unit,
) {
    val feedback = LocalFeedback.current
    val focus = LocalFocusManager.current
    val totalMs = ((Timestamps.parse(round.baseDeadline) ?: 0) - (Timestamps.parse(round.startedAt) ?: 0)).coerceAtLeast(1)
    val seconds = ((remainingMs + 999) / 1000).toInt()
    val me = round.player(state.me.userId)
    val myJokers = state.match?.myJokers.orEmpty()
    var jokerDialog by remember { mutableStateOf<OwnedJoker?>(null) }
    var confirmSubmit by remember { mutableStateOf(false) }
    val filled = round.categories.count { !answers[it.key].isNullOrBlank() }

    LaunchedEffect(seconds) {
        when {
            seconds in 1..5 -> { feedback.play(Sfx.TICK); feedback.haptic(Haptic.LIGHT) }
            seconds == 10 -> { feedback.play(Sfx.WARNING); feedback.haptic(Haptic.WARNING) }
        }
    }
    LaunchedEffect(remainingMs < 700) { if (remainingMs < 700) onFinalSave() }

    // While typing on a small screen the keyboard can take half the window: the header shrinks and
    // the player strip / joker bar step aside so the answer fields keep the space (layout only).
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val typing = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    Column(Modifier.fillMaxSize().imePadding().testTag("answering_root")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (typing) 4.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LetterBadge(round.letter, size = if (typing) 44.dp else 72.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${round.letter} harfiyle başlayan", style = MaterialTheme.typography.titleMedium, color = Loopa.colors.text)
                Text("$filled / ${round.categories.size} kategori dolu", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft)
                if (round.firstSubmitterId == null) {
                    Text("İlk bitiren +5 bonus!", style = MaterialTheme.typography.labelSmall, color = darken(LoopaPalette.ButterDeep, 0.75f))
                } else {
                    val who = state.player(round.firstSubmitterId)?.username ?: "Biri"
                    Text("$who bonusu kaptı!", style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
                }
            }
            TimerRing(remainingMs, totalMs, size = if (typing) 48.dp else 74.dp)
        }
        if (!typing) PlayerStrip(state, nowMs)
        if (!typing) JokerBar(myJokers, enabled = me != null && !me.jokerUsed && remainingMs > 0 && !busyJoker, used = me?.jokerUsed == true) { jokerDialog = it }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("answer_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(round.categories, key = { _, c -> c.key }) { i, cat ->
                val value = answers[cat.key].orEmpty()
                val wrongLetter = value.isNotBlank() && !TurkishText.startsWithLetter(value, round.letter)
                val doubled = me?.doubleCategory == cat.key
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(width = 6.dp, height = 46.dp).clip(RoundedCornerShape(50)).background(LoopaPalette.accent(i)),
                    )
                    Spacer(Modifier.width(10.dp))
                    LoopaTextField(
                        value = value,
                        onValueChange = { onAnswer(cat.key, it) },
                        label = cat.name + if (doubled) "  ·  2X" else "",
                        placeholder = "${round.letter}…",
                        modifier = Modifier.weight(1f).testTag("answer_${cat.key}"),
                        isError = wrongLetter,
                        accent = if (doubled) LoopaPalette.ButterDeep else LoopaPalette.accent(i),
                        enabled = remainingMs > 0,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = if (i == round.categories.lastIndex) ImeAction.Done else ImeAction.Next,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { focus.clearFocus() }),
                    )
                }
                if (wrongLetter) {
                    Text(
                        "\"${round.letter}\" harfiyle başlamıyor — geçersiz sayılır.",
                        style = MaterialTheme.typography.labelSmall, color = darken(LoopaPalette.Error, 0.8f),
                        modifier = Modifier.padding(start = 18.dp, top = 2.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        Box(Modifier.fillMaxWidth().background(Loopa.colors.card).padding(horizontal = 16.dp, vertical = if (typing) 6.dp else 16.dp)) {
            LoopaButton(
                if (filled == round.categories.size) "Gönder ve Kilitle" else "Gönder ($filled/${round.categories.size})",
                onClick = { if (filled < round.categories.size) confirmSubmit = true else onSubmit() },
                loading = submitting, enabled = remainingMs > 0, icon = Icons.Rounded.Send,
                color = if (filled == round.categories.size) LoopaPalette.Mint else LoopaPalette.Butter,
                modifier = Modifier.fillMaxWidth().testTag("submit_answers"), sfx = Sfx.SUBMIT, compact = typing,
            )
        }
    }

    jokerDialog?.let { joker ->
        JokerDialog(state, round, joker, nowMs, onDismiss = { jokerDialog = null }) { target, category ->
            jokerDialog = null
            feedback.play(Sfx.JOKER)
            feedback.haptic(Haptic.SUCCESS)
            onUseJoker(joker, target, category)
        }
    }
    if (confirmSubmit) {
        AlertDialog(
            onDismissRequest = { confirmSubmit = false },
            containerColor = Loopa.colors.card,
            title = { Text("Boş kategoriler var", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("Boş kategoriler 0 puan alır ve erken bitirme bonusu yalnızca tüm kategorileri dolduranlara verilir. Yine de gönderilsin mi?") },
            confirmButton = { TextButton(onClick = { confirmSubmit = false; onSubmit() }) { Text("Gönder") } },
            dismissButton = { TextButton(onClick = { confirmSubmit = false }) { Text("Devam et") } },
        )
    }
}

@Composable
private fun JokerBar(jokers: List<OwnedJoker>, enabled: Boolean, used: Boolean, onPick: (OwnedJoker) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Jokerler", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft)
        if (jokers.isEmpty()) {
            Text("Joker yok — tur kazanarak kazan!", style = MaterialTheme.typography.labelSmall, color = Loopa.colors.textSoft)
        }
        jokers.forEach { j ->
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(jokerColor(j.type).copy(alpha = if (enabled) 1f else 0.4f))
                    .border(2.dp, darken(jokerColor(j.type)), RoundedCornerShape(50))
                    .clickable(enabled = enabled) { onPick(j) }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(jokerIcon(j.type), null, tint = LoopaPalette.Ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(j.type.displayName, style = MaterialTheme.typography.labelSmall, color = LoopaPalette.Ink)
            }
        }
        if (used) Pill("Bu tur kullanıldı", Loopa.colors.cardHigh, textColor = Loopa.colors.textSoft)
    }
}

@Composable
private fun JokerDialog(
    state: RoomState,
    round: Round,
    joker: OwnedJoker,
    nowMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (String?, String?) -> Unit,
) {
    var target by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    val type = joker.type
    val alreadySlowed = round.jokerEvents.filter { it.type == JokerType.SLOWDOWN }.mapNotNull { it.targetUserId }.toSet()
    val targets = round.players.filter { it.userId != state.me.userId && !it.submitted }.map { rp ->
        val remaining = ((Timestamps.parse(rp.deadlineAt) ?: 0) - nowMs).coerceAtLeast(0)
        val blocked = when (type) {
            JokerType.SLOWDOWN -> remaining <= JokerType.SLOWDOWN_BLOCKED_FINAL_SECONDS * 1000L || rp.userId in alreadySlowed
            else -> remaining <= 0
        }
        Triple(rp, remaining, blocked)
    }
    val ready = when {
        type.needsTarget -> target != null
        type.needsCategory -> category != null
        else -> true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Loopa.colors.card,
        shape = RoundedCornerShape(28.dp),
        icon = {
            Box(Modifier.size(64.dp).clip(CircleShape).background(jokerColor(type)), contentAlignment = Alignment.Center) {
                Icon(jokerIcon(type), null, tint = LoopaPalette.Ink, modifier = Modifier.size(34.dp))
            }
        },
        title = { Text(type.displayName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(type.description, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (type.needsTarget) {
                    Text("Hedef seç", style = MaterialTheme.typography.titleSmall)
                    if (targets.isEmpty()) Text("Hedeflenebilecek rakip yok.", style = MaterialTheme.typography.bodySmall)
                    targets.forEach { (rp, remaining, blocked) ->
                        val p = state.player(rp.userId)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                .background(if (target == rp.userId) jokerColor(type) else Loopa.colors.cardHigh)
                                .clickable(enabled = !blocked) { target = rp.userId }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AvatarView(p?.avatar, size = 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(p?.username ?: "?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                                color = if (blocked) Loopa.colors.textSoft else Loopa.colors.text)
                            Text(
                                if (blocked && type == JokerType.SLOWDOWN && rp.userId in alreadySlowed) "zaten yavaş" else "${(remaining + 999) / 1000} sn",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                if (type.needsCategory) {
                    Text("Kategori seç", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        round.categories.forEach { c ->
                            com.loopa.core.ui.components.LoopaChip(c.name, category == c.key, { category = c.key }, color = LoopaPalette.Butter)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(target, category) }, enabled = ready) { Text("Kullan", style = MaterialTheme.typography.labelLarge) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

/** After submitting / time up: waiting for the others. */
@Composable
fun WaitingView(state: RoomState, round: Round, nowMs: Long, timeUp: Boolean, bonus: Boolean) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AnimatedContent(timeUp, transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "wait") { up ->
            Box(Modifier.size(110.dp).clip(CircleShape).background(if (up) LoopaPalette.Peach else LoopaPalette.Mint), contentAlignment = Alignment.Center) {
                Icon(if (up) Icons.Rounded.TimerOff else Icons.Rounded.Lock, null, tint = LoopaPalette.Ink, modifier = Modifier.size(56.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(if (timeUp) "Süre doldu!" else "Cevapların kilitlendi", style = MaterialTheme.typography.headlineMedium, color = Loopa.colors.text)
        Spacer(Modifier.height(6.dp))
        Text(
            if (timeUp) "Yazdıkların otomatik kaydedildi. Diğer oyuncular bekleniyor…" else "Diğer oyuncular bitirince cevaplar açıklanacak.",
            style = MaterialTheme.typography.bodyMedium, color = Loopa.colors.textSoft, textAlign = TextAlign.Center,
        )
        if (bonus) {
            Spacer(Modifier.height(14.dp))
            Pill("⚡ İlk bitiren sensin: +5 bonus!", LoopaPalette.Butter)
        }
        Spacer(Modifier.height(24.dp))
        PlayerStrip(state, nowMs)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Harf", style = MaterialTheme.typography.labelMedium, color = Loopa.colors.textSoft)
            Spacer(Modifier.width(8.dp))
            LetterBadge(round.letter, size = 44.dp)
        }
    }
}

@Composable
fun SpectatorAnsweringView(state: RoomState, round: Round, nowMs: Long) {
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Pill("İzleyici modu", LoopaPalette.Sky)
        Spacer(Modifier.height(16.dp))
        Icon(Icons.Rounded.Visibility, null, tint = Loopa.colors.textSoft, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(10.dp))
        LetterBadge(round.letter, size = 120.dp)
        Spacer(Modifier.height(16.dp))
        Text("Oyuncular yazıyor…", style = MaterialTheme.typography.headlineSmall, color = Loopa.colors.text)
        Spacer(Modifier.height(16.dp))
        PlayerStrip(state, nowMs)
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            round.categories.forEachIndexed { i, c -> Pill(c.name, LoopaPalette.accent(i)) }
        }
        Spacer(Modifier.height(16.dp))
        Text("Cevaplar tur sonunda açıklanacak.", style = MaterialTheme.typography.bodySmall, color = Loopa.colors.textSoft, fontSize = 13.sp)
    }
}
